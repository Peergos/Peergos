"use strict";
var __extends = (this && this.__extends) || function (d, b) {
    for (var p in b) if (b.hasOwnProperty(p)) d[p] = b[p];
    function __() { this.constructor = d; }
    d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
};
var file_system = require('../core/file_system');
var api_error_1 = require('../core/api_error');
var node_fs_stats_1 = require('../core/node_fs_stats');
var path = require('path');
var Inode = require('../generic/inode');
var preload_file = require('../generic/preload_file');
var ROOT_NODE_ID = "/";
function GenerateRandomID() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
        var r = Math.random() * 16 | 0, v = c == 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}
function noError(e, cb) {
    if (e) {
        cb(e);
        return false;
    }
    return true;
}
function noErrorTx(e, tx, cb) {
    if (e) {
        tx.abort(function () {
            cb(e);
        });
        return false;
    }
    return true;
}
var SimpleSyncRWTransaction = (function () {
    function SimpleSyncRWTransaction(store) {
        this.store = store;
        this.originalData = {};
        this.modifiedKeys = [];
    }
    SimpleSyncRWTransaction.prototype.stashOldValue = function (key, value) {
        if (!this.originalData.hasOwnProperty(key)) {
            this.originalData[key] = value;
        }
    };
    SimpleSyncRWTransaction.prototype.markModified = function (key) {
        if (this.modifiedKeys.indexOf(key) === -1) {
            this.modifiedKeys.push(key);
            if (!this.originalData.hasOwnProperty(key)) {
                this.originalData[key] = this.store.get(key);
            }
        }
    };
    SimpleSyncRWTransaction.prototype.get = function (key) {
        var val = this.store.get(key);
        this.stashOldValue(key, val);
        return val;
    };
    SimpleSyncRWTransaction.prototype.put = function (key, data, overwrite) {
        this.markModified(key);
        return this.store.put(key, data, overwrite);
    };
    SimpleSyncRWTransaction.prototype.del = function (key) {
        this.markModified(key);
        this.store.del(key);
    };
    SimpleSyncRWTransaction.prototype.commit = function () { };
    SimpleSyncRWTransaction.prototype.abort = function () {
        var i, key, value;
        for (i = 0; i < this.modifiedKeys.length; i++) {
            key = this.modifiedKeys[i];
            value = this.originalData[key];
            if (value === null) {
                this.store.del(key);
            }
            else {
                this.store.put(key, value, true);
            }
        }
    };
    return SimpleSyncRWTransaction;
}());
exports.SimpleSyncRWTransaction = SimpleSyncRWTransaction;
var SyncKeyValueFile = (function (_super) {
    __extends(SyncKeyValueFile, _super);
    function SyncKeyValueFile(_fs, _path, _flag, _stat, contents) {
        _super.call(this, _fs, _path, _flag, _stat, contents);
    }
    SyncKeyValueFile.prototype.syncSync = function () {
        if (this.isDirty()) {
            this._fs._syncSync(this.getPath(), this.getBuffer(), this.getStats());
            this.resetDirty();
        }
    };
    SyncKeyValueFile.prototype.closeSync = function () {
        this.syncSync();
    };
    return SyncKeyValueFile;
}(preload_file.PreloadFile));
exports.SyncKeyValueFile = SyncKeyValueFile;
var SyncKeyValueFileSystem = (function (_super) {
    __extends(SyncKeyValueFileSystem, _super);
    function SyncKeyValueFileSystem(options) {
        _super.call(this);
        this.store = options.store;
        this.makeRootDirectory();
    }
    SyncKeyValueFileSystem.isAvailable = function () { return true; };
    SyncKeyValueFileSystem.prototype.getName = function () { return this.store.name(); };
    SyncKeyValueFileSystem.prototype.isReadOnly = function () { return false; };
    SyncKeyValueFileSystem.prototype.supportsSymlinks = function () { return false; };
    SyncKeyValueFileSystem.prototype.supportsProps = function () { return false; };
    SyncKeyValueFileSystem.prototype.supportsSynch = function () { return true; };
    SyncKeyValueFileSystem.prototype.makeRootDirectory = function () {
        var tx = this.store.beginTransaction('readwrite');
        if (tx.get(ROOT_NODE_ID) === undefined) {
            var currTime = (new Date()).getTime(), dirInode = new Inode(GenerateRandomID(), 4096, 511 | node_fs_stats_1.FileType.DIRECTORY, currTime, currTime, currTime);
            tx.put(dirInode.id, new Buffer("{}"), false);
            tx.put(ROOT_NODE_ID, dirInode.toBuffer(), false);
            tx.commit();
        }
    };
    SyncKeyValueFileSystem.prototype._findINode = function (tx, parent, filename) {
        var _this = this;
        var read_directory = function (inode) {
            var dirList = _this.getDirListing(tx, parent, inode);
            if (dirList[filename]) {
                return dirList[filename];
            }
            else {
                throw api_error_1.ApiError.ENOENT(path.resolve(parent, filename));
            }
        };
        if (parent === '/') {
            if (filename === '') {
                return ROOT_NODE_ID;
            }
            else {
                return read_directory(this.getINode(tx, parent, ROOT_NODE_ID));
            }
        }
        else {
            return read_directory(this.getINode(tx, parent + path.sep + filename, this._findINode(tx, path.dirname(parent), path.basename(parent))));
        }
    };
    SyncKeyValueFileSystem.prototype.findINode = function (tx, p) {
        return this.getINode(tx, p, this._findINode(tx, path.dirname(p), path.basename(p)));
    };
    SyncKeyValueFileSystem.prototype.getINode = function (tx, p, id) {
        var inode = tx.get(id);
        if (inode === undefined) {
            throw api_error_1.ApiError.ENOENT(p);
        }
        return Inode.fromBuffer(inode);
    };
    SyncKeyValueFileSystem.prototype.getDirListing = function (tx, p, inode) {
        if (!inode.isDirectory()) {
            throw api_error_1.ApiError.ENOTDIR(p);
        }
        var data = tx.get(inode.id);
        if (data === undefined) {
            throw api_error_1.ApiError.ENOENT(p);
        }
        return JSON.parse(data.toString());
    };
    SyncKeyValueFileSystem.prototype.addNewNode = function (tx, data) {
        var retries = 0, currId;
        while (retries < 5) {
            try {
                currId = GenerateRandomID();
                tx.put(currId, data, false);
                return currId;
            }
            catch (e) {
            }
        }
        throw new api_error_1.ApiError(api_error_1.ErrorCode.EIO, 'Unable to commit data to key-value store.');
    };
    SyncKeyValueFileSystem.prototype.commitNewFile = function (tx, p, type, mode, data) {
        var parentDir = path.dirname(p), fname = path.basename(p), parentNode = this.findINode(tx, parentDir), dirListing = this.getDirListing(tx, parentDir, parentNode), currTime = (new Date()).getTime();
        if (p === '/') {
            throw api_error_1.ApiError.EEXIST(p);
        }
        if (dirListing[fname]) {
            throw api_error_1.ApiError.EEXIST(p);
        }
        try {
            var dataId = this.addNewNode(tx, data), fileNode = new Inode(dataId, data.length, mode | type, currTime, currTime, currTime), fileNodeId = this.addNewNode(tx, fileNode.toBuffer());
            dirListing[fname] = fileNodeId;
            tx.put(parentNode.id, new Buffer(JSON.stringify(dirListing)), true);
        }
        catch (e) {
            tx.abort();
            throw e;
        }
        tx.commit();
        return fileNode;
    };
    SyncKeyValueFileSystem.prototype.empty = function () {
        this.store.clear();
        this.makeRootDirectory();
    };
    SyncKeyValueFileSystem.prototype.renameSync = function (oldPath, newPath) {
        var tx = this.store.beginTransaction('readwrite'), oldParent = path.dirname(oldPath), oldName = path.basename(oldPath), newParent = path.dirname(newPath), newName = path.basename(newPath), oldDirNode = this.findINode(tx, oldParent), oldDirList = this.getDirListing(tx, oldParent, oldDirNode);
        if (!oldDirList[oldName]) {
            throw api_error_1.ApiError.ENOENT(oldPath);
        }
        var nodeId = oldDirList[oldName];
        delete oldDirList[oldName];
        if ((newParent + '/').indexOf(oldPath + '/') === 0) {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EBUSY, oldParent);
        }
        var newDirNode, newDirList;
        if (newParent === oldParent) {
            newDirNode = oldDirNode;
            newDirList = oldDirList;
        }
        else {
            newDirNode = this.findINode(tx, newParent);
            newDirList = this.getDirListing(tx, newParent, newDirNode);
        }
        if (newDirList[newName]) {
            var newNameNode = this.getINode(tx, newPath, newDirList[newName]);
            if (newNameNode.isFile()) {
                try {
                    tx.del(newNameNode.id);
                    tx.del(newDirList[newName]);
                }
                catch (e) {
                    tx.abort();
                    throw e;
                }
            }
            else {
                throw api_error_1.ApiError.EPERM(newPath);
            }
        }
        newDirList[newName] = nodeId;
        try {
            tx.put(oldDirNode.id, new Buffer(JSON.stringify(oldDirList)), true);
            tx.put(newDirNode.id, new Buffer(JSON.stringify(newDirList)), true);
        }
        catch (e) {
            tx.abort();
            throw e;
        }
        tx.commit();
    };
    SyncKeyValueFileSystem.prototype.statSync = function (p, isLstat) {
        return this.findINode(this.store.beginTransaction('readonly'), p).toStats();
    };
    SyncKeyValueFileSystem.prototype.createFileSync = function (p, flag, mode) {
        var tx = this.store.beginTransaction('readwrite'), data = new Buffer(0), newFile = this.commitNewFile(tx, p, node_fs_stats_1.FileType.FILE, mode, data);
        return new SyncKeyValueFile(this, p, flag, newFile.toStats(), data);
    };
    SyncKeyValueFileSystem.prototype.openFileSync = function (p, flag) {
        var tx = this.store.beginTransaction('readonly'), node = this.findINode(tx, p), data = tx.get(node.id);
        if (data === undefined) {
            throw api_error_1.ApiError.ENOENT(p);
        }
        return new SyncKeyValueFile(this, p, flag, node.toStats(), data);
    };
    SyncKeyValueFileSystem.prototype.removeEntry = function (p, isDir) {
        var tx = this.store.beginTransaction('readwrite'), parent = path.dirname(p), parentNode = this.findINode(tx, parent), parentListing = this.getDirListing(tx, parent, parentNode), fileName = path.basename(p);
        if (!parentListing[fileName]) {
            throw api_error_1.ApiError.ENOENT(p);
        }
        var fileNodeId = parentListing[fileName];
        delete parentListing[fileName];
        var fileNode = this.getINode(tx, p, fileNodeId);
        if (!isDir && fileNode.isDirectory()) {
            throw api_error_1.ApiError.EISDIR(p);
        }
        else if (isDir && !fileNode.isDirectory()) {
            throw api_error_1.ApiError.ENOTDIR(p);
        }
        try {
            tx.del(fileNode.id);
            tx.del(fileNodeId);
            tx.put(parentNode.id, new Buffer(JSON.stringify(parentListing)), true);
        }
        catch (e) {
            tx.abort();
            throw e;
        }
        tx.commit();
    };
    SyncKeyValueFileSystem.prototype.unlinkSync = function (p) {
        this.removeEntry(p, false);
    };
    SyncKeyValueFileSystem.prototype.rmdirSync = function (p) {
        if (this.readdirSync(p).length > 0) {
            throw api_error_1.ApiError.ENOTEMPTY(p);
        }
        else {
            this.removeEntry(p, true);
        }
    };
    SyncKeyValueFileSystem.prototype.mkdirSync = function (p, mode) {
        var tx = this.store.beginTransaction('readwrite'), data = new Buffer('{}');
        this.commitNewFile(tx, p, node_fs_stats_1.FileType.DIRECTORY, mode, data);
    };
    SyncKeyValueFileSystem.prototype.readdirSync = function (p) {
        var tx = this.store.beginTransaction('readonly');
        return Object.keys(this.getDirListing(tx, p, this.findINode(tx, p)));
    };
    SyncKeyValueFileSystem.prototype._syncSync = function (p, data, stats) {
        var tx = this.store.beginTransaction('readwrite'), fileInodeId = this._findINode(tx, path.dirname(p), path.basename(p)), fileInode = this.getINode(tx, p, fileInodeId), inodeChanged = fileInode.update(stats);
        try {
            tx.put(fileInode.id, data, true);
            if (inodeChanged) {
                tx.put(fileInodeId, fileInode.toBuffer(), true);
            }
        }
        catch (e) {
            tx.abort();
            throw e;
        }
        tx.commit();
    };
    return SyncKeyValueFileSystem;
}(file_system.SynchronousFileSystem));
exports.SyncKeyValueFileSystem = SyncKeyValueFileSystem;
var AsyncKeyValueFile = (function (_super) {
    __extends(AsyncKeyValueFile, _super);
    function AsyncKeyValueFile(_fs, _path, _flag, _stat, contents) {
        _super.call(this, _fs, _path, _flag, _stat, contents);
    }
    AsyncKeyValueFile.prototype.sync = function (cb) {
        var _this = this;
        if (this.isDirty()) {
            this._fs._sync(this.getPath(), this.getBuffer(), this.getStats(), function (e) {
                if (!e) {
                    _this.resetDirty();
                }
                cb(e);
            });
        }
        else {
            cb();
        }
    };
    AsyncKeyValueFile.prototype.close = function (cb) {
        this.sync(cb);
    };
    return AsyncKeyValueFile;
}(preload_file.PreloadFile));
exports.AsyncKeyValueFile = AsyncKeyValueFile;
var AsyncKeyValueFileSystem = (function (_super) {
    __extends(AsyncKeyValueFileSystem, _super);
    function AsyncKeyValueFileSystem() {
        _super.apply(this, arguments);
    }
    AsyncKeyValueFileSystem.prototype.init = function (store, cb) {
        this.store = store;
        this.makeRootDirectory(cb);
    };
    AsyncKeyValueFileSystem.isAvailable = function () { return true; };
    AsyncKeyValueFileSystem.prototype.getName = function () { return this.store.name(); };
    AsyncKeyValueFileSystem.prototype.isReadOnly = function () { return false; };
    AsyncKeyValueFileSystem.prototype.supportsSymlinks = function () { return false; };
    AsyncKeyValueFileSystem.prototype.supportsProps = function () { return false; };
    AsyncKeyValueFileSystem.prototype.supportsSynch = function () { return false; };
    AsyncKeyValueFileSystem.prototype.makeRootDirectory = function (cb) {
        var tx = this.store.beginTransaction('readwrite');
        tx.get(ROOT_NODE_ID, function (e, data) {
            if (e || data === undefined) {
                var currTime = (new Date()).getTime(), dirInode = new Inode(GenerateRandomID(), 4096, 511 | node_fs_stats_1.FileType.DIRECTORY, currTime, currTime, currTime);
                tx.put(dirInode.id, new Buffer("{}"), false, function (e) {
                    if (noErrorTx(e, tx, cb)) {
                        tx.put(ROOT_NODE_ID, dirInode.toBuffer(), false, function (e) {
                            if (e) {
                                tx.abort(function () { cb(e); });
                            }
                            else {
                                tx.commit(cb);
                            }
                        });
                    }
                });
            }
            else {
                tx.commit(cb);
            }
        });
    };
    AsyncKeyValueFileSystem.prototype._findINode = function (tx, parent, filename, cb) {
        var _this = this;
        var handle_directory_listings = function (e, inode, dirList) {
            if (e) {
                cb(e);
            }
            else if (dirList[filename]) {
                cb(null, dirList[filename]);
            }
            else {
                cb(api_error_1.ApiError.ENOENT(path.resolve(parent, filename)));
            }
        };
        if (parent === '/') {
            if (filename === '') {
                cb(null, ROOT_NODE_ID);
            }
            else {
                this.getINode(tx, parent, ROOT_NODE_ID, function (e, inode) {
                    if (noError(e, cb)) {
                        _this.getDirListing(tx, parent, inode, function (e, dirList) {
                            handle_directory_listings(e, inode, dirList);
                        });
                    }
                });
            }
        }
        else {
            this.findINodeAndDirListing(tx, parent, handle_directory_listings);
        }
    };
    AsyncKeyValueFileSystem.prototype.findINode = function (tx, p, cb) {
        var _this = this;
        this._findINode(tx, path.dirname(p), path.basename(p), function (e, id) {
            if (noError(e, cb)) {
                _this.getINode(tx, p, id, cb);
            }
        });
    };
    AsyncKeyValueFileSystem.prototype.getINode = function (tx, p, id, cb) {
        tx.get(id, function (e, data) {
            if (noError(e, cb)) {
                if (data === undefined) {
                    cb(api_error_1.ApiError.ENOENT(p));
                }
                else {
                    cb(null, Inode.fromBuffer(data));
                }
            }
        });
    };
    AsyncKeyValueFileSystem.prototype.getDirListing = function (tx, p, inode, cb) {
        if (!inode.isDirectory()) {
            cb(api_error_1.ApiError.ENOTDIR(p));
        }
        else {
            tx.get(inode.id, function (e, data) {
                if (noError(e, cb)) {
                    try {
                        cb(null, JSON.parse(data.toString()));
                    }
                    catch (e) {
                        cb(api_error_1.ApiError.ENOENT(p));
                    }
                }
            });
        }
    };
    AsyncKeyValueFileSystem.prototype.findINodeAndDirListing = function (tx, p, cb) {
        var _this = this;
        this.findINode(tx, p, function (e, inode) {
            if (noError(e, cb)) {
                _this.getDirListing(tx, p, inode, function (e, listing) {
                    if (noError(e, cb)) {
                        cb(null, inode, listing);
                    }
                });
            }
        });
    };
    AsyncKeyValueFileSystem.prototype.addNewNode = function (tx, data, cb) {
        var retries = 0, currId, reroll = function () {
            if (++retries === 5) {
                cb(new api_error_1.ApiError(api_error_1.ErrorCode.EIO, 'Unable to commit data to key-value store.'));
            }
            else {
                currId = GenerateRandomID();
                tx.put(currId, data, false, function (e, committed) {
                    if (e || !committed) {
                        reroll();
                    }
                    else {
                        cb(null, currId);
                    }
                });
            }
        };
        reroll();
    };
    AsyncKeyValueFileSystem.prototype.commitNewFile = function (tx, p, type, mode, data, cb) {
        var _this = this;
        var parentDir = path.dirname(p), fname = path.basename(p), currTime = (new Date()).getTime();
        if (p === '/') {
            return cb(api_error_1.ApiError.EEXIST(p));
        }
        this.findINodeAndDirListing(tx, parentDir, function (e, parentNode, dirListing) {
            if (noErrorTx(e, tx, cb)) {
                if (dirListing[fname]) {
                    tx.abort(function () {
                        cb(api_error_1.ApiError.EEXIST(p));
                    });
                }
                else {
                    _this.addNewNode(tx, data, function (e, dataId) {
                        if (noErrorTx(e, tx, cb)) {
                            var fileInode = new Inode(dataId, data.length, mode | type, currTime, currTime, currTime);
                            _this.addNewNode(tx, fileInode.toBuffer(), function (e, fileInodeId) {
                                if (noErrorTx(e, tx, cb)) {
                                    dirListing[fname] = fileInodeId;
                                    tx.put(parentNode.id, new Buffer(JSON.stringify(dirListing)), true, function (e) {
                                        if (noErrorTx(e, tx, cb)) {
                                            tx.commit(function (e) {
                                                if (noErrorTx(e, tx, cb)) {
                                                    cb(null, fileInode);
                                                }
                                            });
                                        }
                                    });
                                }
                            });
                        }
                    });
                }
            }
        });
    };
    AsyncKeyValueFileSystem.prototype.empty = function (cb) {
        var _this = this;
        this.store.clear(function (e) {
            if (noError(e, cb)) {
                _this.makeRootDirectory(cb);
            }
        });
    };
    AsyncKeyValueFileSystem.prototype.rename = function (oldPath, newPath, cb) {
        var _this = this;
        var tx = this.store.beginTransaction('readwrite'), oldParent = path.dirname(oldPath), oldName = path.basename(oldPath), newParent = path.dirname(newPath), newName = path.basename(newPath), inodes = {}, lists = {}, errorOccurred = false;
        if ((newParent + '/').indexOf(oldPath + '/') === 0) {
            return cb(new api_error_1.ApiError(api_error_1.ErrorCode.EBUSY, oldParent));
        }
        var theOleSwitcharoo = function () {
            if (errorOccurred || !lists.hasOwnProperty(oldParent) || !lists.hasOwnProperty(newParent)) {
                return;
            }
            var oldParentList = lists[oldParent], oldParentINode = inodes[oldParent], newParentList = lists[newParent], newParentINode = inodes[newParent];
            if (!oldParentList[oldName]) {
                cb(api_error_1.ApiError.ENOENT(oldPath));
            }
            else {
                var fileId = oldParentList[oldName];
                delete oldParentList[oldName];
                var completeRename = function () {
                    newParentList[newName] = fileId;
                    tx.put(oldParentINode.id, new Buffer(JSON.stringify(oldParentList)), true, function (e) {
                        if (noErrorTx(e, tx, cb)) {
                            if (oldParent === newParent) {
                                tx.commit(cb);
                            }
                            else {
                                tx.put(newParentINode.id, new Buffer(JSON.stringify(newParentList)), true, function (e) {
                                    if (noErrorTx(e, tx, cb)) {
                                        tx.commit(cb);
                                    }
                                });
                            }
                        }
                    });
                };
                if (newParentList[newName]) {
                    _this.getINode(tx, newPath, newParentList[newName], function (e, inode) {
                        if (noErrorTx(e, tx, cb)) {
                            if (inode.isFile()) {
                                tx.del(inode.id, function (e) {
                                    if (noErrorTx(e, tx, cb)) {
                                        tx.del(newParentList[newName], function (e) {
                                            if (noErrorTx(e, tx, cb)) {
                                                completeRename();
                                            }
                                        });
                                    }
                                });
                            }
                            else {
                                tx.abort(function (e) {
                                    cb(api_error_1.ApiError.EPERM(newPath));
                                });
                            }
                        }
                    });
                }
                else {
                    completeRename();
                }
            }
        };
        var processInodeAndListings = function (p) {
            _this.findINodeAndDirListing(tx, p, function (e, node, dirList) {
                if (e) {
                    if (!errorOccurred) {
                        errorOccurred = true;
                        tx.abort(function () {
                            cb(e);
                        });
                    }
                }
                else {
                    inodes[p] = node;
                    lists[p] = dirList;
                    theOleSwitcharoo();
                }
            });
        };
        processInodeAndListings(oldParent);
        if (oldParent !== newParent) {
            processInodeAndListings(newParent);
        }
    };
    AsyncKeyValueFileSystem.prototype.stat = function (p, isLstat, cb) {
        var tx = this.store.beginTransaction('readonly');
        this.findINode(tx, p, function (e, inode) {
            if (noError(e, cb)) {
                cb(null, inode.toStats());
            }
        });
    };
    AsyncKeyValueFileSystem.prototype.createFile = function (p, flag, mode, cb) {
        var _this = this;
        var tx = this.store.beginTransaction('readwrite'), data = new Buffer(0);
        this.commitNewFile(tx, p, node_fs_stats_1.FileType.FILE, mode, data, function (e, newFile) {
            if (noError(e, cb)) {
                cb(null, new AsyncKeyValueFile(_this, p, flag, newFile.toStats(), data));
            }
        });
    };
    AsyncKeyValueFileSystem.prototype.openFile = function (p, flag, cb) {
        var _this = this;
        var tx = this.store.beginTransaction('readonly');
        this.findINode(tx, p, function (e, inode) {
            if (noError(e, cb)) {
                tx.get(inode.id, function (e, data) {
                    if (noError(e, cb)) {
                        if (data === undefined) {
                            cb(api_error_1.ApiError.ENOENT(p));
                        }
                        else {
                            cb(null, new AsyncKeyValueFile(_this, p, flag, inode.toStats(), data));
                        }
                    }
                });
            }
        });
    };
    AsyncKeyValueFileSystem.prototype.removeEntry = function (p, isDir, cb) {
        var _this = this;
        var tx = this.store.beginTransaction('readwrite'), parent = path.dirname(p), fileName = path.basename(p);
        this.findINodeAndDirListing(tx, parent, function (e, parentNode, parentListing) {
            if (noErrorTx(e, tx, cb)) {
                if (!parentListing[fileName]) {
                    tx.abort(function () {
                        cb(api_error_1.ApiError.ENOENT(p));
                    });
                }
                else {
                    var fileNodeId = parentListing[fileName];
                    delete parentListing[fileName];
                    _this.getINode(tx, p, fileNodeId, function (e, fileNode) {
                        if (noErrorTx(e, tx, cb)) {
                            if (!isDir && fileNode.isDirectory()) {
                                tx.abort(function () {
                                    cb(api_error_1.ApiError.EISDIR(p));
                                });
                            }
                            else if (isDir && !fileNode.isDirectory()) {
                                tx.abort(function () {
                                    cb(api_error_1.ApiError.ENOTDIR(p));
                                });
                            }
                            else {
                                tx.del(fileNode.id, function (e) {
                                    if (noErrorTx(e, tx, cb)) {
                                        tx.del(fileNodeId, function (e) {
                                            if (noErrorTx(e, tx, cb)) {
                                                tx.put(parentNode.id, new Buffer(JSON.stringify(parentListing)), true, function (e) {
                                                    if (noErrorTx(e, tx, cb)) {
                                                        tx.commit(cb);
                                                    }
                                                });
                                            }
                                        });
                                    }
                                });
                            }
                        }
                    });
                }
            }
        });
    };
    AsyncKeyValueFileSystem.prototype.unlink = function (p, cb) {
        this.removeEntry(p, false, cb);
    };
    AsyncKeyValueFileSystem.prototype.rmdir = function (p, cb) {
        var _this = this;
        this.readdir(p, function (err, files) {
            if (err) {
                cb(err);
            }
            else if (files.length > 0) {
                cb(api_error_1.ApiError.ENOTEMPTY(p));
            }
            else {
                _this.removeEntry(p, true, cb);
            }
        });
    };
    AsyncKeyValueFileSystem.prototype.mkdir = function (p, mode, cb) {
        var tx = this.store.beginTransaction('readwrite'), data = new Buffer('{}');
        this.commitNewFile(tx, p, node_fs_stats_1.FileType.DIRECTORY, mode, data, cb);
    };
    AsyncKeyValueFileSystem.prototype.readdir = function (p, cb) {
        var _this = this;
        var tx = this.store.beginTransaction('readonly');
        this.findINode(tx, p, function (e, inode) {
            if (noError(e, cb)) {
                _this.getDirListing(tx, p, inode, function (e, dirListing) {
                    if (noError(e, cb)) {
                        cb(null, Object.keys(dirListing));
                    }
                });
            }
        });
    };
    AsyncKeyValueFileSystem.prototype._sync = function (p, data, stats, cb) {
        var _this = this;
        var tx = this.store.beginTransaction('readwrite');
        this._findINode(tx, path.dirname(p), path.basename(p), function (e, fileInodeId) {
            if (noErrorTx(e, tx, cb)) {
                _this.getINode(tx, p, fileInodeId, function (e, fileInode) {
                    if (noErrorTx(e, tx, cb)) {
                        var inodeChanged = fileInode.update(stats);
                        tx.put(fileInode.id, data, true, function (e) {
                            if (noErrorTx(e, tx, cb)) {
                                if (inodeChanged) {
                                    tx.put(fileInodeId, fileInode.toBuffer(), true, function (e) {
                                        if (noErrorTx(e, tx, cb)) {
                                            tx.commit(cb);
                                        }
                                    });
                                }
                                else {
                                    tx.commit(cb);
                                }
                            }
                        });
                    }
                });
            }
        });
    };
    return AsyncKeyValueFileSystem;
}(file_system.BaseFileSystem));
exports.AsyncKeyValueFileSystem = AsyncKeyValueFileSystem;
//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJmaWxlIjoia2V5X3ZhbHVlX2ZpbGVzeXN0ZW0uanMiLCJzb3VyY2VSb290IjoiIiwic291cmNlcyI6WyIuLi8uLi8uLi9zcmMvZ2VuZXJpYy9rZXlfdmFsdWVfZmlsZXN5c3RlbS50cyJdLCJuYW1lcyI6W10sIm1hcHBpbmdzIjoiOzs7Ozs7QUFBQSxJQUFPLFdBQVcsV0FBVyxxQkFBcUIsQ0FBQyxDQUFDO0FBQ3BELDBCQUFrQyxtQkFBbUIsQ0FBQyxDQUFBO0FBQ3RELDhCQUF5Qyx1QkFBdUIsQ0FBQyxDQUFBO0FBR2pFLElBQU8sSUFBSSxXQUFXLE1BQU0sQ0FBQyxDQUFDO0FBQzlCLElBQU8sS0FBSyxXQUFXLGtCQUFrQixDQUFDLENBQUM7QUFDM0MsSUFBTyxZQUFZLFdBQVcseUJBQXlCLENBQUMsQ0FBQztBQUN6RCxJQUFJLFlBQVksR0FBVyxHQUFHLENBQUM7QUFLL0I7SUFFRSxNQUFNLENBQUMsc0NBQXNDLENBQUMsT0FBTyxDQUFDLE9BQU8sRUFBRSxVQUFVLENBQUM7UUFDeEUsSUFBSSxDQUFDLEdBQUcsSUFBSSxDQUFDLE1BQU0sRUFBRSxHQUFHLEVBQUUsR0FBRyxDQUFDLEVBQUUsQ0FBQyxHQUFHLENBQUMsSUFBSSxHQUFHLEdBQUcsQ0FBQyxHQUFHLENBQUMsQ0FBQyxHQUFHLEdBQUcsR0FBRyxHQUFHLENBQUMsQ0FBQztRQUNuRSxNQUFNLENBQUMsQ0FBQyxDQUFDLFFBQVEsQ0FBQyxFQUFFLENBQUMsQ0FBQztJQUN4QixDQUFDLENBQUMsQ0FBQztBQUNMLENBQUM7QUFNRCxpQkFBaUIsQ0FBVyxFQUFFLEVBQXlCO0lBQ3JELEVBQUUsQ0FBQyxDQUFDLENBQUMsQ0FBQyxDQUFDLENBQUM7UUFDTixFQUFFLENBQUMsQ0FBQyxDQUFDLENBQUM7UUFDTixNQUFNLENBQUMsS0FBSyxDQUFDO0lBQ2YsQ0FBQztJQUNELE1BQU0sQ0FBQyxJQUFJLENBQUM7QUFDZCxDQUFDO0FBTUQsbUJBQW1CLENBQVcsRUFBRSxFQUE4QixFQUFFLEVBQXlCO0lBQ3ZGLEVBQUUs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RSxJQUFJLEVBQUUsVUFBQyxDQUFXOzRCQUMzQyxFQUFFLENBQUMsQ0FBQyxTQUFTLENBQUMsQ0FBQyxFQUFFLEVBQUUsRUFBRSxFQUFFLENBQUMsQ0FBQyxDQUFDLENBQUM7Z0NBRXpCLEVBQUUsQ0FBQyxDQUFDLFlBQVksQ0FBQyxDQUFDLENBQUM7b0NBQ2pCLEVBQUUsQ0FBQyxHQUFHLENBQUMsV0FBVyxFQUFFLFNBQVMsQ0FBQyxRQUFRLEVBQUUsRUFBRSxJQUFJLEVBQUUsVUFBQyxDQUFXO3dDQUMxRCxFQUFFLENBQUMsQ0FBQyxTQUFTLENBQUMsQ0FBQyxFQUFFLEVBQUUsRUFBRSxFQUFFLENBQUMsQ0FBQyxDQUFDLENBQUM7NENBQ3pCLEVBQUUsQ0FBQyxNQUFNLENBQUMsRUFBRSxDQUFDLENBQUM7d0NBQ2hCLENBQUM7b0NBQ0gsQ0FBQyxDQUFDLENBQUM7Z0NBQ0wsQ0FBQztnQ0FBQyxJQUFJLENBQUMsQ0FBQztvQ0FFTixFQUFFLENBQUMsTUFBTSxDQUFDLEVBQUUsQ0FBQyxDQUFDO2dDQUNoQixDQUFDOzRCQUNILENBQUM7d0JBQ0gsQ0FBQyxDQUFDLENBQUM7b0JBQ0wsQ0FBQztnQkFDSCxDQUFDLENBQUMsQ0FBQztZQUNMLENBQUM7UUFDSCxDQUFDLENBQUMsQ0FBQztJQUNMLENBQUM7SUFDSCw4QkFBQztBQUFELENBQUMsQUFoaUJELENBQTZDLFdBQVcsQ0FBQyxjQUFjLEdBZ2lCdEU7QUFoaUJZLCtCQUF1QiwwQkFnaUJuQyxDQUFBIiwic291cmNlc0NvbnRlbnQiOlsiaW1wb3J0IGZpbGVfc3lzdGVtID0gcmVxdWlyZSgnLi4vY29yZS9maWxlX3N5c3RlbScpO1xyXG5pbXBvcnQge0FwaUVycm9yLCBFcnJvckNvZGV9IGZyb20gJy4uL2NvcmUvYXBpX2Vycm9yJztcclxuaW1wb3J0IHtkZWZhdWx0IGFzIFN0YXRzLCBGaWxlVHlwZX0gZnJvbSAnLi4vY29yZS9ub2RlX2ZzX3N0YXRzJztcclxuaW1wb3J0IGZpbGUgPSByZXF1aXJlKCcuLi9jb3JlL2ZpbGUnKTtcclxuaW1wb3J0IGZpbGVfZmxhZyA9IHJlcXVpcmUoJy4uL2NvcmUvZmlsZV9mbGFnJyk7XHJcbmltcG9ydCBwYXRoID0gcmVxdWlyZSgncGF0aCcpO1xyXG5pbXBvcnQgSW5vZGUgPSByZXF1aXJlKCcuLi9nZW5lcmljL2lub2RlJyk7XHJcbmltcG9ydCBwcmVsb2FkX2ZpbGUgPSByZXF1aXJlKCcuLi9nZW5lcmljL3ByZWxvYWRfZmlsZScpO1xyXG52YXIgUk9PVF9OT0RFX0lEOiBzdHJpbmcgPSBcIi9cIjtcclxuXHJcbi8qKlxyXG4gKiBHZW5lcmF0ZXMgYSByYW5kb20gSUQuXHJcbiAqL1xyXG5mdW5jdGlvbiBHZW5lcmF0ZVJhbmRvbUlEKCk6IHN0cmluZyB7XHJcbiAgLy8gRnJvbSBodHRwOi8vc3RhY2tvdmVyZmxvdy5jb20vcXVlc3Rpb25zLzEwNTAzNC9ob3ctdG8tY3JlYXRlLWEtZ3VpZC11dWlkLWluLWphdmFzY3JpcHRcclxuICByZXR1cm4gJ3h4eHh4eHh4LXh4eHgtNHh4eC15eHh4LXh4eHh4eHh4eHh4eCcucmVwbGFjZSgvW3h5XS9nLCBmdW5jdGlvbiAoYykge1xyXG4gICAgdmFyIHIgPSBNYXRoLnJhbmRvbSgpICogMTYgfCAwLCB2ID0gYyA9PSAneCcgPyByIDogKHIgJiAweDMgfCAweDgpO1xyXG4gICAgcmV0dXJuIHYudG9TdHJpbmcoMTYpO1xyXG4gIH0pO1xyXG59XHJcblxyXG4vKipcclxuICogSGVscGVyIGZ1bmN0aW9uLiBDaGVja3MgaWYgJ2UnIGlzIGRlZmluZWQuIElmIHNvLCBpdCB0cmlnZ2VycyB0aGUgY2FsbGJhY2tcclxuICogd2l0aCAnZScgYW5kIHJldHVybnMgZmFsc2UuIE90aGVyd2lzZSwgcmV0dXJucyB0cnVlLlxyXG4gKi9cclxuZnVuY3Rpb24gbm9FcnJvcihlOiBBcGlFcnJvciwgY2I6IChlOiBBcGlFcnJvcikgPT4gdm9pZCk6IGJvb2xlYW4ge1xyXG4gIGlmIChlKSB7XHJcbiAgICBjYihlKTtcclxuICAgIHJldHVybiBmYWxzZTtcclxuICB9XHJcbiAgcmV0dXJuIHRydWU7XHJcbn1cclxuXHJcbi8qKlxyXG4gKiBIZWxwZXIgZnVuY3Rpb24uIENoZWNrcyBpZiAnZScgaXMgZGVmaW5lZC4gSWYgc28sIGl0IGFib3J0cyB0aGUgdHJhbnNhY3Rpb24sXHJcbiAqIHRyaWdnZXJzIHRoZSBjYWxsYmFjayB3aXRoICdlJywgYW5kIHJldHVybnMgZmFsc2UuIE90aGVyd2lzZSwgcmV0dXJucyB0cnVlLlxyXG4gKi9cclxuZnVuY3Rpb24gbm9FcnJvclR4KGU6IEFwaUVycm9yLCB0eDogQXN5bmNLZXlWYWx1ZVJXVHJhbnNhY3Rpb24sIGNiOiAoZTogQXBpRXJyb3IpID0+IHZvaWQpOiBib29sZWFuIHtcclxuICBpZiAoZSkge1xyXG4gICAgdHguYWJvcnQoKCkgPT4ge1xyXG4gICAgICBjYihlKTtcclxuICAgIH0pO1xyXG4gICAgcmV0dXJuIGZhbHNlO1xyXG4gIH1cclxuICByZXR1cm4gdHJ1ZTtcclxufVxyXG5cclxuLyoqXHJcbiAqIFJlcHJlc2VudHMgYSAqc3luY2hyb25vdXMqIGtleS12YWx1ZSBzdG9yZS5cclxuICovXHJcbmV4cG9ydCBpbnRlcmZhY2UgU3luY0tleVZhbHVlU3RvcmUge1xyXG4gIC8qKlxyXG4gICAqIFRoZSBuYW1lIG9mIHRoZSBrZXktdmFsdWUgc3RvcmUuXHJcbiAgICovXHJcbiAgbmFtZSgpOiBzdHJpbmc7XHJcbiAgLyoqXHJcbiAgICogRW1wdGllcyB0aGUga2V5LXZhbHVlIHN0b3JlIGNvbXBsZXRlbHkuXHJcbiAgICovXHJcbiAgY2xlYXIoKTogdm9pZDtcclxuICAvKipcclxuICAgKiBCZWdpbnMgYSBuZXcgcmVhZC1vbmx5IHRyYW5zYWN0aW9uLlxyXG4gICAqL1xyXG4gIGJlZ2luVHJhbnNhY3Rpb24odHlwZTogXCJyZWFkb25seVwiKTogU3luY0tleVZhbHVlUk9UcmFuc2FjdGlvbjtcclxuICAvKipcclxuICAgKiBCZWdpbnMgYSBuZXcgcmVhZC13cml0ZSB0cmFuc2FjdGlvbi5cclxuICAgKi9cclxuICBiZWdpblRyYW5zYWN0aW9uKHR5cGU6IFwicmVhZHdyaXRlXCIpOiBTeW5jS2V5VmFsdWVSV1RyYW5zYWN0aW9uO1xyXG4gIGJlZ2luVHJhbnNhY3Rpb24odHlwZTogc3RyaW5nKTogU3luY0tleVZhbHVlUk9UcmFuc2FjdGlvbjtcclxufVxyXG5cclxuLyoqXHJcbiAqIEEgcmVhZC1vbmx5IHRyYW5zYWN0aW9uIGZvciBhIHN5bmNocm9ub3VzIGtleSB2YWx1ZSBzdG9yZS5cclxuICovXHJcbmV4cG9ydCBpbnRlcmZhY2UgU3luY0tleVZhbHVlUk9UcmFuc2FjdGlvbiB7XHJcbiAgLyoqXHJcbiAgICogUmV0cmlldmVzIHRoZSBkYXRhIGF0IHRoZSBnaXZlbiBrZXkuIFRocm93cyBhbiBBcGlFcnJvciBpZiBhbiBlcnJvciBvY2N1cnNcclxuICAgKiBvciBpZiB0aGUga2V5IGRvZXMgbm90IGV4aXN0LlxyXG4gICAqIEBwYXJhbSBrZXkgVGhlIGtleSB0byBsb29rIHVuZGVyIGZvciBkYXRhLlxyXG4gICAqIEByZXR1cm4gVGhlIGRhdGEgc3RvcmVkIHVuZGVyIHRoZSBrZXksIG9yIHVuZGVmaW5lZCBpZiBub3QgcHJlc2VudC5cclxuICAgKi9cclxuICBnZXQoa2V5OiBzdHJpbmcpOiBOb2RlQnVmZmVyO1xyXG59XHJcblxyXG4vKipcclxuICogQSByZWFkLXdyaXRlIHRyYW5zYWN0aW9uIGZvciBhIHN5bmNocm9ub3VzIGtleSB2YWx1ZSBzdG9yZS5cclxuICovXHJcbmV4cG9ydCBpbnRlcmZhY2UgU3luY0tleVZhbHVlUldUcmFuc2FjdGlvbiBleHRlbmRzIFN5bmNLZXlWYWx1ZVJPVHJhbnNhY3Rpb24ge1xyXG4gIC8qKlxyXG4gICAqIEFkZHMgdGhlIGRhdGEgdG8gdGhlIHN0b3JlIHVuZGVyIHRoZSBnaXZlbiBrZXkuXHJcbiAgICogQHBhcmFtIGtleSBUaGUga2V5IHRvIGFkZCB0aGUgZGF0YSB1bmRlci5cclxuICAgKiBAcGFyYW0gZGF0YSBUaGUgZGF0YSB0byBhZGQgdG8gdGhlIHN0b3JlLlxyXG4gICAqIEBwYXJhbSBvdmVyd3JpdGUgSWYgJ3RydWUnLCBvdmVyd3JpdGUgYW55IGV4aXN0aW5nIGRhdGEuIElmICdmYWxzZScsXHJcbiAgICogICBhdm9pZHMgc3RvcmluZyB0aGUgZGF0YSBpZiB0aGUga2V5IGV4aXN0cy5cclxuICAgKiBAcmV0dXJuIFRydWUgaWYgc3RvcmFnZSBzdWNjZWVkZWQsIGZhbHNlIG90aGVyd2lzZS5cclxuICAgKi9cclxuICBwdXQoa2V5OiBzdHJpbmcsIGRhdGE6IE5vZGVCdWZmZXIsIG92ZXJ3cml0ZTogYm9vbGVhbik6IGJvb2xlYW47XHJcbiAgLyoqXHJcbiAgICogRGVsZXRlcyB0aGUgZGF0YSBhdCB0aGUgZ2l2ZW4ga2V5LlxyXG4gICAqIEBwYXJhbSBrZXkgVGhlIGtleSB0byBkZWxldGUgZnJvbSB0aGUgc3RvcmUuXHJcbiAgICovXHJcbiAgZGVsKGtleTogc3RyaW5nKTogdm9pZDtcclxuICAvKipcclxuICAgKiBDb21taXRzIHRoZSB0cmFuc2FjdGlvbi5cclxuICAgKi9cclxuICBjb21taXQoKTogdm9pZDtcclxuICAvKipcclxuICAgKiBBYm9ydHMgYW5kIHJvbGxzIGJhY2sgdGhlIHRyYW5zYWN0aW9uLlxyXG4gICAqL1xyXG4gIGFib3J0KCk6IHZvaWQ7XHJcbn1cclxuXHJcbi8qKlxyXG4gKiBBbiBpbnRlcmZhY2UgZm9yIHNpbXBsZSBzeW5jaHJvbm91cyBrZXktdmFsdWUgc3RvcmVzIHRoYXQgZG9uJ3QgaGF2ZSBzcGVjaWFsXHJcbiAqIHN1cHBvcnQgZm9yIHRyYW5zYWN0aW9ucyBhbmQgc3VjaC5cclxuICovXHJcbmV4cG9ydCBpbnRlcmZhY2UgU2ltcGxlU3luY1N0b3JlIHtcclxuICBnZXQoa2V5OiBzdHJpbmcpOiBOb2RlQnVmZmVyO1xyXG4gIHB1dChrZXk6IHN0cmluZywgZGF0YTogTm9kZUJ1ZmZlciwgb3ZlcndyaXRlOiBib29sZWFuKTogYm9vbGVhbjtcclxuICBkZWwoa2V5OiBzdHJpbmcpOiB2b2lkO1xyXG59XHJcblxyXG4vKipcclxuICogQSBzaW1wbGUgUlcgdHJhbnNhY3Rpb24gZm9yIHNpbXBsZSBzeW5jaHJvbm91cyBrZXktdmFsdWUgc3RvcmVzLlxyXG4gKi9cclxuZXhwb3J0IGNsYXNzIFNpbXBsZVN5bmNSV1RyYW5zYWN0aW9uIGltcGxlbWVudHMgU3luY0tleVZhbHVlUldUcmFuc2FjdGlvbiB7XHJcbiAgY29uc3RydWN0b3IocHJpdmF0ZSBzdG9yZTogU2ltcGxlU3luY1N0b3JlKSB7IH1cclxuICAvKipcclxuICAgKiBTdG9yZXMgZGF0YSBpbiB0aGUga2V5cyB3ZSBtb2RpZnkgcHJpb3IgdG8gbW9kaWZ5aW5nIHRoZW0uXHJcbiAgICogQWxsb3dzIHVzIHRvIHJvbGwgYmFjayBjb21taXRzLlxyXG4gICAqL1xyXG4gIHByaXZhdGUgb3JpZ2luYWxEYXRhOiB7IFtrZXk6IHN0cmluZ106IE5vZGVCdWZmZXIgfSA9IHt9O1xyXG4gIC8qKlxyXG4gICAqIExpc3Qgb2Yga2V5cyBtb2RpZmllZCBpbiB0aGlzIHRyYW5zYWN0aW9uLCBpZiBhbnkuXHJcbiAgICovXHJcbiAgcHJpdmF0ZSBtb2RpZmllZEtleXM6IHN0cmluZ1tdID0gW107XHJcbiAgLyoqXHJcbiAgICogU3Rhc2hlcyBnaXZlbiBrZXkgdmFsdWUgcGFpciBpbnRvIGBvcmlnaW5hbERhdGFgIGlmIGl0IGRvZXNuJ3QgYWxyZWFkeVxyXG4gICAqIGV4aXN0LiBBbGxvd3MgdXMgdG8gc3Rhc2ggdmFsdWVzIHRoZSBwcm9ncmFtIGlzIHJlcXVlc3RpbmcgYW55d2F5IHRvXHJcbiAgICogcHJldmVudCBuZWVkbGVzcyBgZ2V0YCByZXF1ZXN0cyBpZiB0aGUgcHJvZ3JhbSBtb2RpZmllcyB0aGUgZGF0YSBsYXRlclxyXG4gICAqIG9uIGR1cmluZyB0aGUgdHJhbnNhY3Rpb24uXHJcbiAgICovXHJcbiAgcHJpdmF0ZSBzdGFzaE9sZFZhbHVlKGtleTogc3RyaW5nLCB2YWx1ZTogTm9kZUJ1ZmZlcikge1xyXG4gICAgLy8gS2VlcCBvbmx5IHRoZSBlYXJsaWVzdCB2YWx1ZSBpbiB0aGUgdHJhbnNhY3Rpb24uXHJcbiAgICBpZiAoIXRoaXMub3JpZ2luYWxEYXRhLmhhc093blByb3BlcnR5KGtleSkpIHtcclxuICAgICAgdGhpcy5vcmlnaW5hbERhdGFba2V5XSA9IHZhbHVlXHJcbiAgICB9XHJcbiAgfVxyXG4gIC8qKlxyXG4gICAqIE1hcmtzIHRoZSBnaXZlbiBrZXkgYXMgbW9kaWZpZWQsIGFuZCBzdGFzaGVzIGl0cyB2YWx1ZSBpZiBpdCBoYXMgbm90IGJlZW5cclxuICAgKiBzdGFzaGVkIGFscmVhZHkuXHJcbiAgICovXHJcbiAgcHJpdmF0ZSBtYXJrTW9kaWZpZWQoa2V5OiBzdHJpbmcpIHtcclxuICAgIGlmICh0aGlzLm1vZGlmaWVkS2V5cy5pbmRleE9mKGtleSkgPT09IC0xKSB7XHJcbiAgICAgIHRoaXMubW9kaWZpZWRLZXlzLnB1c2goa2V5KTtcclxuICAgICAgaWYgKCF0aGlzLm9yaWdpbmFsRGF0YS5oYXNPd25Qcm9wZXJ0eShrZXkpKSB7XHJcbiAgICAgICAgdGhpcy5vcmlnaW5hbERhdGFba2V5XSA9IHRoaXMuc3RvcmUuZ2V0KGtleSk7XHJcbiAgICAgIH1cclxuICAgIH1cclxuICB9XHJcblxyXG4gIHB1YmxpYyBnZXQoa2V5OiBzdHJpbmcpOiBOb2RlQnVmZmVyIHtcclxuICAgIHZhciB2YWwgPSB0aGlzLnN0b3JlLmdldChrZXkpO1xyXG4gICAgdGhpcy5zdGFzaE9sZFZhbHVlKGtleSwgdmFsKTtcclxuICAgIHJldHVybiB2YWw7XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgcHV0KGtleTogc3RyaW5nLCBkYXRhOiBOb2RlQnVmZmVyLCBvdmVyd3JpdGU6IGJvb2xlYW4pOiBib29sZWFuIHtcclxuICAgIHRoaXMubWFya01vZGlmaWVkKGtleSk7XHJcbiAgICByZXR1cm4gdGhpcy5zdG9yZS5wdXQoa2V5LCBkYXRhLCBvdmVyd3JpdGUpO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIGRlbChrZXk6IHN0cmluZyk6IHZvaWQge1xyXG4gICAgdGhpcy5tYXJrTW9kaWZpZWQoa2V5KTtcclxuICAgIHRoaXMuc3RvcmUuZGVsKGtleSk7XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgY29tbWl0KCk6IHZvaWQgey8qIE5PUCAqL31cclxuICBwdWJsaWMgYWJvcnQoKTogdm9pZCB7XHJcbiAgICAvLyBSb2xsYmFjayBvbGQgdmFsdWVzLlxyXG4gICAgdmFyIGk6IG51bWJlciwga2V5OiBzdHJpbmcsIHZhbHVlOiBOb2RlQnVmZmVyO1xyXG4gICAgZm9yIChpID0gMDsgaSA8IHRoaXMubW9kaWZpZWRLZXlzLmxlbmd0aDsgaSsrKSB7XHJcbiAgICAgIGtleSA9IHRoaXMubW9kaWZpZWRLZXlzW2ldO1xyXG4gICAgICB2YWx1ZSA9IHRoaXMub3JpZ2luYWxEYXRhW2tleV07XHJcbiAgICAgIGlmICh2YWx1ZSA9PT0gbnVsbCkge1xyXG4gICAgICAgIC8vIEtleSBkaWRuJ3QgZXhpc3QuXHJcbiAgICAgICAgdGhpcy5zdG9yZS5kZWwoa2V5KTtcclxuICAgICAgfSBlbHNlIHtcclxuICAgICAgICAvLyBLZXkgZXhpc3RlZC4gU3RvcmUgb2xkIHZhbHVlLlxyXG4gICAgICAgIHRoaXMuc3RvcmUucHV0KGtleSwgdmFsdWUsIHRydWUpO1xyXG4gICAgICB9XHJcbiAgICB9XHJcbiAgfVxyXG59XHJcblxyXG5leHBvcnQgaW50ZXJmYWNlIFN5bmNLZXlWYWx1ZUZpbGVTeXN0ZW1PcHRpb25zIHtcclxuICAvKipcclxuICAgKiBUaGUgYWN0dWFsIGtleS12YWx1ZSBzdG9yZSB0byByZWFkIGZyb20vd3JpdGUgdG8uXHJcbiAgICovXHJcbiAgc3RvcmU6IFN5bmNLZXlWYWx1ZVN0b3JlO1xyXG4gIC8qKlxyXG4gICAqIFNob3VsZCB0aGUgZmlsZSBzeXN0ZW0gc3VwcG9ydCBwcm9wZXJ0aWVzIChtdGltZS9hdGltZS9jdGltZS9jaG1vZC9ldGMpP1xyXG4gICAqIEVuYWJsaW5nIHRoaXMgc2xpZ2h0bHkgaW5jcmVhc2VzIHRoZSBzdG9yYWdlIHNwYWNlIHBlciBmaWxlLCBhbmQgYWRkc1xyXG4gICAqIGF0aW1lIHVwZGF0ZXMgZXZlcnkgdGltZSBhIGZpbGUgaXMgYWNjZXNzZWQsIG10aW1lIHVwZGF0ZXMgZXZlcnkgdGltZVxyXG4gICAqIGEgZmlsZSBpcyBtb2RpZmllZCwgYW5kIHBlcm1pc3Npb24gY2hlY2tzIG9uIGV2ZXJ5IG9wZXJhdGlvbi5cclxuICAgKlxyXG4gICAqIERlZmF1bHRzIHRvICpmYWxzZSouXHJcbiAgICovXHJcbiAgLy9zdXBwb3J0UHJvcHM/OiBib29sZWFuO1xyXG4gIC8qKlxyXG4gICAqIFNob3VsZCB0aGUgZmlsZSBzeXN0ZW0gc3VwcG9ydCBsaW5rcz9cclxuICAgKi9cclxuICAvL3N1cHBvcnRMaW5rcz86IGJvb2xlYW47XHJcbn1cclxuXHJcbmV4cG9ydCBjbGFzcyBTeW5jS2V5VmFsdWVGaWxlIGV4dGVuZHMgcHJlbG9hZF9maWxlLlByZWxvYWRGaWxlPFN5bmNLZXlWYWx1ZUZpbGVTeXN0ZW0+IGltcGxlbWVudHMgZmlsZS5GaWxlIHtcclxuICBjb25zdHJ1Y3RvcihfZnM6IFN5bmNLZXlWYWx1ZUZpbGVTeXN0ZW0sIF9wYXRoOiBzdHJpbmcsIF9mbGFnOiBmaWxlX2ZsYWcuRmlsZUZsYWcsIF9zdGF0OiBTdGF0cywgY29udGVudHM/OiBOb2RlQnVmZmVyKSB7XHJcbiAgICBzdXBlcihfZnMsIF9wYXRoLCBfZmxhZywgX3N0YXQsIGNvbnRlbnRzKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBzeW5jU3luYygpOiB2b2lkIHtcclxuICAgIGlmICh0aGlzLmlzRGlydHkoKSkge1xyXG4gICAgICB0aGlzLl9mcy5fc3luY1N5bmModGhpcy5nZXRQYXRoKCksIHRoaXMuZ2V0QnVmZmVyKCksIHRoaXMuZ2V0U3RhdHMoKSk7XHJcbiAgICAgIHRoaXMucmVzZXREaXJ0eSgpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgcHVibGljIGNsb3NlU3luYygpOiB2b2lkIHtcclxuICAgIHRoaXMuc3luY1N5bmMoKTtcclxuICB9XHJcbn1cclxuXHJcbi8qKlxyXG4gKiBBIFwiU3luY2hyb25vdXMga2V5LXZhbHVlIGZpbGUgc3lzdGVtXCIuIFN0b3JlcyBkYXRhIHRvL3JldHJpZXZlcyBkYXRhIGZyb20gYW5cclxuICogdW5kZXJseWluZyBrZXktdmFsdWUgc3RvcmUuXHJcbiAqXHJcbiAqIFdlIHVzZSBhIHVuaXF1ZSBJRCBmb3IgZWFjaCBub2RlIGluIHRoZSBmaWxlIHN5c3RlbS4gVGhlIHJvb3Qgbm9kZSBoYXMgYVxyXG4gKiBmaXhlZCBJRC5cclxuICogQHRvZG8gSW50cm9kdWNlIE5vZGUgSUQgY2FjaGluZy5cclxuICogQHRvZG8gQ2hlY2sgbW9kZXMuXHJcbiAqL1xyXG5leHBvcnQgY2xhc3MgU3luY0tleVZhbHVlRmlsZVN5c3RlbSBleHRlbmRzIGZpbGVfc3lzdGVtLlN5bmNocm9ub3VzRmlsZVN5c3RlbSB7XHJcbiAgcHJpdmF0ZSBzdG9yZTogU3luY0tleVZhbHVlU3RvcmU7XHJcbiAgY29uc3RydWN0b3Iob3B0aW9uczogU3luY0tleVZhbHVlRmlsZVN5c3RlbU9wdGlvbnMpIHtcclxuICAgIHN1cGVyKCk7XHJcbiAgICB0aGlzLnN0b3JlID0gb3B0aW9ucy5zdG9yZTtcclxuICAgIC8vIElOVkFSSUFOVDogRW5zdXJlIHRoYXQgdGhlIHJvb3QgZXhpc3RzLlxyXG4gICAgdGhpcy5tYWtlUm9vdERpcmVjdG9yeSgpO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIHN0YXRpYyBpc0F2YWlsYWJsZSgpOiBib29sZWFuIHsgcmV0dXJuIHRydWU7IH1cclxuICBwdWJsaWMgZ2V0TmFtZSgpOiBzdHJpbmcgeyByZXR1cm4gdGhpcy5zdG9yZS5uYW1lKCk7IH1cclxuICBwdWJsaWMgaXNSZWFkT25seSgpOiBib29sZWFuIHsgcmV0dXJuIGZhbHNlOyB9XHJcbiAgcHVibGljIHN1cHBvcnRzU3ltbGlua3MoKTogYm9vbGVhbiB7IHJldHVybiBmYWxzZTsgfVxyXG4gIHB1YmxpYyBzdXBwb3J0c1Byb3BzKCk6IGJvb2xlYW4geyByZXR1cm4gZmFsc2U7IH1cclxuICBwdWJsaWMgc3VwcG9ydHNTeW5jaCgpOiBib29sZWFuIHsgcmV0dXJuIHRydWU7IH1cclxuXHJcbiAgLyoqXHJcbiAgICogQ2hlY2tzIGlmIHRoZSByb290IGRpcmVjdG9yeSBleGlzdHMuIENyZWF0ZXMgaXQgaWYgaXQgZG9lc24ndC5cclxuICAgKi9cclxuICBwcml2YXRlIG1ha2VSb290RGlyZWN0b3J5KCkge1xyXG4gICAgdmFyIHR4ID0gdGhpcy5zdG9yZS5iZWdpblRyYW5zYWN0aW9uKCdyZWFkd3JpdGUnKTtcclxuICAgIGlmICh0eC5nZXQoUk9PVF9OT0RFX0lEKSA9PT0gdW5kZWZpbmVkKSB7XHJcbiAgICAgIC8vIENyZWF0ZSBuZXcgaW5vZGUuXHJcbiAgICAgIHZhciBjdXJyVGltZSA9IChuZXcgRGF0ZSgpKS5nZXRUaW1lKCksXHJcbiAgICAgICAgLy8gTW9kZSAwNjY2XHJcbiAgICAgICAgZGlySW5vZGUgPSBuZXcgSW5vZGUoR2VuZXJhdGVSYW5kb21JRCgpLCA0MDk2LCA1MTEgfCBGaWxlVHlwZS5ESVJFQ1RPUlksIGN1cnJUaW1lLCBjdXJyVGltZSwgY3VyclRpbWUpO1xyXG4gICAgICAvLyBJZiB0aGUgcm9vdCBkb2Vzbid0IGV4aXN0LCB0aGUgZmlyc3QgcmFuZG9tIElEIHNob3VsZG4ndCBleGlzdCxcclxuICAgICAgLy8gZWl0aGVyLlxyXG4gICAgICB0eC5wdXQoZGlySW5vZGUuaWQsIG5ldyBCdWZmZXIoXCJ7fVwiKSwgZmFsc2UpO1xyXG4gICAgICB0eC5wdXQoUk9PVF9OT0RFX0lELCBkaXJJbm9kZS50b0J1ZmZlcigpLCBmYWxzZSk7XHJcbiAgICAgIHR4LmNvbW1pdCgpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogSGVscGVyIGZ1bmN0aW9uIGZvciBmaW5kSU5vZGUuXHJcbiAgICogQHBhcmFtIHBhcmVudCBUaGUgcGFyZW50IGRpcmVjdG9yeSBvZiB0aGUgZmlsZSB3ZSBhcmUgYXR0ZW1wdGluZyB0byBmaW5kLlxyXG4gICAqIEBwYXJhbSBmaWxlbmFtZSBUaGUgZmlsZW5hbWUgb2YgdGhlIGlub2RlIHdlIGFyZSBhdHRlbXB0aW5nIHRvIGZpbmQsIG1pbnVzXHJcbiAgICogICB0aGUgcGFyZW50LlxyXG4gICAqIEByZXR1cm4gc3RyaW5nIFRoZSBJRCBvZiB0aGUgZmlsZSdzIGlub2RlIGluIHRoZSBmaWxlIHN5c3RlbS5cclxuICAgKi9cclxuICBwcml2YXRlIF9maW5kSU5vZGUodHg6IFN5bmNLZXlWYWx1ZVJPVHJhbnNhY3Rpb24sIHBhcmVudDogc3RyaW5nLCBmaWxlbmFtZTogc3RyaW5nKTogc3RyaW5nIHtcclxuICAgIHZhciByZWFkX2RpcmVjdG9yeSA9IChpbm9kZTogSW5vZGUpOiBzdHJpbmcgPT4ge1xyXG4gICAgICAvLyBHZXQgdGhlIHJvb3QncyBkaXJlY3RvcnkgbGlzdGluZy5cclxuICAgICAgdmFyIGRpckxpc3QgPSB0aGlzLmdldERpckxpc3RpbmcodHgsIHBhcmVudCwgaW5vZGUpO1xyXG4gICAgICAvLyBHZXQgdGhlIGZpbGUncyBJRC5cclxuICAgICAgaWYgKGRpckxpc3RbZmlsZW5hbWVdKSB7XHJcbiAgICAgICAgcmV0dXJuIGRpckxpc3RbZmlsZW5hbWVdO1xyXG4gICAgICB9IGVsc2Uge1xyXG4gICAgICAgIHRocm93IEFwaUVycm9yLkVOT0VOVChwYXRoLnJlc29sdmUocGFyZW50LCBmaWxlbmFtZSkpO1xyXG4gICAgICB9XHJcbiAgICB9O1xyXG4gICAgaWYgKHBhcmVudCA9PT0gJy8nKSB7XHJcbiAgICAgIGlmIChmaWxlbmFtZSA9PT0gJycpIHtcclxuICAgICAgICAvLyBCQVNFIENBU0UgIzE6IFJldHVybiB0aGUgcm9vdCdzIElELlxyXG4gICAgICAgIHJldHVybiBST09UX05PREVfSUQ7XHJcbiAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgLy8gQkFTRSBDQVNFICMyOiBGaW5kIHRoZSBpdGVtIGluIHRoZSByb290IG5kb2UuXHJcbiAgICAgICAgcmV0dXJuIHJlYWRfZGlyZWN0b3J5KHRoaXMuZ2V0SU5vZGUodHgsIHBhcmVudCwgUk9PVF9OT0RFX0lEKSk7XHJcbiAgICAgIH1cclxuICAgIH0gZWxzZSB7XHJcbiAgICAgIHJldHVybiByZWFkX2RpcmVjdG9yeSh0aGlzLmdldElOb2RlKHR4LCBwYXJlbnQgKyBwYXRoLnNlcCArIGZpbGVuYW1lLFxyXG4gICAgICAgIHRoaXMuX2ZpbmRJTm9kZSh0eCwgcGF0aC5kaXJuYW1lKHBhcmVudCksIHBhdGguYmFzZW5hbWUocGFyZW50KSkpKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIEZpbmRzIHRoZSBJbm9kZSBvZiB0aGUgZ2l2ZW4gcGF0aC5cclxuICAgKiBAcGFyYW0gcCBUaGUgcGF0aCB0byBsb29rIHVwLlxyXG4gICAqIEByZXR1cm4gVGhlIElub2RlIG9mIHRoZSBwYXRoIHAuXHJcbiAgICogQHRvZG8gbWVtb2l6ZS9jYWNoZVxyXG4gICAqL1xyXG4gIHByaXZhdGUgZmluZElOb2RlKHR4OiBTeW5jS2V5VmFsdWVST1RyYW5zYWN0aW9uLCBwOiBzdHJpbmcpOiBJbm9kZSB7XHJcbiAgICByZXR1cm4gdGhpcy5nZXRJTm9kZSh0eCwgcCwgdGhpcy5fZmluZElOb2RlKHR4LCBwYXRoLmRpcm5hbWUocCksIHBhdGguYmFzZW5hbWUocCkpKTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIEdpdmVuIHRoZSBJRCBvZiBhIG5vZGUsIHJldHJpZXZlcyB0aGUgY29ycmVzcG9uZGluZyBJbm9kZS5cclxuICAgKiBAcGFyYW0gdHggVGhlIHRyYW5zYWN0aW9uIHRvIHVzZS5cclxuICAgKiBAcGFyYW0gcCBUaGUgY29ycmVzcG9uZGluZyBwYXRoIHRvIHRoZSBmaWxlICh1c2VkIGZvciBlcnJvciBtZXNzYWdlcykuXHJcbiAgICogQHBhcmFtIGlkIFRoZSBJRCB0byBsb29rIHVwLlxyXG4gICAqL1xyXG4gIHByaXZhdGUgZ2V0SU5vZGUodHg6IFN5bmNLZXlWYWx1ZVJPVHJhbnNhY3Rpb24sIHA6IHN0cmluZywgaWQ6IHN0cmluZyk6IElub2RlIHtcclxuICAgIHZhciBpbm9kZSA9IHR4LmdldChpZCk7XHJcbiAgICBpZiAoaW5vZGUgPT09IHVuZGVmaW5lZCkge1xyXG4gICAgICB0aHJvdyBBcGlFcnJvci5FTk9FTlQocCk7XHJcbiAgICB9XHJcbiAgICByZXR1cm4gSW5vZGUuZnJvbUJ1ZmZlcihpbm9kZSk7XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBHaXZlbiB0aGUgSW5vZGUgb2YgYSBkaXJlY3RvcnksIHJldHJpZXZlcyB0aGUgY29ycmVzcG9uZGluZyBkaXJlY3RvcnlcclxuICAgKiBsaXN0aW5nLlxyXG4gICAqL1xyXG4gIHByaXZhdGUgZ2V0RGlyTGlzdGluZyh0eDogU3luY0tleVZhbHVlUk9UcmFuc2FjdGlvbiwgcDogc3RyaW5nLCBpbm9kZTogSW5vZGUpOiB7IFtmaWxlTmFtZTogc3RyaW5nXTogc3RyaW5nIH0ge1xyXG4gICAgaWYgKCFpbm9kZS5pc0RpcmVjdG9yeSgpKSB7XHJcbiAgICAgIHRocm93IEFwaUVycm9yLkVOT1RESVIocCk7XHJcbiAgICB9XHJcbiAgICB2YXIgZGF0YSA9IHR4LmdldChpbm9kZS5pZCk7XHJcbiAgICBpZiAoZGF0YSA9PT0gdW5kZWZpbmVkKSB7XHJcbiAgICAgIHRocm93IEFwaUVycm9yLkVOT0VOVChwKTtcclxuICAgIH1cclxuICAgIHJldHVybiBKU09OLnBhcnNlKGRhdGEudG9TdHJpbmcoKSk7XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBDcmVhdGVzIGEgbmV3IG5vZGUgdW5kZXIgYSByYW5kb20gSUQuIFJldHJpZXMgNSB0aW1lcyBiZWZvcmUgZ2l2aW5nIHVwIGluXHJcbiAgICogdGhlIGV4Y2VlZGluZ2x5IHVubGlrZWx5IGNoYW5jZSB0aGF0IHdlIHRyeSB0byByZXVzZSBhIHJhbmRvbSBHVUlELlxyXG4gICAqIEByZXR1cm4gVGhlIEdVSUQgdGhhdCB0aGUgZGF0YSB3YXMgc3RvcmVkIHVuZGVyLlxyXG4gICAqL1xyXG4gIHByaXZhdGUgYWRkTmV3Tm9kZSh0eDogU3luY0tleVZhbHVlUldUcmFuc2FjdGlvbiwgZGF0YTogTm9kZUJ1ZmZlcik6IHN0cmluZyB7XHJcbiAgICB2YXIgcmV0cmllcyA9IDAsIGN1cnJJZDogc3RyaW5nO1xyXG4gICAgd2hpbGUgKHJldHJpZXMgPCA1KSB7XHJcbiAgICAgIHRyeSB7XHJcbiAgICAgICAgY3VycklkID0gR2VuZXJhdGVSYW5kb21JRCgpO1xyXG4gICAgICAgIHR4LnB1dChjdXJySWQsIGRhdGEsIGZhbHNlKTtcclxuICAgICAgICByZXR1cm4gY3VycklkO1xyXG4gICAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgICAgLy8gSWdub3JlIGFuZCByZXJvbGwuXHJcbiAgICAgIH1cclxuICAgIH1cclxuICAgIHRocm93IG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRUlPLCAnVW5hYmxlIHRvIGNvbW1pdCBkYXRhIHRvIGtleS12YWx1ZSBzdG9yZS4nKTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIENvbW1pdHMgYSBuZXcgZmlsZSAod2VsbCwgYSBGSUxFIG9yIGEgRElSRUNUT1JZKSB0byB0aGUgZmlsZSBzeXN0ZW0gd2l0aFxyXG4gICAqIHRoZSBnaXZlbiBtb2RlLlxyXG4gICAqIE5vdGU6IFRoaXMgd2lsbCBjb21taXQgdGhlIHRyYW5zYWN0aW9uLlxyXG4gICAqIEBwYXJhbSBwIFRoZSBwYXRoIHRvIHRoZSBuZXcgZmlsZS5cclxuICAgKiBAcGFyYW0gdHlwZSBUaGUgdHlwZSBvZiB0aGUgbmV3IGZpbGUuXHJcbiAgICogQHBhcmFtIG1vZGUgVGhlIG1vZGUgdG8gY3JlYXRlIHRoZSBuZXcgZmlsZSB3aXRoLlxyXG4gICAqIEBwYXJhbSBkYXRhIFRoZSBkYXRhIHRvIHN0b3JlIGF0IHRoZSBmaWxlJ3MgZGF0YSBub2RlLlxyXG4gICAqIEByZXR1cm4gVGhlIElub2RlIGZvciB0aGUgbmV3IGZpbGUuXHJcbiAgICovXHJcbiAgcHJpdmF0ZSBjb21taXROZXdGaWxlKHR4OiBTeW5jS2V5VmFsdWVSV1RyYW5zYWN0aW9uLCBwOiBzdHJpbmcsIHR5cGU6IEZpbGVUeXBlLCBtb2RlOiBudW1iZXIsIGRhdGE6IE5vZGVCdWZmZXIpOiBJbm9kZSB7XHJcbiAgICB2YXIgcGFyZW50RGlyID0gcGF0aC5kaXJuYW1lKHApLFxyXG4gICAgICBmbmFtZSA9IHBhdGguYmFzZW5hbWUocCksXHJcbiAgICAgIHBhcmVudE5vZGUgPSB0aGlzLmZpbmRJTm9kZSh0eCwgcGFyZW50RGlyKSxcclxuICAgICAgZGlyTGlzdGluZyA9IHRoaXMuZ2V0RGlyTGlzdGluZyh0eCwgcGFyZW50RGlyLCBwYXJlbnROb2RlKSxcclxuICAgICAgY3VyclRpbWUgPSAobmV3IERhdGUoKSkuZ2V0VGltZSgpO1xyXG5cclxuICAgIC8vIEludmFyaWFudDogVGhlIHJvb3QgYWx3YXlzIGV4aXN0cy5cclxuICAgIC8vIElmIHdlIGRvbid0IGNoZWNrIHRoaXMgcHJpb3IgdG8gdGFraW5nIHN0ZXBzIGJlbG93LCB3ZSB3aWxsIGNyZWF0ZSBhXHJcbiAgICAvLyBmaWxlIHdpdGggbmFtZSAnJyBpbiByb290IHNob3VsZCBwID09ICcvJy5cclxuICAgIGlmIChwID09PSAnLycpIHtcclxuICAgICAgdGhyb3cgQXBpRXJyb3IuRUVYSVNUKHApO1xyXG4gICAgfVxyXG5cclxuICAgIC8vIENoZWNrIGlmIGZpbGUgYWxyZWFkeSBleGlzdHMuXHJcbiAgICBpZiAoZGlyTGlzdGluZ1tmbmFtZV0pIHtcclxuICAgICAgdGhyb3cgQXBpRXJyb3IuRUVYSVNUKHApO1xyXG4gICAgfVxyXG5cclxuICAgIHRyeSB7XHJcbiAgICAgIC8vIENvbW1pdCBkYXRhLlxyXG4gICAgICB2YXIgZGF0YUlkID0gdGhpcy5hZGROZXdOb2RlKHR4LCBkYXRhKSxcclxuICAgICAgICBmaWxlTm9kZSA9IG5ldyBJbm9kZShkYXRhSWQsIGRhdGEubGVuZ3RoLCBtb2RlIHwgdHlwZSwgY3VyclRpbWUsIGN1cnJUaW1lLCBjdXJyVGltZSksXHJcbiAgICAgICAgLy8gQ29tbWl0IGZpbGUgbm9kZS5cclxuICAgICAgICBmaWxlTm9kZUlkID0gdGhpcy5hZGROZXdOb2RlKHR4LCBmaWxlTm9kZS50b0J1ZmZlcigpKTtcclxuICAgICAgLy8gVXBkYXRlIGFuZCBjb21taXQgcGFyZW50IGRpcmVjdG9yeSBsaXN0aW5nLlxyXG4gICAgICBkaXJMaXN0aW5nW2ZuYW1lXSA9IGZpbGVOb2RlSWQ7XHJcbiAgICAgIHR4LnB1dChwYXJlbnROb2RlLmlkLCBuZXcgQnVmZmVyKEpTT04uc3RyaW5naWZ5KGRpckxpc3RpbmcpKSwgdHJ1ZSk7XHJcbiAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgIHR4LmFib3J0KCk7XHJcbiAgICAgIHRocm93IGU7XHJcbiAgICB9XHJcbiAgICB0eC5jb21taXQoKTtcclxuICAgIHJldHVybiBmaWxlTm9kZTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIERlbGV0ZSBhbGwgY29udGVudHMgc3RvcmVkIGluIHRoZSBmaWxlIHN5c3RlbS5cclxuICAgKi9cclxuICBwdWJsaWMgZW1wdHkoKTogdm9pZCB7XHJcbiAgICB0aGlzLnN0b3JlLmNsZWFyKCk7XHJcbiAgICAvLyBJTlZBUklBTlQ6IFJvb3QgYWx3YXlzIGV4aXN0cy5cclxuICAgIHRoaXMubWFrZVJvb3REaXJlY3RvcnkoKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyByZW5hbWVTeW5jKG9sZFBhdGg6IHN0cmluZywgbmV3UGF0aDogc3RyaW5nKTogdm9pZCB7XHJcbiAgICB2YXIgdHggPSB0aGlzLnN0b3JlLmJlZ2luVHJhbnNhY3Rpb24oJ3JlYWR3cml0ZScpLFxyXG4gICAgICBvbGRQYXJlbnQgPSBwYXRoLmRpcm5hbWUob2xkUGF0aCksIG9sZE5hbWUgPSBwYXRoLmJhc2VuYW1lKG9sZFBhdGgpLFxyXG4gICAgICBuZXdQYXJlbnQgPSBwYXRoLmRpcm5hbWUobmV3UGF0aCksIG5ld05hbWUgPSBwYXRoLmJhc2VuYW1lKG5ld1BhdGgpLFxyXG4gICAgICAvLyBSZW1vdmUgb2xkUGF0aCBmcm9tIHBhcmVudCdzIGRpcmVjdG9yeSBsaXN0aW5nLlxyXG4gICAgICBvbGREaXJOb2RlID0gdGhpcy5maW5kSU5vZGUodHgsIG9sZFBhcmVudCksXHJcbiAgICAgIG9sZERpckxpc3QgPSB0aGlzLmdldERpckxpc3RpbmcodHgsIG9sZFBhcmVudCwgb2xkRGlyTm9kZSk7XHJcblxyXG4gICAgaWYgKCFvbGREaXJMaXN0W29sZE5hbWVdKSB7XHJcbiAgICAgIHRocm93IEFwaUVycm9yLkVOT0VOVChvbGRQYXRoKTtcclxuICAgIH1cclxuICAgIHZhciBub2RlSWQ6IHN0cmluZyA9IG9sZERpckxpc3Rbb2xkTmFtZV07XHJcbiAgICBkZWxldGUgb2xkRGlyTGlzdFtvbGROYW1lXTtcclxuXHJcbiAgICAvLyBJbnZhcmlhbnQ6IENhbid0IG1vdmUgYSBmb2xkZXIgaW5zaWRlIGl0c2VsZi5cclxuICAgIC8vIFRoaXMgZnVubnkgbGl0dGxlIGhhY2sgZW5zdXJlcyB0aGF0IHRoZSBjaGVjayBwYXNzZXMgb25seSBpZiBvbGRQYXRoXHJcbiAgICAvLyBpcyBhIHN1YnBhdGggb2YgbmV3UGFyZW50LiBXZSBhcHBlbmQgJy8nIHRvIGF2b2lkIG1hdGNoaW5nIGZvbGRlcnMgdGhhdFxyXG4gICAgLy8gYXJlIGEgc3Vic3RyaW5nIG9mIHRoZSBib3R0b20tbW9zdCBmb2xkZXIgaW4gdGhlIHBhdGguXHJcbiAgICBpZiAoKG5ld1BhcmVudCArICcvJykuaW5kZXhPZihvbGRQYXRoICsgJy8nKSA9PT0gMCkge1xyXG4gICAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVCVVNZLCBvbGRQYXJlbnQpO1xyXG4gICAgfVxyXG5cclxuICAgIC8vIEFkZCBuZXdQYXRoIHRvIHBhcmVudCdzIGRpcmVjdG9yeSBsaXN0aW5nLlxyXG4gICAgdmFyIG5ld0Rpck5vZGU6IElub2RlLCBuZXdEaXJMaXN0OiB0eXBlb2Ygb2xkRGlyTGlzdDtcclxuICAgIGlmIChuZXdQYXJlbnQgPT09IG9sZFBhcmVudCkge1xyXG4gICAgICAvLyBQcmV2ZW50IHVzIGZyb20gcmUtZ3JhYmJpbmcgdGhlIHNhbWUgZGlyZWN0b3J5IGxpc3RpbmcsIHdoaWNoIHN0aWxsXHJcbiAgICAgIC8vIGNvbnRhaW5zIG9sZE5hbWUuXHJcbiAgICAgIG5ld0Rpck5vZGUgPSBvbGREaXJOb2RlO1xyXG4gICAgICBuZXdEaXJMaXN0ID0gb2xkRGlyTGlzdDtcclxuICAgIH0gZWxzZSB7XHJcbiAgICAgIG5ld0Rpck5vZGUgPSB0aGlzLmZpbmRJTm9kZSh0eCwgbmV3UGFyZW50KTtcclxuICAgICAgbmV3RGlyTGlzdCA9IHRoaXMuZ2V0RGlyTGlzdGluZyh0eCwgbmV3UGFyZW50LCBuZXdEaXJOb2RlKTtcclxuICAgIH1cclxuXHJcbiAgICBpZiAobmV3RGlyTGlzdFtuZXdOYW1lXSkge1xyXG4gICAgICAvLyBJZiBpdCdzIGEgZmlsZSwgZGVsZXRlIGl0LlxyXG4gICAgICB2YXIgbmV3TmFtZU5vZGUgPSB0aGlzLmdldElOb2RlKHR4LCBuZXdQYXRoLCBuZXdEaXJMaXN0W25ld05hbWVdKTtcclxuICAgICAgaWYgKG5ld05hbWVOb2RlLmlzRmlsZSgpKSB7XHJcbiAgICAgICAgdHJ5IHtcclxuICAgICAgICAgIHR4LmRlbChuZXdOYW1lTm9kZS5pZCk7XHJcbiAgICAgICAgICB0eC5kZWwobmV3RGlyTGlzdFtuZXdOYW1lXSk7XHJcbiAgICAgICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICAgICAgdHguYWJvcnQoKTtcclxuICAgICAgICAgIHRocm93IGU7XHJcbiAgICAgICAgfVxyXG4gICAgICB9IGVsc2Uge1xyXG4gICAgICAgIC8vIElmIGl0J3MgYSBkaXJlY3RvcnksIHRocm93IGEgcGVybWlzc2lvbnMgZXJyb3IuXHJcbiAgICAgICAgdGhyb3cgQXBpRXJyb3IuRVBFUk0obmV3UGF0aCk7XHJcbiAgICAgIH1cclxuICAgIH1cclxuICAgIG5ld0Rpckxpc3RbbmV3TmFtZV0gPSBub2RlSWQ7XHJcblxyXG4gICAgLy8gQ29tbWl0IHRoZSB0d28gY2hhbmdlZCBkaXJlY3RvcnkgbGlzdGluZ3MuXHJcbiAgICB0cnkge1xyXG4gICAgICB0eC5wdXQob2xkRGlyTm9kZS5pZCwgbmV3IEJ1ZmZlcihKU09OLnN0cmluZ2lmeShvbGREaXJMaXN0KSksIHRydWUpO1xyXG4gICAgICB0eC5wdXQobmV3RGlyTm9kZS5pZCwgbmV3IEJ1ZmZlcihKU09OLnN0cmluZ2lmeShuZXdEaXJMaXN0KSksIHRydWUpO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICB0eC5hYm9ydCgpO1xyXG4gICAgICB0aHJvdyBlO1xyXG4gICAgfVxyXG5cclxuICAgIHR4LmNvbW1pdCgpO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIHN0YXRTeW5jKHA6IHN0cmluZywgaXNMc3RhdDogYm9vbGVhbik6IFN0YXRzIHtcclxuICAgIC8vIEdldCB0aGUgaW5vZGUgdG8gdGhlIGl0ZW0sIGNvbnZlcnQgaXQgaW50byBhIFN0YXRzIG9iamVjdC5cclxuICAgIHJldHVybiB0aGlzLmZpbmRJTm9kZSh0aGlzLnN0b3JlLmJlZ2luVHJhbnNhY3Rpb24oJ3JlYWRvbmx5JyksIHApLnRvU3RhdHMoKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBjcmVhdGVGaWxlU3luYyhwOiBzdHJpbmcsIGZsYWc6IGZpbGVfZmxhZy5GaWxlRmxhZywgbW9kZTogbnVtYmVyKTogZmlsZS5GaWxlIHtcclxuICAgIHZhciB0eCA9IHRoaXMuc3RvcmUuYmVnaW5UcmFuc2FjdGlvbigncmVhZHdyaXRlJyksXHJcbiAgICAgIGRhdGEgPSBuZXcgQnVmZmVyKDApLFxyXG4gICAgICBuZXdGaWxlID0gdGhpcy5jb21taXROZXdGaWxlKHR4LCBwLCBGaWxlVHlwZS5GSUxFLCBtb2RlLCBkYXRhKTtcclxuICAgIC8vIE9wZW4gdGhlIGZpbGUuXHJcbiAgICByZXR1cm4gbmV3IFN5bmNLZXlWYWx1ZUZpbGUodGhpcywgcCwgZmxhZywgbmV3RmlsZS50b1N0YXRzKCksIGRhdGEpO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIG9wZW5GaWxlU3luYyhwOiBzdHJpbmcsIGZsYWc6IGZpbGVfZmxhZy5GaWxlRmxhZyk6IGZpbGUuRmlsZSB7XHJcbiAgICB2YXIgdHggPSB0aGlzLnN0b3JlLmJlZ2luVHJhbnNhY3Rpb24oJ3JlYWRvbmx5JyksXHJcbiAgICAgIG5vZGUgPSB0aGlzLmZpbmRJTm9kZSh0eCwgcCksXHJcbiAgICAgIGRhdGEgPSB0eC5nZXQobm9kZS5pZCk7XHJcbiAgICBpZiAoZGF0YSA9PT0gdW5kZWZpbmVkKSB7XHJcbiAgICAgIHRocm93IEFwaUVycm9yLkVOT0VOVChwKTtcclxuICAgIH1cclxuICAgIHJldHVybiBuZXcgU3luY0tleVZhbHVlRmlsZSh0aGlzLCBwLCBmbGFnLCBub2RlLnRvU3RhdHMoKSwgZGF0YSk7XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBSZW1vdmUgYWxsIHRyYWNlcyBvZiB0aGUgZ2l2ZW4gcGF0aCBmcm9tIHRoZSBmaWxlIHN5c3RlbS5cclxuICAgKiBAcGFyYW0gcCBUaGUgcGF0aCB0byByZW1vdmUgZnJvbSB0aGUgZmlsZSBzeXN0ZW0uXHJcbiAgICogQHBhcmFtIGlzRGlyIERvZXMgdGhlIHBhdGggYmVsb25nIHRvIGEgZGlyZWN0b3J5LCBvciBhIGZpbGU/XHJcbiAgICogQHRvZG8gVXBkYXRlIG10aW1lLlxyXG4gICAqL1xyXG4gIHByaXZhdGUgcmVtb3ZlRW50cnkocDogc3RyaW5nLCBpc0RpcjogYm9vbGVhbik6IHZvaWQge1xyXG4gICAgdmFyIHR4ID0gdGhpcy5zdG9yZS5iZWdpblRyYW5zYWN0aW9uKCdyZWFkd3JpdGUnKSxcclxuICAgICAgcGFyZW50OiBzdHJpbmcgPSBwYXRoLmRpcm5hbWUocCksXHJcbiAgICAgIHBhcmVudE5vZGUgPSB0aGlzLmZpbmRJTm9kZSh0eCwgcGFyZW50KSxcclxuICAgICAgcGFyZW50TGlzdGluZyA9IHRoaXMuZ2V0RGlyTGlzdGluZyh0eCwgcGFyZW50LCBwYXJlbnROb2RlKSxcclxuICAgICAgZmlsZU5hbWU6IHN0cmluZyA9IHBhdGguYmFzZW5hbWUocCk7XHJcblxyXG4gICAgaWYgKCFwYXJlbnRMaXN0aW5nW2ZpbGVOYW1lXSkge1xyXG4gICAgICB0aHJvdyBBcGlFcnJvci5FTk9FTlQocCk7XHJcbiAgICB9XHJcblxyXG4gICAgLy8gUmVtb3ZlIGZyb20gZGlyZWN0b3J5IGxpc3Rpbmcgb2YgcGFyZW50LlxyXG4gICAgdmFyIGZpbGVOb2RlSWQgPSBwYXJlbnRMaXN0aW5nW2ZpbGVOYW1lXTtcclxuICAgIGRlbGV0ZSBwYXJlbnRMaXN0aW5nW2ZpbGVOYW1lXTtcclxuXHJcbiAgICAvLyBHZXQgZmlsZSBpbm9kZS5cclxuICAgIHZhciBmaWxlTm9kZSA9IHRoaXMuZ2V0SU5vZGUodHgsIHAsIGZpbGVOb2RlSWQpO1xyXG4gICAgaWYgKCFpc0RpciAmJiBmaWxlTm9kZS5pc0RpcmVjdG9yeSgpKSB7XHJcbiAgICAgIHRocm93IEFwaUVycm9yLkVJU0RJUihwKTtcclxuICAgIH0gZWxzZSBpZiAoaXNEaXIgJiYgIWZpbGVOb2RlLmlzRGlyZWN0b3J5KCkpIHtcclxuICAgICAgdGhyb3cgQXBpRXJyb3IuRU5PVERJUihwKTtcclxuICAgIH1cclxuXHJcbiAgICB0cnkge1xyXG4gICAgICAvLyBEZWxldGUgZGF0YS5cclxuICAgICAgdHguZGVsKGZpbGVOb2RlLmlkKTtcclxuICAgICAgLy8gRGVsZXRlIG5vZGUuXHJcbiAgICAgIHR4LmRlbChmaWxlTm9kZUlkKTtcclxuICAgICAgLy8gVXBkYXRlIGRpcmVjdG9yeSBsaXN0aW5nLlxyXG4gICAgICB0eC5wdXQocGFyZW50Tm9kZS5pZCwgbmV3IEJ1ZmZlcihKU09OLnN0cmluZ2lmeShwYXJlbnRMaXN0aW5nKSksIHRydWUpO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICB0eC5hYm9ydCgpO1xyXG4gICAgICB0aHJvdyBlO1xyXG4gICAgfVxyXG4gICAgLy8gU3VjY2Vzcy5cclxuICAgIHR4LmNvbW1pdCgpO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIHVubGlua1N5bmMocDogc3RyaW5nKTogdm9pZCB7XHJcbiAgICB0aGlzLnJlbW92ZUVudHJ5KHAsIGZhbHNlKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBybWRpclN5bmMocDogc3RyaW5nKTogdm9pZCB7XHJcbiAgICAvLyBDaGVjayBmaXJzdCBpZiBkaXJlY3RvcnkgaXMgZW1wdHkuXHJcbiAgICBpZiAodGhpcy5yZWFkZGlyU3luYyhwKS5sZW5ndGggPiAwKSB7XHJcbiAgICAgIHRocm93IEFwaUVycm9yLkVOT1RFTVBUWShwKTtcclxuICAgIH0gZWxzZSB7XHJcbiAgICAgIHRoaXMucmVtb3ZlRW50cnkocCwgdHJ1ZSk7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgbWtkaXJTeW5jKHA6IHN0cmluZywgbW9kZTogbnVtYmVyKTogdm9pZCB7XHJcbiAgICB2YXIgdHggPSB0aGlzLnN0b3JlLmJlZ2luVHJhbnNhY3Rpb24oJ3JlYWR3cml0ZScpLFxyXG4gICAgICBkYXRhID0gbmV3IEJ1ZmZlcigne30nKTtcclxuICAgIHRoaXMuY29tbWl0TmV3RmlsZSh0eCwgcCwgRmlsZVR5cGUuRElSRUNUT1JZLCBtb2RlLCBkYXRhKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyByZWFkZGlyU3luYyhwOiBzdHJpbmcpOiBzdHJpbmdbXXtcclxuICAgIHZhciB0eCA9IHRoaXMuc3RvcmUuYmVnaW5UcmFuc2FjdGlvbigncmVhZG9ubHknKTtcclxuICAgIHJldHVybiBPYmplY3Qua2V5cyh0aGlzLmdldERpckxpc3RpbmcodHgsIHAsIHRoaXMuZmluZElOb2RlKHR4LCBwKSkpO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIF9zeW5jU3luYyhwOiBzdHJpbmcsIGRhdGE6IE5vZGVCdWZmZXIsIHN0YXRzOiBTdGF0cyk6IHZvaWQge1xyXG4gICAgLy8gQHRvZG8gRW5zdXJlIG10aW1lIHVwZGF0ZXMgcHJvcGVybHksIGFuZCB1c2UgdGhhdCB0byBkZXRlcm1pbmUgaWYgYSBkYXRhXHJcbiAgICAvLyAgICAgICB1cGRhdGUgaXMgcmVxdWlyZWQuXHJcbiAgICB2YXIgdHggPSB0aGlzLnN0b3JlLmJlZ2luVHJhbnNhY3Rpb24oJ3JlYWR3cml0ZScpLFxyXG4gICAgICAvLyBXZSB1c2UgdGhlIF9maW5kSW5vZGUgaGVscGVyIGJlY2F1c2Ugd2UgYWN0dWFsbHkgbmVlZCB0aGUgSU5vZGUgaWQuXHJcbiAgICAgIGZpbGVJbm9kZUlkID0gdGhpcy5fZmluZElOb2RlKHR4LCBwYXRoLmRpcm5hbWUocCksIHBhdGguYmFzZW5hbWUocCkpLFxyXG4gICAgICBmaWxlSW5vZGUgPSB0aGlzLmdldElOb2RlKHR4LCBwLCBmaWxlSW5vZGVJZCksXHJcbiAgICAgIGlub2RlQ2hhbmdlZCA9IGZpbGVJbm9kZS51cGRhdGUoc3RhdHMpO1xyXG5cclxuICAgIHRyeSB7XHJcbiAgICAgIC8vIFN5bmMgZGF0YS5cclxuICAgICAgdHgucHV0KGZpbGVJbm9kZS5pZCwgZGF0YSwgdHJ1ZSk7XHJcbiAgICAgIC8vIFN5bmMgbWV0YWRhdGEuXHJcbiAgICAgIGlmIChpbm9kZUNoYW5nZWQpIHtcclxuICAgICAgICB0eC5wdXQoZmlsZUlub2RlSWQsIGZpbGVJbm9kZS50b0J1ZmZlcigpLCB0cnVlKTtcclxuICAgICAgfVxyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICB0eC5hYm9ydCgpO1xyXG4gICAgICB0aHJvdyBlO1xyXG4gICAgfVxyXG4gICAgdHguY29tbWl0KCk7XHJcbiAgfVxyXG59XHJcblxyXG4vKipcclxuICogUmVwcmVzZW50cyBhbiAqYXN5bmNocm9ub3VzKiBrZXktdmFsdWUgc3RvcmUuXHJcbiAqL1xyXG5leHBvcnQgaW50ZXJmYWNlIEFzeW5jS2V5VmFsdWVTdG9yZSB7XHJcbiAgLyoqXHJcbiAgICogVGhlIG5hbWUgb2YgdGhlIGtleS12YWx1ZSBzdG9yZS5cclxuICAgKi9cclxuICBuYW1lKCk6IHN0cmluZztcclxuICAvKipcclxuICAgKiBFbXB0aWVzIHRoZSBrZXktdmFsdWUgc3RvcmUgY29tcGxldGVseS5cclxuICAgKi9cclxuICBjbGVhcihjYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCk6IHZvaWQ7XHJcbiAgLyoqXHJcbiAgICogQmVnaW5zIGEgcmVhZC13cml0ZSB0cmFuc2FjdGlvbi5cclxuICAgKi9cclxuICBiZWdpblRyYW5zYWN0aW9uKHR5cGU6ICdyZWFkd3JpdGUnKTogQXN5bmNLZXlWYWx1ZVJXVHJhbnNhY3Rpb247XHJcbiAgLyoqXHJcbiAgICogQmVnaW5zIGEgcmVhZC1vbmx5IHRyYW5zYWN0aW9uLlxyXG4gICAqL1xyXG4gIGJlZ2luVHJhbnNhY3Rpb24odHlwZTogJ3JlYWRvbmx5Jyk6IEFzeW5jS2V5VmFsdWVST1RyYW5zYWN0aW9uO1xyXG4gIGJlZ2luVHJhbnNhY3Rpb24odHlwZTogc3RyaW5nKTogQXN5bmNLZXlWYWx1ZVJPVHJhbnNhY3Rpb247XHJcbn1cclxuXHJcbi8qKlxyXG4gKiBSZXByZXNlbnRzIGFuIGFzeW5jaHJvbm91cyByZWFkLW9ubHkgdHJhbnNhY3Rpb24uXHJcbiAqL1xyXG5leHBvcnQgaW50ZXJmYWNlIEFzeW5jS2V5VmFsdWVST1RyYW5zYWN0aW9uIHtcclxuICAvKipcclxuICAgKiBSZXRyaWV2ZXMgdGhlIGRhdGEgYXQgdGhlIGdpdmVuIGtleS5cclxuICAgKiBAcGFyYW0ga2V5IFRoZSBrZXkgdG8gbG9vayB1bmRlciBmb3IgZGF0YS5cclxuICAgKi9cclxuICBnZXQoa2V5OiBzdHJpbmcsIGNiOiAoZTogQXBpRXJyb3IsIGRhdGE/OiBOb2RlQnVmZmVyKSA9PiB2b2lkKTogdm9pZDtcclxufVxyXG5cclxuLyoqXHJcbiAqIFJlcHJlc2VudHMgYW4gYXN5bmNocm9ub3VzIHJlYWQtd3JpdGUgdHJhbnNhY3Rpb24uXHJcbiAqL1xyXG5leHBvcnQgaW50ZXJmYWNlIEFzeW5jS2V5VmFsdWVSV1RyYW5zYWN0aW9uIGV4dGVuZHMgQXN5bmNLZXlWYWx1ZVJPVHJhbnNhY3Rpb24ge1xyXG4gIC8qKlxyXG4gICAqIEFkZHMgdGhlIGRhdGEgdG8gdGhlIHN0b3JlIHVuZGVyIHRoZSBnaXZlbiBrZXkuIE92ZXJ3cml0ZXMgYW55IGV4aXN0aW5nXHJcbiAgICogZGF0YS5cclxuICAgKiBAcGFyYW0ga2V5IFRoZSBrZXkgdG8gYWRkIHRoZSBkYXRhIHVuZGVyLlxyXG4gICAqIEBwYXJhbSBkYXRhIFRoZSBkYXRhIHRvIGFkZCB0byB0aGUgc3RvcmUuXHJcbiAgICogQHBhcmFtIG92ZXJ3cml0ZSBJZiAndHJ1ZScsIG92ZXJ3cml0ZSBhbnkgZXhpc3RpbmcgZGF0YS4gSWYgJ2ZhbHNlJyxcclxuICAgKiAgIGF2b2lkcyB3cml0aW5nIHRoZSBkYXRhIGlmIHRoZSBrZXkgZXhpc3RzLlxyXG4gICAqIEBwYXJhbSBjYiBUcmlnZ2VyZWQgd2l0aCBhbiBlcnJvciBhbmQgd2hldGhlciBvciBub3QgdGhlIHZhbHVlIHdhc1xyXG4gICAqICAgY29tbWl0dGVkLlxyXG4gICAqL1xyXG4gIHB1dChrZXk6IHN0cmluZywgZGF0YTogTm9kZUJ1ZmZlciwgb3ZlcndyaXRlOiBib29sZWFuLCBjYjogKGU6IEFwaUVycm9yLFxyXG4gICAgY29tbWl0dGVkPzogYm9vbGVhbikgPT4gdm9pZCk6IHZvaWQ7XHJcbiAgLyoqXHJcbiAgICogRGVsZXRlcyB0aGUgZGF0YSBhdCB0aGUgZ2l2ZW4ga2V5LlxyXG4gICAqIEBwYXJhbSBrZXkgVGhlIGtleSB0byBkZWxldGUgZnJvbSB0aGUgc3RvcmUuXHJcbiAgICovXHJcbiAgZGVsKGtleTogc3RyaW5nLCBjYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCk6IHZvaWQ7XHJcbiAgLyoqXHJcbiAgICogQ29tbWl0cyB0aGUgdHJhbnNhY3Rpb24uXHJcbiAgICovXHJcbiAgY29tbWl0KGNiOiAoZT86IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZDtcclxuICAvKipcclxuICAgKiBBYm9ydHMgYW5kIHJvbGxzIGJhY2sgdGhlIHRyYW5zYWN0aW9uLlxyXG4gICAqL1xyXG4gIGFib3J0KGNiOiAoZT86IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZDtcclxufVxyXG5cclxuZXhwb3J0IGNsYXNzIEFzeW5jS2V5VmFsdWVGaWxlIGV4dGVuZHMgcHJlbG9hZF9maWxlLlByZWxvYWRGaWxlPEFzeW5jS2V5VmFsdWVGaWxlU3lzdGVtPiBpbXBsZW1lbnRzIGZpbGUuRmlsZSB7XHJcbiAgY29uc3RydWN0b3IoX2ZzOiBBc3luY0tleVZhbHVlRmlsZVN5c3RlbSwgX3BhdGg6IHN0cmluZywgX2ZsYWc6IGZpbGVfZmxhZy5GaWxlRmxhZywgX3N0YXQ6IFN0YXRzLCBjb250ZW50cz86IE5vZGVCdWZmZXIpIHtcclxuICAgIHN1cGVyKF9mcywgX3BhdGgsIF9mbGFnLCBfc3RhdCwgY29udGVudHMpO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIHN5bmMoY2I6IChlPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIGlmICh0aGlzLmlzRGlydHkoKSkge1xyXG4gICAgICB0aGlzLl9mcy5fc3luYyh0aGlzLmdldFBhdGgoKSwgdGhpcy5nZXRCdWZmZXIoKSwgdGhpcy5nZXRTdGF0cygpLCAoZT86IEFwaUVycm9yKSA9PiB7XHJcbiAgICAgICAgaWYgKCFlKSB7XHJcbiAgICAgICAgICB0aGlzLnJlc2V0RGlydHkoKTtcclxuICAgICAgICB9XHJcbiAgICAgICAgY2IoZSk7XHJcbiAgICAgIH0pO1xyXG4gICAgfSBlbHNlIHtcclxuICAgICAgY2IoKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIHB1YmxpYyBjbG9zZShjYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgdGhpcy5zeW5jKGNiKTtcclxuICB9XHJcbn1cclxuXHJcbi8qKlxyXG4gKiBBbiBcIkFzeW5jaHJvbm91cyBrZXktdmFsdWUgZmlsZSBzeXN0ZW1cIi4gU3RvcmVzIGRhdGEgdG8vcmV0cmlldmVzIGRhdGEgZnJvbVxyXG4gKiBhbiB1bmRlcmx5aW5nIGFzeW5jaHJvbm91cyBrZXktdmFsdWUgc3RvcmUuXHJcbiAqL1xyXG5leHBvcnQgY2xhc3MgQXN5bmNLZXlWYWx1ZUZpbGVTeXN0ZW0gZXh0ZW5kcyBmaWxlX3N5c3RlbS5CYXNlRmlsZVN5c3RlbSB7XHJcbiAgcHJpdmF0ZSBzdG9yZTogQXN5bmNLZXlWYWx1ZVN0b3JlO1xyXG5cclxuICAvKipcclxuICAgKiBJbml0aWFsaXplcyB0aGUgZmlsZSBzeXN0ZW0uIFR5cGljYWxseSBjYWxsZWQgYnkgc3ViY2xhc3NlcycgYXN5bmNcclxuICAgKiBjb25zdHJ1Y3RvcnMuXHJcbiAgICovXHJcbiAgcHVibGljIGluaXQoc3RvcmU6IEFzeW5jS2V5VmFsdWVTdG9yZSwgY2I6IChlPzogQXBpRXJyb3IpID0+IHZvaWQpIHtcclxuICAgIHRoaXMuc3RvcmUgPSBzdG9yZTtcclxuICAgIC8vIElOVkFSSUFOVDogRW5zdXJlIHRoYXQgdGhlIHJvb3QgZXhpc3RzLlxyXG4gICAgdGhpcy5tYWtlUm9vdERpcmVjdG9yeShjYik7XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgc3RhdGljIGlzQXZhaWxhYmxlKCk6IGJvb2xlYW4geyByZXR1cm4gdHJ1ZTsgfVxyXG4gIHB1YmxpYyBnZXROYW1lKCk6IHN0cmluZyB7IHJldHVybiB0aGlzLnN0b3JlLm5hbWUoKTsgfVxyXG4gIHB1YmxpYyBpc1JlYWRPbmx5KCk6IGJvb2xlYW4geyByZXR1cm4gZmFsc2U7IH1cclxuICBwdWJsaWMgc3VwcG9ydHNTeW1saW5rcygpOiBib29sZWFuIHsgcmV0dXJuIGZhbHNlOyB9XHJcbiAgcHVibGljIHN1cHBvcnRzUHJvcHMoKTogYm9vbGVhbiB7IHJldHVybiBmYWxzZTsgfVxyXG4gIHB1YmxpYyBzdXBwb3J0c1N5bmNoKCk6IGJvb2xlYW4geyByZXR1cm4gZmFsc2U7IH1cclxuXHJcbiAgLyoqXHJcbiAgICogQ2hlY2tzIGlmIHRoZSByb290IGRpcmVjdG9yeSBleGlzdHMuIENyZWF0ZXMgaXQgaWYgaXQgZG9lc24ndC5cclxuICAgKi9cclxuICBwcml2YXRlIG1ha2VSb290RGlyZWN0b3J5KGNiOiAoZT86IEFwaUVycm9yKSA9PiB2b2lkKSB7XHJcbiAgICB2YXIgdHggPSB0aGlzLnN0b3JlLmJlZ2luVHJhbnNhY3Rpb24oJ3JlYWR3cml0ZScpO1xyXG4gICAgdHguZ2V0KFJPT1RfTk9ERV9JRCwgKGU6IEFwaUVycm9yLCBkYXRhPzogTm9kZUJ1ZmZlcikgPT4ge1xyXG4gICAgICBpZiAoZSB8fCBkYXRhID09PSB1bmRlZmluZWQpIHtcclxuICAgICAgICAvLyBDcmVhdGUgbmV3IGlub2RlLlxyXG4gICAgICAgIHZhciBjdXJyVGltZSA9IChuZXcgRGF0ZSgpKS5nZXRUaW1lKCksXHJcbiAgICAgICAgICAvLyBNb2RlIDA2NjZcclxuICAgICAgICAgIGRpcklub2RlID0gbmV3IElub2RlKEdlbmVyYXRlUmFuZG9tSUQoKSwgNDA5NiwgNTExIHwgRmlsZVR5cGUuRElSRUNUT1JZLCBjdXJyVGltZSwgY3VyclRpbWUsIGN1cnJUaW1lKTtcclxuICAgICAgICAvLyBJZiB0aGUgcm9vdCBkb2Vzbid0IGV4aXN0LCB0aGUgZmlyc3QgcmFuZG9tIElEIHNob3VsZG4ndCBleGlzdCxcclxuICAgICAgICAvLyBlaXRoZXIuXHJcbiAgICAgICAgdHgucHV0KGRpcklub2RlLmlkLCBuZXcgQnVmZmVyKFwie31cIiksIGZhbHNlLCAoZT86IEFwaUVycm9yKSA9PiB7XHJcbiAgICAgICAgICBpZiAobm9FcnJvclR4KGUsIHR4LCBjYikpIHtcclxuICAgICAgICAgICAgdHgucHV0KFJPT1RfTk9ERV9JRCwgZGlySW5vZGUudG9CdWZmZXIoKSwgZmFsc2UsIChlPzogQXBpRXJyb3IpID0+IHtcclxuICAgICAgICAgICAgICBpZiAoZSkge1xyXG4gICAgICAgICAgICAgICAgdHguYWJvcnQoKCkgPT4geyBjYihlKTsgfSk7XHJcbiAgICAgICAgICAgICAgfSBlbHNlIHtcclxuICAgICAgICAgICAgICAgIHR4LmNvbW1pdChjYik7XHJcbiAgICAgICAgICAgICAgfVxyXG4gICAgICAgICAgICB9KTtcclxuICAgICAgICAgIH1cclxuICAgICAgICB9KTtcclxuICAgICAgfSBlbHNlIHtcclxuICAgICAgICAvLyBXZSdyZSBnb29kLlxyXG4gICAgICAgIHR4LmNvbW1pdChjYik7XHJcbiAgICAgIH1cclxuICAgIH0pO1xyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogSGVscGVyIGZ1bmN0aW9uIGZvciBmaW5kSU5vZGUuXHJcbiAgICogQHBhcmFtIHBhcmVudCBUaGUgcGFyZW50IGRpcmVjdG9yeSBvZiB0aGUgZmlsZSB3ZSBhcmUgYXR0ZW1wdGluZyB0byBmaW5kLlxyXG4gICAqIEBwYXJhbSBmaWxlbmFtZSBUaGUgZmlsZW5hbWUgb2YgdGhlIGlub2RlIHdlIGFyZSBhdHRlbXB0aW5nIHRvIGZpbmQsIG1pbnVzXHJcbiAgICogICB0aGUgcGFyZW50LlxyXG4gICAqIEBwYXJhbSBjYiBQYXNzZWQgYW4gZXJyb3Igb3IgdGhlIElEIG9mIHRoZSBmaWxlJ3MgaW5vZGUgaW4gdGhlIGZpbGUgc3lzdGVtLlxyXG4gICAqL1xyXG4gIHByaXZhdGUgX2ZpbmRJTm9kZSh0eDogQXN5bmNLZXlWYWx1ZVJPVHJhbnNhY3Rpb24sIHBhcmVudDogc3RyaW5nLCBmaWxlbmFtZTogc3RyaW5nLCBjYjogKGU6IEFwaUVycm9yLCBpZD86IHN0cmluZykgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgdmFyIGhhbmRsZV9kaXJlY3RvcnlfbGlzdGluZ3MgPSAoZTogQXBpRXJyb3IsIGlub2RlPzogSW5vZGUsIGRpckxpc3Q/OiB7W25hbWU6IHN0cmluZ106IHN0cmluZ30pOiB2b2lkID0+IHtcclxuICAgICAgaWYgKGUpIHtcclxuICAgICAgICBjYihlKVxyXG4gICAgICB9IGVsc2UgaWYgKGRpckxpc3RbZmlsZW5hbWVdKSB7XHJcbiAgICAgICAgY2IobnVsbCwgZGlyTGlzdFtmaWxlbmFtZV0pO1xyXG4gICAgICB9IGVsc2Uge1xyXG4gICAgICAgIGNiKEFwaUVycm9yLkVOT0VOVChwYXRoLnJlc29sdmUocGFyZW50LCBmaWxlbmFtZSkpKTtcclxuICAgICAgfVxyXG4gICAgfTtcclxuXHJcbiAgICBpZiAocGFyZW50ID09PSAnLycpIHtcclxuICAgICAgaWYgKGZpbGVuYW1lID09PSAnJykge1xyXG4gICAgICAgIC8vIEJBU0UgQ0FTRSAjMTogUmV0dXJuIHRoZSByb290J3MgSUQuXHJcbiAgICAgICAgY2IobnVsbCwgUk9PVF9OT0RFX0lEKTtcclxuICAgICAgfSBlbHNlIHtcclxuICAgICAgICAvLyBCQVNFIENBU0UgIzI6IEZpbmQgdGhlIGl0ZW0gaW4gdGhlIHJvb3Qgbm9kZS5cclxuICAgICAgICB0aGlzLmdldElOb2RlKHR4LCBwYXJlbnQsIFJPT1RfTk9ERV9JRCwgKGU6IEFwaUVycm9yLCBpbm9kZT86IElub2RlKTogdm9pZCA9PiB7XHJcbiAgICAgICAgICBpZiAobm9FcnJvcihlLCBjYikpIHtcclxuICAgICAgICAgICAgdGhpcy5nZXREaXJMaXN0aW5nKHR4LCBwYXJlbnQsIGlub2RlLCAoZTogQXBpRXJyb3IsIGRpckxpc3Q/OiB7W25hbWU6IHN0cmluZ106IHN0cmluZ30pOiB2b2lkID0+IHtcclxuICAgICAgICAgICAgICAvLyBoYW5kbGVfZGlyZWN0b3J5X2xpc3RpbmdzIHdpbGwgaGFuZGxlIGUgZm9yIHVzLlxyXG4gICAgICAgICAgICAgIGhhbmRsZV9kaXJlY3RvcnlfbGlzdGluZ3MoZSwgaW5vZGUsIGRpckxpc3QpO1xyXG4gICAgICAgICAgICB9KTtcclxuICAgICAgICAgIH1cclxuICAgICAgICB9KTtcclxuICAgICAgfVxyXG4gICAgfSBlbHNlIHtcclxuICAgICAgLy8gR2V0IHRoZSBwYXJlbnQgZGlyZWN0b3J5J3MgSU5vZGUsIGFuZCBmaW5kIHRoZSBmaWxlIGluIGl0cyBkaXJlY3RvcnlcclxuICAgICAgLy8gbGlzdGluZy5cclxuICAgICAgdGhpcy5maW5kSU5vZGVBbmREaXJMaXN0aW5nKHR4LCBwYXJlbnQsIGhhbmRsZV9kaXJlY3RvcnlfbGlzdGluZ3MpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogRmluZHMgdGhlIElub2RlIG9mIHRoZSBnaXZlbiBwYXRoLlxyXG4gICAqIEBwYXJhbSBwIFRoZSBwYXRoIHRvIGxvb2sgdXAuXHJcbiAgICogQHBhcmFtIGNiIFBhc3NlZCBhbiBlcnJvciBvciB0aGUgSW5vZGUgb2YgdGhlIHBhdGggcC5cclxuICAgKiBAdG9kbyBtZW1vaXplL2NhY2hlXHJcbiAgICovXHJcbiAgcHJpdmF0ZSBmaW5kSU5vZGUodHg6IEFzeW5jS2V5VmFsdWVST1RyYW5zYWN0aW9uLCBwOiBzdHJpbmcsIGNiOiAoZTogQXBpRXJyb3IsIGlub2RlPzogSW5vZGUpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHRoaXMuX2ZpbmRJTm9kZSh0eCwgcGF0aC5kaXJuYW1lKHApLCBwYXRoLmJhc2VuYW1lKHApLCAoZTogQXBpRXJyb3IsIGlkPzogc3RyaW5nKTogdm9pZCA9PiB7XHJcbiAgICAgIGlmIChub0Vycm9yKGUsIGNiKSkge1xyXG4gICAgICAgIHRoaXMuZ2V0SU5vZGUodHgsIHAsIGlkLCBjYik7XHJcbiAgICAgIH1cclxuICAgIH0pO1xyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogR2l2ZW4gdGhlIElEIG9mIGEgbm9kZSwgcmV0cmlldmVzIHRoZSBjb3JyZXNwb25kaW5nIElub2RlLlxyXG4gICAqIEBwYXJhbSB0eCBUaGUgdHJhbnNhY3Rpb24gdG8gdXNlLlxyXG4gICAqIEBwYXJhbSBwIFRoZSBjb3JyZXNwb25kaW5nIHBhdGggdG8gdGhlIGZpbGUgKHVzZWQgZm9yIGVycm9yIG1lc3NhZ2VzKS5cclxuICAgKiBAcGFyYW0gaWQgVGhlIElEIHRvIGxvb2sgdXAuXHJcbiAgICogQHBhcmFtIGNiIFBhc3NlZCBhbiBlcnJvciBvciB0aGUgaW5vZGUgdW5kZXIgdGhlIGdpdmVuIGlkLlxyXG4gICAqL1xyXG4gIHByaXZhdGUgZ2V0SU5vZGUodHg6IEFzeW5jS2V5VmFsdWVST1RyYW5zYWN0aW9uLCBwOiBzdHJpbmcsIGlkOiBzdHJpbmcsIGNiOiAoZTogQXBpRXJyb3IsIGlub2RlPzogSW5vZGUpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHR4LmdldChpZCwgKGU6IEFwaUVycm9yLCBkYXRhPzogTm9kZUJ1ZmZlcik6IHZvaWQgPT4ge1xyXG4gICAgICBpZiAobm9FcnJvcihlLCBjYikpIHtcclxuICAgICAgICBpZiAoZGF0YSA9PT0gdW5kZWZpbmVkKSB7XHJcbiAgICAgICAgICBjYihBcGlFcnJvci5FTk9FTlQocCkpO1xyXG4gICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICBjYihudWxsLCBJbm9kZS5mcm9tQnVmZmVyKGRhdGEpKTtcclxuICAgICAgICB9XHJcbiAgICAgIH1cclxuICAgIH0pO1xyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogR2l2ZW4gdGhlIElub2RlIG9mIGEgZGlyZWN0b3J5LCByZXRyaWV2ZXMgdGhlIGNvcnJlc3BvbmRpbmcgZGlyZWN0b3J5XHJcbiAgICogbGlzdGluZy5cclxuICAgKi9cclxuICBwcml2YXRlIGdldERpckxpc3RpbmcodHg6IEFzeW5jS2V5VmFsdWVST1RyYW5zYWN0aW9uLCBwOiBzdHJpbmcsIGlub2RlOiBJbm9kZSwgY2I6IChlOiBBcGlFcnJvciwgbGlzdGluZz86IHsgW2ZpbGVOYW1lOiBzdHJpbmddOiBzdHJpbmcgfSkgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgaWYgKCFpbm9kZS5pc0RpcmVjdG9yeSgpKSB7XHJcbiAgICAgIGNiKEFwaUVycm9yLkVOT1RESVIocCkpO1xyXG4gICAgfSBlbHNlIHtcclxuICAgICAgdHguZ2V0KGlub2RlLmlkLCAoZTogQXBpRXJyb3IsIGRhdGE/OiBOb2RlQnVmZmVyKTogdm9pZCA9PiB7XHJcbiAgICAgICAgaWYgKG5vRXJyb3IoZSwgY2IpKSB7XHJcbiAgICAgICAgICB0cnkge1xyXG4gICAgICAgICAgICBjYihudWxsLCBKU09OLnBhcnNlKGRhdGEudG9TdHJpbmcoKSkpO1xyXG4gICAgICAgICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICAgICAgICAvLyBPY2N1cnMgd2hlbiBkYXRhIGlzIHVuZGVmaW5lZCwgb3IgY29ycmVzcG9uZHMgdG8gc29tZXRoaW5nIG90aGVyXHJcbiAgICAgICAgICAgIC8vIHRoYW4gYSBkaXJlY3RvcnkgbGlzdGluZy4gVGhlIGxhdHRlciBzaG91bGQgbmV2ZXIgb2NjdXIgdW5sZXNzXHJcbiAgICAgICAgICAgIC8vIHRoZSBmaWxlIHN5c3RlbSBpcyBjb3JydXB0ZWQuXHJcbiAgICAgICAgICAgIGNiKEFwaUVycm9yLkVOT0VOVChwKSk7XHJcbiAgICAgICAgICB9XHJcbiAgICAgICAgfVxyXG4gICAgICB9KTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIEdpdmVuIGEgcGF0aCB0byBhIGRpcmVjdG9yeSwgcmV0cmlldmVzIHRoZSBjb3JyZXNwb25kaW5nIElOb2RlIGFuZFxyXG4gICAqIGRpcmVjdG9yeSBsaXN0aW5nLlxyXG4gICAqL1xyXG4gIHByaXZhdGUgZmluZElOb2RlQW5kRGlyTGlzdGluZyh0eDogQXN5bmNLZXlWYWx1ZVJPVHJhbnNhY3Rpb24sIHA6IHN0cmluZywgY2I6IChlOiBBcGlFcnJvciwgaW5vZGU/OiBJbm9kZSwgbGlzdGluZz86IHsgW2ZpbGVOYW1lOiBzdHJpbmddOiBzdHJpbmcgfSkgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgdGhpcy5maW5kSU5vZGUodHgsIHAsIChlOiBBcGlFcnJvciwgaW5vZGU/OiBJbm9kZSk6IHZvaWQgPT4ge1xyXG4gICAgICBpZiAobm9FcnJvcihlLCBjYikpIHtcclxuICAgICAgICB0aGlzLmdldERpckxpc3RpbmcodHgsIHAsIGlub2RlLCAoZSwgbGlzdGluZz8pID0+IHtcclxuICAgICAgICAgIGlmIChub0Vycm9yKGUsIGNiKSkge1xyXG4gICAgICAgICAgICBjYihudWxsLCBpbm9kZSwgbGlzdGluZyk7XHJcbiAgICAgICAgICB9XHJcbiAgICAgICAgfSk7XHJcbiAgICAgIH1cclxuICAgIH0pO1xyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogQWRkcyBhIG5ldyBub2RlIHVuZGVyIGEgcmFuZG9tIElELiBSZXRyaWVzIDUgdGltZXMgYmVmb3JlIGdpdmluZyB1cCBpblxyXG4gICAqIHRoZSBleGNlZWRpbmdseSB1bmxpa2VseSBjaGFuY2UgdGhhdCB3ZSB0cnkgdG8gcmV1c2UgYSByYW5kb20gR1VJRC5cclxuICAgKiBAcGFyYW0gY2IgUGFzc2VkIGFuIGVycm9yIG9yIHRoZSBHVUlEIHRoYXQgdGhlIGRhdGEgd2FzIHN0b3JlZCB1bmRlci5cclxuICAgKi9cclxuICBwcml2YXRlIGFkZE5ld05vZGUodHg6IEFzeW5jS2V5VmFsdWVSV1RyYW5zYWN0aW9uLCBkYXRhOiBOb2RlQnVmZmVyLCBjYjogKGU6IEFwaUVycm9yLCBndWlkPzogc3RyaW5nKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICB2YXIgcmV0cmllcyA9IDAsIGN1cnJJZDogc3RyaW5nLFxyXG4gICAgICByZXJvbGwgPSAoKSA9PiB7XHJcbiAgICAgICAgaWYgKCsrcmV0cmllcyA9PT0gNSkge1xyXG4gICAgICAgICAgLy8gTWF4IHJldHJpZXMgaGl0LiBSZXR1cm4gd2l0aCBhbiBlcnJvci5cclxuICAgICAgICAgIGNiKG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRUlPLCAnVW5hYmxlIHRvIGNvbW1pdCBkYXRhIHRvIGtleS12YWx1ZSBzdG9yZS4nKSk7XHJcbiAgICAgICAgfSBlbHNlIHtcclxuICAgICAgICAgIC8vIFRyeSBhZ2Fpbi5cclxuICAgICAgICAgIGN1cnJJZCA9IEdlbmVyYXRlUmFuZG9tSUQoKTtcclxuICAgICAgICAgIHR4LnB1dChjdXJySWQsIGRhdGEsIGZhbHNlLCAoZTogQXBpRXJyb3IsIGNvbW1pdHRlZD86IGJvb2xlYW4pID0+IHtcclxuICAgICAgICAgICAgaWYgKGUgfHwgIWNvbW1pdHRlZCkge1xyXG4gICAgICAgICAgICAgIHJlcm9sbCgpO1xyXG4gICAgICAgICAgICB9IGVsc2Uge1xyXG4gICAgICAgICAgICAgIC8vIFN1Y2Nlc3NmdWxseSBzdG9yZWQgdW5kZXIgJ2N1cnJJZCcuXHJcbiAgICAgICAgICAgICAgY2IobnVsbCwgY3VycklkKTtcclxuICAgICAgICAgICAgfVxyXG4gICAgICAgICAgfSk7XHJcbiAgICAgICAgfVxyXG4gICAgICB9O1xyXG4gICAgcmVyb2xsKCk7XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBDb21taXRzIGEgbmV3IGZpbGUgKHdlbGwsIGEgRklMRSBvciBhIERJUkVDVE9SWSkgdG8gdGhlIGZpbGUgc3lzdGVtIHdpdGhcclxuICAgKiB0aGUgZ2l2ZW4gbW9kZS5cclxuICAgKiBOb3RlOiBUaGlzIHdpbGwgY29tbWl0IHRoZSB0cmFuc2FjdGlvbi5cclxuICAgKiBAcGFyYW0gcCBUaGUgcGF0aCB0byB0aGUgbmV3IGZpbGUuXHJcbiAgICogQHBhcmFtIHR5cGUgVGhlIHR5cGUgb2YgdGhlIG5ldyBmaWxlLlxyXG4gICAqIEBwYXJhbSBtb2RlIFRoZSBtb2RlIHRvIGNyZWF0ZSB0aGUgbmV3IGZpbGUgd2l0aC5cclxuICAgKiBAcGFyYW0gZGF0YSBUaGUgZGF0YSB0byBzdG9yZSBhdCB0aGUgZmlsZSdzIGRhdGEgbm9kZS5cclxuICAgKiBAcGFyYW0gY2IgUGFzc2VkIGFuIGVycm9yIG9yIHRoZSBJbm9kZSBmb3IgdGhlIG5ldyBmaWxlLlxyXG4gICAqL1xyXG4gIHByaXZhdGUgY29tbWl0TmV3RmlsZSh0eDogQXN5bmNLZXlWYWx1ZVJXVHJhbnNhY3Rpb24sIHA6IHN0cmluZywgdHlwZTogRmlsZVR5cGUsIG1vZGU6IG51bWJlciwgZGF0YTogTm9kZUJ1ZmZlciwgY2I6IChlOiBBcGlFcnJvciwgaW5vZGU/OiBJbm9kZSkgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgdmFyIHBhcmVudERpciA9IHBhdGguZGlybmFtZShwKSxcclxuICAgICAgZm5hbWUgPSBwYXRoLmJhc2VuYW1lKHApLFxyXG4gICAgICBjdXJyVGltZSA9IChuZXcgRGF0ZSgpKS5nZXRUaW1lKCk7XHJcblxyXG4gICAgLy8gSW52YXJpYW50OiBUaGUgcm9vdCBhbHdheXMgZXhpc3RzLlxyXG4gICAgLy8gSWYgd2UgZG9uJ3QgY2hlY2sgdGhpcyBwcmlvciB0byB0YWtpbmcgc3RlcHMgYmVsb3csIHdlIHdpbGwgY3JlYXRlIGFcclxuICAgIC8vIGZpbGUgd2l0aCBuYW1lICcnIGluIHJvb3Qgc2hvdWxkIHAgPT0gJy8nLlxyXG4gICAgaWYgKHAgPT09ICcvJykge1xyXG4gICAgICByZXR1cm4gY2IoQXBpRXJyb3IuRUVYSVNUKHApKTtcclxuICAgIH1cclxuXHJcbiAgICAvLyBMZXQncyBidWlsZCBhIHB5cmFtaWQgb2YgY29kZSFcclxuXHJcbiAgICAvLyBTdGVwIDE6IEdldCB0aGUgcGFyZW50IGRpcmVjdG9yeSdzIGlub2RlIGFuZCBkaXJlY3RvcnkgbGlzdGluZ1xyXG4gICAgdGhpcy5maW5kSU5vZGVBbmREaXJMaXN0aW5nKHR4LCBwYXJlbnREaXIsIChlOiBBcGlFcnJvciwgcGFyZW50Tm9kZT86IElub2RlLCBkaXJMaXN0aW5nPzoge1tuYW1lOiBzdHJpbmddOiBzdHJpbmd9KTogdm9pZCA9PiB7XHJcbiAgICAgIGlmIChub0Vycm9yVHgoZSwgdHgsIGNiKSkge1xyXG4gICAgICAgIGlmIChkaXJMaXN0aW5nW2ZuYW1lXSkge1xyXG4gICAgICAgICAgLy8gRmlsZSBhbHJlYWR5IGV4aXN0cy5cclxuICAgICAgICAgIHR4LmFib3J0KCgpID0+IHtcclxuICAgICAgICAgICAgY2IoQXBpRXJyb3IuRUVYSVNUKHApKTtcclxuICAgICAgICAgIH0pO1xyXG4gICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICAvLyBTdGVwIDI6IENvbW1pdCBkYXRhIHRvIHN0b3JlLlxyXG4gICAgICAgICAgdGhpcy5hZGROZXdOb2RlKHR4LCBkYXRhLCAoZTogQXBpRXJyb3IsIGRhdGFJZD86IHN0cmluZyk6IHZvaWQgPT4ge1xyXG4gICAgICAgICAgICBpZiAobm9FcnJvclR4KGUsIHR4LCBjYikpIHtcclxuICAgICAgICAgICAgICAvLyBTdGVwIDM6IENvbW1pdCB0aGUgZmlsZSdzIGlub2RlIHRvIHRoZSBzdG9yZS5cclxuICAgICAgICAgICAgICB2YXIgZmlsZUlub2RlID0gbmV3IElub2RlKGRhdGFJZCwgZGF0YS5sZW5ndGgsIG1vZGUgfCB0eXBlLCBjdXJyVGltZSwgY3VyclRpbWUsIGN1cnJUaW1lKTtcclxuICAgICAgICAgICAgICB0aGlzLmFkZE5ld05vZGUodHgsIGZpbGVJbm9kZS50b0J1ZmZlcigpLCAoZTogQXBpRXJyb3IsIGZpbGVJbm9kZUlkPzogc3RyaW5nKTogdm9pZCA9PiB7XHJcbiAgICAgICAgICAgICAgICBpZiAobm9FcnJvclR4KGUsIHR4LCBjYikpIHtcclxuICAgICAgICAgICAgICAgICAgLy8gU3RlcCA0OiBVcGRhdGUgcGFyZW50IGRpcmVjdG9yeSdzIGxpc3RpbmcuXHJcbiAgICAgICAgICAgICAgICAgIGRpckxpc3RpbmdbZm5hbWVdID0gZmlsZUlub2RlSWQ7XHJcbiAgICAgICAgICAgICAgICAgIHR4LnB1dChwYXJlbnROb2RlLmlkLCBuZXcgQnVmZmVyKEpTT04uc3RyaW5naWZ5KGRpckxpc3RpbmcpKSwgdHJ1ZSwgKGU6IEFwaUVycm9yKTogdm9pZCA9PiB7XHJcbiAgICAgICAgICAgICAgICAgICAgaWYgKG5vRXJyb3JUeChlLCB0eCwgY2IpKSB7XHJcbiAgICAgICAgICAgICAgICAgICAgICAvLyBTdGVwIDU6IENvbW1pdCBhbmQgcmV0dXJuIHRoZSBuZXcgaW5vZGUuXHJcbiAgICAgICAgICAgICAgICAgICAgICB0eC5jb21taXQoKGU/OiBBcGlFcnJvcik6IHZvaWQgPT4ge1xyXG4gICAgICAgICAgICAgICAgICAgICAgICBpZiAobm9FcnJvclR4KGUsIHR4LCBjYikpIHtcclxuICAgICAgICAgICAgICAgICAgICAgICAgICBjYihudWxsLCBmaWxlSW5vZGUpO1xyXG4gICAgICAgICAgICAgICAgICAgICAgICB9XHJcbiAgICAgICAgICAgICAgICAgICAgICB9KTtcclxuICAgICAgICAgICAgICAgICAgICB9XHJcbiAgICAgICAgICAgICAgICAgIH0pO1xyXG4gICAgICAgICAgICAgICAgfVxyXG4gICAgICAgICAgICAgIH0pO1xyXG4gICAgICAgICAgICB9XHJcbiAgICAgICAgICB9KTtcclxuICAgICAgICB9XHJcbiAgICAgIH1cclxuICAgIH0pO1xyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogRGVsZXRlIGFsbCBjb250ZW50cyBzdG9yZWQgaW4gdGhlIGZpbGUgc3lzdGVtLlxyXG4gICAqL1xyXG4gIHB1YmxpYyBlbXB0eShjYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgdGhpcy5zdG9yZS5jbGVhcigoZT8pID0+IHtcclxuICAgICAgaWYgKG5vRXJyb3IoZSwgY2IpKSB7XHJcbiAgICAgICAgLy8gSU5WQVJJQU5UOiBSb290IGFsd2F5cyBleGlzdHMuXHJcbiAgICAgICAgdGhpcy5tYWtlUm9vdERpcmVjdG9yeShjYik7XHJcbiAgICAgIH1cclxuICAgIH0pO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIHJlbmFtZShvbGRQYXRoOiBzdHJpbmcsIG5ld1BhdGg6IHN0cmluZywgY2I6IChlPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHZhciB0eCA9IHRoaXMuc3RvcmUuYmVnaW5UcmFuc2FjdGlvbigncmVhZHdyaXRlJyksXHJcbiAgICAgIG9sZFBhcmVudCA9IHBhdGguZGlybmFtZShvbGRQYXRoKSwgb2xkTmFtZSA9IHBhdGguYmFzZW5hbWUob2xkUGF0aCksXHJcbiAgICAgIG5ld1BhcmVudCA9IHBhdGguZGlybmFtZShuZXdQYXRoKSwgbmV3TmFtZSA9IHBhdGguYmFzZW5hbWUobmV3UGF0aCksXHJcbiAgICAgIGlub2RlczogeyBbcGF0aDogc3RyaW5nXTogSW5vZGUgfSA9IHt9LFxyXG4gICAgICBsaXN0czoge1xyXG4gICAgICAgIFtwYXRoOiBzdHJpbmddOiB7IFtmaWxlOiBzdHJpbmddOiBzdHJpbmcgfVxyXG4gICAgICB9ID0ge30sXHJcbiAgICAgIGVycm9yT2NjdXJyZWQ6IGJvb2xlYW4gPSBmYWxzZTtcclxuXHJcbiAgICAvLyBJbnZhcmlhbnQ6IENhbid0IG1vdmUgYSBmb2xkZXIgaW5zaWRlIGl0c2VsZi5cclxuICAgIC8vIFRoaXMgZnVubnkgbGl0dGxlIGhhY2sgZW5zdXJlcyB0aGF0IHRoZSBjaGVjayBwYXNzZXMgb25seSBpZiBvbGRQYXRoXHJcbiAgICAvLyBpcyBhIHN1YnBhdGggb2YgbmV3UGFyZW50LiBXZSBhcHBlbmQgJy8nIHRvIGF2b2lkIG1hdGNoaW5nIGZvbGRlcnMgdGhhdFxyXG4gICAgLy8gYXJlIGEgc3Vic3RyaW5nIG9mIHRoZSBib3R0b20tbW9zdCBmb2xkZXIgaW4gdGhlIHBhdGguXHJcbiAgICBpZiAoKG5ld1BhcmVudCArICcvJykuaW5kZXhPZihvbGRQYXRoICsgJy8nKSA9PT0gMCkge1xyXG4gICAgICByZXR1cm4gY2IobmV3IEFwaUVycm9yKEVycm9yQ29kZS5FQlVTWSwgb2xkUGFyZW50KSk7XHJcbiAgICB9XHJcblxyXG4gICAgLyoqXHJcbiAgICAgKiBSZXNwb25zaWJsZSBmb3IgUGhhc2UgMiBvZiB0aGUgcmVuYW1lIG9wZXJhdGlvbjogTW9kaWZ5aW5nIGFuZFxyXG4gICAgICogY29tbWl0dGluZyB0aGUgZGlyZWN0b3J5IGxpc3RpbmdzLiBDYWxsZWQgb25jZSB3ZSBoYXZlIHN1Y2Nlc3NmdWxseVxyXG4gICAgICogcmV0cmlldmVkIGJvdGggdGhlIG9sZCBhbmQgbmV3IHBhcmVudCdzIGlub2RlcyBhbmQgbGlzdGluZ3MuXHJcbiAgICAgKi9cclxuICAgIHZhciB0aGVPbGVTd2l0Y2hhcm9vID0gKCk6IHZvaWQgPT4ge1xyXG4gICAgICAvLyBTYW5pdHkgY2hlY2s6IEVuc3VyZSBib3RoIHBhdGhzIGFyZSBwcmVzZW50LCBhbmQgbm8gZXJyb3IgaGFzIG9jY3VycmVkLlxyXG4gICAgICBpZiAoZXJyb3JPY2N1cnJlZCB8fCAhbGlzdHMuaGFzT3duUHJvcGVydHkob2xkUGFyZW50KSB8fCAhbGlzdHMuaGFzT3duUHJvcGVydHkobmV3UGFyZW50KSkge1xyXG4gICAgICAgIHJldHVybjtcclxuICAgICAgfVxyXG4gICAgICB2YXIgb2xkUGFyZW50TGlzdCA9IGxpc3RzW29sZFBhcmVudF0sIG9sZFBhcmVudElOb2RlID0gaW5vZGVzW29sZFBhcmVudF0sXHJcbiAgICAgICAgbmV3UGFyZW50TGlzdCA9IGxpc3RzW25ld1BhcmVudF0sIG5ld1BhcmVudElOb2RlID0gaW5vZGVzW25ld1BhcmVudF07XHJcblxyXG4gICAgICAvLyBEZWxldGUgZmlsZSBmcm9tIG9sZCBwYXJlbnQuXHJcbiAgICAgIGlmICghb2xkUGFyZW50TGlzdFtvbGROYW1lXSkge1xyXG4gICAgICAgIGNiKEFwaUVycm9yLkVOT0VOVChvbGRQYXRoKSk7XHJcbiAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgdmFyIGZpbGVJZCA9IG9sZFBhcmVudExpc3Rbb2xkTmFtZV07XHJcbiAgICAgICAgZGVsZXRlIG9sZFBhcmVudExpc3Rbb2xkTmFtZV07XHJcblxyXG4gICAgICAgIC8vIEZpbmlzaGVzIG9mZiB0aGUgcmVuYW1pbmcgcHJvY2VzcyBieSBhZGRpbmcgdGhlIGZpbGUgdG8gdGhlIG5ld1xyXG4gICAgICAgIC8vIHBhcmVudC5cclxuICAgICAgICB2YXIgY29tcGxldGVSZW5hbWUgPSAoKSA9PiB7XHJcbiAgICAgICAgICBuZXdQYXJlbnRMaXN0W25ld05hbWVdID0gZmlsZUlkO1xyXG4gICAgICAgICAgLy8gQ29tbWl0IG9sZCBwYXJlbnQncyBsaXN0LlxyXG4gICAgICAgICAgdHgucHV0KG9sZFBhcmVudElOb2RlLmlkLCBuZXcgQnVmZmVyKEpTT04uc3RyaW5naWZ5KG9sZFBhcmVudExpc3QpKSwgdHJ1ZSwgKGU6IEFwaUVycm9yKSA9PiB7XHJcbiAgICAgICAgICAgIGlmIChub0Vycm9yVHgoZSwgdHgsIGNiKSkge1xyXG4gICAgICAgICAgICAgIGlmIChvbGRQYXJlbnQgPT09IG5ld1BhcmVudCkge1xyXG4gICAgICAgICAgICAgICAgLy8gRE9ORSFcclxuICAgICAgICAgICAgICAgIHR4LmNvbW1pdChjYik7XHJcbiAgICAgICAgICAgICAgfSBlbHNlIHtcclxuICAgICAgICAgICAgICAgIC8vIENvbW1pdCBuZXcgcGFyZW50J3MgbGlzdC5cclxuICAgICAgICAgICAgICAgIHR4LnB1dChuZXdQYXJlbnRJTm9kZS5pZCwgbmV3IEJ1ZmZlcihKU09OLnN0cmluZ2lmeShuZXdQYXJlbnRMaXN0KSksIHRydWUsIChlOiBBcGlFcnJvcikgPT4ge1xyXG4gICAgICAgICAgICAgICAgICBpZiAobm9FcnJvclR4KGUsIHR4LCBjYikpIHtcclxuICAgICAgICAgICAgICAgICAgICB0eC5jb21taXQoY2IpO1xyXG4gICAgICAgICAgICAgICAgICB9XHJcbiAgICAgICAgICAgICAgICB9KTtcclxuICAgICAgICAgICAgICB9XHJcbiAgICAgICAgICAgIH1cclxuICAgICAgICAgIH0pO1xyXG4gICAgICAgIH07XHJcblxyXG4gICAgICAgIGlmIChuZXdQYXJlbnRMaXN0W25ld05hbWVdKSB7XHJcbiAgICAgICAgICAvLyAnbmV3UGF0aCcgYWxyZWFkeSBleGlzdHMuIENoZWNrIGlmIGl0J3MgYSBmaWxlIG9yIGEgZGlyZWN0b3J5LCBhbmRcclxuICAgICAgICAgIC8vIGFjdCBhY2NvcmRpbmdseS5cclxuICAgICAgICAgIHRoaXMuZ2V0SU5vZGUodHgsIG5ld1BhdGgsIG5ld1BhcmVudExpc3RbbmV3TmFtZV0sIChlOiBBcGlFcnJvciwgaW5vZGU/OiBJbm9kZSkgPT4ge1xyXG4gICAgICAgICAgICBpZiAobm9FcnJvclR4KGUsIHR4LCBjYikpIHtcclxuICAgICAgICAgICAgICBpZiAoaW5vZGUuaXNGaWxlKCkpIHtcclxuICAgICAgICAgICAgICAgIC8vIERlbGV0ZSB0aGUgZmlsZSBhbmQgY29udGludWUuXHJcbiAgICAgICAgICAgICAgICB0eC5kZWwoaW5vZGUuaWQsIChlPzogQXBpRXJyb3IpID0+IHtcclxuICAgICAgICAgICAgICAgICAgaWYgKG5vRXJyb3JUeChlLCB0eCwgY2IpKSB7XHJcbiAgICAgICAgICAgICAgICAgICAgdHguZGVsKG5ld1BhcmVudExpc3RbbmV3TmFtZV0sIChlPzogQXBpRXJyb3IpID0+IHtcclxuICAgICAgICAgICAgICAgICAgICAgIGlmIChub0Vycm9yVHgoZSwgdHgsIGNiKSkge1xyXG4gICAgICAgICAgICAgICAgICAgICAgICBjb21wbGV0ZVJlbmFtZSgpO1xyXG4gICAgICAgICAgICAgICAgICAgICAgfVxyXG4gICAgICAgICAgICAgICAgICAgIH0pO1xyXG4gICAgICAgICAgICAgICAgICB9XHJcbiAgICAgICAgICAgICAgICB9KTtcclxuICAgICAgICAgICAgICB9IGVsc2Uge1xyXG4gICAgICAgICAgICAgICAgLy8gQ2FuJ3Qgb3ZlcndyaXRlIGEgZGlyZWN0b3J5IHVzaW5nIHJlbmFtZS5cclxuICAgICAgICAgICAgICAgIHR4LmFib3J0KChlPykgPT4ge1xyXG4gICAgICAgICAgICAgICAgICBjYihBcGlFcnJvci5FUEVSTShuZXdQYXRoKSk7XHJcbiAgICAgICAgICAgICAgICB9KTtcclxuICAgICAgICAgICAgICB9XHJcbiAgICAgICAgICAgIH1cclxuICAgICAgICAgIH0pO1xyXG4gICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICBjb21wbGV0ZVJlbmFtZSgpO1xyXG4gICAgICAgIH1cclxuICAgICAgfVxyXG4gICAgfTtcclxuXHJcbiAgICAvKipcclxuICAgICAqIEdyYWJzIGEgcGF0aCdzIGlub2RlIGFuZCBkaXJlY3RvcnkgbGlzdGluZywgYW5kIHNob3ZlcyBpdCBpbnRvIHRoZVxyXG4gICAgICogaW5vZGVzIGFuZCBsaXN0cyBoYXNoZXMuXHJcbiAgICAgKi9cclxuICAgIHZhciBwcm9jZXNzSW5vZGVBbmRMaXN0aW5ncyA9IChwOiBzdHJpbmcpOiB2b2lkID0+IHtcclxuICAgICAgdGhpcy5maW5kSU5vZGVBbmREaXJMaXN0aW5nKHR4LCBwLCAoZTogQXBpRXJyb3IsIG5vZGU/OiBJbm9kZSwgZGlyTGlzdD86IHtbbmFtZTogc3RyaW5nXTogc3RyaW5nfSk6IHZvaWQgPT4ge1xyXG4gICAgICAgIGlmIChlKSB7XHJcbiAgICAgICAgICBpZiAoIWVycm9yT2NjdXJyZWQpIHtcclxuICAgICAgICAgICAgZXJyb3JPY2N1cnJlZCA9IHRydWU7XHJcbiAgICAgICAgICAgIHR4LmFib3J0KCgpID0+IHtcclxuICAgICAgICAgICAgICBjYihlKTtcclxuICAgICAgICAgICAgfSk7XHJcbiAgICAgICAgICB9XHJcbiAgICAgICAgICAvLyBJZiBlcnJvciBoYXMgb2NjdXJyZWQgYWxyZWFkeSwganVzdCBzdG9wIGhlcmUuXHJcbiAgICAgICAgfSBlbHNlIHtcclxuICAgICAgICAgIGlub2Rlc1twXSA9IG5vZGU7XHJcbiAgICAgICAgICBsaXN0c1twXSA9IGRpckxpc3Q7XHJcbiAgICAgICAgICB0aGVPbGVTd2l0Y2hhcm9vKCk7XHJcbiAgICAgICAgfVxyXG4gICAgICB9KTtcclxuICAgIH07XHJcblxyXG4gICAgcHJvY2Vzc0lub2RlQW5kTGlzdGluZ3Mob2xkUGFyZW50KTtcclxuICAgIGlmIChvbGRQYXJlbnQgIT09IG5ld1BhcmVudCkge1xyXG4gICAgICBwcm9jZXNzSW5vZGVBbmRMaXN0aW5ncyhuZXdQYXJlbnQpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgcHVibGljIHN0YXQocDogc3RyaW5nLCBpc0xzdGF0OiBib29sZWFuLCBjYjogKGVycjogQXBpRXJyb3IsIHN0YXQ/OiBTdGF0cykgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgdmFyIHR4ID0gdGhpcy5zdG9yZS5iZWdpblRyYW5zYWN0aW9uKCdyZWFkb25seScpO1xyXG4gICAgdGhpcy5maW5kSU5vZGUodHgsIHAsIChlOiBBcGlFcnJvciwgaW5vZGU/OiBJbm9kZSk6IHZvaWQgPT4ge1xyXG4gICAgICBpZiAobm9FcnJvcihlLCBjYikpIHtcclxuICAgICAgICBjYihudWxsLCBpbm9kZS50b1N0YXRzKCkpO1xyXG4gICAgICB9XHJcbiAgICB9KTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBjcmVhdGVGaWxlKHA6IHN0cmluZywgZmxhZzogZmlsZV9mbGFnLkZpbGVGbGFnLCBtb2RlOiBudW1iZXIsIGNiOiAoZTogQXBpRXJyb3IsIGZpbGU/OiBmaWxlLkZpbGUpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHZhciB0eCA9IHRoaXMuc3RvcmUuYmVnaW5UcmFuc2FjdGlvbigncmVhZHdyaXRlJyksXHJcbiAgICAgIGRhdGEgPSBuZXcgQnVmZmVyKDApO1xyXG5cclxuICAgIHRoaXMuY29tbWl0TmV3RmlsZSh0eCwgcCwgRmlsZVR5cGUuRklMRSwgbW9kZSwgZGF0YSwgKGU6IEFwaUVycm9yLCBuZXdGaWxlPzogSW5vZGUpOiB2b2lkID0+IHtcclxuICAgICAgaWYgKG5vRXJyb3IoZSwgY2IpKSB7XHJcbiAgICAgICAgY2IobnVsbCwgbmV3IEFzeW5jS2V5VmFsdWVGaWxlKHRoaXMsIHAsIGZsYWcsIG5ld0ZpbGUudG9TdGF0cygpLCBkYXRhKSk7XHJcbiAgICAgIH1cclxuICAgIH0pO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIG9wZW5GaWxlKHA6IHN0cmluZywgZmxhZzogZmlsZV9mbGFnLkZpbGVGbGFnLCBjYjogKGU6IEFwaUVycm9yLCBmaWxlPzogZmlsZS5GaWxlKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICB2YXIgdHggPSB0aGlzLnN0b3JlLmJlZ2luVHJhbnNhY3Rpb24oJ3JlYWRvbmx5Jyk7XHJcbiAgICAvLyBTdGVwIDE6IEdyYWIgdGhlIGZpbGUncyBpbm9kZS5cclxuICAgIHRoaXMuZmluZElOb2RlKHR4LCBwLCAoZTogQXBpRXJyb3IsIGlub2RlPzogSW5vZGUpID0+IHtcclxuICAgICAgaWYgKG5vRXJyb3IoZSwgY2IpKSB7XHJcbiAgICAgICAgLy8gU3RlcCAyOiBHcmFiIHRoZSBmaWxlJ3MgZGF0YS5cclxuICAgICAgICB0eC5nZXQoaW5vZGUuaWQsIChlOiBBcGlFcnJvciwgZGF0YT86IE5vZGVCdWZmZXIpOiB2b2lkID0+IHtcclxuICAgICAgICAgIGlmIChub0Vycm9yKGUsIGNiKSkge1xyXG4gICAgICAgICAgICBpZiAoZGF0YSA9PT0gdW5kZWZpbmVkKSB7XHJcbiAgICAgICAgICAgICAgY2IoQXBpRXJyb3IuRU5PRU5UKHApKTtcclxuICAgICAgICAgICAgfSBlbHNlIHtcclxuICAgICAgICAgICAgICBjYihudWxsLCBuZXcgQXN5bmNLZXlWYWx1ZUZpbGUodGhpcywgcCwgZmxhZywgaW5vZGUudG9TdGF0cygpLCBkYXRhKSk7XHJcbiAgICAgICAgICAgIH1cclxuICAgICAgICAgIH1cclxuICAgICAgICB9KTtcclxuICAgICAgfVxyXG4gICAgfSk7XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBSZW1vdmUgYWxsIHRyYWNlcyBvZiB0aGUgZ2l2ZW4gcGF0aCBmcm9tIHRoZSBmaWxlIHN5c3RlbS5cclxuICAgKiBAcGFyYW0gcCBUaGUgcGF0aCB0byByZW1vdmUgZnJvbSB0aGUgZmlsZSBzeXN0ZW0uXHJcbiAgICogQHBhcmFtIGlzRGlyIERvZXMgdGhlIHBhdGggYmVsb25nIHRvIGEgZGlyZWN0b3J5LCBvciBhIGZpbGU/XHJcbiAgICogQHRvZG8gVXBkYXRlIG10aW1lLlxyXG4gICAqL1xyXG4gIHByaXZhdGUgcmVtb3ZlRW50cnkocDogc3RyaW5nLCBpc0RpcjogYm9vbGVhbiwgY2I6IChlPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHZhciB0eCA9IHRoaXMuc3RvcmUuYmVnaW5UcmFuc2FjdGlvbigncmVhZHdyaXRlJyksXHJcbiAgICAgIHBhcmVudDogc3RyaW5nID0gcGF0aC5kaXJuYW1lKHApLCBmaWxlTmFtZTogc3RyaW5nID0gcGF0aC5iYXNlbmFtZShwKTtcclxuICAgIC8vIFN0ZXAgMTogR2V0IHBhcmVudCBkaXJlY3RvcnkncyBub2RlIGFuZCBkaXJlY3RvcnkgbGlzdGluZy5cclxuICAgIHRoaXMuZmluZElOb2RlQW5kRGlyTGlzdGluZyh0eCwgcGFyZW50LCAoZTogQXBpRXJyb3IsIHBhcmVudE5vZGU/OiBJbm9kZSwgcGFyZW50TGlzdGluZz86IHtbbmFtZTogc3RyaW5nXTogc3RyaW5nfSk6IHZvaWQgPT4ge1xyXG4gICAgICBpZiAobm9FcnJvclR4KGUsIHR4LCBjYikpIHtcclxuICAgICAgICBpZiAoIXBhcmVudExpc3RpbmdbZmlsZU5hbWVdKSB7XHJcbiAgICAgICAgICB0eC5hYm9ydCgoKSA9PiB7XHJcbiAgICAgICAgICAgIGNiKEFwaUVycm9yLkVOT0VOVChwKSk7XHJcbiAgICAgICAgICB9KTtcclxuICAgICAgICB9IGVsc2Uge1xyXG4gICAgICAgICAgLy8gUmVtb3ZlIGZyb20gZGlyZWN0b3J5IGxpc3Rpbmcgb2YgcGFyZW50LlxyXG4gICAgICAgICAgdmFyIGZpbGVOb2RlSWQgPSBwYXJlbnRMaXN0aW5nW2ZpbGVOYW1lXTtcclxuICAgICAgICAgIGRlbGV0ZSBwYXJlbnRMaXN0aW5nW2ZpbGVOYW1lXTtcclxuICAgICAgICAgIC8vIFN0ZXAgMjogR2V0IGZpbGUgaW5vZGUuXHJcbiAgICAgICAgICB0aGlzLmdldElOb2RlKHR4LCBwLCBmaWxlTm9kZUlkLCAoZTogQXBpRXJyb3IsIGZpbGVOb2RlPzogSW5vZGUpOiB2b2lkID0+IHtcclxuICAgICAgICAgICAgaWYgKG5vRXJyb3JUeChlLCB0eCwgY2IpKSB7XHJcbiAgICAgICAgICAgICAgaWYgKCFpc0RpciAmJiBmaWxlTm9kZS5pc0RpcmVjdG9yeSgpKSB7XHJcbiAgICAgICAgICAgICAgICB0eC5hYm9ydCgoKSA9PiB7XHJcbiAgICAgICAgICAgICAgICAgIGNiKEFwaUVycm9yLkVJU0RJUihwKSk7XHJcbiAgICAgICAgICAgICAgICB9KTtcclxuICAgICAgICAgICAgICB9IGVsc2UgaWYgKGlzRGlyICYmICFmaWxlTm9kZS5pc0RpcmVjdG9yeSgpKSB7XHJcbiAgICAgICAgICAgICAgICB0eC5hYm9ydCgoKSA9PiB7XHJcbiAgICAgICAgICAgICAgICAgIGNiKEFwaUVycm9yLkVOT1RESVIocCkpO1xyXG4gICAgICAgICAgICAgICAgfSk7XHJcbiAgICAgICAgICAgICAgfSBlbHNlIHtcclxuICAgICAgICAgICAgICAgIC8vIFN0ZXAgMzogRGVsZXRlIGRhdGEuXHJcbiAgICAgICAgICAgICAgICB0eC5kZWwoZmlsZU5vZGUuaWQsIChlPzogQXBpRXJyb3IpOiB2b2lkID0+IHtcclxuICAgICAgICAgICAgICAgICAgaWYgKG5vRXJyb3JUeChlLCB0eCwgY2IpKSB7XHJcbiAgICAgICAgICAgICAgICAgICAgLy8gU3RlcCA0OiBEZWxldGUgbm9kZS5cclxuICAgICAgICAgICAgICAgICAgICB0eC5kZWwoZmlsZU5vZGVJZCwgKGU/OiBBcGlFcnJvcik6IHZvaWQgPT4ge1xyXG4gICAgICAgICAgICAgICAgICAgICAgaWYgKG5vRXJyb3JUeChlLCB0eCwgY2IpKSB7XHJcbiAgICAgICAgICAgICAgICAgICAgICAgIC8vIFN0ZXAgNTogVXBkYXRlIGRpcmVjdG9yeSBsaXN0aW5nLlxyXG4gICAgICAgICAgICAgICAgICAgICAgICB0eC5wdXQocGFyZW50Tm9kZS5pZCwgbmV3IEJ1ZmZlcihKU09OLnN0cmluZ2lmeShwYXJlbnRMaXN0aW5nKSksIHRydWUsIChlOiBBcGlFcnJvcik6IHZvaWQgPT4ge1xyXG4gICAgICAgICAgICAgICAgICAgICAgICAgIGlmIChub0Vycm9yVHgoZSwgdHgsIGNiKSkge1xyXG4gICAgICAgICAgICAgICAgICAgICAgICAgICAgdHguY29tbWl0KGNiKTtcclxuICAgICAgICAgICAgICAgICAgICAgICAgICB9XHJcbiAgICAgICAgICAgICAgICAgICAgICAgIH0pO1xyXG4gICAgICAgICAgICAgICAgICAgICAgfVxyXG4gICAgICAgICAgICAgICAgICAgIH0pO1xyXG4gICAgICAgICAgICAgICAgICB9XHJcbiAgICAgICAgICAgICAgICB9KTtcclxuICAgICAgICAgICAgICB9XHJcbiAgICAgICAgICAgIH1cclxuICAgICAgICAgIH0pO1xyXG4gICAgICAgIH1cclxuICAgICAgfVxyXG4gICAgfSk7XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgdW5saW5rKHA6IHN0cmluZywgY2I6IChlPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHRoaXMucmVtb3ZlRW50cnkocCwgZmFsc2UsIGNiKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBybWRpcihwOiBzdHJpbmcsIGNiOiAoZT86IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICAvLyBDaGVjayBmaXJzdCBpZiBkaXJlY3RvcnkgaXMgZW1wdHkuXHJcbiAgICB0aGlzLnJlYWRkaXIocCwgKGVyciwgZmlsZXM/KSA9PiB7XHJcbiAgICAgIGlmIChlcnIpIHtcclxuICAgICAgICBjYihlcnIpO1xyXG4gICAgICB9IGVsc2UgaWYgKGZpbGVzLmxlbmd0aCA+IDApIHtcclxuICAgICAgICBjYihBcGlFcnJvci5FTk9URU1QVFkocCkpO1xyXG4gICAgICB9IGVsc2Uge1xyXG4gICAgICAgIHRoaXMucmVtb3ZlRW50cnkocCwgdHJ1ZSwgY2IpO1xyXG4gICAgICB9XHJcbiAgICB9KTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBta2RpcihwOiBzdHJpbmcsIG1vZGU6IG51bWJlciwgY2I6IChlPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHZhciB0eCA9IHRoaXMuc3RvcmUuYmVnaW5UcmFuc2FjdGlvbigncmVhZHdyaXRlJyksXHJcbiAgICAgIGRhdGEgPSBuZXcgQnVmZmVyKCd7fScpO1xyXG4gICAgdGhpcy5jb21taXROZXdGaWxlKHR4LCBwLCBGaWxlVHlwZS5ESVJFQ1RPUlksIG1vZGUsIGRhdGEsIGNiKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyByZWFkZGlyKHA6IHN0cmluZywgY2I6IChlcnI6IEFwaUVycm9yLCBmaWxlcz86IHN0cmluZ1tdKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICB2YXIgdHggPSB0aGlzLnN0b3JlLmJlZ2luVHJhbnNhY3Rpb24oJ3JlYWRvbmx5Jyk7XHJcbiAgICB0aGlzLmZpbmRJTm9kZSh0eCwgcCwgKGU6IEFwaUVycm9yLCBpbm9kZT86IElub2RlKSA9PiB7XHJcbiAgICAgIGlmIChub0Vycm9yKGUsIGNiKSkge1xyXG4gICAgICAgIHRoaXMuZ2V0RGlyTGlzdGluZyh0eCwgcCwgaW5vZGUsIChlOiBBcGlFcnJvciwgZGlyTGlzdGluZz86IHtbbmFtZTogc3RyaW5nXTogc3RyaW5nfSkgPT4ge1xyXG4gICAgICAgICAgaWYgKG5vRXJyb3IoZSwgY2IpKSB7XHJcbiAgICAgICAgICAgIGNiKG51bGwsIE9iamVjdC5rZXlzKGRpckxpc3RpbmcpKTtcclxuICAgICAgICAgIH1cclxuICAgICAgICB9KTtcclxuICAgICAgfVxyXG4gICAgfSk7XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgX3N5bmMocDogc3RyaW5nLCBkYXRhOiBOb2RlQnVmZmVyLCBzdGF0czogU3RhdHMsIGNiOiAoZT86IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICAvLyBAdG9kbyBFbnN1cmUgbXRpbWUgdXBkYXRlcyBwcm9wZXJseSwgYW5kIHVzZSB0aGF0IHRvIGRldGVybWluZSBpZiBhIGRhdGFcclxuICAgIC8vICAgICAgIHVwZGF0ZSBpcyByZXF1aXJlZC5cclxuICAgIHZhciB0eCA9IHRoaXMuc3RvcmUuYmVnaW5UcmFuc2FjdGlvbigncmVhZHdyaXRlJyk7XHJcbiAgICAvLyBTdGVwIDE6IEdldCB0aGUgZmlsZSBub2RlJ3MgSUQuXHJcbiAgICB0aGlzLl9maW5kSU5vZGUodHgsIHBhdGguZGlybmFtZShwKSwgcGF0aC5iYXNlbmFtZShwKSwgKGU6IEFwaUVycm9yLCBmaWxlSW5vZGVJZD86IHN0cmluZyk6IHZvaWQgPT4ge1xyXG4gICAgICBpZiAobm9FcnJvclR4KGUsIHR4LCBjYikpIHtcclxuICAgICAgICAvLyBTdGVwIDI6IEdldCB0aGUgZmlsZSBpbm9kZS5cclxuICAgICAgICB0aGlzLmdldElOb2RlKHR4LCBwLCBmaWxlSW5vZGVJZCwgKGU6IEFwaUVycm9yLCBmaWxlSW5vZGU/OiBJbm9kZSk6IHZvaWQgPT4ge1xyXG4gICAgICAgICAgaWYgKG5vRXJyb3JUeChlLCB0eCwgY2IpKSB7XHJcbiAgICAgICAgICAgIHZhciBpbm9kZUNoYW5nZWQ6IGJvb2xlYW4gPSBmaWxlSW5vZGUudXBkYXRlKHN0YXRzKTtcclxuICAgICAgICAgICAgLy8gU3RlcCAzOiBTeW5jIHRoZSBkYXRhLlxyXG4gICAgICAgICAgICB0eC5wdXQoZmlsZUlub2RlLmlkLCBkYXRhLCB0cnVlLCAoZTogQXBpRXJyb3IpOiB2b2lkID0+IHtcclxuICAgICAgICAgICAgICBpZiAobm9FcnJvclR4KGUsIHR4LCBjYikpIHtcclxuICAgICAgICAgICAgICAgIC8vIFN0ZXAgNDogU3luYyB0aGUgbWV0YWRhdGEgKGlmIGl0IGNoYW5nZWQpIVxyXG4gICAgICAgICAgICAgICAgaWYgKGlub2RlQ2hhbmdlZCkge1xyXG4gICAgICAgICAgICAgICAgICB0eC5wdXQoZmlsZUlub2RlSWQsIGZpbGVJbm9kZS50b0J1ZmZlcigpLCB0cnVlLCAoZTogQXBpRXJyb3IpOiB2b2lkID0+IHtcclxuICAgICAgICAgICAgICAgICAgICBpZiAobm9FcnJvclR4KGUsIHR4LCBjYikpIHtcclxuICAgICAgICAgICAgICAgICAgICAgIHR4LmNvbW1pdChjYik7XHJcbiAgICAgICAgICAgICAgICAgICAgfVxyXG4gICAgICAgICAgICAgICAgICB9KTtcclxuICAgICAgICAgICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICAgICAgICAgIC8vIE5vIG5lZWQgdG8gc3luYyBtZXRhZGF0YTsgcmV0dXJuLlxyXG4gICAgICAgICAgICAgICAgICB0eC5jb21taXQoY2IpO1xyXG4gICAgICAgICAgICAgICAgfVxyXG4gICAgICAgICAgICAgIH1cclxuICAgICAgICAgICAgfSk7XHJcbiAgICAgICAgICB9XHJcbiAgICAgICAgfSk7XHJcbiAgICAgIH1cclxuICAgIH0pO1xyXG4gIH1cclxufVxyXG4iXX0=