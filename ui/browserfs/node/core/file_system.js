"use strict";
var __extends = (this && this.__extends) || function (d, b) {
    for (var p in b) if (b.hasOwnProperty(p)) d[p] = b[p];
    function __() { this.constructor = d; }
    d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
};
var api_error_1 = require('./api_error');
var file_flag_1 = require('./file_flag');
var path = require('path');
var BaseFileSystem = (function () {
    function BaseFileSystem() {
    }
    BaseFileSystem.prototype.supportsLinks = function () {
        return false;
    };
    BaseFileSystem.prototype.diskSpace = function (p, cb) {
        cb(0, 0);
    };
    BaseFileSystem.prototype.openFile = function (p, flag, cb) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    BaseFileSystem.prototype.createFile = function (p, flag, mode, cb) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    BaseFileSystem.prototype.open = function (p, flag, mode, cb) {
        var _this = this;
        var must_be_file = function (e, stats) {
            if (e) {
                switch (flag.pathNotExistsAction()) {
                    case file_flag_1.ActionType.CREATE_FILE:
                        return _this.stat(path.dirname(p), false, function (e, parentStats) {
                            if (e) {
                                cb(e);
                            }
                            else if (!parentStats.isDirectory()) {
                                cb(api_error_1.ApiError.ENOTDIR(path.dirname(p)));
                            }
                            else {
                                _this.createFile(p, flag, mode, cb);
                            }
                        });
                    case file_flag_1.ActionType.THROW_EXCEPTION:
                        return cb(api_error_1.ApiError.ENOENT(p));
                    default:
                        return cb(new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, 'Invalid FileFlag object.'));
                }
            }
            else {
                if (stats.isDirectory()) {
                    return cb(api_error_1.ApiError.EISDIR(p));
                }
                switch (flag.pathExistsAction()) {
                    case file_flag_1.ActionType.THROW_EXCEPTION:
                        return cb(api_error_1.ApiError.EEXIST(p));
                    case file_flag_1.ActionType.TRUNCATE_FILE:
                        return _this.openFile(p, flag, function (e, fd) {
                            if (e) {
                                cb(e);
                            }
                            else {
                                fd.truncate(0, function () {
                                    fd.sync(function () {
                                        cb(null, fd);
                                    });
                                });
                            }
                        });
                    case file_flag_1.ActionType.NOP:
                        return _this.openFile(p, flag, cb);
                    default:
                        return cb(new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, 'Invalid FileFlag object.'));
                }
            }
        };
        this.stat(p, false, must_be_file);
    };
    BaseFileSystem.prototype.rename = function (oldPath, newPath, cb) {
        cb(new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP));
    };
    BaseFileSystem.prototype.renameSync = function (oldPath, newPath) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    BaseFileSystem.prototype.stat = function (p, isLstat, cb) {
        cb(new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP));
    };
    BaseFileSystem.prototype.statSync = function (p, isLstat) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    BaseFileSystem.prototype.openFileSync = function (p, flag) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    BaseFileSystem.prototype.createFileSync = function (p, flag, mode) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    BaseFileSystem.prototype.openSync = function (p, flag, mode) {
        var stats;
        try {
            stats = this.statSync(p, false);
        }
        catch (e) {
            switch (flag.pathNotExistsAction()) {
                case file_flag_1.ActionType.CREATE_FILE:
                    var parentStats = this.statSync(path.dirname(p), false);
                    if (!parentStats.isDirectory()) {
                        throw api_error_1.ApiError.ENOTDIR(path.dirname(p));
                    }
                    return this.createFileSync(p, flag, mode);
                case file_flag_1.ActionType.THROW_EXCEPTION:
                    throw api_error_1.ApiError.ENOENT(p);
                default:
                    throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, 'Invalid FileFlag object.');
            }
        }
        if (stats.isDirectory()) {
            throw api_error_1.ApiError.EISDIR(p);
        }
        switch (flag.pathExistsAction()) {
            case file_flag_1.ActionType.THROW_EXCEPTION:
                throw api_error_1.ApiError.EEXIST(p);
            case file_flag_1.ActionType.TRUNCATE_FILE:
                this.unlinkSync(p);
                return this.createFileSync(p, flag, stats.mode);
            case file_flag_1.ActionType.NOP:
                return this.openFileSync(p, flag);
            default:
                throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, 'Invalid FileFlag object.');
        }
    };
    BaseFileSystem.prototype.unlink = function (p, cb) {
        cb(new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP));
    };
    BaseFileSystem.prototype.unlinkSync = function (p) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    BaseFileSystem.prototype.rmdir = function (p, cb) {
        cb(new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP));
    };
    BaseFileSystem.prototype.rmdirSync = function (p) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    BaseFileSystem.prototype.mkdir = function (p, mode, cb) {
        cb(new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP));
    };
    BaseFileSystem.prototype.mkdirSync = function (p, mode) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    BaseFileSystem.prototype.readdir = function (p, cb) {
        cb(new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP));
    };
    BaseFileSystem.prototype.readdirSync = function (p) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    BaseFileSystem.prototype.exists = function (p, cb) {
        this.stat(p, null, function (err) {
            cb(err == null);
        });
    };
    BaseFileSystem.prototype.existsSync = function (p) {
        try {
            this.statSync(p, true);
            return true;
        }
        catch (e) {
            return false;
        }
    };
    BaseFileSystem.prototype.realpath = function (p, cache, cb) {
        if (this.supportsLinks()) {
            var splitPath = p.split(path.sep);
            for (var i = 0; i < splitPath.length; i++) {
                var addPaths = splitPath.slice(0, i + 1);
                splitPath[i] = path.join.apply(null, addPaths);
            }
        }
        else {
            this.exists(p, function (doesExist) {
                if (doesExist) {
                    cb(null, p);
                }
                else {
                    cb(api_error_1.ApiError.ENOENT(p));
                }
            });
        }
    };
    BaseFileSystem.prototype.realpathSync = function (p, cache) {
        if (this.supportsLinks()) {
            var splitPath = p.split(path.sep);
            for (var i = 0; i < splitPath.length; i++) {
                var addPaths = splitPath.slice(0, i + 1);
                splitPath[i] = path.join.apply(null, addPaths);
            }
        }
        else {
            if (this.existsSync(p)) {
                return p;
            }
            else {
                throw api_error_1.ApiError.ENOENT(p);
            }
        }
    };
    BaseFileSystem.prototype.truncate = function (p, len, cb) {
        this.open(p, file_flag_1.FileFlag.getFileFlag('r+'), 0x1a4, (function (er, fd) {
            if (er) {
                return cb(er);
            }
            fd.truncate(len, (function (er) {
                fd.close((function (er2) {
                    cb(er || er2);
                }));
            }));
        }));
    };
    BaseFileSystem.prototype.truncateSync = function (p, len) {
        var fd = this.openSync(p, file_flag_1.FileFlag.getFileFlag('r+'), 0x1a4);
        try {
            fd.truncateSync(len);
        }
        catch (e) {
            throw e;
        }
        finally {
            fd.closeSync();
        }
    };
    BaseFileSystem.prototype.readFile = function (fname, encoding, flag, cb) {
        var oldCb = cb;
        this.open(fname, flag, 0x1a4, function (err, fd) {
            if (err) {
                return cb(err);
            }
            cb = function (err, arg) {
                fd.close(function (err2) {
                    if (err == null) {
                        err = err2;
                    }
                    return oldCb(err, arg);
                });
            };
            fd.stat(function (err, stat) {
                if (err != null) {
                    return cb(err);
                }
                var buf = new Buffer(stat.size);
                fd.read(buf, 0, stat.size, 0, function (err) {
                    if (err != null) {
                        return cb(err);
                    }
                    else if (encoding === null) {
                        return cb(err, buf);
                    }
                    try {
                        cb(null, buf.toString(encoding));
                    }
                    catch (e) {
                        cb(e);
                    }
                });
            });
        });
    };
    BaseFileSystem.prototype.readFileSync = function (fname, encoding, flag) {
        var fd = this.openSync(fname, flag, 0x1a4);
        try {
            var stat = fd.statSync();
            var buf = new Buffer(stat.size);
            fd.readSync(buf, 0, stat.size, 0);
            fd.closeSync();
            if (encoding === null) {
                return buf;
            }
            return buf.toString(encoding);
        }
        finally {
            fd.closeSync();
        }
    };
    BaseFileSystem.prototype.writeFile = function (fname, data, encoding, flag, mode, cb) {
        var oldCb = cb;
        this.open(fname, flag, 0x1a4, function (err, fd) {
            if (err != null) {
                return cb(err);
            }
            cb = function (err) {
                fd.close(function (err2) {
                    oldCb(err != null ? err : err2);
                });
            };
            try {
                if (typeof data === 'string') {
                    data = new Buffer(data, encoding);
                }
            }
            catch (e) {
                return cb(e);
            }
            fd.write(data, 0, data.length, 0, cb);
        });
    };
    BaseFileSystem.prototype.writeFileSync = function (fname, data, encoding, flag, mode) {
        var fd = this.openSync(fname, flag, mode);
        try {
            if (typeof data === 'string') {
                data = new Buffer(data, encoding);
            }
            fd.writeSync(data, 0, data.length, 0);
        }
        finally {
            fd.closeSync();
        }
    };
    BaseFileSystem.prototype.appendFile = function (fname, data, encoding, flag, mode, cb) {
        var oldCb = cb;
        this.open(fname, flag, mode, function (err, fd) {
            if (err != null) {
                return cb(err);
            }
            cb = function (err) {
                fd.close(function (err2) {
                    oldCb(err != null ? err : err2);
                });
            };
            if (typeof data === 'string') {
                data = new Buffer(data, encoding);
            }
            fd.write(data, 0, data.length, null, cb);
        });
    };
    BaseFileSystem.prototype.appendFileSync = function (fname, data, encoding, flag, mode) {
        var fd = this.openSync(fname, flag, mode);
        try {
            if (typeof data === 'string') {
                data = new Buffer(data, encoding);
            }
            fd.writeSync(data, 0, data.length, null);
        }
        finally {
            fd.closeSync();
        }
    };
    BaseFileSystem.prototype.chmod = function (p, isLchmod, mode, cb) {
        cb(new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP));
    };
    BaseFileSystem.prototype.chmodSync = function (p, isLchmod, mode) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    BaseFileSystem.prototype.chown = function (p, isLchown, uid, gid, cb) {
        cb(new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP));
    };
    BaseFileSystem.prototype.chownSync = function (p, isLchown, uid, gid) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    BaseFileSystem.prototype.utimes = function (p, atime, mtime, cb) {
        cb(new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP));
    };
    BaseFileSystem.prototype.utimesSync = function (p, atime, mtime) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    BaseFileSystem.prototype.link = function (srcpath, dstpath, cb) {
        cb(new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP));
    };
    BaseFileSystem.prototype.linkSync = function (srcpath, dstpath) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    BaseFileSystem.prototype.symlink = function (srcpath, dstpath, type, cb) {
        cb(new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP));
    };
    BaseFileSystem.prototype.symlinkSync = function (srcpath, dstpath, type) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    BaseFileSystem.prototype.readlink = function (p, cb) {
        cb(new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP));
    };
    BaseFileSystem.prototype.readlinkSync = function (p) {
        throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOTSUP);
    };
    return BaseFileSystem;
}());
exports.BaseFileSystem = BaseFileSystem;
var SynchronousFileSystem = (function (_super) {
    __extends(SynchronousFileSystem, _super);
    function SynchronousFileSystem() {
        _super.apply(this, arguments);
    }
    SynchronousFileSystem.prototype.supportsSynch = function () {
        return true;
    };
    SynchronousFileSystem.prototype.rename = function (oldPath, newPath, cb) {
        try {
            this.renameSync(oldPath, newPath);
            cb();
        }
        catch (e) {
            cb(e);
        }
    };
    SynchronousFileSystem.prototype.stat = function (p, isLstat, cb) {
        try {
            cb(null, this.statSync(p, isLstat));
        }
        catch (e) {
            cb(e);
        }
    };
    SynchronousFileSystem.prototype.open = function (p, flags, mode, cb) {
        try {
            cb(null, this.openSync(p, flags, mode));
        }
        catch (e) {
            cb(e);
        }
    };
    SynchronousFileSystem.prototype.unlink = function (p, cb) {
        try {
            this.unlinkSync(p);
            cb();
        }
        catch (e) {
            cb(e);
        }
    };
    SynchronousFileSystem.prototype.rmdir = function (p, cb) {
        try {
            this.rmdirSync(p);
            cb();
        }
        catch (e) {
            cb(e);
        }
    };
    SynchronousFileSystem.prototype.mkdir = function (p, mode, cb) {
        try {
            this.mkdirSync(p, mode);
            cb();
        }
        catch (e) {
            cb(e);
        }
    };
    SynchronousFileSystem.prototype.readdir = function (p, cb) {
        try {
            cb(null, this.readdirSync(p));
        }
        catch (e) {
            cb(e);
        }
    };
    SynchronousFileSystem.prototype.chmod = function (p, isLchmod, mode, cb) {
        try {
            this.chmodSync(p, isLchmod, mode);
            cb();
        }
        catch (e) {
            cb(e);
        }
    };
    SynchronousFileSystem.prototype.chown = function (p, isLchown, uid, gid, cb) {
        try {
            this.chownSync(p, isLchown, uid, gid);
            cb();
        }
        catch (e) {
            cb(e);
        }
    };
    SynchronousFileSystem.prototype.utimes = function (p, atime, mtime, cb) {
        try {
            this.utimesSync(p, atime, mtime);
            cb();
        }
        catch (e) {
            cb(e);
        }
    };
    SynchronousFileSystem.prototype.link = function (srcpath, dstpath, cb) {
        try {
            this.linkSync(srcpath, dstpath);
            cb();
        }
        catch (e) {
            cb(e);
        }
    };
    SynchronousFileSystem.prototype.symlink = function (srcpath, dstpath, type, cb) {
        try {
            this.symlinkSync(srcpath, dstpath, type);
            cb();
        }
        catch (e) {
            cb(e);
        }
    };
    SynchronousFileSystem.prototype.readlink = function (p, cb) {
        try {
            cb(null, this.readlinkSync(p));
        }
        catch (e) {
            cb(e);
        }
    };
    return SynchronousFileSystem;
}(BaseFileSystem));
exports.SynchronousFileSystem = SynchronousFileSystem;
//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJmaWxlIjoiZmlsZV9zeXN0ZW0uanMiLCJzb3VyY2VSb290IjoiIiwic291cmNlcyI6WyIuLi8uLi8uLi9zcmMvY29yZS9maWxlX3N5c3RlbS50cyJdLCJuYW1lcyI6W10sIm1hcHBpbmdzIjoiOzs7Ozs7QUFBQSwwQkFBa0MsYUFBYSxDQUFDLENBQUE7QUFHaEQsMEJBQW1DLGFBQWEsQ0FBQyxDQUFBO0FBQ2pELElBQU8sSUFBSSxXQUFXLE1BQU0sQ0FBQyxDQUFDO0FBcWU5QjtJQUFBO0lBb1pBLENBQUM7SUFuWlEsc0NBQWEsR0FBcEI7UUFDRSxNQUFNLENBQUMsS0FBSyxDQUFDO0lBQ2YsQ0FBQztJQUNNLGtDQUFTLEdBQWhCLFVBQWlCLENBQVMsRUFBRSxFQUF3QztRQUNsRSxFQUFFLENBQUMsQ0FBQyxFQUFFLENBQUMsQ0FBQyxDQUFDO0lBQ1gsQ0FBQztJQU1NLGlDQUFRLEdBQWYsVUFBZ0IsQ0FBUyxFQUFFLElBQWMsRUFBRSxFQUEyQztRQUNwRixNQUFNLElBQUksb0JBQVEsQ0FBQyxxQkFBUyxDQUFDLE9BQU8sQ0FBQyxDQUFDO0lBQ3hDLENBQUM7SUFLTSxtQ0FBVSxHQUFqQixVQUFrQixDQUFTLEVBQUUsSUFBYyxFQUFFLElBQVksRUFBRSxFQUEyQztRQUNwRyxNQUFNLElBQUksb0JBQVEsQ0FBQyxxQkFBUyxDQUFDLE9BQU8sQ0FBQyxDQUFDO0lBQ3hDLENBQUM7SUFDTSw2QkFBSSxHQUFYLFVBQVksQ0FBUyxFQUFFLElBQWEsRUFBRSxJQUFZLEVBQUUsRUFBOEM7UUFBbEcsaUJBcURDO1FBcERDLElBQUksWUFBWSxHQUFHLFVBQUMsQ0FBVyxFQUFFLEtBQWE7WUFDNUMsRUFBRSxDQUFDLENBQUMsQ0FBQyxDQUFDLENBQUMsQ0FBQztnQkFFTixNQUFNLENBQUMsQ0FBQyxJQUFJLENBQUMsbUJBQW1CLEVBQUUsQ0FBQyxDQUFDLENBQUM7b0JBQ25DLEtBQUssc0JBQVUsQ0FBQyxXQUFXO3dCQUV6QixNQUFNLENBQUMsS0FBSSxDQUFDLElBQUksQ0FBQyxJQUFJ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QVk7UUFDeEQsSUFBSSxDQUFDO1lBQ0gsSUFBSSxDQUFDLFFBQVEsQ0FBQyxPQUFPLEVBQUUsT0FBTyxDQUFDLENBQUM7WUFDaEMsRUFBRSxFQUFFLENBQUM7UUFDUCxDQUFFO1FBQUEsS0FBSyxDQUFDLENBQUMsQ0FBQyxDQUFDLENBQUMsQ0FBQztZQUNYLEVBQUUsQ0FBQyxDQUFDLENBQUMsQ0FBQztRQUNSLENBQUM7SUFDSCxDQUFDO0lBRU0sdUNBQU8sR0FBZCxVQUFlLE9BQWUsRUFBRSxPQUFlLEVBQUUsSUFBWSxFQUFFLEVBQVk7UUFDekUsSUFBSSxDQUFDO1lBQ0gsSUFBSSxDQUFDLFdBQVcsQ0FBQyxPQUFPLEVBQUUsT0FBTyxFQUFFLElBQUksQ0FBQyxDQUFDO1lBQ3pDLEVBQUUsRUFBRSxDQUFDO1FBQ1AsQ0FBRTtRQUFBLEtBQUssQ0FBQyxDQUFDLENBQUMsQ0FBQyxDQUFDLENBQUM7WUFDWCxFQUFFLENBQUMsQ0FBQyxDQUFDLENBQUM7UUFDUixDQUFDO0lBQ0gsQ0FBQztJQUVNLHdDQUFRLEdBQWYsVUFBZ0IsQ0FBUyxFQUFFLEVBQVk7UUFDckMsSUFBSSxDQUFDO1lBQ0gsRUFBRSxDQUFDLElBQUksRUFBRSxJQUFJLENBQUMsWUFBWSxDQUFDLENBQUMsQ0FBQyxDQUFDLENBQUM7UUFDakMsQ0FBRTtRQUFBLEtBQUssQ0FBQyxDQUFDLENBQUMsQ0FBQyxDQUFDLENBQUM7WUFDWCxFQUFFLENBQUMsQ0FBQyxDQUFDLENBQUM7UUFDUixDQUFDO0lBQ0gsQ0FBQztJQUNILDRCQUFDO0FBQUQsQ0FBQyxBQXJIRCxDQUEyQyxjQUFjLEdBcUh4RDtBQXJIWSw2QkFBcUIsd0JBcUhqQyxDQUFBIiwic291cmNlc0NvbnRlbnQiOlsiaW1wb3J0IHtBcGlFcnJvciwgRXJyb3JDb2RlfSBmcm9tICcuL2FwaV9lcnJvcic7XHJcbmltcG9ydCBTdGF0cyBmcm9tICcuL25vZGVfZnNfc3RhdHMnO1xyXG5pbXBvcnQgZmlsZSA9IHJlcXVpcmUoJy4vZmlsZScpO1xyXG5pbXBvcnQge0ZpbGVGbGFnLCBBY3Rpb25UeXBlfSBmcm9tICcuL2ZpbGVfZmxhZyc7XHJcbmltcG9ydCBwYXRoID0gcmVxdWlyZSgncGF0aCcpO1xyXG5cclxuLyoqXHJcbiAqIEludGVyZmFjZSBmb3IgYSBmaWxlc3lzdGVtLiAqKkFsbCoqIEJyb3dzZXJGUyBGaWxlU3lzdGVtcyBzaG91bGQgaW1wbGVtZW50XHJcbiAqIHRoaXMgaW50ZXJmYWNlLlxyXG4gKlxyXG4gKiBCZWxvdywgd2UgZGVub3RlIGVhY2ggQVBJIG1ldGhvZCBhcyAqKkNvcmUqKiwgKipTdXBwbGVtZW50YWwqKiwgb3JcclxuICogKipPcHRpb25hbCoqLlxyXG4gKlxyXG4gKiAjIyMgQ29yZSBNZXRob2RzXHJcbiAqXHJcbiAqICoqQ29yZSoqIEFQSSBtZXRob2RzICpuZWVkKiB0byBiZSBpbXBsZW1lbnRlZCBmb3IgYmFzaWMgcmVhZC93cml0ZVxyXG4gKiBmdW5jdGlvbmFsaXR5LlxyXG4gKlxyXG4gKiBOb3RlIHRoYXQgcmVhZC1vbmx5IEZpbGVTeXN0ZW1zIGNhbiBjaG9vc2UgdG8gbm90IGltcGxlbWVudCBjb3JlIG1ldGhvZHNcclxuICogdGhhdCBtdXRhdGUgZmlsZXMgb3IgbWV0YWRhdGEuIFRoZSBkZWZhdWx0IGltcGxlbWVudGF0aW9uIHdpbGwgcGFzcyBhXHJcbiAqIE5PVF9TVVBQT1JURUQgZXJyb3IgdG8gdGhlIGNhbGxiYWNrLlxyXG4gKlxyXG4gKiAjIyMgU3VwcGxlbWVudGFsIE1ldGhvZHNcclxuICpcclxuICogKipTdXBwbGVtZW50YWwqKiBBUEkgbWV0aG9kcyBkbyBub3QgbmVlZCB0byBiZSBpbXBsZW1lbnRlZCBieSBhIGZpbGVzeXN0ZW0uXHJcbiAqIFRoZSBkZWZhdWx0IGltcGxlbWVudGF0aW9uIGltcGxlbWVudHMgYWxsIG9mIHRoZSBzdXBwbGVtZW50YWwgQVBJIG1ldGhvZHMgaW5cclxuICogdGVybXMgb2YgdGhlICoqY29yZSoqIEFQSSBtZXRob2RzLlxyXG4gKlxyXG4gKiBOb3RlIHRoYXQgYSBmaWxlIHN5c3RlbSBtYXkgY2hvb3NlIHRvIGltcGxlbWVudCBzdXBwbGVtZW50YWwgbWV0aG9kcyBmb3JcclxuICogZWZmaWNpZW5jeSByZWFzb25zLlxyXG4gKlxyXG4gKiBUaGUgY29kZSBmb3Igc29tZSBzdXBwbGVtZW50YWwgbWV0aG9kcyB3YXMgYWRhcHRlZCBkaXJlY3RseSBmcm9tIE5vZGVKUydzXHJcbiAqIGZzLmpzIHNvdXJjZSBjb2RlLlxyXG4gKlxyXG4gKiAjIyMgT3B0aW9uYWwgTWV0aG9kc1xyXG4gKlxyXG4gKiAqKk9wdGlvbmFsKiogQVBJIG1ldGhvZHMgcHJvdmlkZSBmdW5jdGlvbmFsaXR5IHRoYXQgbWF5IG5vdCBiZSBhdmFpbGFibGUgaW5cclxuICogYWxsIGZpbGVzeXN0ZW1zLiBGb3IgZXhhbXBsZSwgYWxsIHN5bWxpbmsvaGFyZGxpbmstcmVsYXRlZCBBUEkgbWV0aG9kcyBmYWxsXHJcbiAqIHVuZGVyIHRoaXMgY2F0ZWdvcnkuXHJcbiAqXHJcbiAqIFRoZSBkZWZhdWx0IGltcGxlbWVudGF0aW9uIHdpbGwgcGFzcyBhIE5PVF9TVVBQT1JURUQgZXJyb3IgdG8gdGhlIGNhbGxiYWNrLlxyXG4gKlxyXG4gKiAjIyMgQXJndW1lbnQgQXNzdW1wdGlvbnNcclxuICpcclxuICogWW91IGNhbiBhc3N1bWUgdGhlIGZvbGxvd2luZyBhYm91dCBhcmd1bWVudHMgcGFzc2VkIHRvIGVhY2ggQVBJIG1ldGhvZDpcclxuICpcclxuICogKiAqKkV2ZXJ5IHBhdGggaXMgYW4gYWJzb2x1dGUgcGF0aC4qKiBNZWFuaW5nLCBgLmAsIGAuLmAsIGFuZCBvdGhlciBpdGVtc1xyXG4gKiAgIGFyZSByZXNvbHZlZCBpbnRvIGFuIGFic29sdXRlIGZvcm0uXHJcbiAqICogKipBbGwgYXJndW1lbnRzIGFyZSBwcmVzZW50LioqIEFueSBvcHRpb25hbCBhcmd1bWVudHMgYXQgdGhlIE5vZGUgQVBJIGxldmVsXHJcbiAqICAgaGF2ZSBiZWVuIHBhc3NlZCBpbiB3aXRoIHRoZWlyIGRlZmF1bHQgdmFsdWVzLlxyXG4gKiAqICoqVGhlIGNhbGxiYWNrIHdpbGwgcmVzZXQgdGhlIHN0YWNrIGRlcHRoLioqIFdoZW4geW91ciBmaWxlc3lzdGVtIGNhbGxzIHRoZVxyXG4gKiAgIGNhbGxiYWNrIHdpdGggdGhlIHJlcXVlc3RlZCBpbmZvcm1hdGlvbiwgaXQgd2lsbCB1c2UgYHNldEltbWVkaWF0ZWAgdG9cclxuICogICByZXNldCB0aGUgSmF2YVNjcmlwdCBzdGFjayBkZXB0aCBiZWZvcmUgY2FsbGluZyB0aGUgdXNlci1zdXBwbGllZCBjYWxsYmFjay5cclxuICogQGNsYXNzIEZpbGVTeXN0ZW1cclxuICovXHJcbmV4cG9ydCBpbnRlcmZhY2UgRmlsZVN5c3RlbSB7XHJcbiAgLyoqXHJcbiAgICogKipPcHRpb25hbCoqOiBSZXR1cm5zIHRoZSBuYW1lIG9mIHRoZSBmaWxlIHN5c3RlbS5cclxuICAgKiBAbWV0aG9kIEZpbGVTeXN0ZW0jZ2V0TmFtZVxyXG4gICAqIEByZXR1cm4ge3N0cmluZ31cclxuICAgKi9cclxuICBnZXROYW1lKCk6IHN0cmluZztcclxuICAvKipcclxuICAgKiAqKk9wdGlvbmFsKio6IFBhc3NlcyB0aGUgZm9sbG93aW5nIGluZm9ybWF0aW9uIHRvIHRoZSBjYWxsYmFjazpcclxuICAgKlxyXG4gICAqICogVG90YWwgbnVtYmVyIG9mIGJ5dGVzIGF2YWlsYWJsZSBvbiB0aGlzIGZpbGUgc3lzdGVtLlxyXG4gICAqICogbnVtYmVyIG9mIGZyZWUgYnl0ZXMgYXZhaWxhYmxlIG9uIHRoaXMgZmlsZSBzeXN0ZW0uXHJcbiAgICpcclxuICAgKiBAbWV0aG9kIEZpbGVTeXN0ZW0jZGlza1NwYWNlXHJcbiAgICogQHRvZG8gVGhpcyBpbmZvIGlzIG5vdCBhdmFpbGFibGUgdGhyb3VnaCB0aGUgTm9kZSBBUEkuIFBlcmhhcHMgd2UgY291bGQgZG8gYVxyXG4gICAqICAgcG9seWZpbGwgb2YgZGlza3NwYWNlLmpzLCBvciBhZGQgYSBuZXcgTm9kZSBBUEkgZnVuY3Rpb24uXHJcbiAgICogQHBhcmFtIHtzdHJpbmd9IHBhdGggVGhlIHBhdGggdG8gdGhlIGxvY2F0aW9uIHRoYXQgaXMgYmVpbmcgcXVlcmllZC4gT25seVxyXG4gICAqICAgdXNlZnVsIGZvciBmaWxlc3lzdGVtcyB0aGF0IHN1cHBvcnQgbW91bnQgcG9pbnRzLlxyXG4gICAqIEBwYXJhbSB7RmlsZVN5c3RlbX5kaXNrU3BhY2VDYWxsYmFja30gY2JcclxuICAgKi9cclxuICBkaXNrU3BhY2UocDogc3RyaW5nLCBjYjogKHRvdGFsOiBudW1iZXIsIGZyZWU6IG51bWJlcikgPT4gYW55KTogdm9pZDtcclxuICAvKipcclxuICAgKiAqKkNvcmUqKjogSXMgdGhpcyBmaWxlc3lzdGVtIHJlYWQtb25seT9cclxuICAgKiBAbWV0aG9kIEZpbGVTeXN0ZW0jaXNSZWFkT25seVxyXG4gICAqIEByZXR1cm4ge2Jvb2xlYW59IFRydWUgaWYgdGhpcyBGaWxlU3lzdGVtIGlzIGluaGVyZW50bHkgcmVhZC1vbmx5LlxyXG4gICAqL1xyXG4gIGlzUmVhZE9ubHkoKTogYm9vbGVhbjtcclxuICAvKipcclxuICAgKiAqKkNvcmUqKjogRG9lcyB0aGUgZmlsZXN5c3RlbSBzdXBwb3J0IG9wdGlvbmFsIHN5bWxpbmsvaGFyZGxpbmstcmVsYXRlZFxyXG4gICAqICAgY29tbWFuZHM/XHJcbiAgICogQG1ldGhvZCBGaWxlU3lzdGVtI3N1cHBvcnRzTGlua3NcclxuICAgKiBAcmV0dXJuIHtib29sZWFufSBUcnVlIGlmIHRoZSBGaWxlU3lzdGVtIHN1cHBvcnRzIHRoZSBvcHRpb25hbFxyXG4gICAqICAgc3ltbGluay9oYXJkbGluay1yZWxhdGVkIGNvbW1hbmRzLlxyXG4gICAqL1xyXG4gIHN1cHBvcnRzTGlua3MoKTogYm9vbGVhbjtcclxuICAvKipcclxuICAgKiAqKkNvcmUqKjogRG9lcyB0aGUgZmlsZXN5c3RlbSBzdXBwb3J0IG9wdGlvbmFsIHByb3BlcnR5LXJlbGF0ZWQgY29tbWFuZHM/XHJcbiAgICogQG1ldGhvZCBGaWxlU3lzdGVtI3N1cHBvcnRzUHJvcHNcclxuICAgKiBAcmV0dXJuIHtib29sZWFufSBUcnVlIGlmIHRoZSBGaWxlU3lzdGVtIHN1cHBvcnRzIHRoZSBvcHRpb25hbFxyXG4gICAqICAgcHJvcGVydHktcmVsYXRlZCBjb21tYW5kcyAocGVybWlzc2lvbnMsIHV0aW1lcywgZXRjKS5cclxuICAgKi9cclxuICBzdXBwb3J0c1Byb3BzKCk6IGJvb2xlYW47XHJcbiAgLyoqXHJcbiAgICogKipDb3JlKio6IERvZXMgdGhlIGZpbGVzeXN0ZW0gc3VwcG9ydCB0aGUgb3B0aW9uYWwgc3luY2hyb25vdXMgaW50ZXJmYWNlP1xyXG4gICAqIEBtZXRob2QgRmlsZVN5c3RlbSNzdXBwb3J0c1N5bmNoXHJcbiAgICogQHJldHVybiB7Ym9vbGVhbn0gVHJ1ZSBpZiB0aGUgRmlsZVN5c3RlbSBzdXBwb3J0cyBzeW5jaHJvbm91cyBvcGVyYXRpb25zLlxyXG4gICAqL1xyXG4gIHN1cHBvcnRzU3luY2goKTogYm9vbGVhbjtcclxuICAvLyAqKkNPUkUgQVBJIE1FVEhPRFMqKlxyXG4gIC8vIEZpbGUgb3IgZGlyZWN0b3J5IG9wZXJhdGlvbnNcclxuICAvKipcclxuICAgKiAqKkNvcmUqKjogQXN5bmNocm9ub3VzIHJlbmFtZS4gTm8gYXJndW1lbnRzIG90aGVyIHRoYW4gYSBwb3NzaWJsZSBleGNlcHRpb25cclxuICAgKiBhcmUgZ2l2ZW4gdG8gdGhlIGNvbXBsZXRpb24gY2FsbGJhY2suXHJcbiAgICogQG1ldGhvZCBGaWxlU3lzdGVtI3JlbmFtZVxyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBvbGRQYXRoXHJcbiAgICogQHBhcmFtIHtzdHJpbmd9IG5ld1BhdGhcclxuICAgKiBAcGFyYW0ge0ZpbGVTeXN0ZW1+bm9kZUNhbGxiYWNrfSBjYlxyXG4gICAqL1xyXG4gIHJlbmFtZShvbGRQYXRoOiBzdHJpbmcsIG5ld1BhdGg6IHN0cmluZywgY2I6IChlcnI/OiBBcGlFcnJvcikgPT4gdm9pZCk6IHZvaWQ7XHJcbiAgLyoqXHJcbiAgICogKipDb3JlKio6IFN5bmNocm9ub3VzIHJlbmFtZS5cclxuICAgKiBAbWV0aG9kIEZpbGVTeXN0ZW0jcmVuYW1lU3luY1xyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBvbGRQYXRoXHJcbiAgICogQHBhcmFtIHtzdHJpbmd9IG5ld1BhdGhcclxuICAgKi9cclxuICByZW5hbWVTeW5jKG9sZFBhdGg6IHN0cmluZywgbmV3UGF0aDogc3RyaW5nKTogdm9pZDtcclxuICAvKipcclxuICAgKiAqKkNvcmUqKjogQXN5bmNocm9ub3VzIGBzdGF0YCBvciBgbHN0YXRgLlxyXG4gICAqIEBtZXRob2QgRmlsZVN5c3RlbSNzdGF0XHJcbiAgICogQHBhcmFtIHtzdHJpbmd9IHBhdGhcclxuICAgKiBAcGFyYW0ge2Jvb2xlYW59IGlzTHN0YXQgVHJ1ZSBpZiB0aGlzIGlzIGBsc3RhdGAsIGZhbHNlIGlmIHRoaXMgaXMgcmVndWxhclxyXG4gICAqICAgYHN0YXRgLlxyXG4gICAqIEBwYXJhbSB7RmlsZVN5c3RlbX5ub2RlU3RhdHNDYWxsYmFja30gY2JcclxuICAgKi9cclxuICBzdGF0KHA6IHN0cmluZywgaXNMc3RhdDogYm9vbGVhbiwgY2I6IChlcnI6IEFwaUVycm9yLCBzdGF0PzogU3RhdHMpID0+IHZvaWQpOiB2b2lkO1xyXG4gIC8qKlxyXG4gICAqICoqQ29yZSoqOiBTeW5jaHJvbm91cyBgc3RhdGAgb3IgYGxzdGF0YC5cclxuICAgKiBAbWV0aG9kIEZpbGVTeXN0ZW0jc3RhdFN5bmNcclxuICAgKiBAcGFyYW0ge3N0cmluZ30gcGF0aFxyXG4gICAqIEBwYXJhbSB7Ym9vbGVhbn0gaXNMc3RhdCBUcnVlIGlmIHRoaXMgaXMgYGxzdGF0YCwgZmFsc2UgaWYgdGhpcyBpcyByZWd1bGFyXHJcbiAgICogICBgc3RhdGAuXHJcbiAgICogQHJldHVybiB7QnJvd3NlckZTLm5vZGUuZnMuU3RhdHN9XHJcbiAgICovXHJcbiAgc3RhdFN5bmMocDogc3RyaW5nLCBpc0xzdGF0OiBib29sZWFuKTogU3RhdHM7XHJcbiAgLy8gRmlsZSBvcGVyYXRpb25zXHJcbiAgLyoqXHJcbiAgICogKipDb3JlKio6IEFzeW5jaHJvbm91cyBmaWxlIG9wZW4uXHJcbiAgICogQHNlZSBodHRwOi8vd3d3Lm1hbnBhZ2V6LmNvbS9tYW4vMi9vcGVuL1xyXG4gICAqIEBtZXRob2QgRmlsZVN5c3RlbSNvcGVuXHJcbiAgICogQHBhcmFtIHtzdHJpbmd9IHBhdGhcclxuICAgKiBAcGFyYW0ge0Jyb3dzZXJGUy5GaWxlTW9kZX0gZmxhZ3MgSGFuZGxlcyB0aGUgY29tcGxleGl0eSBvZiB0aGUgdmFyaW91cyBmaWxlXHJcbiAgICogICBtb2Rlcy4gU2VlIGl0cyBBUEkgZm9yIG1vcmUgZGV0YWlscy5cclxuICAgKiBAcGFyYW0ge251bWJlcn0gbW9kZSBNb2RlIHRvIHVzZSB0byBvcGVuIHRoZSBmaWxlLiBDYW4gYmUgaWdub3JlZCBpZiB0aGVcclxuICAgKiAgIGZpbGVzeXN0ZW0gZG9lc24ndCBzdXBwb3J0IHBlcm1pc3Npb25zLlxyXG4gICAqIEBwYXJhbSB7RmlsZVN5c3RlbX5maWxlQ2FsbGJhY2t9IGNiXHJcbiAgICovXHJcbiAgb3BlbihwOiBzdHJpbmcsIGZsYWc6RmlsZUZsYWcsIG1vZGU6IG51bWJlciwgY2I6IChlcnI6IEFwaUVycm9yLCBmZD86IGZpbGUuRmlsZSkgPT4gYW55KTogdm9pZDtcclxuICAvKipcclxuICAgKiAqKkNvcmUqKjogU3luY2hyb25vdXMgZmlsZSBvcGVuLlxyXG4gICAqIEBzZWUgaHR0cDovL3d3dy5tYW5wYWdlei5jb20vbWFuLzIvb3Blbi9cclxuICAgKiBAbWV0aG9kIEZpbGVTeXN0ZW0jb3BlblN5bmNcclxuICAgKiBAcGFyYW0ge3N0cmluZ30gcGF0aFxyXG4gICAqIEBwYXJhbSB7QnJvd3NlckZTLkZpbGVNb2RlfSBmbGFncyBIYW5kbGVzIHRoZSBjb21wbGV4aXR5IG9mIHRoZSB2YXJpb3VzIGZpbGVcclxuICAgKiAgIG1vZGVzLiBTZWUgaXRzIEFQSSBmb3IgbW9yZSBkZXRhaWxzLlxyXG4gICAqIEBwYXJhbSB7bnVtYmVyfSBtb2RlIE1vZGUgdG8gdXNlIHRvIG9wZW4gdGhlIGZpbGUuIENhbiBiZSBpZ25vcmVkIGlmIHRoZVxyXG4gICAqICAgZmlsZXN5c3RlbSBkb2Vzbid0IHN1cHBvcnQgcGVybWlzc2lvbnMuXHJcbiAgICogQHJldHVybiB7QnJvd3NlckZTLkZpbGV9XHJcbiAgICovXHJcbiAgb3BlblN5bmMocDogc3RyaW5nLCBmbGFnOiBGaWxlRmxhZywgbW9kZTogbnVtYmVyKTogZmlsZS5GaWxlO1xyXG4gIC8qKlxyXG4gICAqICoqQ29yZSoqOiBBc3luY2hyb25vdXMgYHVubGlua2AuXHJcbiAgICogQG1ldGhvZCBGaWxlU3lzdGVtI3VubGlua1xyXG4gICAqIEBwYXJhbSBbc3RyaW5nXSBwYXRoXHJcbiAgICogQHBhcmFtIFtGaWxlU3lzdGVtfm5vZGVDYWxsYmFja10gY2JcclxuICAgKi9cclxuICB1bmxpbmsocDogc3RyaW5nLCBjYjogRnVuY3Rpb24pOiB2b2lkO1xyXG4gIC8qKlxyXG4gICAqICoqQ29yZSoqOiBTeW5jaHJvbm91cyBgdW5saW5rYC5cclxuICAgKiBAbWV0aG9kIEZpbGVTeXN0ZW0jdW5saW5rU3luY1xyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBwYXRoXHJcbiAgICovXHJcbiAgdW5saW5rU3luYyhwOiBzdHJpbmcpOiB2b2lkO1xyXG4gIC8vIERpcmVjdG9yeSBvcGVyYXRpb25zXHJcbiAgLyoqXHJcbiAgICogKipDb3JlKio6IEFzeW5jaHJvbm91cyBgcm1kaXJgLlxyXG4gICAqIEBtZXRob2QgRmlsZVN5c3RlbSNybWRpclxyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBwYXRoXHJcbiAgICogQHBhcmFtIHtGaWxlU3lzdGVtfm5vZGVDYWxsYmFja30gY2JcclxuICAgKi9cclxuICBybWRpcihwOiBzdHJpbmcsIGNiOiBGdW5jdGlvbik6IHZvaWQ7XHJcbiAgLyoqXHJcbiAgICogKipDb3JlKio6IFN5bmNocm9ub3VzIGBybWRpcmAuXHJcbiAgICogQG1ldGhvZCBGaWxlU3lzdGVtI3JtZGlyU3luY1xyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBwYXRoXHJcbiAgICovXHJcbiAgcm1kaXJTeW5jKHA6IHN0cmluZyk6IHZvaWQ7XHJcbiAgLyoqXHJcbiAgICogKipDb3JlKio6IEFzeW5jaHJvbm91cyBgbWtkaXJgLlxyXG4gICAqIEBtZXRob2QgRmlsZVN5c3RlbSNta2RpclxyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBwYXRoXHJcbiAgICogQHBhcmFtIHtudW1iZXI/fSBtb2RlIE1vZGUgdG8gbWFrZSB0aGUgZGlyZWN0b3J5IHVzaW5nLiBDYW4gYmUgaWdub3JlZCBpZlxyXG4gICAqICAgdGhlIGZpbGVzeXN0ZW0gZG9lc24ndCBzdXBwb3J0IHBlcm1pc3Npb25zLlxyXG4gICAqIEBwYXJhbSB7RmlsZVN5c3RlbX5ub2RlQ2FsbGJhY2t9IGNiXHJcbiAgICovXHJcbiAgbWtkaXIocDogc3RyaW5nLCBtb2RlOiBudW1iZXIsIGNiOiBGdW5jdGlvbik6IHZvaWQ7XHJcbiAgLyoqXHJcbiAgICogKipDb3JlKio6IFN5bmNocm9ub3VzIGBta2RpcmAuXHJcbiAgICogQG1ldGhvZCBGaWxlU3lzdGVtI21rZGlyU3luY1xyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBwYXRoXHJcbiAgICogQHBhcmFtIHtudW1iZXJ9IG1vZGUgTW9kZSB0byBtYWtlIHRoZSBkaXJlY3RvcnkgdXNpbmcuIENhbiBiZSBpZ25vcmVkIGlmXHJcbiAgICogICB0aGUgZmlsZXN5c3RlbSBkb2Vzbid0IHN1cHBvcnQgcGVybWlzc2lvbnMuXHJcbiAgICovXHJcbiAgbWtkaXJTeW5jKHA6IHN0cmluZywgbW9kZTogbnVtYmVyKTogdm9pZDtcclxuICAvKipcclxuICAgKiAqKkNvcmUqKjogQXN5bmNocm9ub3VzIGByZWFkZGlyYC4gUmVhZHMgdGhlIGNvbnRlbnRzIG9mIGEgZGlyZWN0b3J5LlxyXG4gICAqXHJcbiAgICogVGhlIGNhbGxiYWNrIGdldHMgdHdvIGFyZ3VtZW50cyBgKGVyciwgZmlsZXMpYCB3aGVyZSBgZmlsZXNgIGlzIGFuIGFycmF5IG9mXHJcbiAgICogdGhlIG5hbWVzIG9mIHRoZSBmaWxlcyBpbiB0aGUgZGlyZWN0b3J5IGV4Y2x1ZGluZyBgJy4nYCBhbmQgYCcuLidgLlxyXG4gICAqIEBtZXRob2QgRmlsZVN5c3RlbSNyZWFkZGlyXHJcbiAgICogQHBhcmFtIHtzdHJpbmd9IHBhdGhcclxuICAgKiBAcGFyYW0ge0ZpbGVTeXN0ZW1+cmVhZGRpckNhbGxiYWNrfSBjYlxyXG4gICAqL1xyXG4gIHJlYWRkaXIocDogc3RyaW5nLCBjYjogKGVycjogQXBpRXJyb3IsIGZpbGVzPzogc3RyaW5nW10pID0+IHZvaWQpOiB2b2lkO1xyXG4gIC8qKlxyXG4gICAqICoqQ29yZSoqOiBTeW5jaHJvbm91cyBgcmVhZGRpcmAuIFJlYWRzIHRoZSBjb250ZW50cyBvZiBhIGRpcmVjdG9yeS5cclxuICAgKiBAbWV0aG9kIEZpbGVTeXN0ZW0jcmVhZGRpclN5bmNcclxuICAgKiBAcGFyYW0ge3N0cmluZ30gcGF0aFxyXG4gICAqIEByZXR1cm4ge3N0cmluZ1tdfVxyXG4gICAqL1xyXG4gIHJlYWRkaXJTeW5jKHA6IHN0cmluZyk6IHN0cmluZ1tdO1xyXG4gIC8vICoqU1VQUExFTUVOVEFMIElOVEVSRkFDRSBNRVRIT0RTKipcclxuICAvLyBGaWxlIG9yIGRpcmVjdG9yeSBvcGVyYXRpb25zXHJcbiAgLyoqXHJcbiAgICogKipTdXBwbGVtZW50YWwqKjogVGVzdCB3aGV0aGVyIG9yIG5vdCB0aGUgZ2l2ZW4gcGF0aCBleGlzdHMgYnkgY2hlY2tpbmcgd2l0aFxyXG4gICAqIHRoZSBmaWxlIHN5c3RlbS4gVGhlbiBjYWxsIHRoZSBjYWxsYmFjayBhcmd1bWVudCB3aXRoIGVpdGhlciB0cnVlIG9yIGZhbHNlLlxyXG4gICAqIEBtZXRob2QgRmlsZVN5c3RlbSNleGlzdHNcclxuICAgKiBAcGFyYW0ge3N0cmluZ30gcGF0aFxyXG4gICAqIEBwYXJhbSB7RmlsZVN5c3RlbX5leGlzdHNDYWxsYmFja30gY2JcclxuICAgKi9cclxuICBleGlzdHMocDogc3RyaW5nLCBjYjogKGV4aXN0czogYm9vbGVhbikgPT4gdm9pZCk6IHZvaWQ7XHJcbiAgLyoqXHJcbiAgICogKipTdXBwbGVtZW50YWwqKjogVGVzdCB3aGV0aGVyIG9yIG5vdCB0aGUgZ2l2ZW4gcGF0aCBleGlzdHMgYnkgY2hlY2tpbmcgd2l0aFxyXG4gICAqIHRoZSBmaWxlIHN5c3RlbS5cclxuICAgKiBAbWV0aG9kIEZpbGVTeXN0ZW0jZXhpc3RzU3luY1xyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBwYXRoXHJcbiAgICogQHJldHVybiB7Ym9vbGVhbn1cclxuICAgKi9cclxuICBleGlzdHNTeW5jKHA6IHN0cmluZyk6IGJvb2xlYW47XHJcbiAgLyoqXHJcbiAgICogKipTdXBwbGVtZW50YWwqKjogQXN5bmNocm9ub3VzIGByZWFscGF0aGAuIFRoZSBjYWxsYmFjayBnZXRzIHR3byBhcmd1bWVudHNcclxuICAgKiBgKGVyciwgcmVzb2x2ZWRQYXRoKWAuXHJcbiAgICpcclxuICAgKiBOb3RlIHRoYXQgdGhlIE5vZGUgQVBJIHdpbGwgcmVzb2x2ZSBgcGF0aGAgdG8gYW4gYWJzb2x1dGUgcGF0aC5cclxuICAgKiBAbWV0aG9kIEZpbGVTeXN0ZW0jcmVhbHBhdGhcclxuICAgKiBAcGFyYW0ge3N0cmluZ30gcGF0aFxyXG4gICAqIEBwYXJhbSB7T2JqZWN0fSBjYWNoZSBBbiBvYmplY3QgbGl0ZXJhbCBvZiBtYXBwZWQgcGF0aHMgdGhhdCBjYW4gYmUgdXNlZCB0b1xyXG4gICAqICAgZm9yY2UgYSBzcGVjaWZpYyBwYXRoIHJlc29sdXRpb24gb3IgYXZvaWQgYWRkaXRpb25hbCBgZnMuc3RhdGAgY2FsbHMgZm9yXHJcbiAgICogICBrbm93biByZWFsIHBhdGhzLiBJZiBub3Qgc3VwcGxpZWQgYnkgdGhlIHVzZXIsIGl0J2xsIGJlIGFuIGVtcHR5IG9iamVjdC5cclxuICAgKiBAcGFyYW0ge0ZpbGVTeXN0ZW1+cGF0aENhbGxiYWNrfSBjYlxyXG4gICAqL1xyXG4gIHJlYWxwYXRoKHA6IHN0cmluZywgY2FjaGU6IHtbcGF0aDogc3RyaW5nXTogc3RyaW5nfSwgY2I6IChlcnI6IEFwaUVycm9yLCByZXNvbHZlZFBhdGg/OiBzdHJpbmcpID0+IGFueSk6IHZvaWQ7XHJcbiAgLyoqXHJcbiAgICogKipTdXBwbGVtZW50YWwqKjogU3luY2hyb25vdXMgYHJlYWxwYXRoYC5cclxuICAgKlxyXG4gICAqIE5vdGUgdGhhdCB0aGUgTm9kZSBBUEkgd2lsbCByZXNvbHZlIGBwYXRoYCB0byBhbiBhYnNvbHV0ZSBwYXRoLlxyXG4gICAqIEBtZXRob2QgRmlsZVN5c3RlbSNyZWFscGF0aFN5bmNcclxuICAgKiBAcGFyYW0ge3N0cmluZ30gcGF0aFxyXG4gICAqIEBwYXJhbSB7T2JqZWN0fSBjYWNoZSBBbiBvYmplY3QgbGl0ZXJhbCBvZiBtYXBwZWQgcGF0aHMgdGhhdCBjYW4gYmUgdXNlZCB0b1xyXG4gICAqICAgZm9yY2UgYSBzcGVjaWZpYyBwYXRoIHJlc29sdXRpb24gb3IgYXZvaWQgYWRkaXRpb25hbCBgZnMuc3RhdGAgY2FsbHMgZm9yXHJcbiAgICogICBrbm93biByZWFsIHBhdGhzLiBJZiBub3Qgc3VwcGxpZWQgYnkgdGhlIHVzZXIsIGl0J2xsIGJlIGFuIGVtcHR5IG9iamVjdC5cclxuICAgKiBAcmV0dXJuIHtzdHJpbmd9XHJcbiAgICovXHJcbiAgcmVhbHBhdGhTeW5jKHA6IHN0cmluZywgY2FjaGU6IHtbcGF0aDogc3RyaW5nXTogc3RyaW5nfSk6IHN0cmluZztcclxuICAvLyBGaWxlIG9wZXJhdGlvbnNcclxuICAvKipcclxuICAgKlxyXG4gICAqICoqU3VwcGxlbWVudGFsKio6IEFzeW5jaHJvbm91cyBgdHJ1bmNhdGVgLlxyXG4gICAqIEBtZXRob2QgRmlsZVN5c3RlbSN0cnVuY2F0ZVxyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBwYXRoXHJcbiAgICogQHBhcmFtIHtudW1iZXJ9IGxlblxyXG4gICAqIEBwYXJhbSB7RmlsZVN5c3RlbX5ub2RlQ2FsbGJhY2t9IGNiXHJcbiAgICovXHJcbiAgdHJ1bmNhdGUocDogc3RyaW5nLCBsZW46IG51bWJlciwgY2I6IEZ1bmN0aW9uKTogdm9pZDtcclxuICAvKipcclxuICAgKiAqKlN1cHBsZW1lbnRhbCoqOiBTeW5jaHJvbm91cyBgdHJ1bmNhdGVgLlxyXG4gICAqIEBtZXRob2QgRmlsZVN5c3RlbSN0cnVuY2F0ZVN5bmNcclxuICAgKiBAcGFyYW0ge3N0cmluZ30gcGF0aFxyXG4gICAqIEBwYXJhbSB7bnVtYmVyfSBsZW5cclxuICAgKi9cclxuICB0cnVuY2F0ZVN5bmMocDogc3RyaW5nLCBsZW46IG51bWJlcik6IHZvaWQ7XHJcbiAgLyoqXHJcbiAgICogKipTdXBwbGVtZW50YWwqKjogQXN5bmNocm9ub3VzbHkgcmVhZHMgdGhlIGVudGlyZSBjb250ZW50cyBvZiBhIGZpbGUuXHJcbiAgICogQG1ldGhvZCBGaWxlU3lzdGVtI3JlYWRGaWxlXHJcbiAgICogQHBhcmFtIHtzdHJpbmd9IGZpbGVuYW1lXHJcbiAgICogQHBhcmFtIHtzdHJpbmd9IGVuY29kaW5nIElmIG5vbi1udWxsLCB0aGUgZmlsZSdzIGNvbnRlbnRzIHNob3VsZCBiZSBkZWNvZGVkXHJcbiAgICogICBpbnRvIGEgc3RyaW5nIHVzaW5nIHRoYXQgZW5jb2RpbmcuIE90aGVyd2lzZSwgaWYgZW5jb2RpbmcgaXMgbnVsbCwgZmV0Y2hcclxuICAgKiAgIHRoZSBmaWxlJ3MgY29udGVudHMgYXMgYSBCdWZmZXIuXHJcbiAgICogQHBhcmFtIHtCcm93c2VyRlMuRmlsZU1vZGV9IGZsYWdcclxuICAgKiBAcGFyYW0ge0ZpbGVTeXN0ZW1+cmVhZENhbGxiYWNrfSBjYiBJZiBubyBlbmNvZGluZyBpcyBzcGVjaWZpZWQsIHRoZW4gdGhlXHJcbiAgICogICByYXcgYnVmZmVyIGlzIHJldHVybmVkLlxyXG4gICAqL1xyXG4gIHJlYWRGaWxlKGZuYW1lOiBzdHJpbmcsIGVuY29kaW5nOiBzdHJpbmcsIGZsYWc6IEZpbGVGbGFnLCBjYjogKGVycjogQXBpRXJyb3IsIGRhdGE/OiBhbnkpID0+IHZvaWQpOiB2b2lkO1xyXG4gIC8qKlxyXG4gICAqICoqU3VwcGxlbWVudGFsKio6IFN5bmNocm9ub3VzbHkgcmVhZHMgdGhlIGVudGlyZSBjb250ZW50cyBvZiBhIGZpbGUuXHJcbiAgICogQG1ldGhvZCBGaWxlU3lzdGVtI3JlYWRGaWxlU3luY1xyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBmaWxlbmFtZVxyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBlbmNvZGluZyBJZiBub24tbnVsbCwgdGhlIGZpbGUncyBjb250ZW50cyBzaG91bGQgYmUgZGVjb2RlZFxyXG4gICAqICAgaW50byBhIHN0cmluZyB1c2luZyB0aGF0IGVuY29kaW5nLiBPdGhlcndpc2UsIGlmIGVuY29kaW5nIGlzIG51bGwsIGZldGNoXHJcbiAgICogICB0aGUgZmlsZSdzIGNvbnRlbnRzIGFzIGEgQnVmZmVyLlxyXG4gICAqIEBwYXJhbSB7QnJvd3NlckZTLkZpbGVNb2RlfSBmbGFnXHJcbiAgICogQHJldHVybiB7KHN0cmluZ3xCcm93c2VyRlMuQnVmZmVyKX1cclxuICAgKi9cclxuICByZWFkRmlsZVN5bmMoZm5hbWU6IHN0cmluZywgZW5jb2Rpbmc6IHN0cmluZywgZmxhZzogRmlsZUZsYWcpOiBhbnk7XHJcbiAgLyoqXHJcbiAgICogKipTdXBwbGVtZW50YWwqKjogQXN5bmNocm9ub3VzbHkgd3JpdGVzIGRhdGEgdG8gYSBmaWxlLCByZXBsYWNpbmcgdGhlIGZpbGVcclxuICAgKiBpZiBpdCBhbHJlYWR5IGV4aXN0cy5cclxuICAgKlxyXG4gICAqIFRoZSBlbmNvZGluZyBvcHRpb24gaXMgaWdub3JlZCBpZiBkYXRhIGlzIGEgYnVmZmVyLlxyXG4gICAqIEBtZXRob2QgRmlsZVN5c3RlbSN3cml0ZUZpbGVcclxuICAgKiBAcGFyYW0ge3N0cmluZ30gZmlsZW5hbWVcclxuICAgKiBAcGFyYW0geyhzdHJpbmcgfCBCcm93c2VyRlMubm9kZS5CdWZmZXIpfSBkYXRhXHJcbiAgICogQHBhcmFtIHtzdHJpbmd9IGVuY29kaW5nXHJcbiAgICogQHBhcmFtIHtCcm93c2VyRlMuRmlsZU1vZGV9IGZsYWdcclxuICAgKiBAcGFyYW0ge251bWJlcn0gbW9kZVxyXG4gICAqIEBwYXJhbSB7RmlsZVN5c3RlbX5ub2RlQ2FsbGJhY2t9IGNiXHJcbiAgICovXHJcbiAgd3JpdGVGaWxlKGZuYW1lOiBzdHJpbmcsIGRhdGE6IGFueSwgZW5jb2Rpbmc6IHN0cmluZywgZmxhZzogRmlsZUZsYWcsIG1vZGU6IG51bWJlciwgY2I6IChlcnI6IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZDtcclxuICAvKipcclxuICAgKiAqKlN1cHBsZW1lbnRhbCoqOiBTeW5jaHJvbm91c2x5IHdyaXRlcyBkYXRhIHRvIGEgZmlsZSwgcmVwbGFjaW5nIHRoZSBmaWxlXHJcbiAgICogaWYgaXQgYWxyZWFkeSBleGlzdHMuXHJcbiAgICpcclxuICAgKiBUaGUgZW5jb2Rpbmcgb3B0aW9uIGlzIGlnbm9yZWQgaWYgZGF0YSBpcyBhIGJ1ZmZlci5cclxuICAgKiBAbWV0aG9kIEZpbGVTeXN0ZW0jd3JpdGVGaWxlU3luY1xyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBmaWxlbmFtZVxyXG4gICAqIEBwYXJhbSB7KHN0cmluZyB8IEJyb3dzZXJGUy5ub2RlLkJ1ZmZlcil9IGRhdGFcclxuICAgKiBAcGFyYW0ge3N0cmluZ30gZW5jb2RpbmdcclxuICAgKiBAcGFyYW0ge0Jyb3dzZXJGUy5GaWxlTW9kZX0gZmxhZ1xyXG4gICAqIEBwYXJhbSB7bnVtYmVyfSBtb2RlXHJcbiAgICovXHJcbiAgd3JpdGVGaWxlU3luYyhmbmFtZTogc3RyaW5nLCBkYXRhOiBhbnksIGVuY29kaW5nOiBzdHJpbmcsIGZsYWc6IEZpbGVGbGFnLCBtb2RlOiBudW1iZXIpOiB2b2lkO1xyXG4gIC8qKlxyXG4gICAqICoqU3VwcGxlbWVudGFsKio6IEFzeW5jaHJvbm91c2x5IGFwcGVuZCBkYXRhIHRvIGEgZmlsZSwgY3JlYXRpbmcgdGhlIGZpbGUgaWZcclxuICAgKiBpdCBub3QgeWV0IGV4aXN0cy5cclxuICAgKiBAbWV0aG9kIEZpbGVTeXN0ZW0jYXBwZW5kRmlsZVxyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBmaWxlbmFtZVxyXG4gICAqIEBwYXJhbSB7KHN0cmluZyB8IEJyb3dzZXJGUy5ub2RlLkJ1ZmZlcil9IGRhdGFcclxuICAgKiBAcGFyYW0ge3N0cmluZ30gZW5jb2RpbmdcclxuICAgKiBAcGFyYW0ge0Jyb3dzZXJGUy5GaWxlTW9kZX0gZmxhZ1xyXG4gICAqIEBwYXJhbSB7bnVtYmVyfSBtb2RlXHJcbiAgICogQHBhcmFtIHtGaWxlU3lzdGVtfm5vZGVDYWxsYmFja30gY2JcclxuICAgKi9cclxuICBhcHBlbmRGaWxlKGZuYW1lOiBzdHJpbmcsIGRhdGE6IGFueSwgZW5jb2Rpbmc6IHN0cmluZywgZmxhZzogRmlsZUZsYWcsIG1vZGU6IG51bWJlciwgY2I6IChlcnI6IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZDtcclxuICAvKipcclxuICAgKiAqKlN1cHBsZW1lbnRhbCoqOiBTeW5jaHJvbm91c2x5IGFwcGVuZCBkYXRhIHRvIGEgZmlsZSwgY3JlYXRpbmcgdGhlIGZpbGUgaWZcclxuICAgKiBpdCBub3QgeWV0IGV4aXN0cy5cclxuICAgKiBAbWV0aG9kIEZpbGVTeXN0ZW0jYXBwZW5kRmlsZVN5bmNcclxuICAgKiBAcGFyYW0ge3N0cmluZ30gZmlsZW5hbWVcclxuICAgKiBAcGFyYW0geyhzdHJpbmcgfCBCcm93c2VyRlMubm9kZS5CdWZmZXIpfSBkYXRhXHJcbiAgICogQHBhcmFtIHtzdHJpbmd9IGVuY29kaW5nXHJcbiAgICogQHBhcmFtIHtCcm93c2VyRlMuRmlsZU1vZGV9IGZsYWdcclxuICAgKiBAcGFyYW0ge251bWJlcn0gbW9kZVxyXG4gICAqL1xyXG4gIGFwcGVuZEZpbGVTeW5jKGZuYW1lOiBzdHJpbmcsIGRhdGE6IGFueSwgZW5jb2Rpbmc6IHN0cmluZywgZmxhZzogRmlsZUZsYWcsIG1vZGU6IG51bWJlcik6IHZvaWQ7XHJcbiAgLy8gKipPUFRJT05BTCBJTlRFUkZBQ0UgTUVUSE9EUyoqXHJcbiAgLy8gUHJvcGVydHkgb3BlcmF0aW9uc1xyXG4gIC8vIFRoaXMgaXNuJ3QgYWx3YXlzIHBvc3NpYmxlIG9uIHNvbWUgZmlsZXN5c3RlbSB0eXBlcyAoZS5nLiBEcm9wYm94KS5cclxuICAvKipcclxuICAgKiAqKk9wdGlvbmFsKio6IEFzeW5jaHJvbm91cyBgY2htb2RgIG9yIGBsY2htb2RgLlxyXG4gICAqIEBtZXRob2QgRmlsZVN5c3RlbSNjaG1vZFxyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBwYXRoXHJcbiAgICogQHBhcmFtIHtib29sZWFufSBpc0xjaG1vZCBgVHJ1ZWAgaWYgYGxjaG1vZGAsIGZhbHNlIGlmIGBjaG1vZGAuIEhhcyBub1xyXG4gICAqICAgYmVhcmluZyBvbiByZXN1bHQgaWYgbGlua3MgYXJlbid0IHN1cHBvcnRlZC5cclxuICAgKiBAcGFyYW0ge251bWJlcn0gbW9kZVxyXG4gICAqIEBwYXJhbSB7RmlsZVN5c3RlbX5ub2RlQ2FsbGJhY2t9IGNiXHJcbiAgICovXHJcbiAgY2htb2QocDogc3RyaW5nLCBpc0xjaG1vZDogYm9vbGVhbiwgbW9kZTogbnVtYmVyLCBjYjogRnVuY3Rpb24pOiB2b2lkO1xyXG4gIC8qKlxyXG4gICAqICoqT3B0aW9uYWwqKjogU3luY2hyb25vdXMgYGNobW9kYCBvciBgbGNobW9kYC5cclxuICAgKiBAbWV0aG9kIEZpbGVTeXN0ZW0jY2htb2RTeW5jXHJcbiAgICogQHBhcmFtIHtzdHJpbmd9IHBhdGhcclxuICAgKiBAcGFyYW0ge2Jvb2xlYW59IGlzTGNobW9kIGBUcnVlYCBpZiBgbGNobW9kYCwgZmFsc2UgaWYgYGNobW9kYC4gSGFzIG5vXHJcbiAgICogICBiZWFyaW5nIG9uIHJlc3VsdCBpZiBsaW5rcyBhcmVuJ3Qgc3VwcG9ydGVkLlxyXG4gICAqIEBwYXJhbSB7bnVtYmVyfSBtb2RlXHJcbiAgICovXHJcbiAgY2htb2RTeW5jKHA6IHN0cmluZywgaXNMY2htb2Q6IGJvb2xlYW4sIG1vZGU6IG51bWJlcik6IHZvaWQ7XHJcbiAgLyoqXHJcbiAgICogKipPcHRpb25hbCoqOiBBc3luY2hyb25vdXMgYGNob3duYCBvciBgbGNob3duYC5cclxuICAgKiBAbWV0aG9kIEZpbGVTeXN0ZW0jY2hvd25cclxuICAgKiBAcGFyYW0ge3N0cmluZ30gcGF0aFxyXG4gICAqIEBwYXJhbSB7Ym9vbGVhbn0gaXNMY2hvd24gYFRydWVgIGlmIGBsY2hvd25gLCBmYWxzZSBpZiBgY2hvd25gLiBIYXMgbm9cclxuICAgKiAgIGJlYXJpbmcgb24gcmVzdWx0IGlmIGxpbmtzIGFyZW4ndCBzdXBwb3J0ZWQuXHJcbiAgICogQHBhcmFtIHtudW1iZXJ9IHVpZFxyXG4gICAqIEBwYXJhbSB7bnVtYmVyfSBnaWRcclxuICAgKiBAcGFyYW0ge0ZpbGVTeXN0ZW1+bm9kZUNhbGxiYWNrfSBjYlxyXG4gICAqL1xyXG4gIGNob3duKHA6IHN0cmluZywgaXNMY2hvd246IGJvb2xlYW4sIHVpZDogbnVtYmVyLCBnaWQ6IG51bWJlciwgY2I6IEZ1bmN0aW9uKTogdm9pZDtcclxuICAvKipcclxuICAgKiAqKk9wdGlvbmFsKio6IFN5bmNocm9ub3VzIGBjaG93bmAgb3IgYGxjaG93bmAuXHJcbiAgICogQG1ldGhvZCBGaWxlU3lzdGVtI2Nob3duU3luY1xyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBwYXRoXHJcbiAgICogQHBhcmFtIHtib29sZWFufSBpc0xjaG93biBgVHJ1ZWAgaWYgYGxjaG93bmAsIGZhbHNlIGlmIGBjaG93bmAuIEhhcyBub1xyXG4gICAqICAgYmVhcmluZyBvbiByZXN1bHQgaWYgbGlua3MgYXJlbid0IHN1cHBvcnRlZC5cclxuICAgKiBAcGFyYW0ge251bWJlcn0gdWlkXHJcbiAgICogQHBhcmFtIHtudW1iZXJ9IGdpZFxyXG4gICAqL1xyXG4gIGNob3duU3luYyhwOiBzdHJpbmcsIGlzTGNob3duOiBib29sZWFuLCB1aWQ6IG51bWJlciwgZ2lkOiBudW1iZXIpOiB2b2lkO1xyXG4gIC8qKlxyXG4gICAqICoqT3B0aW9uYWwqKjogQ2hhbmdlIGZpbGUgdGltZXN0YW1wcyBvZiB0aGUgZmlsZSByZWZlcmVuY2VkIGJ5IHRoZSBzdXBwbGllZFxyXG4gICAqIHBhdGguXHJcbiAgICogQG1ldGhvZCBGaWxlU3lzdGVtI3V0aW1lc1xyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBwYXRoXHJcbiAgICogQHBhcmFtIHtEYXRlfSBhdGltZVxyXG4gICAqIEBwYXJhbSB7RGF0ZX0gbXRpbWVcclxuICAgKiBAcGFyYW0ge0ZpbGVTeXN0ZW1+bm9kZUNhbGxiYWNrfSBjYlxyXG4gICAqL1xyXG4gIHV0aW1lcyhwOiBzdHJpbmcsIGF0aW1lOiBEYXRlLCBtdGltZTogRGF0ZSwgY2I6IEZ1bmN0aW9uKTogdm9pZDtcclxuICAvKipcclxuICAgKiAqKk9wdGlvbmFsKio6IENoYW5nZSBmaWxlIHRpbWVzdGFtcHMgb2YgdGhlIGZpbGUgcmVmZXJlbmNlZCBieSB0aGUgc3VwcGxpZWRcclxuICAgKiBwYXRoLlxyXG4gICAqIEBtZXRob2QgRmlsZVN5c3RlbSN1dGltZXNTeW5jXHJcbiAgICogQHBhcmFtIHtzdHJpbmd9IHBhdGhcclxuICAgKiBAcGFyYW0ge0RhdGV9IGF0aW1lXHJcbiAgICogQHBhcmFtIHtEYXRlfSBtdGltZVxyXG4gICAqL1xyXG4gIHV0aW1lc1N5bmMocDogc3RyaW5nLCBhdGltZTogRGF0ZSwgbXRpbWU6IERhdGUpOiB2b2lkO1xyXG4gIC8vIFN5bWxpbmsgb3BlcmF0aW9uc1xyXG4gIC8vIFN5bWxpbmtzIGFyZW4ndCBhbHdheXMgc3VwcG9ydGVkLlxyXG4gIC8qKlxyXG4gICAqICoqT3B0aW9uYWwqKjogQXN5bmNocm9ub3VzIGBsaW5rYC5cclxuICAgKiBAbWV0aG9kIEZpbGVTeXN0ZW0jbGlua1xyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBzcmNwYXRoXHJcbiAgICogQHBhcmFtIHtzdHJpbmd9IGRzdHBhdGhcclxuICAgKiBAcGFyYW0ge0ZpbGVTeXN0ZW1+bm9kZUNhbGxiYWNrfSBjYlxyXG4gICAqL1xyXG4gIGxpbmsoc3JjcGF0aDogc3RyaW5nLCBkc3RwYXRoOiBzdHJpbmcsIGNiOiBGdW5jdGlvbik6IHZvaWQ7XHJcbiAgLyoqXHJcbiAgICogKipPcHRpb25hbCoqOiBTeW5jaHJvbm91cyBgbGlua2AuXHJcbiAgICogQG1ldGhvZCBGaWxlU3lzdGVtI2xpbmtTeW5jXHJcbiAgICogQHBhcmFtIHtzdHJpbmd9IHNyY3BhdGhcclxuICAgKiBAcGFyYW0ge3N0cmluZ30gZHN0cGF0aFxyXG4gICAqL1xyXG4gIGxpbmtTeW5jKHNyY3BhdGg6IHN0cmluZywgZHN0cGF0aDogc3RyaW5nKTogdm9pZDtcclxuICAvKipcclxuICAgKiAqKk9wdGlvbmFsKio6IEFzeW5jaHJvbm91cyBgc3ltbGlua2AuXHJcbiAgICogQG1ldGhvZCBGaWxlU3lzdGVtI3N5bWxpbmtcclxuICAgKiBAcGFyYW0ge3N0cmluZ30gc3JjcGF0aFxyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBkc3RwYXRoXHJcbiAgICogQHBhcmFtIHtzdHJpbmd9IHR5cGUgY2FuIGJlIGVpdGhlciBgJ2RpcidgIG9yIGAnZmlsZSdgXHJcbiAgICogQHBhcmFtIHtGaWxlU3lzdGVtfm5vZGVDYWxsYmFja30gY2JcclxuICAgKi9cclxuICBzeW1saW5rKHNyY3BhdGg6IHN0cmluZywgZHN0cGF0aDogc3RyaW5nLCB0eXBlOiBzdHJpbmcsIGNiOiBGdW5jdGlvbik6IHZvaWQ7XHJcbiAgLyoqXHJcbiAgICogKipPcHRpb25hbCoqOiBTeW5jaHJvbm91cyBgc3ltbGlua2AuXHJcbiAgICogQG1ldGhvZCBGaWxlU3lzdGVtI3N5bWxpbmtTeW5jXHJcbiAgICogQHBhcmFtIHtzdHJpbmd9IHNyY3BhdGhcclxuICAgKiBAcGFyYW0ge3N0cmluZ30gZHN0cGF0aFxyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSB0eXBlIGNhbiBiZSBlaXRoZXIgYCdkaXInYCBvciBgJ2ZpbGUnYFxyXG4gICAqL1xyXG4gIHN5bWxpbmtTeW5jKHNyY3BhdGg6IHN0cmluZywgZHN0cGF0aDogc3RyaW5nLCB0eXBlOiBzdHJpbmcpOiB2b2lkO1xyXG4gIC8qKlxyXG4gICAqICoqT3B0aW9uYWwqKjogQXN5bmNocm9ub3VzIHJlYWRsaW5rLlxyXG4gICAqIEBtZXRob2QgRmlsZVN5c3RlbSNyZWFkbGlua1xyXG4gICAqIEBwYXJhbSB7c3RyaW5nfSBwYXRoXHJcbiAgICogQHBhcmFtIHtGaWxlU3lzdGVtfnBhdGhDYWxsYmFja30gY2FsbGJhY2tcclxuICAgKi9cclxuICByZWFkbGluayhwOiBzdHJpbmcsIGNiOiBGdW5jdGlvbik6IHZvaWQ7XHJcbiAgLyoqXHJcbiAgICogKipPcHRpb25hbCoqOiBTeW5jaHJvbm91cyByZWFkbGluay5cclxuICAgKiBAbWV0aG9kIEZpbGVTeXN0ZW0jcmVhZGxpbmtTeW5jXHJcbiAgICogQHBhcmFtIHtzdHJpbmd9IHBhdGhcclxuICAgKi9cclxuICByZWFkbGlua1N5bmMocDogc3RyaW5nKTogc3RyaW5nO1xyXG59XHJcblxyXG4vKipcclxuICogQ29udGFpbnMgdHlwaW5ncyBmb3Igc3RhdGljIGZ1bmN0aW9ucyBvbiB0aGUgZmlsZSBzeXN0ZW0gY29uc3RydWN0b3IuXHJcbiAqL1xyXG5leHBvcnQgaW50ZXJmYWNlIEZpbGVTeXN0ZW1Db25zdHJ1Y3RvciB7XHJcbiAgLyoqXHJcbiAgICogKipDb3JlKio6IFJldHVybnMgJ3RydWUnIGlmIHRoaXMgZmlsZXN5c3RlbSBpcyBhdmFpbGFibGUgaW4gdGhlIGN1cnJlbnRcclxuICAgKiBlbnZpcm9ubWVudC4gRm9yIGV4YW1wbGUsIGEgYGxvY2FsU3RvcmFnZWAtYmFja2VkIGZpbGVzeXN0ZW0gd2lsbCByZXR1cm5cclxuICAgKiAnZmFsc2UnIGlmIHRoZSBicm93c2VyIGRvZXMgbm90IHN1cHBvcnQgdGhhdCBBUEkuXHJcbiAgICpcclxuICAgKiBEZWZhdWx0cyB0byAnZmFsc2UnLCBhcyB0aGUgRmlsZVN5c3RlbSBiYXNlIGNsYXNzIGlzbid0IHVzYWJsZSBhbG9uZS5cclxuICAgKiBAbWV0aG9kIEZpbGVTeXN0ZW0uaXNBdmFpbGFibGVcclxuICAgKiBAcmV0dXJuIHtib29sZWFufVxyXG4gICAqL1xyXG4gIGlzQXZhaWxhYmxlKCk6IGJvb2xlYW47XHJcbn1cclxuXHJcbi8qKlxyXG4gKiBCYXNpYyBmaWxlc3lzdGVtIGNsYXNzLiBNb3N0IGZpbGVzeXN0ZW1zIHNob3VsZCBleHRlbmQgdGhpcyBjbGFzcywgYXMgaXRcclxuICogcHJvdmlkZXMgZGVmYXVsdCBpbXBsZW1lbnRhdGlvbnMgZm9yIGEgaGFuZGZ1bCBvZiBtZXRob2RzLlxyXG4gKi9cclxuZXhwb3J0IGNsYXNzIEJhc2VGaWxlU3lzdGVtIHtcclxuICBwdWJsaWMgc3VwcG9ydHNMaW5rcygpOiBib29sZWFuIHtcclxuICAgIHJldHVybiBmYWxzZTtcclxuICB9XHJcbiAgcHVibGljIGRpc2tTcGFjZShwOiBzdHJpbmcsIGNiOiAodG90YWw6IG51bWJlciwgZnJlZTogbnVtYmVyKSA9PiBhbnkpOiB2b2lkIHtcclxuICAgIGNiKDAsIDApO1xyXG4gIH1cclxuICAvKipcclxuICAgKiBPcGVucyB0aGUgZmlsZSBhdCBwYXRoIHAgd2l0aCB0aGUgZ2l2ZW4gZmxhZy4gVGhlIGZpbGUgbXVzdCBleGlzdC5cclxuICAgKiBAcGFyYW0gcCBUaGUgcGF0aCB0byBvcGVuLlxyXG4gICAqIEBwYXJhbSBmbGFnIFRoZSBmbGFnIHRvIHVzZSB3aGVuIG9wZW5pbmcgdGhlIGZpbGUuXHJcbiAgICovXHJcbiAgcHVibGljIG9wZW5GaWxlKHA6IHN0cmluZywgZmxhZzogRmlsZUZsYWcsIGNiOiAoZTogQXBpRXJyb3IsIGZpbGU/OiBmaWxlLkZpbGUpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHRocm93IG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRU5PVFNVUCk7XHJcbiAgfVxyXG4gIC8qKlxyXG4gICAqIENyZWF0ZSB0aGUgZmlsZSBhdCBwYXRoIHAgd2l0aCB0aGUgZ2l2ZW4gbW9kZS4gVGhlbiwgb3BlbiBpdCB3aXRoIHRoZSBnaXZlblxyXG4gICAqIGZsYWcuXHJcbiAgICovXHJcbiAgcHVibGljIGNyZWF0ZUZpbGUocDogc3RyaW5nLCBmbGFnOiBGaWxlRmxhZywgbW9kZTogbnVtYmVyLCBjYjogKGU6IEFwaUVycm9yLCBmaWxlPzogZmlsZS5GaWxlKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVOT1RTVVApO1xyXG4gIH1cclxuICBwdWJsaWMgb3BlbihwOiBzdHJpbmcsIGZsYWc6RmlsZUZsYWcsIG1vZGU6IG51bWJlciwgY2I6IChlcnI6IEFwaUVycm9yLCBmZD86IGZpbGUuQmFzZUZpbGUpID0+IGFueSk6IHZvaWQge1xyXG4gICAgdmFyIG11c3RfYmVfZmlsZSA9IChlOiBBcGlFcnJvciwgc3RhdHM/OiBTdGF0cyk6IHZvaWQgPT4ge1xyXG4gICAgICBpZiAoZSkge1xyXG4gICAgICAgIC8vIEZpbGUgZG9lcyBub3QgZXhpc3QuXHJcbiAgICAgICAgc3dpdGNoIChmbGFnLnBhdGhOb3RFeGlzdHNBY3Rpb24oKSkge1xyXG4gICAgICAgICAgY2FzZSBBY3Rpb25UeXBlLkNSRUFURV9GSUxFOlxyXG4gICAgICAgICAgICAvLyBFbnN1cmUgcGFyZW50IGV4aXN0cy5cclxuICAgICAgICAgICAgcmV0dXJuIHRoaXMuc3RhdChwYXRoLmRpcm5hbWUocCksIGZhbHNlLCAoZTogQXBpRXJyb3IsIHBhcmVudFN0YXRzPzogU3RhdHMpID0+IHtcclxuICAgICAgICAgICAgICBpZiAoZSkge1xyXG4gICAgICAgICAgICAgICAgY2IoZSk7XHJcbiAgICAgICAgICAgICAgfSBlbHNlIGlmICghcGFyZW50U3RhdHMuaXNEaXJlY3RvcnkoKSkge1xyXG4gICAgICAgICAgICAgICAgY2IoQXBpRXJyb3IuRU5PVERJUihwYXRoLmRpcm5hbWUocCkpKTtcclxuICAgICAgICAgICAgICB9IGVsc2Uge1xyXG4gICAgICAgICAgICAgICAgdGhpcy5jcmVhdGVGaWxlKHAsIGZsYWcsIG1vZGUsIGNiKTtcclxuICAgICAgICAgICAgICB9XHJcbiAgICAgICAgICAgIH0pO1xyXG4gICAgICAgICAgY2FzZSBBY3Rpb25UeXBlLlRIUk9XX0VYQ0VQVElPTjpcclxuICAgICAgICAgICAgcmV0dXJuIGNiKEFwaUVycm9yLkVOT0VOVChwKSk7XHJcbiAgICAgICAgICBkZWZhdWx0OlxyXG4gICAgICAgICAgICByZXR1cm4gY2IobmV3IEFwaUVycm9yKEVycm9yQ29kZS5FSU5WQUwsICdJbnZhbGlkIEZpbGVGbGFnIG9iamVjdC4nKSk7XHJcbiAgICAgICAgfVxyXG4gICAgICB9IGVsc2Uge1xyXG4gICAgICAgIC8vIEZpbGUgZXhpc3RzLlxyXG4gICAgICAgIGlmIChzdGF0cy5pc0RpcmVjdG9yeSgpKSB7XHJcbiAgICAgICAgICByZXR1cm4gY2IoQXBpRXJyb3IuRUlTRElSKHApKTtcclxuICAgICAgICB9XHJcbiAgICAgICAgc3dpdGNoIChmbGFnLnBhdGhFeGlzdHNBY3Rpb24oKSkge1xyXG4gICAgICAgICAgY2FzZSBBY3Rpb25UeXBlLlRIUk9XX0VYQ0VQVElPTjpcclxuICAgICAgICAgICAgcmV0dXJuIGNiKEFwaUVycm9yLkVFWElTVChwKSk7XHJcbiAgICAgICAgICBjYXNlIEFjdGlvblR5cGUuVFJVTkNBVEVfRklMRTpcclxuICAgICAgICAgICAgLy8gTk9URTogSW4gYSBwcmV2aW91cyBpbXBsZW1lbnRhdGlvbiwgd2UgZGVsZXRlZCB0aGUgZmlsZSBhbmRcclxuICAgICAgICAgICAgLy8gcmUtY3JlYXRlZCBpdC4gSG93ZXZlciwgdGhpcyBjcmVhdGVkIGEgcmFjZSBjb25kaXRpb24gaWYgYW5vdGhlclxyXG4gICAgICAgICAgICAvLyBhc3luY2hyb25vdXMgcmVxdWVzdCB3YXMgdHJ5aW5nIHRvIHJlYWQgdGhlIGZpbGUsIGFzIHRoZSBmaWxlXHJcbiAgICAgICAgICAgIC8vIHdvdWxkIG5vdCBleGlzdCBmb3IgYSBzbWFsbCBwZXJpb2Qgb2YgdGltZS5cclxuICAgICAgICAgICAgcmV0dXJuIHRoaXMub3BlbkZpbGUocCwgZmxhZywgKGU6IEFwaUVycm9yLCBmZD86IGZpbGUuRmlsZSk6IHZvaWQgPT4ge1xyXG4gICAgICAgICAgICAgIGlmIChlKSB7XHJcbiAgICAgICAgICAgICAgICBjYihlKTtcclxuICAgICAgICAgICAgICB9IGVsc2Uge1xyXG4gICAgICAgICAgICAgICAgZmQudHJ1bmNhdGUoMCwgKCkgPT4ge1xyXG4gICAgICAgICAgICAgICAgICBmZC5zeW5jKCgpID0+IHtcclxuICAgICAgICAgICAgICAgICAgICBjYihudWxsLCBmZCk7XHJcbiAgICAgICAgICAgICAgICAgIH0pO1xyXG4gICAgICAgICAgICAgICAgfSk7XHJcbiAgICAgICAgICAgICAgfVxyXG4gICAgICAgICAgICB9KTtcclxuICAgICAgICAgIGNhc2UgQWN0aW9uVHlwZS5OT1A6XHJcbiAgICAgICAgICAgIHJldHVybiB0aGlzLm9wZW5GaWxlKHAsIGZsYWcsIGNiKTtcclxuICAgICAgICAgIGRlZmF1bHQ6XHJcbiAgICAgICAgICAgIHJldHVybiBjYihuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVJTlZBTCwgJ0ludmFsaWQgRmlsZUZsYWcgb2JqZWN0LicpKTtcclxuICAgICAgICB9XHJcbiAgICAgIH1cclxuICAgIH07XHJcbiAgICB0aGlzLnN0YXQocCwgZmFsc2UsIG11c3RfYmVfZmlsZSk7XHJcbiAgfVxyXG4gIHB1YmxpYyByZW5hbWUob2xkUGF0aDogc3RyaW5nLCBuZXdQYXRoOiBzdHJpbmcsIGNiOiAoZXJyPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIGNiKG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRU5PVFNVUCkpO1xyXG4gIH1cclxuICBwdWJsaWMgcmVuYW1lU3luYyhvbGRQYXRoOiBzdHJpbmcsIG5ld1BhdGg6IHN0cmluZyk6IHZvaWQge1xyXG4gICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FTk9UU1VQKTtcclxuICB9XHJcbiAgcHVibGljIHN0YXQocDogc3RyaW5nLCBpc0xzdGF0OiBib29sZWFuLCBjYjogKGVycjogQXBpRXJyb3IsIHN0YXQ/OiBTdGF0cykgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgY2IobmV3IEFwaUVycm9yKEVycm9yQ29kZS5FTk9UU1VQKSk7XHJcbiAgfVxyXG4gIHB1YmxpYyBzdGF0U3luYyhwOiBzdHJpbmcsIGlzTHN0YXQ6IGJvb2xlYW4pOiBTdGF0cyB7XHJcbiAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVOT1RTVVApO1xyXG4gIH1cclxuICAvKipcclxuICAgKiBPcGVucyB0aGUgZmlsZSBhdCBwYXRoIHAgd2l0aCB0aGUgZ2l2ZW4gZmxhZy4gVGhlIGZpbGUgbXVzdCBleGlzdC5cclxuICAgKiBAcGFyYW0gcCBUaGUgcGF0aCB0byBvcGVuLlxyXG4gICAqIEBwYXJhbSBmbGFnIFRoZSBmbGFnIHRvIHVzZSB3aGVuIG9wZW5pbmcgdGhlIGZpbGUuXHJcbiAgICogQHJldHVybiBBIEZpbGUgb2JqZWN0IGNvcnJlc3BvbmRpbmcgdG8gdGhlIG9wZW5lZCBmaWxlLlxyXG4gICAqL1xyXG4gIHB1YmxpYyBvcGVuRmlsZVN5bmMocDogc3RyaW5nLCBmbGFnOiBGaWxlRmxhZyk6IGZpbGUuRmlsZSB7XHJcbiAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVOT1RTVVApO1xyXG4gIH1cclxuICAvKipcclxuICAgKiBDcmVhdGUgdGhlIGZpbGUgYXQgcGF0aCBwIHdpdGggdGhlIGdpdmVuIG1vZGUuIFRoZW4sIG9wZW4gaXQgd2l0aCB0aGUgZ2l2ZW5cclxuICAgKiBmbGFnLlxyXG4gICAqL1xyXG4gIHB1YmxpYyBjcmVhdGVGaWxlU3luYyhwOiBzdHJpbmcsIGZsYWc6IEZpbGVGbGFnLCBtb2RlOiBudW1iZXIpOiBmaWxlLkZpbGUge1xyXG4gICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FTk9UU1VQKTtcclxuICB9XHJcbiAgcHVibGljIG9wZW5TeW5jKHA6IHN0cmluZywgZmxhZzogRmlsZUZsYWcsIG1vZGU6IG51bWJlcik6IGZpbGUuRmlsZSB7XHJcbiAgICAvLyBDaGVjayBpZiB0aGUgcGF0aCBleGlzdHMsIGFuZCBpcyBhIGZpbGUuXHJcbiAgICB2YXIgc3RhdHM6IFN0YXRzO1xyXG4gICAgdHJ5IHtcclxuICAgICAgc3RhdHMgPSB0aGlzLnN0YXRTeW5jKHAsIGZhbHNlKTtcclxuICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgLy8gRmlsZSBkb2VzIG5vdCBleGlzdC5cclxuICAgICAgc3dpdGNoIChmbGFnLnBhdGhOb3RFeGlzdHNBY3Rpb24oKSkge1xyXG4gICAgICAgIGNhc2UgQWN0aW9uVHlwZS5DUkVBVEVfRklMRTpcclxuICAgICAgICAgIC8vIEVuc3VyZSBwYXJlbnQgZXhpc3RzLlxyXG4gICAgICAgICAgdmFyIHBhcmVudFN0YXRzID0gdGhpcy5zdGF0U3luYyhwYXRoLmRpcm5hbWUocCksIGZhbHNlKTtcclxuICAgICAgICAgIGlmICghcGFyZW50U3RhdHMuaXNEaXJlY3RvcnkoKSkge1xyXG4gICAgICAgICAgICB0aHJvdyBBcGlFcnJvci5FTk9URElSKHBhdGguZGlybmFtZShwKSk7XHJcbiAgICAgICAgICB9XHJcbiAgICAgICAgICByZXR1cm4gdGhpcy5jcmVhdGVGaWxlU3luYyhwLCBmbGFnLCBtb2RlKTtcclxuICAgICAgICBjYXNlIEFjdGlvblR5cGUuVEhST1dfRVhDRVBUSU9OOlxyXG4gICAgICAgICAgdGhyb3cgQXBpRXJyb3IuRU5PRU5UKHApO1xyXG4gICAgICAgIGRlZmF1bHQ6XHJcbiAgICAgICAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVJTlZBTCwgJ0ludmFsaWQgRmlsZUZsYWcgb2JqZWN0LicpO1xyXG4gICAgICB9XHJcbiAgICB9XHJcblxyXG4gICAgLy8gRmlsZSBleGlzdHMuXHJcbiAgICBpZiAoc3RhdHMuaXNEaXJlY3RvcnkoKSkge1xyXG4gICAgICB0aHJvdyBBcGlFcnJvci5FSVNESVIocCk7XHJcbiAgICB9XHJcbiAgICBzd2l0Y2ggKGZsYWcucGF0aEV4aXN0c0FjdGlvbigpKSB7XHJcbiAgICAgIGNhc2UgQWN0aW9uVHlwZS5USFJPV19FWENFUFRJT046XHJcbiAgICAgICAgdGhyb3cgQXBpRXJyb3IuRUVYSVNUKHApO1xyXG4gICAgICBjYXNlIEFjdGlvblR5cGUuVFJVTkNBVEVfRklMRTpcclxuICAgICAgICAvLyBEZWxldGUgZmlsZS5cclxuICAgICAgICB0aGlzLnVubGlua1N5bmMocCk7XHJcbiAgICAgICAgLy8gQ3JlYXRlIGZpbGUuIFVzZSB0aGUgc2FtZSBtb2RlIGFzIHRoZSBvbGQgZmlsZS5cclxuICAgICAgICAvLyBOb2RlIGl0c2VsZiBtb2RpZmllcyB0aGUgY3RpbWUgd2hlbiB0aGlzIG9jY3Vycywgc28gdGhpcyBhY3Rpb25cclxuICAgICAgICAvLyB3aWxsIHByZXNlcnZlIHRoYXQgYmVoYXZpb3IgaWYgdGhlIHVuZGVybHlpbmcgZmlsZSBzeXN0ZW1cclxuICAgICAgICAvLyBzdXBwb3J0cyB0aG9zZSBwcm9wZXJ0aWVzLlxyXG4gICAgICAgIHJldHVybiB0aGlzLmNyZWF0ZUZpbGVTeW5jKHAsIGZsYWcsIHN0YXRzLm1vZGUpO1xyXG4gICAgICBjYXNlIEFjdGlvblR5cGUuTk9QOlxyXG4gICAgICAgIHJldHVybiB0aGlzLm9wZW5GaWxlU3luYyhwLCBmbGFnKTtcclxuICAgICAgZGVmYXVsdDpcclxuICAgICAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVJTlZBTCwgJ0ludmFsaWQgRmlsZUZsYWcgb2JqZWN0LicpO1xyXG4gICAgfVxyXG4gIH1cclxuICBwdWJsaWMgdW5saW5rKHA6IHN0cmluZywgY2I6IEZ1bmN0aW9uKTogdm9pZCB7XHJcbiAgICBjYihuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVOT1RTVVApKTtcclxuICB9XHJcbiAgcHVibGljIHVubGlua1N5bmMocDogc3RyaW5nKTogdm9pZCB7XHJcbiAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVOT1RTVVApO1xyXG4gIH1cclxuICBwdWJsaWMgcm1kaXIocDogc3RyaW5nLCBjYjogRnVuY3Rpb24pOiB2b2lkIHtcclxuICAgIGNiKG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRU5PVFNVUCkpO1xyXG4gIH1cclxuICBwdWJsaWMgcm1kaXJTeW5jKHA6IHN0cmluZyk6IHZvaWQge1xyXG4gICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FTk9UU1VQKTtcclxuICB9XHJcbiAgcHVibGljIG1rZGlyKHA6IHN0cmluZywgbW9kZTogbnVtYmVyLCBjYjogRnVuY3Rpb24pOiB2b2lkIHtcclxuICAgIGNiKG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRU5PVFNVUCkpO1xyXG4gIH1cclxuICBwdWJsaWMgbWtkaXJTeW5jKHA6IHN0cmluZywgbW9kZTogbnVtYmVyKTogdm9pZCB7XHJcbiAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVOT1RTVVApO1xyXG4gIH1cclxuICBwdWJsaWMgcmVhZGRpcihwOiBzdHJpbmcsIGNiOiAoZXJyOiBBcGlFcnJvciwgZmlsZXM/OiBzdHJpbmdbXSkgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgY2IobmV3IEFwaUVycm9yKEVycm9yQ29kZS5FTk9UU1VQKSk7XHJcbiAgfVxyXG4gIHB1YmxpYyByZWFkZGlyU3luYyhwOiBzdHJpbmcpOiBzdHJpbmdbXSB7XHJcbiAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVOT1RTVVApO1xyXG4gIH1cclxuICBwdWJsaWMgZXhpc3RzKHA6IHN0cmluZywgY2I6IChleGlzdHM6IGJvb2xlYW4pID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHRoaXMuc3RhdChwLCBudWxsLCBmdW5jdGlvbihlcnIpIHtcclxuICAgICAgY2IoZXJyID09IG51bGwpO1xyXG4gICAgfSk7XHJcbiAgfVxyXG4gIHB1YmxpYyBleGlzdHNTeW5jKHA6IHN0cmluZyk6IGJvb2xlYW4ge1xyXG4gICAgdHJ5IHtcclxuICAgICAgdGhpcy5zdGF0U3luYyhwLCB0cnVlKTtcclxuICAgICAgcmV0dXJuIHRydWU7XHJcbiAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgIHJldHVybiBmYWxzZTtcclxuICAgIH1cclxuICB9XHJcbiAgcHVibGljIHJlYWxwYXRoKHA6IHN0cmluZywgY2FjaGU6IHtbcGF0aDogc3RyaW5nXTogc3RyaW5nfSwgY2I6IChlcnI6IEFwaUVycm9yLCByZXNvbHZlZFBhdGg/OiBzdHJpbmcpID0+IGFueSk6IHZvaWQge1xyXG4gICAgaWYgKHRoaXMuc3VwcG9ydHNMaW5rcygpKSB7XHJcbiAgICAgIC8vIFRoZSBwYXRoIGNvdWxkIGNvbnRhaW4gc3ltbGlua3MuIFNwbGl0IHVwIHRoZSBwYXRoLFxyXG4gICAgICAvLyByZXNvbHZlIGFueSBzeW1saW5rcywgcmV0dXJuIHRoZSByZXNvbHZlZCBzdHJpbmcuXHJcbiAgICAgIHZhciBzcGxpdFBhdGggPSBwLnNwbGl0KHBhdGguc2VwKTtcclxuICAgICAgLy8gVE9ETzogU2ltcGxlciB0byBqdXN0IHBhc3MgdGhyb3VnaCBmaWxlLCBmaW5kIHNlcCBhbmQgc3VjaC5cclxuICAgICAgZm9yICh2YXIgaSA9IDA7IGkgPCBzcGxpdFBhdGgubGVuZ3RoOyBpKyspIHtcclxuICAgICAgICB2YXIgYWRkUGF0aHMgPSBzcGxpdFBhdGguc2xpY2UoMCwgaSArIDEpO1xyXG4gICAgICAgIHNwbGl0UGF0aFtpXSA9IHBhdGguam9pbi5hcHBseShudWxsLCBhZGRQYXRocyk7XHJcbiAgICAgIH1cclxuICAgIH0gZWxzZSB7XHJcbiAgICAgIC8vIE5vIHN5bWxpbmtzLiBXZSBqdXN0IG5lZWQgdG8gdmVyaWZ5IHRoYXQgaXQgZXhpc3RzLlxyXG4gICAgICB0aGlzLmV4aXN0cyhwLCBmdW5jdGlvbihkb2VzRXhpc3QpIHtcclxuICAgICAgICBpZiAoZG9lc0V4aXN0KSB7XHJcbiAgICAgICAgICBjYihudWxsLCBwKTtcclxuICAgICAgICB9IGVsc2Uge1xyXG4gICAgICAgICAgY2IoQXBpRXJyb3IuRU5PRU5UKHApKTtcclxuICAgICAgICB9XHJcbiAgICAgIH0pO1xyXG4gICAgfVxyXG4gIH1cclxuICBwdWJsaWMgcmVhbHBhdGhTeW5jKHA6IHN0cmluZywgY2FjaGU6IHtbcGF0aDogc3RyaW5nXTogc3RyaW5nfSk6IHN0cmluZyB7XHJcbiAgICBpZiAodGhpcy5zdXBwb3J0c0xpbmtzKCkpIHtcclxuICAgICAgLy8gVGhlIHBhdGggY291bGQgY29udGFpbiBzeW1saW5rcy4gU3BsaXQgdXAgdGhlIHBhdGgsXHJcbiAgICAgIC8vIHJlc29sdmUgYW55IHN5bWxpbmtzLCByZXR1cm4gdGhlIHJlc29sdmVkIHN0cmluZy5cclxuICAgICAgdmFyIHNwbGl0UGF0aCA9IHAuc3BsaXQocGF0aC5zZXApO1xyXG4gICAgICAvLyBUT0RPOiBTaW1wbGVyIHRvIGp1c3QgcGFzcyB0aHJvdWdoIGZpbGUsIGZpbmQgc2VwIGFuZCBzdWNoLlxyXG4gICAgICBmb3IgKHZhciBpID0gMDsgaSA8IHNwbGl0UGF0aC5sZW5ndGg7IGkrKykge1xyXG4gICAgICAgIHZhciBhZGRQYXRocyA9IHNwbGl0UGF0aC5zbGljZSgwLCBpICsgMSk7XHJcbiAgICAgICAgc3BsaXRQYXRoW2ldID0gcGF0aC5qb2luLmFwcGx5KG51bGwsIGFkZFBhdGhzKTtcclxuICAgICAgfVxyXG4gICAgfSBlbHNlIHtcclxuICAgICAgLy8gTm8gc3ltbGlua3MuIFdlIGp1c3QgbmVlZCB0byB2ZXJpZnkgdGhhdCBpdCBleGlzdHMuXHJcbiAgICAgIGlmICh0aGlzLmV4aXN0c1N5bmMocCkpIHtcclxuICAgICAgICByZXR1cm4gcDtcclxuICAgICAgfSBlbHNlIHtcclxuICAgICAgICB0aHJvdyBBcGlFcnJvci5FTk9FTlQocCk7XHJcbiAgICAgIH1cclxuICAgIH1cclxuICB9XHJcbiAgcHVibGljIHRydW5jYXRlKHA6IHN0cmluZywgbGVuOiBudW1iZXIsIGNiOiBGdW5jdGlvbik6IHZvaWQge1xyXG4gICAgdGhpcy5vcGVuKHAsIEZpbGVGbGFnLmdldEZpbGVGbGFnKCdyKycpLCAweDFhNCwgKGZ1bmN0aW9uKGVyOiBBcGlFcnJvciwgZmQ/OiBmaWxlLkZpbGUpIHtcclxuICAgICAgaWYgKGVyKSB7XHJcbiAgICAgICAgcmV0dXJuIGNiKGVyKTtcclxuICAgICAgfVxyXG4gICAgICBmZC50cnVuY2F0ZShsZW4sIChmdW5jdGlvbihlcjogYW55KSB7XHJcbiAgICAgICAgZmQuY2xvc2UoKGZ1bmN0aW9uKGVyMjogYW55KSB7XHJcbiAgICAgICAgICBjYihlciB8fCBlcjIpO1xyXG4gICAgICAgIH0pKTtcclxuICAgICAgfSkpO1xyXG4gICAgfSkpO1xyXG4gIH1cclxuICBwdWJsaWMgdHJ1bmNhdGVTeW5jKHA6IHN0cmluZywgbGVuOiBudW1iZXIpOiB2b2lkIHtcclxuICAgIHZhciBmZCA9IHRoaXMub3BlblN5bmMocCwgRmlsZUZsYWcuZ2V0RmlsZUZsYWcoJ3IrJyksIDB4MWE0KTtcclxuICAgIC8vIE5lZWQgdG8gc2FmZWx5IGNsb3NlIEZELCByZWdhcmRsZXNzIG9mIHdoZXRoZXIgb3Igbm90IHRydW5jYXRlIHN1Y2NlZWRzLlxyXG4gICAgdHJ5IHtcclxuICAgICAgZmQudHJ1bmNhdGVTeW5jKGxlbik7XHJcbiAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgIHRocm93IGU7XHJcbiAgICB9IGZpbmFsbHkge1xyXG4gICAgICBmZC5jbG9zZVN5bmMoKTtcclxuICAgIH1cclxuICB9XHJcbiAgcHVibGljIHJlYWRGaWxlKGZuYW1lOiBzdHJpbmcsIGVuY29kaW5nOiBzdHJpbmcsIGZsYWc6IEZpbGVGbGFnLCBjYjogKGVycjogQXBpRXJyb3IsIGRhdGE/OiBhbnkpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIC8vIFdyYXAgY2IgaW4gZmlsZSBjbG9zaW5nIGNvZGUuXHJcbiAgICB2YXIgb2xkQ2IgPSBjYjtcclxuICAgIC8vIEdldCBmaWxlLlxyXG4gICAgdGhpcy5vcGVuKGZuYW1lLCBmbGFnLCAweDFhNCwgZnVuY3Rpb24oZXJyOiBBcGlFcnJvciwgZmQ/OiBmaWxlLkZpbGUpIHtcclxuICAgICAgaWYgKGVycikge1xyXG4gICAgICAgIHJldHVybiBjYihlcnIpO1xyXG4gICAgICB9XHJcbiAgICAgIGNiID0gZnVuY3Rpb24oZXJyOiBBcGlFcnJvciwgYXJnPzogZmlsZS5GaWxlKSB7XHJcbiAgICAgICAgZmQuY2xvc2UoZnVuY3Rpb24oZXJyMjogYW55KSB7XHJcbiAgICAgICAgICBpZiAoZXJyID09IG51bGwpIHtcclxuICAgICAgICAgICAgZXJyID0gZXJyMjtcclxuICAgICAgICAgIH1cclxuICAgICAgICAgIHJldHVybiBvbGRDYihlcnIsIGFyZyk7XHJcbiAgICAgICAgfSk7XHJcbiAgICAgIH07XHJcbiAgICAgIGZkLnN0YXQoZnVuY3Rpb24oZXJyOiBBcGlFcnJvciwgc3RhdD86IFN0YXRzKSB7XHJcbiAgICAgICAgaWYgKGVyciAhPSBudWxsKSB7XHJcbiAgICAgICAgICByZXR1cm4gY2IoZXJyKTtcclxuICAgICAgICB9XHJcbiAgICAgICAgLy8gQWxsb2NhdGUgYnVmZmVyLlxyXG4gICAgICAgIHZhciBidWYgPSBuZXcgQnVmZmVyKHN0YXQuc2l6ZSk7XHJcbiAgICAgICAgZmQucmVhZChidWYsIDAsIHN0YXQuc2l6ZSwgMCwgZnVuY3Rpb24oZXJyKSB7XHJcbiAgICAgICAgICBpZiAoZXJyICE9IG51bGwpIHtcclxuICAgICAgICAgICAgcmV0dXJuIGNiKGVycik7XHJcbiAgICAgICAgICB9IGVsc2UgaWYgKGVuY29kaW5nID09PSBudWxsKSB7XHJcbiAgICAgICAgICAgIHJldHVybiBjYihlcnIsIGJ1Zik7XHJcbiAgICAgICAgICB9XHJcbiAgICAgICAgICB0cnkge1xyXG4gICAgICAgICAgICBjYihudWxsLCBidWYudG9TdHJpbmcoZW5jb2RpbmcpKTtcclxuICAgICAgICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgICAgICAgY2IoZSk7XHJcbiAgICAgICAgICB9XHJcbiAgICAgICAgfSk7XHJcbiAgICAgIH0pO1xyXG4gICAgfSk7XHJcbiAgfVxyXG4gIHB1YmxpYyByZWFkRmlsZVN5bmMoZm5hbWU6IHN0cmluZywgZW5jb2Rpbmc6IHN0cmluZywgZmxhZzogRmlsZUZsYWcpOiBhbnkge1xyXG4gICAgLy8gR2V0IGZpbGUuXHJcbiAgICB2YXIgZmQgPSB0aGlzLm9wZW5TeW5jKGZuYW1lLCBmbGFnLCAweDFhNCk7XHJcbiAgICB0cnkge1xyXG4gICAgICB2YXIgc3RhdCA9IGZkLnN0YXRTeW5jKCk7XHJcbiAgICAgIC8vIEFsbG9jYXRlIGJ1ZmZlci5cclxuICAgICAgdmFyIGJ1ZiA9IG5ldyBCdWZmZXIoc3RhdC5zaXplKTtcclxuICAgICAgZmQucmVhZFN5bmMoYnVmLCAwLCBzdGF0LnNpemUsIDApO1xyXG4gICAgICBmZC5jbG9zZVN5bmMoKTtcclxuICAgICAgaWYgKGVuY29kaW5nID09PSBudWxsKSB7XHJcbiAgICAgICAgcmV0dXJuIGJ1ZjtcclxuICAgICAgfVxyXG4gICAgICByZXR1cm4gYnVmLnRvU3RyaW5nKGVuY29kaW5nKTtcclxuICAgIH0gZmluYWxseSB7XHJcbiAgICAgIGZkLmNsb3NlU3luYygpO1xyXG4gICAgfVxyXG4gIH1cclxuICBwdWJsaWMgd3JpdGVGaWxlKGZuYW1lOiBzdHJpbmcsIGRhdGE6IGFueSwgZW5jb2Rpbmc6IHN0cmluZywgZmxhZzogRmlsZUZsYWcsIG1vZGU6IG51bWJlciwgY2I6IChlcnI6IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICAvLyBXcmFwIGNiIGluIGZpbGUgY2xvc2luZyBjb2RlLlxyXG4gICAgdmFyIG9sZENiID0gY2I7XHJcbiAgICAvLyBHZXQgZmlsZS5cclxuICAgIHRoaXMub3BlbihmbmFtZSwgZmxhZywgMHgxYTQsIGZ1bmN0aW9uKGVycjogQXBpRXJyb3IsIGZkPzpmaWxlLkZpbGUpIHtcclxuICAgICAgaWYgKGVyciAhPSBudWxsKSB7XHJcbiAgICAgICAgcmV0dXJuIGNiKGVycik7XHJcbiAgICAgIH1cclxuICAgICAgY2IgPSBmdW5jdGlvbihlcnI6IEFwaUVycm9yKSB7XHJcbiAgICAgICAgZmQuY2xvc2UoZnVuY3Rpb24oZXJyMjogYW55KSB7XHJcbiAgICAgICAgICBvbGRDYihlcnIgIT0gbnVsbCA/IGVyciA6IGVycjIpO1xyXG4gICAgICAgIH0pO1xyXG4gICAgICB9O1xyXG5cclxuICAgICAgdHJ5IHtcclxuICAgICAgICBpZiAodHlwZW9mIGRhdGEgPT09ICdzdHJpbmcnKSB7XHJcbiAgICAgICAgICBkYXRhID0gbmV3IEJ1ZmZlcihkYXRhLCBlbmNvZGluZyk7XHJcbiAgICAgICAgfVxyXG4gICAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgICAgcmV0dXJuIGNiKGUpO1xyXG4gICAgICB9XHJcbiAgICAgIC8vIFdyaXRlIGludG8gZmlsZS5cclxuICAgICAgZmQud3JpdGUoZGF0YSwgMCwgZGF0YS5sZW5ndGgsIDAsIGNiKTtcclxuICAgIH0pO1xyXG4gIH1cclxuICBwdWJsaWMgd3JpdGVGaWxlU3luYyhmbmFtZTogc3RyaW5nLCBkYXRhOiBhbnksIGVuY29kaW5nOiBzdHJpbmcsIGZsYWc6IEZpbGVGbGFnLCBtb2RlOiBudW1iZXIpOiB2b2lkIHtcclxuICAgIC8vIEdldCBmaWxlLlxyXG4gICAgdmFyIGZkID0gdGhpcy5vcGVuU3luYyhmbmFtZSwgZmxhZywgbW9kZSk7XHJcbiAgICB0cnkge1xyXG4gICAgICBpZiAodHlwZW9mIGRhdGEgPT09ICdzdHJpbmcnKSB7XHJcbiAgICAgICAgZGF0YSA9IG5ldyBCdWZmZXIoZGF0YSwgZW5jb2RpbmcpO1xyXG4gICAgICB9XHJcbiAgICAgIC8vIFdyaXRlIGludG8gZmlsZS5cclxuICAgICAgZmQud3JpdGVTeW5jKGRhdGEsIDAsIGRhdGEubGVuZ3RoLCAwKTtcclxuICAgIH0gZmluYWxseSB7XHJcbiAgICAgIGZkLmNsb3NlU3luYygpO1xyXG4gICAgfVxyXG4gIH1cclxuICBwdWJsaWMgYXBwZW5kRmlsZShmbmFtZTogc3RyaW5nLCBkYXRhOiBhbnksIGVuY29kaW5nOiBzdHJpbmcsIGZsYWc6IEZpbGVGbGFnLCBtb2RlOiBudW1iZXIsIGNiOiAoZXJyOiBBcGlFcnJvcikgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgLy8gV3JhcCBjYiBpbiBmaWxlIGNsb3NpbmcgY29kZS5cclxuICAgIHZhciBvbGRDYiA9IGNiO1xyXG4gICAgdGhpcy5vcGVuKGZuYW1lLCBmbGFnLCBtb2RlLCBmdW5jdGlvbihlcnI6IEFwaUVycm9yLCBmZD86IGZpbGUuRmlsZSkge1xyXG4gICAgICBpZiAoZXJyICE9IG51bGwpIHtcclxuICAgICAgICByZXR1cm4gY2IoZXJyKTtcclxuICAgICAgfVxyXG4gICAgICBjYiA9IGZ1bmN0aW9uKGVycjogQXBpRXJyb3IpIHtcclxuICAgICAgICBmZC5jbG9zZShmdW5jdGlvbihlcnIyOiBhbnkpIHtcclxuICAgICAgICAgIG9sZENiKGVyciAhPSBudWxsID8gZXJyIDogZXJyMik7XHJcbiAgICAgICAgfSk7XHJcbiAgICAgIH07XHJcbiAgICAgIGlmICh0eXBlb2YgZGF0YSA9PT0gJ3N0cmluZycpIHtcclxuICAgICAgICBkYXRhID0gbmV3IEJ1ZmZlcihkYXRhLCBlbmNvZGluZyk7XHJcbiAgICAgIH1cclxuICAgICAgZmQud3JpdGUoZGF0YSwgMCwgZGF0YS5sZW5ndGgsIG51bGwsIGNiKTtcclxuICAgIH0pO1xyXG4gIH1cclxuICBwdWJsaWMgYXBwZW5kRmlsZVN5bmMoZm5hbWU6IHN0cmluZywgZGF0YTogYW55LCBlbmNvZGluZzogc3RyaW5nLCBmbGFnOiBGaWxlRmxhZywgbW9kZTogbnVtYmVyKTogdm9pZCB7XHJcbiAgICB2YXIgZmQgPSB0aGlzLm9wZW5TeW5jKGZuYW1lLCBmbGFnLCBtb2RlKTtcclxuICAgIHRyeSB7XHJcbiAgICAgIGlmICh0eXBlb2YgZGF0YSA9PT0gJ3N0cmluZycpIHtcclxuICAgICAgICBkYXRhID0gbmV3IEJ1ZmZlcihkYXRhLCBlbmNvZGluZyk7XHJcbiAgICAgIH1cclxuICAgICAgZmQud3JpdGVTeW5jKGRhdGEsIDAsIGRhdGEubGVuZ3RoLCBudWxsKTtcclxuICAgIH0gZmluYWxseSB7XHJcbiAgICAgIGZkLmNsb3NlU3luYygpO1xyXG4gICAgfVxyXG4gIH1cclxuICBwdWJsaWMgY2htb2QocDogc3RyaW5nLCBpc0xjaG1vZDogYm9vbGVhbiwgbW9kZTogbnVtYmVyLCBjYjogRnVuY3Rpb24pOiB2b2lkIHtcclxuICAgIGNiKG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRU5PVFNVUCkpO1xyXG4gIH1cclxuICBwdWJsaWMgY2htb2RTeW5jKHA6IHN0cmluZywgaXNMY2htb2Q6IGJvb2xlYW4sIG1vZGU6IG51bWJlcikge1xyXG4gICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FTk9UU1VQKTtcclxuICB9XHJcbiAgcHVibGljIGNob3duKHA6IHN0cmluZywgaXNMY2hvd246IGJvb2xlYW4sIHVpZDogbnVtYmVyLCBnaWQ6IG51bWJlciwgY2I6IEZ1bmN0aW9uKTogdm9pZCB7XHJcbiAgICBjYihuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVOT1RTVVApKTtcclxuICB9XHJcbiAgcHVibGljIGNob3duU3luYyhwOiBzdHJpbmcsIGlzTGNob3duOiBib29sZWFuLCB1aWQ6IG51bWJlciwgZ2lkOiBudW1iZXIpOiB2b2lkIHtcclxuICAgIHRocm93IG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRU5PVFNVUCk7XHJcbiAgfVxyXG4gIHB1YmxpYyB1dGltZXMocDogc3RyaW5nLCBhdGltZTogRGF0ZSwgbXRpbWU6IERhdGUsIGNiOiBGdW5jdGlvbik6IHZvaWQge1xyXG4gICAgY2IobmV3IEFwaUVycm9yKEVycm9yQ29kZS5FTk9UU1VQKSk7XHJcbiAgfVxyXG4gIHB1YmxpYyB1dGltZXNTeW5jKHA6IHN0cmluZywgYXRpbWU6IERhdGUsIG10aW1lOiBEYXRlKTogdm9pZCB7XHJcbiAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVOT1RTVVApO1xyXG4gIH1cclxuICBwdWJsaWMgbGluayhzcmNwYXRoOiBzdHJpbmcsIGRzdHBhdGg6IHN0cmluZywgY2I6IEZ1bmN0aW9uKTogdm9pZCB7XHJcbiAgICBjYihuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVOT1RTVVApKTtcclxuICB9XHJcbiAgcHVibGljIGxpbmtTeW5jKHNyY3BhdGg6IHN0cmluZywgZHN0cGF0aDogc3RyaW5nKTogdm9pZCB7XHJcbiAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVOT1RTVVApO1xyXG4gIH1cclxuICBwdWJsaWMgc3ltbGluayhzcmNwYXRoOiBzdHJpbmcsIGRzdHBhdGg6IHN0cmluZywgdHlwZTogc3RyaW5nLCBjYjogRnVuY3Rpb24pOiB2b2lkIHtcclxuICAgIGNiKG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRU5PVFNVUCkpO1xyXG4gIH1cclxuICBwdWJsaWMgc3ltbGlua1N5bmMoc3JjcGF0aDogc3RyaW5nLCBkc3RwYXRoOiBzdHJpbmcsIHR5cGU6IHN0cmluZyk6IHZvaWQge1xyXG4gICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FTk9UU1VQKTtcclxuICB9XHJcbiAgcHVibGljIHJlYWRsaW5rKHA6IHN0cmluZywgY2I6IEZ1bmN0aW9uKTogdm9pZCB7XHJcbiAgICBjYihuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVOT1RTVVApKTtcclxuICB9XHJcbiAgcHVibGljIHJlYWRsaW5rU3luYyhwOiBzdHJpbmcpOiBzdHJpbmcge1xyXG4gICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FTk9UU1VQKTtcclxuICB9XHJcbn1cclxuXHJcbi8qKlxyXG4gKiBJbXBsZW1lbnRzIHRoZSBhc3luY2hyb25vdXMgQVBJIGluIHRlcm1zIG9mIHRoZSBzeW5jaHJvbm91cyBBUEkuXHJcbiAqIEBjbGFzcyBTeW5jaHJvbm91c0ZpbGVTeXN0ZW1cclxuICovXHJcbmV4cG9ydCBjbGFzcyBTeW5jaHJvbm91c0ZpbGVTeXN0ZW0gZXh0ZW5kcyBCYXNlRmlsZVN5c3RlbSB7XHJcbiAgcHVibGljIHN1cHBvcnRzU3luY2goKTogYm9vbGVhbiB7XHJcbiAgICByZXR1cm4gdHJ1ZTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyByZW5hbWUob2xkUGF0aDogc3RyaW5nLCBuZXdQYXRoOiBzdHJpbmcsIGNiOiBGdW5jdGlvbik6IHZvaWQge1xyXG4gICAgdHJ5IHtcclxuICAgICAgdGhpcy5yZW5hbWVTeW5jKG9sZFBhdGgsIG5ld1BhdGgpO1xyXG4gICAgICBjYigpO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICBjYihlKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIHB1YmxpYyBzdGF0KHA6IHN0cmluZywgaXNMc3RhdDogYm9vbGVhbiwgY2I6IEZ1bmN0aW9uKTogdm9pZCB7XHJcbiAgICB0cnkge1xyXG4gICAgICBjYihudWxsLCB0aGlzLnN0YXRTeW5jKHAsIGlzTHN0YXQpKTtcclxuICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgY2IoZSk7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgb3BlbihwOiBzdHJpbmcsIGZsYWdzOiBGaWxlRmxhZywgbW9kZTogbnVtYmVyLCBjYjogRnVuY3Rpb24pOiB2b2lkIHtcclxuICAgIHRyeSB7XHJcbiAgICAgIGNiKG51bGwsIHRoaXMub3BlblN5bmMocCwgZmxhZ3MsIG1vZGUpKTtcclxuICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgY2IoZSk7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgdW5saW5rKHA6IHN0cmluZywgY2I6IEZ1bmN0aW9uKTogdm9pZCB7XHJcbiAgICB0cnkge1xyXG4gICAgICB0aGlzLnVubGlua1N5bmMocCk7XHJcbiAgICAgIGNiKCk7XHJcbiAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgIGNiKGUpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgcHVibGljIHJtZGlyKHA6IHN0cmluZywgY2I6IEZ1bmN0aW9uKTogdm9pZCB7XHJcbiAgICB0cnkge1xyXG4gICAgICB0aGlzLnJtZGlyU3luYyhwKTtcclxuICAgICAgY2IoKTtcclxuICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgY2IoZSk7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgbWtkaXIocDogc3RyaW5nLCBtb2RlOiBudW1iZXIsIGNiOiBGdW5jdGlvbik6IHZvaWQge1xyXG4gICAgdHJ5IHtcclxuICAgICAgdGhpcy5ta2RpclN5bmMocCwgbW9kZSk7XHJcbiAgICAgIGNiKCk7XHJcbiAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgIGNiKGUpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgcHVibGljIHJlYWRkaXIocDogc3RyaW5nLCBjYjogRnVuY3Rpb24pOiB2b2lkIHtcclxuICAgIHRyeSB7XHJcbiAgICAgIGNiKG51bGwsIHRoaXMucmVhZGRpclN5bmMocCkpO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICBjYihlKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIHB1YmxpYyBjaG1vZChwOiBzdHJpbmcsIGlzTGNobW9kOiBib29sZWFuLCBtb2RlOiBudW1iZXIsIGNiOiBGdW5jdGlvbik6IHZvaWQge1xyXG4gICAgdHJ5IHtcclxuICAgICAgdGhpcy5jaG1vZFN5bmMocCwgaXNMY2htb2QsIG1vZGUpO1xyXG4gICAgICBjYigpO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICBjYihlKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIHB1YmxpYyBjaG93bihwOiBzdHJpbmcsIGlzTGNob3duOiBib29sZWFuLCB1aWQ6IG51bWJlciwgZ2lkOiBudW1iZXIsIGNiOiBGdW5jdGlvbik6IHZvaWQge1xyXG4gICAgdHJ5IHtcclxuICAgICAgdGhpcy5jaG93blN5bmMocCwgaXNMY2hvd24sIHVpZCwgZ2lkKTtcclxuICAgICAgY2IoKTtcclxuICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgY2IoZSk7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgdXRpbWVzKHA6IHN0cmluZywgYXRpbWU6IERhdGUsIG10aW1lOiBEYXRlLCBjYjogRnVuY3Rpb24pOiB2b2lkIHtcclxuICAgIHRyeSB7XHJcbiAgICAgIHRoaXMudXRpbWVzU3luYyhwLCBhdGltZSwgbXRpbWUpO1xyXG4gICAgICBjYigpO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICBjYihlKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIHB1YmxpYyBsaW5rKHNyY3BhdGg6IHN0cmluZywgZHN0cGF0aDogc3RyaW5nLCBjYjogRnVuY3Rpb24pOiB2b2lkIHtcclxuICAgIHRyeSB7XHJcbiAgICAgIHRoaXMubGlua1N5bmMoc3JjcGF0aCwgZHN0cGF0aCk7XHJcbiAgICAgIGNiKCk7XHJcbiAgICB9IGNhdGNoIChlKSB7XHJcbiAgICAgIGNiKGUpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgcHVibGljIHN5bWxpbmsoc3JjcGF0aDogc3RyaW5nLCBkc3RwYXRoOiBzdHJpbmcsIHR5cGU6IHN0cmluZywgY2I6IEZ1bmN0aW9uKTogdm9pZCB7XHJcbiAgICB0cnkge1xyXG4gICAgICB0aGlzLnN5bWxpbmtTeW5jKHNyY3BhdGgsIGRzdHBhdGgsIHR5cGUpO1xyXG4gICAgICBjYigpO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICBjYihlKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIHB1YmxpYyByZWFkbGluayhwOiBzdHJpbmcsIGNiOiBGdW5jdGlvbik6IHZvaWQge1xyXG4gICAgdHJ5IHtcclxuICAgICAgY2IobnVsbCwgdGhpcy5yZWFkbGlua1N5bmMocCkpO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICBjYihlKTtcclxuICAgIH1cclxuICB9XHJcbn1cclxuIl19