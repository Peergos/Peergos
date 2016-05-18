"use strict";
var __extends = (this && this.__extends) || function (d, b) {
    for (var p in b) if (b.hasOwnProperty(p)) d[p] = b[p];
    function __() { this.constructor = d; }
    d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
};
var preload_file = require('../generic/preload_file');
var file_system = require('../core/file_system');
var api_error_1 = require('../core/api_error');
var file_flag_1 = require('../core/file_flag');
var node_fs_stats_1 = require('../core/node_fs_stats');
var path = require('path');
var global = require('../core/global');
var async = require('async');
var util_1 = require('../core/util');
function isDirectoryEntry(entry) {
    return entry.isDirectory;
}
var _getFS = global.webkitRequestFileSystem || global.requestFileSystem || null;
function _requestQuota(type, size, success, errorCallback) {
    if (typeof navigator['webkitPersistentStorage'] !== 'undefined') {
        switch (type) {
            case global.PERSISTENT:
                navigator.webkitPersistentStorage.requestQuota(size, success, errorCallback);
                break;
            case global.TEMPORARY:
                navigator.webkitTemporaryStorage.requestQuota(size, success, errorCallback);
                break;
            default:
                errorCallback(new TypeError("Invalid storage type: " + type));
                break;
        }
    }
    else {
        global.webkitStorageInfo.requestQuota(type, size, success, errorCallback);
    }
}
function _toArray(list) {
    return Array.prototype.slice.call(list || [], 0);
}
var HTML5FSFile = (function (_super) {
    __extends(HTML5FSFile, _super);
    function HTML5FSFile(_fs, _path, _flag, _stat, contents) {
        _super.call(this, _fs, _path, _flag, _stat, contents);
    }
    HTML5FSFile.prototype.sync = function (cb) {
        var _this = this;
        if (this.isDirty()) {
            var opts = {
                create: false
            };
            var _fs = this._fs;
            var success = function (entry) {
                entry.createWriter(function (writer) {
                    var buffer = _this.getBuffer();
                    var blob = new Blob([util_1.buffer2ArrayBuffer(buffer)]);
                    var length = blob.size;
                    writer.onwriteend = function () {
                        writer.onwriteend = null;
                        writer.truncate(length);
                        _this.resetDirty();
                        cb();
                    };
                    writer.onerror = function (err) {
                        cb(_fs.convert(err, _this.getPath(), false));
                    };
                    writer.write(blob);
                });
            };
            var error = function (err) {
                cb(_fs.convert(err, _this.getPath(), false));
            };
            _fs.fs.root.getFile(this.getPath(), opts, success, error);
        }
        else {
            cb();
        }
    };
    HTML5FSFile.prototype.close = function (cb) {
        this.sync(cb);
    };
    return HTML5FSFile;
}(preload_file.PreloadFile));
exports.HTML5FSFile = HTML5FSFile;
var HTML5FS = (function (_super) {
    __extends(HTML5FS, _super);
    function HTML5FS(size, type) {
        if (size === void 0) { size = 5; }
        if (type === void 0) { type = global.PERSISTENT; }
        _super.call(this);
        this.size = 1024 * 1024 * size;
        this.type = type;
    }
    HTML5FS.prototype.getName = function () {
        return 'HTML5 FileSystem';
    };
    HTML5FS.isAvailable = function () {
        return _getFS != null;
    };
    HTML5FS.prototype.isReadOnly = function () {
        return false;
    };
    HTML5FS.prototype.supportsSymlinks = function () {
        return false;
    };
    HTML5FS.prototype.supportsProps = function () {
        return false;
    };
    HTML5FS.prototype.supportsSynch = function () {
        return false;
    };
    HTML5FS.prototype.convert = function (err, p, expectedDir) {
        switch (err.name) {
            case "PathExistsError":
                return api_error_1.ApiError.EEXIST(p);
            case 'QuotaExceededError':
                return api_error_1.ApiError.FileError(api_error_1.ErrorCode.ENOSPC, p);
            case 'NotFoundError':
                return api_error_1.ApiError.ENOENT(p);
            case 'SecurityError':
                return api_error_1.ApiError.FileError(api_error_1.ErrorCode.EACCES, p);
            case 'InvalidModificationError':
                return api_error_1.ApiError.FileError(api_error_1.ErrorCode.EPERM, p);
            case 'TypeMismatchError':
                return api_error_1.ApiError.FileError(expectedDir ? api_error_1.ErrorCode.ENOTDIR : api_error_1.ErrorCode.EISDIR, p);
            case "EncodingError":
            case "InvalidStateError":
            case "NoModificationAllowedError":
            default:
                return api_error_1.ApiError.FileError(api_error_1.ErrorCode.EINVAL, p);
        }
    };
    HTML5FS.prototype.allocate = function (cb) {
        var _this = this;
        if (cb === void 0) { cb = function () { }; }
        var success = function (fs) {
            _this.fs = fs;
            cb();
        };
        var error = function (err) {
            cb(_this.convert(err, "/", true));
        };
        if (this.type === global.PERSISTENT) {
            _requestQuota(this.type, this.size, function (granted) {
                _getFS(_this.type, granted, success, error);
            }, error);
        }
        else {
            _getFS(this.type, this.size, success, error);
        }
    };
    HTML5FS.prototype.empty = function (mainCb) {
        var _this = this;
        this._readdir('/', function (err, entries) {
            if (err) {
                console.error('Failed to empty FS');
                mainCb(err);
            }
            else {
                var finished = function (er) {
                    if (err) {
                        console.error("Failed to empty FS");
                        mainCb(err);
                    }
                    else {
                        mainCb();
                    }
                };
                var deleteEntry = function (entry, cb) {
                    var succ = function () {
                        cb();
                    };
                    var error = function (err) {
                        cb(_this.convert(err, entry.fullPath, !entry.isDirectory));
                    };
                    if (isDirectoryEntry(entry)) {
                        entry.removeRecursively(succ, error);
                    }
                    else {
                        entry.remove(succ, error);
                    }
                };
                async.each(entries, deleteEntry, finished);
            }
        });
    };
    HTML5FS.prototype.rename = function (oldPath, newPath, cb) {
        var _this = this;
        var semaphore = 2, successCount = 0, root = this.fs.root, currentPath = oldPath, error = function (err) {
            if (--semaphore <= 0) {
                cb(_this.convert(err, currentPath, false));
            }
        }, success = function (file) {
            if (++successCount === 2) {
                return cb(new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "Something was identified as both a file and a directory. This should never happen."));
            }
            if (oldPath === newPath) {
                return cb();
            }
            currentPath = path.dirname(newPath);
            root.getDirectory(currentPath, {}, function (parentDir) {
                currentPath = path.basename(newPath);
                file.moveTo(parentDir, currentPath, function (entry) { cb(); }, function (err) {
                    if (file.isDirectory) {
                        currentPath = newPath;
                        _this.unlink(newPath, function (e) {
                            if (e) {
                                error(err);
                            }
                            else {
                                _this.rename(oldPath, newPath, cb);
                            }
                        });
                    }
                    else {
                        error(err);
                    }
                });
            }, error);
        };
        root.getFile(oldPath, {}, success, error);
        root.getDirectory(oldPath, {}, success, error);
    };
    HTML5FS.prototype.stat = function (path, isLstat, cb) {
        var _this = this;
        var opts = {
            create: false
        };
        var loadAsFile = function (entry) {
            var fileFromEntry = function (file) {
                var stat = new node_fs_stats_1["default"](node_fs_stats_1.FileType.FILE, file.size);
                cb(null, stat);
            };
            entry.file(fileFromEntry, failedToLoad);
        };
        var loadAsDir = function (dir) {
            var size = 4096;
            var stat = new node_fs_stats_1["default"](node_fs_stats_1.FileType.DIRECTORY, size);
            cb(null, stat);
        };
        var failedToLoad = function (err) {
            cb(_this.convert(err, path, false));
        };
        var failedToLoadAsFile = function () {
            _this.fs.root.getDirectory(path, opts, loadAsDir, failedToLoad);
        };
        this.fs.root.getFile(path, opts, loadAsFile, failedToLoadAsFile);
    };
    HTML5FS.prototype.open = function (p, flags, mode, cb) {
        var _this = this;
        var error = function (err) {
            if (err.name === 'InvalidModificationError' && flags.isExclusive()) {
                cb(api_error_1.ApiError.EEXIST(p));
            }
            else {
                cb(_this.convert(err, p, false));
            }
        };
        this.fs.root.getFile(p, {
            create: flags.pathNotExistsAction() === file_flag_1.ActionType.CREATE_FILE,
            exclusive: flags.isExclusive()
        }, function (entry) {
            entry.file(function (file) {
                var reader = new FileReader();
                reader.onloadend = function (event) {
                    var bfs_file = _this._makeFile(p, flags, file, reader.result);
                    cb(null, bfs_file);
                };
                reader.onerror = function (ev) {
                    error(reader.error);
                };
                reader.readAsArrayBuffer(file);
            }, error);
        }, error);
    };
    HTML5FS.prototype._statType = function (stat) {
        return stat.isFile ? node_fs_stats_1.FileType.FILE : node_fs_stats_1.FileType.DIRECTORY;
    };
    HTML5FS.prototype._makeFile = function (path, flag, stat, data) {
        if (data === void 0) { data = new ArrayBuffer(0); }
        var stats = new node_fs_stats_1["default"](node_fs_stats_1.FileType.FILE, stat.size);
        var buffer = util_1.arrayBuffer2Buffer(data);
        return new HTML5FSFile(this, path, flag, stats, buffer);
    };
    HTML5FS.prototype._remove = function (path, cb, isFile) {
        var _this = this;
        var success = function (entry) {
            var succ = function () {
                cb();
            };
            var err = function (err) {
                cb(_this.convert(err, path, !isFile));
            };
            entry.remove(succ, err);
        };
        var error = function (err) {
            cb(_this.convert(err, path, !isFile));
        };
        var opts = {
            create: false
        };
        if (isFile) {
            this.fs.root.getFile(path, opts, success, error);
        }
        else {
            this.fs.root.getDirectory(path, opts, success, error);
        }
    };
    HTML5FS.prototype.unlink = function (path, cb) {
        this._remove(path, cb, true);
    };
    HTML5FS.prototype.rmdir = function (path, cb) {
        var _this = this;
        this.readdir(path, function (e, files) {
            if (e) {
                cb(e);
            }
            else if (files.length > 0) {
                cb(api_error_1.ApiError.ENOTEMPTY(path));
            }
            else {
                _this._remove(path, cb, false);
            }
        });
    };
    HTML5FS.prototype.mkdir = function (path, mode, cb) {
        var _this = this;
        var opts = {
            create: true,
            exclusive: true
        };
        var success = function (dir) {
            cb();
        };
        var error = function (err) {
            cb(_this.convert(err, path, true));
        };
        this.fs.root.getDirectory(path, opts, success, error);
    };
    HTML5FS.prototype._readdir = function (path, cb) {
        var _this = this;
        var error = function (err) {
            cb(_this.convert(err, path, true));
        };
        this.fs.root.getDirectory(path, { create: false }, function (dirEntry) {
            var reader = dirEntry.createReader();
            var entries = [];
            var readEntries = function () {
                reader.readEntries((function (results) {
                    if (results.length) {
                        entries = entries.concat(_toArray(results));
                        readEntries();
                    }
                    else {
                        cb(null, entries);
                    }
                }), error);
            };
            readEntries();
        }, error);
    };
    HTML5FS.prototype.readdir = function (path, cb) {
        this._readdir(path, function (e, entries) {
            if (e) {
                return cb(e);
            }
            var rv = [];
            for (var i = 0; i < entries.length; i++) {
                rv.push(entries[i].name);
            }
            cb(null, rv);
        });
    };
    return HTML5FS;
}(file_system.BaseFileSystem));
exports.__esModule = true;
exports["default"] = HTML5FS;
//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJmaWxlIjoiSFRNTDVGUy5qcyIsInNvdXJjZVJvb3QiOiIiLCJzb3VyY2VzIjpbIi4uLy4uLy4uL3NyYy9iYWNrZW5kL0hUTUw1RlMudHMiXSwibmFtZXMiOltdLCJtYXBwaW5ncyI6Ijs7Ozs7O0FBQUEsSUFBTyxZQUFZLFdBQVcseUJBQXlCLENBQUMsQ0FBQztBQUN6RCxJQUFPLFdBQVcsV0FBVyxxQkFBcUIsQ0FBQyxDQUFDO0FBQ3BELDBCQUFrQyxtQkFBbUIsQ0FBQyxDQUFBO0FBQ3RELDBCQUFtQyxtQkFBbUIsQ0FBQyxDQUFBO0FBQ3ZELDhCQUF5Qyx1QkFBdUIsQ0FBQyxDQUFBO0FBRWpFLElBQU8sSUFBSSxXQUFXLE1BQU0sQ0FBQyxDQUFDO0FBQzlCLElBQU8sTUFBTSxXQUFXLGdCQUFnQixDQUFDLENBQUM7QUFDMUMsSUFBTyxLQUFLLFdBQVcsT0FBTyxDQUFDLENBQUM7QUFDaEMscUJBQXFELGNBQWMsQ0FBQyxDQUFBO0FBRXBFLDBCQUEwQixLQUFZO0lBQ3BDLE1BQU0sQ0FBQyxLQUFLLENBQUMsV0FBVyxDQUFDO0FBQzNCLENBQUM7QUFFRCxJQUFJLE1BQU0sR0FBMkcsTUFBTSxDQUFDLHVCQUF1QixJQUFJLE1BQU0sQ0FBQyxpQkFBaUIsSUFBSSxJQUFJLENBQUM7QUFFeEwsdUJBQXVCLElBQVksRUFBRSxJQUFZLEVBQUUsT0FBK0IsRUFBRSxhQUE0QjtJQU05RyxFQUFFLENBQUMsQ0FBQyxPQUFjLFNBQVUsQ0FBQyx5QkFBeUIsQ0FBQyxLQUFLLFdBQVcsQ0FBQyxDQUFDLENBQUM7UUFDeEUsTUFBTSxDQUFBLENBQUMsSUFBSSxDQUFDLENBQUMsQ0FBQztZQUNaLEtBQUssTUFBTSxDQUFDLFVBQVU7Z0JBQ2IsU0FBVSxDQUFDLHVCQUF1QixDQUFDLFlBQVksQ0FBQyxJQUFJLEVBQUUsT0FBTyxFQUFFLGFBQWEsQ0FBQyxDQUFDO2dCQUNyRixLQUFLLENBQUM7WUFDUixLQUFLLE1BQU0sQ0FBQyxTQUFTO2dCQUNaLFNBQVUsQ0FBQyxzQkFBc0IsQ0FBQyxZ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LE9BQU8sQ0FBQyxDQUFDLENBQUMsQ0FBQyxJQUFJLENBQUMsQ0FBQztZQUMzQixDQUFDO1lBQ0QsRUFBRSxDQUFDLElBQUksRUFBRSxFQUFFLENBQUMsQ0FBQztRQUNmLENBQUMsQ0FBQyxDQUFDO0lBQ0wsQ0FBQztJQUNILGNBQUM7QUFBRCxDQUFDLEFBdFlELENBQXFDLFdBQVcsQ0FBQyxjQUFjLEdBc1k5RDtBQXRZRDs0QkFzWUMsQ0FBQSIsInNvdXJjZXNDb250ZW50IjpbImltcG9ydCBwcmVsb2FkX2ZpbGUgPSByZXF1aXJlKCcuLi9nZW5lcmljL3ByZWxvYWRfZmlsZScpO1xyXG5pbXBvcnQgZmlsZV9zeXN0ZW0gPSByZXF1aXJlKCcuLi9jb3JlL2ZpbGVfc3lzdGVtJyk7XHJcbmltcG9ydCB7QXBpRXJyb3IsIEVycm9yQ29kZX0gZnJvbSAnLi4vY29yZS9hcGlfZXJyb3InO1xyXG5pbXBvcnQge0ZpbGVGbGFnLCBBY3Rpb25UeXBlfSBmcm9tICcuLi9jb3JlL2ZpbGVfZmxhZyc7XHJcbmltcG9ydCB7ZGVmYXVsdCBhcyBTdGF0cywgRmlsZVR5cGV9IGZyb20gJy4uL2NvcmUvbm9kZV9mc19zdGF0cyc7XHJcbmltcG9ydCBmaWxlID0gcmVxdWlyZSgnLi4vY29yZS9maWxlJyk7XHJcbmltcG9ydCBwYXRoID0gcmVxdWlyZSgncGF0aCcpO1xyXG5pbXBvcnQgZ2xvYmFsID0gcmVxdWlyZSgnLi4vY29yZS9nbG9iYWwnKTtcclxuaW1wb3J0IGFzeW5jID0gcmVxdWlyZSgnYXN5bmMnKTtcclxuaW1wb3J0IHtidWZmZXIyQXJyYXlCdWZmZXIsIGFycmF5QnVmZmVyMkJ1ZmZlcn0gZnJvbSAnLi4vY29yZS91dGlsJztcclxuXHJcbmZ1bmN0aW9uIGlzRGlyZWN0b3J5RW50cnkoZW50cnk6IEVudHJ5KTogZW50cnkgaXMgRGlyZWN0b3J5RW50cnkge1xyXG4gIHJldHVybiBlbnRyeS5pc0RpcmVjdG9yeTtcclxufVxyXG5cclxudmFyIF9nZXRGUzogKHR5cGU6bnVtYmVyLCBzaXplOm51bWJlciwgc3VjY2Vzc0NhbGxiYWNrOiBGaWxlU3lzdGVtQ2FsbGJhY2ssIGVycm9yQ2FsbGJhY2s/OiBFcnJvckNhbGxiYWNrKSA9PiB2b2lkID0gZ2xvYmFsLndlYmtpdFJlcXVlc3RGaWxlU3lzdGVtIHx8IGdsb2JhbC5yZXF1ZXN0RmlsZVN5c3RlbSB8fCBudWxsO1xyXG5cclxuZnVuY3Rpb24gX3JlcXVlc3RRdW90YSh0eXBlOiBudW1iZXIsIHNpemU6IG51bWJlciwgc3VjY2VzczogKHNpemU6IG51bWJlcikgPT4gdm9pZCwgZXJyb3JDYWxsYmFjazogRXJyb3JDYWxsYmFjaykge1xyXG4gIC8vIFdlIGNhc3QgbmF2aWdhdG9yIGFuZCB3aW5kb3cgdG8gJzxhbnk+JyBiZWNhdXNlIGV2ZXJ5dGhpbmcgaGVyZSBpc1xyXG4gIC8vIG5vbnN0YW5kYXJkIGZ1bmN0aW9uYWxpdHksIGRlc3BpdGUgdGhlIGZhY3QgdGhhdCBDaHJvbWUgaGFzIHRoZSBvbmx5XHJcbiAgLy8gaW1wbGVtZW50YXRpb24gb2YgdGhlIEhUTUw1RlMgYW5kIGlzIGxpa2VseSBkcml2aW5nIHRoZSBzdGFuZGFyZGl6YXRpb25cclxuICAvLyBwcm9jZXNzLiBUaHVzLCB0aGVzZSBvYmplY3RzIGRlZmluZWQgb2ZmIG9mIG5hdmlnYXRvciBhbmQgd2luZG93IGFyZSBub3RcclxuICAvLyBwcmVzZW50IGluIHRoZSBEZWZpbml0ZWx5VHlwZWQgVHlwZVNjcmlwdCB0eXBpbmdzIGZvciBGaWxlU3lzdGVtLlxyXG4gIGlmICh0eXBlb2YgKDxhbnk+IG5hdmlnYXRvcilbJ3dlYmtpdFBlcnNpc3RlbnRTdG9yYWdlJ10gIT09ICd1bmRlZmluZWQnKSB7XHJcbiAgICBzd2l0Y2godHlwZSkge1xyXG4gICAgICBjYXNlIGdsb2JhbC5QRVJTSVNURU5UOlxyXG4gICAgICAgICg8YW55PiBuYXZpZ2F0b3IpLndlYmtpdFBlcnNpc3RlbnRTdG9yYWdlLnJlcXVlc3RRdW90YShzaXplLCBzdWNjZXNzLCBlcnJvckNhbGxiYWNrKTtcclxuICAgICAgICBicmVhaztcclxuICAgICAgY2FzZSBnbG9iYWwuVEVNUE9SQVJZOlxyXG4gICAgICAgICg8YW55PiBuYXZpZ2F0b3IpLndlYmtpdFRlbXBvcmFyeVN0b3JhZ2UucmVxdWVzdFF1b3RhKHNpemUsIHN1Y2Nlc3MsIGVycm9yQ2FsbGJhY2spO1xyXG4gICAgICAgIGJyZWFrXHJcbiAgICAgIGRlZmF1bHQ6XHJcbiAgICAgICAgZXJyb3JDYWxsYmFjayhuZXcgVHlwZUVycm9yKGBJbnZhbGlkIHN0b3JhZ2UgdHlwZTogJHt0eXBlfWApKTtcclxuICAgICAgICBicmVhaztcclxuICAgIH1cclxuICB9IGVsc2Uge1xyXG4gICAgKDxhbnk+IGdsb2JhbCkud2Via2l0U3RvcmFnZUluZm8ucmVxdWVzdFF1b3RhKHR5cGUsIHNpemUsIHN1Y2Nlc3MsIGVycm9yQ2FsbGJhY2spO1xyXG4gIH1cclxufVxyXG5cclxuZnVuY3Rpb24gX3RvQXJyYXkobGlzdD86IGFueVtdKTogYW55W10ge1xyXG4gIHJldHVybiBBcnJheS5wcm90b3R5cGUuc2xpY2UuY2FsbChsaXN0IHx8IFtdLCAwKTtcclxufVxyXG5cclxuLy8gQSBub3RlIGFib3V0IGdldEZpbGUgYW5kIGdldERpcmVjdG9yeSBvcHRpb25zOlxyXG4vLyBUaGVzZSBtZXRob2RzIGFyZSBjYWxsZWQgYXQgbnVtZXJvdXMgcGxhY2VzIGluIHRoaXMgZmlsZSwgYW5kIGFyZSBwYXNzZWRcclxuLy8gc29tZSBjb21iaW5hdGlvbiBvZiB0aGVzZSB0d28gb3B0aW9uczpcclxuLy8gICAtIGNyZWF0ZTogSWYgdHJ1ZSwgdGhlIGVudHJ5IHdpbGwgYmUgY3JlYXRlZCBpZiBpdCBkb2Vzbid0IGV4aXN0LlxyXG4vLyAgICAgICAgICAgICBJZiBmYWxzZSwgYW4gZXJyb3Igd2lsbCBiZSB0aHJvd24gaWYgaXQgZG9lc24ndCBleGlzdC5cclxuLy8gICAtIGV4Y2x1c2l2ZTogSWYgdHJ1ZSwgb25seSBjcmVhdGUgdGhlIGVudHJ5IGlmIGl0IGRvZXNuJ3QgYWxyZWFkeSBleGlzdCxcclxuLy8gICAgICAgICAgICAgICAgYW5kIHRocm93IGFuIGVycm9yIGlmIGl0IGRvZXMuXHJcblxyXG5leHBvcnQgY2xhc3MgSFRNTDVGU0ZpbGUgZXh0ZW5kcyBwcmVsb2FkX2ZpbGUuUHJlbG9hZEZpbGU8SFRNTDVGUz4gaW1wbGVtZW50cyBmaWxlLkZpbGUge1xyXG4gIGNvbnN0cnVjdG9yKF9mczogSFRNTDVGUywgX3BhdGg6IHN0cmluZywgX2ZsYWc6IEZpbGVGbGFnLCBfc3RhdDogU3RhdHMsIGNvbnRlbnRzPzogTm9kZUJ1ZmZlcikge1xyXG4gICAgc3VwZXIoX2ZzLCBfcGF0aCwgX2ZsYWcsIF9zdGF0LCBjb250ZW50cyk7XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgc3luYyhjYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgaWYgKHRoaXMuaXNEaXJ0eSgpKSB7XHJcbiAgICAgIC8vIERvbid0IGNyZWF0ZSB0aGUgZmlsZSAoaXQgc2hvdWxkIGFscmVhZHkgaGF2ZSBiZWVuIGNyZWF0ZWQgYnkgYG9wZW5gKVxyXG4gICAgICB2YXIgb3B0cyA9IHtcclxuICAgICAgICBjcmVhdGU6IGZhbHNlXHJcbiAgICAgIH07XHJcbiAgICAgIHZhciBfZnMgPSB0aGlzLl9mcztcclxuICAgICAgdmFyIHN1Y2Nlc3M6IEZpbGVFbnRyeUNhbGxiYWNrID0gKGVudHJ5KSA9PiB7XHJcbiAgICAgICAgZW50cnkuY3JlYXRlV3JpdGVyKCh3cml0ZXIpID0+IHtcclxuICAgICAgICAgIHZhciBidWZmZXIgPSB0aGlzLmdldEJ1ZmZlcigpO1xyXG4gICAgICAgICAgdmFyIGJsb2IgPSBuZXcgQmxvYihbYnVmZmVyMkFycmF5QnVmZmVyKGJ1ZmZlcildKTtcclxuICAgICAgICAgIHZhciBsZW5ndGggPSBibG9iLnNpemU7XHJcbiAgICAgICAgICB3cml0ZXIub253cml0ZWVuZCA9ICgpID0+IHtcclxuICAgICAgICAgICAgd3JpdGVyLm9ud3JpdGVlbmQgPSBudWxsO1xyXG4gICAgICAgICAgICB3cml0ZXIudHJ1bmNhdGUobGVuZ3RoKTtcclxuICAgICAgICAgICAgdGhpcy5yZXNldERpcnR5KCk7XHJcbiAgICAgICAgICAgIGNiKCk7XHJcbiAgICAgICAgICB9O1xyXG4gICAgICAgICAgd3JpdGVyLm9uZXJyb3IgPSAoZXJyOiBET01FcnJvcikgPT4ge1xyXG4gICAgICAgICAgICBjYihfZnMuY29udmVydChlcnIsIHRoaXMuZ2V0UGF0aCgpLCBmYWxzZSkpO1xyXG4gICAgICAgICAgfTtcclxuICAgICAgICAgIHdyaXRlci53cml0ZShibG9iKTtcclxuICAgICAgICB9KTtcclxuICAgICAgfTtcclxuICAgICAgdmFyIGVycm9yID0gKGVycjogRE9NRXJyb3IpID0+IHtcclxuICAgICAgICBjYihfZnMuY29udmVydChlcnIsIHRoaXMuZ2V0UGF0aCgpLCBmYWxzZSkpO1xyXG4gICAgICB9O1xyXG4gICAgICBfZnMuZnMucm9vdC5nZXRGaWxlKHRoaXMuZ2V0UGF0aCgpLCBvcHRzLCBzdWNjZXNzLCBlcnJvcik7XHJcbiAgICB9IGVsc2Uge1xyXG4gICAgICBjYigpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgcHVibGljIGNsb3NlKGNiOiAoZT86IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICB0aGlzLnN5bmMoY2IpO1xyXG4gIH1cclxufVxyXG5cclxuZXhwb3J0IGRlZmF1bHQgY2xhc3MgSFRNTDVGUyBleHRlbmRzIGZpbGVfc3lzdGVtLkJhc2VGaWxlU3lzdGVtIGltcGxlbWVudHMgZmlsZV9zeXN0ZW0uRmlsZVN5c3RlbSB7XHJcbiAgcHJpdmF0ZSBzaXplOiBudW1iZXI7XHJcbiAgcHJpdmF0ZSB0eXBlOiBudW1iZXI7XHJcbiAgLy8gSFRNTDVGaWxlIHJlYWNoZXMgaW50byBIVE1MNUZTLiA6L1xyXG4gIHB1YmxpYyBmczogRmlsZVN5c3RlbTtcclxuICAvKipcclxuICAgKiBBcmd1bWVudHM6XHJcbiAgICogICAtIHR5cGU6IFBFUlNJU1RFTlQgb3IgVEVNUE9SQVJZXHJcbiAgICogICAtIHNpemU6IHN0b3JhZ2UgcXVvdGEgdG8gcmVxdWVzdCwgaW4gbWVnYWJ5dGVzLiBBbGxvY2F0ZWQgdmFsdWUgbWF5IGJlIGxlc3MuXHJcbiAgICovXHJcbiAgY29uc3RydWN0b3Ioc2l6ZTogbnVtYmVyID0gNSwgdHlwZTogbnVtYmVyID0gZ2xvYmFsLlBFUlNJU1RFTlQpIHtcclxuICAgIHN1cGVyKCk7XHJcbiAgICAvLyBDb252ZXJ0IE1CIHRvIGJ5dGVzLlxyXG4gICAgdGhpcy5zaXplID0gMTAyNCAqIDEwMjQgKiBzaXplO1xyXG4gICAgdGhpcy50eXBlID0gdHlwZTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBnZXROYW1lKCk6IHN0cmluZyB7XHJcbiAgICByZXR1cm4gJ0hUTUw1IEZpbGVTeXN0ZW0nO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIHN0YXRpYyBpc0F2YWlsYWJsZSgpOiBib29sZWFuIHtcclxuICAgIHJldHVybiBfZ2V0RlMgIT0gbnVsbDtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBpc1JlYWRPbmx5KCk6IGJvb2xlYW4ge1xyXG4gICAgcmV0dXJuIGZhbHNlO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIHN1cHBvcnRzU3ltbGlua3MoKTogYm9vbGVhbiB7XHJcbiAgICByZXR1cm4gZmFsc2U7XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgc3VwcG9ydHNQcm9wcygpOiBib29sZWFuIHtcclxuICAgIHJldHVybiBmYWxzZTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBzdXBwb3J0c1N5bmNoKCk6IGJvb2xlYW4ge1xyXG4gICAgcmV0dXJuIGZhbHNlO1xyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogQ29udmVydHMgdGhlIGdpdmVuIERPTUVycm9yIGludG8gYW4gYXBwcm9wcmlhdGUgQXBpRXJyb3IuXHJcbiAgICogRnVsbCBsaXN0IG9mIHZhbHVlcyBoZXJlOlxyXG4gICAqIGh0dHBzOi8vZGV2ZWxvcGVyLm1vemlsbGEub3JnL2VuLVVTL2RvY3MvV2ViL0FQSS9ET01FcnJvclxyXG4gICAqL1xyXG4gIHB1YmxpYyBjb252ZXJ0KGVycjogRE9NRXJyb3IsIHA6IHN0cmluZywgZXhwZWN0ZWREaXI6IGJvb2xlYW4pOiBBcGlFcnJvciB7XHJcbiAgICBzd2l0Y2ggKGVyci5uYW1lKSB7XHJcbiAgICAgIC8qIFRoZSB1c2VyIGFnZW50IGZhaWxlZCB0byBjcmVhdGUgYSBmaWxlIG9yIGRpcmVjdG9yeSBkdWUgdG8gdGhlIGV4aXN0ZW5jZSBvZiBhIGZpbGUgb3JcclxuICAgICAgICAgZGlyZWN0b3J5IHdpdGggdGhlIHNhbWUgcGF0aC4gICovXHJcbiAgICAgIGNhc2UgXCJQYXRoRXhpc3RzRXJyb3JcIjpcclxuICAgICAgICByZXR1cm4gQXBpRXJyb3IuRUVYSVNUKHApO1xyXG4gICAgICAvKiBUaGUgb3BlcmF0aW9uIGZhaWxlZCBiZWNhdXNlIGl0IHdvdWxkIGNhdXNlIHRoZSBhcHBsaWNhdGlvbiB0byBleGNlZWQgaXRzIHN0b3JhZ2UgcXVvdGEuICAqL1xyXG4gICAgICBjYXNlICdRdW90YUV4Y2VlZGVkRXJyb3InOlxyXG4gICAgICAgIHJldHVybiBBcGlFcnJvci5GaWxlRXJyb3IoRXJyb3JDb2RlLkVOT1NQQywgcCk7XHJcbiAgICAgIC8qICBBIHJlcXVpcmVkIGZpbGUgb3IgZGlyZWN0b3J5IGNvdWxkIG5vdCBiZSBmb3VuZCBhdCB0aGUgdGltZSBhbiBvcGVyYXRpb24gd2FzIHByb2Nlc3NlZC4gICAqL1xyXG4gICAgICBjYXNlICdOb3RGb3VuZEVycm9yJzpcclxuICAgICAgICByZXR1cm4gQXBpRXJyb3IuRU5PRU5UKHApO1xyXG4gICAgICAvKiBUaGlzIGlzIGEgc2VjdXJpdHkgZXJyb3IgY29kZSB0byBiZSB1c2VkIGluIHNpdHVhdGlvbnMgbm90IGNvdmVyZWQgYnkgYW55IG90aGVyIGVycm9yIGNvZGVzLlxyXG4gICAgICAgICAtIEEgcmVxdWlyZWQgZmlsZSB3YXMgdW5zYWZlIGZvciBhY2Nlc3Mgd2l0aGluIGEgV2ViIGFwcGxpY2F0aW9uXHJcbiAgICAgICAgIC0gVG9vIG1hbnkgY2FsbHMgYXJlIGJlaW5nIG1hZGUgb24gZmlsZXN5c3RlbSByZXNvdXJjZXMgKi9cclxuICAgICAgY2FzZSAnU2VjdXJpdHlFcnJvcic6XHJcbiAgICAgICAgcmV0dXJuIEFwaUVycm9yLkZpbGVFcnJvcihFcnJvckNvZGUuRUFDQ0VTLCBwKTtcclxuICAgICAgLyogVGhlIG1vZGlmaWNhdGlvbiByZXF1ZXN0ZWQgd2FzIGlsbGVnYWwuIEV4YW1wbGVzIG9mIGludmFsaWQgbW9kaWZpY2F0aW9ucyBpbmNsdWRlIG1vdmluZyBhXHJcbiAgICAgICAgIGRpcmVjdG9yeSBpbnRvIGl0cyBvd24gY2hpbGQsIG1vdmluZyBhIGZpbGUgaW50byBpdHMgcGFyZW50IGRpcmVjdG9yeSB3aXRob3V0IGNoYW5naW5nIGl0cyBuYW1lLFxyXG4gICAgICAgICBvciBjb3B5aW5nIGEgZGlyZWN0b3J5IHRvIGEgcGF0aCBvY2N1cGllZCBieSBhIGZpbGUuICAqL1xyXG4gICAgICBjYXNlICdJbnZhbGlkTW9kaWZpY2F0aW9uRXJyb3InOlxyXG4gICAgICAgIHJldHVybiBBcGlFcnJvci5GaWxlRXJyb3IoRXJyb3JDb2RlLkVQRVJNLCBwKTtcclxuICAgICAgLyogVGhlIHVzZXIgaGFzIGF0dGVtcHRlZCB0byBsb29rIHVwIGEgZmlsZSBvciBkaXJlY3RvcnksIGJ1dCB0aGUgRW50cnkgZm91bmQgaXMgb2YgdGhlIHdyb25nIHR5cGVcclxuICAgICAgICAgW2UuZy4gaXMgYSBEaXJlY3RvcnlFbnRyeSB3aGVuIHRoZSB1c2VyIHJlcXVlc3RlZCBhIEZpbGVFbnRyeV0uICAqL1xyXG4gICAgICBjYXNlICdUeXBlTWlzbWF0Y2hFcnJvcic6XHJcbiAgICAgICAgcmV0dXJuIEFwaUVycm9yLkZpbGVFcnJvcihleHBlY3RlZERpciA/IEVycm9yQ29kZS5FTk9URElSIDogRXJyb3JDb2RlLkVJU0RJUiwgcCk7XHJcbiAgICAgIC8qIEEgcGF0aCBvciBVUkwgc3VwcGxpZWQgdG8gdGhlIEFQSSB3YXMgbWFsZm9ybWVkLiAgKi9cclxuICAgICAgY2FzZSBcIkVuY29kaW5nRXJyb3JcIjpcclxuICAgICAgLyogQW4gb3BlcmF0aW9uIGRlcGVuZGVkIG9uIHN0YXRlIGNhY2hlZCBpbiBhbiBpbnRlcmZhY2Ugb2JqZWN0LCBidXQgdGhhdCBzdGF0ZSB0aGF0IGhhcyBjaGFuZ2VkXHJcbiAgICAgICAgIHNpbmNlIGl0IHdhcyByZWFkIGZyb20gZGlzay4gICovXHJcbiAgICAgIGNhc2UgXCJJbnZhbGlkU3RhdGVFcnJvclwiOlxyXG4gICAgICAvKiBUaGUgdXNlciBhdHRlbXB0ZWQgdG8gd3JpdGUgdG8gYSBmaWxlIG9yIGRpcmVjdG9yeSB3aGljaCBjb3VsZCBub3QgYmUgbW9kaWZpZWQgZHVlIHRvIHRoZSBzdGF0ZVxyXG4gICAgICAgICBvZiB0aGUgdW5kZXJseWluZyBmaWxlc3lzdGVtLiAgKi9cclxuICAgICAgY2FzZSBcIk5vTW9kaWZpY2F0aW9uQWxsb3dlZEVycm9yXCI6XHJcbiAgICAgIGRlZmF1bHQ6XHJcbiAgICAgICAgcmV0dXJuIEFwaUVycm9yLkZpbGVFcnJvcihFcnJvckNvZGUuRUlOVkFMLCBwKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIE5vbnN0YW5kYXJkXHJcbiAgICogUmVxdWVzdHMgYSBzdG9yYWdlIHF1b3RhIGZyb20gdGhlIGJyb3dzZXIgdG8gYmFjayB0aGlzIEZTLlxyXG4gICAqL1xyXG4gIHB1YmxpYyBhbGxvY2F0ZShjYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCA9IGZ1bmN0aW9uKCl7fSk6IHZvaWQge1xyXG4gICAgdmFyIHN1Y2Nlc3MgPSAoZnM6IEZpbGVTeXN0ZW0pOiB2b2lkID0+IHtcclxuICAgICAgdGhpcy5mcyA9IGZzO1xyXG4gICAgICBjYigpXHJcbiAgICB9O1xyXG4gICAgdmFyIGVycm9yID0gKGVycjogRE9NRXhjZXB0aW9uKTogdm9pZCA9PiB7XHJcbiAgICAgIGNiKHRoaXMuY29udmVydChlcnIsIFwiL1wiLCB0cnVlKSk7XHJcbiAgICB9O1xyXG4gICAgaWYgKHRoaXMudHlwZSA9PT0gZ2xvYmFsLlBFUlNJU1RFTlQpIHtcclxuICAgICAgX3JlcXVlc3RRdW90YSh0aGlzLnR5cGUsIHRoaXMuc2l6ZSwgKGdyYW50ZWQ6IG51bWJlcikgPT4ge1xyXG4gICAgICAgIF9nZXRGUyh0aGlzLnR5cGUsIGdyYW50ZWQsIHN1Y2Nlc3MsIGVycm9yKTtcclxuICAgICAgfSwgZXJyb3IpO1xyXG4gICAgfSBlbHNlIHtcclxuICAgICAgX2dldEZTKHRoaXMudHlwZSwgdGhpcy5zaXplLCBzdWNjZXNzLCBlcnJvcik7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBOb25zdGFuZGFyZFxyXG4gICAqIERlbGV0ZXMgZXZlcnl0aGluZyBpbiB0aGUgRlMuIFVzZWQgZm9yIHRlc3RpbmcuXHJcbiAgICogS2FybWEgY2xlYXJzIHRoZSBzdG9yYWdlIGFmdGVyIHlvdSBxdWl0IGl0IGJ1dCBub3QgYmV0d2VlbiBydW5zIG9mIHRoZSB0ZXN0XHJcbiAgICogc3VpdGUsIGFuZCB0aGUgdGVzdHMgZXhwZWN0IGFuIGVtcHR5IEZTIGV2ZXJ5IHRpbWUuXHJcbiAgICovXHJcbiAgcHVibGljIGVtcHR5KG1haW5DYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgLy8gR2V0IGEgbGlzdCBvZiBhbGwgZW50cmllcyBpbiB0aGUgcm9vdCBkaXJlY3RvcnkgdG8gZGVsZXRlIHRoZW1cclxuICAgIHRoaXMuX3JlYWRkaXIoJy8nLCAoZXJyOiBBcGlFcnJvciwgZW50cmllcz86IEVudHJ5W10pOiB2b2lkID0+IHtcclxuICAgICAgaWYgKGVycikge1xyXG4gICAgICAgIGNvbnNvbGUuZXJyb3IoJ0ZhaWxlZCB0byBlbXB0eSBGUycpO1xyXG4gICAgICAgIG1haW5DYihlcnIpO1xyXG4gICAgICB9IGVsc2Uge1xyXG4gICAgICAgIC8vIENhbGxlZCB3aGVuIGV2ZXJ5IGVudHJ5IGhhcyBiZWVuIG9wZXJhdGVkIG9uXHJcbiAgICAgICAgdmFyIGZpbmlzaGVkID0gKGVyOiBhbnkpOiB2b2lkID0+IHtcclxuICAgICAgICAgIGlmIChlcnIpIHtcclxuICAgICAgICAgICAgY29uc29sZS5lcnJvcihcIkZhaWxlZCB0byBlbXB0eSBGU1wiKTtcclxuICAgICAgICAgICAgbWFpbkNiKGVycik7XHJcbiAgICAgICAgICB9IGVsc2Uge1xyXG4gICAgICAgICAgICBtYWluQ2IoKTtcclxuICAgICAgICAgIH1cclxuICAgICAgICB9O1xyXG4gICAgICAgIC8vIFJlbW92ZXMgZmlsZXMgYW5kIHJlY3Vyc2l2ZWx5IHJlbW92ZXMgZGlyZWN0b3JpZXNcclxuICAgICAgICB2YXIgZGVsZXRlRW50cnkgPSAoZW50cnk6IEVudHJ5LCBjYjogKGU/OiBhbnkpID0+IHZvaWQpOiB2b2lkID0+IHtcclxuICAgICAgICAgIHZhciBzdWNjID0gKCkgPT4ge1xyXG4gICAgICAgICAgICBjYigpO1xyXG4gICAgICAgICAgfTtcclxuICAgICAgICAgIHZhciBlcnJvciA9IChlcnI6IERPTUV4Y2VwdGlvbikgPT4ge1xyXG4gICAgICAgICAgICBjYih0aGlzLmNvbnZlcnQoZXJyLCBlbnRyeS5mdWxsUGF0aCwgIWVudHJ5LmlzRGlyZWN0b3J5KSk7XHJcbiAgICAgICAgICB9O1xyXG4gICAgICAgICAgaWYgKGlzRGlyZWN0b3J5RW50cnkoZW50cnkpKSB7XHJcbiAgICAgICAgICAgIGVudHJ5LnJlbW92ZVJlY3Vyc2l2ZWx5KHN1Y2MsIGVycm9yKTtcclxuICAgICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICAgIGVudHJ5LnJlbW92ZShzdWNjLCBlcnJvcik7XHJcbiAgICAgICAgICB9XHJcbiAgICAgICAgfTtcclxuICAgICAgICAvLyBMb29wIHRocm91Z2ggdGhlIGVudHJpZXMgYW5kIHJlbW92ZSB0aGVtLCB0aGVuIGNhbGwgdGhlIGNhbGxiYWNrXHJcbiAgICAgICAgLy8gd2hlbiB0aGV5J3JlIGFsbCBmaW5pc2hlZC5cclxuICAgICAgICBhc3luYy5lYWNoKGVudHJpZXMsIGRlbGV0ZUVudHJ5LCBmaW5pc2hlZCk7XHJcbiAgICAgIH1cclxuICAgIH0pO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIHJlbmFtZShvbGRQYXRoOiBzdHJpbmcsIG5ld1BhdGg6IHN0cmluZywgY2I6IChlPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHZhciBzZW1hcGhvcmU6IG51bWJlciA9IDIsXHJcbiAgICAgIHN1Y2Nlc3NDb3VudDogbnVtYmVyID0gMCxcclxuICAgICAgcm9vdDogRGlyZWN0b3J5RW50cnkgPSB0aGlzLmZzLnJvb3QsXHJcbiAgICAgIGN1cnJlbnRQYXRoOiBzdHJpbmcgPSBvbGRQYXRoLFxyXG4gICAgICBlcnJvciA9IChlcnI6IERPTUV4Y2VwdGlvbik6IHZvaWQgPT4ge1xyXG4gICAgICAgIGlmICgtLXNlbWFwaG9yZSA8PSAwKSB7XHJcbiAgICAgICAgICAgIGNiKHRoaXMuY29udmVydChlcnIsIGN1cnJlbnRQYXRoLCBmYWxzZSkpO1xyXG4gICAgICAgIH1cclxuICAgICAgfSxcclxuICAgICAgc3VjY2VzcyA9IChmaWxlOiBFbnRyeSk6IHZvaWQgPT4ge1xyXG4gICAgICAgIGlmICgrK3N1Y2Nlc3NDb3VudCA9PT0gMikge1xyXG4gICAgICAgICAgcmV0dXJuIGNiKG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRUlOVkFMLCBcIlNvbWV0aGluZyB3YXMgaWRlbnRpZmllZCBhcyBib3RoIGEgZmlsZSBhbmQgYSBkaXJlY3RvcnkuIFRoaXMgc2hvdWxkIG5ldmVyIGhhcHBlbi5cIikpO1xyXG4gICAgICAgIH1cclxuXHJcbiAgICAgICAgLy8gU1BFQ0lBTCBDQVNFOiBJZiBuZXdQYXRoID09PSBvbGRQYXRoLCBhbmQgdGhlIHBhdGggZXhpc3RzLCB0aGVuXHJcbiAgICAgICAgLy8gdGhpcyBvcGVyYXRpb24gdHJpdmlhbGx5IHN1Y2NlZWRzLlxyXG4gICAgICAgIGlmIChvbGRQYXRoID09PSBuZXdQYXRoKSB7XHJcbiAgICAgICAgICByZXR1cm4gY2IoKTtcclxuICAgICAgICB9XHJcblxyXG4gICAgICAgIC8vIEdldCB0aGUgbmV3IHBhcmVudCBkaXJlY3RvcnkuXHJcbiAgICAgICAgY3VycmVudFBhdGggPSBwYXRoLmRpcm5hbWUobmV3UGF0aCk7XHJcbiAgICAgICAgcm9vdC5nZXREaXJlY3RvcnkoY3VycmVudFBhdGgsIHt9LCAocGFyZW50RGlyOiBEaXJlY3RvcnlFbnRyeSk6IHZvaWQgPT4ge1xyXG4gICAgICAgICAgY3VycmVudFBhdGggPSBwYXRoLmJhc2VuYW1lKG5ld1BhdGgpO1xyXG4gICAgICAgICAgZmlsZS5tb3ZlVG8ocGFyZW50RGlyLCBjdXJyZW50UGF0aCwgKGVudHJ5OiBFbnRyeSk6IHZvaWQgPT4geyBjYigpOyB9LCAoZXJyOiBET01FeGNlcHRpb24pOiB2b2lkID0+IHtcclxuICAgICAgICAgICAgLy8gU1BFQ0lBTCBDQVNFOiBJZiBvbGRQYXRoIGlzIGEgZGlyZWN0b3J5LCBhbmQgbmV3UGF0aCBpcyBhXHJcbiAgICAgICAgICAgIC8vIGZpbGUsIHJlbmFtZSBzaG91bGQgZGVsZXRlIHRoZSBmaWxlIGFuZCBwZXJmb3JtIHRoZSBtb3ZlLlxyXG4gICAgICAgICAgICBpZiAoZmlsZS5pc0RpcmVjdG9yeSkge1xyXG4gICAgICAgICAgICAgIGN1cnJlbnRQYXRoID0gbmV3UGF0aDtcclxuICAgICAgICAgICAgICAvLyBVbmxpbmsgb25seSB3b3JrcyBvbiBmaWxlcy4gVHJ5IHRvIGRlbGV0ZSBuZXdQYXRoLlxyXG4gICAgICAgICAgICAgIHRoaXMudW5saW5rKG5ld1BhdGgsIChlPyk6IHZvaWQgPT4ge1xyXG4gICAgICAgICAgICAgICAgaWYgKGUpIHtcclxuICAgICAgICAgICAgICAgICAgLy8gbmV3UGF0aCBpcyBwcm9iYWJseSBhIGRpcmVjdG9yeS5cclxuICAgICAgICAgICAgICAgICAgZXJyb3IoZXJyKTtcclxuICAgICAgICAgICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICAgICAgICAgIC8vIFJlY3VyLCBub3cgdGhhdCBuZXdQYXRoIGRvZXNuJ3QgZXhpc3QuXHJcbiAgICAgICAgICAgICAgICAgIHRoaXMucmVuYW1lKG9sZFBhdGgsIG5ld1BhdGgsIGNiKTtcclxuICAgICAgICAgICAgICAgIH1cclxuICAgICAgICAgICAgICB9KTtcclxuICAgICAgICAgICAgfSBlbHNlIHtcclxuICAgICAgICAgICAgICBlcnJvcihlcnIpO1xyXG4gICAgICAgICAgICB9XHJcbiAgICAgICAgICB9KTtcclxuICAgICAgICB9LCBlcnJvcik7XHJcbiAgICAgIH07XHJcblxyXG4gICAgLy8gV2UgZG9uJ3Qga25vdyBpZiBvbGRQYXRoIGlzIGEgKmZpbGUqIG9yIGEgKmRpcmVjdG9yeSosIGFuZCB0aGVyZSdzIG5vXHJcbiAgICAvLyB3YXkgdG8gc3RhdCBpdGVtcy4gU28gbGF1bmNoIGJvdGggcmVxdWVzdHMsIHNlZSB3aGljaCBvbmUgc3VjY2VlZHMuXHJcbiAgICByb290LmdldEZpbGUob2xkUGF0aCwge30sIHN1Y2Nlc3MsIGVycm9yKTtcclxuICAgIHJvb3QuZ2V0RGlyZWN0b3J5KG9sZFBhdGgsIHt9LCBzdWNjZXNzLCBlcnJvcik7XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgc3RhdChwYXRoOiBzdHJpbmcsIGlzTHN0YXQ6IGJvb2xlYW4sIGNiOiAoZXJyOiBBcGlFcnJvciwgc3RhdD86IFN0YXRzKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICAvLyBUaHJvdyBhbiBlcnJvciBpZiB0aGUgZW50cnkgZG9lc24ndCBleGlzdCwgYmVjYXVzZSB0aGVuIHRoZXJlJ3Mgbm90aGluZ1xyXG4gICAgLy8gdG8gc3RhdC5cclxuICAgIHZhciBvcHRzID0ge1xyXG4gICAgICBjcmVhdGU6IGZhbHNlXHJcbiAgICB9O1xyXG4gICAgLy8gQ2FsbGVkIHdoZW4gdGhlIHBhdGggaGFzIGJlZW4gc3VjY2Vzc2Z1bGx5IGxvYWRlZCBhcyBhIGZpbGUuXHJcbiAgICB2YXIgbG9hZEFzRmlsZSA9IChlbnRyeTogRmlsZUVudHJ5KTogdm9pZCA9PiB7XHJcbiAgICAgIHZhciBmaWxlRnJvbUVudHJ5ID0gKGZpbGU6IEZpbGUpOiB2b2lkID0+IHtcclxuICAgICAgICB2YXIgc3RhdCA9IG5ldyBTdGF0cyhGaWxlVHlwZS5GSUxFLCBmaWxlLnNpemUpO1xyXG4gICAgICAgIGNiKG51bGwsIHN0YXQpO1xyXG4gICAgICB9O1xyXG4gICAgICBlbnRyeS5maWxlKGZpbGVGcm9tRW50cnksIGZhaWxlZFRvTG9hZCk7XHJcbiAgICB9O1xyXG4gICAgLy8gQ2FsbGVkIHdoZW4gdGhlIHBhdGggaGFzIGJlZW4gc3VjY2Vzc2Z1bGx5IGxvYWRlZCBhcyBhIGRpcmVjdG9yeS5cclxuICAgIHZhciBsb2FkQXNEaXIgPSAoZGlyOiBEaXJlY3RvcnlFbnRyeSk6IHZvaWQgPT4ge1xyXG4gICAgICAvLyBEaXJlY3RvcnkgZW50cnkgc2l6ZSBjYW4ndCBiZSBkZXRlcm1pbmVkIGZyb20gdGhlIEhUTUw1IEZTIEFQSSwgYW5kIGlzXHJcbiAgICAgIC8vIGltcGxlbWVudGF0aW9uLWRlcGVuZGFudCBhbnl3YXksIHNvIGEgZHVtbXkgdmFsdWUgaXMgdXNlZC5cclxuICAgICAgdmFyIHNpemUgPSA0MDk2O1xyXG4gICAgICB2YXIgc3RhdCA9IG5ldyBTdGF0cyhGaWxlVHlwZS5ESVJFQ1RPUlksIHNpemUpO1xyXG4gICAgICBjYihudWxsLCBzdGF0KTtcclxuICAgIH07XHJcbiAgICAvLyBDYWxsZWQgd2hlbiB0aGUgcGF0aCBjb3VsZG4ndCBiZSBvcGVuZWQgYXMgYSBkaXJlY3Rvcnkgb3IgYSBmaWxlLlxyXG4gICAgdmFyIGZhaWxlZFRvTG9hZCA9IChlcnI6IERPTUV4Y2VwdGlvbik6IHZvaWQgPT4ge1xyXG4gICAgICBjYih0aGlzLmNvbnZlcnQoZXJyLCBwYXRoLCBmYWxzZSAvKiBVbmtub3duIC8gaXJyZWxldmFudCAqLykpO1xyXG4gICAgfTtcclxuICAgIC8vIENhbGxlZCB3aGVuIHRoZSBwYXRoIGNvdWxkbid0IGJlIG9wZW5lZCBhcyBhIGZpbGUsIGJ1dCBtaWdodCBzdGlsbCBiZSBhXHJcbiAgICAvLyBkaXJlY3RvcnkuXHJcbiAgICB2YXIgZmFpbGVkVG9Mb2FkQXNGaWxlID0gKCk6IHZvaWQgPT4ge1xyXG4gICAgICB0aGlzLmZzLnJvb3QuZ2V0RGlyZWN0b3J5KHBhdGgsIG9wdHMsIGxvYWRBc0RpciwgZmFpbGVkVG9Mb2FkKTtcclxuICAgIH07XHJcbiAgICAvLyBObyBtZXRob2QgY3VycmVudGx5IGV4aXN0cyB0byBkZXRlcm1pbmUgd2hldGhlciBhIHBhdGggcmVmZXJzIHRvIGFcclxuICAgIC8vIGRpcmVjdG9yeSBvciBhIGZpbGUsIHNvIHRoaXMgaW1wbGVtZW50YXRpb24gdHJpZXMgYm90aCBhbmQgdXNlcyB0aGUgZmlyc3RcclxuICAgIC8vIG9uZSB0aGF0IHN1Y2NlZWRzLlxyXG4gICAgdGhpcy5mcy5yb290LmdldEZpbGUocGF0aCwgb3B0cywgbG9hZEFzRmlsZSwgZmFpbGVkVG9Mb2FkQXNGaWxlKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBvcGVuKHA6IHN0cmluZywgZmxhZ3M6IEZpbGVGbGFnLCBtb2RlOiBudW1iZXIsIGNiOiAoZXJyOiBBcGlFcnJvciwgZmQ/OiBmaWxlLkZpbGUpID0+IGFueSk6IHZvaWQge1xyXG4gICAgdmFyIGVycm9yID0gKGVycjogRE9NRXJyb3IpOiB2b2lkID0+IHtcclxuICAgICAgaWYgKGVyci5uYW1lID09PSAnSW52YWxpZE1vZGlmaWNhdGlvbkVycm9yJyAmJiBmbGFncy5pc0V4Y2x1c2l2ZSgpKSB7XHJcbiAgICAgICAgY2IoQXBpRXJyb3IuRUVYSVNUKHApKTtcclxuICAgICAgfSBlbHNlIHtcclxuICAgICAgICBjYih0aGlzLmNvbnZlcnQoZXJyLCBwLCBmYWxzZSkpO1xyXG4gICAgICB9XHJcbiAgICB9O1xyXG5cclxuICAgIHRoaXMuZnMucm9vdC5nZXRGaWxlKHAsIHtcclxuICAgICAgY3JlYXRlOiBmbGFncy5wYXRoTm90RXhpc3RzQWN0aW9uKCkgPT09IEFjdGlvblR5cGUuQ1JFQVRFX0ZJTEUsXHJcbiAgICAgIGV4Y2x1c2l2ZTogZmxhZ3MuaXNFeGNsdXNpdmUoKVxyXG4gICAgfSwgKGVudHJ5OiBGaWxlRW50cnkpOiB2b2lkID0+IHtcclxuICAgICAgLy8gVHJ5IHRvIGZldGNoIGNvcnJlc3BvbmRpbmcgZmlsZS5cclxuICAgICAgZW50cnkuZmlsZSgoZmlsZTogRmlsZSk6IHZvaWQgPT4ge1xyXG4gICAgICAgIHZhciByZWFkZXIgPSBuZXcgRmlsZVJlYWRlcigpO1xyXG4gICAgICAgIHJlYWRlci5vbmxvYWRlbmQgPSAoZXZlbnQ6IEV2ZW50KTogdm9pZCA9PiB7XHJcbiAgICAgICAgICB2YXIgYmZzX2ZpbGUgPSB0aGlzLl9tYWtlRmlsZShwLCBmbGFncywgZmlsZSwgPEFycmF5QnVmZmVyPiByZWFkZXIucmVzdWx0KTtcclxuICAgICAgICAgIGNiKG51bGwsIGJmc19maWxlKTtcclxuICAgICAgICB9O1xyXG4gICAgICAgIHJlYWRlci5vbmVycm9yID0gKGV2OiBFdmVudCkgPT4ge1xyXG4gICAgICAgICAgZXJyb3IocmVhZGVyLmVycm9yKTtcclxuICAgICAgICB9O1xyXG4gICAgICAgIHJlYWRlci5yZWFkQXNBcnJheUJ1ZmZlcihmaWxlKTtcclxuICAgICAgfSwgZXJyb3IpO1xyXG4gICAgfSwgZXJyb3IpO1xyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogUmV0dXJucyBhIEJyb3dzZXJGUyBvYmplY3QgcmVwcmVzZW50aW5nIHRoZSB0eXBlIG9mIGEgRHJvcGJveC5qcyBzdGF0IG9iamVjdFxyXG4gICAqL1xyXG4gIHByaXZhdGUgX3N0YXRUeXBlKHN0YXQ6IEVudHJ5KTogRmlsZVR5cGUge1xyXG4gICAgcmV0dXJuIHN0YXQuaXNGaWxlID8gRmlsZVR5cGUuRklMRSA6IEZpbGVUeXBlLkRJUkVDVE9SWTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIFJldHVybnMgYSBCcm93c2VyRlMgb2JqZWN0IHJlcHJlc2VudGluZyBhIEZpbGUsIGNyZWF0ZWQgZnJvbSB0aGUgZGF0YVxyXG4gICAqIHJldHVybmVkIGJ5IGNhbGxzIHRvIHRoZSBEcm9wYm94IEFQSS5cclxuICAgKi9cclxuICBwcml2YXRlIF9tYWtlRmlsZShwYXRoOiBzdHJpbmcsIGZsYWc6IEZpbGVGbGFnLCBzdGF0OiBGaWxlLCBkYXRhOiBBcnJheUJ1ZmZlciA9IG5ldyBBcnJheUJ1ZmZlcigwKSk6IEhUTUw1RlNGaWxlIHtcclxuICAgIHZhciBzdGF0cyA9IG5ldyBTdGF0cyhGaWxlVHlwZS5GSUxFLCBzdGF0LnNpemUpO1xyXG4gICAgdmFyIGJ1ZmZlciA9IGFycmF5QnVmZmVyMkJ1ZmZlcihkYXRhKTtcclxuICAgIHJldHVybiBuZXcgSFRNTDVGU0ZpbGUodGhpcywgcGF0aCwgZmxhZywgc3RhdHMsIGJ1ZmZlcik7XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBEZWxldGUgYSBmaWxlIG9yIGRpcmVjdG9yeSBmcm9tIHRoZSBmaWxlIHN5c3RlbVxyXG4gICAqIGlzRmlsZSBzaG91bGQgcmVmbGVjdCB3aGljaCBjYWxsIHdhcyBtYWRlIHRvIHJlbW92ZSB0aGUgaXQgKGB1bmxpbmtgIG9yXHJcbiAgICogYHJtZGlyYCkuIElmIHRoaXMgZG9lc24ndCBtYXRjaCB3aGF0J3MgYWN0dWFsbHkgYXQgYHBhdGhgLCBhbiBlcnJvciB3aWxsIGJlXHJcbiAgICogcmV0dXJuZWRcclxuICAgKi9cclxuICBwcml2YXRlIF9yZW1vdmUocGF0aDogc3RyaW5nLCBjYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCwgaXNGaWxlOiBib29sZWFuKTogdm9pZCB7XHJcbiAgICB2YXIgc3VjY2VzcyA9IChlbnRyeTogRW50cnkpOiB2b2lkID0+IHtcclxuICAgICAgdmFyIHN1Y2MgPSAoKSA9PiB7XHJcbiAgICAgICAgY2IoKTtcclxuICAgICAgfTtcclxuICAgICAgdmFyIGVyciA9IChlcnI6IERPTUV4Y2VwdGlvbikgPT4ge1xyXG4gICAgICAgIGNiKHRoaXMuY29udmVydChlcnIsIHBhdGgsICFpc0ZpbGUpKTtcclxuICAgICAgfTtcclxuICAgICAgZW50cnkucmVtb3ZlKHN1Y2MsIGVycik7XHJcbiAgICB9O1xyXG4gICAgdmFyIGVycm9yID0gKGVycjogRE9NRXhjZXB0aW9uKTogdm9pZCA9PiB7XHJcbiAgICAgIGNiKHRoaXMuY29udmVydChlcnIsIHBhdGgsICFpc0ZpbGUpKTtcclxuICAgIH07XHJcbiAgICAvLyBEZWxldGluZyB0aGUgZW50cnksIHNvIGRvbid0IGNyZWF0ZSBpdFxyXG4gICAgdmFyIG9wdHMgPSB7XHJcbiAgICAgIGNyZWF0ZTogZmFsc2VcclxuICAgIH07XHJcblxyXG4gICAgaWYgKGlzRmlsZSkge1xyXG4gICAgICB0aGlzLmZzLnJvb3QuZ2V0RmlsZShwYXRoLCBvcHRzLCBzdWNjZXNzLCBlcnJvcik7XHJcbiAgICB9IGVsc2Uge1xyXG4gICAgICB0aGlzLmZzLnJvb3QuZ2V0RGlyZWN0b3J5KHBhdGgsIG9wdHMsIHN1Y2Nlc3MsIGVycm9yKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIHB1YmxpYyB1bmxpbmsocGF0aDogc3RyaW5nLCBjYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgdGhpcy5fcmVtb3ZlKHBhdGgsIGNiLCB0cnVlKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBybWRpcihwYXRoOiBzdHJpbmcsIGNiOiAoZT86IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICAvLyBDaGVjayBpZiBkaXJlY3RvcnkgaXMgbm9uLWVtcHR5LCBmaXJzdC5cclxuICAgIHRoaXMucmVhZGRpcihwYXRoLCAoZSwgZmlsZXM/KSA9PiB7XHJcbiAgICAgIGlmIChlKSB7XHJcbiAgICAgICAgY2IoZSk7XHJcbiAgICAgIH0gZWxzZSBpZiAoZmlsZXMubGVuZ3RoID4gMCkge1xyXG4gICAgICAgIGNiKEFwaUVycm9yLkVOT1RFTVBUWShwYXRoKSk7XHJcbiAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgdGhpcy5fcmVtb3ZlKHBhdGgsIGNiLCBmYWxzZSk7XHJcbiAgICAgIH1cclxuICAgIH0pO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIG1rZGlyKHBhdGg6IHN0cmluZywgbW9kZTogbnVtYmVyLCBjYjogKGU/OiBBcGlFcnJvcikgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgLy8gQ3JlYXRlIHRoZSBkaXJlY3RvcnksIGJ1dCB0aHJvdyBhbiBlcnJvciBpZiBpdCBhbHJlYWR5IGV4aXN0cywgYXMgcGVyXHJcbiAgICAvLyBta2RpcigxKVxyXG4gICAgdmFyIG9wdHMgPSB7XHJcbiAgICAgIGNyZWF0ZTogdHJ1ZSxcclxuICAgICAgZXhjbHVzaXZlOiB0cnVlXHJcbiAgICB9O1xyXG4gICAgdmFyIHN1Y2Nlc3MgPSAoZGlyOiBEaXJlY3RvcnlFbnRyeSk6IHZvaWQgPT4ge1xyXG4gICAgICBjYigpO1xyXG4gICAgfTtcclxuICAgIHZhciBlcnJvciA9IChlcnI6IERPTUV4Y2VwdGlvbik6IHZvaWQgPT4ge1xyXG4gICAgICBjYih0aGlzLmNvbnZlcnQoZXJyLCBwYXRoLCB0cnVlKSk7XHJcbiAgICB9O1xyXG4gICAgdGhpcy5mcy5yb290LmdldERpcmVjdG9yeShwYXRoLCBvcHRzLCBzdWNjZXNzLCBlcnJvcik7XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBSZXR1cm5zIGFuIGFycmF5IG9mIGBGaWxlRW50cnlgcy4gVXNlZCBpbnRlcm5hbGx5IGJ5IGVtcHR5IGFuZCByZWFkZGlyLlxyXG4gICAqL1xyXG4gIHByaXZhdGUgX3JlYWRkaXIocGF0aDogc3RyaW5nLCBjYjogKGU6IEFwaUVycm9yLCBlbnRyaWVzPzogRW50cnlbXSkgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgdmFyIGVycm9yID0gKGVycjogRE9NRXhjZXB0aW9uKTogdm9pZCA9PiB7XHJcbiAgICAgIGNiKHRoaXMuY29udmVydChlcnIsIHBhdGgsIHRydWUpKTtcclxuICAgIH07XHJcbiAgICAvLyBHcmFiIHRoZSByZXF1ZXN0ZWQgZGlyZWN0b3J5LlxyXG4gICAgdGhpcy5mcy5yb290LmdldERpcmVjdG9yeShwYXRoLCB7IGNyZWF0ZTogZmFsc2UgfSwgKGRpckVudHJ5OiBEaXJlY3RvcnlFbnRyeSkgPT4ge1xyXG4gICAgICB2YXIgcmVhZGVyID0gZGlyRW50cnkuY3JlYXRlUmVhZGVyKCk7XHJcbiAgICAgIHZhciBlbnRyaWVzOiBFbnRyeVtdID0gW107XHJcblxyXG4gICAgICAvLyBDYWxsIHRoZSByZWFkZXIucmVhZEVudHJpZXMoKSB1bnRpbCBubyBtb3JlIHJlc3VsdHMgYXJlIHJldHVybmVkLlxyXG4gICAgICB2YXIgcmVhZEVudHJpZXMgPSAoKSA9PiB7XHJcbiAgICAgICAgcmVhZGVyLnJlYWRFbnRyaWVzKCgocmVzdWx0cykgPT4ge1xyXG4gICAgICAgICAgaWYgKHJlc3VsdHMubGVuZ3RoKSB7XHJcbiAgICAgICAgICAgIGVudHJpZXMgPSBlbnRyaWVzLmNvbmNhdChfdG9BcnJheShyZXN1bHRzKSk7XHJcbiAgICAgICAgICAgIHJlYWRFbnRyaWVzKCk7XHJcbiAgICAgICAgICB9IGVsc2Uge1xyXG4gICAgICAgICAgICBjYihudWxsLCBlbnRyaWVzKTtcclxuICAgICAgICAgIH1cclxuICAgICAgICB9KSwgZXJyb3IpO1xyXG4gICAgICB9O1xyXG4gICAgICByZWFkRW50cmllcygpO1xyXG4gICAgfSwgZXJyb3IpO1xyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogTWFwIF9yZWFkZGlyJ3MgbGlzdCBvZiBgRmlsZUVudHJ5YHMgdG8gdGhlaXIgbmFtZXMgYW5kIHJldHVybiB0aGF0LlxyXG4gICAqL1xyXG4gIHB1YmxpYyByZWFkZGlyKHBhdGg6IHN0cmluZywgY2I6IChlcnI6IEFwaUVycm9yLCBmaWxlcz86IHN0cmluZ1tdKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICB0aGlzLl9yZWFkZGlyKHBhdGgsIChlOiBBcGlFcnJvciwgZW50cmllcz86IEVudHJ5W10pOiB2b2lkID0+IHtcclxuICAgICAgaWYgKGUpIHtcclxuICAgICAgICByZXR1cm4gY2IoZSk7XHJcbiAgICAgIH1cclxuICAgICAgdmFyIHJ2OiBzdHJpbmdbXSA9IFtdO1xyXG4gICAgICBmb3IgKHZhciBpID0gMDsgaSA8IGVudHJpZXMubGVuZ3RoOyBpKyspIHtcclxuICAgICAgICBydi5wdXNoKGVudHJpZXNbaV0ubmFtZSk7XHJcbiAgICAgIH1cclxuICAgICAgY2IobnVsbCwgcnYpO1xyXG4gICAgfSk7XHJcbiAgfVxyXG59XHJcbiJdfQ==