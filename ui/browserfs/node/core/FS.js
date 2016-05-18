"use strict";
var api_error_1 = require('./api_error');
var file_flag_1 = require('./file_flag');
var path = require('path');
var node_fs_stats_1 = require('./node_fs_stats');
function wrapCb(cb, numArgs) {
    if (typeof cb !== 'function') {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, 'Callback must be a function.');
    }
    if (typeof __numWaiting === 'undefined') {
        __numWaiting = 0;
    }
    __numWaiting++;
    switch (numArgs) {
        case 1:
            return function (arg1) {
                setImmediate(function () {
                    __numWaiting--;
                    return cb(arg1);
                });
            };
        case 2:
            return function (arg1, arg2) {
                setImmediate(function () {
                    __numWaiting--;
                    return cb(arg1, arg2);
                });
            };
        case 3:
            return function (arg1, arg2, arg3) {
                setImmediate(function () {
                    __numWaiting--;
                    return cb(arg1, arg2, arg3);
                });
            };
        default:
            throw new Error('Invalid invocation of wrapCb.');
    }
}
function normalizeMode(mode, def) {
    switch (typeof mode) {
        case 'number':
            return mode;
        case 'string':
            var trueMode = parseInt(mode, 8);
            if (trueMode !== NaN) {
                return trueMode;
            }
        default:
            return def;
    }
}
function normalizeTime(time) {
    if (time instanceof Date) {
        return time;
    }
    else if (typeof time === 'number') {
        return new Date(time * 1000);
    }
    else {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "Invalid time.");
    }
}
function normalizePath(p) {
    if (p.indexOf('\u0000') >= 0) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, 'Path must be a string without null bytes.');
    }
    else if (p === '') {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, 'Path must not be empty.');
    }
    return path.resolve(p);
}
function normalizeOptions(options, defEnc, defFlag, defMode) {
    switch (typeof options) {
        case 'object':
            return {
                encoding: typeof options['encoding'] !== 'undefined' ? options['encoding'] : defEnc,
                flag: typeof options['flag'] !== 'undefined' ? options['flag'] : defFlag,
                mode: normalizeMode(options['mode'], defMode)
            };
        case 'string':
            return {
                encoding: options,
                flag: defFlag,
                mode: defMode
            };
        default:
            return {
                encoding: defEnc,
                flag: defFlag,
                mode: defMode
            };
    }
}
function nopCb() { }
;
var FS = (function () {
    function FS() {
        this.root = null;
        this.fdMap = {};
        this.nextFd = 100;
        this.F_OK = 0;
        this.R_OK = 4;
        this.W_OK = 2;
        this.X_OK = 1;
        this._wrapCb = wrapCb;
    }
    FS.prototype.getFdForFile = function (file) {
        var fd = this.nextFd++;
        this.fdMap[fd] = file;
        return fd;
    };
    FS.prototype.fd2file = function (fd) {
        var rv = this.fdMap[fd];
        if (rv) {
            return rv;
        }
        else {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EBADF, 'Invalid file descriptor.');
        }
    };
    FS.prototype.closeFd = function (fd) {
        delete this.fdMap[fd];
    };
    FS.prototype.initialize = function (rootFS) {
        if (!rootFS.constructor.isAvailable()) {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, 'Tried to instantiate BrowserFS with an unavailable file system.');
        }
        return this.root = rootFS;
    };
    FS.prototype._toUnixTimestamp = function (time) {
        if (typeof time === 'number') {
            return time;
        }
        else if (time instanceof Date) {
            return time.getTime() / 1000;
        }
        throw new Error("Cannot parse time: " + time);
    };
    FS.prototype.getRootFS = function () {
        if (this.root) {
            return this.root;
        }
        else {
            return null;
        }
    };
    FS.prototype.rename = function (oldPath, newPath, cb) {
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 1);
        try {
            this.root.rename(normalizePath(oldPath), normalizePath(newPath), newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.renameSync = function (oldPath, newPath) {
        this.root.renameSync(normalizePath(oldPath), normalizePath(newPath));
    };
    FS.prototype.exists = function (path, cb) {
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 1);
        try {
            return this.root.exists(normalizePath(path), newCb);
        }
        catch (e) {
            return newCb(false);
        }
    };
    FS.prototype.existsSync = function (path) {
        try {
            return this.root.existsSync(normalizePath(path));
        }
        catch (e) {
            return false;
        }
    };
    FS.prototype.stat = function (path, cb) {
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 2);
        try {
            return this.root.stat(normalizePath(path), false, newCb);
        }
        catch (e) {
            return newCb(e, null);
        }
    };
    FS.prototype.statSync = function (path) {
        return this.root.statSync(normalizePath(path), false);
    };
    FS.prototype.lstat = function (path, cb) {
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 2);
        try {
            return this.root.stat(normalizePath(path), true, newCb);
        }
        catch (e) {
            return newCb(e, null);
        }
    };
    FS.prototype.lstatSync = function (path) {
        return this.root.statSync(normalizePath(path), true);
    };
    FS.prototype.truncate = function (path, arg2, cb) {
        if (arg2 === void 0) { arg2 = 0; }
        if (cb === void 0) { cb = nopCb; }
        var len = 0;
        if (typeof arg2 === 'function') {
            cb = arg2;
        }
        else if (typeof arg2 === 'number') {
            len = arg2;
        }
        var newCb = wrapCb(cb, 1);
        try {
            if (len < 0) {
                throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL);
            }
            return this.root.truncate(normalizePath(path), len, newCb);
        }
        catch (e) {
            return newCb(e);
        }
    };
    FS.prototype.truncateSync = function (path, len) {
        if (len === void 0) { len = 0; }
        if (len < 0) {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL);
        }
        return this.root.truncateSync(normalizePath(path), len);
    };
    FS.prototype.unlink = function (path, cb) {
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 1);
        try {
            return this.root.unlink(normalizePath(path), newCb);
        }
        catch (e) {
            return newCb(e);
        }
    };
    FS.prototype.unlinkSync = function (path) {
        return this.root.unlinkSync(normalizePath(path));
    };
    FS.prototype.open = function (path, flag, arg2, cb) {
        var _this = this;
        if (cb === void 0) { cb = nopCb; }
        var mode = normalizeMode(arg2, 0x1a4);
        cb = typeof arg2 === 'function' ? arg2 : cb;
        var newCb = wrapCb(cb, 2);
        try {
            this.root.open(normalizePath(path), file_flag_1.FileFlag.getFileFlag(flag), mode, function (e, file) {
                if (file) {
                    newCb(e, _this.getFdForFile(file));
                }
                else {
                    newCb(e);
                }
            });
        }
        catch (e) {
            newCb(e, null);
        }
    };
    FS.prototype.openSync = function (path, flag, mode) {
        if (mode === void 0) { mode = 0x1a4; }
        return this.getFdForFile(this.root.openSync(normalizePath(path), file_flag_1.FileFlag.getFileFlag(flag), normalizeMode(mode, 0x1a4)));
    };
    FS.prototype.readFile = function (filename, arg2, cb) {
        if (arg2 === void 0) { arg2 = {}; }
        if (cb === void 0) { cb = nopCb; }
        var options = normalizeOptions(arg2, null, 'r', null);
        cb = typeof arg2 === 'function' ? arg2 : cb;
        var newCb = wrapCb(cb, 2);
        try {
            var flag = file_flag_1.FileFlag.getFileFlag(options['flag']);
            if (!flag.isReadable()) {
                return newCb(new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, 'Flag passed to readFile must allow for reading.'));
            }
            return this.root.readFile(normalizePath(filename), options.encoding, flag, newCb);
        }
        catch (e) {
            return newCb(e, null);
        }
    };
    FS.prototype.readFileSync = function (filename, arg2) {
        if (arg2 === void 0) { arg2 = {}; }
        var options = normalizeOptions(arg2, null, 'r', null);
        var flag = file_flag_1.FileFlag.getFileFlag(options.flag);
        if (!flag.isReadable()) {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, 'Flag passed to readFile must allow for reading.');
        }
        return this.root.readFileSync(normalizePath(filename), options.encoding, flag);
    };
    FS.prototype.writeFile = function (filename, data, arg3, cb) {
        if (arg3 === void 0) { arg3 = {}; }
        if (cb === void 0) { cb = nopCb; }
        var options = normalizeOptions(arg3, 'utf8', 'w', 0x1a4);
        cb = typeof arg3 === 'function' ? arg3 : cb;
        var newCb = wrapCb(cb, 1);
        try {
            var flag = file_flag_1.FileFlag.getFileFlag(options.flag);
            if (!flag.isWriteable()) {
                return newCb(new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, 'Flag passed to writeFile must allow for writing.'));
            }
            return this.root.writeFile(normalizePath(filename), data, options.encoding, flag, options.mode, newCb);
        }
        catch (e) {
            return newCb(e);
        }
    };
    FS.prototype.writeFileSync = function (filename, data, arg3) {
        var options = normalizeOptions(arg3, 'utf8', 'w', 0x1a4);
        var flag = file_flag_1.FileFlag.getFileFlag(options.flag);
        if (!flag.isWriteable()) {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, 'Flag passed to writeFile must allow for writing.');
        }
        return this.root.writeFileSync(normalizePath(filename), data, options.encoding, flag, options.mode);
    };
    FS.prototype.appendFile = function (filename, data, arg3, cb) {
        if (cb === void 0) { cb = nopCb; }
        var options = normalizeOptions(arg3, 'utf8', 'a', 0x1a4);
        cb = typeof arg3 === 'function' ? arg3 : cb;
        var newCb = wrapCb(cb, 1);
        try {
            var flag = file_flag_1.FileFlag.getFileFlag(options.flag);
            if (!flag.isAppendable()) {
                return newCb(new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, 'Flag passed to appendFile must allow for appending.'));
            }
            this.root.appendFile(normalizePath(filename), data, options.encoding, flag, options.mode, newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.appendFileSync = function (filename, data, arg3) {
        var options = normalizeOptions(arg3, 'utf8', 'a', 0x1a4);
        var flag = file_flag_1.FileFlag.getFileFlag(options.flag);
        if (!flag.isAppendable()) {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, 'Flag passed to appendFile must allow for appending.');
        }
        return this.root.appendFileSync(normalizePath(filename), data, options.encoding, flag, options.mode);
    };
    FS.prototype.fstat = function (fd, cb) {
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 2);
        try {
            var file = this.fd2file(fd);
            file.stat(newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.fstatSync = function (fd) {
        return this.fd2file(fd).statSync();
    };
    FS.prototype.close = function (fd, cb) {
        var _this = this;
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 1);
        try {
            this.fd2file(fd).close(function (e) {
                if (!e) {
                    _this.closeFd(fd);
                }
                newCb(e);
            });
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.closeSync = function (fd) {
        this.fd2file(fd).closeSync();
        this.closeFd(fd);
    };
    FS.prototype.ftruncate = function (fd, arg2, cb) {
        if (cb === void 0) { cb = nopCb; }
        var length = typeof arg2 === 'number' ? arg2 : 0;
        cb = typeof arg2 === 'function' ? arg2 : cb;
        var newCb = wrapCb(cb, 1);
        try {
            var file = this.fd2file(fd);
            if (length < 0) {
                throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL);
            }
            file.truncate(length, newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.ftruncateSync = function (fd, len) {
        if (len === void 0) { len = 0; }
        var file = this.fd2file(fd);
        if (len < 0) {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL);
        }
        file.truncateSync(len);
    };
    FS.prototype.fsync = function (fd, cb) {
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 1);
        try {
            this.fd2file(fd).sync(newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.fsyncSync = function (fd) {
        this.fd2file(fd).syncSync();
    };
    FS.prototype.fdatasync = function (fd, cb) {
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 1);
        try {
            this.fd2file(fd).datasync(newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.fdatasyncSync = function (fd) {
        this.fd2file(fd).datasyncSync();
    };
    FS.prototype.write = function (fd, arg2, arg3, arg4, arg5, cb) {
        if (cb === void 0) { cb = nopCb; }
        var buffer, offset, length, position = null;
        if (typeof arg2 === 'string') {
            var encoding = 'utf8';
            switch (typeof arg3) {
                case 'function':
                    cb = arg3;
                    break;
                case 'number':
                    position = arg3;
                    encoding = typeof arg4 === 'string' ? arg4 : 'utf8';
                    cb = typeof arg5 === 'function' ? arg5 : cb;
                    break;
                default:
                    cb = typeof arg4 === 'function' ? arg4 : typeof arg5 === 'function' ? arg5 : cb;
                    return cb(new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, 'Invalid arguments.'));
            }
            buffer = new Buffer(arg2, encoding);
            offset = 0;
            length = buffer.length;
        }
        else {
            buffer = arg2;
            offset = arg3;
            length = arg4;
            position = typeof arg5 === 'number' ? arg5 : null;
            cb = typeof arg5 === 'function' ? arg5 : cb;
        }
        var newCb = wrapCb(cb, 3);
        try {
            var file = this.fd2file(fd);
            if (position == null) {
                position = file.getPos();
            }
            file.write(buffer, offset, length, position, newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.writeSync = function (fd, arg2, arg3, arg4, arg5) {
        var buffer, offset = 0, length, position;
        if (typeof arg2 === 'string') {
            position = typeof arg3 === 'number' ? arg3 : null;
            var encoding = typeof arg4 === 'string' ? arg4 : 'utf8';
            offset = 0;
            buffer = new Buffer(arg2, encoding);
            length = buffer.length;
        }
        else {
            buffer = arg2;
            offset = arg3;
            length = arg4;
            position = typeof arg5 === 'number' ? arg5 : null;
        }
        var file = this.fd2file(fd);
        if (position == null) {
            position = file.getPos();
        }
        return file.writeSync(buffer, offset, length, position);
    };
    FS.prototype.read = function (fd, arg2, arg3, arg4, arg5, cb) {
        if (cb === void 0) { cb = nopCb; }
        var position, offset, length, buffer, newCb;
        if (typeof arg2 === 'number') {
            length = arg2;
            position = arg3;
            var encoding = arg4;
            cb = typeof arg5 === 'function' ? arg5 : cb;
            offset = 0;
            buffer = new Buffer(length);
            newCb = wrapCb((function (err, bytesRead, buf) {
                if (err) {
                    return cb(err);
                }
                cb(err, buf.toString(encoding), bytesRead);
            }), 3);
        }
        else {
            buffer = arg2;
            offset = arg3;
            length = arg4;
            position = arg5;
            newCb = wrapCb(cb, 3);
        }
        try {
            var file = this.fd2file(fd);
            if (position == null) {
                position = file.getPos();
            }
            file.read(buffer, offset, length, position, newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.readSync = function (fd, arg2, arg3, arg4, arg5) {
        var shenanigans = false;
        var buffer, offset, length, position;
        if (typeof arg2 === 'number') {
            length = arg2;
            position = arg3;
            var encoding = arg4;
            offset = 0;
            buffer = new Buffer(length);
            shenanigans = true;
        }
        else {
            buffer = arg2;
            offset = arg3;
            length = arg4;
            position = arg5;
        }
        var file = this.fd2file(fd);
        if (position == null) {
            position = file.getPos();
        }
        var rv = file.readSync(buffer, offset, length, position);
        if (!shenanigans) {
            return rv;
        }
        else {
            return [buffer.toString(encoding), rv];
        }
    };
    FS.prototype.fchown = function (fd, uid, gid, callback) {
        if (callback === void 0) { callback = nopCb; }
        var newCb = wrapCb(callback, 1);
        try {
            this.fd2file(fd).chown(uid, gid, newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.fchownSync = function (fd, uid, gid) {
        this.fd2file(fd).chownSync(uid, gid);
    };
    FS.prototype.fchmod = function (fd, mode, cb) {
        var newCb = wrapCb(cb, 1);
        try {
            var numMode = typeof mode === 'string' ? parseInt(mode, 8) : mode;
            this.fd2file(fd).chmod(numMode, newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.fchmodSync = function (fd, mode) {
        var numMode = typeof mode === 'string' ? parseInt(mode, 8) : mode;
        this.fd2file(fd).chmodSync(numMode);
    };
    FS.prototype.futimes = function (fd, atime, mtime, cb) {
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 1);
        try {
            var file = this.fd2file(fd);
            if (typeof atime === 'number') {
                atime = new Date(atime * 1000);
            }
            if (typeof mtime === 'number') {
                mtime = new Date(mtime * 1000);
            }
            file.utimes(atime, mtime, newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.futimesSync = function (fd, atime, mtime) {
        this.fd2file(fd).utimesSync(normalizeTime(atime), normalizeTime(mtime));
    };
    FS.prototype.rmdir = function (path, cb) {
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 1);
        try {
            path = normalizePath(path);
            this.root.rmdir(path, newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.rmdirSync = function (path) {
        path = normalizePath(path);
        return this.root.rmdirSync(path);
    };
    FS.prototype.mkdir = function (path, mode, cb) {
        if (cb === void 0) { cb = nopCb; }
        if (typeof mode === 'function') {
            cb = mode;
            mode = 0x1ff;
        }
        var newCb = wrapCb(cb, 1);
        try {
            path = normalizePath(path);
            this.root.mkdir(path, mode, newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.mkdirSync = function (path, mode) {
        this.root.mkdirSync(normalizePath(path), normalizeMode(mode, 0x1ff));
    };
    FS.prototype.readdir = function (path, cb) {
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 2);
        try {
            path = normalizePath(path);
            this.root.readdir(path, newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.readdirSync = function (path) {
        path = normalizePath(path);
        return this.root.readdirSync(path);
    };
    FS.prototype.link = function (srcpath, dstpath, cb) {
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 1);
        try {
            srcpath = normalizePath(srcpath);
            dstpath = normalizePath(dstpath);
            this.root.link(srcpath, dstpath, newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.linkSync = function (srcpath, dstpath) {
        srcpath = normalizePath(srcpath);
        dstpath = normalizePath(dstpath);
        return this.root.linkSync(srcpath, dstpath);
    };
    FS.prototype.symlink = function (srcpath, dstpath, arg3, cb) {
        if (cb === void 0) { cb = nopCb; }
        var type = typeof arg3 === 'string' ? arg3 : 'file';
        cb = typeof arg3 === 'function' ? arg3 : cb;
        var newCb = wrapCb(cb, 1);
        try {
            if (type !== 'file' && type !== 'dir') {
                return newCb(new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "Invalid type: " + type));
            }
            srcpath = normalizePath(srcpath);
            dstpath = normalizePath(dstpath);
            this.root.symlink(srcpath, dstpath, type, newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.symlinkSync = function (srcpath, dstpath, type) {
        if (type == null) {
            type = 'file';
        }
        else if (type !== 'file' && type !== 'dir') {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "Invalid type: " + type);
        }
        srcpath = normalizePath(srcpath);
        dstpath = normalizePath(dstpath);
        return this.root.symlinkSync(srcpath, dstpath, type);
    };
    FS.prototype.readlink = function (path, cb) {
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 2);
        try {
            path = normalizePath(path);
            this.root.readlink(path, newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.readlinkSync = function (path) {
        path = normalizePath(path);
        return this.root.readlinkSync(path);
    };
    FS.prototype.chown = function (path, uid, gid, cb) {
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 1);
        try {
            path = normalizePath(path);
            this.root.chown(path, false, uid, gid, newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.chownSync = function (path, uid, gid) {
        path = normalizePath(path);
        this.root.chownSync(path, false, uid, gid);
    };
    FS.prototype.lchown = function (path, uid, gid, cb) {
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 1);
        try {
            path = normalizePath(path);
            this.root.chown(path, true, uid, gid, newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.lchownSync = function (path, uid, gid) {
        path = normalizePath(path);
        this.root.chownSync(path, true, uid, gid);
    };
    FS.prototype.chmod = function (path, mode, cb) {
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 1);
        try {
            var numMode = normalizeMode(mode, -1);
            if (numMode < 0) {
                throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "Invalid mode.");
            }
            this.root.chmod(normalizePath(path), false, numMode, newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.chmodSync = function (path, mode) {
        var numMode = normalizeMode(mode, -1);
        if (numMode < 0) {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "Invalid mode.");
        }
        path = normalizePath(path);
        this.root.chmodSync(path, false, numMode);
    };
    FS.prototype.lchmod = function (path, mode, cb) {
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 1);
        try {
            var numMode = normalizeMode(mode, -1);
            if (numMode < 0) {
                throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "Invalid mode.");
            }
            this.root.chmod(normalizePath(path), true, numMode, newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.lchmodSync = function (path, mode) {
        var numMode = normalizeMode(mode, -1);
        if (numMode < 1) {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "Invalid mode.");
        }
        this.root.chmodSync(normalizePath(path), true, numMode);
    };
    FS.prototype.utimes = function (path, atime, mtime, cb) {
        if (cb === void 0) { cb = nopCb; }
        var newCb = wrapCb(cb, 1);
        try {
            this.root.utimes(normalizePath(path), normalizeTime(atime), normalizeTime(mtime), newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.utimesSync = function (path, atime, mtime) {
        this.root.utimesSync(normalizePath(path), normalizeTime(atime), normalizeTime(mtime));
    };
    FS.prototype.realpath = function (path, arg2, cb) {
        if (cb === void 0) { cb = nopCb; }
        var cache = typeof arg2 === 'object' ? arg2 : {};
        cb = typeof arg2 === 'function' ? arg2 : nopCb;
        var newCb = wrapCb(cb, 2);
        try {
            path = normalizePath(path);
            this.root.realpath(path, cache, newCb);
        }
        catch (e) {
            newCb(e);
        }
    };
    FS.prototype.realpathSync = function (path, cache) {
        if (cache === void 0) { cache = {}; }
        path = normalizePath(path);
        return this.root.realpathSync(path, cache);
    };
    FS.prototype.watchFile = function (filename, arg2, listener) {
        if (listener === void 0) { listener = nopCb; }
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    FS.prototype.unwatchFile = function (filename, listener) {
        if (listener === void 0) { listener = nopCb; }
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    FS.prototype.watch = function (filename, arg2, listener) {
        if (listener === void 0) { listener = nopCb; }
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    FS.prototype.access = function (path, arg2, cb) {
        if (cb === void 0) { cb = nopCb; }
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    FS.prototype.accessSync = function (path, mode) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    FS.prototype.createReadStream = function (path, options) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    FS.prototype.createWriteStream = function (path, options) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    FS.Stats = node_fs_stats_1["default"];
    return FS;
}());
exports.__esModule = true;
exports["default"] = FS;
var _ = new FS();
//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJmaWxlIjoiRlMuanMiLCJzb3VyY2VSb290IjoiIiwic291cmNlcyI6WyIuLi8uLi8uLi9zcmMvY29yZS9GUy50cyJdLCJuYW1lcyI6W10sIm1hcHBpbmdzIjoiO0FBQ0EsMEJBQWtDLGFBQWEsQ0FBQyxDQUFBO0FBRWhELDBCQUF1QixhQUFhLENBQUMsQ0FBQTtBQUNyQyxJQUFPLElBQUksV0FBVyxNQUFNLENBQUMsQ0FBQztBQUM5Qiw4QkFBa0IsaUJBQWlCLENBQUMsQ0FBQTtBQWFwQyxnQkFBb0MsRUFBSyxFQUFFLE9BQWU7SUFDeEQsRUFBRSxDQUFDLENBQUMsT0FBTyxFQUFFLEtBQUssVUFBVSxDQUFDLENBQUMsQ0FBQztRQUM3QixNQUFNLElBQUksb0JBQVEsQ0FBQyxxQkFBUyxDQUFDLE1BQU0sRUFBRSw4QkFBOEIsQ0FBQyxDQUFDO0lBQ3ZFLENBQUM7SUFHRCxFQUFFLENBQUMsQ0FBQyxPQUFPLFlBQVksS0FBSyxXQUFXLENBQUMsQ0FBQyxDQUFDO1FBQ3hDLFlBQVksR0FBRyxDQUFDLENBQUM7SUFDbkIsQ0FBQztJQUNELFlBQVksRUFBRSxDQUFDO0lBR2YsTUFBTSxDQUFDLENBQUMsT0FBTyxDQUFDLENBQUMsQ0FBQztRQUNoQixLQUFLLENBQUM7WUFDSixNQUFNLENBQU8sVUFBUyxJQUFTO2dCQUM3QixZQUFZLENBQUM7b0JBQ1gsWUFBWSxFQUFFLENBQUM7b0JBQ2YsTUFBTSxDQUFDLEVBQUUsQ0FBQyxJQUFJLENBQUMsQ0FBQztnQkFDbEIsQ0FBQyxDQUFDLENBQUM7WUFDTCxDQUFDLENBQUM7UUFDSixLQUFLLENBQUM7WUFDSixNQUFNLENBQU8sVUFBUyxJQUFTLEVBQUUsSUFBUztnQkFDeEMsWUFBWSxDQUFDO29CQUNYLFlBQVksRUFBRSxDQUFDO29CQUNmLE1BQU0sQ0FBQyxFQUFFLENBQUMsSUFBSSxFQUFFLElBQUksQ0FBQyxDQUFDO2dCQUN4QixDQUFDLENBQUMsQ0FBQztZQUNMLENBQUMsQ0FBQztRQUNKLEtBQUssQ0FBQztZQUNKLE1BQU0sQ0FB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eEMsQ0FBQztJQUlNLGtCQUFLLEdBQVosVUFBYSxRQUFnQixFQUFFLElBQVMsRUFBRSxRQUEwRDtRQUExRCx3QkFBMEQsR0FBMUQsZ0JBQTBEO1FBQ2xHLE1BQU0sSUFBSSxvQkFBUSxDQUFDLHFCQUFTLENBQUMsT0FBTyxDQUFDLENBQUM7SUFDeEMsQ0FBQztJQVNNLG1CQUFNLEdBQWIsVUFBYyxJQUFZLEVBQUUsSUFBUyxFQUFFLEVBQWlDO1FBQWpDLGtCQUFpQyxHQUFqQyxVQUFpQztRQUN0RSxNQUFNLElBQUksb0JBQVEsQ0FBQyxxQkFBUyxDQUFDLE9BQU8sQ0FBQyxDQUFDO0lBQ3hDLENBQUM7SUFFTSx1QkFBVSxHQUFqQixVQUFrQixJQUFZLEVBQUUsSUFBYTtRQUMzQyxNQUFNLElBQUksb0JBQVEsQ0FBQyxxQkFBUyxDQUFDLE9BQU8sQ0FBQyxDQUFDO0lBQ3hDLENBQUM7SUFFTSw2QkFBZ0IsR0FBdkIsVUFBd0IsSUFBWSxFQUFFLE9BTW5DO1FBQ0QsTUFBTSxJQUFJLG9CQUFRLENBQUMscUJBQVMsQ0FBQyxPQUFPLENBQUMsQ0FBQztJQUN4QyxDQUFDO0lBRU0sOEJBQWlCLEdBQXhCLFVBQXlCLElBQVksRUFBRSxPQUtwQztRQUNELE1BQU0sSUFBSSxvQkFBUSxDQUFDLHFCQUFTLENBQUMsT0FBTyxDQUFDLENBQUM7SUFDeEMsQ0FBQztJQWp5Q2EsUUFBSyxHQUFHLDBCQUFLLENBQUM7SUFveUM5QixTQUFDO0FBQUQsQ0FBQyxBQXR5Q0QsSUFzeUNDO0FBdHlDRDt1QkFzeUNDLENBQUE7QUFHRCxJQUFJLENBQUMsR0FBZSxJQUFJLEVBQUUsRUFBRSxDQUFDIiwic291cmNlc0NvbnRlbnQiOlsiaW1wb3J0IHtGaWxlfSBmcm9tICcuL2ZpbGUnO1xyXG5pbXBvcnQge0FwaUVycm9yLCBFcnJvckNvZGV9IGZyb20gJy4vYXBpX2Vycm9yJztcclxuaW1wb3J0IGZpbGVfc3lzdGVtID0gcmVxdWlyZSgnLi9maWxlX3N5c3RlbScpO1xyXG5pbXBvcnQge0ZpbGVGbGFnfSBmcm9tICcuL2ZpbGVfZmxhZyc7XHJcbmltcG9ydCBwYXRoID0gcmVxdWlyZSgncGF0aCcpO1xyXG5pbXBvcnQgU3RhdHMgZnJvbSAnLi9ub2RlX2ZzX3N0YXRzJztcclxuLy8gVHlwaW5nIGluZm8gb25seS5cclxuaW1wb3J0IF9mcyA9IHJlcXVpcmUoJ2ZzJyk7XHJcblxyXG5kZWNsYXJlIHZhciBfX251bVdhaXRpbmc6IG51bWJlcjtcclxuZGVjbGFyZSB2YXIgc2V0SW1tZWRpYXRlOiAoY2I6IEZ1bmN0aW9uKSA9PiB2b2lkO1xyXG5cclxuLyoqXHJcbiAqIFdyYXBzIGEgY2FsbGJhY2sgd2l0aCBhIHNldEltbWVkaWF0ZSBjYWxsLlxyXG4gKiBAcGFyYW0gW0Z1bmN0aW9uXSBjYiBUaGUgY2FsbGJhY2sgdG8gd3JhcC5cclxuICogQHBhcmFtIFtOdW1iZXJdIG51bUFyZ3MgVGhlIG51bWJlciBvZiBhcmd1bWVudHMgdGhhdCB0aGUgY2FsbGJhY2sgdGFrZXMuXHJcbiAqIEByZXR1cm4gW0Z1bmN0aW9uXSBUaGUgd3JhcHBlZCBjYWxsYmFjay5cclxuICovXHJcbmZ1bmN0aW9uIHdyYXBDYjxUIGV4dGVuZHMgRnVuY3Rpb24+KGNiOiBULCBudW1BcmdzOiBudW1iZXIpOiBUIHtcclxuICBpZiAodHlwZW9mIGNiICE9PSAnZnVuY3Rpb24nKSB7XHJcbiAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVJTlZBTCwgJ0NhbGxiYWNrIG11c3QgYmUgYSBmdW5jdGlvbi4nKTtcclxuICB9XHJcbiAgLy8gQHRvZG8gVGhpcyBpcyB1c2VkIGZvciB1bml0IHRlc3RpbmcuIE1heWJlIHdlIHNob3VsZCBpbmplY3QgdGhpcyBsb2dpY1xyXG4gIC8vICAgICAgIGR5bmFtaWNhbGx5IHJhdGhlciB0aGFuIGJ1bmRsZSBpdCBpbiAncHJvZHVjdGlvbicgY29kZS5cclxuICBpZiAodHlwZW9mIF9fbnVtV2FpdGluZyA9PT0gJ3VuZGVmaW5lZCcpIHtcclxuICAgIF9fbnVtV2FpdGluZyA9IDA7XHJcbiAgfVxyXG4gIF9fbnVtV2FpdGluZysrO1xyXG4gIC8vIFdlIGNvdWxkIHVzZSBgYXJndW1lbnRzYCwgYnV0IEZ1bmN0aW9uLmNhbGwvYXBwbHkgaXMgZXhwZW5zaXZlLiBBbmQgd2Ugb25seVxyXG4gIC8vIG5lZWQgdG8gaGFuZGxlIDEtMyBhcmd1bWVudHNcclxuICBzd2l0Y2ggKG51bUFyZ3MpIHtcclxuICAgIGNhc2UgMTpcclxuICAgICAgcmV0dXJuIDxhbnk+IGZ1bmN0aW9uKGFyZzE6IGFueSkge1xyXG4gICAgICAgIHNldEltbWVkaWF0ZShmdW5jdGlvbigpIHtcclxuICAgICAgICAgIF9fbnVtV2FpdGluZy0tO1xyXG4gICAgICAgICAgcmV0dXJuIGNiKGFyZzEpO1xyXG4gICAgICAgIH0pO1xyXG4gICAgICB9O1xyXG4gICAgY2FzZSAyOlxyXG4gICAgICByZXR1cm4gPGFueT4gZnVuY3Rpb24oYXJnMTogYW55LCBhcmcyOiBhbnkpIHtcclxuICAgICAgICBzZXRJbW1lZGlhdGUoZnVuY3Rpb24oKSB7XHJcbiAgICAgICAgICBfX251bVdhaXRpbmctLTtcclxuICAgICAgICAgIHJldHVybiBjYihhcmcxLCBhcmcyKTtcclxuICAgICAgICB9KTtcclxuICAgICAgfTtcclxuICAgIGNhc2UgMzpcclxuICAgICAgcmV0dXJuIDxhbnk+IGZ1bmN0aW9uKGFyZzE6IGFueSwgYXJnMjogYW55LCBhcmczOiBhbnkpIHtcclxuICAgICAgICBzZXRJbW1lZGlhdGUoZnVuY3Rpb24oKSB7XHJcbiAgICAgICAgICBfX251bVdhaXRpbmctLTtcclxuICAgICAgICAgIHJldHVybiBjYihhcmcxLCBhcmcyLCBhcmczKTtcclxuICAgICAgICB9KTtcclxuICAgICAgfTtcclxuICAgIGRlZmF1bHQ6XHJcbiAgICAgIHRocm93IG5ldyBFcnJvcignSW52YWxpZCBpbnZvY2F0aW9uIG9mIHdyYXBDYi4nKTtcclxuICB9XHJcbn1cclxuXHJcbmZ1bmN0aW9uIG5vcm1hbGl6ZU1vZGUobW9kZTogbnVtYmVyfHN0cmluZywgZGVmOiBudW1iZXIpOiBudW1iZXIge1xyXG4gIHN3aXRjaCh0eXBlb2YgbW9kZSkge1xyXG4gICAgY2FzZSAnbnVtYmVyJzpcclxuICAgICAgLy8gKHBhdGgsIGZsYWcsIG1vZGUsIGNiPylcclxuICAgICAgcmV0dXJuIDxudW1iZXI+IG1vZGU7XHJcbiAgICBjYXNlICdzdHJpbmcnOlxyXG4gICAgICAvLyAocGF0aCwgZmxhZywgbW9kZVN0cmluZywgY2I/KVxyXG4gICAgICB2YXIgdHJ1ZU1vZGUgPSBwYXJzZUludCg8c3RyaW5nPiBtb2RlLCA4KTtcclxuICAgICAgaWYgKHRydWVNb2RlICE9PSBOYU4pIHtcclxuICAgICAgICByZXR1cm4gdHJ1ZU1vZGU7XHJcbiAgICAgIH1cclxuICAgICAgLy8gRkFMTCBUSFJPVUdIIGlmIG1vZGUgaXMgYW4gaW52YWxpZCBzdHJpbmchXHJcbiAgICBkZWZhdWx0OlxyXG4gICAgICByZXR1cm4gZGVmO1xyXG4gIH1cclxufVxyXG5cclxuZnVuY3Rpb24gbm9ybWFsaXplVGltZSh0aW1lOiBudW1iZXIgfCBEYXRlKTogRGF0ZSB7XHJcbiAgaWYgKHRpbWUgaW5zdGFuY2VvZiBEYXRlKSB7XHJcbiAgICByZXR1cm4gdGltZTtcclxuICB9IGVsc2UgaWYgKHR5cGVvZiB0aW1lID09PSAnbnVtYmVyJykge1xyXG4gICAgcmV0dXJuIG5ldyBEYXRlKHRpbWUgKiAxMDAwKTtcclxuICB9IGVsc2Uge1xyXG4gICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FSU5WQUwsIGBJbnZhbGlkIHRpbWUuYCk7XHJcbiAgfVxyXG59XHJcblxyXG5mdW5jdGlvbiBub3JtYWxpemVQYXRoKHA6IHN0cmluZyk6IHN0cmluZyB7XHJcbiAgLy8gTm9kZSBkb2Vzbid0IGFsbG93IG51bGwgY2hhcmFjdGVycyBpbiBwYXRocy5cclxuICBpZiAocC5pbmRleE9mKCdcXHUwMDAwJykgPj0gMCkge1xyXG4gICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FSU5WQUwsICdQYXRoIG11c3QgYmUgYSBzdHJpbmcgd2l0aG91dCBudWxsIGJ5dGVzLicpO1xyXG4gIH0gZWxzZSBpZiAocCA9PT0gJycpIHtcclxuICAgIHRocm93IG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRUlOVkFMLCAnUGF0aCBtdXN0IG5vdCBiZSBlbXB0eS4nKTtcclxuICB9XHJcbiAgcmV0dXJuIHBhdGgucmVzb2x2ZShwKTtcclxufVxyXG5cclxuZnVuY3Rpb24gbm9ybWFsaXplT3B0aW9ucyhvcHRpb25zOiBhbnksIGRlZkVuYzogc3RyaW5nLCBkZWZGbGFnOiBzdHJpbmcsIGRlZk1vZGU6IG51bWJlcik6IHtlbmNvZGluZzogc3RyaW5nOyBmbGFnOiBzdHJpbmc7IG1vZGU6IG51bWJlcn0ge1xyXG4gIHN3aXRjaCAodHlwZW9mIG9wdGlvbnMpIHtcclxuICAgIGNhc2UgJ29iamVjdCc6XHJcbiAgICAgIHJldHVybiB7XHJcbiAgICAgICAgZW5jb2Rpbmc6IHR5cGVvZiBvcHRpb25zWydlbmNvZGluZyddICE9PSAndW5kZWZpbmVkJyA/IG9wdGlvbnNbJ2VuY29kaW5nJ10gOiBkZWZFbmMsXHJcbiAgICAgICAgZmxhZzogdHlwZW9mIG9wdGlvbnNbJ2ZsYWcnXSAhPT0gJ3VuZGVmaW5lZCcgPyBvcHRpb25zWydmbGFnJ10gOiBkZWZGbGFnLFxyXG4gICAgICAgIG1vZGU6IG5vcm1hbGl6ZU1vZGUob3B0aW9uc1snbW9kZSddLCBkZWZNb2RlKVxyXG4gICAgICB9O1xyXG4gICAgY2FzZSAnc3RyaW5nJzpcclxuICAgICAgcmV0dXJuIHtcclxuICAgICAgICBlbmNvZGluZzogb3B0aW9ucyxcclxuICAgICAgICBmbGFnOiBkZWZGbGFnLFxyXG4gICAgICAgIG1vZGU6IGRlZk1vZGVcclxuICAgICAgfTtcclxuICAgIGRlZmF1bHQ6XHJcbiAgICAgIHJldHVybiB7XHJcbiAgICAgICAgZW5jb2Rpbmc6IGRlZkVuYyxcclxuICAgICAgICBmbGFnOiBkZWZGbGFnLFxyXG4gICAgICAgIG1vZGU6IGRlZk1vZGVcclxuICAgICAgfTtcclxuICB9XHJcbn1cclxuXHJcbi8vIFRoZSBkZWZhdWx0IGNhbGxiYWNrIGlzIGEgTk9QLlxyXG5mdW5jdGlvbiBub3BDYigpIHt9O1xyXG5cclxuLyoqXHJcbiAqIFRoZSBub2RlIGZyb250ZW5kIHRvIGFsbCBmaWxlc3lzdGVtcy5cclxuICogVGhpcyBsYXllciBoYW5kbGVzOlxyXG4gKlxyXG4gKiAqIFNhbml0eSBjaGVja2luZyBpbnB1dHMuXHJcbiAqICogTm9ybWFsaXppbmcgcGF0aHMuXHJcbiAqICogUmVzZXR0aW5nIHN0YWNrIGRlcHRoIGZvciBhc3luY2hyb25vdXMgb3BlcmF0aW9ucyB3aGljaCBtYXkgbm90IGdvIHRocm91Z2hcclxuICogICB0aGUgYnJvd3NlciBieSB3cmFwcGluZyBhbGwgaW5wdXQgY2FsbGJhY2tzIHVzaW5nIGBzZXRJbW1lZGlhdGVgLlxyXG4gKiAqIFBlcmZvcm1pbmcgdGhlIHJlcXVlc3RlZCBvcGVyYXRpb24gdGhyb3VnaCB0aGUgZmlsZXN5c3RlbSBvciB0aGUgZmlsZVxyXG4gKiAgIGRlc2NyaXB0b3IsIGFzIGFwcHJvcHJpYXRlLlxyXG4gKiAqIEhhbmRsaW5nIG9wdGlvbmFsIGFyZ3VtZW50cyBhbmQgc2V0dGluZyBkZWZhdWx0IGFyZ3VtZW50cy5cclxuICogQHNlZSBodHRwOi8vbm9kZWpzLm9yZy9hcGkvZnMuaHRtbFxyXG4gKiBAY2xhc3NcclxuICovXHJcbmV4cG9ydCBkZWZhdWx0IGNsYXNzIEZTIHtcclxuICAvLyBFeHBvcnRlZCBmcy5TdGF0cy5cclxuICBwdWJsaWMgc3RhdGljIFN0YXRzID0gU3RhdHM7XHJcblxyXG4gIHByaXZhdGUgcm9vdDogZmlsZV9zeXN0ZW0uRmlsZVN5c3RlbSA9IG51bGw7XHJcbiAgcHJpdmF0ZSBmZE1hcDoge1tmZDogbnVtYmVyXTogRmlsZX0gPSB7fTtcclxuICBwcml2YXRlIG5leHRGZCA9IDEwMDtcclxuICBwcml2YXRlIGdldEZkRm9yRmlsZShmaWxlOiBGaWxlKTogbnVtYmVyIHtcclxuICAgIGxldCBmZCA9IHRoaXMubmV4dEZkKys7XHJcbiAgICB0aGlzLmZkTWFwW2ZkXSA9IGZpbGU7XHJcbiAgICByZXR1cm4gZmQ7XHJcbiAgfVxyXG4gIHByaXZhdGUgZmQyZmlsZShmZDogbnVtYmVyKTogRmlsZSB7XHJcbiAgICBsZXQgcnYgPSB0aGlzLmZkTWFwW2ZkXTtcclxuICAgIGlmIChydikge1xyXG4gICAgICByZXR1cm4gcnY7XHJcbiAgICB9IGVsc2Uge1xyXG4gICAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVCQURGLCAnSW52YWxpZCBmaWxlIGRlc2NyaXB0b3IuJyk7XHJcbiAgICB9XHJcbiAgfVxyXG4gIHByaXZhdGUgY2xvc2VGZChmZDogbnVtYmVyKTogdm9pZCB7XHJcbiAgICBkZWxldGUgdGhpcy5mZE1hcFtmZF07XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgaW5pdGlhbGl6ZShyb290RlM6IGZpbGVfc3lzdGVtLkZpbGVTeXN0ZW0pOiBmaWxlX3N5c3RlbS5GaWxlU3lzdGVtIHtcclxuICAgIGlmICghKDxhbnk+IHJvb3RGUykuY29uc3RydWN0b3IuaXNBdmFpbGFibGUoKSkge1xyXG4gICAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVJTlZBTCwgJ1RyaWVkIHRvIGluc3RhbnRpYXRlIEJyb3dzZXJGUyB3aXRoIGFuIHVuYXZhaWxhYmxlIGZpbGUgc3lzdGVtLicpO1xyXG4gICAgfVxyXG4gICAgcmV0dXJuIHRoaXMucm9vdCA9IHJvb3RGUztcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIGNvbnZlcnRzIERhdGUgb3IgbnVtYmVyIHRvIGEgZnJhY3Rpb25hbCBVTklYIHRpbWVzdGFtcFxyXG4gICAqIEdyYWJiZWQgZnJvbSBOb2RlSlMgc291cmNlcyAobGliL2ZzLmpzKVxyXG4gICAqL1xyXG4gIHB1YmxpYyBfdG9Vbml4VGltZXN0YW1wKHRpbWU6IERhdGUgfCBudW1iZXIpOiBudW1iZXIge1xyXG4gICAgaWYgKHR5cGVvZiB0aW1lID09PSAnbnVtYmVyJykge1xyXG4gICAgICByZXR1cm4gdGltZTtcclxuICAgIH0gZWxzZSBpZiAodGltZSBpbnN0YW5jZW9mIERhdGUpIHtcclxuICAgICAgcmV0dXJuIHRpbWUuZ2V0VGltZSgpIC8gMTAwMDtcclxuICAgIH1cclxuICAgIHRocm93IG5ldyBFcnJvcihcIkNhbm5vdCBwYXJzZSB0aW1lOiBcIiArIHRpbWUpO1xyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogKipOT05TVEFOREFSRCoqOiBHcmFiIHRoZSBGaWxlU3lzdGVtIGluc3RhbmNlIHRoYXQgYmFja3MgdGhpcyBBUEkuXHJcbiAgICogQHJldHVybiBbQnJvd3NlckZTLkZpbGVTeXN0ZW0gfCBudWxsXSBSZXR1cm5zIG51bGwgaWYgdGhlIGZpbGUgc3lzdGVtIGhhc1xyXG4gICAqICAgbm90IGJlZW4gaW5pdGlhbGl6ZWQuXHJcbiAgICovXHJcbiAgcHVibGljIGdldFJvb3RGUygpOiBmaWxlX3N5c3RlbS5GaWxlU3lzdGVtIHtcclxuICAgIGlmICh0aGlzLnJvb3QpIHtcclxuICAgICAgcmV0dXJuIHRoaXMucm9vdDtcclxuICAgIH0gZWxzZSB7XHJcbiAgICAgIHJldHVybiBudWxsO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLy8gRklMRSBPUiBESVJFQ1RPUlkgTUVUSE9EU1xyXG5cclxuICAvKipcclxuICAgKiBBc3luY2hyb25vdXMgcmVuYW1lLiBObyBhcmd1bWVudHMgb3RoZXIgdGhhbiBhIHBvc3NpYmxlIGV4Y2VwdGlvbiBhcmUgZ2l2ZW5cclxuICAgKiB0byB0aGUgY29tcGxldGlvbiBjYWxsYmFjay5cclxuICAgKiBAcGFyYW0gW1N0cmluZ10gb2xkUGF0aFxyXG4gICAqIEBwYXJhbSBbU3RyaW5nXSBuZXdQYXRoXHJcbiAgICogQHBhcmFtIFtGdW5jdGlvbihCcm93c2VyRlMuQXBpRXJyb3IpXSBjYWxsYmFja1xyXG4gICAqL1xyXG4gIHB1YmxpYyByZW5hbWUob2xkUGF0aDogc3RyaW5nLCBuZXdQYXRoOiBzdHJpbmcsIGNiOiAoZXJyPzogQXBpRXJyb3IpID0+IHZvaWQgPSBub3BDYik6IHZvaWQge1xyXG4gICAgdmFyIG5ld0NiID0gd3JhcENiKGNiLCAxKTtcclxuICAgIHRyeSB7XHJcbiAgICAgIHRoaXMucm9vdC5yZW5hbWUobm9ybWFsaXplUGF0aChvbGRQYXRoKSwgbm9ybWFsaXplUGF0aChuZXdQYXRoKSwgbmV3Q2IpO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICBuZXdDYihlKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIFN5bmNocm9ub3VzIHJlbmFtZS5cclxuICAgKiBAcGFyYW0gW1N0cmluZ10gb2xkUGF0aFxyXG4gICAqIEBwYXJhbSBbU3RyaW5nXSBuZXdQYXRoXHJcbiAgICovXHJcbiAgcHVibGljIHJlbmFtZVN5bmMob2xkUGF0aDogc3RyaW5nLCBuZXdQYXRoOiBzdHJpbmcpOiB2b2lkIHtcclxuICAgIHRoaXMucm9vdC5yZW5hbWVTeW5jKG5vcm1hbGl6ZVBhdGgob2xkUGF0aCksIG5vcm1hbGl6ZVBhdGgobmV3UGF0aCkpO1xyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogVGVzdCB3aGV0aGVyIG9yIG5vdCB0aGUgZ2l2ZW4gcGF0aCBleGlzdHMgYnkgY2hlY2tpbmcgd2l0aCB0aGUgZmlsZSBzeXN0ZW0uXHJcbiAgICogVGhlbiBjYWxsIHRoZSBjYWxsYmFjayBhcmd1bWVudCB3aXRoIGVpdGhlciB0cnVlIG9yIGZhbHNlLlxyXG4gICAqIEBleGFtcGxlIFNhbXBsZSBpbnZvY2F0aW9uXHJcbiAgICogICBmcy5leGlzdHMoJy9ldGMvcGFzc3dkJywgZnVuY3Rpb24gKGV4aXN0cykge1xyXG4gICAqICAgICB1dGlsLmRlYnVnKGV4aXN0cyA/IFwiaXQncyB0aGVyZVwiIDogXCJubyBwYXNzd2QhXCIpO1xyXG4gICAqICAgfSk7XHJcbiAgICogQHBhcmFtIFtTdHJpbmddIHBhdGhcclxuICAgKiBAcGFyYW0gW0Z1bmN0aW9uKEJvb2xlYW4pXSBjYWxsYmFja1xyXG4gICAqL1xyXG4gIHB1YmxpYyBleGlzdHMocGF0aDogc3RyaW5nLCBjYjogKGV4aXN0czogYm9vbGVhbikgPT4gdm9pZCA9IG5vcENiKTogdm9pZCB7XHJcbiAgICB2YXIgbmV3Q2IgPSB3cmFwQ2IoY2IsIDEpO1xyXG4gICAgdHJ5IHtcclxuICAgICAgcmV0dXJuIHRoaXMucm9vdC5leGlzdHMobm9ybWFsaXplUGF0aChwYXRoKSwgbmV3Q2IpO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICAvLyBEb2Vzbid0IHJldHVybiBhbiBlcnJvci4gSWYgc29tZXRoaW5nIGJhZCBoYXBwZW5zLCB3ZSBhc3N1bWUgaXQganVzdFxyXG4gICAgICAvLyBkb2Vzbid0IGV4aXN0LlxyXG4gICAgICByZXR1cm4gbmV3Q2IoZmFsc2UpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogVGVzdCB3aGV0aGVyIG9yIG5vdCB0aGUgZ2l2ZW4gcGF0aCBleGlzdHMgYnkgY2hlY2tpbmcgd2l0aCB0aGUgZmlsZSBzeXN0ZW0uXHJcbiAgICogQHBhcmFtIFtTdHJpbmddIHBhdGhcclxuICAgKiBAcmV0dXJuIFtib29sZWFuXVxyXG4gICAqL1xyXG4gIHB1YmxpYyBleGlzdHNTeW5jKHBhdGg6IHN0cmluZyk6IGJvb2xlYW4ge1xyXG4gICAgdHJ5IHtcclxuICAgICAgcmV0dXJuIHRoaXMucm9vdC5leGlzdHNTeW5jKG5vcm1hbGl6ZVBhdGgocGF0aCkpO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICAvLyBEb2Vzbid0IHJldHVybiBhbiBlcnJvci4gSWYgc29tZXRoaW5nIGJhZCBoYXBwZW5zLCB3ZSBhc3N1bWUgaXQganVzdFxyXG4gICAgICAvLyBkb2Vzbid0IGV4aXN0LlxyXG4gICAgICByZXR1cm4gZmFsc2U7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBBc3luY2hyb25vdXMgYHN0YXRgLlxyXG4gICAqIEBwYXJhbSBbU3RyaW5nXSBwYXRoXHJcbiAgICogQHBhcmFtIFtGdW5jdGlvbihCcm93c2VyRlMuQXBpRXJyb3IsIEJyb3dzZXJGUy5ub2RlLmZzLlN0YXRzKV0gY2FsbGJhY2tcclxuICAgKi9cclxuICBwdWJsaWMgc3RhdChwYXRoOiBzdHJpbmcsIGNiOiAoZXJyOiBBcGlFcnJvciwgc3RhdHM/OiBTdGF0cykgPT4gYW55ID0gbm9wQ2IpOiB2b2lkIHtcclxuICAgIHZhciBuZXdDYiA9IHdyYXBDYihjYiwgMik7XHJcbiAgICB0cnkge1xyXG4gICAgICByZXR1cm4gdGhpcy5yb290LnN0YXQobm9ybWFsaXplUGF0aChwYXRoKSwgZmFsc2UsIG5ld0NiKTtcclxuICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgcmV0dXJuIG5ld0NiKGUsIG51bGwpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogU3luY2hyb25vdXMgYHN0YXRgLlxyXG4gICAqIEBwYXJhbSBbU3RyaW5nXSBwYXRoXHJcbiAgICogQHJldHVybiBbQnJvd3NlckZTLm5vZGUuZnMuU3RhdHNdXHJcbiAgICovXHJcbiAgcHVibGljIHN0YXRTeW5jKHBhdGg6IHN0cmluZyk6IFN0YXRzIHtcclxuICAgIHJldHVybiB0aGlzLnJvb3Quc3RhdFN5bmMobm9ybWFsaXplUGF0aChwYXRoKSwgZmFsc2UpO1xyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogQXN5bmNocm9ub3VzIGBsc3RhdGAuXHJcbiAgICogYGxzdGF0KClgIGlzIGlkZW50aWNhbCB0byBgc3RhdCgpYCwgZXhjZXB0IHRoYXQgaWYgcGF0aCBpcyBhIHN5bWJvbGljIGxpbmssXHJcbiAgICogdGhlbiB0aGUgbGluayBpdHNlbGYgaXMgc3RhdC1lZCwgbm90IHRoZSBmaWxlIHRoYXQgaXQgcmVmZXJzIHRvLlxyXG4gICAqIEBwYXJhbSBbU3RyaW5nXSBwYXRoXHJcbiAgICogQHBhcmFtIFtGdW5jdGlvbihCcm93c2VyRlMuQXBpRXJyb3IsIEJyb3dzZXJGUy5ub2RlLmZzLlN0YXRzKV0gY2FsbGJhY2tcclxuICAgKi9cclxuICBwdWJsaWMgbHN0YXQocGF0aDogc3RyaW5nLCBjYjogKGVycjogQXBpRXJyb3IsIHN0YXRzPzogU3RhdHMpID0+IGFueSA9IG5vcENiKTogdm9pZCB7XHJcbiAgICB2YXIgbmV3Q2IgPSB3cmFwQ2IoY2IsIDIpO1xyXG4gICAgdHJ5IHtcclxuICAgICAgcmV0dXJuIHRoaXMucm9vdC5zdGF0KG5vcm1hbGl6ZVBhdGgocGF0aCksIHRydWUsIG5ld0NiKTtcclxuICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgcmV0dXJuIG5ld0NiKGUsIG51bGwpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogU3luY2hyb25vdXMgYGxzdGF0YC5cclxuICAgKiBgbHN0YXQoKWAgaXMgaWRlbnRpY2FsIHRvIGBzdGF0KClgLCBleGNlcHQgdGhhdCBpZiBwYXRoIGlzIGEgc3ltYm9saWMgbGluayxcclxuICAgKiB0aGVuIHRoZSBsaW5rIGl0c2VsZiBpcyBzdGF0LWVkLCBub3QgdGhlIGZpbGUgdGhhdCBpdCByZWZlcnMgdG8uXHJcbiAgICogQHBhcmFtIFtTdHJpbmddIHBhdGhcclxuICAgKiBAcmV0dXJuIFtCcm93c2VyRlMubm9kZS5mcy5TdGF0c11cclxuICAgKi9cclxuICBwdWJsaWMgbHN0YXRTeW5jKHBhdGg6IHN0cmluZyk6IFN0YXRzIHtcclxuICAgIHJldHVybiB0aGlzLnJvb3Quc3RhdFN5bmMobm9ybWFsaXplUGF0aChwYXRoKSwgdHJ1ZSk7XHJcbiAgfVxyXG5cclxuICAvLyBGSUxFLU9OTFkgTUVUSE9EU1xyXG5cclxuICAvKipcclxuICAgKiBBc3luY2hyb25vdXMgYHRydW5jYXRlYC5cclxuICAgKiBAcGFyYW0gW1N0cmluZ10gcGF0aFxyXG4gICAqIEBwYXJhbSBbTnVtYmVyXSBsZW5cclxuICAgKiBAcGFyYW0gW0Z1bmN0aW9uKEJyb3dzZXJGUy5BcGlFcnJvcildIGNhbGxiYWNrXHJcbiAgICovXHJcbiAgcHVibGljIHRydW5jYXRlKHBhdGg6IHN0cmluZywgY2I/OiAoZXJyPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkO1xyXG4gIHB1YmxpYyB0cnVuY2F0ZShwYXRoOiBzdHJpbmcsIGxlbjogbnVtYmVyLCBjYj86IChlcnI/OiBBcGlFcnJvcikgPT4gdm9pZCk6IHZvaWQ7XHJcbiAgcHVibGljIHRydW5jYXRlKHBhdGg6IHN0cmluZywgYXJnMjogYW55ID0gMCwgY2I6IChlcnI/OiBBcGlFcnJvcikgPT4gdm9pZCA9IG5vcENiKTogdm9pZCB7XHJcbiAgICB2YXIgbGVuID0gMDtcclxuICAgIGlmICh0eXBlb2YgYXJnMiA9PT0gJ2Z1bmN0aW9uJykge1xyXG4gICAgICBjYiA9IGFyZzI7XHJcbiAgICB9IGVsc2UgaWYgKHR5cGVvZiBhcmcyID09PSAnbnVtYmVyJykge1xyXG4gICAgICBsZW4gPSBhcmcyO1xyXG4gICAgfVxyXG5cclxuICAgIHZhciBuZXdDYiA9IHdyYXBDYihjYiwgMSk7XHJcbiAgICB0cnkge1xyXG4gICAgICBpZiAobGVuIDwgMCkge1xyXG4gICAgICAgIHRocm93IG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRUlOVkFMKTtcclxuICAgICAgfVxyXG4gICAgICByZXR1cm4gdGhpcy5yb290LnRydW5jYXRlKG5vcm1hbGl6ZVBhdGgocGF0aCksIGxlbiwgbmV3Q2IpO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICByZXR1cm4gbmV3Q2IoZSk7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBTeW5jaHJvbm91cyBgdHJ1bmNhdGVgLlxyXG4gICAqIEBwYXJhbSBbU3RyaW5nXSBwYXRoXHJcbiAgICogQHBhcmFtIFtOdW1iZXJdIGxlblxyXG4gICAqL1xyXG4gIHB1YmxpYyB0cnVuY2F0ZVN5bmMocGF0aDogc3RyaW5nLCBsZW46IG51bWJlciA9IDApOiB2b2lkIHtcclxuICAgIGlmIChsZW4gPCAwKSB7XHJcbiAgICAgIHRocm93IG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRUlOVkFMKTtcclxuICAgIH1cclxuICAgIHJldHVybiB0aGlzLnJvb3QudHJ1bmNhdGVTeW5jKG5vcm1hbGl6ZVBhdGgocGF0aCksIGxlbik7XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBBc3luY2hyb25vdXMgYHVubGlua2AuXHJcbiAgICogQHBhcmFtIFtTdHJpbmddIHBhdGhcclxuICAgKiBAcGFyYW0gW0Z1bmN0aW9uKEJyb3dzZXJGUy5BcGlFcnJvcildIGNhbGxiYWNrXHJcbiAgICovXHJcbiAgcHVibGljIHVubGluayhwYXRoOiBzdHJpbmcsIGNiOiAoZXJyPzogQXBpRXJyb3IpID0+IHZvaWQgPSBub3BDYik6IHZvaWQge1xyXG4gICAgdmFyIG5ld0NiID0gd3JhcENiKGNiLCAxKTtcclxuICAgIHRyeSB7XHJcbiAgICAgIHJldHVybiB0aGlzLnJvb3QudW5saW5rKG5vcm1hbGl6ZVBhdGgocGF0aCksIG5ld0NiKTtcclxuICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgcmV0dXJuIG5ld0NiKGUpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogU3luY2hyb25vdXMgYHVubGlua2AuXHJcbiAgICogQHBhcmFtIFtTdHJpbmddIHBhdGhcclxuICAgKi9cclxuICBwdWJsaWMgdW5saW5rU3luYyhwYXRoOiBzdHJpbmcpOiB2b2lkIHtcclxuICAgIHJldHVybiB0aGlzLnJvb3QudW5saW5rU3luYyhub3JtYWxpemVQYXRoKHBhdGgpKTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIEFzeW5jaHJvbm91cyBmaWxlIG9wZW4uXHJcbiAgICogRXhjbHVzaXZlIG1vZGUgZW5zdXJlcyB0aGF0IHBhdGggaXMgbmV3bHkgY3JlYXRlZC5cclxuICAgKlxyXG4gICAqIGBmbGFnc2AgY2FuIGJlOlxyXG4gICAqXHJcbiAgICogKiBgJ3InYCAtIE9wZW4gZmlsZSBmb3IgcmVhZGluZy4gQW4gZXhjZXB0aW9uIG9jY3VycyBpZiB0aGUgZmlsZSBkb2VzIG5vdCBleGlzdC5cclxuICAgKiAqIGAncisnYCAtIE9wZW4gZmlsZSBmb3IgcmVhZGluZyBhbmQgd3JpdGluZy4gQW4gZXhjZXB0aW9uIG9jY3VycyBpZiB0aGUgZmlsZSBkb2VzIG5vdCBleGlzdC5cclxuICAgKiAqIGAncnMnYCAtIE9wZW4gZmlsZSBmb3IgcmVhZGluZyBpbiBzeW5jaHJvbm91cyBtb2RlLiBJbnN0cnVjdHMgdGhlIGZpbGVzeXN0ZW0gdG8gbm90IGNhY2hlIHdyaXRlcy5cclxuICAgKiAqIGAncnMrJ2AgLSBPcGVuIGZpbGUgZm9yIHJlYWRpbmcgYW5kIHdyaXRpbmcsIGFuZCBvcGVucyB0aGUgZmlsZSBpbiBzeW5jaHJvbm91cyBtb2RlLlxyXG4gICAqICogYCd3J2AgLSBPcGVuIGZpbGUgZm9yIHdyaXRpbmcuIFRoZSBmaWxlIGlzIGNyZWF0ZWQgKGlmIGl0IGRvZXMgbm90IGV4aXN0KSBvciB0cnVuY2F0ZWQgKGlmIGl0IGV4aXN0cykuXHJcbiAgICogKiBgJ3d4J2AgLSBMaWtlICd3JyBidXQgb3BlbnMgdGhlIGZpbGUgaW4gZXhjbHVzaXZlIG1vZGUuXHJcbiAgICogKiBgJ3crJ2AgLSBPcGVuIGZpbGUgZm9yIHJlYWRpbmcgYW5kIHdyaXRpbmcuIFRoZSBmaWxlIGlzIGNyZWF0ZWQgKGlmIGl0IGRvZXMgbm90IGV4aXN0KSBvciB0cnVuY2F0ZWQgKGlmIGl0IGV4aXN0cykuXHJcbiAgICogKiBgJ3d4KydgIC0gTGlrZSAndysnIGJ1dCBvcGVucyB0aGUgZmlsZSBpbiBleGNsdXNpdmUgbW9kZS5cclxuICAgKiAqIGAnYSdgIC0gT3BlbiBmaWxlIGZvciBhcHBlbmRpbmcuIFRoZSBmaWxlIGlzIGNyZWF0ZWQgaWYgaXQgZG9lcyBub3QgZXhpc3QuXHJcbiAgICogKiBgJ2F4J2AgLSBMaWtlICdhJyBidXQgb3BlbnMgdGhlIGZpbGUgaW4gZXhjbHVzaXZlIG1vZGUuXHJcbiAgICogKiBgJ2ErJ2AgLSBPcGVuIGZpbGUgZm9yIHJlYWRpbmcgYW5kIGFwcGVuZGluZy4gVGhlIGZpbGUgaXMgY3JlYXRlZCBpZiBpdCBkb2VzIG5vdCBleGlzdC5cclxuICAgKiAqIGAnYXgrJ2AgLSBMaWtlICdhKycgYnV0IG9wZW5zIHRoZSBmaWxlIGluIGV4Y2x1c2l2ZSBtb2RlLlxyXG4gICAqXHJcbiAgICogQHNlZSBodHRwOi8vd3d3Lm1hbnBhZ2V6LmNvbS9tYW4vMi9vcGVuL1xyXG4gICAqIEBwYXJhbSBbU3RyaW5nXSBwYXRoXHJcbiAgICogQHBhcmFtIFtTdHJpbmddIGZsYWdzXHJcbiAgICogQHBhcmFtIFtOdW1iZXI/XSBtb2RlIGRlZmF1bHRzIHRvIGAwNjQ0YFxyXG4gICAqIEBwYXJhbSBbRnVuY3Rpb24oQnJvd3NlckZTLkFwaUVycm9yLCBCcm93c2VyRlMuRmlsZSldIGNhbGxiYWNrXHJcbiAgICovXHJcbiAgcHVibGljIG9wZW4ocGF0aDogc3RyaW5nLCBmbGFnOiBzdHJpbmcsIGNiPzogKGVycjogQXBpRXJyb3IsIGZkPzogbnVtYmVyKSA9PiBhbnkpOiB2b2lkO1xyXG4gIHB1YmxpYyBvcGVuKHBhdGg6IHN0cmluZywgZmxhZzogc3RyaW5nLCBtb2RlOiBudW1iZXJ8c3RyaW5nLCBjYj86IChlcnI6IEFwaUVycm9yLCBmZD86IG51bWJlcikgPT4gYW55KTogdm9pZDtcclxuICBwdWJsaWMgb3BlbihwYXRoOiBzdHJpbmcsIGZsYWc6IHN0cmluZywgYXJnMj86IGFueSwgY2I6IChlcnI6IEFwaUVycm9yLCBmZD86IG51bWJlcikgPT4gYW55ID0gbm9wQ2IpOiB2b2lkIHtcclxuICAgIHZhciBtb2RlID0gbm9ybWFsaXplTW9kZShhcmcyLCAweDFhNCk7XHJcbiAgICBjYiA9IHR5cGVvZiBhcmcyID09PSAnZnVuY3Rpb24nID8gYXJnMiA6IGNiO1xyXG4gICAgdmFyIG5ld0NiID0gd3JhcENiKGNiLCAyKTtcclxuICAgIHRyeSB7XHJcbiAgICAgIHRoaXMucm9vdC5vcGVuKG5vcm1hbGl6ZVBhdGgocGF0aCksIEZpbGVGbGFnLmdldEZpbGVGbGFnKGZsYWcpLCBtb2RlLCAoZTogQXBpRXJyb3IsIGZpbGU/OiBGaWxlKSA9PiB7XHJcbiAgICAgICAgaWYgKGZpbGUpIHtcclxuICAgICAgICAgIG5ld0NiKGUsIHRoaXMuZ2V0RmRGb3JGaWxlKGZpbGUpKTtcclxuICAgICAgICB9IGVsc2Uge1xyXG4gICAgICAgICAgbmV3Q2IoZSk7XHJcbiAgICAgICAgfVxyXG4gICAgICB9KTtcclxuICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgbmV3Q2IoZSwgbnVsbCk7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBTeW5jaHJvbm91cyBmaWxlIG9wZW4uXHJcbiAgICogQHNlZSBodHRwOi8vd3d3Lm1hbnBhZ2V6LmNvbS9tYW4vMi9vcGVuL1xyXG4gICAqIEBwYXJhbSBbU3RyaW5nXSBwYXRoXHJcbiAgICogQHBhcmFtIFtTdHJpbmddIGZsYWdzXHJcbiAgICogQHBhcmFtIFtOdW1iZXI/XSBtb2RlIGRlZmF1bHRzIHRvIGAwNjQ0YFxyXG4gICAqIEByZXR1cm4gW0Jyb3dzZXJGUy5GaWxlXVxyXG4gICAqL1xyXG4gIHB1YmxpYyBvcGVuU3luYyhwYXRoOiBzdHJpbmcsIGZsYWc6IHN0cmluZywgbW9kZTogbnVtYmVyfHN0cmluZyA9IDB4MWE0KTogbnVtYmVyIHtcclxuICAgIHJldHVybiB0aGlzLmdldEZkRm9yRmlsZShcclxuICAgICAgdGhpcy5yb290Lm9wZW5TeW5jKG5vcm1hbGl6ZVBhdGgocGF0aCksIEZpbGVGbGFnLmdldEZpbGVGbGFnKGZsYWcpLCBub3JtYWxpemVNb2RlKG1vZGUsIDB4MWE0KSkpO1xyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogQXN5bmNocm9ub3VzbHkgcmVhZHMgdGhlIGVudGlyZSBjb250ZW50cyBvZiBhIGZpbGUuXHJcbiAgICogQGV4YW1wbGUgVXNhZ2UgZXhhbXBsZVxyXG4gICAqICAgZnMucmVhZEZpbGUoJy9ldGMvcGFzc3dkJywgZnVuY3Rpb24gKGVyciwgZGF0YSkge1xyXG4gICAqICAgICBpZiAoZXJyKSB0aHJvdyBlcnI7XHJcbiAgICogICAgIGNvbnNvbGUubG9nKGRhdGEpO1xyXG4gICAqICAgfSk7XHJcbiAgICogQHBhcmFtIFtTdHJpbmddIGZpbGVuYW1lXHJcbiAgICogQHBhcmFtIFtPYmplY3Q/XSBvcHRpb25zXHJcbiAgICogQG9wdGlvbiBvcHRpb25zIFtTdHJpbmddIGVuY29kaW5nIFRoZSBzdHJpbmcgZW5jb2RpbmcgZm9yIHRoZSBmaWxlIGNvbnRlbnRzLiBEZWZhdWx0cyB0byBgbnVsbGAuXHJcbiAgICogQG9wdGlvbiBvcHRpb25zIFtTdHJpbmddIGZsYWcgRGVmYXVsdHMgdG8gYCdyJ2AuXHJcbiAgICogQHBhcmFtIFtGdW5jdGlvbihCcm93c2VyRlMuQXBpRXJyb3IsIFN0cmluZyB8IEJyb3dzZXJGUy5ub2RlLkJ1ZmZlcildIGNhbGxiYWNrIElmIG5vIGVuY29kaW5nIGlzIHNwZWNpZmllZCwgdGhlbiB0aGUgcmF3IGJ1ZmZlciBpcyByZXR1cm5lZC5cclxuICAgKi9cclxuICBwdWJsaWMgcmVhZEZpbGUoZmlsZW5hbWU6IHN0cmluZywgY2I6IChlcnI6IEFwaUVycm9yLCBkYXRhPzogQnVmZmVyKSA9PiB2b2lkICk6IHZvaWQ7XHJcbiAgcHVibGljIHJlYWRGaWxlKGZpbGVuYW1lOiBzdHJpbmcsIG9wdGlvbnM6IHsgZmxhZz86IHN0cmluZzsgfSwgY2FsbGJhY2s6IChlcnI6IEFwaUVycm9yLCBkYXRhOiBCdWZmZXIpID0+IHZvaWQpOiB2b2lkO1xyXG4gIHB1YmxpYyByZWFkRmlsZShmaWxlbmFtZTogc3RyaW5nLCBvcHRpb25zOiB7IGVuY29kaW5nOiBzdHJpbmc7IGZsYWc/OiBzdHJpbmc7IH0sIGNhbGxiYWNrOiAoZXJyOiBBcGlFcnJvciwgZGF0YTogc3RyaW5nKSA9PiB2b2lkKTogdm9pZDtcclxuICBwdWJsaWMgcmVhZEZpbGUoZmlsZW5hbWU6IHN0cmluZywgZW5jb2Rpbmc6IHN0cmluZywgY2I/OiAoZXJyOiBBcGlFcnJvciwgZGF0YT86IHN0cmluZykgPT4gdm9pZCApOiB2b2lkO1xyXG4gIHB1YmxpYyByZWFkRmlsZShmaWxlbmFtZTogc3RyaW5nLCBhcmcyOiBhbnkgPSB7fSwgY2I6IChlcnI6IEFwaUVycm9yLCBkYXRhPzogYW55KSA9PiB2b2lkID0gbm9wQ2IgKSB7XHJcbiAgICB2YXIgb3B0aW9ucyA9IG5vcm1hbGl6ZU9wdGlvbnMoYXJnMiwgbnVsbCwgJ3InLCBudWxsKTtcclxuICAgIGNiID0gdHlwZW9mIGFyZzIgPT09ICdmdW5jdGlvbicgPyBhcmcyIDogY2I7XHJcbiAgICB2YXIgbmV3Q2IgPSB3cmFwQ2IoY2IsIDIpO1xyXG4gICAgdHJ5IHtcclxuICAgICAgdmFyIGZsYWcgPSBGaWxlRmxhZy5nZXRGaWxlRmxhZyhvcHRpb25zWydmbGFnJ10pO1xyXG4gICAgICBpZiAoIWZsYWcuaXNSZWFkYWJsZSgpKSB7XHJcbiAgICAgICAgcmV0dXJuIG5ld0NiKG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRUlOVkFMLCAnRmxhZyBwYXNzZWQgdG8gcmVhZEZpbGUgbXVzdCBhbGxvdyBmb3IgcmVhZGluZy4nKSk7XHJcbiAgICAgIH1cclxuICAgICAgcmV0dXJuIHRoaXMucm9vdC5yZWFkRmlsZShub3JtYWxpemVQYXRoKGZpbGVuYW1lKSwgb3B0aW9ucy5lbmNvZGluZywgZmxhZywgbmV3Q2IpO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICByZXR1cm4gbmV3Q2IoZSwgbnVsbCk7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBTeW5jaHJvbm91c2x5IHJlYWRzIHRoZSBlbnRpcmUgY29udGVudHMgb2YgYSBmaWxlLlxyXG4gICAqIEBwYXJhbSBbU3RyaW5nXSBmaWxlbmFtZVxyXG4gICAqIEBwYXJhbSBbT2JqZWN0P10gb3B0aW9uc1xyXG4gICAqIEBvcHRpb24gb3B0aW9ucyBbU3RyaW5nXSBlbmNvZGluZyBUaGUgc3RyaW5nIGVuY29kaW5nIGZvciB0aGUgZmlsZSBjb250ZW50cy4gRGVmYXVsdHMgdG8gYG51bGxgLlxyXG4gICAqIEBvcHRpb24gb3B0aW9ucyBbU3RyaW5nXSBmbGFnIERlZmF1bHRzIHRvIGAncidgLlxyXG4gICAqIEByZXR1cm4gW1N0cmluZyB8IEJyb3dzZXJGUy5ub2RlLkJ1ZmZlcl1cclxuICAgKi9cclxuICBwdWJsaWMgcmVhZEZpbGVTeW5jKGZpbGVuYW1lOiBzdHJpbmcsIG9wdGlvbnM/OiB7IGZsYWc/OiBzdHJpbmc7IH0pOiBCdWZmZXI7XHJcbiAgcHVibGljIHJlYWRGaWxlU3luYyhmaWxlbmFtZTogc3RyaW5nLCBvcHRpb25zOiB7IGVuY29kaW5nOiBzdHJpbmc7IGZsYWc/OiBzdHJpbmc7IH0pOiBzdHJpbmc7XHJcbiAgcHVibGljIHJlYWRGaWxlU3luYyhmaWxlbmFtZTogc3RyaW5nLCBlbmNvZGluZzogc3RyaW5nKTogc3RyaW5nO1xyXG4gIHB1YmxpYyByZWFkRmlsZVN5bmMoZmlsZW5hbWU6IHN0cmluZywgYXJnMjogYW55ID0ge30pOiBhbnkge1xyXG4gICAgdmFyIG9wdGlvbnMgPSBub3JtYWxpemVPcHRpb25zKGFyZzIsIG51bGwsICdyJywgbnVsbCk7XHJcbiAgICB2YXIgZmxhZyA9IEZpbGVGbGFnLmdldEZpbGVGbGFnKG9wdGlvbnMuZmxhZyk7XHJcbiAgICBpZiAoIWZsYWcuaXNSZWFkYWJsZSgpKSB7XHJcbiAgICAgIHRocm93IG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRUlOVkFMLCAnRmxhZyBwYXNzZWQgdG8gcmVhZEZpbGUgbXVzdCBhbGxvdyBmb3IgcmVhZGluZy4nKTtcclxuICAgIH1cclxuICAgIHJldHVybiB0aGlzLnJvb3QucmVhZEZpbGVTeW5jKG5vcm1hbGl6ZVBhdGgoZmlsZW5hbWUpLCBvcHRpb25zLmVuY29kaW5nLCBmbGFnKTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIEFzeW5jaHJvbm91c2x5IHdyaXRlcyBkYXRhIHRvIGEgZmlsZSwgcmVwbGFjaW5nIHRoZSBmaWxlIGlmIGl0IGFscmVhZHlcclxuICAgKiBleGlzdHMuXHJcbiAgICpcclxuICAgKiBUaGUgZW5jb2Rpbmcgb3B0aW9uIGlzIGlnbm9yZWQgaWYgZGF0YSBpcyBhIGJ1ZmZlci5cclxuICAgKlxyXG4gICAqIEBleGFtcGxlIFVzYWdlIGV4YW1wbGVcclxuICAgKiAgIGZzLndyaXRlRmlsZSgnbWVzc2FnZS50eHQnLCAnSGVsbG8gTm9kZScsIGZ1bmN0aW9uIChlcnIpIHtcclxuICAgKiAgICAgaWYgKGVycikgdGhyb3cgZXJyO1xyXG4gICAqICAgICBjb25zb2xlLmxvZygnSXRcXCdzIHNhdmVkIScpO1xyXG4gICAqICAgfSk7XHJcbiAgICogQHBhcmFtIFtTdHJpbmddIGZpbGVuYW1lXHJcbiAgICogQHBhcmFtIFtTdHJpbmcgfCBCcm93c2VyRlMubm9kZS5CdWZmZXJdIGRhdGFcclxuICAgKiBAcGFyYW0gW09iamVjdD9dIG9wdGlvbnNcclxuICAgKiBAb3B0aW9uIG9wdGlvbnMgW1N0cmluZ10gZW5jb2RpbmcgRGVmYXVsdHMgdG8gYCd1dGY4J2AuXHJcbiAgICogQG9wdGlvbiBvcHRpb25zIFtOdW1iZXJdIG1vZGUgRGVmYXVsdHMgdG8gYDA2NDRgLlxyXG4gICAqIEBvcHRpb24gb3B0aW9ucyBbU3RyaW5nXSBmbGFnIERlZmF1bHRzIHRvIGAndydgLlxyXG4gICAqIEBwYXJhbSBbRnVuY3Rpb24oQnJvd3NlckZTLkFwaUVycm9yKV0gY2FsbGJhY2tcclxuICAgKi9cclxuICBwdWJsaWMgd3JpdGVGaWxlKGZpbGVuYW1lOiBzdHJpbmcsIGRhdGE6IGFueSwgY2I/OiAoZXJyPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkO1xyXG4gIHB1YmxpYyB3cml0ZUZpbGUoZmlsZW5hbWU6IHN0cmluZywgZGF0YTogYW55LCBlbmNvZGluZz86IHN0cmluZywgY2I/OiAoZXJyPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkO1xyXG4gIHB1YmxpYyB3cml0ZUZpbGUoZmlsZW5hbWU6IHN0cmluZywgZGF0YTogYW55LCBvcHRpb25zPzogeyBlbmNvZGluZz86IHN0cmluZzsgbW9kZT86IHN0cmluZyB8IG51bWJlcjsgZmxhZz86IHN0cmluZzsgfSwgY2I/OiAoZXJyPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkO1xyXG4gIHB1YmxpYyB3cml0ZUZpbGUoZmlsZW5hbWU6IHN0cmluZywgZGF0YTogYW55LCBhcmczOiBhbnkgPSB7fSwgY2I6IChlcnI/OiBBcGlFcnJvcikgPT4gdm9pZCA9IG5vcENiKTogdm9pZCB7XHJcbiAgICB2YXIgb3B0aW9ucyA9IG5vcm1hbGl6ZU9wdGlvbnMoYXJnMywgJ3V0ZjgnLCAndycsIDB4MWE0KTtcclxuICAgIGNiID0gdHlwZW9mIGFyZzMgPT09ICdmdW5jdGlvbicgPyBhcmczIDogY2I7XHJcbiAgICB2YXIgbmV3Q2IgPSB3cmFwQ2IoY2IsIDEpO1xyXG4gICAgdHJ5IHtcclxuICAgICAgdmFyIGZsYWcgPSBGaWxlRmxhZy5nZXRGaWxlRmxhZyhvcHRpb25zLmZsYWcpO1xyXG4gICAgICBpZiAoIWZsYWcuaXNXcml0ZWFibGUoKSkge1xyXG4gICAgICAgIHJldHVybiBuZXdDYihuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVJTlZBTCwgJ0ZsYWcgcGFzc2VkIHRvIHdyaXRlRmlsZSBtdXN0IGFsbG93IGZvciB3cml0aW5nLicpKTtcclxuICAgICAgfVxyXG4gICAgICByZXR1cm4gdGhpcy5yb290LndyaXRlRmlsZShub3JtYWxpemVQYXRoKGZpbGVuYW1lKSwgZGF0YSwgb3B0aW9ucy5lbmNvZGluZywgZmxhZywgb3B0aW9ucy5tb2RlLCBuZXdDYik7XHJcbiAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgIHJldHVybiBuZXdDYihlKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIFN5bmNocm9ub3VzbHkgd3JpdGVzIGRhdGEgdG8gYSBmaWxlLCByZXBsYWNpbmcgdGhlIGZpbGUgaWYgaXQgYWxyZWFkeVxyXG4gICAqIGV4aXN0cy5cclxuICAgKlxyXG4gICAqIFRoZSBlbmNvZGluZyBvcHRpb24gaXMgaWdub3JlZCBpZiBkYXRhIGlzIGEgYnVmZmVyLlxyXG4gICAqIEBwYXJhbSBbU3RyaW5nXSBmaWxlbmFtZVxyXG4gICAqIEBwYXJhbSBbU3RyaW5nIHwgQnJvd3NlckZTLm5vZGUuQnVmZmVyXSBkYXRhXHJcbiAgICogQHBhcmFtIFtPYmplY3Q/XSBvcHRpb25zXHJcbiAgICogQG9wdGlvbiBvcHRpb25zIFtTdHJpbmddIGVuY29kaW5nIERlZmF1bHRzIHRvIGAndXRmOCdgLlxyXG4gICAqIEBvcHRpb24gb3B0aW9ucyBbTnVtYmVyXSBtb2RlIERlZmF1bHRzIHRvIGAwNjQ0YC5cclxuICAgKiBAb3B0aW9uIG9wdGlvbnMgW1N0cmluZ10gZmxhZyBEZWZhdWx0cyB0byBgJ3cnYC5cclxuICAgKi9cclxuICBwdWJsaWMgd3JpdGVGaWxlU3luYyhmaWxlbmFtZTogc3RyaW5nLCBkYXRhOiBhbnksIG9wdGlvbnM/OiB7IGVuY29kaW5nPzogc3RyaW5nOyBtb2RlPzogbnVtYmVyIHwgc3RyaW5nOyBmbGFnPzogc3RyaW5nOyB9KTogdm9pZDtcclxuICBwdWJsaWMgd3JpdGVGaWxlU3luYyhmaWxlbmFtZTogc3RyaW5nLCBkYXRhOiBhbnksIGVuY29kaW5nPzogc3RyaW5nKTogdm9pZDtcclxuICBwdWJsaWMgd3JpdGVGaWxlU3luYyhmaWxlbmFtZTogc3RyaW5nLCBkYXRhOiBhbnksIGFyZzM/OiBhbnkpOiB2b2lkIHtcclxuICAgIHZhciBvcHRpb25zID0gbm9ybWFsaXplT3B0aW9ucyhhcmczLCAndXRmOCcsICd3JywgMHgxYTQpO1xyXG4gICAgdmFyIGZsYWcgPSBGaWxlRmxhZy5nZXRGaWxlRmxhZyhvcHRpb25zLmZsYWcpO1xyXG4gICAgaWYgKCFmbGFnLmlzV3JpdGVhYmxlKCkpIHtcclxuICAgICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FSU5WQUwsICdGbGFnIHBhc3NlZCB0byB3cml0ZUZpbGUgbXVzdCBhbGxvdyBmb3Igd3JpdGluZy4nKTtcclxuICAgIH1cclxuICAgIHJldHVybiB0aGlzLnJvb3Qud3JpdGVGaWxlU3luYyhub3JtYWxpemVQYXRoKGZpbGVuYW1lKSwgZGF0YSwgb3B0aW9ucy5lbmNvZGluZywgZmxhZywgb3B0aW9ucy5tb2RlKTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIEFzeW5jaHJvbm91c2x5IGFwcGVuZCBkYXRhIHRvIGEgZmlsZSwgY3JlYXRpbmcgdGhlIGZpbGUgaWYgaXQgbm90IHlldFxyXG4gICAqIGV4aXN0cy5cclxuICAgKlxyXG4gICAqIEBleGFtcGxlIFVzYWdlIGV4YW1wbGVcclxuICAgKiAgIGZzLmFwcGVuZEZpbGUoJ21lc3NhZ2UudHh0JywgJ2RhdGEgdG8gYXBwZW5kJywgZnVuY3Rpb24gKGVycikge1xyXG4gICAqICAgICBpZiAoZXJyKSB0aHJvdyBlcnI7XHJcbiAgICogICAgIGNvbnNvbGUubG9nKCdUaGUgXCJkYXRhIHRvIGFwcGVuZFwiIHdhcyBhcHBlbmRlZCB0byBmaWxlIScpO1xyXG4gICAqICAgfSk7XHJcbiAgICogQHBhcmFtIFtTdHJpbmddIGZpbGVuYW1lXHJcbiAgICogQHBhcmFtIFtTdHJpbmcgfCBCcm93c2VyRlMubm9kZS5CdWZmZXJdIGRhdGFcclxuICAgKiBAcGFyYW0gW09iamVjdD9dIG9wdGlvbnNcclxuICAgKiBAb3B0aW9uIG9wdGlvbnMgW1N0cmluZ10gZW5jb2RpbmcgRGVmYXVsdHMgdG8gYCd1dGY4J2AuXHJcbiAgICogQG9wdGlvbiBvcHRpb25zIFtOdW1iZXJdIG1vZGUgRGVmYXVsdHMgdG8gYDA2NDRgLlxyXG4gICAqIEBvcHRpb24gb3B0aW9ucyBbU3RyaW5nXSBmbGFnIERlZmF1bHRzIHRvIGAnYSdgLlxyXG4gICAqIEBwYXJhbSBbRnVuY3Rpb24oQnJvd3NlckZTLkFwaUVycm9yKV0gY2FsbGJhY2tcclxuICAgKi9cclxuICBwdWJsaWMgYXBwZW5kRmlsZShmaWxlbmFtZTogc3RyaW5nLCBkYXRhOiBhbnksIGNiPzogKGVycjogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkO1xyXG4gIHB1YmxpYyBhcHBlbmRGaWxlKGZpbGVuYW1lOiBzdHJpbmcsIGRhdGE6IGFueSwgb3B0aW9ucz86IHsgZW5jb2Rpbmc/OiBzdHJpbmc7IG1vZGU/OiBudW1iZXJ8c3RyaW5nOyBmbGFnPzogc3RyaW5nOyB9LCBjYj86IChlcnI6IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZDtcclxuICBwdWJsaWMgYXBwZW5kRmlsZShmaWxlbmFtZTogc3RyaW5nLCBkYXRhOiBhbnksIGVuY29kaW5nPzogc3RyaW5nLCBjYj86IChlcnI6IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZDtcclxuICBwdWJsaWMgYXBwZW5kRmlsZShmaWxlbmFtZTogc3RyaW5nLCBkYXRhOiBhbnksIGFyZzM/OiBhbnksIGNiOiAoZXJyOiBBcGlFcnJvcikgPT4gdm9pZCA9IG5vcENiKTogdm9pZCB7XHJcbiAgICB2YXIgb3B0aW9ucyA9IG5vcm1hbGl6ZU9wdGlvbnMoYXJnMywgJ3V0ZjgnLCAnYScsIDB4MWE0KTtcclxuICAgIGNiID0gdHlwZW9mIGFyZzMgPT09ICdmdW5jdGlvbicgPyBhcmczIDogY2I7XHJcbiAgICB2YXIgbmV3Q2IgPSB3cmFwQ2IoY2IsIDEpO1xyXG4gICAgdHJ5IHtcclxuICAgICAgdmFyIGZsYWcgPSBGaWxlRmxhZy5nZXRGaWxlRmxhZyhvcHRpb25zLmZsYWcpO1xyXG4gICAgICBpZiAoIWZsYWcuaXNBcHBlbmRhYmxlKCkpIHtcclxuICAgICAgICByZXR1cm4gbmV3Q2IobmV3IEFwaUVycm9yKEVycm9yQ29kZS5FSU5WQUwsICdGbGFnIHBhc3NlZCB0byBhcHBlbmRGaWxlIG11c3QgYWxsb3cgZm9yIGFwcGVuZGluZy4nKSk7XHJcbiAgICAgIH1cclxuICAgICAgdGhpcy5yb290LmFwcGVuZEZpbGUobm9ybWFsaXplUGF0aChmaWxlbmFtZSksIGRhdGEsIG9wdGlvbnMuZW5jb2RpbmcsIGZsYWcsIG9wdGlvbnMubW9kZSwgbmV3Q2IpO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICBuZXdDYihlKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIEFzeW5jaHJvbm91c2x5IGFwcGVuZCBkYXRhIHRvIGEgZmlsZSwgY3JlYXRpbmcgdGhlIGZpbGUgaWYgaXQgbm90IHlldFxyXG4gICAqIGV4aXN0cy5cclxuICAgKlxyXG4gICAqIEBleGFtcGxlIFVzYWdlIGV4YW1wbGVcclxuICAgKiAgIGZzLmFwcGVuZEZpbGUoJ21lc3NhZ2UudHh0JywgJ2RhdGEgdG8gYXBwZW5kJywgZnVuY3Rpb24gKGVycikge1xyXG4gICAqICAgICBpZiAoZXJyKSB0aHJvdyBlcnI7XHJcbiAgICogICAgIGNvbnNvbGUubG9nKCdUaGUgXCJkYXRhIHRvIGFwcGVuZFwiIHdhcyBhcHBlbmRlZCB0byBmaWxlIScpO1xyXG4gICAqICAgfSk7XHJcbiAgICogQHBhcmFtIFtTdHJpbmddIGZpbGVuYW1lXHJcbiAgICogQHBhcmFtIFtTdHJpbmcgfCBCcm93c2VyRlMubm9kZS5CdWZmZXJdIGRhdGFcclxuICAgKiBAcGFyYW0gW09iamVjdD9dIG9wdGlvbnNcclxuICAgKiBAb3B0aW9uIG9wdGlvbnMgW1N0cmluZ10gZW5jb2RpbmcgRGVmYXVsdHMgdG8gYCd1dGY4J2AuXHJcbiAgICogQG9wdGlvbiBvcHRpb25zIFtOdW1iZXJdIG1vZGUgRGVmYXVsdHMgdG8gYDA2NDRgLlxyXG4gICAqIEBvcHRpb24gb3B0aW9ucyBbU3RyaW5nXSBmbGFnIERlZmF1bHRzIHRvIGAnYSdgLlxyXG4gICAqL1xyXG4gIHB1YmxpYyBhcHBlbmRGaWxlU3luYyhmaWxlbmFtZTogc3RyaW5nLCBkYXRhOiBhbnksIG9wdGlvbnM/OiB7IGVuY29kaW5nPzogc3RyaW5nOyBtb2RlPzogbnVtYmVyIHwgc3RyaW5nOyBmbGFnPzogc3RyaW5nOyB9KTogdm9pZDtcclxuICBwdWJsaWMgYXBwZW5kRmlsZVN5bmMoZmlsZW5hbWU6IHN0cmluZywgZGF0YTogYW55LCBlbmNvZGluZz86IHN0cmluZyk6IHZvaWQ7XHJcbiAgcHVibGljIGFwcGVuZEZpbGVTeW5jKGZpbGVuYW1lOiBzdHJpbmcsIGRhdGE6IGFueSwgYXJnMz86IGFueSk6IHZvaWQge1xyXG4gICAgdmFyIG9wdGlvbnMgPSBub3JtYWxpemVPcHRpb25zKGFyZzMsICd1dGY4JywgJ2EnLCAweDFhNCk7XHJcbiAgICB2YXIgZmxhZyA9IEZpbGVGbGFnLmdldEZpbGVGbGFnKG9wdGlvbnMuZmxhZyk7XHJcbiAgICBpZiAoIWZsYWcuaXNBcHBlbmRhYmxlKCkpIHtcclxuICAgICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FSU5WQUwsICdGbGFnIHBhc3NlZCB0byBhcHBlbmRGaWxlIG11c3QgYWxsb3cgZm9yIGFwcGVuZGluZy4nKTtcclxuICAgIH1cclxuICAgIHJldHVybiB0aGlzLnJvb3QuYXBwZW5kRmlsZVN5bmMobm9ybWFsaXplUGF0aChmaWxlbmFtZSksIGRhdGEsIG9wdGlvbnMuZW5jb2RpbmcsIGZsYWcsIG9wdGlvbnMubW9kZSk7XHJcbiAgfVxyXG5cclxuICAvLyBGSUxFIERFU0NSSVBUT1IgTUVUSE9EU1xyXG5cclxuICAvKipcclxuICAgKiBBc3luY2hyb25vdXMgYGZzdGF0YC5cclxuICAgKiBgZnN0YXQoKWAgaXMgaWRlbnRpY2FsIHRvIGBzdGF0KClgLCBleGNlcHQgdGhhdCB0aGUgZmlsZSB0byBiZSBzdGF0LWVkIGlzXHJcbiAgICogc3BlY2lmaWVkIGJ5IHRoZSBmaWxlIGRlc2NyaXB0b3IgYGZkYC5cclxuICAgKiBAcGFyYW0gW0Jyb3dzZXJGUy5GaWxlXSBmZFxyXG4gICAqIEBwYXJhbSBbRnVuY3Rpb24oQnJvd3NlckZTLkFwaUVycm9yLCBCcm93c2VyRlMubm9kZS5mcy5TdGF0cyldIGNhbGxiYWNrXHJcbiAgICovXHJcbiAgcHVibGljIGZzdGF0KGZkOiBudW1iZXIsIGNiOiAoZXJyOiBBcGlFcnJvciwgc3RhdHM/OiBTdGF0cykgPT4gYW55ID0gbm9wQ2IpOiB2b2lkIHtcclxuICAgIHZhciBuZXdDYiA9IHdyYXBDYihjYiwgMik7XHJcbiAgICB0cnkge1xyXG4gICAgICBsZXQgZmlsZSA9IHRoaXMuZmQyZmlsZShmZCk7XHJcbiAgICAgIGZpbGUuc3RhdChuZXdDYik7XHJcbiAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgIG5ld0NiKGUpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogU3luY2hyb25vdXMgYGZzdGF0YC5cclxuICAgKiBgZnN0YXQoKWAgaXMgaWRlbnRpY2FsIHRvIGBzdGF0KClgLCBleGNlcHQgdGhhdCB0aGUgZmlsZSB0byBiZSBzdGF0LWVkIGlzXHJcbiAgICogc3BlY2lmaWVkIGJ5IHRoZSBmaWxlIGRlc2NyaXB0b3IgYGZkYC5cclxuICAgKiBAcGFyYW0gW0Jyb3dzZXJGUy5GaWxlXSBmZFxyXG4gICAqIEByZXR1cm4gW0Jyb3dzZXJGUy5ub2RlLmZzLlN0YXRzXVxyXG4gICAqL1xyXG4gIHB1YmxpYyBmc3RhdFN5bmMoZmQ6IG51bWJlcik6IFN0YXRzIHtcclxuICAgIHJldHVybiB0aGlzLmZkMmZpbGUoZmQpLnN0YXRTeW5jKCk7XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBBc3luY2hyb25vdXMgY2xvc2UuXHJcbiAgICogQHBhcmFtIFtCcm93c2VyRlMuRmlsZV0gZmRcclxuICAgKiBAcGFyYW0gW0Z1bmN0aW9uKEJyb3dzZXJGUy5BcGlFcnJvcildIGNhbGxiYWNrXHJcbiAgICovXHJcbiAgcHVibGljIGNsb3NlKGZkOiBudW1iZXIsIGNiOiAoZT86IEFwaUVycm9yKSA9PiB2b2lkID0gbm9wQ2IpOiB2b2lkIHtcclxuICAgIHZhciBuZXdDYiA9IHdyYXBDYihjYiwgMSk7XHJcbiAgICB0cnkge1xyXG4gICAgICB0aGlzLmZkMmZpbGUoZmQpLmNsb3NlKChlOiBBcGlFcnJvcikgPT4ge1xyXG4gICAgICAgIGlmICghZSkge1xyXG4gICAgICAgICAgdGhpcy5jbG9zZUZkKGZkKTtcclxuICAgICAgICB9XHJcbiAgICAgICAgbmV3Q2IoZSk7XHJcbiAgICAgIH0pO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICBuZXdDYihlKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIFN5bmNocm9ub3VzIGNsb3NlLlxyXG4gICAqIEBwYXJhbSBbQnJvd3NlckZTLkZpbGVdIGZkXHJcbiAgICovXHJcbiAgcHVibGljIGNsb3NlU3luYyhmZDogbnVtYmVyKTogdm9pZCB7XHJcbiAgICB0aGlzLmZkMmZpbGUoZmQpLmNsb3NlU3luYygpO1xyXG4gICAgdGhpcy5jbG9zZUZkKGZkKTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIEFzeW5jaHJvbm91cyBmdHJ1bmNhdGUuXHJcbiAgICogQHBhcmFtIFtCcm93c2VyRlMuRmlsZV0gZmRcclxuICAgKiBAcGFyYW0gW051bWJlcl0gbGVuXHJcbiAgICogQHBhcmFtIFtGdW5jdGlvbihCcm93c2VyRlMuQXBpRXJyb3IpXSBjYWxsYmFja1xyXG4gICAqL1xyXG4gIHB1YmxpYyBmdHJ1bmNhdGUoZmQ6IG51bWJlciwgY2I/OiAoZXJyPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkO1xyXG4gIHB1YmxpYyBmdHJ1bmNhdGUoZmQ6IG51bWJlciwgbGVuPzogbnVtYmVyLCBjYj86IChlcnI/OiBBcGlFcnJvcikgPT4gdm9pZCk6IHZvaWQ7XHJcbiAgcHVibGljIGZ0cnVuY2F0ZShmZDogbnVtYmVyLCBhcmcyPzogYW55LCBjYjogKGVycj86IEFwaUVycm9yKSA9PiB2b2lkID0gbm9wQ2IpOiB2b2lkIHtcclxuICAgIHZhciBsZW5ndGggPSB0eXBlb2YgYXJnMiA9PT0gJ251bWJlcicgPyBhcmcyIDogMDtcclxuICAgIGNiID0gdHlwZW9mIGFyZzIgPT09ICdmdW5jdGlvbicgPyBhcmcyIDogY2I7XHJcbiAgICB2YXIgbmV3Q2IgPSB3cmFwQ2IoY2IsIDEpO1xyXG4gICAgdHJ5IHtcclxuICAgICAgbGV0IGZpbGUgPSB0aGlzLmZkMmZpbGUoZmQpO1xyXG4gICAgICBpZiAobGVuZ3RoIDwgMCkge1xyXG4gICAgICAgIHRocm93IG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRUlOVkFMKTtcclxuICAgICAgfVxyXG4gICAgICBmaWxlLnRydW5jYXRlKGxlbmd0aCwgbmV3Q2IpO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICBuZXdDYihlKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIFN5bmNocm9ub3VzIGZ0cnVuY2F0ZS5cclxuICAgKiBAcGFyYW0gW0Jyb3dzZXJGUy5GaWxlXSBmZFxyXG4gICAqIEBwYXJhbSBbTnVtYmVyXSBsZW5cclxuICAgKi9cclxuICBwdWJsaWMgZnRydW5jYXRlU3luYyhmZDogbnVtYmVyLCBsZW46IG51bWJlciA9IDApOiB2b2lkIHtcclxuICAgIGxldCBmaWxlID0gdGhpcy5mZDJmaWxlKGZkKTtcclxuICAgIGlmIChsZW4gPCAwKSB7XHJcbiAgICAgIHRocm93IG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRUlOVkFMKTtcclxuICAgIH1cclxuICAgIGZpbGUudHJ1bmNhdGVTeW5jKGxlbik7XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBBc3luY2hyb25vdXMgZnN5bmMuXHJcbiAgICogQHBhcmFtIFtCcm93c2VyRlMuRmlsZV0gZmRcclxuICAgKiBAcGFyYW0gW0Z1bmN0aW9uKEJyb3dzZXJGUy5BcGlFcnJvcildIGNhbGxiYWNrXHJcbiAgICovXHJcbiAgcHVibGljIGZzeW5jKGZkOiBudW1iZXIsIGNiOiAoZXJyPzogQXBpRXJyb3IpID0+IHZvaWQgPSBub3BDYik6IHZvaWQge1xyXG4gICAgdmFyIG5ld0NiID0gd3JhcENiKGNiLCAxKTtcclxuICAgIHRyeSB7XHJcbiAgICAgIHRoaXMuZmQyZmlsZShmZCkuc3luYyhuZXdDYik7XHJcbiAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgIG5ld0NiKGUpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogU3luY2hyb25vdXMgZnN5bmMuXHJcbiAgICogQHBhcmFtIFtCcm93c2VyRlMuRmlsZV0gZmRcclxuICAgKi9cclxuICBwdWJsaWMgZnN5bmNTeW5jKGZkOiBudW1iZXIpOiB2b2lkIHtcclxuICAgIHRoaXMuZmQyZmlsZShmZCkuc3luY1N5bmMoKTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIEFzeW5jaHJvbm91cyBmZGF0YXN5bmMuXHJcbiAgICogQHBhcmFtIFtCcm93c2VyRlMuRmlsZV0gZmRcclxuICAgKiBAcGFyYW0gW0Z1bmN0aW9uKEJyb3dzZXJGUy5BcGlFcnJvcildIGNhbGxiYWNrXHJcbiAgICovXHJcbiAgcHVibGljIGZkYXRhc3luYyhmZDogbnVtYmVyLCBjYjogKGVycj86IEFwaUVycm9yKSA9PiB2b2lkID0gbm9wQ2IpOiB2b2lkIHtcclxuICAgIHZhciBuZXdDYiA9IHdyYXBDYihjYiwgMSk7XHJcbiAgICB0cnkge1xyXG4gICAgICB0aGlzLmZkMmZpbGUoZmQpLmRhdGFzeW5jKG5ld0NiKTtcclxuICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgbmV3Q2IoZSk7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBTeW5jaHJvbm91cyBmZGF0YXN5bmMuXHJcbiAgICogQHBhcmFtIFtCcm93c2VyRlMuRmlsZV0gZmRcclxuICAgKi9cclxuICBwdWJsaWMgZmRhdGFzeW5jU3luYyhmZDogbnVtYmVyKTogdm9pZCB7XHJcbiAgICB0aGlzLmZkMmZpbGUoZmQpLmRhdGFzeW5jU3luYygpO1xyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogV3JpdGUgYnVmZmVyIHRvIHRoZSBmaWxlIHNwZWNpZmllZCBieSBgZmRgLlxyXG4gICAqIE5vdGUgdGhhdCBpdCBpcyB1bnNhZmUgdG8gdXNlIGZzLndyaXRlIG11bHRpcGxlIHRpbWVzIG9uIHRoZSBzYW1lIGZpbGVcclxuICAgKiB3aXRob3V0IHdhaXRpbmcgZm9yIHRoZSBjYWxsYmFjay5cclxuICAgKiBAcGFyYW0gW0Jyb3dzZXJGUy5GaWxlXSBmZFxyXG4gICAqIEBwYXJhbSBbQnJvd3NlckZTLm5vZGUuQnVmZmVyXSBidWZmZXIgQnVmZmVyIGNvbnRhaW5pbmcgdGhlIGRhdGEgdG8gd3JpdGUgdG9cclxuICAgKiAgIHRoZSBmaWxlLlxyXG4gICAqIEBwYXJhbSBbTnVtYmVyXSBvZmZzZXQgT2Zmc2V0IGluIHRoZSBidWZmZXIgdG8gc3RhcnQgcmVhZGluZyBkYXRhIGZyb20uXHJcbiAgICogQHBhcmFtIFtOdW1iZXJdIGxlbmd0aCBUaGUgYW1vdW50IG9mIGJ5dGVzIHRvIHdyaXRlIHRvIHRoZSBmaWxlLlxyXG4gICAqIEBwYXJhbSBbTnVtYmVyXSBwb3NpdGlvbiBPZmZzZXQgZnJvbSB0aGUgYmVnaW5uaW5nIG9mIHRoZSBmaWxlIHdoZXJlIHRoaXNcclxuICAgKiAgIGRhdGEgc2hvdWxkIGJlIHdyaXR0ZW4uIElmIHBvc2l0aW9uIGlzIG51bGwsIHRoZSBkYXRhIHdpbGwgYmUgd3JpdHRlbiBhdFxyXG4gICAqICAgdGhlIGN1cnJlbnQgcG9zaXRpb24uXHJcbiAgICogQHBhcmFtIFtGdW5jdGlvbihCcm93c2VyRlMuQXBpRXJyb3IsIE51bWJlciwgQnJvd3NlckZTLm5vZGUuQnVmZmVyKV1cclxuICAgKiAgIGNhbGxiYWNrIFRoZSBudW1iZXIgc3BlY2lmaWVzIHRoZSBudW1iZXIgb2YgYnl0ZXMgd3JpdHRlbiBpbnRvIHRoZSBmaWxlLlxyXG4gICAqL1xyXG4gIHB1YmxpYyB3cml0ZShmZDogbnVtYmVyLCBidWZmZXI6IEJ1ZmZlciwgb2Zmc2V0OiBudW1iZXIsIGxlbmd0aDogbnVtYmVyLCBjYj86IChlcnI6IEFwaUVycm9yLCB3cml0dGVuOiBudW1iZXIsIGJ1ZmZlcjogQnVmZmVyKSA9PiB2b2lkKTogdm9pZDtcclxuICBwdWJsaWMgd3JpdGUoZmQ6IG51bWJlciwgYnVmZmVyOiBCdWZmZXIsIG9mZnNldDogbnVtYmVyLCBsZW5ndGg6IG51bWJlciwgcG9zaXRpb246IG51bWJlciwgY2I/OiAoZXJyOiBBcGlFcnJvciwgd3JpdHRlbjogbnVtYmVyLCBidWZmZXI6IEJ1ZmZlcikgPT4gdm9pZCk6IHZvaWQ7XHJcbiAgcHVibGljIHdyaXRlKGZkOiBudW1iZXIsIGRhdGE6IGFueSwgY2I/OiAoZXJyOiBBcGlFcnJvciwgd3JpdHRlbjogbnVtYmVyLCBzdHI6IHN0cmluZykgPT4gYW55KTogdm9pZDtcclxuICBwdWJsaWMgd3JpdGUoZmQ6IG51bWJlciwgZGF0YTogYW55LCBwb3NpdGlvbjogbnVtYmVyLCBjYj86IChlcnI6IEFwaUVycm9yLCB3cml0dGVuOiBudW1iZXIsIHN0cjogc3RyaW5nKSA9PiBhbnkpOiB2b2lkO1xyXG4gIHB1YmxpYyB3cml0ZShmZDogbnVtYmVyLCBkYXRhOiBhbnksIHBvc2l0aW9uOiBudW1iZXIsIGVuY29kaW5nOiBzdHJpbmcsIGNiPzogKGVycjogQXBpRXJyb3IsIHdyaXR0ZW46IG51bWJlciwgc3RyOiBzdHJpbmcpID0+IHZvaWQpOiB2b2lkO1xyXG4gIHB1YmxpYyB3cml0ZShmZDogbnVtYmVyLCBhcmcyOiBhbnksIGFyZzM/OiBhbnksIGFyZzQ/OiBhbnksIGFyZzU/OiBhbnksIGNiOiAoZXJyOiBBcGlFcnJvciwgd3JpdHRlbj86IG51bWJlciwgYnVmZmVyPzogQnVmZmVyKSA9PiB2b2lkID0gbm9wQ2IpOiB2b2lkIHtcclxuICAgIHZhciBidWZmZXI6IEJ1ZmZlciwgb2Zmc2V0OiBudW1iZXIsIGxlbmd0aDogbnVtYmVyLCBwb3NpdGlvbjogbnVtYmVyID0gbnVsbDtcclxuICAgIGlmICh0eXBlb2YgYXJnMiA9PT0gJ3N0cmluZycpIHtcclxuICAgICAgLy8gU2lnbmF0dXJlIDE6IChmZCwgc3RyaW5nLCBbcG9zaXRpb24/LCBbZW5jb2Rpbmc/XV0sIGNiPylcclxuICAgICAgdmFyIGVuY29kaW5nID0gJ3V0ZjgnO1xyXG4gICAgICBzd2l0Y2ggKHR5cGVvZiBhcmczKSB7XHJcbiAgICAgICAgY2FzZSAnZnVuY3Rpb24nOlxyXG4gICAgICAgICAgLy8gKGZkLCBzdHJpbmcsIGNiKVxyXG4gICAgICAgICAgY2IgPSBhcmczO1xyXG4gICAgICAgICAgYnJlYWs7XHJcbiAgICAgICAgY2FzZSAnbnVtYmVyJzpcclxuICAgICAgICAgIC8vIChmZCwgc3RyaW5nLCBwb3NpdGlvbiwgZW5jb2Rpbmc/LCBjYj8pXHJcbiAgICAgICAgICBwb3NpdGlvbiA9IGFyZzM7XHJcbiAgICAgICAgICBlbmNvZGluZyA9IHR5cGVvZiBhcmc0ID09PSAnc3RyaW5nJyA/IGFyZzQgOiAndXRmOCc7XHJcbiAgICAgICAgICBjYiA9IHR5cGVvZiBhcmc1ID09PSAnZnVuY3Rpb24nID8gYXJnNSA6IGNiO1xyXG4gICAgICAgICAgYnJlYWs7XHJcbiAgICAgICAgZGVmYXVsdDpcclxuICAgICAgICAgIC8vIC4uLnRyeSB0byBmaW5kIHRoZSBjYWxsYmFjayBhbmQgZ2V0IG91dCBvZiBoZXJlIVxyXG4gICAgICAgICAgY2IgPSB0eXBlb2YgYXJnNCA9PT0gJ2Z1bmN0aW9uJyA/IGFyZzQgOiB0eXBlb2YgYXJnNSA9PT0gJ2Z1bmN0aW9uJyA/IGFyZzUgOiBjYjtcclxuICAgICAgICAgIHJldHVybiBjYihuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVJTlZBTCwgJ0ludmFsaWQgYXJndW1lbnRzLicpKTtcclxuICAgICAgfVxyXG4gICAgICBidWZmZXIgPSBuZXcgQnVmZmVyKGFyZzIsIGVuY29kaW5nKTtcclxuICAgICAgb2Zmc2V0ID0gMDtcclxuICAgICAgbGVuZ3RoID0gYnVmZmVyLmxlbmd0aDtcclxuICAgIH0gZWxzZSB7XHJcbiAgICAgIC8vIFNpZ25hdHVyZSAyOiAoZmQsIGJ1ZmZlciwgb2Zmc2V0LCBsZW5ndGgsIHBvc2l0aW9uPywgY2I/KVxyXG4gICAgICBidWZmZXIgPSBhcmcyO1xyXG4gICAgICBvZmZzZXQgPSBhcmczO1xyXG4gICAgICBsZW5ndGggPSBhcmc0O1xyXG4gICAgICBwb3NpdGlvbiA9IHR5cGVvZiBhcmc1ID09PSAnbnVtYmVyJyA/IGFyZzUgOiBudWxsO1xyXG4gICAgICBjYiA9IHR5cGVvZiBhcmc1ID09PSAnZnVuY3Rpb24nID8gYXJnNSA6IGNiO1xyXG4gICAgfVxyXG5cclxuICAgIHZhciBuZXdDYiA9IHdyYXBDYihjYiwgMyk7XHJcbiAgICB0cnkge1xyXG4gICAgICBsZXQgZmlsZSA9IHRoaXMuZmQyZmlsZShmZCk7XHJcbiAgICAgIGlmIChwb3NpdGlvbiA9PSBudWxsKSB7XHJcbiAgICAgICAgcG9zaXRpb24gPSBmaWxlLmdldFBvcygpO1xyXG4gICAgICB9XHJcbiAgICAgIGZpbGUud3JpdGUoYnVmZmVyLCBvZmZzZXQsIGxlbmd0aCwgcG9zaXRpb24sIG5ld0NiKTtcclxuICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgbmV3Q2IoZSk7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBXcml0ZSBidWZmZXIgdG8gdGhlIGZpbGUgc3BlY2lmaWVkIGJ5IGBmZGAuXHJcbiAgICogTm90ZSB0aGF0IGl0IGlzIHVuc2FmZSB0byB1c2UgZnMud3JpdGUgbXVsdGlwbGUgdGltZXMgb24gdGhlIHNhbWUgZmlsZVxyXG4gICAqIHdpdGhvdXQgd2FpdGluZyBmb3IgaXQgdG8gcmV0dXJuLlxyXG4gICAqIEBwYXJhbSBbQnJvd3NlckZTLkZpbGVdIGZkXHJcbiAgICogQHBhcmFtIFtCcm93c2VyRlMubm9kZS5CdWZmZXJdIGJ1ZmZlciBCdWZmZXIgY29udGFpbmluZyB0aGUgZGF0YSB0byB3cml0ZSB0b1xyXG4gICAqICAgdGhlIGZpbGUuXHJcbiAgICogQHBhcmFtIFtOdW1iZXJdIG9mZnNldCBPZmZzZXQgaW4gdGhlIGJ1ZmZlciB0byBzdGFydCByZWFkaW5nIGRhdGEgZnJvbS5cclxuICAgKiBAcGFyYW0gW051bWJlcl0gbGVuZ3RoIFRoZSBhbW91bnQgb2YgYnl0ZXMgdG8gd3JpdGUgdG8gdGhlIGZpbGUuXHJcbiAgICogQHBhcmFtIFtOdW1iZXJdIHBvc2l0aW9uIE9mZnNldCBmcm9tIHRoZSBiZWdpbm5pbmcgb2YgdGhlIGZpbGUgd2hlcmUgdGhpc1xyXG4gICAqICAgZGF0YSBzaG91bGQgYmUgd3JpdHRlbi4gSWYgcG9zaXRpb24gaXMgbnVsbCwgdGhlIGRhdGEgd2lsbCBiZSB3cml0dGVuIGF0XHJcbiAgICogICB0aGUgY3VycmVudCBwb3NpdGlvbi5cclxuICAgKiBAcmV0dXJuIFtOdW1iZXJdXHJcbiAgICovXHJcbiAgcHVibGljIHdyaXRlU3luYyhmZDogbnVtYmVyLCBidWZmZXI6IEJ1ZmZlciwgb2Zmc2V0OiBudW1iZXIsIGxlbmd0aDogbnVtYmVyLCBwb3NpdGlvbj86IG51bWJlcik6IG51bWJlcjtcclxuICBwdWJsaWMgd3JpdGVTeW5jKGZkOiBudW1iZXIsIGRhdGE6IHN0cmluZywgcG9zaXRpb24/OiBudW1iZXIsIGVuY29kaW5nPzogc3RyaW5nKTogbnVtYmVyO1xyXG4gIHB1YmxpYyB3cml0ZVN5bmMoZmQ6IG51bWJlciwgYXJnMjogYW55LCBhcmczPzogYW55LCBhcmc0PzogYW55LCBhcmc1PzogYW55KTogbnVtYmVyIHtcclxuICAgIHZhciBidWZmZXI6IEJ1ZmZlciwgb2Zmc2V0OiBudW1iZXIgPSAwLCBsZW5ndGg6IG51bWJlciwgcG9zaXRpb246IG51bWJlcjtcclxuICAgIGlmICh0eXBlb2YgYXJnMiA9PT0gJ3N0cmluZycpIHtcclxuICAgICAgLy8gU2lnbmF0dXJlIDE6IChmZCwgc3RyaW5nLCBbcG9zaXRpb24/LCBbZW5jb2Rpbmc/XV0pXHJcbiAgICAgIHBvc2l0aW9uID0gdHlwZW9mIGFyZzMgPT09ICdudW1iZXInID8gYXJnMyA6IG51bGw7XHJcbiAgICAgIHZhciBlbmNvZGluZyA9IHR5cGVvZiBhcmc0ID09PSAnc3RyaW5nJyA/IGFyZzQgOiAndXRmOCc7XHJcbiAgICAgIG9mZnNldCA9IDA7XHJcbiAgICAgIGJ1ZmZlciA9IG5ldyBCdWZmZXIoYXJnMiwgZW5jb2RpbmcpO1xyXG4gICAgICBsZW5ndGggPSBidWZmZXIubGVuZ3RoO1xyXG4gICAgfSBlbHNlIHtcclxuICAgICAgLy8gU2lnbmF0dXJlIDI6IChmZCwgYnVmZmVyLCBvZmZzZXQsIGxlbmd0aCwgcG9zaXRpb24/KVxyXG4gICAgICBidWZmZXIgPSBhcmcyO1xyXG4gICAgICBvZmZzZXQgPSBhcmczO1xyXG4gICAgICBsZW5ndGggPSBhcmc0O1xyXG4gICAgICBwb3NpdGlvbiA9IHR5cGVvZiBhcmc1ID09PSAnbnVtYmVyJyA/IGFyZzUgOiBudWxsO1xyXG4gICAgfVxyXG5cclxuICAgIGxldCBmaWxlID0gdGhpcy5mZDJmaWxlKGZkKTtcclxuICAgIGlmIChwb3NpdGlvbiA9PSBudWxsKSB7XHJcbiAgICAgIHBvc2l0aW9uID0gZmlsZS5nZXRQb3MoKTtcclxuICAgIH1cclxuICAgIHJldHVybiBmaWxlLndyaXRlU3luYyhidWZmZXIsIG9mZnNldCwgbGVuZ3RoLCBwb3NpdGlvbik7XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBSZWFkIGRhdGEgZnJvbSB0aGUgZmlsZSBzcGVjaWZpZWQgYnkgYGZkYC5cclxuICAgKiBAcGFyYW0gW0Jyb3dzZXJGUy5GaWxlXSBmZFxyXG4gICAqIEBwYXJhbSBbQnJvd3NlckZTLm5vZGUuQnVmZmVyXSBidWZmZXIgVGhlIGJ1ZmZlciB0aGF0IHRoZSBkYXRhIHdpbGwgYmVcclxuICAgKiAgIHdyaXR0ZW4gdG8uXHJcbiAgICogQHBhcmFtIFtOdW1iZXJdIG9mZnNldCBUaGUgb2Zmc2V0IHdpdGhpbiB0aGUgYnVmZmVyIHdoZXJlIHdyaXRpbmcgd2lsbFxyXG4gICAqICAgc3RhcnQuXHJcbiAgICogQHBhcmFtIFtOdW1iZXJdIGxlbmd0aCBBbiBpbnRlZ2VyIHNwZWNpZnlpbmcgdGhlIG51bWJlciBvZiBieXRlcyB0byByZWFkLlxyXG4gICAqIEBwYXJhbSBbTnVtYmVyXSBwb3NpdGlvbiBBbiBpbnRlZ2VyIHNwZWNpZnlpbmcgd2hlcmUgdG8gYmVnaW4gcmVhZGluZyBmcm9tXHJcbiAgICogICBpbiB0aGUgZmlsZS4gSWYgcG9zaXRpb24gaXMgbnVsbCwgZGF0YSB3aWxsIGJlIHJlYWQgZnJvbSB0aGUgY3VycmVudCBmaWxlXHJcbiAgICogICBwb3NpdGlvbi5cclxuICAgKiBAcGFyYW0gW0Z1bmN0aW9uKEJyb3dzZXJGUy5BcGlFcnJvciwgTnVtYmVyLCBCcm93c2VyRlMubm9kZS5CdWZmZXIpXVxyXG4gICAqICAgY2FsbGJhY2sgVGhlIG51bWJlciBpcyB0aGUgbnVtYmVyIG9mIGJ5dGVzIHJlYWRcclxuICAgKi9cclxuICBwdWJsaWMgcmVhZChmZDogbnVtYmVyLCBsZW5ndGg6IG51bWJlciwgcG9zaXRpb246IG51bWJlciwgZW5jb2Rpbmc6IHN0cmluZywgY2I/OiAoZXJyOiBBcGlFcnJvciwgZGF0YT86IHN0cmluZywgYnl0ZXNSZWFkPzogbnVtYmVyKSA9PiB2b2lkKTogdm9pZDtcclxuICBwdWJsaWMgcmVhZChmZDogbnVtYmVyLCBidWZmZXI6IEJ1ZmZlciwgb2Zmc2V0OiBudW1iZXIsIGxlbmd0aDogbnVtYmVyLCBwb3NpdGlvbjogbnVtYmVyLCBjYj86IChlcnI6IEFwaUVycm9yLCBieXRlc1JlYWQ/OiBudW1iZXIsIGJ1ZmZlcj86IEJ1ZmZlcikgPT4gdm9pZCk6IHZvaWQ7XHJcbiAgcHVibGljIHJlYWQoZmQ6IG51bWJlciwgYXJnMjogYW55LCBhcmczOiBhbnksIGFyZzQ6IGFueSwgYXJnNT86IGFueSwgY2I6IChlcnI6IEFwaUVycm9yLCBhcmcyPzogYW55LCBhcmczPzogYW55KSA9PiB2b2lkID0gbm9wQ2IpOiB2b2lkIHtcclxuICAgIHZhciBwb3NpdGlvbjogbnVtYmVyLCBvZmZzZXQ6IG51bWJlciwgbGVuZ3RoOiBudW1iZXIsIGJ1ZmZlcjogQnVmZmVyLCBuZXdDYjogKGVycjogQXBpRXJyb3IsIGJ5dGVzUmVhZD86IG51bWJlciwgYnVmZmVyPzogQnVmZmVyKSA9PiB2b2lkO1xyXG4gICAgaWYgKHR5cGVvZiBhcmcyID09PSAnbnVtYmVyJykge1xyXG4gICAgICAvLyBsZWdhY3kgaW50ZXJmYWNlXHJcbiAgICAgIC8vIChmZCwgbGVuZ3RoLCBwb3NpdGlvbiwgZW5jb2RpbmcsIGNhbGxiYWNrKVxyXG4gICAgICBsZW5ndGggPSBhcmcyO1xyXG4gICAgICBwb3NpdGlvbiA9IGFyZzM7XHJcbiAgICAgIHZhciBlbmNvZGluZyA9IGFyZzQ7XHJcbiAgICAgIGNiID0gdHlwZW9mIGFyZzUgPT09ICdmdW5jdGlvbicgPyBhcmc1IDogY2I7XHJcbiAgICAgIG9mZnNldCA9IDA7XHJcbiAgICAgIGJ1ZmZlciA9IG5ldyBCdWZmZXIobGVuZ3RoKTtcclxuICAgICAgLy8gWFhYOiBJbmVmZmljaWVudC5cclxuICAgICAgLy8gV3JhcCB0aGUgY2Igc28gd2Ugc2hlbHRlciB1cHBlciBsYXllcnMgb2YgdGhlIEFQSSBmcm9tIHRoZXNlXHJcbiAgICAgIC8vIHNoZW5hbmlnYW5zLlxyXG4gICAgICBuZXdDYiA9IHdyYXBDYigoZnVuY3Rpb24oZXJyOiBhbnksIGJ5dGVzUmVhZDogbnVtYmVyLCBidWY6IEJ1ZmZlcikge1xyXG4gICAgICAgIGlmIChlcnIpIHtcclxuICAgICAgICAgIHJldHVybiBjYihlcnIpO1xyXG4gICAgICAgIH1cclxuICAgICAgICBjYihlcnIsIGJ1Zi50b1N0cmluZyhlbmNvZGluZyksIGJ5dGVzUmVhZCk7XHJcbiAgICAgIH0pLCAzKTtcclxuICAgIH0gZWxzZSB7XHJcbiAgICAgIGJ1ZmZlciA9IGFyZzI7XHJcbiAgICAgIG9mZnNldCA9IGFyZzM7XHJcbiAgICAgIGxlbmd0aCA9IGFyZzQ7XHJcbiAgICAgIHBvc2l0aW9uID0gYXJnNTtcclxuICAgICAgbmV3Q2IgPSB3cmFwQ2IoY2IsIDMpO1xyXG4gICAgfVxyXG5cclxuICAgIHRyeSB7XHJcbiAgICAgIGxldCBmaWxlID0gdGhpcy5mZDJmaWxlKGZkKTtcclxuICAgICAgaWYgKHBvc2l0aW9uID09IG51bGwpIHtcclxuICAgICAgICBwb3NpdGlvbiA9IGZpbGUuZ2V0UG9zKCk7XHJcbiAgICAgIH1cclxuICAgICAgZmlsZS5yZWFkKGJ1ZmZlciwgb2Zmc2V0LCBsZW5ndGgsIHBvc2l0aW9uLCBuZXdDYik7XHJcbiAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgIG5ld0NiKGUpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogUmVhZCBkYXRhIGZyb20gdGhlIGZpbGUgc3BlY2lmaWVkIGJ5IGBmZGAuXHJcbiAgICogQHBhcmFtIFtCcm93c2VyRlMuRmlsZV0gZmRcclxuICAgKiBAcGFyYW0gW0Jyb3dzZXJGUy5ub2RlLkJ1ZmZlcl0gYnVmZmVyIFRoZSBidWZmZXIgdGhhdCB0aGUgZGF0YSB3aWxsIGJlXHJcbiAgICogICB3cml0dGVuIHRvLlxyXG4gICAqIEBwYXJhbSBbTnVtYmVyXSBvZmZzZXQgVGhlIG9mZnNldCB3aXRoaW4gdGhlIGJ1ZmZlciB3aGVyZSB3cml0aW5nIHdpbGxcclxuICAgKiAgIHN0YXJ0LlxyXG4gICAqIEBwYXJhbSBbTnVtYmVyXSBsZW5ndGggQW4gaW50ZWdlciBzcGVjaWZ5aW5nIHRoZSBudW1iZXIgb2YgYnl0ZXMgdG8gcmVhZC5cclxuICAgKiBAcGFyYW0gW051bWJlcl0gcG9zaXRpb24gQW4gaW50ZWdlciBzcGVjaWZ5aW5nIHdoZXJlIHRvIGJlZ2luIHJlYWRpbmcgZnJvbVxyXG4gICAqICAgaW4gdGhlIGZpbGUuIElmIHBvc2l0aW9uIGlzIG51bGwsIGRhdGEgd2lsbCBiZSByZWFkIGZyb20gdGhlIGN1cnJlbnQgZmlsZVxyXG4gICAqICAgcG9zaXRpb24uXHJcbiAgICogQHJldHVybiBbTnVtYmVyXVxyXG4gICAqL1xyXG4gIHB1YmxpYyByZWFkU3luYyhmZDogbnVtYmVyLCBsZW5ndGg6IG51bWJlciwgcG9zaXRpb246IG51bWJlciwgZW5jb2Rpbmc6IHN0cmluZyk6IHN0cmluZztcclxuICBwdWJsaWMgcmVhZFN5bmMoZmQ6IG51bWJlciwgYnVmZmVyOiBCdWZmZXIsIG9mZnNldDogbnVtYmVyLCBsZW5ndGg6IG51bWJlciwgcG9zaXRpb246IG51bWJlcik6IG51bWJlcjtcclxuICBwdWJsaWMgcmVhZFN5bmMoZmQ6IG51bWJlciwgYXJnMjogYW55LCBhcmczOiBhbnksIGFyZzQ6IGFueSwgYXJnNT86IGFueSk6IGFueSB7XHJcbiAgICB2YXIgc2hlbmFuaWdhbnMgPSBmYWxzZTtcclxuICAgIHZhciBidWZmZXI6IEJ1ZmZlciwgb2Zmc2V0OiBudW1iZXIsIGxlbmd0aDogbnVtYmVyLCBwb3NpdGlvbjogbnVtYmVyO1xyXG4gICAgaWYgKHR5cGVvZiBhcmcyID09PSAnbnVtYmVyJykge1xyXG4gICAgICBsZW5ndGggPSBhcmcyO1xyXG4gICAgICBwb3NpdGlvbiA9IGFyZzM7XHJcbiAgICAgIHZhciBlbmNvZGluZyA9IGFyZzQ7XHJcbiAgICAgIG9mZnNldCA9IDA7XHJcbiAgICAgIGJ1ZmZlciA9IG5ldyBCdWZmZXIobGVuZ3RoKTtcclxuICAgICAgc2hlbmFuaWdhbnMgPSB0cnVlO1xyXG4gICAgfSBlbHNlIHtcclxuICAgICAgYnVmZmVyID0gYXJnMjtcclxuICAgICAgb2Zmc2V0ID0gYXJnMztcclxuICAgICAgbGVuZ3RoID0gYXJnNDtcclxuICAgICAgcG9zaXRpb24gPSBhcmc1O1xyXG4gICAgfVxyXG4gICAgbGV0IGZpbGUgPSB0aGlzLmZkMmZpbGUoZmQpO1xyXG4gICAgaWYgKHBvc2l0aW9uID09IG51bGwpIHtcclxuICAgICAgcG9zaXRpb24gPSBmaWxlLmdldFBvcygpO1xyXG4gICAgfVxyXG5cclxuICAgIHZhciBydiA9IGZpbGUucmVhZFN5bmMoYnVmZmVyLCBvZmZzZXQsIGxlbmd0aCwgcG9zaXRpb24pO1xyXG4gICAgaWYgKCFzaGVuYW5pZ2Fucykge1xyXG4gICAgICByZXR1cm4gcnY7XHJcbiAgICB9IGVsc2Uge1xyXG4gICAgICByZXR1cm4gW2J1ZmZlci50b1N0cmluZyhlbmNvZGluZyksIHJ2XTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIEFzeW5jaHJvbm91cyBgZmNob3duYC5cclxuICAgKiBAcGFyYW0gW0Jyb3dzZXJGUy5GaWxlXSBmZFxyXG4gICAqIEBwYXJhbSBbTnVtYmVyXSB1aWRcclxuICAgKiBAcGFyYW0gW051bWJlcl0gZ2lkXHJcbiAgICogQHBhcmFtIFtGdW5jdGlvbihCcm93c2VyRlMuQXBpRXJyb3IpXSBjYWxsYmFja1xyXG4gICAqL1xyXG4gIHB1YmxpYyBmY2hvd24oZmQ6IG51bWJlciwgdWlkOiBudW1iZXIsIGdpZDogbnVtYmVyLCBjYWxsYmFjazogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCA9IG5vcENiKTogdm9pZCB7XHJcbiAgICB2YXIgbmV3Q2IgPSB3cmFwQ2IoY2FsbGJhY2ssIDEpO1xyXG4gICAgdHJ5IHtcclxuICAgICAgdGhpcy5mZDJmaWxlKGZkKS5jaG93bih1aWQsIGdpZCwgbmV3Q2IpO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICBuZXdDYihlKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIFN5bmNocm9ub3VzIGBmY2hvd25gLlxyXG4gICAqIEBwYXJhbSBbQnJvd3NlckZTLkZpbGVdIGZkXHJcbiAgICogQHBhcmFtIFtOdW1iZXJdIHVpZFxyXG4gICAqIEBwYXJhbSBbTnVtYmVyXSBnaWRcclxuICAgKi9cclxuICBwdWJsaWMgZmNob3duU3luYyhmZDogbnVtYmVyLCB1aWQ6IG51bWJlciwgZ2lkOiBudW1iZXIpOiB2b2lkIHtcclxuICAgIHRoaXMuZmQyZmlsZShmZCkuY2hvd25TeW5jKHVpZCwgZ2lkKTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIEFzeW5jaHJvbm91cyBgZmNobW9kYC5cclxuICAgKiBAcGFyYW0gW0Jyb3dzZXJGUy5GaWxlXSBmZFxyXG4gICAqIEBwYXJhbSBbTnVtYmVyXSBtb2RlXHJcbiAgICogQHBhcmFtIFtGdW5jdGlvbihCcm93c2VyRlMuQXBpRXJyb3IpXSBjYWxsYmFja1xyXG4gICAqL1xyXG4gIHB1YmxpYyBmY2htb2QoZmQ6IG51bWJlciwgbW9kZTogc3RyaW5nIHwgbnVtYmVyLCBjYj86IChlPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHZhciBuZXdDYiA9IHdyYXBDYihjYiwgMSk7XHJcbiAgICB0cnkge1xyXG4gICAgICBsZXQgbnVtTW9kZSA9IHR5cGVvZiBtb2RlID09PSAnc3RyaW5nJyA/IHBhcnNlSW50KG1vZGUsIDgpIDogbW9kZTtcclxuICAgICAgdGhpcy5mZDJmaWxlKGZkKS5jaG1vZChudW1Nb2RlLCBuZXdDYik7XHJcbiAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgIG5ld0NiKGUpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogU3luY2hyb25vdXMgYGZjaG1vZGAuXHJcbiAgICogQHBhcmFtIFtCcm93c2VyRlMuRmlsZV0gZmRcclxuICAgKiBAcGFyYW0gW051bWJlcl0gbW9kZVxyXG4gICAqL1xyXG4gIHB1YmxpYyBmY2htb2RTeW5jKGZkOiBudW1iZXIsIG1vZGU6IG51bWJlciB8IHN0cmluZyk6IHZvaWQge1xyXG4gICAgbGV0IG51bU1vZGUgPSB0eXBlb2YgbW9kZSA9PT0gJ3N0cmluZycgPyBwYXJzZUludChtb2RlLCA4KSA6IG1vZGU7XHJcbiAgICB0aGlzLmZkMmZpbGUoZmQpLmNobW9kU3luYyhudW1Nb2RlKTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIENoYW5nZSB0aGUgZmlsZSB0aW1lc3RhbXBzIG9mIGEgZmlsZSByZWZlcmVuY2VkIGJ5IHRoZSBzdXBwbGllZCBmaWxlXHJcbiAgICogZGVzY3JpcHRvci5cclxuICAgKiBAcGFyYW0gW0Jyb3dzZXJGUy5GaWxlXSBmZFxyXG4gICAqIEBwYXJhbSBbRGF0ZV0gYXRpbWVcclxuICAgKiBAcGFyYW0gW0RhdGVdIG10aW1lXHJcbiAgICogQHBhcmFtIFtGdW5jdGlvbihCcm93c2VyRlMuQXBpRXJyb3IpXSBjYWxsYmFja1xyXG4gICAqL1xyXG4gIHB1YmxpYyBmdXRpbWVzKGZkOiBudW1iZXIsIGF0aW1lOiBudW1iZXIsIG10aW1lOiBudW1iZXIsIGNiOiAoZT86IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZDtcclxuICBwdWJsaWMgZnV0aW1lcyhmZDogbnVtYmVyLCBhdGltZTogRGF0ZSwgbXRpbWU6IERhdGUsIGNiOiAoZT86IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZDtcclxuICBwdWJsaWMgZnV0aW1lcyhmZDogbnVtYmVyLCBhdGltZTogYW55LCBtdGltZTogYW55LCBjYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCA9IG5vcENiKTogdm9pZCB7XHJcbiAgICB2YXIgbmV3Q2IgPSB3cmFwQ2IoY2IsIDEpO1xyXG4gICAgdHJ5IHtcclxuICAgICAgbGV0IGZpbGUgPSB0aGlzLmZkMmZpbGUoZmQpO1xyXG4gICAgICBpZiAodHlwZW9mIGF0aW1lID09PSAnbnVtYmVyJykge1xyXG4gICAgICAgIGF0aW1lID0gbmV3IERhdGUoYXRpbWUgKiAxMDAwKTtcclxuICAgICAgfVxyXG4gICAgICBpZiAodHlwZW9mIG10aW1lID09PSAnbnVtYmVyJykge1xyXG4gICAgICAgIG10aW1lID0gbmV3IERhdGUobXRpbWUgKiAxMDAwKTtcclxuICAgICAgfVxyXG4gICAgICBmaWxlLnV0aW1lcyhhdGltZSwgbXRpbWUsIG5ld0NiKTtcclxuICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgbmV3Q2IoZSk7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBDaGFuZ2UgdGhlIGZpbGUgdGltZXN0YW1wcyBvZiBhIGZpbGUgcmVmZXJlbmNlZCBieSB0aGUgc3VwcGxpZWQgZmlsZVxyXG4gICAqIGRlc2NyaXB0b3IuXHJcbiAgICogQHBhcmFtIFtCcm93c2VyRlMuRmlsZV0gZmRcclxuICAgKiBAcGFyYW0gW0RhdGVdIGF0aW1lXHJcbiAgICogQHBhcmFtIFtEYXRlXSBtdGltZVxyXG4gICAqL1xyXG4gIHB1YmxpYyBmdXRpbWVzU3luYyhmZDogbnVtYmVyLCBhdGltZTogbnVtYmVyIHwgRGF0ZSwgbXRpbWU6IG51bWJlciB8IERhdGUpOiB2b2lkIHtcclxuICAgIHRoaXMuZmQyZmlsZShmZCkudXRpbWVzU3luYyhub3JtYWxpemVUaW1lKGF0aW1lKSwgbm9ybWFsaXplVGltZShtdGltZSkpO1xyXG4gIH1cclxuXHJcbiAgLy8gRElSRUNUT1JZLU9OTFkgTUVUSE9EU1xyXG5cclxuICAvKipcclxuICAgKiBBc3luY2hyb25vdXMgYHJtZGlyYC5cclxuICAgKiBAcGFyYW0gW1N0cmluZ10gcGF0aFxyXG4gICAqIEBwYXJhbSBbRnVuY3Rpb24oQnJvd3NlckZTLkFwaUVycm9yKV0gY2FsbGJhY2tcclxuICAgKi9cclxuICBwdWJsaWMgcm1kaXIocGF0aDogc3RyaW5nLCBjYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCA9IG5vcENiKTogdm9pZCB7XHJcbiAgICB2YXIgbmV3Q2IgPSB3cmFwQ2IoY2IsIDEpO1xyXG4gICAgdHJ5IHtcclxuICAgICAgcGF0aCA9IG5vcm1hbGl6ZVBhdGgocGF0aCk7XHJcbiAgICAgIHRoaXMucm9vdC5ybWRpcihwYXRoLCBuZXdDYik7XHJcbiAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgIG5ld0NiKGUpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogU3luY2hyb25vdXMgYHJtZGlyYC5cclxuICAgKiBAcGFyYW0gW1N0cmluZ10gcGF0aFxyXG4gICAqL1xyXG4gIHB1YmxpYyBybWRpclN5bmMocGF0aDogc3RyaW5nKTogdm9pZCB7XHJcbiAgICBwYXRoID0gbm9ybWFsaXplUGF0aChwYXRoKTtcclxuICAgIHJldHVybiB0aGlzLnJvb3Qucm1kaXJTeW5jKHBhdGgpO1xyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogQXN5bmNocm9ub3VzIGBta2RpcmAuXHJcbiAgICogQHBhcmFtIFtTdHJpbmddIHBhdGhcclxuICAgKiBAcGFyYW0gW051bWJlcj9dIG1vZGUgZGVmYXVsdHMgdG8gYDA3NzdgXHJcbiAgICogQHBhcmFtIFtGdW5jdGlvbihCcm93c2VyRlMuQXBpRXJyb3IpXSBjYWxsYmFja1xyXG4gICAqL1xyXG4gIHB1YmxpYyBta2RpcihwYXRoOiBzdHJpbmcsIG1vZGU/OiBhbnksIGNiOiAoZT86IEFwaUVycm9yKSA9PiB2b2lkID0gbm9wQ2IpOiB2b2lkIHtcclxuICAgIGlmICh0eXBlb2YgbW9kZSA9PT0gJ2Z1bmN0aW9uJykge1xyXG4gICAgICBjYiA9IG1vZGU7XHJcbiAgICAgIG1vZGUgPSAweDFmZjtcclxuICAgIH1cclxuICAgIHZhciBuZXdDYiA9IHdyYXBDYihjYiwgMSk7XHJcbiAgICB0cnkge1xyXG4gICAgICBwYXRoID0gbm9ybWFsaXplUGF0aChwYXRoKTtcclxuICAgICAgdGhpcy5yb290Lm1rZGlyKHBhdGgsIG1vZGUsIG5ld0NiKTtcclxuICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgbmV3Q2IoZSk7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBTeW5jaHJvbm91cyBgbWtkaXJgLlxyXG4gICAqIEBwYXJhbSBbU3RyaW5nXSBwYXRoXHJcbiAgICogQHBhcmFtIFtOdW1iZXI/XSBtb2RlIGRlZmF1bHRzIHRvIGAwNzc3YFxyXG4gICAqL1xyXG4gIHB1YmxpYyBta2RpclN5bmMocGF0aDogc3RyaW5nLCBtb2RlPzogbnVtYmVyIHwgc3RyaW5nKTogdm9pZCB7XHJcbiAgICB0aGlzLnJvb3QubWtkaXJTeW5jKG5vcm1hbGl6ZVBhdGgocGF0aCksIG5vcm1hbGl6ZU1vZGUobW9kZSwgMHgxZmYpKTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIEFzeW5jaHJvbm91cyBgcmVhZGRpcmAuIFJlYWRzIHRoZSBjb250ZW50cyBvZiBhIGRpcmVjdG9yeS5cclxuICAgKiBUaGUgY2FsbGJhY2sgZ2V0cyB0d28gYXJndW1lbnRzIGAoZXJyLCBmaWxlcylgIHdoZXJlIGBmaWxlc2AgaXMgYW4gYXJyYXkgb2ZcclxuICAgKiB0aGUgbmFtZXMgb2YgdGhlIGZpbGVzIGluIHRoZSBkaXJlY3RvcnkgZXhjbHVkaW5nIGAnLidgIGFuZCBgJy4uJ2AuXHJcbiAgICogQHBhcmFtIFtTdHJpbmddIHBhdGhcclxuICAgKiBAcGFyYW0gW0Z1bmN0aW9uKEJyb3dzZXJGUy5BcGlFcnJvciwgU3RyaW5nW10pXSBjYWxsYmFja1xyXG4gICAqL1xyXG4gIHB1YmxpYyByZWFkZGlyKHBhdGg6IHN0cmluZywgY2I6IChlcnI6IEFwaUVycm9yLCBmaWxlcz86IHN0cmluZ1tdKSA9PiB2b2lkID0gbm9wQ2IpOiB2b2lkIHtcclxuICAgIHZhciBuZXdDYiA9IDwoZXJyOiBBcGlFcnJvciwgZmlsZXM/OiBzdHJpbmdbXSkgPT4gdm9pZD4gd3JhcENiKGNiLCAyKTtcclxuICAgIHRyeSB7XHJcbiAgICAgIHBhdGggPSBub3JtYWxpemVQYXRoKHBhdGgpO1xyXG4gICAgICB0aGlzLnJvb3QucmVhZGRpcihwYXRoLCBuZXdDYik7XHJcbiAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgIG5ld0NiKGUpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogU3luY2hyb25vdXMgYHJlYWRkaXJgLiBSZWFkcyB0aGUgY29udGVudHMgb2YgYSBkaXJlY3RvcnkuXHJcbiAgICogQHBhcmFtIFtTdHJpbmddIHBhdGhcclxuICAgKiBAcmV0dXJuIFtTdHJpbmdbXV1cclxuICAgKi9cclxuICBwdWJsaWMgcmVhZGRpclN5bmMocGF0aDogc3RyaW5nKTogc3RyaW5nW10ge1xyXG4gICAgcGF0aCA9IG5vcm1hbGl6ZVBhdGgocGF0aCk7XHJcbiAgICByZXR1cm4gdGhpcy5yb290LnJlYWRkaXJTeW5jKHBhdGgpO1xyXG4gIH1cclxuXHJcbiAgLy8gU1lNTElOSyBNRVRIT0RTXHJcblxyXG4gIC8qKlxyXG4gICAqIEFzeW5jaHJvbm91cyBgbGlua2AuXHJcbiAgICogQHBhcmFtIFtTdHJpbmddIHNyY3BhdGhcclxuICAgKiBAcGFyYW0gW1N0cmluZ10gZHN0cGF0aFxyXG4gICAqIEBwYXJhbSBbRnVuY3Rpb24oQnJvd3NlckZTLkFwaUVycm9yKV0gY2FsbGJhY2tcclxuICAgKi9cclxuICBwdWJsaWMgbGluayhzcmNwYXRoOiBzdHJpbmcsIGRzdHBhdGg6IHN0cmluZywgY2I6IChlPzogQXBpRXJyb3IpID0+IHZvaWQgPSBub3BDYik6IHZvaWQge1xyXG4gICAgdmFyIG5ld0NiID0gd3JhcENiKGNiLCAxKTtcclxuICAgIHRyeSB7XHJcbiAgICAgIHNyY3BhdGggPSBub3JtYWxpemVQYXRoKHNyY3BhdGgpO1xyXG4gICAgICBkc3RwYXRoID0gbm9ybWFsaXplUGF0aChkc3RwYXRoKTtcclxuICAgICAgdGhpcy5yb290Lmxpbmsoc3JjcGF0aCwgZHN0cGF0aCwgbmV3Q2IpO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICBuZXdDYihlKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIFN5bmNocm9ub3VzIGBsaW5rYC5cclxuICAgKiBAcGFyYW0gW1N0cmluZ10gc3JjcGF0aFxyXG4gICAqIEBwYXJhbSBbU3RyaW5nXSBkc3RwYXRoXHJcbiAgICovXHJcbiAgcHVibGljIGxpbmtTeW5jKHNyY3BhdGg6IHN0cmluZywgZHN0cGF0aDogc3RyaW5nKTogdm9pZCB7XHJcbiAgICBzcmNwYXRoID0gbm9ybWFsaXplUGF0aChzcmNwYXRoKTtcclxuICAgIGRzdHBhdGggPSBub3JtYWxpemVQYXRoKGRzdHBhdGgpO1xyXG4gICAgcmV0dXJuIHRoaXMucm9vdC5saW5rU3luYyhzcmNwYXRoLCBkc3RwYXRoKTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIEFzeW5jaHJvbm91cyBgc3ltbGlua2AuXHJcbiAgICogQHBhcmFtIFtTdHJpbmddIHNyY3BhdGhcclxuICAgKiBAcGFyYW0gW1N0cmluZ10gZHN0cGF0aFxyXG4gICAqIEBwYXJhbSBbU3RyaW5nP10gdHlwZSBjYW4gYmUgZWl0aGVyIGAnZGlyJ2Agb3IgYCdmaWxlJ2AgKGRlZmF1bHQgaXMgYCdmaWxlJ2ApXHJcbiAgICogQHBhcmFtIFtGdW5jdGlvbihCcm93c2VyRlMuQXBpRXJyb3IpXSBjYWxsYmFja1xyXG4gICAqL1xyXG4gIHB1YmxpYyBzeW1saW5rKHNyY3BhdGg6IHN0cmluZywgZHN0cGF0aDogc3RyaW5nLCBjYj86IChlPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkO1xyXG4gIHB1YmxpYyBzeW1saW5rKHNyY3BhdGg6IHN0cmluZywgZHN0cGF0aDogc3RyaW5nLCB0eXBlPzogc3RyaW5nLCBjYj86IChlPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkO1xyXG4gIHB1YmxpYyBzeW1saW5rKHNyY3BhdGg6IHN0cmluZywgZHN0cGF0aDogc3RyaW5nLCBhcmczPzogYW55LCBjYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCA9IG5vcENiKTogdm9pZCB7XHJcbiAgICB2YXIgdHlwZSA9IHR5cGVvZiBhcmczID09PSAnc3RyaW5nJyA/IGFyZzMgOiAnZmlsZSc7XHJcbiAgICBjYiA9IHR5cGVvZiBhcmczID09PSAnZnVuY3Rpb24nID8gYXJnMyA6IGNiO1xyXG4gICAgdmFyIG5ld0NiID0gd3JhcENiKGNiLCAxKTtcclxuICAgIHRyeSB7XHJcbiAgICAgIGlmICh0eXBlICE9PSAnZmlsZScgJiYgdHlwZSAhPT0gJ2RpcicpIHtcclxuICAgICAgICByZXR1cm4gbmV3Q2IobmV3IEFwaUVycm9yKEVycm9yQ29kZS5FSU5WQUwsIFwiSW52YWxpZCB0eXBlOiBcIiArIHR5cGUpKTtcclxuICAgICAgfVxyXG4gICAgICBzcmNwYXRoID0gbm9ybWFsaXplUGF0aChzcmNwYXRoKTtcclxuICAgICAgZHN0cGF0aCA9IG5vcm1hbGl6ZVBhdGgoZHN0cGF0aCk7XHJcbiAgICAgIHRoaXMucm9vdC5zeW1saW5rKHNyY3BhdGgsIGRzdHBhdGgsIHR5cGUsIG5ld0NiKTtcclxuICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgbmV3Q2IoZSk7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBTeW5jaHJvbm91cyBgc3ltbGlua2AuXHJcbiAgICogQHBhcmFtIFtTdHJpbmddIHNyY3BhdGhcclxuICAgKiBAcGFyYW0gW1N0cmluZ10gZHN0cGF0aFxyXG4gICAqIEBwYXJhbSBbU3RyaW5nP10gdHlwZSBjYW4gYmUgZWl0aGVyIGAnZGlyJ2Agb3IgYCdmaWxlJ2AgKGRlZmF1bHQgaXMgYCdmaWxlJ2ApXHJcbiAgICovXHJcbiAgcHVibGljIHN5bWxpbmtTeW5jKHNyY3BhdGg6IHN0cmluZywgZHN0cGF0aDogc3RyaW5nLCB0eXBlPzogc3RyaW5nKTogdm9pZCB7XHJcbiAgICBpZiAodHlwZSA9PSBudWxsKSB7XHJcbiAgICAgIHR5cGUgPSAnZmlsZSc7XHJcbiAgICB9IGVsc2UgaWYgKHR5cGUgIT09ICdmaWxlJyAmJiB0eXBlICE9PSAnZGlyJykge1xyXG4gICAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVJTlZBTCwgXCJJbnZhbGlkIHR5cGU6IFwiICsgdHlwZSk7XHJcbiAgICB9XHJcbiAgICBzcmNwYXRoID0gbm9ybWFsaXplUGF0aChzcmNwYXRoKTtcclxuICAgIGRzdHBhdGggPSBub3JtYWxpemVQYXRoKGRzdHBhdGgpO1xyXG4gICAgcmV0dXJuIHRoaXMucm9vdC5zeW1saW5rU3luYyhzcmNwYXRoLCBkc3RwYXRoLCB0eXBlKTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIEFzeW5jaHJvbm91cyByZWFkbGluay5cclxuICAgKiBAcGFyYW0gW1N0cmluZ10gcGF0aFxyXG4gICAqIEBwYXJhbSBbRnVuY3Rpb24oQnJvd3NlckZTLkFwaUVycm9yLCBTdHJpbmcpXSBjYWxsYmFja1xyXG4gICAqL1xyXG4gIHB1YmxpYyByZWFkbGluayhwYXRoOiBzdHJpbmcsIGNiOiAoZXJyOiBBcGlFcnJvciwgbGlua1N0cmluZz86IHN0cmluZykgPT4gYW55ID0gbm9wQ2IpOiB2b2lkIHtcclxuICAgIHZhciBuZXdDYiA9IHdyYXBDYihjYiwgMik7XHJcbiAgICB0cnkge1xyXG4gICAgICBwYXRoID0gbm9ybWFsaXplUGF0aChwYXRoKTtcclxuICAgICAgdGhpcy5yb290LnJlYWRsaW5rKHBhdGgsIG5ld0NiKTtcclxuICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgbmV3Q2IoZSk7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBTeW5jaHJvbm91cyByZWFkbGluay5cclxuICAgKiBAcGFyYW0gW1N0cmluZ10gcGF0aFxyXG4gICAqIEByZXR1cm4gW1N0cmluZ11cclxuICAgKi9cclxuICBwdWJsaWMgcmVhZGxpbmtTeW5jKHBhdGg6IHN0cmluZyk6IHN0cmluZyB7XHJcbiAgICBwYXRoID0gbm9ybWFsaXplUGF0aChwYXRoKTtcclxuICAgIHJldHVybiB0aGlzLnJvb3QucmVhZGxpbmtTeW5jKHBhdGgpO1xyXG4gIH1cclxuXHJcbiAgLy8gUFJPUEVSVFkgT1BFUkFUSU9OU1xyXG5cclxuICAvKipcclxuICAgKiBBc3luY2hyb25vdXMgYGNob3duYC5cclxuICAgKiBAcGFyYW0gW1N0cmluZ10gcGF0aFxyXG4gICAqIEBwYXJhbSBbTnVtYmVyXSB1aWRcclxuICAgKiBAcGFyYW0gW051bWJlcl0gZ2lkXHJcbiAgICogQHBhcmFtIFtGdW5jdGlvbihCcm93c2VyRlMuQXBpRXJyb3IpXSBjYWxsYmFja1xyXG4gICAqL1xyXG4gIHB1YmxpYyBjaG93bihwYXRoOiBzdHJpbmcsIHVpZDogbnVtYmVyLCBnaWQ6IG51bWJlciwgY2I6IChlPzogQXBpRXJyb3IpID0+IHZvaWQgPSBub3BDYik6IHZvaWQge1xyXG4gICAgdmFyIG5ld0NiID0gd3JhcENiKGNiLCAxKTtcclxuICAgIHRyeSB7XHJcbiAgICAgIHBhdGggPSBub3JtYWxpemVQYXRoKHBhdGgpO1xyXG4gICAgICB0aGlzLnJvb3QuY2hvd24ocGF0aCwgZmFsc2UsIHVpZCwgZ2lkLCBuZXdDYik7XHJcbiAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgIG5ld0NiKGUpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogU3luY2hyb25vdXMgYGNob3duYC5cclxuICAgKiBAcGFyYW0gW1N0cmluZ10gcGF0aFxyXG4gICAqIEBwYXJhbSBbTnVtYmVyXSB1aWRcclxuICAgKiBAcGFyYW0gW051bWJlcl0gZ2lkXHJcbiAgICovXHJcbiAgcHVibGljIGNob3duU3luYyhwYXRoOiBzdHJpbmcsIHVpZDogbnVtYmVyLCBnaWQ6IG51bWJlcik6IHZvaWQge1xyXG4gICAgcGF0aCA9IG5vcm1hbGl6ZVBhdGgocGF0aCk7XHJcbiAgICB0aGlzLnJvb3QuY2hvd25TeW5jKHBhdGgsIGZhbHNlLCB1aWQsIGdpZCk7XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBBc3luY2hyb25vdXMgYGxjaG93bmAuXHJcbiAgICogQHBhcmFtIFtTdHJpbmddIHBhdGhcclxuICAgKiBAcGFyYW0gW051bWJlcl0gdWlkXHJcbiAgICogQHBhcmFtIFtOdW1iZXJdIGdpZFxyXG4gICAqIEBwYXJhbSBbRnVuY3Rpb24oQnJvd3NlckZTLkFwaUVycm9yKV0gY2FsbGJhY2tcclxuICAgKi9cclxuICBwdWJsaWMgbGNob3duKHBhdGg6IHN0cmluZywgdWlkOiBudW1iZXIsIGdpZDogbnVtYmVyLCBjYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCA9IG5vcENiKTogdm9pZCB7XHJcbiAgICB2YXIgbmV3Q2IgPSB3cmFwQ2IoY2IsIDEpO1xyXG4gICAgdHJ5IHtcclxuICAgICAgcGF0aCA9IG5vcm1hbGl6ZVBhdGgocGF0aCk7XHJcbiAgICAgIHRoaXMucm9vdC5jaG93bihwYXRoLCB0cnVlLCB1aWQsIGdpZCwgbmV3Q2IpO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICBuZXdDYihlKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIFN5bmNocm9ub3VzIGBsY2hvd25gLlxyXG4gICAqIEBwYXJhbSBbU3RyaW5nXSBwYXRoXHJcbiAgICogQHBhcmFtIFtOdW1iZXJdIHVpZFxyXG4gICAqIEBwYXJhbSBbTnVtYmVyXSBnaWRcclxuICAgKi9cclxuICBwdWJsaWMgbGNob3duU3luYyhwYXRoOiBzdHJpbmcsIHVpZDogbnVtYmVyLCBnaWQ6IG51bWJlcik6IHZvaWQge1xyXG4gICAgcGF0aCA9IG5vcm1hbGl6ZVBhdGgocGF0aCk7XHJcbiAgICB0aGlzLnJvb3QuY2hvd25TeW5jKHBhdGgsIHRydWUsIHVpZCwgZ2lkKTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIEFzeW5jaHJvbm91cyBgY2htb2RgLlxyXG4gICAqIEBwYXJhbSBbU3RyaW5nXSBwYXRoXHJcbiAgICogQHBhcmFtIFtOdW1iZXJdIG1vZGVcclxuICAgKiBAcGFyYW0gW0Z1bmN0aW9uKEJyb3dzZXJGUy5BcGlFcnJvcildIGNhbGxiYWNrXHJcbiAgICovXHJcbiAgcHVibGljIGNobW9kKHBhdGg6IHN0cmluZywgbW9kZTogbnVtYmVyIHwgc3RyaW5nLCBjYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCA9IG5vcENiKTogdm9pZCB7XHJcbiAgICB2YXIgbmV3Q2IgPSB3cmFwQ2IoY2IsIDEpO1xyXG4gICAgdHJ5IHtcclxuICAgICAgbGV0IG51bU1vZGUgPSBub3JtYWxpemVNb2RlKG1vZGUsIC0xKTtcclxuICAgICAgaWYgKG51bU1vZGUgPCAwKSB7XHJcbiAgICAgICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FSU5WQUwsIGBJbnZhbGlkIG1vZGUuYCk7XHJcbiAgICAgIH1cclxuICAgICAgdGhpcy5yb290LmNobW9kKG5vcm1hbGl6ZVBhdGgocGF0aCksIGZhbHNlLCBudW1Nb2RlLCBuZXdDYik7XHJcbiAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgIG5ld0NiKGUpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogU3luY2hyb25vdXMgYGNobW9kYC5cclxuICAgKiBAcGFyYW0gW1N0cmluZ10gcGF0aFxyXG4gICAqIEBwYXJhbSBbTnVtYmVyXSBtb2RlXHJcbiAgICovXHJcbiAgcHVibGljIGNobW9kU3luYyhwYXRoOiBzdHJpbmcsIG1vZGU6IHN0cmluZ3xudW1iZXIpOiB2b2lkIHtcclxuICAgIGxldCBudW1Nb2RlID0gbm9ybWFsaXplTW9kZShtb2RlLCAtMSk7XHJcbiAgICBpZiAobnVtTW9kZSA8IDApIHtcclxuICAgICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FSU5WQUwsIGBJbnZhbGlkIG1vZGUuYCk7XHJcbiAgICB9XHJcbiAgICBwYXRoID0gbm9ybWFsaXplUGF0aChwYXRoKTtcclxuICAgIHRoaXMucm9vdC5jaG1vZFN5bmMocGF0aCwgZmFsc2UsIG51bU1vZGUpO1xyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogQXN5bmNocm9ub3VzIGBsY2htb2RgLlxyXG4gICAqIEBwYXJhbSBbU3RyaW5nXSBwYXRoXHJcbiAgICogQHBhcmFtIFtOdW1iZXJdIG1vZGVcclxuICAgKiBAcGFyYW0gW0Z1bmN0aW9uKEJyb3dzZXJGUy5BcGlFcnJvcildIGNhbGxiYWNrXHJcbiAgICovXHJcbiAgcHVibGljIGxjaG1vZChwYXRoOiBzdHJpbmcsIG1vZGU6IG51bWJlcnxzdHJpbmcsIGNiOiBGdW5jdGlvbiA9IG5vcENiKTogdm9pZCB7XHJcbiAgICB2YXIgbmV3Q2IgPSB3cmFwQ2IoY2IsIDEpO1xyXG4gICAgdHJ5IHtcclxuICAgICAgbGV0IG51bU1vZGUgPSBub3JtYWxpemVNb2RlKG1vZGUsIC0xKTtcclxuICAgICAgaWYgKG51bU1vZGUgPCAwKSB7XHJcbiAgICAgICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FSU5WQUwsIGBJbnZhbGlkIG1vZGUuYCk7XHJcbiAgICAgIH1cclxuICAgICAgdGhpcy5yb290LmNobW9kKG5vcm1hbGl6ZVBhdGgocGF0aCksIHRydWUsIG51bU1vZGUsIG5ld0NiKTtcclxuICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgbmV3Q2IoZSk7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBTeW5jaHJvbm91cyBgbGNobW9kYC5cclxuICAgKiBAcGFyYW0gW1N0cmluZ10gcGF0aFxyXG4gICAqIEBwYXJhbSBbTnVtYmVyXSBtb2RlXHJcbiAgICovXHJcbiAgcHVibGljIGxjaG1vZFN5bmMocGF0aDogc3RyaW5nLCBtb2RlOiBudW1iZXJ8c3RyaW5nKTogdm9pZCB7XHJcbiAgICBsZXQgbnVtTW9kZSA9IG5vcm1hbGl6ZU1vZGUobW9kZSwgLTEpO1xyXG4gICAgaWYgKG51bU1vZGUgPCAxKSB7XHJcbiAgICAgIHRocm93IG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRUlOVkFMLCBgSW52YWxpZCBtb2RlLmApO1xyXG4gICAgfVxyXG4gICAgdGhpcy5yb290LmNobW9kU3luYyhub3JtYWxpemVQYXRoKHBhdGgpLCB0cnVlLCBudW1Nb2RlKTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIENoYW5nZSBmaWxlIHRpbWVzdGFtcHMgb2YgdGhlIGZpbGUgcmVmZXJlbmNlZCBieSB0aGUgc3VwcGxpZWQgcGF0aC5cclxuICAgKiBAcGFyYW0gW1N0cmluZ10gcGF0aFxyXG4gICAqIEBwYXJhbSBbRGF0ZV0gYXRpbWVcclxuICAgKiBAcGFyYW0gW0RhdGVdIG10aW1lXHJcbiAgICogQHBhcmFtIFtGdW5jdGlvbihCcm93c2VyRlMuQXBpRXJyb3IpXSBjYWxsYmFja1xyXG4gICAqL1xyXG4gIHB1YmxpYyB1dGltZXMocGF0aDogc3RyaW5nLCBhdGltZTogbnVtYmVyfERhdGUsIG10aW1lOiBudW1iZXJ8RGF0ZSwgY2I6IChlPzogQXBpRXJyb3IpID0+IHZvaWQgPSBub3BDYik6IHZvaWQge1xyXG4gICAgdmFyIG5ld0NiID0gd3JhcENiKGNiLCAxKTtcclxuICAgIHRyeSB7XHJcbiAgICAgIHRoaXMucm9vdC51dGltZXMobm9ybWFsaXplUGF0aChwYXRoKSwgbm9ybWFsaXplVGltZShhdGltZSksIG5vcm1hbGl6ZVRpbWUobXRpbWUpLCBuZXdDYik7XHJcbiAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgIG5ld0NiKGUpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogQ2hhbmdlIGZpbGUgdGltZXN0YW1wcyBvZiB0aGUgZmlsZSByZWZlcmVuY2VkIGJ5IHRoZSBzdXBwbGllZCBwYXRoLlxyXG4gICAqIEBwYXJhbSBbU3RyaW5nXSBwYXRoXHJcbiAgICogQHBhcmFtIFtEYXRlXSBhdGltZVxyXG4gICAqIEBwYXJhbSBbRGF0ZV0gbXRpbWVcclxuICAgKi9cclxuICBwdWJsaWMgdXRpbWVzU3luYyhwYXRoOiBzdHJpbmcsIGF0aW1lOiBudW1iZXJ8RGF0ZSwgbXRpbWU6IG51bWJlcnxEYXRlKTogdm9pZCB7XHJcbiAgICB0aGlzLnJvb3QudXRpbWVzU3luYyhub3JtYWxpemVQYXRoKHBhdGgpLCBub3JtYWxpemVUaW1lKGF0aW1lKSwgbm9ybWFsaXplVGltZShtdGltZSkpO1xyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogQXN5bmNocm9ub3VzIGByZWFscGF0aGAuIFRoZSBjYWxsYmFjayBnZXRzIHR3byBhcmd1bWVudHNcclxuICAgKiBgKGVyciwgcmVzb2x2ZWRQYXRoKWAuIE1heSB1c2UgYHByb2Nlc3MuY3dkYCB0byByZXNvbHZlIHJlbGF0aXZlIHBhdGhzLlxyXG4gICAqXHJcbiAgICogQGV4YW1wbGUgVXNhZ2UgZXhhbXBsZVxyXG4gICAqICAgdmFyIGNhY2hlID0geycvZXRjJzonL3ByaXZhdGUvZXRjJ307XHJcbiAgICogICBmcy5yZWFscGF0aCgnL2V0Yy9wYXNzd2QnLCBjYWNoZSwgZnVuY3Rpb24gKGVyciwgcmVzb2x2ZWRQYXRoKSB7XHJcbiAgICogICAgIGlmIChlcnIpIHRocm93IGVycjtcclxuICAgKiAgICAgY29uc29sZS5sb2cocmVzb2x2ZWRQYXRoKTtcclxuICAgKiAgIH0pO1xyXG4gICAqXHJcbiAgICogQHBhcmFtIFtTdHJpbmddIHBhdGhcclxuICAgKiBAcGFyYW0gW09iamVjdD9dIGNhY2hlIEFuIG9iamVjdCBsaXRlcmFsIG9mIG1hcHBlZCBwYXRocyB0aGF0IGNhbiBiZSB1c2VkIHRvXHJcbiAgICogICBmb3JjZSBhIHNwZWNpZmljIHBhdGggcmVzb2x1dGlvbiBvciBhdm9pZCBhZGRpdGlvbmFsIGBmcy5zdGF0YCBjYWxscyBmb3JcclxuICAgKiAgIGtub3duIHJlYWwgcGF0aHMuXHJcbiAgICogQHBhcmFtIFtGdW5jdGlvbihCcm93c2VyRlMuQXBpRXJyb3IsIFN0cmluZyldIGNhbGxiYWNrXHJcbiAgICovXHJcbiAgcHVibGljIHJlYWxwYXRoKHBhdGg6IHN0cmluZywgY2I/OiAoZXJyOiBBcGlFcnJvciwgcmVzb2x2ZWRQYXRoPzogc3RyaW5nKSA9PmFueSk6IHZvaWQ7XHJcbiAgcHVibGljIHJlYWxwYXRoKHBhdGg6IHN0cmluZywgY2FjaGU6IHtbcGF0aDogc3RyaW5nXTogc3RyaW5nfSwgY2I6IChlcnI6IEFwaUVycm9yLCByZXNvbHZlZFBhdGg/OiBzdHJpbmcpID0+YW55KTogdm9pZDtcclxuICBwdWJsaWMgcmVhbHBhdGgocGF0aDogc3RyaW5nLCBhcmcyPzogYW55LCBjYjogKGVycjogQXBpRXJyb3IsIHJlc29sdmVkUGF0aD86IHN0cmluZykgPT4gYW55ID0gbm9wQ2IpOiB2b2lkIHtcclxuICAgIHZhciBjYWNoZSA9IHR5cGVvZiBhcmcyID09PSAnb2JqZWN0JyA/IGFyZzIgOiB7fTtcclxuICAgIGNiID0gdHlwZW9mIGFyZzIgPT09ICdmdW5jdGlvbicgPyBhcmcyIDogbm9wQ2I7XHJcbiAgICB2YXIgbmV3Q2IgPSA8KGVycjogQXBpRXJyb3IsIHJlc29sdmVkUGF0aD86IHN0cmluZykgPT5hbnk+IHdyYXBDYihjYiwgMik7XHJcbiAgICB0cnkge1xyXG4gICAgICBwYXRoID0gbm9ybWFsaXplUGF0aChwYXRoKTtcclxuICAgICAgdGhpcy5yb290LnJlYWxwYXRoKHBhdGgsIGNhY2hlLCBuZXdDYik7XHJcbiAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgIG5ld0NiKGUpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogU3luY2hyb25vdXMgYHJlYWxwYXRoYC5cclxuICAgKiBAcGFyYW0gW1N0cmluZ10gcGF0aFxyXG4gICAqIEBwYXJhbSBbT2JqZWN0P10gY2FjaGUgQW4gb2JqZWN0IGxpdGVyYWwgb2YgbWFwcGVkIHBhdGhzIHRoYXQgY2FuIGJlIHVzZWQgdG9cclxuICAgKiAgIGZvcmNlIGEgc3BlY2lmaWMgcGF0aCByZXNvbHV0aW9uIG9yIGF2b2lkIGFkZGl0aW9uYWwgYGZzLnN0YXRgIGNhbGxzIGZvclxyXG4gICAqICAga25vd24gcmVhbCBwYXRocy5cclxuICAgKiBAcmV0dXJuIFtTdHJpbmddXHJcbiAgICovXHJcbiAgcHVibGljIHJlYWxwYXRoU3luYyhwYXRoOiBzdHJpbmcsIGNhY2hlOiB7W3BhdGg6IHN0cmluZ106IHN0cmluZ30gPSB7fSk6IHN0cmluZyB7XHJcbiAgICBwYXRoID0gbm9ybWFsaXplUGF0aChwYXRoKTtcclxuICAgIHJldHVybiB0aGlzLnJvb3QucmVhbHBhdGhTeW5jKHBhdGgsIGNhY2hlKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyB3YXRjaEZpbGUoZmlsZW5hbWU6IHN0cmluZywgbGlzdGVuZXI6IChjdXJyOiBTdGF0cywgcHJldjogU3RhdHMpID0+IHZvaWQpOiB2b2lkO1xyXG4gIHB1YmxpYyB3YXRjaEZpbGUoZmlsZW5hbWU6IHN0cmluZywgb3B0aW9uczogeyBwZXJzaXN0ZW50PzogYm9vbGVhbjsgaW50ZXJ2YWw/OiBudW1iZXI7IH0sIGxpc3RlbmVyOiAoY3VycjogU3RhdHMsIHByZXY6IFN0YXRzKSA9PiB2b2lkKTogdm9pZDtcclxuICBwdWJsaWMgd2F0Y2hGaWxlKGZpbGVuYW1lOiBzdHJpbmcsIGFyZzI6IGFueSwgbGlzdGVuZXI6IChjdXJyOiBTdGF0cywgcHJldjogU3RhdHMpID0+IHZvaWQgPSBub3BDYik6IHZvaWQge1xyXG4gICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FTk9UU1VQKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyB1bndhdGNoRmlsZShmaWxlbmFtZTogc3RyaW5nLCBsaXN0ZW5lcjogKGN1cnI6IFN0YXRzLCBwcmV2OiBTdGF0cykgPT4gdm9pZCA9IG5vcENiKTogdm9pZCB7XHJcbiAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVOT1RTVVApO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIHdhdGNoKGZpbGVuYW1lOiBzdHJpbmcsIGxpc3RlbmVyPzogKGV2ZW50OiBzdHJpbmcsIGZpbGVuYW1lOiBzdHJpbmcpID0+IGFueSk6IF9mcy5GU1dhdGNoZXI7XHJcbiAgcHVibGljIHdhdGNoKGZpbGVuYW1lOiBzdHJpbmcsIG9wdGlvbnM6IHsgcGVyc2lzdGVudD86IGJvb2xlYW47IH0sIGxpc3RlbmVyPzogKGV2ZW50OiBzdHJpbmcsIGZpbGVuYW1lOiBzdHJpbmcpID0+IGFueSk6IF9mcy5GU1dhdGNoZXI7XHJcbiAgcHVibGljIHdhdGNoKGZpbGVuYW1lOiBzdHJpbmcsIGFyZzI6IGFueSwgbGlzdGVuZXI6IChldmVudDogc3RyaW5nLCBmaWxlbmFtZTogc3RyaW5nKSA9PiBhbnkgPSBub3BDYik6IF9mcy5GU1dhdGNoZXIge1xyXG4gICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FTk9UU1VQKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBGX09LOiBudW1iZXIgPSAwO1xyXG4gIHB1YmxpYyBSX09LOiBudW1iZXIgPSA0O1xyXG4gIHB1YmxpYyBXX09LOiBudW1iZXIgPSAyO1xyXG4gIHB1YmxpYyBYX09LOiBudW1iZXIgPSAxO1xyXG5cclxuICBwdWJsaWMgYWNjZXNzKHBhdGg6IHN0cmluZywgY2FsbGJhY2s6IChlcnI6IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZDtcclxuICBwdWJsaWMgYWNjZXNzKHBhdGg6IHN0cmluZywgbW9kZTogbnVtYmVyLCBjYWxsYmFjazogKGVycjogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkO1xyXG4gIHB1YmxpYyBhY2Nlc3MocGF0aDogc3RyaW5nLCBhcmcyOiBhbnksIGNiOiAoZTogQXBpRXJyb3IpID0+IHZvaWQgPSBub3BDYik6IHZvaWQge1xyXG4gICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FTk9UU1VQKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBhY2Nlc3NTeW5jKHBhdGg6IHN0cmluZywgbW9kZT86IG51bWJlcik6IHZvaWQge1xyXG4gICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FTk9UU1VQKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBjcmVhdGVSZWFkU3RyZWFtKHBhdGg6IHN0cmluZywgb3B0aW9ucz86IHtcclxuICAgICAgICBmbGFncz86IHN0cmluZztcclxuICAgICAgICBlbmNvZGluZz86IHN0cmluZztcclxuICAgICAgICBmZD86IG51bWJlcjtcclxuICAgICAgICBtb2RlPzogbnVtYmVyO1xyXG4gICAgICAgIGF1dG9DbG9zZT86IGJvb2xlYW47XHJcbiAgICB9KTogX2ZzLlJlYWRTdHJlYW0ge1xyXG4gICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FTk9UU1VQKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBjcmVhdGVXcml0ZVN0cmVhbShwYXRoOiBzdHJpbmcsIG9wdGlvbnM/OiB7XHJcbiAgICAgICAgZmxhZ3M/OiBzdHJpbmc7XHJcbiAgICAgICAgZW5jb2Rpbmc/OiBzdHJpbmc7XHJcbiAgICAgICAgZmQ/OiBudW1iZXI7XHJcbiAgICAgICAgbW9kZT86IG51bWJlcjtcclxuICAgIH0pOiBfZnMuV3JpdGVTdHJlYW0ge1xyXG4gICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FTk9UU1VQKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBfd3JhcENiOiAoY2I6IEZ1bmN0aW9uLCBhcmdzOiBudW1iZXIpID0+IEZ1bmN0aW9uID0gd3JhcENiO1xyXG59XHJcblxyXG4vLyBUeXBlIGNoZWNraW5nLlxyXG52YXIgXzogdHlwZW9mIF9mcyA9IG5ldyBGUygpO1xyXG5cclxuZXhwb3J0IGludGVyZmFjZSBGU01vZHVsZSBleHRlbmRzIEZTIHtcclxuICAvKipcclxuICAgKiBSZXRyaWV2ZSB0aGUgRlMgb2JqZWN0IGJhY2tpbmcgdGhlIGZzIG1vZHVsZS5cclxuICAgKi9cclxuICBnZXRGU01vZHVsZSgpOiBGUztcclxuICAvKipcclxuICAgKiBTZXQgdGhlIEZTIG9iamVjdCBiYWNraW5nIHRoZSBmcyBtb2R1bGUuXHJcbiAgICovXHJcbiAgY2hhbmdlRlNNb2R1bGUobmV3RnM6IEZTKTogdm9pZDtcclxuICAvKipcclxuICAgKiBUaGUgRlMgY29uc3RydWN0b3IuXHJcbiAgICovXHJcbiAgRlM6IHR5cGVvZiBGUztcclxufVxyXG4iXX0=