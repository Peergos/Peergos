"use strict";
var __extends = (this && this.__extends) || function (d, b) {
    for (var p in b) if (b.hasOwnProperty(p)) d[p] = b[p];
    function __() { this.constructor = d; }
    d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
};
var file_system = require('../core/file_system');
var api_error_1 = require('../core/api_error');
var file_flag = require('../core/file_flag');
var preload_file = require('../generic/preload_file');
var MirrorFile = (function (_super) {
    __extends(MirrorFile, _super);
    function MirrorFile(fs, path, flag, stat, data) {
        _super.call(this, fs, path, flag, stat, data);
    }
    MirrorFile.prototype.syncSync = function () {
        if (this.isDirty()) {
            this._fs._syncSync(this);
            this.resetDirty();
        }
    };
    MirrorFile.prototype.closeSync = function () {
        this.syncSync();
    };
    return MirrorFile;
}(preload_file.PreloadFile));
var AsyncMirror = (function (_super) {
    __extends(AsyncMirror, _super);
    function AsyncMirror(sync, async) {
        _super.call(this);
        this._queue = [];
        this._queueRunning = false;
        this._isInitialized = false;
        this._initializeCallbacks = [];
        this._sync = sync;
        this._async = async;
        if (!sync.supportsSynch()) {
            throw new Error("Expected synchronous storage.");
        }
        if (async.supportsSynch()) {
            throw new Error("Expected asynchronous storage.");
        }
    }
    AsyncMirror.prototype.getName = function () {
        return "AsyncMirror";
    };
    AsyncMirror.isAvailable = function () {
        return true;
    };
    AsyncMirror.prototype._syncSync = function (fd) {
        this._sync.writeFileSync(fd.getPath(), fd.getBuffer(), null, file_flag.FileFlag.getFileFlag('w'), fd.getStats().mode);
        this.enqueueOp({
            apiMethod: 'writeFile',
            arguments: [fd.getPath(), fd.getBuffer(), null, fd.getFlag(), fd.getStats().mode]
        });
    };
    AsyncMirror.prototype.initialize = function (userCb) {
        var _this = this;
        var callbacks = this._initializeCallbacks;
        var end = function (e) {
            _this._isInitialized = !e;
            _this._initializeCallbacks = [];
            callbacks.forEach(function (cb) { return cb(e); });
        };
        if (!this._isInitialized) {
            if (callbacks.push(userCb) === 1) {
                var copyDirectory_1 = function (p, mode, cb) {
                    if (p !== '/') {
                        _this._sync.mkdirSync(p, mode);
                    }
                    _this._async.readdir(p, function (err, files) {
                        if (err) {
                            cb(err);
                        }
                        else {
                            var i = 0;
                            function copyNextFile(err) {
                                if (err) {
                                    cb(err);
                                }
                                else if (i < files.length) {
                                    copyItem_1(p + "/" + files[i], copyNextFile);
                                    i++;
                                }
                                else {
                                    cb();
                                }
                            }
                            copyNextFile();
                        }
                    });
                }, copyFile_1 = function (p, mode, cb) {
                    _this._async.readFile(p, null, file_flag.FileFlag.getFileFlag('r'), function (err, data) {
                        if (err) {
                            cb(err);
                        }
                        else {
                            try {
                                _this._sync.writeFileSync(p, data, null, file_flag.FileFlag.getFileFlag('w'), mode);
                            }
                            catch (e) {
                                err = e;
                            }
                            finally {
                                cb(err);
                            }
                        }
                    });
                }, copyItem_1 = function (p, cb) {
                    _this._async.stat(p, false, function (err, stats) {
                        if (err) {
                            cb(err);
                        }
                        else if (stats.isDirectory()) {
                            copyDirectory_1(p, stats.mode, cb);
                        }
                        else {
                            copyFile_1(p, stats.mode, cb);
                        }
                    });
                };
                copyDirectory_1('/', 0, end);
            }
        }
        else {
            userCb();
        }
    };
    AsyncMirror.prototype.checkInitialized = function () {
        if (!this._isInitialized) {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EPERM, "OverlayFS is not initialized. Please initialize OverlayFS using its initialize() method before using it.");
        }
    };
    AsyncMirror.prototype.isReadOnly = function () { return false; };
    AsyncMirror.prototype.supportsSynch = function () { return true; };
    AsyncMirror.prototype.supportsLinks = function () { return false; };
    AsyncMirror.prototype.supportsProps = function () { return this._sync.supportsProps() && this._async.supportsProps(); };
    AsyncMirror.prototype.enqueueOp = function (op) {
        var _this = this;
        this._queue.push(op);
        if (!this._queueRunning) {
            this._queueRunning = true;
            var doNextOp = function (err) {
                if (err) {
                    console.error("WARNING: File system has desynchronized. Received following error: " + err + "\n$");
                }
                if (_this._queue.length > 0) {
                    var op = _this._queue.shift(), args = op.arguments;
                    args.push(doNextOp);
                    _this._async[op.apiMethod].apply(_this._async, args);
                }
                else {
                    _this._queueRunning = false;
                }
            };
            doNextOp();
        }
    };
    AsyncMirror.prototype.renameSync = function (oldPath, newPath) {
        this.checkInitialized();
        this._sync.renameSync(oldPath, newPath);
        this.enqueueOp({
            apiMethod: 'rename',
            arguments: [oldPath, newPath]
        });
    };
    AsyncMirror.prototype.statSync = function (p, isLstat) {
        this.checkInitialized();
        return this._sync.statSync(p, isLstat);
    };
    AsyncMirror.prototype.openSync = function (p, flag, mode) {
        this.checkInitialized();
        var fd = this._sync.openSync(p, flag, mode);
        fd.closeSync();
        return new MirrorFile(this, p, flag, this._sync.statSync(p, false), this._sync.readFileSync(p, null, file_flag.FileFlag.getFileFlag('r')));
    };
    AsyncMirror.prototype.unlinkSync = function (p) {
        this.checkInitialized();
        this._sync.unlinkSync(p);
        this.enqueueOp({
            apiMethod: 'unlink',
            arguments: [p]
        });
    };
    AsyncMirror.prototype.rmdirSync = function (p) {
        this.checkInitialized();
        this._sync.rmdirSync(p);
        this.enqueueOp({
            apiMethod: 'rmdir',
            arguments: [p]
        });
    };
    AsyncMirror.prototype.mkdirSync = function (p, mode) {
        this.checkInitialized();
        this._sync.mkdirSync(p, mode);
        this.enqueueOp({
            apiMethod: 'mkdir',
            arguments: [p, mode]
        });
    };
    AsyncMirror.prototype.readdirSync = function (p) {
        this.checkInitialized();
        return this._sync.readdirSync(p);
    };
    AsyncMirror.prototype.existsSync = function (p) {
        this.checkInitialized();
        return this._sync.existsSync(p);
    };
    AsyncMirror.prototype.chmodSync = function (p, isLchmod, mode) {
        this.checkInitialized();
        this._sync.chmodSync(p, isLchmod, mode);
        this.enqueueOp({
            apiMethod: 'chmod',
            arguments: [p, isLchmod, mode]
        });
    };
    AsyncMirror.prototype.chownSync = function (p, isLchown, uid, gid) {
        this.checkInitialized();
        this._sync.chownSync(p, isLchown, uid, gid);
        this.enqueueOp({
            apiMethod: 'chown',
            arguments: [p, isLchown, uid, gid]
        });
    };
    AsyncMirror.prototype.utimesSync = function (p, atime, mtime) {
        this.checkInitialized();
        this._sync.utimesSync(p, atime, mtime);
        this.enqueueOp({
            apiMethod: 'utimes',
            arguments: [p, atime, mtime]
        });
    };
    return AsyncMirror;
}(file_system.SynchronousFileSystem));
exports.__esModule = true;
exports["default"] = AsyncMirror;
//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJmaWxlIjoiQXN5bmNNaXJyb3IuanMiLCJzb3VyY2VSb290IjoiIiwic291cmNlcyI6WyIuLi8uLi8uLi9zcmMvYmFja2VuZC9Bc3luY01pcnJvci50cyJdLCJuYW1lcyI6W10sIm1hcHBpbmdzIjoiOzs7Ozs7QUFBQSxJQUFPLFdBQVcsV0FBVyxxQkFBcUIsQ0FBQyxDQUFDO0FBQ3BELDBCQUFrQyxtQkFBbUIsQ0FBQyxDQUFBO0FBQ3RELElBQU8sU0FBUyxXQUFXLG1CQUFtQixDQUFDLENBQUM7QUFHaEQsSUFBTyxZQUFZLFdBQVcseUJBQXlCLENBQUMsQ0FBQztBQVV6RDtJQUF5Qiw4QkFBcUM7SUFDNUQsb0JBQVksRUFBZSxFQUFFLElBQVksRUFBRSxJQUF3QixFQUFFLElBQVcsRUFBRSxJQUFZO1FBQzVGLGtCQUFNLEVBQUUsRUFBRSxJQUFJLEVBQUUsSUFBSSxFQUFFLElBQUksRUFBRSxJQUFJLENBQUMsQ0FBQztJQUNwQyxDQUFDO0lBRU0sNkJBQVEsR0FBZjtRQUNFLEVBQUUsQ0FBQyxDQUFDLElBQUksQ0FBQyxPQUFPLEVBQUUsQ0FBQyxDQUFDLENBQUM7WUFDbkIsSUFBSSxDQUFDLEdBQUcsQ0FBQyxTQUFTLENBQUMsSUFBSSxDQUFDLENBQUM7WUFDekIsSUFBSSxDQUFDLFVBQVUsRUFBRSxDQUFDO1FBQ3BCLENBQUM7SUFDSCxDQUFDO0lBRU0sOEJBQVMsR0FBaEI7UUFDRSxJQUFJLENBQUMsUUFBUSxFQUFFLENBQUM7SUFDbEIsQ0FBQztJQUNILGlCQUFDO0FBQUQsQ0FBQyxBQWZELENBQXlCLFlBQVksQ0FBQyxXQUFXLEdBZWhEO0FBWUQ7SUFBeUMsK0JBQWlDO0lBVXhFLHFCQUFZLElBQTRCLEVBQUUsS0FBNkI7UUFDckUsaUJBQU8sQ0FBQztRQVBGLFdBQU0sR0FBc0IsRUFBRSxDQUFDO1FBQy9CLGtCQUFhLEdBQVksS0FBSyxDQUFDO1FBRy9CLG1CQUFjLEdBQVksS0FBSyxDQUFDO1FBQ2hDLHlCQUFvQixHQUErQixFQUFFLENBQUM7UUFHNUQsSUFBSSxD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UyxDQUFDLENBQUMsRUFBRSxRQUFRLEVBQUUsSUFBSSxDQUFDLENBQUM7UUFDeEMsSUFBSSxDQUFDLFNBQVMsQ0FBQztZQUNiLFNBQVMsRUFBRSxPQUFPO1lBQ2xCLFNBQVMsRUFBRSxDQUFDLENBQUMsRUFBRSxRQUFRLEVBQUUsSUFBSSxDQUFDO1NBQy9CLENBQUMsQ0FBQztJQUNMLENBQUM7SUFDTSwrQkFBUyxHQUFoQixVQUFpQixDQUFTLEVBQUUsUUFBaUIsRUFBRSxHQUFXLEVBQUUsR0FBVztRQUNyRSxJQUFJLENBQUMsZ0JBQWdCLEVBQUUsQ0FBQztRQUN4QixJQUFJLENBQUMsS0FBSyxDQUFDLFNBQVMsQ0FBQyxDQUFDLEVBQUUsUUFBUSxFQUFFLEdBQUcsRUFBRSxHQUFHLENBQUMsQ0FBQztRQUM1QyxJQUFJLENBQUMsU0FBUyxDQUFDO1lBQ2IsU0FBUyxFQUFFLE9BQU87WUFDbEIsU0FBUyxFQUFFLENBQUMsQ0FBQyxFQUFFLFFBQVEsRUFBRSxHQUFHLEVBQUUsR0FBRyxDQUFDO1NBQ25DLENBQUMsQ0FBQztJQUNMLENBQUM7SUFDTSxnQ0FBVSxHQUFqQixVQUFrQixDQUFTLEVBQUUsS0FBVyxFQUFFLEtBQVc7UUFDbkQsSUFBSSxDQUFDLGdCQUFnQixFQUFFLENBQUM7UUFDeEIsSUFBSSxDQUFDLEtBQUssQ0FBQyxVQUFVLENBQUMsQ0FBQyxFQUFFLEtBQUssRUFBRSxLQUFLLENBQUMsQ0FBQztRQUN2QyxJQUFJLENBQUMsU0FBUyxDQUFDO1lBQ2IsU0FBUyxFQUFFLFFBQVE7WUFDbkIsU0FBUyxFQUFFLENBQUMsQ0FBQyxFQUFFLEtBQUssRUFBRSxLQUFLLENBQUM7U0FDN0IsQ0FBQyxDQUFDO0lBQ0wsQ0FBQztJQUNILGtCQUFDO0FBQUQsQ0FBQyxBQXRORCxDQUF5QyxXQUFXLENBQUMscUJBQXFCLEdBc056RTtBQXRORDtnQ0FzTkMsQ0FBQSIsInNvdXJjZXNDb250ZW50IjpbImltcG9ydCBmaWxlX3N5c3RlbSA9IHJlcXVpcmUoJy4uL2NvcmUvZmlsZV9zeXN0ZW0nKTtcclxuaW1wb3J0IHtBcGlFcnJvciwgRXJyb3JDb2RlfSBmcm9tICcuLi9jb3JlL2FwaV9lcnJvcic7XHJcbmltcG9ydCBmaWxlX2ZsYWcgPSByZXF1aXJlKCcuLi9jb3JlL2ZpbGVfZmxhZycpO1xyXG5pbXBvcnQgZmlsZSA9IHJlcXVpcmUoJy4uL2NvcmUvZmlsZScpO1xyXG5pbXBvcnQgU3RhdHMgZnJvbSAnLi4vY29yZS9ub2RlX2ZzX3N0YXRzJztcclxuaW1wb3J0IHByZWxvYWRfZmlsZSA9IHJlcXVpcmUoJy4uL2dlbmVyaWMvcHJlbG9hZF9maWxlJyk7XHJcblxyXG5pbnRlcmZhY2UgSUFzeW5jT3BlcmF0aW9uIHtcclxuXHRhcGlNZXRob2Q6IHN0cmluZztcclxuXHRhcmd1bWVudHM6IGFueVtdO1xyXG59XHJcblxyXG4vKipcclxuICogV2UgZGVmaW5lIG91ciBvd24gZmlsZSB0byBpbnRlcnBvc2Ugb24gc3luY1N5bmMoKSBmb3IgbWlycm9yaW5nIHB1cnBvc2VzLlxyXG4gKi9cclxuY2xhc3MgTWlycm9yRmlsZSBleHRlbmRzIHByZWxvYWRfZmlsZS5QcmVsb2FkRmlsZTxBc3luY01pcnJvcj4gaW1wbGVtZW50cyBmaWxlLkZpbGUge1xyXG4gIGNvbnN0cnVjdG9yKGZzOiBBc3luY01pcnJvciwgcGF0aDogc3RyaW5nLCBmbGFnOiBmaWxlX2ZsYWcuRmlsZUZsYWcsIHN0YXQ6IFN0YXRzLCBkYXRhOiBCdWZmZXIpIHtcclxuICAgIHN1cGVyKGZzLCBwYXRoLCBmbGFnLCBzdGF0LCBkYXRhKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBzeW5jU3luYygpOiB2b2lkIHtcclxuICAgIGlmICh0aGlzLmlzRGlydHkoKSkge1xyXG4gICAgICB0aGlzLl9mcy5fc3luY1N5bmModGhpcyk7XHJcbiAgICAgIHRoaXMucmVzZXREaXJ0eSgpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgcHVibGljIGNsb3NlU3luYygpOiB2b2lkIHtcclxuICAgIHRoaXMuc3luY1N5bmMoKTtcclxuICB9XHJcbn1cclxuXHJcbi8qKlxyXG4gKiBBc3luY01pcnJvckZTIG1pcnJvcnMgYSBzeW5jaHJvbm91cyBmaWxlc3lzdGVtIGludG8gYW4gYXN5bmNocm9ub3VzIGZpbGVzeXN0ZW1cclxuICogYnk6XHJcbiAqICogUGVyZm9ybWluZyBvcGVyYXRpb25zIG92ZXIgdGhlIGluLW1lbW9yeSBjb3B5LCB3aGlsZSBhc3luY2hyb25vdXNseSBwaXBlbGluaW5nIHRoZW1cclxuICogICB0byB0aGUgYmFja2luZyBzdG9yZS5cclxuICogKiBEdXJpbmcgYXBwbGljYXRpb24gbG9hZGluZywgdGhlIGNvbnRlbnRzIG9mIHRoZSBhc3luYyBmaWxlIHN5c3RlbSBjYW4gYmUgcmVsb2FkZWQgaW50b1xyXG4gKiAgIHRoZSBzeW5jaHJvbm91cyBzdG9yZSwgaWYgZGVzaXJlZC5cclxuICogVGhlIHR3byBzdG9yZXMgd2lsbCBiZSBrZXB0IGluIHN5bmMuIFRoZSBtb3N0IGNvbW1vbiB1c2UtY2FzZSBpcyB0byBwYWlyIGEgc3luY2hyb25vdXNcclxuICogaW4tbWVtb3J5IGZpbGVzeXN0ZW0gd2l0aCBhbiBhc3luY2hyb25vdXMgYmFja2luZyBzdG9yZS5cclxuICovXHJcbmV4cG9ydCBkZWZhdWx0IGNsYXNzIEFzeW5jTWlycm9yIGV4dGVuZHMgZmlsZV9zeXN0ZW0uU3luY2hyb25vdXNGaWxlU3lzdGVtIGltcGxlbWVudHMgZmlsZV9zeXN0ZW0uRmlsZVN5c3RlbSB7XHJcbiAgLyoqXHJcbiAgICogUXVldWUgb2YgcGVuZGluZyBhc3luY2hyb25vdXMgb3BlcmF0aW9ucy5cclxuICAgKi9cclxuICBwcml2YXRlIF9xdWV1ZTogSUFzeW5jT3BlcmF0aW9uW10gPSBbXTtcclxuICBwcml2YXRlIF9xdWV1ZVJ1bm5pbmc6IGJvb2xlYW4gPSBmYWxzZTtcclxuICBwcml2YXRlIF9zeW5jOiBmaWxlX3N5c3RlbS5GaWxlU3lzdGVtO1xyXG4gIHByaXZhdGUgX2FzeW5jOiBmaWxlX3N5c3RlbS5GaWxlU3lzdGVtO1xyXG4gIHByaXZhdGUgX2lzSW5pdGlhbGl6ZWQ6IGJvb2xlYW4gPSBmYWxzZTtcclxuICBwcml2YXRlIF9pbml0aWFsaXplQ2FsbGJhY2tzOiAoKGU/OiBBcGlFcnJvcikgPT4gdm9pZClbXSA9IFtdO1xyXG4gIGNvbnN0cnVjdG9yKHN5bmM6IGZpbGVfc3lzdGVtLkZpbGVTeXN0ZW0sIGFzeW5jOiBmaWxlX3N5c3RlbS5GaWxlU3lzdGVtKSB7XHJcbiAgICBzdXBlcigpO1xyXG4gICAgdGhpcy5fc3luYyA9IHN5bmM7XHJcbiAgICB0aGlzLl9hc3luYyA9IGFzeW5jO1xyXG4gICAgaWYgKCFzeW5jLnN1cHBvcnRzU3luY2goKSkge1xyXG4gICAgICB0aHJvdyBuZXcgRXJyb3IoXCJFeHBlY3RlZCBzeW5jaHJvbm91cyBzdG9yYWdlLlwiKTtcclxuICAgIH1cclxuICAgIGlmIChhc3luYy5zdXBwb3J0c1N5bmNoKCkpIHtcclxuICAgICAgdGhyb3cgbmV3IEVycm9yKFwiRXhwZWN0ZWQgYXN5bmNocm9ub3VzIHN0b3JhZ2UuXCIpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgcHVibGljIGdldE5hbWUoKTogc3RyaW5nIHtcclxuXHQgXHRyZXR1cm4gXCJBc3luY01pcnJvclwiO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIHN0YXRpYyBpc0F2YWlsYWJsZSgpOiBib29sZWFuIHtcclxuICAgIHJldHVybiB0cnVlO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIF9zeW5jU3luYyhmZDogcHJlbG9hZF9maWxlLlByZWxvYWRGaWxlPGFueT4pIHtcclxuICAgIHRoaXMuX3N5bmMud3JpdGVGaWxlU3luYyhmZC5nZXRQYXRoKCksIGZkLmdldEJ1ZmZlcigpLCBudWxsLCBmaWxlX2ZsYWcuRmlsZUZsYWcuZ2V0RmlsZUZsYWcoJ3cnKSwgZmQuZ2V0U3RhdHMoKS5tb2RlKTtcclxuICAgIHRoaXMuZW5xdWV1ZU9wKHtcclxuICAgICAgYXBpTWV0aG9kOiAnd3JpdGVGaWxlJyxcclxuICAgICAgYXJndW1lbnRzOiBbZmQuZ2V0UGF0aCgpLCBmZC5nZXRCdWZmZXIoKSwgbnVsbCwgZmQuZ2V0RmxhZygpLCBmZC5nZXRTdGF0cygpLm1vZGVdXHJcbiAgICB9KTtcclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIENhbGxlZCBvbmNlIHRvIGxvYWQgdXAgZmlsZXMgZnJvbSBhc3luYyBzdG9yYWdlIGludG8gc3luYyBzdG9yYWdlLlxyXG4gICAqL1xyXG4gIHB1YmxpYyBpbml0aWFsaXplKHVzZXJDYjogKGVycj86IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICBjb25zdCBjYWxsYmFja3MgPSB0aGlzLl9pbml0aWFsaXplQ2FsbGJhY2tzO1xyXG5cclxuICAgIGNvbnN0IGVuZCA9IChlPzogQXBpRXJyb3IpOiB2b2lkID0+IHtcclxuICAgICAgdGhpcy5faXNJbml0aWFsaXplZCA9ICFlO1xyXG4gICAgICB0aGlzLl9pbml0aWFsaXplQ2FsbGJhY2tzID0gW107XHJcbiAgICAgIGNhbGxiYWNrcy5mb3JFYWNoKChjYikgPT4gY2IoZSkpO1xyXG4gICAgfTtcclxuXHJcbiAgICBpZiAoIXRoaXMuX2lzSW5pdGlhbGl6ZWQpIHtcclxuICAgICAgLy8gRmlyc3QgY2FsbCB0cmlnZ2VycyBpbml0aWFsaXphdGlvbiwgdGhlIHJlc3Qgd2FpdC5cclxuICAgICAgaWYgKGNhbGxiYWNrcy5wdXNoKHVzZXJDYikgPT09IDEpIHtcclxuICAgICAgICBjb25zdCBjb3B5RGlyZWN0b3J5ID0gKHA6IHN0cmluZywgbW9kZTogbnVtYmVyLCBjYjogKGVycj86IEFwaUVycm9yKSA9PiB2b2lkKSA9PiB7XHJcbiAgICAgICAgICBpZiAocCAhPT0gJy8nKSB7XHJcbiAgICAgICAgICAgIHRoaXMuX3N5bmMubWtkaXJTeW5jKHAsIG1vZGUpO1xyXG4gICAgICAgICAgfVxyXG4gICAgICAgICAgdGhpcy5fYXN5bmMucmVhZGRpcihwLCAoZXJyLCBmaWxlcykgPT4ge1xyXG4gICAgICAgICAgICBpZiAoZXJyKSB7XHJcbiAgICAgICAgICAgICAgY2IoZXJyKTtcclxuICAgICAgICAgICAgfSBlbHNlIHtcclxuICAgICAgICAgICAgICB2YXIgaSA9IDA7XHJcbiAgICAgICAgICAgICAgZnVuY3Rpb24gY29weU5leHRGaWxlKGVycj86IEFwaUVycm9yKSB7XHJcbiAgICAgICAgICAgICAgICBpZiAoZXJyKSB7XHJcbiAgICAgICAgICAgICAgICAgIGNiKGVycik7XHJcbiAgICAgICAgICAgICAgICB9IGVsc2UgaWYgKGkgPCBmaWxlcy5sZW5ndGgpIHtcclxuICAgICAgICAgICAgICAgICAgY29weUl0ZW0oYCR7cH0vJHtmaWxlc1tpXX1gLCBjb3B5TmV4dEZpbGUpO1xyXG4gICAgICAgICAgICAgICAgICBpKys7XHJcbiAgICAgICAgICAgICAgICB9IGVsc2Uge1xyXG4gICAgICAgICAgICAgICAgICBjYigpO1xyXG4gICAgICAgICAgICAgICAgfVxyXG4gICAgICAgICAgICAgIH1cclxuICAgICAgICAgICAgICBjb3B5TmV4dEZpbGUoKTtcclxuICAgICAgICAgICAgfVxyXG4gICAgICAgICAgfSk7XHJcbiAgICAgICAgfSwgY29weUZpbGUgPSAocDogc3RyaW5nLCBtb2RlOiBudW1iZXIsIGNiOiAoZXJyPzogQXBpRXJyb3IpID0+IHZvaWQpID0+IHtcclxuICAgICAgICAgIHRoaXMuX2FzeW5jLnJlYWRGaWxlKHAsIG51bGwsIGZpbGVfZmxhZy5GaWxlRmxhZy5nZXRGaWxlRmxhZygncicpLCAoZXJyLCBkYXRhKSA9PiB7XHJcbiAgICAgICAgICAgIGlmIChlcnIpIHtcclxuICAgICAgICAgICAgICBjYihlcnIpO1xyXG4gICAgICAgICAgICB9IGVsc2Uge1xyXG4gICAgICAgICAgICAgIHRyeSB7XHJcbiAgICAgICAgICAgICAgICB0aGlzLl9zeW5jLndyaXRlRmlsZVN5bmMocCwgZGF0YSwgbnVsbCwgZmlsZV9mbGFnLkZpbGVGbGFnLmdldEZpbGVGbGFnKCd3JyksIG1vZGUpO1xyXG4gICAgICAgICAgICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgICAgICAgICAgIGVyciA9IGU7XHJcbiAgICAgICAgICAgICAgfSBmaW5hbGx5IHtcclxuICAgICAgICAgICAgICAgIGNiKGVycik7XHJcbiAgICAgICAgICAgICAgfVxyXG4gICAgICAgICAgICB9XHJcbiAgICAgICAgICB9KTtcclxuICAgICAgICB9LCBjb3B5SXRlbSA9IChwOiBzdHJpbmcsIGNiOiAoZXJyPzogQXBpRXJyb3IpID0+IHZvaWQpID0+IHtcclxuICAgICAgICAgIHRoaXMuX2FzeW5jLnN0YXQocCwgZmFsc2UsIChlcnIsIHN0YXRzKSA9PiB7XHJcbiAgICAgICAgICAgIGlmIChlcnIpIHtcclxuICAgICAgICAgICAgICBjYihlcnIpO1xyXG4gICAgICAgICAgICB9IGVsc2UgaWYgKHN0YXRzLmlzRGlyZWN0b3J5KCkpIHtcclxuICAgICAgICAgICAgICBjb3B5RGlyZWN0b3J5KHAsIHN0YXRzLm1vZGUsIGNiKTtcclxuICAgICAgICAgICAgfSBlbHNlIHtcclxuICAgICAgICAgICAgICBjb3B5RmlsZShwLCBzdGF0cy5tb2RlLCBjYik7XHJcbiAgICAgICAgICAgIH1cclxuICAgICAgICAgIH0pO1xyXG4gICAgICAgIH07XHJcbiAgICAgICAgY29weURpcmVjdG9yeSgnLycsIDAsIGVuZCk7XHJcbiAgICAgIH1cclxuICAgIH0gZWxzZSB7XHJcbiAgICAgIHVzZXJDYigpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgcHJpdmF0ZSBjaGVja0luaXRpYWxpemVkKCk6IHZvaWQge1xyXG4gICAgaWYgKCF0aGlzLl9pc0luaXRpYWxpemVkKSB7XHJcbiAgICAgIHRocm93IG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRVBFUk0sIFwiT3ZlcmxheUZTIGlzIG5vdCBpbml0aWFsaXplZC4gUGxlYXNlIGluaXRpYWxpemUgT3ZlcmxheUZTIHVzaW5nIGl0cyBpbml0aWFsaXplKCkgbWV0aG9kIGJlZm9yZSB1c2luZyBpdC5cIik7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgaXNSZWFkT25seSgpOiBib29sZWFuIHsgcmV0dXJuIGZhbHNlOyB9XHJcbiAgcHVibGljIHN1cHBvcnRzU3luY2goKTogYm9vbGVhbiB7IHJldHVybiB0cnVlOyB9XHJcbiAgcHVibGljIHN1cHBvcnRzTGlua3MoKTogYm9vbGVhbiB7IHJldHVybiBmYWxzZTsgfVxyXG4gIHB1YmxpYyBzdXBwb3J0c1Byb3BzKCk6IGJvb2xlYW4geyByZXR1cm4gdGhpcy5fc3luYy5zdXBwb3J0c1Byb3BzKCkgJiYgdGhpcy5fYXN5bmMuc3VwcG9ydHNQcm9wcygpOyB9XHJcblxyXG4gIHByaXZhdGUgZW5xdWV1ZU9wKG9wOiBJQXN5bmNPcGVyYXRpb24pIHtcclxuICAgIHRoaXMuX3F1ZXVlLnB1c2gob3ApO1xyXG4gICAgaWYgKCF0aGlzLl9xdWV1ZVJ1bm5pbmcpIHtcclxuICAgICAgdGhpcy5fcXVldWVSdW5uaW5nID0gdHJ1ZTtcclxuICAgICAgdmFyIGRvTmV4dE9wID0gKGVycj86IEFwaUVycm9yKSA9PiB7XHJcbiAgICAgICAgaWYgKGVycikge1xyXG4gICAgICAgICAgY29uc29sZS5lcnJvcihgV0FSTklORzogRmlsZSBzeXN0ZW0gaGFzIGRlc3luY2hyb25pemVkLiBSZWNlaXZlZCBmb2xsb3dpbmcgZXJyb3I6ICR7ZXJyfVxcbiRgKTtcclxuICAgICAgICB9XHJcbiAgICAgICAgaWYgKHRoaXMuX3F1ZXVlLmxlbmd0aCA+IDApIHtcclxuICAgICAgICAgIHZhciBvcCA9IHRoaXMuX3F1ZXVlLnNoaWZ0KCksXHJcbiAgICAgICAgICAgIGFyZ3MgPSBvcC5hcmd1bWVudHM7XHJcbiAgICAgICAgICBhcmdzLnB1c2goZG9OZXh0T3ApO1xyXG4gICAgICAgICAgKDxGdW5jdGlvbj4gKDxhbnk+IHRoaXMuX2FzeW5jKVtvcC5hcGlNZXRob2RdKS5hcHBseSh0aGlzLl9hc3luYywgYXJncyk7XHJcbiAgICAgICAgfSBlbHNlIHtcclxuICAgICAgICAgIHRoaXMuX3F1ZXVlUnVubmluZyA9IGZhbHNlO1xyXG4gICAgICAgIH1cclxuICAgICAgfTtcclxuICAgICAgZG9OZXh0T3AoKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIHB1YmxpYyByZW5hbWVTeW5jKG9sZFBhdGg6IHN0cmluZywgbmV3UGF0aDogc3RyaW5nKTogdm9pZCB7XHJcbiAgICB0aGlzLmNoZWNrSW5pdGlhbGl6ZWQoKTtcclxuICAgIHRoaXMuX3N5bmMucmVuYW1lU3luYyhvbGRQYXRoLCBuZXdQYXRoKTtcclxuICAgIHRoaXMuZW5xdWV1ZU9wKHtcclxuICAgICAgYXBpTWV0aG9kOiAncmVuYW1lJyxcclxuICAgICAgYXJndW1lbnRzOiBbb2xkUGF0aCwgbmV3UGF0aF1cclxuICAgIH0pO1xyXG4gIH1cclxuICBwdWJsaWMgc3RhdFN5bmMocDogc3RyaW5nLCBpc0xzdGF0OiBib29sZWFuKTogU3RhdHMge1xyXG4gICAgdGhpcy5jaGVja0luaXRpYWxpemVkKCk7XHJcbiAgICByZXR1cm4gdGhpcy5fc3luYy5zdGF0U3luYyhwLCBpc0xzdGF0KTtcclxuICB9XHJcbiAgcHVibGljIG9wZW5TeW5jKHA6IHN0cmluZywgZmxhZzogZmlsZV9mbGFnLkZpbGVGbGFnLCBtb2RlOiBudW1iZXIpOiBmaWxlLkZpbGUge1xyXG4gICAgdGhpcy5jaGVja0luaXRpYWxpemVkKCk7XHJcbiAgICAvLyBTYW5pdHkgY2hlY2s6IElzIHRoaXMgb3Blbi9jbG9zZSBwZXJtaXR0ZWQ/XHJcbiAgICB2YXIgZmQgPSB0aGlzLl9zeW5jLm9wZW5TeW5jKHAsIGZsYWcsIG1vZGUpO1xyXG4gICAgZmQuY2xvc2VTeW5jKCk7XHJcbiAgICByZXR1cm4gbmV3IE1pcnJvckZpbGUodGhpcywgcCwgZmxhZywgdGhpcy5fc3luYy5zdGF0U3luYyhwLCBmYWxzZSksIHRoaXMuX3N5bmMucmVhZEZpbGVTeW5jKHAsIG51bGwsIGZpbGVfZmxhZy5GaWxlRmxhZy5nZXRGaWxlRmxhZygncicpKSk7XHJcbiAgfVxyXG4gIHB1YmxpYyB1bmxpbmtTeW5jKHA6IHN0cmluZyk6IHZvaWQge1xyXG4gICAgdGhpcy5jaGVja0luaXRpYWxpemVkKCk7XHJcbiAgICB0aGlzLl9zeW5jLnVubGlua1N5bmMocCk7XHJcbiAgICB0aGlzLmVucXVldWVPcCh7XHJcbiAgICAgIGFwaU1ldGhvZDogJ3VubGluaycsXHJcbiAgICAgIGFyZ3VtZW50czogW3BdXHJcbiAgICB9KTtcclxuICB9XHJcbiAgcHVibGljIHJtZGlyU3luYyhwOiBzdHJpbmcpOiB2b2lkIHtcclxuICAgIHRoaXMuY2hlY2tJbml0aWFsaXplZCgpO1xyXG4gICAgdGhpcy5fc3luYy5ybWRpclN5bmMocCk7XHJcbiAgICB0aGlzLmVucXVldWVPcCh7XHJcbiAgICAgIGFwaU1ldGhvZDogJ3JtZGlyJyxcclxuICAgICAgYXJndW1lbnRzOiBbcF1cclxuICAgIH0pO1xyXG4gIH1cclxuICBwdWJsaWMgbWtkaXJTeW5jKHA6IHN0cmluZywgbW9kZTogbnVtYmVyKTogdm9pZCB7XHJcbiAgICB0aGlzLmNoZWNrSW5pdGlhbGl6ZWQoKTtcclxuICAgIHRoaXMuX3N5bmMubWtkaXJTeW5jKHAsIG1vZGUpO1xyXG4gICAgdGhpcy5lbnF1ZXVlT3Aoe1xyXG4gICAgICBhcGlNZXRob2Q6ICdta2RpcicsXHJcbiAgICAgIGFyZ3VtZW50czogW3AsIG1vZGVdXHJcbiAgICB9KTtcclxuICB9XHJcbiAgcHVibGljIHJlYWRkaXJTeW5jKHA6IHN0cmluZyk6IHN0cmluZ1tdIHtcclxuICAgIHRoaXMuY2hlY2tJbml0aWFsaXplZCgpO1xyXG4gICAgcmV0dXJuIHRoaXMuX3N5bmMucmVhZGRpclN5bmMocCk7XHJcbiAgfVxyXG4gIHB1YmxpYyBleGlzdHNTeW5jKHA6IHN0cmluZyk6IGJvb2xlYW4ge1xyXG4gICAgdGhpcy5jaGVja0luaXRpYWxpemVkKCk7XHJcbiAgICByZXR1cm4gdGhpcy5fc3luYy5leGlzdHNTeW5jKHApO1xyXG4gIH1cclxuICBwdWJsaWMgY2htb2RTeW5jKHA6IHN0cmluZywgaXNMY2htb2Q6IGJvb2xlYW4sIG1vZGU6IG51bWJlcik6IHZvaWQge1xyXG4gICAgdGhpcy5jaGVja0luaXRpYWxpemVkKCk7XHJcbiAgICB0aGlzLl9zeW5jLmNobW9kU3luYyhwLCBpc0xjaG1vZCwgbW9kZSk7XHJcbiAgICB0aGlzLmVucXVldWVPcCh7XHJcbiAgICAgIGFwaU1ldGhvZDogJ2NobW9kJyxcclxuICAgICAgYXJndW1lbnRzOiBbcCwgaXNMY2htb2QsIG1vZGVdXHJcbiAgICB9KTtcclxuICB9XHJcbiAgcHVibGljIGNob3duU3luYyhwOiBzdHJpbmcsIGlzTGNob3duOiBib29sZWFuLCB1aWQ6IG51bWJlciwgZ2lkOiBudW1iZXIpOiB2b2lkIHtcclxuICAgIHRoaXMuY2hlY2tJbml0aWFsaXplZCgpO1xyXG4gICAgdGhpcy5fc3luYy5jaG93blN5bmMocCwgaXNMY2hvd24sIHVpZCwgZ2lkKTtcclxuICAgIHRoaXMuZW5xdWV1ZU9wKHtcclxuICAgICAgYXBpTWV0aG9kOiAnY2hvd24nLFxyXG4gICAgICBhcmd1bWVudHM6IFtwLCBpc0xjaG93biwgdWlkLCBnaWRdXHJcbiAgICB9KTtcclxuICB9XHJcbiAgcHVibGljIHV0aW1lc1N5bmMocDogc3RyaW5nLCBhdGltZTogRGF0ZSwgbXRpbWU6IERhdGUpOiB2b2lkIHtcclxuICAgIHRoaXMuY2hlY2tJbml0aWFsaXplZCgpO1xyXG4gICAgdGhpcy5fc3luYy51dGltZXNTeW5jKHAsIGF0aW1lLCBtdGltZSk7XHJcbiAgICB0aGlzLmVucXVldWVPcCh7XHJcbiAgICAgIGFwaU1ldGhvZDogJ3V0aW1lcycsXHJcbiAgICAgIGFyZ3VtZW50czogW3AsIGF0aW1lLCBtdGltZV1cclxuICAgIH0pO1xyXG4gIH1cclxufVxyXG4iXX0=