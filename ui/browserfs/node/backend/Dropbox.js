"use strict";
var __extends = (this && this.__extends) || function (d, b) {
    for (var p in b) if (b.hasOwnProperty(p)) d[p] = b[p];
    function __() { this.constructor = d; }
    d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
};
var preload_file = require('../generic/preload_file');
var file_system = require('../core/file_system');
var node_fs_stats_1 = require('../core/node_fs_stats');
var api_error_1 = require('../core/api_error');
var async = require('async');
var path = require('path');
var util_1 = require('../core/util');
var errorCodeLookup = null;
function constructErrorCodeLookup() {
    if (errorCodeLookup !== null) {
        return;
    }
    errorCodeLookup = {};
    errorCodeLookup[Dropbox.ApiError.NETWORK_ERROR] = api_error_1.ErrorCode.EIO;
    errorCodeLookup[Dropbox.ApiError.INVALID_PARAM] = api_error_1.ErrorCode.EINVAL;
    errorCodeLookup[Dropbox.ApiError.INVALID_TOKEN] = api_error_1.ErrorCode.EPERM;
    errorCodeLookup[Dropbox.ApiError.OAUTH_ERROR] = api_error_1.ErrorCode.EPERM;
    errorCodeLookup[Dropbox.ApiError.NOT_FOUND] = api_error_1.ErrorCode.ENOENT;
    errorCodeLookup[Dropbox.ApiError.INVALID_METHOD] = api_error_1.ErrorCode.EINVAL;
    errorCodeLookup[Dropbox.ApiError.NOT_ACCEPTABLE] = api_error_1.ErrorCode.EINVAL;
    errorCodeLookup[Dropbox.ApiError.CONFLICT] = api_error_1.ErrorCode.EINVAL;
    errorCodeLookup[Dropbox.ApiError.RATE_LIMITED] = api_error_1.ErrorCode.EBUSY;
    errorCodeLookup[Dropbox.ApiError.SERVER_ERROR] = api_error_1.ErrorCode.EBUSY;
    errorCodeLookup[Dropbox.ApiError.OVER_QUOTA] = api_error_1.ErrorCode.ENOSPC;
}
function isFileInfo(cache) {
    return cache && cache.stat.isFile;
}
function isDirInfo(cache) {
    return cache && cache.stat.isFolder;
}
function isArrayBuffer(ab) {
    return ab === null || ab === undefined || (typeof (ab) === 'object' && typeof (ab['byteLength']) === 'number');
}
var CachedDropboxClient = (function () {
    function CachedDropboxClient(client) {
        this._cache = {};
        this._client = client;
    }
    CachedDropboxClient.prototype.getCachedInfo = function (p) {
        return this._cache[p.toLowerCase()];
    };
    CachedDropboxClient.prototype.putCachedInfo = function (p, cache) {
        this._cache[p.toLowerCase()] = cache;
    };
    CachedDropboxClient.prototype.deleteCachedInfo = function (p) {
        delete this._cache[p.toLowerCase()];
    };
    CachedDropboxClient.prototype.getCachedDirInfo = function (p) {
        var info = this.getCachedInfo(p);
        if (isDirInfo(info)) {
            return info;
        }
        else {
            return null;
        }
    };
    CachedDropboxClient.prototype.getCachedFileInfo = function (p) {
        var info = this.getCachedInfo(p);
        if (isFileInfo(info)) {
            return info;
        }
        else {
            return null;
        }
    };
    CachedDropboxClient.prototype.updateCachedDirInfo = function (p, stat, contents) {
        if (contents === void 0) { contents = null; }
        var cachedInfo = this.getCachedInfo(p);
        if (stat.contentHash !== null && (cachedInfo === undefined || cachedInfo.stat.contentHash !== stat.contentHash)) {
            this.putCachedInfo(p, {
                stat: stat,
                contents: contents
            });
        }
    };
    CachedDropboxClient.prototype.updateCachedFileInfo = function (p, stat, contents) {
        if (contents === void 0) { contents = null; }
        var cachedInfo = this.getCachedInfo(p);
        if (stat.versionTag !== null && (cachedInfo === undefined || cachedInfo.stat.versionTag !== stat.versionTag)) {
            this.putCachedInfo(p, {
                stat: stat,
                contents: contents
            });
        }
    };
    CachedDropboxClient.prototype.updateCachedInfo = function (p, stat, contents) {
        if (contents === void 0) { contents = null; }
        if (stat.isFile && isArrayBuffer(contents)) {
            this.updateCachedFileInfo(p, stat, contents);
        }
        else if (stat.isFolder && Array.isArray(contents)) {
            this.updateCachedDirInfo(p, stat, contents);
        }
    };
    CachedDropboxClient.prototype.readdir = function (p, cb) {
        var _this = this;
        var cacheInfo = this.getCachedDirInfo(p);
        this._wrap(function (interceptCb) {
            if (cacheInfo !== null && cacheInfo.contents) {
                _this._client.readdir(p, {
                    contentHash: cacheInfo.stat.contentHash
                }, interceptCb);
            }
            else {
                _this._client.readdir(p, interceptCb);
            }
        }, function (err, filenames, stat, folderEntries) {
            if (err) {
                if (err.status === Dropbox.ApiError.NO_CONTENT && cacheInfo !== null) {
                    cb(null, cacheInfo.contents.slice(0));
                }
                else {
                    cb(err);
                }
            }
            else {
                _this.updateCachedDirInfo(p, stat, filenames.slice(0));
                folderEntries.forEach(function (entry) {
                    _this.updateCachedInfo(path.join(p, entry.name), entry);
                });
                cb(null, filenames);
            }
        });
    };
    CachedDropboxClient.prototype.remove = function (p, cb) {
        var _this = this;
        this._wrap(function (interceptCb) {
            _this._client.remove(p, interceptCb);
        }, function (err, stat) {
            if (!err) {
                _this.updateCachedInfo(p, stat);
            }
            cb(err);
        });
    };
    CachedDropboxClient.prototype.move = function (src, dest, cb) {
        var _this = this;
        this._wrap(function (interceptCb) {
            _this._client.move(src, dest, interceptCb);
        }, function (err, stat) {
            if (!err) {
                _this.deleteCachedInfo(src);
                _this.updateCachedInfo(dest, stat);
            }
            cb(err);
        });
    };
    CachedDropboxClient.prototype.stat = function (p, cb) {
        var _this = this;
        this._wrap(function (interceptCb) {
            _this._client.stat(p, interceptCb);
        }, function (err, stat) {
            if (!err) {
                _this.updateCachedInfo(p, stat);
            }
            cb(err, stat);
        });
    };
    CachedDropboxClient.prototype.readFile = function (p, cb) {
        var _this = this;
        var cacheInfo = this.getCachedFileInfo(p);
        if (cacheInfo !== null && cacheInfo.contents !== null) {
            this.stat(p, function (error, stat) {
                if (error) {
                    cb(error);
                }
                else if (stat.contentHash === cacheInfo.stat.contentHash) {
                    cb(error, cacheInfo.contents.slice(0), cacheInfo.stat);
                }
                else {
                    _this.readFile(p, cb);
                }
            });
        }
        else {
            this._wrap(function (interceptCb) {
                _this._client.readFile(p, { arrayBuffer: true }, interceptCb);
            }, function (err, contents, stat) {
                if (!err) {
                    _this.updateCachedInfo(p, stat, contents.slice(0));
                }
                cb(err, contents, stat);
            });
        }
    };
    CachedDropboxClient.prototype.writeFile = function (p, contents, cb) {
        var _this = this;
        this._wrap(function (interceptCb) {
            _this._client.writeFile(p, contents, interceptCb);
        }, function (err, stat) {
            if (!err) {
                _this.updateCachedInfo(p, stat, contents.slice(0));
            }
            cb(err, stat);
        });
    };
    CachedDropboxClient.prototype.mkdir = function (p, cb) {
        var _this = this;
        this._wrap(function (interceptCb) {
            _this._client.mkdir(p, interceptCb);
        }, function (err, stat) {
            if (!err) {
                _this.updateCachedInfo(p, stat, []);
            }
            cb(err);
        });
    };
    CachedDropboxClient.prototype._wrap = function (performOp, cb) {
        var numRun = 0, interceptCb = function (error) {
            var timeoutDuration = 2;
            if (error && 3 > (++numRun)) {
                switch (error.status) {
                    case Dropbox.ApiError.SERVER_ERROR:
                    case Dropbox.ApiError.NETWORK_ERROR:
                    case Dropbox.ApiError.RATE_LIMITED:
                        setTimeout(function () {
                            performOp(interceptCb);
                        }, timeoutDuration * 1000);
                        break;
                    default:
                        cb.apply(null, arguments);
                        break;
                }
            }
            else {
                cb.apply(null, arguments);
            }
        };
        performOp(interceptCb);
    };
    return CachedDropboxClient;
}());
var DropboxFile = (function (_super) {
    __extends(DropboxFile, _super);
    function DropboxFile(_fs, _path, _flag, _stat, contents) {
        _super.call(this, _fs, _path, _flag, _stat, contents);
    }
    DropboxFile.prototype.sync = function (cb) {
        var _this = this;
        if (this.isDirty()) {
            var buffer = this.getBuffer(), arrayBuffer = util_1.buffer2ArrayBuffer(buffer);
            this._fs._writeFileStrict(this.getPath(), arrayBuffer, function (e) {
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
    DropboxFile.prototype.close = function (cb) {
        this.sync(cb);
    };
    return DropboxFile;
}(preload_file.PreloadFile));
exports.DropboxFile = DropboxFile;
var DropboxFileSystem = (function (_super) {
    __extends(DropboxFileSystem, _super);
    function DropboxFileSystem(client) {
        _super.call(this);
        this._client = new CachedDropboxClient(client);
        constructErrorCodeLookup();
    }
    DropboxFileSystem.prototype.getName = function () {
        return 'Dropbox';
    };
    DropboxFileSystem.isAvailable = function () {
        return typeof Dropbox !== 'undefined';
    };
    DropboxFileSystem.prototype.isReadOnly = function () {
        return false;
    };
    DropboxFileSystem.prototype.supportsSymlinks = function () {
        return false;
    };
    DropboxFileSystem.prototype.supportsProps = function () {
        return false;
    };
    DropboxFileSystem.prototype.supportsSynch = function () {
        return false;
    };
    DropboxFileSystem.prototype.empty = function (mainCb) {
        var _this = this;
        this._client.readdir('/', function (error, files) {
            if (error) {
                mainCb(_this.convert(error, '/'));
            }
            else {
                var deleteFile = function (file, cb) {
                    var p = path.join('/', file);
                    _this._client.remove(p, function (err) {
                        cb(err ? _this.convert(err, p) : null);
                    });
                };
                var finished = function (err) {
                    if (err) {
                        mainCb(err);
                    }
                    else {
                        mainCb();
                    }
                };
                async.each(files, deleteFile, finished);
            }
        });
    };
    DropboxFileSystem.prototype.rename = function (oldPath, newPath, cb) {
        var _this = this;
        this._client.move(oldPath, newPath, function (error) {
            if (error) {
                _this._client.stat(newPath, function (error2, stat) {
                    if (error2 || stat.isFolder) {
                        var missingPath = error.response.error.indexOf(oldPath) > -1 ? oldPath : newPath;
                        cb(_this.convert(error, missingPath));
                    }
                    else {
                        _this._client.remove(newPath, function (error2) {
                            if (error2) {
                                cb(_this.convert(error2, newPath));
                            }
                            else {
                                _this.rename(oldPath, newPath, cb);
                            }
                        });
                    }
                });
            }
            else {
                cb();
            }
        });
    };
    DropboxFileSystem.prototype.stat = function (path, isLstat, cb) {
        var _this = this;
        this._client.stat(path, function (error, stat) {
            if (error) {
                cb(_this.convert(error, path));
            }
            else if ((stat != null) && stat.isRemoved) {
                cb(api_error_1.ApiError.FileError(api_error_1.ErrorCode.ENOENT, path));
            }
            else {
                var stats = new node_fs_stats_1["default"](_this._statType(stat), stat.size);
                return cb(null, stats);
            }
        });
    };
    DropboxFileSystem.prototype.open = function (path, flags, mode, cb) {
        var _this = this;
        this._client.readFile(path, function (error, content, dbStat) {
            if (error) {
                if (flags.isReadable()) {
                    cb(_this.convert(error, path));
                }
                else {
                    switch (error.status) {
                        case Dropbox.ApiError.NOT_FOUND:
                            var ab = new ArrayBuffer(0);
                            return _this._writeFileStrict(path, ab, function (error2, stat) {
                                if (error2) {
                                    cb(error2);
                                }
                                else {
                                    var file = _this._makeFile(path, flags, stat, util_1.arrayBuffer2Buffer(ab));
                                    cb(null, file);
                                }
                            });
                        default:
                            return cb(_this.convert(error, path));
                    }
                }
            }
            else {
                var buffer;
                if (content === null) {
                    buffer = new Buffer(0);
                }
                else {
                    buffer = util_1.arrayBuffer2Buffer(content);
                }
                var file = _this._makeFile(path, flags, dbStat, buffer);
                return cb(null, file);
            }
        });
    };
    DropboxFileSystem.prototype._writeFileStrict = function (p, data, cb) {
        var _this = this;
        var parent = path.dirname(p);
        this.stat(parent, false, function (error, stat) {
            if (error) {
                cb(api_error_1.ApiError.FileError(api_error_1.ErrorCode.ENOENT, parent));
            }
            else {
                _this._client.writeFile(p, data, function (error2, stat) {
                    if (error2) {
                        cb(_this.convert(error2, p));
                    }
                    else {
                        cb(null, stat);
                    }
                });
            }
        });
    };
    DropboxFileSystem.prototype._statType = function (stat) {
        return stat.isFile ? node_fs_stats_1.FileType.FILE : node_fs_stats_1.FileType.DIRECTORY;
    };
    DropboxFileSystem.prototype._makeFile = function (path, flag, stat, buffer) {
        var type = this._statType(stat);
        var stats = new node_fs_stats_1["default"](type, stat.size);
        return new DropboxFile(this, path, flag, stats, buffer);
    };
    DropboxFileSystem.prototype._remove = function (path, cb, isFile) {
        var _this = this;
        this._client.stat(path, function (error, stat) {
            if (error) {
                cb(_this.convert(error, path));
            }
            else {
                if (stat.isFile && !isFile) {
                    cb(api_error_1.ApiError.FileError(api_error_1.ErrorCode.ENOTDIR, path));
                }
                else if (!stat.isFile && isFile) {
                    cb(api_error_1.ApiError.FileError(api_error_1.ErrorCode.EISDIR, path));
                }
                else {
                    _this._client.remove(path, function (error) {
                        if (error) {
                            cb(_this.convert(error, path));
                        }
                        else {
                            cb(null);
                        }
                    });
                }
            }
        });
    };
    DropboxFileSystem.prototype.unlink = function (path, cb) {
        this._remove(path, cb, true);
    };
    DropboxFileSystem.prototype.rmdir = function (path, cb) {
        this._remove(path, cb, false);
    };
    DropboxFileSystem.prototype.mkdir = function (p, mode, cb) {
        var _this = this;
        var parent = path.dirname(p);
        this._client.stat(parent, function (error, stat) {
            if (error) {
                cb(_this.convert(error, parent));
            }
            else {
                _this._client.mkdir(p, function (error) {
                    if (error) {
                        cb(api_error_1.ApiError.FileError(api_error_1.ErrorCode.EEXIST, p));
                    }
                    else {
                        cb(null);
                    }
                });
            }
        });
    };
    DropboxFileSystem.prototype.readdir = function (path, cb) {
        var _this = this;
        this._client.readdir(path, function (error, files) {
            if (error) {
                return cb(_this.convert(error));
            }
            else {
                return cb(null, files);
            }
        });
    };
    DropboxFileSystem.prototype.convert = function (err, path) {
        if (path === void 0) { path = null; }
        var errorCode = errorCodeLookup[err.status];
        if (errorCode === undefined) {
            errorCode = api_error_1.ErrorCode.EIO;
        }
        if (path == null) {
            return new api_error_1.ApiError(errorCode);
        }
        else {
            return api_error_1.ApiError.FileError(errorCode, path);
        }
    };
    return DropboxFileSystem;
}(file_system.BaseFileSystem));
exports.__esModule = true;
exports["default"] = DropboxFileSystem;
//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJmaWxlIjoiRHJvcGJveC5qcyIsInNvdXJjZVJvb3QiOiIiLCJzb3VyY2VzIjpbIi4uLy4uLy4uL3NyYy9iYWNrZW5kL0Ryb3Bib3gudHMiXSwibmFtZXMiOltdLCJtYXBwaW5ncyI6Ijs7Ozs7O0FBQUEsSUFBTyxZQUFZLFdBQVcseUJBQXlCLENBQUMsQ0FBQztBQUN6RCxJQUFPLFdBQVcsV0FBVyxxQkFBcUIsQ0FBQyxDQUFDO0FBRXBELDhCQUF5Qyx1QkFBdUIsQ0FBQyxDQUFBO0FBQ2pFLDBCQUFrQyxtQkFBbUIsQ0FBQyxDQUFBO0FBRXRELElBQU8sS0FBSyxXQUFXLE9BQU8sQ0FBQyxDQUFDO0FBQ2hDLElBQU8sSUFBSSxXQUFXLE1BQU0sQ0FBQyxDQUFDO0FBQzlCLHFCQUFxRCxjQUFjLENBQUMsQ0FBQTtBQUVwRSxJQUFJLGVBQWUsR0FBNEMsSUFBSSxDQUFDO0FBRXBFO0lBQ0UsRUFBRSxDQUFDLENBQUMsZUFBZSxLQUFLLElBQUksQ0FBQyxDQUFDLENBQUM7UUFDN0IsTUFBTSxDQUFDO0lBQ1QsQ0FBQztJQUNELGVBQWUsR0FBRyxFQUFFLENBQUM7SUFFckIsZUFBZSxDQUFDLE9BQU8sQ0FBQyxRQUFRLENBQUMsYUFBYSxDQUFDLEdBQUcscUJBQVMsQ0FBQyxHQUFHLENBQUM7SUFJaEUsZUFBZSxDQUFDLE9BQU8sQ0FBQyxRQUFRLENBQUMsYUFBYSxDQUFDLEdBQUcscUJBQVMsQ0FBQyxNQUFNLENBQUM7SUFFbkUsZUFBZSxDQUFDLE9BQU8sQ0FBQyxRQUFRLENBQUMsYUFBYSxDQUFDLEdBQUcscUJBQVMsQ0FBQyxLQUFLLENBQUM7SUFHbEUsZUFBZSxDQUFDLE9BQU8sQ0FBQyxRQUFRLENBQUMsV0FBVyxDQUFDLEdBQUcscUJBQVMsQ0FBQyxLQUFLLENBQUM7SUFFaEUsZUFBZSxDQUFDLE9BQU8sQ0FBQyxRQUFRLENBQUMsU0FBUyxDQUFDLEdBQUcscUJBQVMsQ0FBQyxNQUFNLENBQUM7SUFFL0QsZUFBZSxDQUFDLE9BQU8sQ0FBQyxRQUFRLENBQUMsY0FBYyxDQUFDLEdBQUcscUJBQVMsQ0FBQyxNQUFNLENBQUM7SUFFcEUsZUFBZSxDQUFDLE9BQU8sQ0FBQyxRQUFRLENBQUMsY0FBYyxDQUFDLEdBQUcscUJBQVMsQ0FBQyxNQUFNLENBQUM7SUFFcEUsZUFBZSxDQUFDLE9BQU8sQ0FBQyxRQUFRLENBQUMsUUFBUSxDQUFDLEdBQUcscUJBQVMsQ0FBQyxNQUFNLENBQUM7SUFFOUQsZUFBZSxDQUFDLE9BQU8sQ0FBQyxRQUFRLENBQUMsWUFBWSxDQUFDLEdBQUcscUJBQVMsQ0FBQyxLQUFLLENBQUM7SUFFakUsZUFBZSxDQUFDLE9BQU8sQ0FBQyxRQUFRLENBQUMsWUFBWSxDQUFDLEdBQUcscUJBQVMsQ0FBQyxLQUFLLENBQUM7SUFFakUsZUFBZSxDQUFDLE9BQU8sQ0FBQyxRQUFRLENBQUMsVUFBVSxDQUFDLEdBQUcscUJBQVMsQ0FBQyxNQUFNLENBQUM7QUFDbEUsQ0FBQztBQVVELG9CQUFvQixLQUFzQjtJQUN4QyxNQUFNLENBQUMsS0FBSyxJQUFJLEtBQUssQ0FBQyxJQUFJLENBQUMsTUFBTSxDQUFDO0FBQ3BDLENBQUM7QUFNRCxtQkFBbUIsS0FBc0I7SUFDdkMsTUFBTSxDQUFDLEtBQUssSUFBSSxLQUFLLENBQUMsSUFBSSxDQUFDLFFBQVEsQ0FBQztBQUN0QyxDQUFDO0FBRUQsdUJBQXVCLEVBQU87SUFFNUIsTUFBTSxDQUFDLEVBQUUsS0FBSyxJQUFJLElBQUksRUFBRSxLQUFLLFNBQVMsSUFBSSxDQUFDLE9BQU0sQ0FBQyxFQUFFLENBQUMsS0FBSyxRQUFRLElBQUksT0FBTSxDQUFDLEVBQUUsQ0FBQyxZQUFZLENBQUMsQ0FBQyxLQUFLLFFBQVEsQ0FBQyxDQUFDO0FBQy9HLENBQUM7QUFLRDtJQUlFLDZCQUFZLE1BQXNCO1FBSDFCLFdBQU0sR0FBc0MsRUFBRSxDQUFDO1FBSXJELElBQUksQ0FBQyxPQUFPLEdBQUcsTUFBTSxDQUFDO0lBQ3hCLENBQUM7SUFFTywyQ0FBYSxHQUFyQixVQUFzQixDQUFTO1FBQzdCLE1BQU0sQ0FBQyxJQUFJLENBQUMsTUFBTSxDQUFDLENBQUMsQ0FBQyxXQUFXLEVBQUUsQ0FBQyxDQUFDO0lBQ3RDLENBQUM7SUFFTywyQ0FBYSxHQUFyQixVQUFzQixDQUFTLEVBQUUsS0FBc0I7UUFDckQsSUFBSSxDQUFDLE1BQU0sQ0FBQyxDQUFDLENBQUMsV0FBVyxFQUFFLENBQUMsR0FBRyxLQUFLLENBQUM7SUFDdkMsQ0FBQztJQUVPLDhDQUFnQixHQUF4QixVQUF5QixDQUFTO1FBQ2hDLE9BQU8sSUFBSSxDQUFDLE1BQU0sQ0FBQyxDQUFDLENBQUMsV0FBVyxFQUFFLENBQUMsQ0FBQztJQUN0QyxDQUFDO0lBRU8sOENBQWdCLEdBQXhCLFVBQXlCLENBQVM7UUFDaEMsSUFBSSxJQUFJLEdBQUcsSUFBSSxDQUFDLGFBQWEsQ0FBQyxDQUFDLENBQUMsQ0FBQztRQUNqQyxFQUFFLENBQUMsQ0FBQyxTQUFTLENBQUMsSUFBSSxDQUFDLENBQUMsQ0FBQyxDQUFDO1lBQ3BCLE1BQU0sQ0FBQyxJQUFJLENBQUM7UUFDZCxDQUFDO1FBQUMsSUFBSSxDQUFDLENBQUM7WUFDTixNQUFNLENBQUMsSUFBSSxDQUFDO1FBQ2QsQ0FBQztJQUNILENBQUM7SUFFTywrQ0FBaUIsR0FBekIsVUFBMEIsQ0FBUztRQUNqQyxJQUFJLElBQUksR0FBRyxJQUFJLENBQUMsYUFBYSxDQUFDLENBQUMsQ0FBQyxDQUFDO1FBQ2pDLEVBQUUsQ0FBQyxDQUFDLFVBQVUsQ0FBQyxJQUFJLENBQUMsQ0FBQyxDQUFDLENBQUM7WUFDckIsTUFBTSxDQUFDLElBQUksQ0FBQztRQUNkLENBQUM7UUFBQyxJQUFJLENBQUMsQ0FBQztZQUNOLE1BQU0sQ0FBQyxJQUFJLENBQUM7UUFDZCxDQUFDO0lBQ0gsQ0FBQztJQUVPLGlEQUFtQixHQUEzQixVQUE0QixDQUFTLEVBQUUsSUFBdUIsRUFBRSxRQUF5QjtRQUF6Qix3QkFBeUIsR0FBekIsZUFBeUI7UUFDdkYsSUFBSSxVQUFVLEdBQUcsSUFBSSxDQUFDLGFBQWEsQ0FBQyxDQUFDLENBQUMsQ0FBQztRQUl2QyxFQUFFLENBQUMsQ0FBQyxJQUFJLENBQUMsV0FBVyxLQUFLLElBQUksSUFBSSxDQUFDLFVBQVUsS0FBSyxTQUFTLElBQUksVUFBVSxDQUFDLElBQUksQ0FBQyxXQUFXLEtBQUssSUFBSSxDQUFDLFdBQVcsQ0FBQyxDQUFDLENBQUMsQ0FBQztZQUNoSCxJQUFJLENBQUMsYUFBYSxDQUFDLENBQUMsRUFBbUI7Z0JBQ3JDLElBQUksRUFBRSxJQUFJO2dCQUNWLFFBQVEsRUFBRSxRQUFRO2FBQ25CLENBQUMsQ0FBQztRQUNMLENBQUM7SUFDSCxDQUFDO0lBRU8sa0RBQW9CLEdBQTVCLFVBQTZCLENBQVMsRUFBRSxJQUF1QixFQUFFLFFBQTRCO1FBQTVCLHdCQUE0QixHQUE1QixlQUE0QjtRQUMzRixJQUFJLFVBQVUsR0FBRyxJQUFJLENBQUMsYUFBYSxDQUFDLENBQUMsQ0FBQyxDQUFDO1FBR3ZDLEVBQUUsQ0FBQyxDQUFDLElBQUksQ0FBQyxVQUFVLEtBQUssSUFBSSxJQUFJLENBQUMsVUFBVSxLQUFLLFNBQVMsSUFBSSxVQUFVLENBQUMsSUFBSSxDQUFDLFVBQVUsS0FBSyxJQUFJLENBQUMsVUFBVSxDQUFDLENBQUMsQ0FBQyxDQUFDO1lBQzdHLElBQUksQ0FBQyxhQUFhLENBQUMsQ0FBQyxFQUFvQjtnQkFDdEMsSUFBSSxFQUFFLElBQUk7Z0JBQ1YsUUFBUSxFQUFFLFFBQVE7YUFDbkIsQ0FBQyxDQUFDO1FBQ0wsQ0FBQztJQUNILENBQUM7SUFFTyw4Q0FBZ0IsR0FBeEIsVUFBeUIsQ0FBUyxFQUFFLElBQXVCLEVBQUUsUUFBdUM7UUFBdkMsd0JBQXVDLEdBQXZDLGVBQXVDO1FBQ2xHLEVBQUUsQ0FBQyxDQUFDLElBQUksQ0FBQyxNQUFNLElBQUksYUFBYSxDQUFDLFFBQVEsQ0FBQyxDQUFDLENBQUMsQ0FBQztZQUMzQyxJQUFJLENBQUMsb0JBQW9CLENBQUMsQ0FBQyxFQUFFLElBQUksRUFBRSxRQUFRLENBQUMsQ0FBQztRQUMvQyxDQUFDO1FBQUMsSUFBSSxDQUFDLEVBQUUsQ0FBQyxDQUFDLElBQUksQ0FBQyxRQUFRLElBQUksS0FBSyxDQUFDLE9BQU8sQ0FBQyxRQUFRLENBQUMsQ0FBQyxDQUFDLENBQUM7WUFDcEQsSUFBSSxDQUFDLG1CQUFtQixDQUFDLENBQUMsRUFBRSxJQUFJLEVBQUUsUUFBUSxDQUFDLENBQUM7UUFDOUMsQ0FBQztJQUNILENBQUM7SUFFTSxxQ0FBTyxHQUFkLFVBQWUsQ0FBUyxFQUFFLEVBQTBEO1FBQXBGLGlCQTBCQztRQXpCQyxJQUFJLFNBQVMsR0FBRyxJQUFJLENBQUMsZ0JBQWdCLENBQUMsQ0FBQyxDQUFDLENBQUM7UUFFekMsSUFBSSxDQUFDLEtBQUssQ0FBQyxVQUFDLFdBQVc7WUFDckIsRUFBRSxDQUFDLENBQUMsU0FBUyxLQUFLLElBQUksSUFBSSxTQUFTLENBQUMsUUFBUSxDQUFDLENBQUMsQ0FBQztnQkFDN0MsS0FBSSxDQUFDLE9BQU8sQ0FBQyxPQUFPLENBQUMsQ0FBQyxFQUFFO29CQUN0QixXQUFXLEVBQUUsU0FBUyxDQUFDLElBQUksQ0FBQyxXQUFXO2lCQUN4QyxFQUFFLFdBQVcsQ0FBQyxDQUFDO1lBQ2xCLENBQUM7WUFBQyxJQUFJLENBQUMsQ0FBQztnQkFDTixLQUFJLENBQUMsT0FBTyxDQUFDLE9BQU8sQ0FBQyxDQUFDLEVBQUUsV0FBVyxDQUFDLENBQUM7WUFDdkMsQ0FBQztRQUNILENBQUMsRUFBRSxVQUFDLEdBQXFCLEVBQUUsU0FBbUIsRUFBRSxJQUF1QixFQUFFLGFBQWtDO1lBQ3pHLEVBQUUsQ0FBQyxDQUFDLEdBQUcsQ0FBQyxDQUFDLENBQUM7Z0JBQ1IsRUFBRSxDQUFDLENBQUMsR0FBRyxDQUFDLE1BQU0sS0FBSyxPQUFPLENBQUMsUUFBUSxDQUFDLFVBQVUsSUFBSSxTQUFTLEtBQUssSUFBSSxDQUFDLENBQUMsQ0FBQztvQkFDckUsRUFBRSxDQUFDLElBQUksRUFBRSxTQUFTLENBQUMsUUFBUSxDQUFDLEtBQUssQ0FBQyxDQUFDLENBQUMsQ0FBQyxDQUFDO2dCQUN4QyxDQUFDO2dCQUFDLElBQUksQ0FBQyxDQUFDO29CQUNOLEVBQUUsQ0FBQyxHQUFHLENBQUMsQ0FBQztnQkFDVixDQUFDO1lBQ0gsQ0FBQztZQUFDLElBQUksQ0FBQyxDQUFDO2dCQUNOLEtBQUksQ0FBQyxtQkFBbUIsQ0FBQyxDQUFDLEVBQUUsSUFBSSxFQUFFLFNBQVMsQ0FBQyxLQUFLLENBQUMsQ0FBQyxDQUFDLENBQUMsQ0FBQztnQkFDdEQsYUFBYSxDQUFDLE9BQU8sQ0FBQyxVQUFDLEtBQUs7b0JBQzFCLEtBQUksQ0FBQyxnQkFBZ0IsQ0FBQyxJQUFJLENBQUMsSUFBSSxDQUFDLENBQUMsRUFBRSxLQUFLLENBQUMsSUFBSSxDQUFDLEVBQUUsS0FBSyxDQUFDLENBQUM7Z0JBQ3pELENBQUMsQ0FBQyxDQUFDO2dCQUNILEVBQUUsQ0FBQyxJQUFJLEVBQUUsU0FBUyxDQUFDLENBQUM7WUFDdEIsQ0FBQztRQUNILENBQUMsQ0FBQyxDQUFDO0lBQ0wsQ0FBQztJQUVNLG9DQUFNLEdBQWIsVUFBYyxDQUFTLEVBQUUsRUFBc0M7UUFBL0QsaUJBU0M7UUFSQyxJQUFJLENBQUMsS0FBSyxDQUFDLFVBQUMsV0FBVztZQUNyQixLQUFJLENBQUMsT0FBTyxDQUFDLE1BQU0sQ0FBQyxDQUFDLEVBQUUsV0FBVyxDQUFDLENBQUM7UUFDdEMsQ0FBQyxFQUFFLFVBQUMsR0FBcUIsRUFBRSxJQUF3QjtZQUNqRCxFQUFFLENBQUMsQ0FBQyxDQUFDLEdBQUcsQ0FBQyxDQUFDLENBQUM7Z0JBQ1QsS0FBSSxDQUFDLGdCQUFnQixDQUFDLENBQUMsRUFBRSxJQUFJLENBQUMsQ0FBQztZQUNqQyxDQUFDO1lBQ0QsRUFBRSxDQUFDLEdBQUcsQ0FBQyxDQUFDO1FBQ1YsQ0FBQyxDQUFDLENBQUM7SUFDTCxDQUFDO0lBRU0sa0NBQUksR0FBWCxVQUFZLEdBQVcsRUFBRSxJQUFZLEVBQUUsRUFBc0M7UUFBN0UsaUJBVUM7UUFUQyxJQUFJLENBQUMsS0FBSyxDQUFDLFVBQUMsV0FBVztZQUNyQixLQUFJLENBQUMsT0FBTyxDQUFDLElBQUksQ0FBQyxHQUFHLEVBQUUsSUFBSSxFQUFFLFdBQVcsQ0FBQyxDQUFDO1FBQzVDLENBQUMsRUFBRSxVQUFDLEdBQXFCLEVBQUUsSUFBdUI7WUFDaEQsRUFBRSxDQUFDLENBQUMsQ0FBQyxHQUFHLENBQUMsQ0FBQyxDQUFDO2dCQUNULEtBQUksQ0FBQyxnQkFBZ0IsQ0FBQyxHQUFHLENBQUMsQ0FBQztnQkFDM0IsS0FBSSxDQUFDLGdCQUFnQixDQUFDLElBQUksRUFBRSxJQUFJLENBQUMsQ0FBQztZQUNwQyxDQUFDO1lBQ0QsRUFBRSxDQUFDLEdBQUcsQ0FBQyxDQUFDO1FBQ1YsQ0FBQyxDQUFDLENBQUM7SUFDTCxDQUFDO0lBRU0sa0NBQUksR0FBWCxVQUFZLENBQVMsRUFBRSxFQUErRDtRQUF0RixpQkFTQztRQVJDLElBQUksQ0FBQyxLQUFLLENBQUMsVUFBQyxXQUFXO1lBQ3JCLEtBQUksQ0FBQyxPQUFPLENBQUMsSUFBSSxDQUFDLENBQUMsRUFBRSxXQUFXLENBQUMsQ0FBQztRQUNwQyxDQUFDLEVBQUUsVUFBQyxHQUFxQixFQUFFLElBQXVCO1lBQ2hELEVBQUUsQ0FBQyxDQUFDLENBQUMsR0FBRyxDQUFDLENBQUMsQ0FBQztnQkFDVCxLQUFJLENBQUMsZ0JBQWdCLENBQUMsQ0FBQyxFQUFFLElBQUksQ0FBQyxDQUFDO1lBQ2pDLENBQUM7WUFDRCxFQUFFLENBQUMsR0FBRyxFQUFFLElBQUksQ0FBQyxDQUFDO1FBQ2hCLENBQUMsQ0FBQyxDQUFDO0lBQ0wsQ0FBQztJQUVNLHNDQUFRLEdBQWYsVUFBZ0IsQ0FBUyxFQUFFLEVBQW1GO1FBQTlHLGlCQXlCQztRQXhCQyxJQUFJLFNBQVMsR0FBRyxJQUFJLENBQUMsaUJBQWlCLENBQUMsQ0FBQyxDQUFDLENBQUM7UUFDMUMsRUFBRSxDQUFDLENBQUMsU0FBUyxLQUFLLElBQUksSUFBSSxTQUFTLENBQUMsUUFBUSxLQUFLLElBQUksQ0FBQyxDQUFDLENBQUM7WUFFdEQsSUFBSSxDQUFDLElBQUksQ0FBQyxDQUFDLEVBQUUsVUFBQyxLQUFLLEVBQUUsSUFBSztnQkFDeEIsRUFBRSxDQUFDLENBQUMsS0FBSyxDQUFDLENBQUMsQ0FBQztvQkFDVixFQUFFLENBQUMsS0FBSyxDQUFDLENBQUM7Z0JBQ1osQ0FBQztnQkFBQyxJQUFJLENBQUMsRUFBRSxDQUFDLENBQUMsSUFBSSxDQUFDLFdBQVcsS0FBSyxTQUFTLENBQUMsSUFBSSxDQUFDLFdBQVcsQ0FBQyxDQUFDLENBQUM7b0JBRTNELEVBQUUsQ0FBQyxLQUFLLEVBQUUsU0FBUyxDQUFDLFFBQVEsQ0FBQyxLQUFLLENBQUMsQ0FBQyxDQUFDLEVBQUUsU0FBUyxDQUFDLElBQUksQ0FBQyxDQUFDO2dCQUN6RCxDQUFDO2dCQUFDLElBQUksQ0FBQyxDQUFDO29CQUVOLEtBQUksQ0FBQyxRQUFRLENBQUMsQ0FBQyxFQUFFLEVBQUUsQ0FBQyxDQUFDO2dCQUN2QixDQUFDO1lBQ0gsQ0FBQyxDQUFDLENBQUM7UUFDTCxDQUFDO1FBQUMsSUFBSSxDQUFDLENBQUM7WUFDTixJQUFJLENBQUMsS0FBSyxDQUFDLFVBQUMsV0FBVztnQkFDckIsS0FBSSxDQUFDLE9BQU8sQ0FBQyxRQUFRLENBQUMsQ0FBQyxFQUFFLEVBQUUsV0FBVyxFQUFFLElBQUksRUFBRSxFQUFFLFdBQVcsQ0FBQyxDQUFDO1lBQy9ELENBQUMsRUFBRSxVQUFDLEdBQXFCLEVBQUUsUUFBYSxFQUFFLElBQXVCO2dCQUMvRCxFQUFFLENBQUMsQ0FBQyxDQUFDLEdBQUcsQ0FBQyxDQUFDLENBQUM7b0JBQ1QsS0FBSSxDQUFDLGdCQUFnQixDQUFDLENBQUMsRUFBRSxJQUFJLEVBQUUsUUFBUSxDQUFDLEtBQUssQ0FBQyxDQUFDLENBQUMsQ0FBQyxDQUFDO2dCQUNwRCxDQUFDO2dCQUNELEVBQUUsQ0FBQyxHQUFHLEVBQUUsUUFBUSxFQUFFLElBQUksQ0FBQyxDQUFDO1lBQzFCLENBQUMsQ0FBQyxDQUFDO1FBQ0wsQ0FBQztJQUNILENBQUM7SUFFTSx1Q0FBUyxHQUFoQixVQUFpQixDQUFTLEVBQUUsUUFBcUIsRUFBRSxFQUErRDtRQUFsSCxpQkFTQztRQVJDLElBQUksQ0FBQyxLQUFLLENBQUMsVUFBQyxXQUFXO1lBQ3JCLEtBQUksQ0FBQyxPQUFPLENBQUMsU0FBUyxDQUFDLENBQUMsRUFBRSxRQUFRLEVBQUUsV0FBVyxDQUFDLENBQUM7UUFDbkQsQ0FBQyxFQUFDLFVBQUMsR0FBcUIsRUFBRSxJQUF1QjtZQUMvQyxFQUFFLENBQUMsQ0FBQyxDQUFDLEdBQUcsQ0FBQyxDQUFDLENBQUM7Z0JBQ1QsS0FBSSxDQUFDLGdCQUFnQixDQUFDLENBQUMsRUFBRSxJQUFJLEVBQUUsUUFBUSxDQUFDLEtBQUssQ0FBQyxDQUFDLENBQUMsQ0FBQyxDQUFDO1lBQ3BELENBQUM7WUFDRCxFQUFFLENBQUMsR0FBRyxFQUFFLElBQUksQ0FBQyxDQUFDO1FBQ2hCLENBQUMsQ0FBQyxDQUFDO0lBQ0wsQ0FBQztJQUVNLG1DQUFLLEdBQVosVUFBYSxDQUFTLEVBQUUsRUFBc0M7UUFBOUQsaUJBU0M7UUFSQyxJQUFJLENBQUMsS0FBSyxDQUFDLFVBQUMsV0FBVztZQUNyQixLQUFJLENBQUMsT0FBTyxDQUFDLEtBQUssQ0FBQyxDQUFDLEVBQUUsV0FBVyxDQUFDLENBQUM7UUFDckMsQ0FBQyxFQUFFLFVBQUMsR0FBcUIsRUFBRSxJQUF1QjtZQUNoRCxFQUFFLENBQUMsQ0FBQyxDQUFDLEdBQUcsQ0FBQyxDQUFDLENBQUM7Z0JBQ1QsS0FBSSxDQUFDLGdCQUFnQixDQUFDLENBQUMsRUFBRSxJQUFJLEVBQUUsRUFBRSxDQUFDLENBQUM7WUFDckMsQ0FBQztZQUNELEVBQUUsQ0FBQyxHQUFHLENBQUMsQ0FBQztRQUNWLENBQUMsQ0FBQyxDQUFDO0lBQ0wsQ0FBQztJQVNPLG1DQUFLLEdBQWIsVUFBYyxTQUFtRSxFQUFFLEVBQVk7UUFDN0YsSUFBSSxNQUFNLEdBQUcsQ0FBQyxFQUNaLFdBQVcsR0FBRyxVQUFVLEtBQXVCO1lBRTdDLElBQUksZUFBZSxHQUFXLENBQUMsQ0FBQztZQUNoQyxFQUFFLENBQUMsQ0FBQyxLQUFLLElBQUksQ0FBQyxHQUFHLENBQUMsRUFBRSxNQUFNLENBQUMsQ0FBQyxDQUFDLENBQUM7Z0JBQzVCLE1BQU0sQ0FBQSxDQUFDLEtBQUssQ0FBQyxNQUFNLENBQUMsQ0FBQyxDQUFDO29CQUNwQixLQUFLLE9BQU8sQ0FBQyxRQUFRLENBQUMsWUFBWSxDQUFDO29CQUNuQyxLQUFLLE9BQU8sQ0FBQyxRQUFRLENBQUMsYUFBYSxDQUFDO29CQUNwQyxLQUFLLE9BQU8sQ0FBQyxRQUFRLENBQUMsWUFBWTt3QkFDaEMsVUFBVSxDQUFDOzRCQUNULFNBQVMsQ0FBQyxXQUFXLENBQUMsQ0FBQzt3QkFDekIsQ0FBQyxFQUFFLGVBQWUsR0FBRyxJQUFJLENBQUMsQ0FBQzt3QkFDM0IsS0FBSyxDQUFDO29CQUNSO3dCQUNFLEVBQUUsQ0FBQyxLQUFLLENBQUMsSUFBSSxFQUFFLFNBQVMsQ0FBQyxDQUFDO3dCQUMxQixLQUFLLENBQUM7Z0JBQ1YsQ0FBQztZQUNILENBQUM7WUFBQyxJQUFJLENBQUMsQ0FBQztnQkFDTixFQUFFLENBQUMsS0FBSyxDQUFDLElBQUksRUFBRSxTQUFTLENBQUMsQ0FBQztZQUM1QixDQUFDO1FBQ0gsQ0FBQyxDQUFDO1FBRUosU0FBUyxDQUFDLFdBQVcsQ0FBQyxDQUFDO0lBQ3pCLENBQUM7SUFDSCwwQkFBQztBQUFELENBQUMsQUF0TkQsSUFzTkM7QUFFRDtJQUFpQywrQkFBMkM7SUFDMUUscUJBQVksR0FBc0IsRUFBRSxLQUFhLEVBQUUsS0FBeUIsRUFBRSxLQUFZLEVBQUUsUUFBcUI7UUFDL0csa0JBQU0sR0FBRyxFQUFFLEtBQUssRUFBRSxLQUFLLEVBQUUsS0FBSyxFQUFFLFFBQVEsQ0FBQyxDQUFBO0lBQzNDLENBQUM7SUFFTSwwQkFBSSxHQUFYLFVBQVksRUFBMEI7UUFBdEMsaUJBYUM7UUFaQyxFQUFFLENBQUMsQ0FBQyxJQUFJLENBQUMsT0FBTyxFQUFFLENBQUMsQ0FBQyxDQUFDO1lBQ25CLElBQUksTUFBTSxHQUFHLElBQUksQ0FBQyxTQUFTLEVBQUUsRUFDM0IsV0FBVyxHQUFHLHlCQUFrQixDQUFDLE1BQU0sQ0FBQyxDQUFDO1lBQzNDLElBQUksQ0FBQyxHQUFHLENBQUMsZ0JBQWdCLENBQUMsSUFBSSxDQUFDLE9BQU8sRUFBRSxFQUFFLFdBQVcsRUFBRSxVQUFDLENBQVk7Z0JBQ2xFLEVBQUUsQ0FBQyxDQUFDLENBQUMsQ0FBQyxDQUFDLENBQUMsQ0FBQztvQkFDUCxLQUFJLENBQUMsVUFBVSxFQUFFLENBQUM7Z0JBQ3BCLENBQUM7Z0JBQ0QsRUFBRSxDQUFDLENBQUMsQ0FBQyxDQUFDO1lBQ1IsQ0FBQyxDQUFDLENBQUM7UUFDTCxDQUFDO1FBQUMsSUFBSSxDQUFDLENBQUM7WUFDTixFQUFFLEVBQUUsQ0FBQztRQUNQLENBQUM7SUFDSCxDQUFDO0lBRU0sMkJBQUssR0FBWixVQUFhLEVBQTBCO1FBQ3JDLElBQUksQ0FBQyxJQUFJLENBQUMsRUFBRSxDQUFDLENBQUM7SUFDaEIsQ0FBQztJQUNILGtCQUFDO0FBQUQsQ0FBQyxBQXZCRCxDQUFpQyxZQUFZLENBQUMsV0FBVyxHQXVCeEQ7QUF2QlksbUJBQVcsY0F1QnZCLENBQUE7QUFFRDtJQUErQyxxQ0FBMEI7SUFPdkUsMkJBQVksTUFBc0I7UUFDaEMsaUJBQU8sQ0FBQztRQUNSLElBQUksQ0FBQyxPQUFPLEdBQUcsSUFBSSxtQkFBbUIsQ0FBQyxNQUFNLENBQUMsQ0FBQztRQUMvQyx3QkFBd0IsRUFBRSxDQUFDO0lBQzdCLENBQUM7SUFFTSxtQ0FBTyxHQUFkO1FBQ0UsTUFBTSxDQUFDLFNBQVMsQ0FBQztJQUNuQixDQUFDO0lBRWEsNkJBQVcsR0FBekI7UUFFRSxNQUFNLENBQUMsT0FBTyxPQUFPLEtBQUssV0FBVyxDQUFDO0lBQ3hDLENBQUM7SUFFTSxzQ0FBVSxHQUFqQjtRQUNFLE1BQU0sQ0FBQyxLQUFLLENBQUM7SUFDZixDQUFDO0lBSU0sNENBQWdCLEdBQXZCO1FBQ0UsTUFBTSxDQUFDLEtBQUssQ0FBQztJQUNmLENBQUM7SUFFTSx5Q0FBYSxHQUFwQjtRQUNFLE1BQU0sQ0FBQyxLQUFLLENBQUM7SUFDZixDQUFDO0lBRU0seUNBQWEsR0FBcEI7UUFDRSxNQUFNLENBQUMsS0FBSyxDQUFDO0lBQ2YsQ0FBQztJQUVNLGlDQUFLLEdBQVosVUFBYSxNQUE4QjtRQUEzQyxpQkFzQkM7UUFyQkMsSUFBSSxDQUFDLE9BQU8sQ0FBQyxPQUFPLENBQUMsR0FBRyxFQUFFLFVBQUMsS0FBSyxFQUFFLEtBQUs7WUFDckMsRUFBRSxDQUFDLENBQUMsS0FBSyxDQUFDLENBQUMsQ0FBQztnQkFDVixNQUFNLENBQUMsS0FBSSxDQUFDLE9BQU8sQ0FBQyxLQUFLLEVBQUUsR0FBRyxDQUFDLENBQUMsQ0FBQztZQUNuQyxDQUFDO1lBQUMsSUFBSSxDQUFDLENBQUM7Z0JBQ04sSUFBSSxVQUFVLEdBQUcsVUFBQyxJQUFZLEVBQUUsRUFBNEI7b0JBQzFELElBQUksQ0FBQyxHQUFHLElBQUksQ0FBQyxJQUFJLENBQUMsR0FBRyxFQUFFLElBQUksQ0FBQyxDQUFDO29CQUM3QixLQUFJLENBQUMsT0FBTyxDQUFDLE1BQU0sQ0FBQyxDQUFDLEVBQUUsVUFBQyxHQUFHO3dCQUN6QixFQUFFLENBQUMsR0FBRyxHQUFHLEtBQUksQ0FBQyxPQUFPLENBQUMsR0FBRyxFQUFFLENBQUMsQ0FBQyxHQUFHLElBQUksQ0FBQyxDQUFDO29CQUN4QyxDQUFDLENBQUMsQ0FBQztnQkFDTCxDQUFDLENBQUM7Z0JBQ0YsSUFBSSxRQUFRLEdBQUcsVUFBQyxHQUFjO29CQUM1QixFQUFFLENBQUMsQ0FBQyxHQUFHLENBQUMsQ0FBQyxDQUFDO3dCQUNSLE1BQU0sQ0FBQyxHQUFHLENBQUMsQ0FBQztvQkFDZCxDQUFDO29CQUFDLElBQUksQ0FBQyxDQUFDO3dCQUNOLE1BQU0sRUFBRSxDQUFDO29CQUNYLENBQUM7Z0JBQ0gsQ0FBQyxDQUFDO2dCQUVGLEtBQUssQ0FBQyxJQUFJLENBQUMsS0FBSyxFQUFRLFVBQVUsRUFBUSxRQUFRLENBQUMsQ0FBQztZQUN0RCxDQUFDO1FBQ0gsQ0FBQyxDQUFDLENBQUM7SUFDTCxDQUFDO0lBRUssa0NBQU0sR0FBYixVQUFjLE9BQWUsRUFBRSxPQUFlLEVBQUUsRUFBMEI7UUFBMUUsaUJBd0JFO1FBdkJDLElBQUksQ0FBQyxPQUFPLENBQUMsSUFBSSxDQUFDLE9BQU8sRUFBRSxPQUFPLEVBQUUsVUFBQyxLQUFLO1lBQ3hDLEVBQUUsQ0FBQyxDQUFDLEtBQUssQ0FBQyxDQUFDLENBQUM7Z0JBR1YsS0FBSSxDQUFDLE9BQU8sQ0FBQyxJQUFJLENBQUMsT0FBTyxFQUFFLFVBQUMsTUFBTSxFQUFFLElBQUk7b0JBQ3RDLEVBQUUsQ0FBQyxDQUFDLE1BQU0sSUFBSSxJQUFJLENBQUMsUUFBUSxDQUFDLENBQUMsQ0FBQzt3QkFDNUIsSUFBSSxXQUFXLEdBQVUsS0FBSyxDQUFDLFFBQVMsQ0FBQyxLQUFLLENBQUMsT0FBTyxDQUFDLE9BQU8sQ0FBQyxHQUFHLENBQUMsQ0FBQyxHQUFHLE9BQU8sR0FBRyxPQUFPLENBQUM7d0JBQ3pGLEVBQUUsQ0FBQyxLQUFJLENBQUMsT0FBTyxDQUFDLEtBQUssRUFBRSxXQUFXLENBQUMsQ0FBQyxDQUFDO29CQUN2QyxDQUFDO29CQUFDLElBQUksQ0FBQyxDQUFDO3dCQUVOLEtBQUksQ0FBQyxPQUFPLENBQUMsTUFBTSxDQUFDLE9BQU8sRUFBRSxVQUFDLE1BQU07NEJBQ2xDLEVBQUUsQ0FBQyxDQUFDLE1BQU0sQ0FBQyxDQUFDLENBQUM7Z0NBQ1gsRUFBRSxDQUFDLEtBQUksQ0FBQyxPQUFPLENBQUMsTUFBTSxFQUFFLE9BQU8sQ0FBQyxDQUFDLENBQUM7NEJBQ3BDLENBQUM7NEJBQUMsSUFBSSxDQUFDLENBQUM7Z0NBQ04sS0FBSSxDQUFDLE1BQU0sQ0FBQyxPQUFPLEVBQUUsT0FBTyxFQUFFLEVBQUUsQ0FBQyxDQUFDOzRCQUNwQyxDQUFDO3dCQUNILENBQUMsQ0FBQyxDQUFDO29CQUNMLENBQUM7Z0JBQ0gsQ0FBQyxDQUFDLENBQUM7WUFDTCxDQUFDO1lBQUMsSUFBSSxDQUFDLENBQUM7Z0JBQ04sRUFBRSxFQUFFLENBQUM7WUFDUCxDQUFDO1FBQ0gsQ0FBQyxDQUFDLENBQUM7SUFDTCxDQUFDO0lBRU0sZ0NBQUksR0FBWCxVQUFZLElBQVksRUFBRSxPQUFnQixFQUFFLEVBQXlDO1FBQXJGLGlCQWVDO1FBWkMsSUFBSSxDQUFDLE9BQU8sQ0FBQyxJQUFJLENBQUMsSUFBSSxFQUFFLFVBQUMsS0FBSyxFQUFFLElBQUk7WUFDbEMsRUFBRSxDQUFDLENBQUMsS0FBSyxDQUFDLENBQUMsQ0FBQztnQkFDVixFQUFFLENBQUMsS0FBSSxDQUFDLE9BQU8sQ0FBQyxLQUFLLEVBQUUsSUFBSSxDQUFDLENBQUMsQ0FBQztZQUNoQyxDQUFDO1lBQUMsSUFBSSxDQUFDLEVBQUUsQ0FBQyxDQUFDLENBQUMsSUFBSSxJQUFJLElBQUksQ0FBQyxJQUFJLElBQUksQ0FBQyxTQUFTLENBQUMsQ0FBQyxDQUFDO2dCQUc1QyxFQUFFLENBQUMsb0JBQVEsQ0FBQyxTQUFTLENBQUMscUJBQVMsQ0FBQyxNQUFNLEVBQUUsSUFBSSxDQUFDLENBQUMsQ0FBQztZQUNqRCxDQUFDO1lBQUMsSUFBSSxDQUFDLENBQUM7Z0JBQ04sSUFBSSxLQUFLLEdBQUcsSUFBSSwwQkFBSyxDQUFDLEtBQUksQ0FBQyxTQUFTLENBQUMsSUFBSSxDQUFDLEVBQUUsSUFBSSxDQUFDLElBQUksQ0FBQyxDQUFDO2dCQUN2RCxNQUFNLENBQUMsRUFBRSxDQUFDLElBQUksRUFBRSxLQUFLLENBQUMsQ0FBQztZQUN6QixDQUFDO1FBQ0gsQ0FBQyxDQUFDLENBQUM7SUFDTCxDQUFDO0lBRU0sZ0NBQUksR0FBWCxVQUFZLElBQVksRUFBRSxLQUF5QixFQUFFLElBQVksRUFBRSxFQUEwQztRQUE3RyxpQkF3Q0M7UUF0Q0MsSUFBSSxDQUFDLE9BQU8sQ0FBQyxRQUFRLENBQUMsSUFBSSxFQUFFLFVBQUMsS0FBSyxFQUFFLE9BQU8sRUFBRSxNQUFNO1lBQ2pELEVBQUUsQ0FBQyxDQUFDLEtBQUssQ0FBQyxDQUFDLENBQUM7Z0JBR1YsRUFBRSxDQUFDLENBQUMsS0FBSyxDQUFDLFVBQVUsRUFBRSxDQUFDLENBQUMsQ0FBQztvQkFDdkIsRUFBRSxDQUFDLEtBQUksQ0FBQyxPQUFPLENBQUMsS0FBSyxFQUFFLElBQUksQ0FBQyxDQUFDLENBQUM7Z0JBQ2hDLENBQUM7Z0JBQUMsSUFBSSxDQUFDLENBQUM7b0JBQ04sTUFBTSxDQUFDLENBQUMsS0FBSyxDQUFDLE1BQU0sQ0FBQyxDQUFDLENBQUM7d0JBR3JCLEtBQUssT0FBTyxDQUFDLFFBQVEsQ0FBQyxTQUFTOzRCQUM3QixJQUFJLEVBQUUsR0FBRyxJQUFJLFdBQVcsQ0FBQyxDQUFDLENBQUMsQ0FBQzs0QkFDNUIsTUFBTSxDQUFDLEtBQUksQ0FBQyxnQkFBZ0IsQ0FBQyxJQUFJLEVBQUUsRUFBRSxFQUFFLFVBQUMsTUFBZ0IsRUFBRSxJQUF3QjtnQ0FDaEYsRUFBRSxDQUFDLENBQUMsTUFBTSxDQUFDLENBQUMsQ0FBQztvQ0FDWCxFQUFFLENBQUMsTUFBTSxDQUFDLENBQUM7Z0NBQ2IsQ0FBQztnQ0FBQyxJQUFJLENBQUMsQ0FBQztvQ0FDTixJQUFJLElBQUksR0FBRyxLQUFJLENBQUMsU0FBUyxDQUFDLElBQUksRUFBRSxLQUFLLEVBQUUsSUFBSSxFQUFFLHlCQUFrQixDQUFDLEVBQUUsQ0FBQyxDQUFDLENBQUM7b0NBQ3JFLEVBQUUsQ0FBQyxJQUFJLEVBQUUsSUFBSSxDQUFDLENBQUM7Z0NBQ2pCLENBQUM7NEJBQ0gsQ0FBQyxDQUFDLENBQUM7d0JBQ0w7NEJBQ0UsTUFBTSxDQUFDLEVBQUUsQ0FBQyxLQUFJLENBQUMsT0FBTyxDQUFDLEtBQUssRUFBRSxJQUFJLENBQUMsQ0FBQyxDQUFDO29CQUN6QyxDQUFDO2dCQUNILENBQUM7WUFDSCxDQUFDO1lBQUMsSUFBSSxDQUFDLENBQUM7Z0JBRU4sSUFBSSxNQUFjLENBQUM7Z0JBR25CLEVBQUUsQ0FBQyxDQUFDLE9BQU8sS0FBSyxJQUFJLENBQUMsQ0FBQyxDQUFDO29CQUNyQixNQUFNLEdBQUcsSUFBSSxNQUFNLENBQUMsQ0FBQyxDQUFDLENBQUM7Z0JBQ3pCLENBQUM7Z0JBQUMsSUFBSSxDQUFDLENBQUM7b0JBQ04sTUFBTSxHQUFHLHlCQUFrQixDQUFDLE9BQU8sQ0FBQyxDQUFDO2dCQUN2QyxDQUFDO2dCQUNELElBQUksSUFBSSxHQUFHLEtBQUksQ0FBQyxTQUFTLENBQUMsSUFBSSxFQUFFLEtBQUssRUFBRSxNQUFNLEVBQUUsTUFBTSxDQUFDLENBQUM7Z0JBQ3ZELE1BQU0sQ0FBQyxFQUFFLENBQUMsSUFBSSxFQUFFLElBQUksQ0FBQyxDQUFDO1lBQ3hCLENBQUM7UUFDSCxDQUFDLENBQUMsQ0FBQztJQUNMLENBQUM7SUFFTSw0Q0FBZ0IsR0FBdkIsVUFBd0IsQ0FBUyxFQUFFLElBQWlCLEVBQUUsRUFBbUQ7UUFBekcsaUJBZUM7UUFkQyxJQUFJLE1BQU0sR0FBRyxJQUFJLENBQUMsT0FBTyxDQUFDLENBQUMsQ0FBQyxDQUFDO1FBQzdCLElBQUksQ0FBQyxJQUFJLENBQUMsTUFBTSxFQUFFLEtBQUssRUFBRSxVQUFDLEtBQWUsRUFBRSxJQUFZO1lBQ3JELEVBQUUsQ0FBQyxDQUFDLEtBQUssQ0FBQyxDQUFDLENBQUM7Z0JBQ1YsRUFBRSxDQUFDLG9CQUFRLENBQUMsU0FBUyxDQUFDLHFCQUFTLENBQUMsTUFBTSxFQUFFLE1BQU0sQ0FBQyxDQUFDLENBQUM7WUFDbkQsQ0FBQztZQUFDLElBQUksQ0FBQyxDQUFDO2dCQUNOLEtBQUksQ0FBQyxPQUFPLENBQUMsU0FBUyxDQUFDLENBQUMsRUFBRSxJQUFJLEVBQUUsVUFBQyxNQUFNLEVBQUUsSUFBSTtvQkFDM0MsRUFBRSxDQUFDLENBQUMsTUFBTSxDQUFDLENBQUMsQ0FBQzt3QkFDWCxFQUFFLENBQUMsS0FBSSxDQUFDLE9BQU8sQ0FBQyxNQUFNLEVBQUUsQ0FBQyxDQUFDLENBQUMsQ0FBQztvQkFDOUIsQ0FBQztvQkFBQyxJQUFJLENBQUMsQ0FBQzt3QkFDTixFQUFFLENBQUMsSUFBSSxFQUFFLElBQUksQ0FBQyxDQUFDO29CQUNqQixDQUFDO2dCQUNILENBQUMsQ0FBQyxDQUFDO1lBQ0wsQ0FBQztRQUNILENBQUMsQ0FBQyxDQUFDO0lBQ0wsQ0FBQztJQU1NLHFDQUFTLEdBQWhCLFVBQWlCLElBQXVCO1FBQ3RDLE1BQU0sQ0FBQyxJQUFJLENBQUMsTUFBTSxHQUFHLHdCQUFRLENBQUMsSUFBSSxHQUFHLHdCQUFRLENBQUMsU0FBUyxDQUFDO0lBQzFELENBQUM7SUFPTSxxQ0FBUyxHQUFoQixVQUFpQixJQUFZLEVBQUUsSUFBd0IsRUFBRSxJQUF1QixFQUFFLE1BQWtCO1FBQ2xHLElBQUksSUFBSSxHQUFHLElBQUksQ0FBQyxTQUFTLENBQUMsSUFBSSxDQUFDLENBQUM7UUFDaEMsSUFBSSxLQUFLLEdBQUcsSUFBSSwwQkFBSyxDQUFDLElBQUksRUFBRSxJQUFJLENBQUMsSUFBSSxDQUFDLENBQUM7UUFDdkMsTUFBTSxDQUFDLElBQUksV0FBVyxDQUFDLElBQUksRUFBRSxJQUFJLEVBQUUsSUFBSSxFQUFFLEtBQUssRUFBRSxNQUFNLENBQUMsQ0FBQztJQUMxRCxDQUFDO0lBU00sbUNBQU8sR0FBZCxVQUFlLElBQVksRUFBRSxFQUEwQixFQUFFLE1BQWU7UUFBeEUsaUJBb0JDO1FBbkJDLElBQUksQ0FBQyxPQUFPLENBQUMsSUFBSSxDQUFDLElBQUksRUFBRSxVQUFDLEtBQUssRUFBRSxJQUFJO1lBQ2xDLEVBQUUsQ0FBQyxDQUFDLEtBQUssQ0FBQyxDQUFDLENBQUM7Z0JBQ1YsRUFBRSxDQUFDLEtBQUksQ0FBQyxPQUFPLENBQUMsS0FBSyxFQUFFLElBQUksQ0FBQyxDQUFDLENBQUM7WUFDaEMsQ0FBQztZQUFDLElBQUksQ0FBQyxDQUFDO2dCQUNOLEVBQUUsQ0FBQyxDQUFDLElBQUksQ0FBQyxNQUFNLElBQUksQ0FBQyxNQUFNLENBQUMsQ0FBQyxDQUFDO29CQUMzQixFQUFFLENBQUMsb0JBQVEsQ0FBQyxTQUFTLENBQUMscUJBQVMsQ0FBQyxPQUFPLEVBQUUsSUFBSSxDQUFDLENBQUMsQ0FBQztnQkFDbEQsQ0FBQztnQkFBQyxJQUFJLENBQUMsRUFBRSxDQUFDLENBQUMsQ0FBQyxJQUFJLENBQUMsTUFBTSxJQUFJLE1BQU0sQ0FBQyxDQUFDLENBQUM7b0JBQ2xDLEVBQUUsQ0FBQyxvQkFBUSxDQUFDLFNBQVMsQ0FBQyxxQkFBUyxDQUFDLE1BQU0sRUFBRSxJQUFJLENBQUMsQ0FBQyxDQUFDO2dCQUNqRCxDQUFDO2dCQUFDLElBQUksQ0FBQyxDQUFDO29CQUNOLEtBQUksQ0FBQyxPQUFPLENBQUMsTUFBTSxDQUFDLElBQUksRUFBRSxVQUFDLEtBQUs7d0JBQzlCLEVBQUUsQ0FBQyxDQUFDLEtBQUssQ0FBQyxDQUFDLENBQUM7NEJBQ1YsRUFBRSxDQUFDLEtBQUksQ0FBQyxPQUFPLENBQUMsS0FBSyxFQUFFLElBQUksQ0FBQyxDQUFDLENBQUM7d0JBQ2hDLENBQUM7d0JBQUMsSUFBSSxDQUFDLENBQUM7NEJBQ04sRUFBRSxDQUFDLElBQUksQ0FBQyxDQUFDO3dCQUNYLENBQUM7b0JBQ0gsQ0FBQyxDQUFDLENBQUM7Z0JBQ0wsQ0FBQztZQUNILENBQUM7UUFDSCxDQUFDLENBQUMsQ0FBQztJQUNMLENBQUM7SUFLTSxrQ0FBTSxHQUFiLFVBQWMsSUFBWSxFQUFFLEVBQTBCO1FBQ3BELElBQUksQ0FBQyxPQUFPLENBQUMsSUFBSSxFQUFFLEVBQUUsRUFBRSxJQUFJLENBQUMsQ0FBQztJQUMvQixDQUFDO0lBS00saUNBQUssR0FBWixVQUFhLElBQVksRUFBRSxFQUEwQjtRQUNuRCxJQUFJLENBQUMsT0FBTyxDQUFDLElBQUksRUFBRSxFQUFFLEVBQUUsS0FBSyxDQUFDLENBQUM7SUFDaEMsQ0FBQztJQUtNLGlDQUFLLEdBQVosVUFBYSxDQUFTLEVBQUUsSUFBWSxFQUFFLEVBQTBCO1FBQWhFLGlCQXNCQztRQWRDLElBQUksTUFBTSxHQUFHLElBQUksQ0FBQyxPQUFPLENBQUMsQ0FBQyxDQUFDLENBQUM7UUFDN0IsSUFBSSxDQUFDLE9BQU8sQ0FBQyxJQUFJLENBQUMsTUFBTSxFQUFFLFVBQUMsS0FBSyxFQUFFLElBQUk7WUFDcEMsRUFBRSxDQUFDLENBQUMsS0FBSyxDQUFDLENBQUMsQ0FBQztnQkFDVixFQUFFLENBQUMsS0FBSSxDQUFDLE9BQU8sQ0FBQyxLQUFLLEVBQUUsTUFBTSxDQUFDLENBQUMsQ0FBQztZQUNsQyxDQUFDO1lBQUMsSUFBSSxDQUFDLENBQUM7Z0JBQ04sS0FBSSxDQUFDLE9BQU8sQ0FBQyxLQUFLLENBQUMsQ0FBQyxFQUFFLFVBQUMsS0FBSztvQkFDMUIsRUFBRSxDQUFDLENBQUMsS0FBSyxDQUFDLENBQUMsQ0FBQzt3QkFDVixFQUFFLENBQUMsb0JBQVEsQ0FBQyxTQUFTLENBQUMscUJBQVMsQ0FBQyxNQUFNLEVBQUUsQ0FBQyxDQUFDLENBQUMsQ0FBQztvQkFDOUMsQ0FBQztvQkFBQyxJQUFJLENBQUMsQ0FBQzt3QkFDTixFQUFFLENBQUMsSUFBSSxDQUFDLENBQUM7b0JBQ1gsQ0FBQztnQkFDSCxDQUFDLENBQUMsQ0FBQztZQUNMLENBQUM7UUFDSCxDQUFDLENBQUMsQ0FBQztJQUNMLENBQUM7SUFLTSxtQ0FBTyxHQUFkLFVBQWUsSUFBWSxFQUFFLEVBQTZDO1FBQTFFLGlCQVFDO1FBUEMsSUFBSSxDQUFDLE9BQU8sQ0FBQyxPQUFPLENBQUMsSUFBSSxFQUFFLFVBQUMsS0FBSyxFQUFFLEtBQUs7WUFDdEMsRUFBRSxDQUFDLENBQUMsS0FBSyxDQUFDLENBQUMsQ0FBQztnQkFDVixNQUFNLENBQUMsRUFBRSxDQUFDLEtBQUksQ0FBQyxPQUFPLENBQUMsS0FBSyxDQUFDLENBQUMsQ0FBQztZQUNqQyxDQUFDO1lBQUMsSUFBSSxDQUFDLENBQUM7Z0JBQ04sTUFBTSxDQUFDLEVBQUUsQ0FBQyxJQUFJLEVBQUUsS0FBSyxDQUFDLENBQUM7WUFDekIsQ0FBQztRQUNILENBQUMsQ0FBQyxDQUFDO0lBQ0wsQ0FBQztJQUtNLG1DQUFPLEdBQWQsVUFBZSxHQUFxQixFQUFFLElBQW1CO1FBQW5CLG9CQUFtQixHQUFuQixXQUFtQjtRQUN2RCxJQUFJLFNBQVMsR0FBRyxlQUFlLENBQUMsR0FBRyxDQUFDLE1BQU0sQ0FBQyxDQUFDO1FBQzVDLEVBQUUsQ0FBQyxDQUFDLFNBQVMsS0FBSyxTQUFTLENBQUMsQ0FBQyxDQUFDO1lBQzVCLFNBQVMsR0FBRyxxQkFBUyxDQUFDLEdBQUcsQ0FBQztRQUM1QixDQUFDO1FBRUQsRUFBRSxDQUFDLENBQUMsSUFBSSxJQUFJLElBQUksQ0FBQyxDQUFDLENBQUM7WUFDakIsTUFBTSxDQUFDLElBQUksb0JBQVEsQ0FBQyxTQUFTLENBQUMsQ0FBQztRQUNqQyxDQUFDO1FBQUMsSUFBSSxDQUFDLENBQUM7WUFDTixNQUFNLENBQUMsb0JBQVEsQ0FBQyxTQUFTLENBQUMsU0FBUyxFQUFFLElBQUksQ0FBQyxDQUFDO1FBQzdDLENBQUM7SUFDSCxDQUFDO0lBQ0gsd0JBQUM7QUFBRCxDQUFDLEFBM1JELENBQStDLFdBQVcsQ0FBQyxjQUFjLEdBMlJ4RTtBQTNSRDtzQ0EyUkMsQ0FBQSIsInNvdXJjZXNDb250ZW50IjpbImltcG9ydCBwcmVsb2FkX2ZpbGUgPSByZXF1aXJlKCcuLi9nZW5lcmljL3ByZWxvYWRfZmlsZScpO1xyXG5pbXBvcnQgZmlsZV9zeXN0ZW0gPSByZXF1aXJlKCcuLi9jb3JlL2ZpbGVfc3lzdGVtJyk7XHJcbmltcG9ydCBmaWxlX2ZsYWcgPSByZXF1aXJlKCcuLi9jb3JlL2ZpbGVfZmxhZycpO1xyXG5pbXBvcnQge2RlZmF1bHQgYXMgU3RhdHMsIEZpbGVUeXBlfSBmcm9tICcuLi9jb3JlL25vZGVfZnNfc3RhdHMnO1xyXG5pbXBvcnQge0FwaUVycm9yLCBFcnJvckNvZGV9IGZyb20gJy4uL2NvcmUvYXBpX2Vycm9yJztcclxuaW1wb3J0IGZpbGUgPSByZXF1aXJlKCcuLi9jb3JlL2ZpbGUnKTtcclxuaW1wb3J0IGFzeW5jID0gcmVxdWlyZSgnYXN5bmMnKTtcclxuaW1wb3J0IHBhdGggPSByZXF1aXJlKCdwYXRoJyk7XHJcbmltcG9ydCB7YXJyYXlCdWZmZXIyQnVmZmVyLCBidWZmZXIyQXJyYXlCdWZmZXJ9IGZyb20gJy4uL2NvcmUvdXRpbCc7XHJcblxyXG52YXIgZXJyb3JDb2RlTG9va3VwOiB7W2Ryb3Bib3hFcnJvckNvZGU6IG51bWJlcl06IEVycm9yQ29kZX0gPSBudWxsO1xyXG4vLyBMYXppbHkgY29uc3RydWN0IGVycm9yIGNvZGUgbG9va3VwLCBzaW5jZSBEcm9wYm94SlMgbWlnaHQgYmUgbG9hZGVkICphZnRlciogQnJvd3NlckZTIChvciBub3QgYXQgYWxsISlcclxuZnVuY3Rpb24gY29uc3RydWN0RXJyb3JDb2RlTG9va3VwKCkge1xyXG4gIGlmIChlcnJvckNvZGVMb29rdXAgIT09IG51bGwpIHtcclxuICAgIHJldHVybjtcclxuICB9XHJcbiAgZXJyb3JDb2RlTG9va3VwID0ge307XHJcbiAgLy8gVGhpcyBpbmRpY2F0ZXMgYSBuZXR3b3JrIHRyYW5zbWlzc2lvbiBlcnJvciBvbiBtb2Rlcm4gYnJvd3NlcnMuIEludGVybmV0IEV4cGxvcmVyIG1pZ2h0IGNhdXNlIHRoaXMgY29kZSB0byBiZSByZXBvcnRlZCBvbiBzb21lIEFQSSBzZXJ2ZXIgZXJyb3JzLlxyXG4gIGVycm9yQ29kZUxvb2t1cFtEcm9wYm94LkFwaUVycm9yLk5FVFdPUktfRVJST1JdID0gRXJyb3JDb2RlLkVJTztcclxuICAvLyBUaGlzIGhhcHBlbnMgd2hlbiB0aGUgY29udGVudEhhc2ggcGFyYW1ldGVyIHBhc3NlZCB0byBhIERyb3Bib3guQ2xpZW50I3JlYWRkaXIgb3IgRHJvcGJveC5DbGllbnQjc3RhdCBtYXRjaGVzIHRoZSBtb3N0IHJlY2VudCBjb250ZW50LCBzbyB0aGUgQVBJIGNhbGwgcmVzcG9uc2UgaXMgb21pdHRlZCwgdG8gc2F2ZSBiYW5kd2lkdGguXHJcbiAgLy8gZXJyb3JDb2RlTG9va3VwW0Ryb3Bib3guQXBpRXJyb3IuTk9fQ09OVEVOVF07XHJcbiAgLy8gVGhlIGVycm9yIHByb3BlcnR5IG9uIHtEcm9wYm94LkFwaUVycm9yI3Jlc3BvbnNlfSBzaG91bGQgaW5kaWNhdGUgd2hpY2ggaW5wdXQgcGFyYW1ldGVyIGlzIGludmFsaWQgYW5kIHdoeS5cclxuICBlcnJvckNvZGVMb29rdXBbRHJvcGJveC5BcGlFcnJvci5JTlZBTElEX1BBUkFNXSA9IEVycm9yQ29kZS5FSU5WQUw7XHJcbiAgLy8gVGhlIE9BdXRoIHRva2VuIHVzZWQgZm9yIHRoZSByZXF1ZXN0IHdpbGwgbmV2ZXIgYmVjb21lIHZhbGlkIGFnYWluLCBzbyB0aGUgdXNlciBzaG91bGQgYmUgcmUtYXV0aGVudGljYXRlZC5cclxuICBlcnJvckNvZGVMb29rdXBbRHJvcGJveC5BcGlFcnJvci5JTlZBTElEX1RPS0VOXSA9IEVycm9yQ29kZS5FUEVSTTtcclxuICAvLyBUaGlzIGluZGljYXRlcyBhIGJ1ZyBpbiBkcm9wYm94LmpzIGFuZCBzaG91bGQgbmV2ZXIgb2NjdXIgdW5kZXIgbm9ybWFsIGNpcmN1bXN0YW5jZXMuXHJcbiAgLy8gXiBBY3R1YWxseSwgdGhhdCdzIGZhbHNlLiBUaGlzIG9jY3VycyB3aGVuIHlvdSB0cnkgdG8gbW92ZSBmb2xkZXJzIHRvIHRoZW1zZWx2ZXMsIG9yIG1vdmUgYSBmaWxlIG92ZXIgYW5vdGhlciBmaWxlLlxyXG4gIGVycm9yQ29kZUxvb2t1cFtEcm9wYm94LkFwaUVycm9yLk9BVVRIX0VSUk9SXSA9IEVycm9yQ29kZS5FUEVSTTtcclxuICAvLyBUaGlzIGhhcHBlbnMgd2hlbiB0cnlpbmcgdG8gcmVhZCBmcm9tIGEgbm9uLWV4aXN0aW5nIGZpbGUsIHJlYWRkaXIgYSBub24tZXhpc3RpbmcgZGlyZWN0b3J5LCB3cml0ZSBhIGZpbGUgaW50byBhIG5vbi1leGlzdGluZyBkaXJlY3RvcnksIGV0Yy5cclxuICBlcnJvckNvZGVMb29rdXBbRHJvcGJveC5BcGlFcnJvci5OT1RfRk9VTkRdID0gRXJyb3JDb2RlLkVOT0VOVDtcclxuICAvLyBUaGlzIGluZGljYXRlcyBhIGJ1ZyBpbiBkcm9wYm94LmpzIGFuZCBzaG91bGQgbmV2ZXIgb2NjdXIgdW5kZXIgbm9ybWFsIGNpcmN1bXN0YW5jZXMuXHJcbiAgZXJyb3JDb2RlTG9va3VwW0Ryb3Bib3guQXBpRXJyb3IuSU5WQUxJRF9NRVRIT0RdID0gRXJyb3JDb2RlLkVJTlZBTDtcclxuICAvLyBUaGlzIGhhcHBlbnMgd2hlbiBhIERyb3Bib3guQ2xpZW50I3JlYWRkaXIgb3IgRHJvcGJveC5DbGllbnQjc3RhdCBjYWxsIHdvdWxkIHJldHVybiBtb3JlIHRoYW4gYSBtYXhpbXVtIGFtb3VudCBvZiBkaXJlY3RvcnkgZW50cmllcy5cclxuICBlcnJvckNvZGVMb29rdXBbRHJvcGJveC5BcGlFcnJvci5OT1RfQUNDRVBUQUJMRV0gPSBFcnJvckNvZGUuRUlOVkFMO1xyXG4gIC8vIFRoaXMgaXMgdXNlZCBieSBzb21lIGJhY2tlbmQgbWV0aG9kcyB0byBpbmRpY2F0ZSB0aGF0IHRoZSBjbGllbnQgbmVlZHMgdG8gZG93bmxvYWQgc2VydmVyLXNpZGUgY2hhbmdlcyBhbmQgcGVyZm9ybSBjb25mbGljdCByZXNvbHV0aW9uLiBVbmRlciBub3JtYWwgdXNhZ2UsIGVycm9ycyB3aXRoIHRoaXMgY29kZSBzaG91bGQgbmV2ZXIgc3VyZmFjZSB0byB0aGUgY29kZSB1c2luZyBkcm9wYm94LmpzLlxyXG4gIGVycm9yQ29kZUxvb2t1cFtEcm9wYm94LkFwaUVycm9yLkNPTkZMSUNUXSA9IEVycm9yQ29kZS5FSU5WQUw7XHJcbiAgLy8gU3RhdHVzIHZhbHVlIGluZGljYXRpbmcgdGhhdCB0aGUgYXBwbGljYXRpb24gaXMgbWFraW5nIHRvbyBtYW55IHJlcXVlc3RzLlxyXG4gIGVycm9yQ29kZUxvb2t1cFtEcm9wYm94LkFwaUVycm9yLlJBVEVfTElNSVRFRF0gPSBFcnJvckNvZGUuRUJVU1k7XHJcbiAgLy8gVGhlIHJlcXVlc3Qgc2hvdWxkIGJlIHJldHJpZWQgYWZ0ZXIgc29tZSB0aW1lLlxyXG4gIGVycm9yQ29kZUxvb2t1cFtEcm9wYm94LkFwaUVycm9yLlNFUlZFUl9FUlJPUl0gPSBFcnJvckNvZGUuRUJVU1k7XHJcbiAgLy8gU3RhdHVzIHZhbHVlIGluZGljYXRpbmcgdGhhdCB0aGUgdXNlcidzIERyb3Bib3ggaXMgb3ZlciBpdHMgc3RvcmFnZSBxdW90YS5cclxuICBlcnJvckNvZGVMb29rdXBbRHJvcGJveC5BcGlFcnJvci5PVkVSX1FVT1RBXSA9IEVycm9yQ29kZS5FTk9TUEM7XHJcbn1cclxuXHJcbmludGVyZmFjZSBJQ2FjaGVkUGF0aEluZm8ge1xyXG4gIHN0YXQ6IERyb3Bib3guRmlsZS5TdGF0O1xyXG59XHJcblxyXG5pbnRlcmZhY2UgSUNhY2hlZEZpbGVJbmZvIGV4dGVuZHMgSUNhY2hlZFBhdGhJbmZvIHtcclxuICBjb250ZW50czogQXJyYXlCdWZmZXI7XHJcbn1cclxuXHJcbmZ1bmN0aW9uIGlzRmlsZUluZm8oY2FjaGU6IElDYWNoZWRQYXRoSW5mbyk6IGNhY2hlIGlzIElDYWNoZWRGaWxlSW5mbyB7XHJcbiAgcmV0dXJuIGNhY2hlICYmIGNhY2hlLnN0YXQuaXNGaWxlO1xyXG59XHJcblxyXG5pbnRlcmZhY2UgSUNhY2hlZERpckluZm8gZXh0ZW5kcyBJQ2FjaGVkUGF0aEluZm8ge1xyXG4gIGNvbnRlbnRzOiBzdHJpbmdbXTtcclxufVxyXG5cclxuZnVuY3Rpb24gaXNEaXJJbmZvKGNhY2hlOiBJQ2FjaGVkUGF0aEluZm8pOiBjYWNoZSBpcyBJQ2FjaGVkRGlySW5mbyB7XHJcbiAgcmV0dXJuIGNhY2hlICYmIGNhY2hlLnN0YXQuaXNGb2xkZXI7XHJcbn1cclxuXHJcbmZ1bmN0aW9uIGlzQXJyYXlCdWZmZXIoYWI6IGFueSk6IGFiIGlzIEFycmF5QnVmZmVyIHtcclxuICAvLyBBY2NlcHQgbnVsbCAvIHVuZGVmaW5lZCwgdG9vLlxyXG4gIHJldHVybiBhYiA9PT0gbnVsbCB8fCBhYiA9PT0gdW5kZWZpbmVkIHx8ICh0eXBlb2YoYWIpID09PSAnb2JqZWN0JyAmJiB0eXBlb2YoYWJbJ2J5dGVMZW5ndGgnXSkgPT09ICdudW1iZXInKTtcclxufVxyXG5cclxuLyoqXHJcbiAqIFdyYXBzIGEgRHJvcGJveCBjbGllbnQgYW5kIGNhY2hlcyBvcGVyYXRpb25zLlxyXG4gKi9cclxuY2xhc3MgQ2FjaGVkRHJvcGJveENsaWVudCB7XHJcbiAgcHJpdmF0ZSBfY2FjaGU6IHtbcGF0aDogc3RyaW5nXTogSUNhY2hlZFBhdGhJbmZvfSA9IHt9O1xyXG4gIHByaXZhdGUgX2NsaWVudDogRHJvcGJveC5DbGllbnQ7XHJcblxyXG4gIGNvbnN0cnVjdG9yKGNsaWVudDogRHJvcGJveC5DbGllbnQpIHtcclxuICAgIHRoaXMuX2NsaWVudCA9IGNsaWVudDtcclxuICB9XHJcblxyXG4gIHByaXZhdGUgZ2V0Q2FjaGVkSW5mbyhwOiBzdHJpbmcpOiBJQ2FjaGVkUGF0aEluZm8ge1xyXG4gICAgcmV0dXJuIHRoaXMuX2NhY2hlW3AudG9Mb3dlckNhc2UoKV07XHJcbiAgfVxyXG5cclxuICBwcml2YXRlIHB1dENhY2hlZEluZm8ocDogc3RyaW5nLCBjYWNoZTogSUNhY2hlZFBhdGhJbmZvKTogdm9pZCB7XHJcbiAgICB0aGlzLl9jYWNoZVtwLnRvTG93ZXJDYXNlKCldID0gY2FjaGU7XHJcbiAgfVxyXG5cclxuICBwcml2YXRlIGRlbGV0ZUNhY2hlZEluZm8ocDogc3RyaW5nKTogdm9pZCB7XHJcbiAgICBkZWxldGUgdGhpcy5fY2FjaGVbcC50b0xvd2VyQ2FzZSgpXTtcclxuICB9XHJcblxyXG4gIHByaXZhdGUgZ2V0Q2FjaGVkRGlySW5mbyhwOiBzdHJpbmcpOiBJQ2FjaGVkRGlySW5mbyB7XHJcbiAgICB2YXIgaW5mbyA9IHRoaXMuZ2V0Q2FjaGVkSW5mbyhwKTtcclxuICAgIGlmIChpc0RpckluZm8oaW5mbykpIHtcclxuICAgICAgcmV0dXJuIGluZm87XHJcbiAgICB9IGVsc2Uge1xyXG4gICAgICByZXR1cm4gbnVsbDtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIHByaXZhdGUgZ2V0Q2FjaGVkRmlsZUluZm8ocDogc3RyaW5nKTogSUNhY2hlZEZpbGVJbmZvIHtcclxuICAgIHZhciBpbmZvID0gdGhpcy5nZXRDYWNoZWRJbmZvKHApO1xyXG4gICAgaWYgKGlzRmlsZUluZm8oaW5mbykpIHtcclxuICAgICAgcmV0dXJuIGluZm87XHJcbiAgICB9IGVsc2Uge1xyXG4gICAgICByZXR1cm4gbnVsbDtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIHByaXZhdGUgdXBkYXRlQ2FjaGVkRGlySW5mbyhwOiBzdHJpbmcsIHN0YXQ6IERyb3Bib3guRmlsZS5TdGF0LCBjb250ZW50czogc3RyaW5nW10gPSBudWxsKTogdm9pZCB7XHJcbiAgICB2YXIgY2FjaGVkSW5mbyA9IHRoaXMuZ2V0Q2FjaGVkSW5mbyhwKTtcclxuICAgIC8vIERyb3Bib3ggdXNlcyB0aGUgKmNvbnRlbnRIYXNoKiBwcm9wZXJ0eSBmb3IgZGlyZWN0b3JpZXMuXHJcbiAgICAvLyBJZ25vcmUgc3RhdCBvYmplY3RzIHcvbyBhIGNvbnRlbnRIYXNoIGRlZmluZWQ7IHRob3NlIGFjdHVhbGx5IGV4aXN0ISEhXHJcbiAgICAvLyAoRXhhbXBsZTogcmVhZGRpciByZXR1cm5zIGFuIGFycmF5IG9mIHN0YXQgb2Jqczsgc3RhdCBvYmpzIGZvciBkaXJzIGluIHRoYXQgY29udGV4dCBoYXZlIG5vIGNvbnRlbnRIYXNoKVxyXG4gICAgaWYgKHN0YXQuY29udGVudEhhc2ggIT09IG51bGwgJiYgKGNhY2hlZEluZm8gPT09IHVuZGVmaW5lZCB8fCBjYWNoZWRJbmZvLnN0YXQuY29udGVudEhhc2ggIT09IHN0YXQuY29udGVudEhhc2gpKSB7XHJcbiAgICAgIHRoaXMucHV0Q2FjaGVkSW5mbyhwLCA8SUNhY2hlZERpckluZm8+IHtcclxuICAgICAgICBzdGF0OiBzdGF0LFxyXG4gICAgICAgIGNvbnRlbnRzOiBjb250ZW50c1xyXG4gICAgICB9KTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIHByaXZhdGUgdXBkYXRlQ2FjaGVkRmlsZUluZm8ocDogc3RyaW5nLCBzdGF0OiBEcm9wYm94LkZpbGUuU3RhdCwgY29udGVudHM6IEFycmF5QnVmZmVyID0gbnVsbCk6IHZvaWQge1xyXG4gICAgdmFyIGNhY2hlZEluZm8gPSB0aGlzLmdldENhY2hlZEluZm8ocCk7XHJcbiAgICAvLyBEcm9wYm94IHVzZXMgdGhlICp2ZXJzaW9uVGFnKiBwcm9wZXJ0eSBmb3IgZmlsZXMuXHJcbiAgICAvLyBJZ25vcmUgc3RhdCBvYmplY3RzIHcvbyBhIHZlcnNpb25UYWcgZGVmaW5lZC5cclxuICAgIGlmIChzdGF0LnZlcnNpb25UYWcgIT09IG51bGwgJiYgKGNhY2hlZEluZm8gPT09IHVuZGVmaW5lZCB8fCBjYWNoZWRJbmZvLnN0YXQudmVyc2lvblRhZyAhPT0gc3RhdC52ZXJzaW9uVGFnKSkge1xyXG4gICAgICB0aGlzLnB1dENhY2hlZEluZm8ocCwgPElDYWNoZWRGaWxlSW5mbz4ge1xyXG4gICAgICAgIHN0YXQ6IHN0YXQsXHJcbiAgICAgICAgY29udGVudHM6IGNvbnRlbnRzXHJcbiAgICAgIH0pO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgcHJpdmF0ZSB1cGRhdGVDYWNoZWRJbmZvKHA6IHN0cmluZywgc3RhdDogRHJvcGJveC5GaWxlLlN0YXQsIGNvbnRlbnRzOiBBcnJheUJ1ZmZlciB8IHN0cmluZ1tdID0gbnVsbCk6IHZvaWQge1xyXG4gICAgaWYgKHN0YXQuaXNGaWxlICYmIGlzQXJyYXlCdWZmZXIoY29udGVudHMpKSB7XHJcbiAgICAgIHRoaXMudXBkYXRlQ2FjaGVkRmlsZUluZm8ocCwgc3RhdCwgY29udGVudHMpO1xyXG4gICAgfSBlbHNlIGlmIChzdGF0LmlzRm9sZGVyICYmIEFycmF5LmlzQXJyYXkoY29udGVudHMpKSB7XHJcbiAgICAgIHRoaXMudXBkYXRlQ2FjaGVkRGlySW5mbyhwLCBzdGF0LCBjb250ZW50cyk7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgcmVhZGRpcihwOiBzdHJpbmcsIGNiOiAoZXJyb3I6IERyb3Bib3guQXBpRXJyb3IsIGNvbnRlbnRzPzogc3RyaW5nW10pID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHZhciBjYWNoZUluZm8gPSB0aGlzLmdldENhY2hlZERpckluZm8ocCk7XHJcblxyXG4gICAgdGhpcy5fd3JhcCgoaW50ZXJjZXB0Q2IpID0+IHtcclxuICAgICAgaWYgKGNhY2hlSW5mbyAhPT0gbnVsbCAmJiBjYWNoZUluZm8uY29udGVudHMpIHtcclxuICAgICAgICB0aGlzLl9jbGllbnQucmVhZGRpcihwLCB7XHJcbiAgICAgICAgICBjb250ZW50SGFzaDogY2FjaGVJbmZvLnN0YXQuY29udGVudEhhc2hcclxuICAgICAgICB9LCBpbnRlcmNlcHRDYik7XHJcbiAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgdGhpcy5fY2xpZW50LnJlYWRkaXIocCwgaW50ZXJjZXB0Q2IpO1xyXG4gICAgICB9XHJcbiAgICB9LCAoZXJyOiBEcm9wYm94LkFwaUVycm9yLCBmaWxlbmFtZXM6IHN0cmluZ1tdLCBzdGF0OiBEcm9wYm94LkZpbGUuU3RhdCwgZm9sZGVyRW50cmllczogRHJvcGJveC5GaWxlLlN0YXRbXSkgPT4ge1xyXG4gICAgICBpZiAoZXJyKSB7XHJcbiAgICAgICAgaWYgKGVyci5zdGF0dXMgPT09IERyb3Bib3guQXBpRXJyb3IuTk9fQ09OVEVOVCAmJiBjYWNoZUluZm8gIT09IG51bGwpIHtcclxuICAgICAgICAgIGNiKG51bGwsIGNhY2hlSW5mby5jb250ZW50cy5zbGljZSgwKSk7XHJcbiAgICAgICAgfSBlbHNlIHtcclxuICAgICAgICAgIGNiKGVycik7XHJcbiAgICAgICAgfVxyXG4gICAgICB9IGVsc2Uge1xyXG4gICAgICAgIHRoaXMudXBkYXRlQ2FjaGVkRGlySW5mbyhwLCBzdGF0LCBmaWxlbmFtZXMuc2xpY2UoMCkpO1xyXG4gICAgICAgIGZvbGRlckVudHJpZXMuZm9yRWFjaCgoZW50cnkpID0+IHtcclxuICAgICAgICAgIHRoaXMudXBkYXRlQ2FjaGVkSW5mbyhwYXRoLmpvaW4ocCwgZW50cnkubmFtZSksIGVudHJ5KTtcclxuICAgICAgICB9KTtcclxuICAgICAgICBjYihudWxsLCBmaWxlbmFtZXMpO1xyXG4gICAgICB9XHJcbiAgICB9KTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyByZW1vdmUocDogc3RyaW5nLCBjYjogKGVycm9yPzogRHJvcGJveC5BcGlFcnJvcikgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgdGhpcy5fd3JhcCgoaW50ZXJjZXB0Q2IpID0+IHtcclxuICAgICAgdGhpcy5fY2xpZW50LnJlbW92ZShwLCBpbnRlcmNlcHRDYik7XHJcbiAgICB9LCAoZXJyOiBEcm9wYm94LkFwaUVycm9yLCBzdGF0PzogRHJvcGJveC5GaWxlLlN0YXQpID0+IHtcclxuICAgICAgaWYgKCFlcnIpIHtcclxuICAgICAgICB0aGlzLnVwZGF0ZUNhY2hlZEluZm8ocCwgc3RhdCk7XHJcbiAgICAgIH1cclxuICAgICAgY2IoZXJyKTtcclxuICAgIH0pO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIG1vdmUoc3JjOiBzdHJpbmcsIGRlc3Q6IHN0cmluZywgY2I6IChlcnJvcj86IERyb3Bib3guQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHRoaXMuX3dyYXAoKGludGVyY2VwdENiKSA9PiB7XHJcbiAgICAgIHRoaXMuX2NsaWVudC5tb3ZlKHNyYywgZGVzdCwgaW50ZXJjZXB0Q2IpO1xyXG4gICAgfSwgKGVycjogRHJvcGJveC5BcGlFcnJvciwgc3RhdDogRHJvcGJveC5GaWxlLlN0YXQpID0+IHtcclxuICAgICAgaWYgKCFlcnIpIHtcclxuICAgICAgICB0aGlzLmRlbGV0ZUNhY2hlZEluZm8oc3JjKTtcclxuICAgICAgICB0aGlzLnVwZGF0ZUNhY2hlZEluZm8oZGVzdCwgc3RhdCk7XHJcbiAgICAgIH1cclxuICAgICAgY2IoZXJyKTtcclxuICAgIH0pO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIHN0YXQocDogc3RyaW5nLCBjYjogKGVycm9yOiBEcm9wYm94LkFwaUVycm9yLCBzdGF0PzogRHJvcGJveC5GaWxlLlN0YXQpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHRoaXMuX3dyYXAoKGludGVyY2VwdENiKSA9PiB7XHJcbiAgICAgIHRoaXMuX2NsaWVudC5zdGF0KHAsIGludGVyY2VwdENiKTtcclxuICAgIH0sIChlcnI6IERyb3Bib3guQXBpRXJyb3IsIHN0YXQ6IERyb3Bib3guRmlsZS5TdGF0KSA9PiB7XHJcbiAgICAgIGlmICghZXJyKSB7XHJcbiAgICAgICAgdGhpcy51cGRhdGVDYWNoZWRJbmZvKHAsIHN0YXQpO1xyXG4gICAgICB9XHJcbiAgICAgIGNiKGVyciwgc3RhdCk7XHJcbiAgICB9KTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyByZWFkRmlsZShwOiBzdHJpbmcsIGNiOiAoZXJyb3I6IERyb3Bib3guQXBpRXJyb3IsIGZpbGU/OiBBcnJheUJ1ZmZlciwgc3RhdD86IERyb3Bib3guRmlsZS5TdGF0KSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICB2YXIgY2FjaGVJbmZvID0gdGhpcy5nZXRDYWNoZWRGaWxlSW5mbyhwKTtcclxuICAgIGlmIChjYWNoZUluZm8gIT09IG51bGwgJiYgY2FjaGVJbmZvLmNvbnRlbnRzICE9PSBudWxsKSB7XHJcbiAgICAgIC8vIFRyeSB0byB1c2UgY2FjaGVkIGluZm87IGlzc3VlIGEgc3RhdCB0byBzZWUgaWYgY29udGVudHMgYXJlIHVwLXRvLWRhdGUuXHJcbiAgICAgIHRoaXMuc3RhdChwLCAoZXJyb3IsIHN0YXQ/KSA9PiB7XHJcbiAgICAgICAgaWYgKGVycm9yKSB7XHJcbiAgICAgICAgICBjYihlcnJvcik7XHJcbiAgICAgICAgfSBlbHNlIGlmIChzdGF0LmNvbnRlbnRIYXNoID09PSBjYWNoZUluZm8uc3RhdC5jb250ZW50SGFzaCkge1xyXG4gICAgICAgICAgLy8gTm8gZmlsZSBjaGFuZ2VzLlxyXG4gICAgICAgICAgY2IoZXJyb3IsIGNhY2hlSW5mby5jb250ZW50cy5zbGljZSgwKSwgY2FjaGVJbmZvLnN0YXQpO1xyXG4gICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICAvLyBGaWxlIGNoYW5nZXM7IHJlcnVuIHRvIHRyaWdnZXIgYWN0dWFsIHJlYWRGaWxlLlxyXG4gICAgICAgICAgdGhpcy5yZWFkRmlsZShwLCBjYik7XHJcbiAgICAgICAgfVxyXG4gICAgICB9KTtcclxuICAgIH0gZWxzZSB7XHJcbiAgICAgIHRoaXMuX3dyYXAoKGludGVyY2VwdENiKSA9PiB7XHJcbiAgICAgICAgdGhpcy5fY2xpZW50LnJlYWRGaWxlKHAsIHsgYXJyYXlCdWZmZXI6IHRydWUgfSwgaW50ZXJjZXB0Q2IpO1xyXG4gICAgICB9LCAoZXJyOiBEcm9wYm94LkFwaUVycm9yLCBjb250ZW50czogYW55LCBzdGF0OiBEcm9wYm94LkZpbGUuU3RhdCkgPT4ge1xyXG4gICAgICAgIGlmICghZXJyKSB7XHJcbiAgICAgICAgICB0aGlzLnVwZGF0ZUNhY2hlZEluZm8ocCwgc3RhdCwgY29udGVudHMuc2xpY2UoMCkpO1xyXG4gICAgICAgIH1cclxuICAgICAgICBjYihlcnIsIGNvbnRlbnRzLCBzdGF0KTtcclxuICAgICAgfSk7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgd3JpdGVGaWxlKHA6IHN0cmluZywgY29udGVudHM6IEFycmF5QnVmZmVyLCBjYjogKGVycm9yOiBEcm9wYm94LkFwaUVycm9yLCBzdGF0PzogRHJvcGJveC5GaWxlLlN0YXQpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHRoaXMuX3dyYXAoKGludGVyY2VwdENiKSA9PiB7XHJcbiAgICAgIHRoaXMuX2NsaWVudC53cml0ZUZpbGUocCwgY29udGVudHMsIGludGVyY2VwdENiKTtcclxuICAgIH0sKGVycjogRHJvcGJveC5BcGlFcnJvciwgc3RhdDogRHJvcGJveC5GaWxlLlN0YXQpID0+IHtcclxuICAgICAgaWYgKCFlcnIpIHtcclxuICAgICAgICB0aGlzLnVwZGF0ZUNhY2hlZEluZm8ocCwgc3RhdCwgY29udGVudHMuc2xpY2UoMCkpO1xyXG4gICAgICB9XHJcbiAgICAgIGNiKGVyciwgc3RhdCk7XHJcbiAgICB9KTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBta2RpcihwOiBzdHJpbmcsIGNiOiAoZXJyb3I/OiBEcm9wYm94LkFwaUVycm9yKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICB0aGlzLl93cmFwKChpbnRlcmNlcHRDYikgPT4ge1xyXG4gICAgICB0aGlzLl9jbGllbnQubWtkaXIocCwgaW50ZXJjZXB0Q2IpO1xyXG4gICAgfSwgKGVycjogRHJvcGJveC5BcGlFcnJvciwgc3RhdDogRHJvcGJveC5GaWxlLlN0YXQpID0+IHtcclxuICAgICAgaWYgKCFlcnIpIHtcclxuICAgICAgICB0aGlzLnVwZGF0ZUNhY2hlZEluZm8ocCwgc3RhdCwgW10pO1xyXG4gICAgICB9XHJcbiAgICAgIGNiKGVycik7XHJcbiAgICB9KTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIFdyYXBzIGFuIG9wZXJhdGlvbiBzdWNoIHRoYXQgd2UgcmV0cnkgYSBmYWlsZWQgb3BlcmF0aW9uIDMgdGltZXMuXHJcbiAgICogTmVjZXNzYXJ5IHRvIGRlYWwgd2l0aCBEcm9wYm94IHJhdGUgbGltaXRpbmcuXHJcbiAgICpcclxuICAgKiBAcGFyYW0gcGVyZm9ybU9wIEZ1bmN0aW9uIHRoYXQgcGVyZm9ybXMgdGhlIG9wZXJhdGlvbi4gV2lsbCBiZSBjYWxsZWQgdXAgdG8gdGhyZWUgdGltZXMuXHJcbiAgICogQHBhcmFtIGNiIENhbGxlZCB3aGVuIHRoZSBvcGVyYXRpb24gc3VjY2VlZHMsIGZhaWxzIGluIGEgbm9uLXRlbXBvcmFyeSBtYW5uZXIsIG9yIGZhaWxzIHRocmVlIHRpbWVzLlxyXG4gICAqL1xyXG4gIHByaXZhdGUgX3dyYXAocGVyZm9ybU9wOiAoaW50ZXJjZXB0Q2I6IChlcnJvcjogRHJvcGJveC5BcGlFcnJvcikgPT4gdm9pZCkgPT4gdm9pZCwgY2I6IEZ1bmN0aW9uKTogdm9pZCB7XHJcbiAgICB2YXIgbnVtUnVuID0gMCxcclxuICAgICAgaW50ZXJjZXB0Q2IgPSBmdW5jdGlvbiAoZXJyb3I6IERyb3Bib3guQXBpRXJyb3IpOiB2b2lkIHtcclxuICAgICAgICAvLyBUaW1lb3V0IGR1cmF0aW9uLCBpbiBzZWNvbmRzLlxyXG4gICAgICAgIHZhciB0aW1lb3V0RHVyYXRpb246IG51bWJlciA9IDI7XHJcbiAgICAgICAgaWYgKGVycm9yICYmIDMgPiAoKytudW1SdW4pKSB7XHJcbiAgICAgICAgICBzd2l0Y2goZXJyb3Iuc3RhdHVzKSB7XHJcbiAgICAgICAgICAgIGNhc2UgRHJvcGJveC5BcGlFcnJvci5TRVJWRVJfRVJST1I6XHJcbiAgICAgICAgICAgIGNhc2UgRHJvcGJveC5BcGlFcnJvci5ORVRXT1JLX0VSUk9SOlxyXG4gICAgICAgICAgICBjYXNlIERyb3Bib3guQXBpRXJyb3IuUkFURV9MSU1JVEVEOlxyXG4gICAgICAgICAgICAgIHNldFRpbWVvdXQoKCkgPT4ge1xyXG4gICAgICAgICAgICAgICAgcGVyZm9ybU9wKGludGVyY2VwdENiKTtcclxuICAgICAgICAgICAgICB9LCB0aW1lb3V0RHVyYXRpb24gKiAxMDAwKTtcclxuICAgICAgICAgICAgICBicmVhaztcclxuICAgICAgICAgICAgZGVmYXVsdDpcclxuICAgICAgICAgICAgICBjYi5hcHBseShudWxsLCBhcmd1bWVudHMpO1xyXG4gICAgICAgICAgICAgIGJyZWFrO1xyXG4gICAgICAgICAgfVxyXG4gICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICBjYi5hcHBseShudWxsLCBhcmd1bWVudHMpO1xyXG4gICAgICAgIH1cclxuICAgICAgfTtcclxuXHJcbiAgICBwZXJmb3JtT3AoaW50ZXJjZXB0Q2IpO1xyXG4gIH1cclxufVxyXG5cclxuZXhwb3J0IGNsYXNzIERyb3Bib3hGaWxlIGV4dGVuZHMgcHJlbG9hZF9maWxlLlByZWxvYWRGaWxlPERyb3Bib3hGaWxlU3lzdGVtPiBpbXBsZW1lbnRzIGZpbGUuRmlsZSB7XHJcbiAgY29uc3RydWN0b3IoX2ZzOiBEcm9wYm94RmlsZVN5c3RlbSwgX3BhdGg6IHN0cmluZywgX2ZsYWc6IGZpbGVfZmxhZy5GaWxlRmxhZywgX3N0YXQ6IFN0YXRzLCBjb250ZW50cz86IE5vZGVCdWZmZXIpIHtcclxuICAgIHN1cGVyKF9mcywgX3BhdGgsIF9mbGFnLCBfc3RhdCwgY29udGVudHMpXHJcbiAgfVxyXG5cclxuICBwdWJsaWMgc3luYyhjYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgaWYgKHRoaXMuaXNEaXJ0eSgpKSB7XHJcbiAgICAgIHZhciBidWZmZXIgPSB0aGlzLmdldEJ1ZmZlcigpLFxyXG4gICAgICAgIGFycmF5QnVmZmVyID0gYnVmZmVyMkFycmF5QnVmZmVyKGJ1ZmZlcik7XHJcbiAgICAgIHRoaXMuX2ZzLl93cml0ZUZpbGVTdHJpY3QodGhpcy5nZXRQYXRoKCksIGFycmF5QnVmZmVyLCAoZT86IEFwaUVycm9yKSA9PiB7XHJcbiAgICAgICAgaWYgKCFlKSB7XHJcbiAgICAgICAgICB0aGlzLnJlc2V0RGlydHkoKTtcclxuICAgICAgICB9XHJcbiAgICAgICAgY2IoZSk7XHJcbiAgICAgIH0pO1xyXG4gICAgfSBlbHNlIHtcclxuICAgICAgY2IoKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIHB1YmxpYyBjbG9zZShjYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgdGhpcy5zeW5jKGNiKTtcclxuICB9XHJcbn1cclxuXHJcbmV4cG9ydCBkZWZhdWx0IGNsYXNzIERyb3Bib3hGaWxlU3lzdGVtIGV4dGVuZHMgZmlsZV9zeXN0ZW0uQmFzZUZpbGVTeXN0ZW0gaW1wbGVtZW50cyBmaWxlX3N5c3RlbS5GaWxlU3lzdGVtIHtcclxuICAvLyBUaGUgRHJvcGJveCBjbGllbnQuXHJcbiAgcHJpdmF0ZSBfY2xpZW50OiBDYWNoZWREcm9wYm94Q2xpZW50O1xyXG5cclxuICAvKipcclxuICAgKiBBcmd1bWVudHM6IGFuIGF1dGhlbnRpY2F0ZWQgRHJvcGJveC5qcyBjbGllbnRcclxuICAgKi9cclxuICBjb25zdHJ1Y3RvcihjbGllbnQ6IERyb3Bib3guQ2xpZW50KSB7XHJcbiAgICBzdXBlcigpO1xyXG4gICAgdGhpcy5fY2xpZW50ID0gbmV3IENhY2hlZERyb3Bib3hDbGllbnQoY2xpZW50KTtcclxuICAgIGNvbnN0cnVjdEVycm9yQ29kZUxvb2t1cCgpO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIGdldE5hbWUoKTogc3RyaW5nIHtcclxuICAgIHJldHVybiAnRHJvcGJveCc7XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgc3RhdGljIGlzQXZhaWxhYmxlKCk6IGJvb2xlYW4ge1xyXG4gICAgLy8gQ2hlY2tzIGlmIHRoZSBEcm9wYm94IGxpYnJhcnkgaXMgbG9hZGVkLlxyXG4gICAgcmV0dXJuIHR5cGVvZiBEcm9wYm94ICE9PSAndW5kZWZpbmVkJztcclxuICB9XHJcblxyXG4gIHB1YmxpYyBpc1JlYWRPbmx5KCk6IGJvb2xlYW4ge1xyXG4gICAgcmV0dXJuIGZhbHNlO1xyXG4gIH1cclxuXHJcbiAgLy8gRHJvcGJveCBkb2Vzbid0IHN1cHBvcnQgc3ltbGlua3MsIHByb3BlcnRpZXMsIG9yIHN5bmNocm9ub3VzIGNhbGxzXHJcblxyXG4gIHB1YmxpYyBzdXBwb3J0c1N5bWxpbmtzKCk6IGJvb2xlYW4ge1xyXG4gICAgcmV0dXJuIGZhbHNlO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIHN1cHBvcnRzUHJvcHMoKTogYm9vbGVhbiB7XHJcbiAgICByZXR1cm4gZmFsc2U7XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgc3VwcG9ydHNTeW5jaCgpOiBib29sZWFuIHtcclxuICAgIHJldHVybiBmYWxzZTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBlbXB0eShtYWluQ2I6IChlPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHRoaXMuX2NsaWVudC5yZWFkZGlyKCcvJywgKGVycm9yLCBmaWxlcykgPT4ge1xyXG4gICAgICBpZiAoZXJyb3IpIHtcclxuICAgICAgICBtYWluQ2IodGhpcy5jb252ZXJ0KGVycm9yLCAnLycpKTtcclxuICAgICAgfSBlbHNlIHtcclxuICAgICAgICB2YXIgZGVsZXRlRmlsZSA9IChmaWxlOiBzdHJpbmcsIGNiOiAoZXJyPzogQXBpRXJyb3IpID0+IHZvaWQpID0+IHtcclxuICAgICAgICAgIHZhciBwID0gcGF0aC5qb2luKCcvJywgZmlsZSk7XHJcbiAgICAgICAgICB0aGlzLl9jbGllbnQucmVtb3ZlKHAsIChlcnIpID0+IHtcclxuICAgICAgICAgICAgY2IoZXJyID8gdGhpcy5jb252ZXJ0KGVyciwgcCkgOiBudWxsKTtcclxuICAgICAgICAgIH0pO1xyXG4gICAgICAgIH07XHJcbiAgICAgICAgdmFyIGZpbmlzaGVkID0gKGVycj86IEFwaUVycm9yKSA9PiB7XHJcbiAgICAgICAgICBpZiAoZXJyKSB7XHJcbiAgICAgICAgICAgIG1haW5DYihlcnIpO1xyXG4gICAgICAgICAgfSBlbHNlIHtcclxuICAgICAgICAgICAgbWFpbkNiKCk7XHJcbiAgICAgICAgICB9XHJcbiAgICAgICAgfTtcclxuICAgICAgICAvLyBYWFg6IDxhbnk+IHR5cGluZyBpcyB0byBnZXQgYXJvdW5kIG92ZXJseS1yZXN0cmljdGl2ZSBFcnJvckNhbGxiYWNrIHR5cGluZy5cclxuICAgICAgICBhc3luYy5lYWNoKGZpbGVzLCA8YW55PiBkZWxldGVGaWxlLCA8YW55PiBmaW5pc2hlZCk7XHJcbiAgICAgIH1cclxuICAgIH0pO1xyXG4gIH1cclxuXHJcbiBwdWJsaWMgcmVuYW1lKG9sZFBhdGg6IHN0cmluZywgbmV3UGF0aDogc3RyaW5nLCBjYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgdGhpcy5fY2xpZW50Lm1vdmUob2xkUGF0aCwgbmV3UGF0aCwgKGVycm9yKSA9PiB7XHJcbiAgICAgIGlmIChlcnJvcikge1xyXG4gICAgICAgIC8vIHRoZSBtb3ZlIGlzIHBlcm1pdHRlZCBpZiBuZXdQYXRoIGlzIGEgZmlsZS5cclxuICAgICAgICAvLyBDaGVjayBpZiB0aGlzIGlzIHRoZSBjYXNlLCBhbmQgcmVtb3ZlIGlmIHNvLlxyXG4gICAgICAgIHRoaXMuX2NsaWVudC5zdGF0KG5ld1BhdGgsIChlcnJvcjIsIHN0YXQpID0+IHtcclxuICAgICAgICAgIGlmIChlcnJvcjIgfHwgc3RhdC5pc0ZvbGRlcikge1xyXG4gICAgICAgICAgICB2YXIgbWlzc2luZ1BhdGggPSAoPGFueT4gZXJyb3IucmVzcG9uc2UpLmVycm9yLmluZGV4T2Yob2xkUGF0aCkgPiAtMSA/IG9sZFBhdGggOiBuZXdQYXRoO1xyXG4gICAgICAgICAgICBjYih0aGlzLmNvbnZlcnQoZXJyb3IsIG1pc3NpbmdQYXRoKSk7XHJcbiAgICAgICAgICB9IGVsc2Uge1xyXG4gICAgICAgICAgICAvLyBEZWxldGUgZmlsZSwgcmVwZWF0IHJlbmFtZS5cclxuICAgICAgICAgICAgdGhpcy5fY2xpZW50LnJlbW92ZShuZXdQYXRoLCAoZXJyb3IyKSA9PiB7XHJcbiAgICAgICAgICAgICAgaWYgKGVycm9yMikge1xyXG4gICAgICAgICAgICAgICAgY2IodGhpcy5jb252ZXJ0KGVycm9yMiwgbmV3UGF0aCkpO1xyXG4gICAgICAgICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICAgICAgICB0aGlzLnJlbmFtZShvbGRQYXRoLCBuZXdQYXRoLCBjYik7XHJcbiAgICAgICAgICAgICAgfVxyXG4gICAgICAgICAgICB9KTtcclxuICAgICAgICAgIH1cclxuICAgICAgICB9KTtcclxuICAgICAgfSBlbHNlIHtcclxuICAgICAgICBjYigpO1xyXG4gICAgICB9XHJcbiAgICB9KTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBzdGF0KHBhdGg6IHN0cmluZywgaXNMc3RhdDogYm9vbGVhbiwgY2I6IChlcnI6IEFwaUVycm9yLCBzdGF0PzogU3RhdHMpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIC8vIElnbm9yZSBsc3RhdCBjYXNlIC0tIERyb3Bib3ggZG9lc24ndCBzdXBwb3J0IHN5bWxpbmtzXHJcbiAgICAvLyBTdGF0IHRoZSBmaWxlXHJcbiAgICB0aGlzLl9jbGllbnQuc3RhdChwYXRoLCAoZXJyb3IsIHN0YXQpID0+IHtcclxuICAgICAgaWYgKGVycm9yKSB7XHJcbiAgICAgICAgY2IodGhpcy5jb252ZXJ0KGVycm9yLCBwYXRoKSk7XHJcbiAgICAgIH0gZWxzZSBpZiAoKHN0YXQgIT0gbnVsbCkgJiYgc3RhdC5pc1JlbW92ZWQpIHtcclxuICAgICAgICAvLyBEcm9wYm94IGtlZXBzIHRyYWNrIG9mIGRlbGV0ZWQgZmlsZXMsIHNvIGlmIGEgZmlsZSBoYXMgZXhpc3RlZCBpbiB0aGVcclxuICAgICAgICAvLyBwYXN0IGJ1dCBkb2Vzbid0IGFueSBsb25nZXIsIHlvdSB3b250IGdldCBhbiBlcnJvclxyXG4gICAgICAgIGNiKEFwaUVycm9yLkZpbGVFcnJvcihFcnJvckNvZGUuRU5PRU5ULCBwYXRoKSk7XHJcbiAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgdmFyIHN0YXRzID0gbmV3IFN0YXRzKHRoaXMuX3N0YXRUeXBlKHN0YXQpLCBzdGF0LnNpemUpO1xyXG4gICAgICAgIHJldHVybiBjYihudWxsLCBzdGF0cyk7XHJcbiAgICAgIH1cclxuICAgIH0pO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIG9wZW4ocGF0aDogc3RyaW5nLCBmbGFnczogZmlsZV9mbGFnLkZpbGVGbGFnLCBtb2RlOiBudW1iZXIsIGNiOiAoZXJyOiBBcGlFcnJvciwgZmQ/OiBmaWxlLkZpbGUpID0+IGFueSk6IHZvaWQge1xyXG4gICAgLy8gVHJ5IGFuZCBnZXQgdGhlIGZpbGUncyBjb250ZW50c1xyXG4gICAgdGhpcy5fY2xpZW50LnJlYWRGaWxlKHBhdGgsIChlcnJvciwgY29udGVudCwgZGJTdGF0KSA9PiB7XHJcbiAgICAgIGlmIChlcnJvcikge1xyXG4gICAgICAgIC8vIElmIHRoZSBmaWxlJ3MgYmVpbmcgb3BlbmVkIGZvciByZWFkaW5nIGFuZCBkb2Vzbid0IGV4aXN0LCByZXR1cm4gYW5cclxuICAgICAgICAvLyBlcnJvclxyXG4gICAgICAgIGlmIChmbGFncy5pc1JlYWRhYmxlKCkpIHtcclxuICAgICAgICAgIGNiKHRoaXMuY29udmVydChlcnJvciwgcGF0aCkpO1xyXG4gICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICBzd2l0Y2ggKGVycm9yLnN0YXR1cykge1xyXG4gICAgICAgICAgICAvLyBJZiBpdCdzIGJlaW5nIG9wZW5lZCBmb3Igd3JpdGluZyBvciBhcHBlbmRpbmcsIGNyZWF0ZSBpdCBzbyB0aGF0XHJcbiAgICAgICAgICAgIC8vIGl0IGNhbiBiZSB3cml0dGVuIHRvXHJcbiAgICAgICAgICAgIGNhc2UgRHJvcGJveC5BcGlFcnJvci5OT1RfRk9VTkQ6XHJcbiAgICAgICAgICAgICAgdmFyIGFiID0gbmV3IEFycmF5QnVmZmVyKDApO1xyXG4gICAgICAgICAgICAgIHJldHVybiB0aGlzLl93cml0ZUZpbGVTdHJpY3QocGF0aCwgYWIsIChlcnJvcjI6IEFwaUVycm9yLCBzdGF0PzogRHJvcGJveC5GaWxlLlN0YXQpID0+IHtcclxuICAgICAgICAgICAgICAgIGlmIChlcnJvcjIpIHtcclxuICAgICAgICAgICAgICAgICAgY2IoZXJyb3IyKTtcclxuICAgICAgICAgICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICAgICAgICAgIHZhciBmaWxlID0gdGhpcy5fbWFrZUZpbGUocGF0aCwgZmxhZ3MsIHN0YXQsIGFycmF5QnVmZmVyMkJ1ZmZlcihhYikpO1xyXG4gICAgICAgICAgICAgICAgICBjYihudWxsLCBmaWxlKTtcclxuICAgICAgICAgICAgICAgIH1cclxuICAgICAgICAgICAgICB9KTtcclxuICAgICAgICAgICAgZGVmYXVsdDpcclxuICAgICAgICAgICAgICByZXR1cm4gY2IodGhpcy5jb252ZXJ0KGVycm9yLCBwYXRoKSk7XHJcbiAgICAgICAgICB9XHJcbiAgICAgICAgfVxyXG4gICAgICB9IGVsc2Uge1xyXG4gICAgICAgIC8vIE5vIGVycm9yXHJcbiAgICAgICAgdmFyIGJ1ZmZlcjogQnVmZmVyO1xyXG4gICAgICAgIC8vIERyb3Bib3guanMgc2VlbXMgdG8gc2V0IGBjb250ZW50YCB0byBgbnVsbGAgcmF0aGVyIHRoYW4gdG8gYW4gZW1wdHlcclxuICAgICAgICAvLyBidWZmZXIgd2hlbiByZWFkaW5nIGFuIGVtcHR5IGZpbGUuIE5vdCBzdXJlIHdoeSB0aGlzIGlzLlxyXG4gICAgICAgIGlmIChjb250ZW50ID09PSBudWxsKSB7XHJcbiAgICAgICAgICBidWZmZXIgPSBuZXcgQnVmZmVyKDApO1xyXG4gICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICBidWZmZXIgPSBhcnJheUJ1ZmZlcjJCdWZmZXIoY29udGVudCk7XHJcbiAgICAgICAgfVxyXG4gICAgICAgIHZhciBmaWxlID0gdGhpcy5fbWFrZUZpbGUocGF0aCwgZmxhZ3MsIGRiU3RhdCwgYnVmZmVyKTtcclxuICAgICAgICByZXR1cm4gY2IobnVsbCwgZmlsZSk7XHJcbiAgICAgIH1cclxuICAgIH0pO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIF93cml0ZUZpbGVTdHJpY3QocDogc3RyaW5nLCBkYXRhOiBBcnJheUJ1ZmZlciwgY2I6IChlOiBBcGlFcnJvciwgc3RhdD86IERyb3Bib3guRmlsZS5TdGF0KSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICB2YXIgcGFyZW50ID0gcGF0aC5kaXJuYW1lKHApO1xyXG4gICAgdGhpcy5zdGF0KHBhcmVudCwgZmFsc2UsIChlcnJvcjogQXBpRXJyb3IsIHN0YXQ/OiBTdGF0cyk6IHZvaWQgPT4ge1xyXG4gICAgICBpZiAoZXJyb3IpIHtcclxuICAgICAgICBjYihBcGlFcnJvci5GaWxlRXJyb3IoRXJyb3JDb2RlLkVOT0VOVCwgcGFyZW50KSk7XHJcbiAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgdGhpcy5fY2xpZW50LndyaXRlRmlsZShwLCBkYXRhLCAoZXJyb3IyLCBzdGF0KSA9PiB7XHJcbiAgICAgICAgICBpZiAoZXJyb3IyKSB7XHJcbiAgICAgICAgICAgIGNiKHRoaXMuY29udmVydChlcnJvcjIsIHApKTtcclxuICAgICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICAgIGNiKG51bGwsIHN0YXQpO1xyXG4gICAgICAgICAgfVxyXG4gICAgICAgIH0pO1xyXG4gICAgICB9XHJcbiAgICB9KTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIFByaXZhdGVcclxuICAgKiBSZXR1cm5zIGEgQnJvd3NlckZTIG9iamVjdCByZXByZXNlbnRpbmcgdGhlIHR5cGUgb2YgYSBEcm9wYm94LmpzIHN0YXQgb2JqZWN0XHJcbiAgICovXHJcbiAgcHVibGljIF9zdGF0VHlwZShzdGF0OiBEcm9wYm94LkZpbGUuU3RhdCk6IEZpbGVUeXBlIHtcclxuICAgIHJldHVybiBzdGF0LmlzRmlsZSA/IEZpbGVUeXBlLkZJTEUgOiBGaWxlVHlwZS5ESVJFQ1RPUlk7XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBQcml2YXRlXHJcbiAgICogUmV0dXJucyBhIEJyb3dzZXJGUyBvYmplY3QgcmVwcmVzZW50aW5nIGEgRmlsZSwgY3JlYXRlZCBmcm9tIHRoZSBkYXRhXHJcbiAgICogcmV0dXJuZWQgYnkgY2FsbHMgdG8gdGhlIERyb3Bib3ggQVBJLlxyXG4gICAqL1xyXG4gIHB1YmxpYyBfbWFrZUZpbGUocGF0aDogc3RyaW5nLCBmbGFnOiBmaWxlX2ZsYWcuRmlsZUZsYWcsIHN0YXQ6IERyb3Bib3guRmlsZS5TdGF0LCBidWZmZXI6IE5vZGVCdWZmZXIpOiBEcm9wYm94RmlsZSB7XHJcbiAgICB2YXIgdHlwZSA9IHRoaXMuX3N0YXRUeXBlKHN0YXQpO1xyXG4gICAgdmFyIHN0YXRzID0gbmV3IFN0YXRzKHR5cGUsIHN0YXQuc2l6ZSk7XHJcbiAgICByZXR1cm4gbmV3IERyb3Bib3hGaWxlKHRoaXMsIHBhdGgsIGZsYWcsIHN0YXRzLCBidWZmZXIpO1xyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogUHJpdmF0ZVxyXG4gICAqIERlbGV0ZSBhIGZpbGUgb3IgZGlyZWN0b3J5IGZyb20gRHJvcGJveFxyXG4gICAqIGlzRmlsZSBzaG91bGQgcmVmbGVjdCB3aGljaCBjYWxsIHdhcyBtYWRlIHRvIHJlbW92ZSB0aGUgaXQgKGB1bmxpbmtgIG9yXHJcbiAgICogYHJtZGlyYCkuIElmIHRoaXMgZG9lc24ndCBtYXRjaCB3aGF0J3MgYWN0dWFsbHkgYXQgYHBhdGhgLCBhbiBlcnJvciB3aWxsIGJlXHJcbiAgICogcmV0dXJuZWRcclxuICAgKi9cclxuICBwdWJsaWMgX3JlbW92ZShwYXRoOiBzdHJpbmcsIGNiOiAoZT86IEFwaUVycm9yKSA9PiB2b2lkLCBpc0ZpbGU6IGJvb2xlYW4pOiB2b2lkIHtcclxuICAgIHRoaXMuX2NsaWVudC5zdGF0KHBhdGgsIChlcnJvciwgc3RhdCkgPT4ge1xyXG4gICAgICBpZiAoZXJyb3IpIHtcclxuICAgICAgICBjYih0aGlzLmNvbnZlcnQoZXJyb3IsIHBhdGgpKTtcclxuICAgICAgfSBlbHNlIHtcclxuICAgICAgICBpZiAoc3RhdC5pc0ZpbGUgJiYgIWlzRmlsZSkge1xyXG4gICAgICAgICAgY2IoQXBpRXJyb3IuRmlsZUVycm9yKEVycm9yQ29kZS5FTk9URElSLCBwYXRoKSk7XHJcbiAgICAgICAgfSBlbHNlIGlmICghc3RhdC5pc0ZpbGUgJiYgaXNGaWxlKSB7XHJcbiAgICAgICAgICBjYihBcGlFcnJvci5GaWxlRXJyb3IoRXJyb3JDb2RlLkVJU0RJUiwgcGF0aCkpO1xyXG4gICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICB0aGlzLl9jbGllbnQucmVtb3ZlKHBhdGgsIChlcnJvcikgPT4ge1xyXG4gICAgICAgICAgICBpZiAoZXJyb3IpIHtcclxuICAgICAgICAgICAgICBjYih0aGlzLmNvbnZlcnQoZXJyb3IsIHBhdGgpKTtcclxuICAgICAgICAgICAgfSBlbHNlIHtcclxuICAgICAgICAgICAgICBjYihudWxsKTtcclxuICAgICAgICAgICAgfVxyXG4gICAgICAgICAgfSk7XHJcbiAgICAgICAgfVxyXG4gICAgICB9XHJcbiAgICB9KTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIERlbGV0ZSBhIGZpbGVcclxuICAgKi9cclxuICBwdWJsaWMgdW5saW5rKHBhdGg6IHN0cmluZywgY2I6IChlPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHRoaXMuX3JlbW92ZShwYXRoLCBjYiwgdHJ1ZSk7XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBEZWxldGUgYSBkaXJlY3RvcnlcclxuICAgKi9cclxuICBwdWJsaWMgcm1kaXIocGF0aDogc3RyaW5nLCBjYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgdGhpcy5fcmVtb3ZlKHBhdGgsIGNiLCBmYWxzZSk7XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBDcmVhdGUgYSBkaXJlY3RvcnlcclxuICAgKi9cclxuICBwdWJsaWMgbWtkaXIocDogc3RyaW5nLCBtb2RlOiBudW1iZXIsIGNiOiAoZT86IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICAvLyBEcm9wYm94LmpzJyBjbGllbnQubWtkaXIoKSBiZWhhdmVzIGxpa2UgYG1rZGlyIC1wYCwgaS5lLiBpdCBjcmVhdGVzIGFcclxuICAgIC8vIGRpcmVjdG9yeSBhbmQgYWxsIGl0cyBhbmNlc3RvcnMgaWYgdGhleSBkb24ndCBleGlzdC5cclxuICAgIC8vIE5vZGUncyBmcy5ta2RpcigpIGJlaGF2ZXMgbGlrZSBgbWtkaXJgLCBpLmUuIGl0IHRocm93cyBhbiBlcnJvciBpZiBhbiBhdHRlbXB0XHJcbiAgICAvLyBpcyBtYWRlIHRvIGNyZWF0ZSBhIGRpcmVjdG9yeSB3aXRob3V0IGEgcGFyZW50LlxyXG4gICAgLy8gVG8gaGFuZGxlIHRoaXMgaW5jb25zaXN0ZW5jeSwgYSBjaGVjayBmb3IgdGhlIGV4aXN0ZW5jZSBvZiBgcGF0aGAncyBwYXJlbnRcclxuICAgIC8vIG11c3QgYmUgcGVyZm9ybWVkIGJlZm9yZSBpdCBpcyBjcmVhdGVkLCBhbmQgYW4gZXJyb3IgdGhyb3duIGlmIGl0IGRvZXNcclxuICAgIC8vIG5vdCBleGlzdFxyXG4gICAgdmFyIHBhcmVudCA9IHBhdGguZGlybmFtZShwKTtcclxuICAgIHRoaXMuX2NsaWVudC5zdGF0KHBhcmVudCwgKGVycm9yLCBzdGF0KSA9PiB7XHJcbiAgICAgIGlmIChlcnJvcikge1xyXG4gICAgICAgIGNiKHRoaXMuY29udmVydChlcnJvciwgcGFyZW50KSk7XHJcbiAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgdGhpcy5fY2xpZW50Lm1rZGlyKHAsIChlcnJvcikgPT4ge1xyXG4gICAgICAgICAgaWYgKGVycm9yKSB7XHJcbiAgICAgICAgICAgIGNiKEFwaUVycm9yLkZpbGVFcnJvcihFcnJvckNvZGUuRUVYSVNULCBwKSk7XHJcbiAgICAgICAgICB9IGVsc2Uge1xyXG4gICAgICAgICAgICBjYihudWxsKTtcclxuICAgICAgICAgIH1cclxuICAgICAgICB9KTtcclxuICAgICAgfVxyXG4gICAgfSk7XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBHZXQgdGhlIG5hbWVzIG9mIHRoZSBmaWxlcyBpbiBhIGRpcmVjdG9yeVxyXG4gICAqL1xyXG4gIHB1YmxpYyByZWFkZGlyKHBhdGg6IHN0cmluZywgY2I6IChlcnI6IEFwaUVycm9yLCBmaWxlcz86IHN0cmluZ1tdKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICB0aGlzLl9jbGllbnQucmVhZGRpcihwYXRoLCAoZXJyb3IsIGZpbGVzKSA9PiB7XHJcbiAgICAgIGlmIChlcnJvcikge1xyXG4gICAgICAgIHJldHVybiBjYih0aGlzLmNvbnZlcnQoZXJyb3IpKTtcclxuICAgICAgfSBlbHNlIHtcclxuICAgICAgICByZXR1cm4gY2IobnVsbCwgZmlsZXMpO1xyXG4gICAgICB9XHJcbiAgICB9KTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIENvbnZlcnRzIGEgRHJvcGJveC1KUyBlcnJvciBpbnRvIGEgQkZTIGVycm9yLlxyXG4gICAqL1xyXG4gIHB1YmxpYyBjb252ZXJ0KGVycjogRHJvcGJveC5BcGlFcnJvciwgcGF0aDogc3RyaW5nID0gbnVsbCk6IEFwaUVycm9yIHtcclxuICAgIHZhciBlcnJvckNvZGUgPSBlcnJvckNvZGVMb29rdXBbZXJyLnN0YXR1c107XHJcbiAgICBpZiAoZXJyb3JDb2RlID09PSB1bmRlZmluZWQpIHtcclxuICAgICAgZXJyb3JDb2RlID0gRXJyb3JDb2RlLkVJTztcclxuICAgIH1cclxuXHJcbiAgICBpZiAocGF0aCA9PSBudWxsKSB7XHJcbiAgICAgIHJldHVybiBuZXcgQXBpRXJyb3IoZXJyb3JDb2RlKTtcclxuICAgIH0gZWxzZSB7XHJcbiAgICAgIHJldHVybiBBcGlFcnJvci5GaWxlRXJyb3IoZXJyb3JDb2RlLCBwYXRoKTtcclxuICAgIH1cclxuICB9XHJcbn1cclxuIl19