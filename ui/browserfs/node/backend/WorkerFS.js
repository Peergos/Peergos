"use strict";
var __extends = (this && this.__extends) || function (d, b) {
    for (var p in b) if (b.hasOwnProperty(p)) d[p] = b[p];
    function __() { this.constructor = d; }
    d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
};
var file_system = require('../core/file_system');
var api_error_1 = require('../core/api_error');
var file_flag = require('../core/file_flag');
var util_1 = require('../core/util');
var file = require('../core/file');
var node_fs_stats_1 = require('../core/node_fs_stats');
var preload_file = require('../generic/preload_file');
var global = require('../core/global');
var fs = require('../core/node_fs');
var SpecialArgType;
(function (SpecialArgType) {
    SpecialArgType[SpecialArgType["CB"] = 0] = "CB";
    SpecialArgType[SpecialArgType["FD"] = 1] = "FD";
    SpecialArgType[SpecialArgType["API_ERROR"] = 2] = "API_ERROR";
    SpecialArgType[SpecialArgType["STATS"] = 3] = "STATS";
    SpecialArgType[SpecialArgType["PROBE"] = 4] = "PROBE";
    SpecialArgType[SpecialArgType["FILEFLAG"] = 5] = "FILEFLAG";
    SpecialArgType[SpecialArgType["BUFFER"] = 6] = "BUFFER";
    SpecialArgType[SpecialArgType["ERROR"] = 7] = "ERROR";
})(SpecialArgType || (SpecialArgType = {}));
var CallbackArgumentConverter = (function () {
    function CallbackArgumentConverter() {
        this._callbacks = {};
        this._nextId = 0;
    }
    CallbackArgumentConverter.prototype.toRemoteArg = function (cb) {
        var id = this._nextId++;
        this._callbacks[id] = cb;
        return {
            type: SpecialArgType.CB,
            id: id
        };
    };
    CallbackArgumentConverter.prototype.toLocalArg = function (id) {
        var cb = this._callbacks[id];
        delete this._callbacks[id];
        return cb;
    };
    return CallbackArgumentConverter;
}());
var FileDescriptorArgumentConverter = (function () {
    function FileDescriptorArgumentConverter() {
        this._fileDescriptors = {};
        this._nextId = 0;
    }
    FileDescriptorArgumentConverter.prototype.toRemoteArg = function (fd, p, flag, cb) {
        var id = this._nextId++, data, stat, argsLeft = 2;
        this._fileDescriptors[id] = fd;
        fd.stat(function (err, stats) {
            if (err) {
                cb(err);
            }
            else {
                stat = bufferToTransferrableObject(stats.toBuffer());
                if (flag.isReadable()) {
                    fd.read(new Buffer(stats.size), 0, stats.size, 0, function (err, bytesRead, buff) {
                        if (err) {
                            cb(err);
                        }
                        else {
                            data = bufferToTransferrableObject(buff);
                            cb(null, {
                                type: SpecialArgType.FD,
                                id: id,
                                data: data,
                                stat: stat,
                                path: p,
                                flag: flag.getFlagString()
                            });
                        }
                    });
                }
                else {
                    cb(null, {
                        type: SpecialArgType.FD,
                        id: id,
                        data: new ArrayBuffer(0),
                        stat: stat,
                        path: p,
                        flag: flag.getFlagString()
                    });
                }
            }
        });
    };
    FileDescriptorArgumentConverter.prototype._applyFdChanges = function (remoteFd, cb) {
        var fd = this._fileDescriptors[remoteFd.id], data = transferrableObjectToBuffer(remoteFd.data), remoteStats = node_fs_stats_1["default"].fromBuffer(transferrableObjectToBuffer(remoteFd.stat));
        var flag = file_flag.FileFlag.getFileFlag(remoteFd.flag);
        if (flag.isWriteable()) {
            fd.write(data, 0, data.length, flag.isAppendable() ? fd.getPos() : 0, function (e) {
                if (e) {
                    cb(e);
                }
                else {
                    function applyStatChanges() {
                        fd.stat(function (e, stats) {
                            if (e) {
                                cb(e);
                            }
                            else {
                                if (stats.mode !== remoteStats.mode) {
                                    fd.chmod(remoteStats.mode, function (e) {
                                        cb(e, fd);
                                    });
                                }
                                else {
                                    cb(e, fd);
                                }
                            }
                        });
                    }
                    if (!flag.isAppendable()) {
                        fd.truncate(data.length, function () {
                            applyStatChanges();
                        });
                    }
                    else {
                        applyStatChanges();
                    }
                }
            });
        }
        else {
            cb(null, fd);
        }
    };
    FileDescriptorArgumentConverter.prototype.applyFdAPIRequest = function (request, cb) {
        var _this = this;
        var fdArg = request.args[0];
        this._applyFdChanges(fdArg, function (err, fd) {
            if (err) {
                cb(err);
            }
            else {
                fd[request.method](function (e) {
                    if (request.method === 'close') {
                        delete _this._fileDescriptors[fdArg.id];
                    }
                    cb(e);
                });
            }
        });
    };
    return FileDescriptorArgumentConverter;
}());
function apiErrorLocal2Remote(e) {
    return {
        type: SpecialArgType.API_ERROR,
        errorData: bufferToTransferrableObject(e.writeToBuffer())
    };
}
function apiErrorRemote2Local(e) {
    return api_error_1.ApiError.fromBuffer(transferrableObjectToBuffer(e.errorData));
}
function errorLocal2Remote(e) {
    return {
        type: SpecialArgType.ERROR,
        name: e.name,
        message: e.message,
        stack: e.stack
    };
}
function errorRemote2Local(e) {
    var cnstr = global[e.name];
    if (typeof (cnstr) !== 'function') {
        cnstr = Error;
    }
    var err = new cnstr(e.message);
    err.stack = e.stack;
    return err;
}
function statsLocal2Remote(stats) {
    return {
        type: SpecialArgType.STATS,
        statsData: bufferToTransferrableObject(stats.toBuffer())
    };
}
function statsRemote2Local(stats) {
    return node_fs_stats_1["default"].fromBuffer(transferrableObjectToBuffer(stats.statsData));
}
function fileFlagLocal2Remote(flag) {
    return {
        type: SpecialArgType.FILEFLAG,
        flagStr: flag.getFlagString()
    };
}
function fileFlagRemote2Local(remoteFlag) {
    return file_flag.FileFlag.getFileFlag(remoteFlag.flagStr);
}
function bufferToTransferrableObject(buff) {
    return util_1.buffer2ArrayBuffer(buff);
}
function transferrableObjectToBuffer(buff) {
    return util_1.arrayBuffer2Buffer(buff);
}
function bufferLocal2Remote(buff) {
    return {
        type: SpecialArgType.BUFFER,
        data: bufferToTransferrableObject(buff)
    };
}
function bufferRemote2Local(buffArg) {
    return transferrableObjectToBuffer(buffArg.data);
}
function isAPIRequest(data) {
    return data != null && typeof data === 'object' && data.hasOwnProperty('browserfsMessage') && data['browserfsMessage'];
}
function isAPIResponse(data) {
    return data != null && typeof data === 'object' && data.hasOwnProperty('browserfsMessage') && data['browserfsMessage'];
}
var WorkerFile = (function (_super) {
    __extends(WorkerFile, _super);
    function WorkerFile(_fs, _path, _flag, _stat, remoteFdId, contents) {
        _super.call(this, _fs, _path, _flag, _stat, contents);
        this._remoteFdId = remoteFdId;
    }
    WorkerFile.prototype.getRemoteFdId = function () {
        return this._remoteFdId;
    };
    WorkerFile.prototype.toRemoteArg = function () {
        return {
            type: SpecialArgType.FD,
            id: this._remoteFdId,
            data: bufferToTransferrableObject(this.getBuffer()),
            stat: bufferToTransferrableObject(this.getStats().toBuffer()),
            path: this.getPath(),
            flag: this.getFlag().getFlagString()
        };
    };
    WorkerFile.prototype._syncClose = function (type, cb) {
        var _this = this;
        if (this.isDirty()) {
            this._fs.syncClose(type, this, function (e) {
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
    WorkerFile.prototype.sync = function (cb) {
        this._syncClose('sync', cb);
    };
    WorkerFile.prototype.close = function (cb) {
        this._syncClose('close', cb);
    };
    return WorkerFile;
}(preload_file.PreloadFile));
var WorkerFS = (function (_super) {
    __extends(WorkerFS, _super);
    function WorkerFS(worker) {
        var _this = this;
        _super.call(this);
        this._callbackConverter = new CallbackArgumentConverter();
        this._isInitialized = false;
        this._isReadOnly = false;
        this._supportLinks = false;
        this._supportProps = false;
        this._outstandingRequests = {};
        this._worker = worker;
        this._worker.addEventListener('message', function (e) {
            var resp = e.data;
            if (isAPIResponse(resp)) {
                var i, args = resp.args, fixedArgs = new Array(args.length);
                for (i = 0; i < fixedArgs.length; i++) {
                    fixedArgs[i] = _this._argRemote2Local(args[i]);
                }
                _this._callbackConverter.toLocalArg(resp.cbId).apply(null, fixedArgs);
            }
        });
    }
    WorkerFS.isAvailable = function () {
        return typeof Worker !== 'undefined';
    };
    WorkerFS.prototype.getName = function () {
        return 'WorkerFS';
    };
    WorkerFS.prototype._argRemote2Local = function (arg) {
        if (arg == null) {
            return arg;
        }
        switch (typeof arg) {
            case 'object':
                if (arg['type'] != null && typeof arg['type'] === 'number') {
                    var specialArg = arg;
                    switch (specialArg.type) {
                        case SpecialArgType.API_ERROR:
                            return apiErrorRemote2Local(specialArg);
                        case SpecialArgType.FD:
                            var fdArg = specialArg;
                            return new WorkerFile(this, fdArg.path, file_flag.FileFlag.getFileFlag(fdArg.flag), node_fs_stats_1["default"].fromBuffer(transferrableObjectToBuffer(fdArg.stat)), fdArg.id, transferrableObjectToBuffer(fdArg.data));
                        case SpecialArgType.STATS:
                            return statsRemote2Local(specialArg);
                        case SpecialArgType.FILEFLAG:
                            return fileFlagRemote2Local(specialArg);
                        case SpecialArgType.BUFFER:
                            return bufferRemote2Local(specialArg);
                        case SpecialArgType.ERROR:
                            return errorRemote2Local(specialArg);
                        default:
                            return arg;
                    }
                }
                else {
                    return arg;
                }
            default:
                return arg;
        }
    };
    WorkerFS.prototype._argLocal2Remote = function (arg) {
        if (arg == null) {
            return arg;
        }
        switch (typeof arg) {
            case "object":
                if (arg instanceof node_fs_stats_1["default"]) {
                    return statsLocal2Remote(arg);
                }
                else if (arg instanceof api_error_1.ApiError) {
                    return apiErrorLocal2Remote(arg);
                }
                else if (arg instanceof WorkerFile) {
                    return arg.toRemoteArg();
                }
                else if (arg instanceof file_flag.FileFlag) {
                    return fileFlagLocal2Remote(arg);
                }
                else if (arg instanceof Buffer) {
                    return bufferLocal2Remote(arg);
                }
                else if (arg instanceof Error) {
                    return errorLocal2Remote(arg);
                }
                else {
                    return "Unknown argument";
                }
            case "function":
                return this._callbackConverter.toRemoteArg(arg);
            default:
                return arg;
        }
    };
    WorkerFS.prototype.initialize = function (cb) {
        var _this = this;
        if (!this._isInitialized) {
            var message = {
                browserfsMessage: true,
                method: 'probe',
                args: [this._argLocal2Remote(new Buffer(0)), this._callbackConverter.toRemoteArg(function (probeResponse) {
                        _this._isInitialized = true;
                        _this._isReadOnly = probeResponse.isReadOnly;
                        _this._supportLinks = probeResponse.supportsLinks;
                        _this._supportProps = probeResponse.supportsProps;
                        cb();
                    })]
            };
            this._worker.postMessage(message);
        }
        else {
            cb();
        }
    };
    WorkerFS.prototype.isReadOnly = function () { return this._isReadOnly; };
    WorkerFS.prototype.supportsSynch = function () { return false; };
    WorkerFS.prototype.supportsLinks = function () { return this._supportLinks; };
    WorkerFS.prototype.supportsProps = function () { return this._supportProps; };
    WorkerFS.prototype._rpc = function (methodName, args) {
        var message = {
            browserfsMessage: true,
            method: methodName,
            args: null
        }, fixedArgs = new Array(args.length), i;
        for (i = 0; i < args.length; i++) {
            fixedArgs[i] = this._argLocal2Remote(args[i]);
        }
        message.args = fixedArgs;
        this._worker.postMessage(message);
    };
    WorkerFS.prototype.rename = function (oldPath, newPath, cb) {
        this._rpc('rename', arguments);
    };
    WorkerFS.prototype.stat = function (p, isLstat, cb) {
        this._rpc('stat', arguments);
    };
    WorkerFS.prototype.open = function (p, flag, mode, cb) {
        this._rpc('open', arguments);
    };
    WorkerFS.prototype.unlink = function (p, cb) {
        this._rpc('unlink', arguments);
    };
    WorkerFS.prototype.rmdir = function (p, cb) {
        this._rpc('rmdir', arguments);
    };
    WorkerFS.prototype.mkdir = function (p, mode, cb) {
        this._rpc('mkdir', arguments);
    };
    WorkerFS.prototype.readdir = function (p, cb) {
        this._rpc('readdir', arguments);
    };
    WorkerFS.prototype.exists = function (p, cb) {
        this._rpc('exists', arguments);
    };
    WorkerFS.prototype.realpath = function (p, cache, cb) {
        this._rpc('realpath', arguments);
    };
    WorkerFS.prototype.truncate = function (p, len, cb) {
        this._rpc('truncate', arguments);
    };
    WorkerFS.prototype.readFile = function (fname, encoding, flag, cb) {
        this._rpc('readFile', arguments);
    };
    WorkerFS.prototype.writeFile = function (fname, data, encoding, flag, mode, cb) {
        this._rpc('writeFile', arguments);
    };
    WorkerFS.prototype.appendFile = function (fname, data, encoding, flag, mode, cb) {
        this._rpc('appendFile', arguments);
    };
    WorkerFS.prototype.chmod = function (p, isLchmod, mode, cb) {
        this._rpc('chmod', arguments);
    };
    WorkerFS.prototype.chown = function (p, isLchown, uid, gid, cb) {
        this._rpc('chown', arguments);
    };
    WorkerFS.prototype.utimes = function (p, atime, mtime, cb) {
        this._rpc('utimes', arguments);
    };
    WorkerFS.prototype.link = function (srcpath, dstpath, cb) {
        this._rpc('link', arguments);
    };
    WorkerFS.prototype.symlink = function (srcpath, dstpath, type, cb) {
        this._rpc('symlink', arguments);
    };
    WorkerFS.prototype.readlink = function (p, cb) {
        this._rpc('readlink', arguments);
    };
    WorkerFS.prototype.syncClose = function (method, fd, cb) {
        this._worker.postMessage({
            browserfsMessage: true,
            method: method,
            args: [fd.toRemoteArg(), this._callbackConverter.toRemoteArg(cb)]
        });
    };
    WorkerFS.attachRemoteListener = function (worker) {
        var fdConverter = new FileDescriptorArgumentConverter();
        function argLocal2Remote(arg, requestArgs, cb) {
            switch (typeof arg) {
                case 'object':
                    if (arg instanceof node_fs_stats_1["default"]) {
                        cb(null, statsLocal2Remote(arg));
                    }
                    else if (arg instanceof api_error_1.ApiError) {
                        cb(null, apiErrorLocal2Remote(arg));
                    }
                    else if (arg instanceof file.BaseFile) {
                        cb(null, fdConverter.toRemoteArg(arg, requestArgs[0], requestArgs[1], cb));
                    }
                    else if (arg instanceof file_flag.FileFlag) {
                        cb(null, fileFlagLocal2Remote(arg));
                    }
                    else if (arg instanceof Buffer) {
                        cb(null, bufferLocal2Remote(arg));
                    }
                    else if (arg instanceof Error) {
                        cb(null, errorLocal2Remote(arg));
                    }
                    else {
                        cb(null, arg);
                    }
                    break;
                default:
                    cb(null, arg);
                    break;
            }
        }
        function argRemote2Local(arg, fixedRequestArgs) {
            if (arg == null) {
                return arg;
            }
            switch (typeof arg) {
                case 'object':
                    if (typeof arg['type'] === 'number') {
                        var specialArg = arg;
                        switch (specialArg.type) {
                            case SpecialArgType.CB:
                                var cbId = arg.id;
                                return function () {
                                    var i, fixedArgs = new Array(arguments.length), message, countdown = arguments.length;
                                    function abortAndSendError(err) {
                                        if (countdown > 0) {
                                            countdown = -1;
                                            message = {
                                                browserfsMessage: true,
                                                cbId: cbId,
                                                args: [apiErrorLocal2Remote(err)]
                                            };
                                            worker.postMessage(message);
                                        }
                                    }
                                    for (i = 0; i < arguments.length; i++) {
                                        (function (i, arg) {
                                            argLocal2Remote(arg, fixedRequestArgs, function (err, fixedArg) {
                                                fixedArgs[i] = fixedArg;
                                                if (err) {
                                                    abortAndSendError(err);
                                                }
                                                else if (--countdown === 0) {
                                                    message = {
                                                        browserfsMessage: true,
                                                        cbId: cbId,
                                                        args: fixedArgs
                                                    };
                                                    worker.postMessage(message);
                                                }
                                            });
                                        })(i, arguments[i]);
                                    }
                                    if (arguments.length === 0) {
                                        message = {
                                            browserfsMessage: true,
                                            cbId: cbId,
                                            args: fixedArgs
                                        };
                                        worker.postMessage(message);
                                    }
                                };
                            case SpecialArgType.API_ERROR:
                                return apiErrorRemote2Local(specialArg);
                            case SpecialArgType.STATS:
                                return statsRemote2Local(specialArg);
                            case SpecialArgType.FILEFLAG:
                                return fileFlagRemote2Local(specialArg);
                            case SpecialArgType.BUFFER:
                                return bufferRemote2Local(specialArg);
                            case SpecialArgType.ERROR:
                                return errorRemote2Local(specialArg);
                            default:
                                return arg;
                        }
                    }
                    else {
                        return arg;
                    }
                default:
                    return arg;
            }
        }
        worker.addEventListener('message', function (e) {
            var request = e.data;
            if (isAPIRequest(request)) {
                var args = request.args, fixedArgs = new Array(args.length), i;
                switch (request.method) {
                    case 'close':
                    case 'sync':
                        (function () {
                            var remoteCb = args[1];
                            fdConverter.applyFdAPIRequest(request, function (err) {
                                var response = {
                                    browserfsMessage: true,
                                    cbId: remoteCb.id,
                                    args: err ? [apiErrorLocal2Remote(err)] : []
                                };
                                worker.postMessage(response);
                            });
                        })();
                        break;
                    case 'probe':
                        (function () {
                            var rootFs = fs.getRootFS(), remoteCb = args[1], probeResponse = {
                                type: SpecialArgType.PROBE,
                                isReadOnly: rootFs.isReadOnly(),
                                supportsLinks: rootFs.supportsLinks(),
                                supportsProps: rootFs.supportsProps()
                            }, response = {
                                browserfsMessage: true,
                                cbId: remoteCb.id,
                                args: [probeResponse]
                            };
                            worker.postMessage(response);
                        })();
                        break;
                    default:
                        for (i = 0; i < args.length; i++) {
                            fixedArgs[i] = argRemote2Local(args[i], fixedArgs);
                        }
                        var rootFS = fs.getRootFS();
                        rootFS[request.method].apply(rootFS, fixedArgs);
                        break;
                }
            }
        });
    };
    return WorkerFS;
}(file_system.BaseFileSystem));
exports.__esModule = true;
exports["default"] = WorkerFS;
//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJmaWxlIjoiV29ya2VyRlMuanMiLCJzb3VyY2VSb290IjoiIiwic291cmNlcyI6WyIuLi8uLi8uLi9zcmMvYmFja2VuZC9Xb3JrZXJGUy50cyJdLCJuYW1lcyI6W10sIm1hcHBpbmdzIjoiOzs7Ozs7QUFBQSxJQUFPLFdBQVcsV0FBVyxxQkFBcUIsQ0FBQyxDQUFDO0FBQ3BELDBCQUF1QixtQkFBbUIsQ0FBQyxDQUFBO0FBQzNDLElBQU8sU0FBUyxXQUFXLG1CQUFtQixDQUFDLENBQUM7QUFDaEQscUJBQXFELGNBQWMsQ0FBQyxDQUFBO0FBQ3BFLElBQU8sSUFBSSxXQUFXLGNBQWMsQ0FBQyxDQUFDO0FBQ3RDLDhCQUF5Qyx1QkFBdUIsQ0FBQyxDQUFBO0FBQ2pFLElBQU8sWUFBWSxXQUFXLHlCQUF5QixDQUFDLENBQUM7QUFDekQsSUFBTyxNQUFNLFdBQVcsZ0JBQWdCLENBQUMsQ0FBQztBQUMxQyxJQUFPLEVBQUUsV0FBVyxpQkFBaUIsQ0FBQyxDQUFDO0FBTXZDLElBQUssY0FpQko7QUFqQkQsV0FBSyxjQUFjO0lBRWpCLCtDQUFFLENBQUE7SUFFRiwrQ0FBRSxDQUFBO0lBRUYsNkRBQVMsQ0FBQTtJQUVULHFEQUFLLENBQUE7SUFFTCxxREFBSyxDQUFBO0lBRUwsMkRBQVEsQ0FBQTtJQUVSLHVEQUFNLENBQUE7SUFFTixxREFBSyxDQUFBO0FBQ1AsQ0FBQyxFQWpCSSxjQUFjLEtBQWQsY0FBYyxRQWlCbEI7QUFxQkQ7SUFBQTtRQUNVLGVBQVUsR0FBK0IsRUFBRSxDQUFDO1FBQzVDLFlBQU8sR0FBVyxDQUFDLENBQUM7SUFnQjlCLENBQUM7SUFkUSwrQ0FBVyxHQUFsQixVQUFtQixFQUFZO1FBQzdCLElBQUksRUFBRSxHQUFHLElBQUksQ0FBQyxPQUFPLEVBQUUsQ0FBQztRQUN4QixJQUFJLENBQUMsVUFBVSxDQUFDLEVBQUUsQ0FBQyxHQUFHLEVBQUUsQ0FBQztRQUN6QixNQUFNLENBQUM7WUFDTCxJQUFJLEVBQUUsY0FBYyxDQUFDLEVBQUU7WUFDdkIsRUFBRSxFQUFF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YSxFQUFFO2dDQUNyQyxhQUFhLEVBQUUsTUFBTSxDQUFDLGFBQWEsRUFBRTs2QkFDdEMsRUFDRCxRQUFRLEdBQWlCO2dDQUN2QixnQkFBZ0IsRUFBRSxJQUFJO2dDQUN0QixJQUFJLEVBQUUsUUFBUSxDQUFDLEVBQUU7Z0NBQ2pCLElBQUksRUFBRSxDQUFDLGFBQWEsQ0FBQzs2QkFDdEIsQ0FBQzs0QkFFSixNQUFNLENBQUMsV0FBVyxDQUFDLFFBQVEsQ0FBQyxDQUFDO3dCQUMvQixDQUFDLENBQUMsRUFBRSxDQUFDO3dCQUNMLEtBQUssQ0FBQztvQkFDUjt3QkFFRSxHQUFHLENBQUMsQ0FBQyxDQUFDLEdBQUcsQ0FBQyxFQUFFLENBQUMsR0FBRyxJQUFJLENBQUMsTUFBTSxFQUFFLENBQUMsRUFBRSxFQUFFLENBQUM7NEJBQ2pDLFNBQVMsQ0FBQyxDQUFDLENBQUMsR0FBRyxlQUFlLENBQUMsSUFBSSxDQUFDLENBQUMsQ0FBQyxFQUFFLFNBQVMsQ0FBQyxDQUFDO3dCQUNyRCxDQUFDO3dCQUNELElBQUksTUFBTSxHQUFHLEVBQUUsQ0FBQyxTQUFTLEVBQUUsQ0FBQzt3QkFDaEIsTUFBTSxDQUFDLE9BQU8sQ0FBQyxNQUFNLENBQUUsQ0FBQyxLQUFLLENBQUMsTUFBTSxFQUFFLFNBQVMsQ0FBQyxDQUFDO3dCQUM3RCxLQUFLLENBQUM7Z0JBQ1YsQ0FBQztZQUNILENBQUM7UUFDSCxDQUFDLENBQUMsQ0FBQztJQUNMLENBQUM7SUFDSCxlQUFDO0FBQUQsQ0FBQyxBQTNYRCxDQUFzQyxXQUFXLENBQUMsY0FBYyxHQTJYL0Q7QUEzWEQ7NkJBMlhDLENBQUEiLCJzb3VyY2VzQ29udGVudCI6WyJpbXBvcnQgZmlsZV9zeXN0ZW0gPSByZXF1aXJlKCcuLi9jb3JlL2ZpbGVfc3lzdGVtJyk7XHJcbmltcG9ydCB7QXBpRXJyb3J9IGZyb20gJy4uL2NvcmUvYXBpX2Vycm9yJztcclxuaW1wb3J0IGZpbGVfZmxhZyA9IHJlcXVpcmUoJy4uL2NvcmUvZmlsZV9mbGFnJyk7XHJcbmltcG9ydCB7YnVmZmVyMkFycmF5QnVmZmVyLCBhcnJheUJ1ZmZlcjJCdWZmZXJ9IGZyb20gJy4uL2NvcmUvdXRpbCc7XHJcbmltcG9ydCBmaWxlID0gcmVxdWlyZSgnLi4vY29yZS9maWxlJyk7XHJcbmltcG9ydCB7ZGVmYXVsdCBhcyBTdGF0cywgRmlsZVR5cGV9IGZyb20gJy4uL2NvcmUvbm9kZV9mc19zdGF0cyc7XHJcbmltcG9ydCBwcmVsb2FkX2ZpbGUgPSByZXF1aXJlKCcuLi9nZW5lcmljL3ByZWxvYWRfZmlsZScpO1xyXG5pbXBvcnQgZ2xvYmFsID0gcmVxdWlyZSgnLi4vY29yZS9nbG9iYWwnKTtcclxuaW1wb3J0IGZzID0gcmVxdWlyZSgnLi4vY29yZS9ub2RlX2ZzJyk7XHJcblxyXG5pbnRlcmZhY2UgSUJyb3dzZXJGU01lc3NhZ2Uge1xyXG4gIGJyb3dzZXJmc01lc3NhZ2U6IGJvb2xlYW47XHJcbn1cclxuXHJcbmVudW0gU3BlY2lhbEFyZ1R5cGUge1xyXG4gIC8vIENhbGxiYWNrXHJcbiAgQ0IsXHJcbiAgLy8gRmlsZSBkZXNjcmlwdG9yXHJcbiAgRkQsXHJcbiAgLy8gQVBJIGVycm9yXHJcbiAgQVBJX0VSUk9SLFxyXG4gIC8vIFN0YXRzIG9iamVjdFxyXG4gIFNUQVRTLFxyXG4gIC8vIEluaXRpYWwgcHJvYmUgZm9yIGZpbGUgc3lzdGVtIGluZm9ybWF0aW9uLlxyXG4gIFBST0JFLFxyXG4gIC8vIEZpbGVGbGFnIG9iamVjdC5cclxuICBGSUxFRkxBRyxcclxuICAvLyBCdWZmZXIgb2JqZWN0LlxyXG4gIEJVRkZFUixcclxuICAvLyBHZW5lcmljIEVycm9yIG9iamVjdC5cclxuICBFUlJPUlxyXG59XHJcblxyXG5pbnRlcmZhY2UgSVNwZWNpYWxBcmd1bWVudCB7XHJcbiAgdHlwZTogU3BlY2lhbEFyZ1R5cGU7XHJcbn1cclxuXHJcbmludGVyZmFjZSBJUHJvYmVSZXNwb25zZSBleHRlbmRzIElTcGVjaWFsQXJndW1lbnQge1xyXG4gIGlzUmVhZE9ubHk6IGJvb2xlYW47XHJcbiAgc3VwcG9ydHNMaW5rczogYm9vbGVhbjtcclxuICBzdXBwb3J0c1Byb3BzOiBib29sZWFuO1xyXG59XHJcblxyXG5pbnRlcmZhY2UgSUNhbGxiYWNrQXJndW1lbnQgZXh0ZW5kcyBJU3BlY2lhbEFyZ3VtZW50IHtcclxuICAvLyBUaGUgY2FsbGJhY2sgSUQuXHJcbiAgaWQ6IG51bWJlcjtcclxufVxyXG5cclxuLyoqXHJcbiAqIENvbnZlcnRzIGNhbGxiYWNrIGFyZ3VtZW50cyBpbnRvIElDYWxsYmFja0FyZ3VtZW50IG9iamVjdHMsIGFuZCBiYWNrXHJcbiAqIGFnYWluLlxyXG4gKi9cclxuY2xhc3MgQ2FsbGJhY2tBcmd1bWVudENvbnZlcnRlciB7XHJcbiAgcHJpdmF0ZSBfY2FsbGJhY2tzOiB7IFtpZDogbnVtYmVyXTogRnVuY3Rpb24gfSA9IHt9O1xyXG4gIHByaXZhdGUgX25leHRJZDogbnVtYmVyID0gMDtcclxuXHJcbiAgcHVibGljIHRvUmVtb3RlQXJnKGNiOiBGdW5jdGlvbik6IElDYWxsYmFja0FyZ3VtZW50IHtcclxuICAgIHZhciBpZCA9IHRoaXMuX25leHRJZCsrO1xyXG4gICAgdGhpcy5fY2FsbGJhY2tzW2lkXSA9IGNiO1xyXG4gICAgcmV0dXJuIHtcclxuICAgICAgdHlwZTogU3BlY2lhbEFyZ1R5cGUuQ0IsXHJcbiAgICAgIGlkOiBpZFxyXG4gICAgfTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyB0b0xvY2FsQXJnKGlkOiBudW1iZXIpOiBGdW5jdGlvbiB7XHJcbiAgICB2YXIgY2IgPSB0aGlzLl9jYWxsYmFja3NbaWRdO1xyXG4gICAgZGVsZXRlIHRoaXMuX2NhbGxiYWNrc1tpZF07XHJcbiAgICByZXR1cm4gY2I7XHJcbiAgfVxyXG59XHJcblxyXG5pbnRlcmZhY2UgSUZpbGVEZXNjcmlwdG9yQXJndW1lbnQgZXh0ZW5kcyBJU3BlY2lhbEFyZ3VtZW50IHtcclxuICAvLyBUaGUgZmlsZSBkZXNjcmlwdG9yJ3MgaWQgb24gdGhlIHJlbW90ZSBzaWRlLlxyXG4gIGlkOiBudW1iZXI7XHJcbiAgLy8gVGhlIGVudGlyZSBmaWxlJ3MgZGF0YSwgYXMgYW4gYXJyYXkgYnVmZmVyLlxyXG4gIGRhdGE6IEFycmF5QnVmZmVyO1xyXG4gIC8vIFRoZSBmaWxlJ3Mgc3RhdCBvYmplY3QsIGFzIGFuIGFycmF5IGJ1ZmZlci5cclxuICBzdGF0OiBBcnJheUJ1ZmZlcjtcclxuICAvLyBUaGUgcGF0aCB0byB0aGUgZmlsZS5cclxuICBwYXRoOiBzdHJpbmc7XHJcbiAgLy8gVGhlIGZsYWcgb2YgdGhlIG9wZW4gZmlsZSBkZXNjcmlwdG9yLlxyXG4gIGZsYWc6IHN0cmluZztcclxufVxyXG5cclxuY2xhc3MgRmlsZURlc2NyaXB0b3JBcmd1bWVudENvbnZlcnRlciB7XHJcbiAgcHJpdmF0ZSBfZmlsZURlc2NyaXB0b3JzOiB7IFtpZDogbnVtYmVyXTogZmlsZS5GaWxlIH0gPSB7fTtcclxuICBwcml2YXRlIF9uZXh0SWQ6IG51bWJlciA9IDA7XHJcblxyXG4gIHB1YmxpYyB0b1JlbW90ZUFyZyhmZDogZmlsZS5GaWxlLCBwOiBzdHJpbmcsIGZsYWc6IGZpbGVfZmxhZy5GaWxlRmxhZywgY2I6IChlcnI6IEFwaUVycm9yLCBhcmc/OiBJRmlsZURlc2NyaXB0b3JBcmd1bWVudCkgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgdmFyIGlkID0gdGhpcy5fbmV4dElkKyssXHJcbiAgICAgIGRhdGE6IEFycmF5QnVmZmVyLFxyXG4gICAgICBzdGF0OiBBcnJheUJ1ZmZlcixcclxuICAgICAgYXJnc0xlZnQ6IG51bWJlciA9IDI7XHJcbiAgICB0aGlzLl9maWxlRGVzY3JpcHRvcnNbaWRdID0gZmQ7XHJcblxyXG4gICAgLy8gRXh0cmFjdCBuZWVkZWQgaW5mb3JtYXRpb24gYXN5bmNocm9ub3VzbHkuXHJcbiAgICBmZC5zdGF0KChlcnIsIHN0YXRzKSA9PiB7XHJcbiAgICAgIGlmIChlcnIpIHtcclxuICAgICAgICBjYihlcnIpO1xyXG4gICAgICB9IGVsc2Uge1xyXG4gICAgICAgIHN0YXQgPSBidWZmZXJUb1RyYW5zZmVycmFibGVPYmplY3Qoc3RhdHMudG9CdWZmZXIoKSk7XHJcbiAgICAgICAgLy8gSWYgaXQncyBhIHJlYWRhYmxlIGZsYWcsIHdlIG5lZWQgdG8gZ3JhYiBjb250ZW50cy5cclxuICAgICAgICBpZiAoZmxhZy5pc1JlYWRhYmxlKCkpIHtcclxuICAgICAgICAgIGZkLnJlYWQobmV3IEJ1ZmZlcihzdGF0cy5zaXplKSwgMCwgc3RhdHMuc2l6ZSwgMCwgKGVyciwgYnl0ZXNSZWFkLCBidWZmKSA9PiB7XHJcbiAgICAgICAgICAgIGlmIChlcnIpIHtcclxuICAgICAgICAgICAgICBjYihlcnIpO1xyXG4gICAgICAgICAgICB9IGVsc2Uge1xyXG4gICAgICAgICAgICAgIGRhdGEgPSBidWZmZXJUb1RyYW5zZmVycmFibGVPYmplY3QoYnVmZik7XHJcbiAgICAgICAgICAgICAgY2IobnVsbCwge1xyXG4gICAgICAgICAgICAgICAgdHlwZTogU3BlY2lhbEFyZ1R5cGUuRkQsXHJcbiAgICAgICAgICAgICAgICBpZDogaWQsXHJcbiAgICAgICAgICAgICAgICBkYXRhOiBkYXRhLFxyXG4gICAgICAgICAgICAgICAgc3RhdDogc3RhdCxcclxuICAgICAgICAgICAgICAgIHBhdGg6IHAsXHJcbiAgICAgICAgICAgICAgICBmbGFnOiBmbGFnLmdldEZsYWdTdHJpbmcoKVxyXG4gICAgICAgICAgICAgIH0pO1xyXG4gICAgICAgICAgICB9XHJcbiAgICAgICAgICB9KTtcclxuICAgICAgICB9IGVsc2Uge1xyXG4gICAgICAgICAgLy8gRmlsZSBpcyBub3QgcmVhZGFibGUsIHdoaWNoIG1lYW5zIHdyaXRpbmcgdG8gaXQgd2lsbCBhcHBlbmQgb3JcclxuICAgICAgICAgIC8vIHRydW5jYXRlL3JlcGxhY2UgZXhpc3RpbmcgY29udGVudHMuIFJldHVybiBhbiBlbXB0eSBhcnJheWJ1ZmZlci5cclxuICAgICAgICAgIGNiKG51bGwsIHtcclxuICAgICAgICAgICAgdHlwZTogU3BlY2lhbEFyZ1R5cGUuRkQsXHJcbiAgICAgICAgICAgIGlkOiBpZCxcclxuICAgICAgICAgICAgZGF0YTogbmV3IEFycmF5QnVmZmVyKDApLFxyXG4gICAgICAgICAgICBzdGF0OiBzdGF0LFxyXG4gICAgICAgICAgICBwYXRoOiBwLFxyXG4gICAgICAgICAgICBmbGFnOiBmbGFnLmdldEZsYWdTdHJpbmcoKVxyXG4gICAgICAgICAgfSk7XHJcbiAgICAgICAgfVxyXG4gICAgICB9XHJcbiAgICB9KTtcclxuICB9XHJcblxyXG4gIHByaXZhdGUgX2FwcGx5RmRDaGFuZ2VzKHJlbW90ZUZkOiBJRmlsZURlc2NyaXB0b3JBcmd1bWVudCwgY2I6IChlcnI6IEFwaUVycm9yLCBmZD86IGZpbGUuRmlsZSkgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgdmFyIGZkID0gdGhpcy5fZmlsZURlc2NyaXB0b3JzW3JlbW90ZUZkLmlkXSxcclxuICAgICAgZGF0YSA9IHRyYW5zZmVycmFibGVPYmplY3RUb0J1ZmZlcihyZW1vdGVGZC5kYXRhKSxcclxuICAgICAgcmVtb3RlU3RhdHMgPSBTdGF0cy5mcm9tQnVmZmVyKHRyYW5zZmVycmFibGVPYmplY3RUb0J1ZmZlcihyZW1vdGVGZC5zdGF0KSk7XHJcblxyXG4gICAgLy8gV3JpdGUgZGF0YSBpZiB0aGUgZmlsZSBpcyB3cml0YWJsZS5cclxuICAgIHZhciBmbGFnID0gZmlsZV9mbGFnLkZpbGVGbGFnLmdldEZpbGVGbGFnKHJlbW90ZUZkLmZsYWcpO1xyXG4gICAgaWYgKGZsYWcuaXNXcml0ZWFibGUoKSkge1xyXG4gICAgICAvLyBBcHBlbmRhYmxlOiBXcml0ZSB0byBlbmQgb2YgZmlsZS5cclxuICAgICAgLy8gV3JpdGVhYmxlOiBSZXBsYWNlIGVudGlyZSBjb250ZW50cyBvZiBmaWxlLlxyXG4gICAgICBmZC53cml0ZShkYXRhLCAwLCBkYXRhLmxlbmd0aCwgZmxhZy5pc0FwcGVuZGFibGUoKSA/IGZkLmdldFBvcygpIDogMCwgKGUpID0+IHtcclxuICAgICAgICBpZiAoZSkge1xyXG4gICAgICAgICAgY2IoZSk7XHJcbiAgICAgICAgfSBlbHNlIHtcclxuICAgICAgICAgIGZ1bmN0aW9uIGFwcGx5U3RhdENoYW5nZXMoKSB7XHJcbiAgICAgICAgICAgIC8vIENoZWNrIGlmIG1vZGUgY2hhbmdlZC5cclxuICAgICAgICAgICAgZmQuc3RhdCgoZSwgc3RhdHM/KSA9PiB7XHJcbiAgICAgICAgICAgICAgaWYgKGUpIHtcclxuICAgICAgICAgICAgICAgIGNiKGUpO1xyXG4gICAgICAgICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICAgICAgICBpZiAoc3RhdHMubW9kZSAhPT0gcmVtb3RlU3RhdHMubW9kZSkge1xyXG4gICAgICAgICAgICAgICAgICBmZC5jaG1vZChyZW1vdGVTdGF0cy5tb2RlLCAoZTogYW55KSA9PiB7XHJcbiAgICAgICAgICAgICAgICAgICAgY2IoZSwgZmQpO1xyXG4gICAgICAgICAgICAgICAgICB9KTtcclxuICAgICAgICAgICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICAgICAgICAgIGNiKGUsIGZkKTtcclxuICAgICAgICAgICAgICAgIH1cclxuICAgICAgICAgICAgICB9XHJcbiAgICAgICAgICAgIH0pO1xyXG4gICAgICAgICAgfVxyXG5cclxuICAgICAgICAgIC8vIElmIHdyaXRlYWJsZSAmIG5vdCBhcHBlbmRhYmxlLCB3ZSBuZWVkIHRvIGVuc3VyZSBmaWxlIGNvbnRlbnRzIGFyZVxyXG4gICAgICAgICAgLy8gaWRlbnRpY2FsIHRvIHRob3NlIGZyb20gdGhlIHJlbW90ZSBGRC4gVGh1cywgd2UgdHJ1bmNhdGUgdG8gdGhlXHJcbiAgICAgICAgICAvLyBsZW5ndGggb2YgdGhlIHJlbW90ZSBmaWxlLlxyXG4gICAgICAgICAgaWYgKCFmbGFnLmlzQXBwZW5kYWJsZSgpKSB7XHJcbiAgICAgICAgICAgIGZkLnRydW5jYXRlKGRhdGEubGVuZ3RoLCAoKSA9PiB7XHJcbiAgICAgICAgICAgICAgYXBwbHlTdGF0Q2hhbmdlcygpO1xyXG4gICAgICAgICAgICB9KVxyXG4gICAgICAgICAgfSBlbHNlIHtcclxuICAgICAgICAgICAgYXBwbHlTdGF0Q2hhbmdlcygpO1xyXG4gICAgICAgICAgfVxyXG4gICAgICAgIH1cclxuICAgICAgfSk7XHJcbiAgICB9IGVsc2Uge1xyXG4gICAgICBjYihudWxsLCBmZCk7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgYXBwbHlGZEFQSVJlcXVlc3QocmVxdWVzdDogSUFQSVJlcXVlc3QsIGNiOiAoZXJyPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHZhciBmZEFyZyA9IDxJRmlsZURlc2NyaXB0b3JBcmd1bWVudD4gcmVxdWVzdC5hcmdzWzBdO1xyXG4gICAgdGhpcy5fYXBwbHlGZENoYW5nZXMoZmRBcmcsIChlcnIsIGZkPykgPT4ge1xyXG4gICAgICBpZiAoZXJyKSB7XHJcbiAgICAgICAgY2IoZXJyKTtcclxuICAgICAgfSBlbHNlIHtcclxuICAgICAgICAvLyBBcHBseSBtZXRob2Qgb24gbm93LWNoYW5nZWQgZmlsZSBkZXNjcmlwdG9yLlxyXG4gICAgICAgICg8YW55PiBmZClbcmVxdWVzdC5tZXRob2RdKChlPzogQXBpRXJyb3IpID0+IHtcclxuICAgICAgICAgIGlmIChyZXF1ZXN0Lm1ldGhvZCA9PT0gJ2Nsb3NlJykge1xyXG4gICAgICAgICAgICBkZWxldGUgdGhpcy5fZmlsZURlc2NyaXB0b3JzW2ZkQXJnLmlkXTtcclxuICAgICAgICAgIH1cclxuICAgICAgICAgIGNiKGUpO1xyXG4gICAgICAgIH0pO1xyXG4gICAgICB9XHJcbiAgICB9KTtcclxuICB9XHJcbn1cclxuXHJcbmludGVyZmFjZSBJQVBJRXJyb3JBcmd1bWVudCBleHRlbmRzIElTcGVjaWFsQXJndW1lbnQge1xyXG4gIC8vIFRoZSBlcnJvciBvYmplY3QsIGFzIGFuIGFycmF5IGJ1ZmZlci5cclxuICBlcnJvckRhdGE6IEFycmF5QnVmZmVyO1xyXG59XHJcblxyXG5mdW5jdGlvbiBhcGlFcnJvckxvY2FsMlJlbW90ZShlOiBBcGlFcnJvcik6IElBUElFcnJvckFyZ3VtZW50IHtcclxuICByZXR1cm4ge1xyXG4gICAgdHlwZTogU3BlY2lhbEFyZ1R5cGUuQVBJX0VSUk9SLFxyXG4gICAgZXJyb3JEYXRhOiBidWZmZXJUb1RyYW5zZmVycmFibGVPYmplY3QoZS53cml0ZVRvQnVmZmVyKCkpXHJcbiAgfTtcclxufVxyXG5cclxuZnVuY3Rpb24gYXBpRXJyb3JSZW1vdGUyTG9jYWwoZTogSUFQSUVycm9yQXJndW1lbnQpOiBBcGlFcnJvciB7XHJcbiAgcmV0dXJuIEFwaUVycm9yLmZyb21CdWZmZXIodHJhbnNmZXJyYWJsZU9iamVjdFRvQnVmZmVyKGUuZXJyb3JEYXRhKSk7XHJcbn1cclxuXHJcbmludGVyZmFjZSBJRXJyb3JBcmd1bWVudCBleHRlbmRzIElTcGVjaWFsQXJndW1lbnQge1xyXG4gIC8vIFRoZSBuYW1lIG9mIHRoZSBlcnJvciAoZS5nLiAnVHlwZUVycm9yJykuXHJcbiAgbmFtZTogc3RyaW5nO1xyXG4gIC8vIFRoZSBtZXNzYWdlIGFzc29jaWF0ZWQgd2l0aCB0aGUgZXJyb3IuXHJcbiAgbWVzc2FnZTogc3RyaW5nO1xyXG4gIC8vIFRoZSBzdGFjayBhc3NvY2lhdGVkIHdpdGggdGhlIGVycm9yLlxyXG4gIHN0YWNrOiBzdHJpbmc7XHJcbn1cclxuXHJcbmZ1bmN0aW9uIGVycm9yTG9jYWwyUmVtb3RlKGU6IEVycm9yKTogSUVycm9yQXJndW1lbnQge1xyXG4gIHJldHVybiB7XHJcbiAgICB0eXBlOiBTcGVjaWFsQXJnVHlwZS5FUlJPUixcclxuICAgIG5hbWU6IGUubmFtZSxcclxuICAgIG1lc3NhZ2U6IGUubWVzc2FnZSxcclxuICAgIHN0YWNrOiBlLnN0YWNrXHJcbiAgfTtcclxufVxyXG5cclxuZnVuY3Rpb24gZXJyb3JSZW1vdGUyTG9jYWwoZTogSUVycm9yQXJndW1lbnQpOiBFcnJvciB7XHJcbiAgdmFyIGNuc3RyOiB7XHJcbiAgICBuZXcgKG1zZzogc3RyaW5nKTogRXJyb3I7XHJcbiAgfSA9IGdsb2JhbFtlLm5hbWVdO1xyXG4gIGlmICh0eXBlb2YoY25zdHIpICE9PSAnZnVuY3Rpb24nKSB7XHJcbiAgICBjbnN0ciA9IEVycm9yO1xyXG4gIH1cclxuICB2YXIgZXJyID0gbmV3IGNuc3RyKGUubWVzc2FnZSk7XHJcbiAgZXJyLnN0YWNrID0gZS5zdGFjaztcclxuICByZXR1cm4gZXJyO1xyXG59XHJcblxyXG5pbnRlcmZhY2UgSVN0YXRzQXJndW1lbnQgZXh0ZW5kcyBJU3BlY2lhbEFyZ3VtZW50IHtcclxuICAvLyBUaGUgc3RhdHMgb2JqZWN0IGFzIGFuIGFycmF5IGJ1ZmZlci5cclxuICBzdGF0c0RhdGE6IEFycmF5QnVmZmVyO1xyXG59XHJcblxyXG5mdW5jdGlvbiBzdGF0c0xvY2FsMlJlbW90ZShzdGF0czogU3RhdHMpOiBJU3RhdHNBcmd1bWVudCB7XHJcbiAgcmV0dXJuIHtcclxuICAgIHR5cGU6IFNwZWNpYWxBcmdUeXBlLlNUQVRTLFxyXG4gICAgc3RhdHNEYXRhOiBidWZmZXJUb1RyYW5zZmVycmFibGVPYmplY3Qoc3RhdHMudG9CdWZmZXIoKSlcclxuICB9O1xyXG59XHJcblxyXG5mdW5jdGlvbiBzdGF0c1JlbW90ZTJMb2NhbChzdGF0czogSVN0YXRzQXJndW1lbnQpOiBTdGF0cyB7XHJcbiAgcmV0dXJuIFN0YXRzLmZyb21CdWZmZXIodHJhbnNmZXJyYWJsZU9iamVjdFRvQnVmZmVyKHN0YXRzLnN0YXRzRGF0YSkpO1xyXG59XHJcblxyXG5pbnRlcmZhY2UgSUZpbGVGbGFnQXJndW1lbnQgZXh0ZW5kcyBJU3BlY2lhbEFyZ3VtZW50IHtcclxuICBmbGFnU3RyOiBzdHJpbmc7XHJcbn1cclxuXHJcbmZ1bmN0aW9uIGZpbGVGbGFnTG9jYWwyUmVtb3RlKGZsYWc6IGZpbGVfZmxhZy5GaWxlRmxhZyk6IElGaWxlRmxhZ0FyZ3VtZW50IHtcclxuICByZXR1cm4ge1xyXG4gICAgdHlwZTogU3BlY2lhbEFyZ1R5cGUuRklMRUZMQUcsXHJcbiAgICBmbGFnU3RyOiBmbGFnLmdldEZsYWdTdHJpbmcoKVxyXG4gIH07XHJcbn1cclxuXHJcbmZ1bmN0aW9uIGZpbGVGbGFnUmVtb3RlMkxvY2FsKHJlbW90ZUZsYWc6IElGaWxlRmxhZ0FyZ3VtZW50KTogZmlsZV9mbGFnLkZpbGVGbGFnIHtcclxuICByZXR1cm4gZmlsZV9mbGFnLkZpbGVGbGFnLmdldEZpbGVGbGFnKHJlbW90ZUZsYWcuZmxhZ1N0cik7XHJcbn1cclxuXHJcbmludGVyZmFjZSBJQnVmZmVyQXJndW1lbnQgZXh0ZW5kcyBJU3BlY2lhbEFyZ3VtZW50IHtcclxuICBkYXRhOiBBcnJheUJ1ZmZlcjtcclxufVxyXG5cclxuZnVuY3Rpb24gYnVmZmVyVG9UcmFuc2ZlcnJhYmxlT2JqZWN0KGJ1ZmY6IE5vZGVCdWZmZXIpOiBBcnJheUJ1ZmZlciB7XHJcbiAgcmV0dXJuIGJ1ZmZlcjJBcnJheUJ1ZmZlcihidWZmKTtcclxufVxyXG5cclxuZnVuY3Rpb24gdHJhbnNmZXJyYWJsZU9iamVjdFRvQnVmZmVyKGJ1ZmY6IEFycmF5QnVmZmVyKTogQnVmZmVyIHtcclxuICByZXR1cm4gYXJyYXlCdWZmZXIyQnVmZmVyKGJ1ZmYpO1xyXG59XHJcblxyXG5mdW5jdGlvbiBidWZmZXJMb2NhbDJSZW1vdGUoYnVmZjogQnVmZmVyKTogSUJ1ZmZlckFyZ3VtZW50IHtcclxuICByZXR1cm4ge1xyXG4gICAgdHlwZTogU3BlY2lhbEFyZ1R5cGUuQlVGRkVSLFxyXG4gICAgZGF0YTogYnVmZmVyVG9UcmFuc2ZlcnJhYmxlT2JqZWN0KGJ1ZmYpXHJcbiAgfTtcclxufVxyXG5cclxuZnVuY3Rpb24gYnVmZmVyUmVtb3RlMkxvY2FsKGJ1ZmZBcmc6IElCdWZmZXJBcmd1bWVudCk6IEJ1ZmZlciB7XHJcbiAgcmV0dXJuIHRyYW5zZmVycmFibGVPYmplY3RUb0J1ZmZlcihidWZmQXJnLmRhdGEpO1xyXG59XHJcblxyXG5pbnRlcmZhY2UgSUFQSVJlcXVlc3QgZXh0ZW5kcyBJQnJvd3NlckZTTWVzc2FnZSB7XHJcbiAgbWV0aG9kOiBzdHJpbmc7XHJcbiAgYXJnczogQXJyYXk8bnVtYmVyIHwgc3RyaW5nIHwgSVNwZWNpYWxBcmd1bWVudD47XHJcbn1cclxuXHJcbmZ1bmN0aW9uIGlzQVBJUmVxdWVzdChkYXRhOiBhbnkpOiBkYXRhIGlzIElBUElSZXF1ZXN0IHtcclxuICByZXR1cm4gZGF0YSAhPSBudWxsICYmIHR5cGVvZiBkYXRhID09PSAnb2JqZWN0JyAmJiBkYXRhLmhhc093blByb3BlcnR5KCdicm93c2VyZnNNZXNzYWdlJykgJiYgZGF0YVsnYnJvd3NlcmZzTWVzc2FnZSddO1xyXG59XHJcblxyXG5pbnRlcmZhY2UgSUFQSVJlc3BvbnNlIGV4dGVuZHMgSUJyb3dzZXJGU01lc3NhZ2Uge1xyXG4gIGNiSWQ6IG51bWJlcjtcclxuICBhcmdzOiBBcnJheTxudW1iZXIgfCBzdHJpbmcgfCBJU3BlY2lhbEFyZ3VtZW50PjtcclxufVxyXG5cclxuZnVuY3Rpb24gaXNBUElSZXNwb25zZShkYXRhOiBhbnkpOiBkYXRhIGlzIElBUElSZXNwb25zZSB7XHJcbiAgcmV0dXJuIGRhdGEgIT0gbnVsbCAmJiB0eXBlb2YgZGF0YSA9PT0gJ29iamVjdCcgJiYgZGF0YS5oYXNPd25Qcm9wZXJ0eSgnYnJvd3NlcmZzTWVzc2FnZScpICYmIGRhdGFbJ2Jyb3dzZXJmc01lc3NhZ2UnXTtcclxufVxyXG5cclxuLyoqXHJcbiAqIFJlcHJlc2VudHMgYSByZW1vdGUgZmlsZSBpbiBhIGRpZmZlcmVudCB3b3JrZXIvdGhyZWFkLlxyXG4gKi9cclxuY2xhc3MgV29ya2VyRmlsZSBleHRlbmRzIHByZWxvYWRfZmlsZS5QcmVsb2FkRmlsZTxXb3JrZXJGUz4ge1xyXG4gIHByaXZhdGUgX3JlbW90ZUZkSWQ6IG51bWJlcjtcclxuXHJcbiAgY29uc3RydWN0b3IoX2ZzOiBXb3JrZXJGUywgX3BhdGg6IHN0cmluZywgX2ZsYWc6IGZpbGVfZmxhZy5GaWxlRmxhZywgX3N0YXQ6IFN0YXRzLCByZW1vdGVGZElkOiBudW1iZXIsIGNvbnRlbnRzPzogTm9kZUJ1ZmZlcikge1xyXG4gICAgc3VwZXIoX2ZzLCBfcGF0aCwgX2ZsYWcsIF9zdGF0LCBjb250ZW50cyk7XHJcbiAgICB0aGlzLl9yZW1vdGVGZElkID0gcmVtb3RlRmRJZDtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBnZXRSZW1vdGVGZElkKCkge1xyXG4gICAgcmV0dXJuIHRoaXMuX3JlbW90ZUZkSWQ7XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgdG9SZW1vdGVBcmcoKTogSUZpbGVEZXNjcmlwdG9yQXJndW1lbnQge1xyXG4gICAgcmV0dXJuIDxJRmlsZURlc2NyaXB0b3JBcmd1bWVudD4ge1xyXG4gICAgICB0eXBlOiBTcGVjaWFsQXJnVHlwZS5GRCxcclxuICAgICAgaWQ6IHRoaXMuX3JlbW90ZUZkSWQsXHJcbiAgICAgIGRhdGE6IGJ1ZmZlclRvVHJhbnNmZXJyYWJsZU9iamVjdCh0aGlzLmdldEJ1ZmZlcigpKSxcclxuICAgICAgc3RhdDogYnVmZmVyVG9UcmFuc2ZlcnJhYmxlT2JqZWN0KHRoaXMuZ2V0U3RhdHMoKS50b0J1ZmZlcigpKSxcclxuICAgICAgcGF0aDogdGhpcy5nZXRQYXRoKCksXHJcbiAgICAgIGZsYWc6IHRoaXMuZ2V0RmxhZygpLmdldEZsYWdTdHJpbmcoKVxyXG4gICAgfTtcclxuICB9XHJcblxyXG4gIHByaXZhdGUgX3N5bmNDbG9zZSh0eXBlOiBzdHJpbmcsIGNiOiAoZT86IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICBpZiAodGhpcy5pc0RpcnR5KCkpIHtcclxuICAgICAgKDxXb3JrZXJGUz4gdGhpcy5fZnMpLnN5bmNDbG9zZSh0eXBlLCB0aGlzLCAoZT86IEFwaUVycm9yKSA9PiB7XHJcbiAgICAgICAgaWYgKCFlKSB7XHJcbiAgICAgICAgICB0aGlzLnJlc2V0RGlydHkoKTtcclxuICAgICAgICB9XHJcbiAgICAgICAgY2IoZSk7XHJcbiAgICAgIH0pO1xyXG4gICAgfSBlbHNlIHtcclxuICAgICAgY2IoKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIHB1YmxpYyBzeW5jKGNiOiAoZT86IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICB0aGlzLl9zeW5jQ2xvc2UoJ3N5bmMnLCBjYik7XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgY2xvc2UoY2I6IChlPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHRoaXMuX3N5bmNDbG9zZSgnY2xvc2UnLCBjYik7XHJcbiAgfVxyXG59XHJcblxyXG4vKipcclxuICogV29ya2VyRlMgbGV0cyB5b3UgYWNjZXNzIGEgQnJvd3NlckZTIGluc3RhbmNlIHRoYXQgaXMgcnVubmluZyBpbiBhIGRpZmZlcmVudFxyXG4gKiBKYXZhU2NyaXB0IGNvbnRleHQgKGUuZy4gYWNjZXNzIEJyb3dzZXJGUyBpbiBvbmUgb2YgeW91ciBXZWJXb3JrZXJzLCBvclxyXG4gKiBhY2Nlc3MgQnJvd3NlckZTIHJ1bm5pbmcgb24gdGhlIG1haW4gcGFnZSBmcm9tIGEgV2ViV29ya2VyKS5cclxuICpcclxuICogRm9yIGV4YW1wbGUsIHRvIGhhdmUgYSBXZWJXb3JrZXIgYWNjZXNzIGZpbGVzIGluIHRoZSBtYWluIGJyb3dzZXIgdGhyZWFkLFxyXG4gKiBkbyB0aGUgZm9sbG93aW5nOlxyXG4gKlxyXG4gKiBNQUlOIEJST1dTRVIgVEhSRUFEOlxyXG4gKiBgYGBcclxuICogICAvLyBMaXN0ZW4gZm9yIHJlbW90ZSBmaWxlIHN5c3RlbSByZXF1ZXN0cy5cclxuICogICBCcm93c2VyRlMuRmlsZVN5c3RlbS5Xb3JrZXJGUy5hdHRhY2hSZW1vdGVMaXN0ZW5lcih3ZWJXb3JrZXJPYmplY3QpO1xyXG4gKiBgYFxyXG4gKlxyXG4gKiBXRUJXT1JLRVIgVEhSRUFEOlxyXG4gKiBgYGBcclxuICogICAvLyBTZXQgdGhlIHJlbW90ZSBmaWxlIHN5c3RlbSBhcyB0aGUgcm9vdCBmaWxlIHN5c3RlbS5cclxuICogICBCcm93c2VyRlMuaW5pdGlhbGl6ZShuZXcgQnJvd3NlckZTLkZpbGVTeXN0ZW0uV29ya2VyRlMoc2VsZikpO1xyXG4gKiBgYGBcclxuICpcclxuICogTm90ZSB0aGF0IHN5bmNocm9ub3VzIG9wZXJhdGlvbnMgYXJlIG5vdCBwZXJtaXR0ZWQgb24gdGhlIFdvcmtlckZTLCByZWdhcmRsZXNzXHJcbiAqIG9mIHRoZSBjb25maWd1cmF0aW9uIG9wdGlvbiBvZiB0aGUgcmVtb3RlIEZTLlxyXG4gKi9cclxuZXhwb3J0IGRlZmF1bHQgY2xhc3MgV29ya2VyRlMgZXh0ZW5kcyBmaWxlX3N5c3RlbS5CYXNlRmlsZVN5c3RlbSBpbXBsZW1lbnRzIGZpbGVfc3lzdGVtLkZpbGVTeXN0ZW0ge1xyXG4gIHByaXZhdGUgX3dvcmtlcjogV29ya2VyO1xyXG4gIHByaXZhdGUgX2NhbGxiYWNrQ29udmVydGVyID0gbmV3IENhbGxiYWNrQXJndW1lbnRDb252ZXJ0ZXIoKTtcclxuXHJcbiAgcHJpdmF0ZSBfaXNJbml0aWFsaXplZDogYm9vbGVhbiA9IGZhbHNlO1xyXG4gIHByaXZhdGUgX2lzUmVhZE9ubHk6IGJvb2xlYW4gPSBmYWxzZTtcclxuICBwcml2YXRlIF9zdXBwb3J0TGlua3M6IGJvb2xlYW4gPSBmYWxzZTtcclxuICBwcml2YXRlIF9zdXBwb3J0UHJvcHM6IGJvb2xlYW4gPSBmYWxzZTtcclxuXHJcbiAgLyoqXHJcbiAgICogU3RvcmVzIG91dHN0YW5kaW5nIEFQSSByZXF1ZXN0cyB0byB0aGUgcmVtb3RlIEJyb3dzZXJGUyBpbnN0YW5jZS5cclxuICAgKi9cclxuICBwcml2YXRlIF9vdXRzdGFuZGluZ1JlcXVlc3RzOiB7IFtpZDogbnVtYmVyXTogKCkgPT4gdm9pZCB9ID0ge307XHJcblxyXG4gIC8qKlxyXG4gICAqIENvbnN0cnVjdHMgYSBuZXcgV29ya2VyRlMgaW5zdGFuY2UgdGhhdCBjb25uZWN0cyB3aXRoIEJyb3dzZXJGUyBydW5uaW5nIG9uXHJcbiAgICogdGhlIHNwZWNpZmllZCB3b3JrZXIuXHJcbiAgICovXHJcbiAgY29uc3RydWN0b3Iod29ya2VyOiBXb3JrZXIpIHtcclxuICAgIHN1cGVyKCk7XHJcbiAgICB0aGlzLl93b3JrZXIgPSB3b3JrZXI7XHJcbiAgICB0aGlzLl93b3JrZXIuYWRkRXZlbnRMaXN0ZW5lcignbWVzc2FnZScsKGU6IE1lc3NhZ2VFdmVudCkgPT4ge1xyXG4gICAgICB2YXIgcmVzcDogT2JqZWN0ID0gZS5kYXRhO1xyXG4gICAgICBpZiAoaXNBUElSZXNwb25zZShyZXNwKSkge1xyXG4gICAgICAgIHZhciBpOiBudW1iZXIsIGFyZ3MgPSByZXNwLmFyZ3MsIGZpeGVkQXJncyA9IG5ldyBBcnJheShhcmdzLmxlbmd0aCk7XHJcbiAgICAgICAgLy8gRGlzcGF0Y2ggZXZlbnQgdG8gY29ycmVjdCBpZC5cclxuICAgICAgICBmb3IgKGkgPSAwOyBpIDwgZml4ZWRBcmdzLmxlbmd0aDsgaSsrKSB7XHJcbiAgICAgICAgICBmaXhlZEFyZ3NbaV0gPSB0aGlzLl9hcmdSZW1vdGUyTG9jYWwoYXJnc1tpXSk7XHJcbiAgICAgICAgfVxyXG4gICAgICAgIHRoaXMuX2NhbGxiYWNrQ29udmVydGVyLnRvTG9jYWxBcmcocmVzcC5jYklkKS5hcHBseShudWxsLCBmaXhlZEFyZ3MpO1xyXG4gICAgICB9XHJcbiAgICB9KTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBzdGF0aWMgaXNBdmFpbGFibGUoKTogYm9vbGVhbiB7XHJcbiAgICByZXR1cm4gdHlwZW9mIFdvcmtlciAhPT0gJ3VuZGVmaW5lZCc7XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgZ2V0TmFtZSgpOiBzdHJpbmcge1xyXG4gICAgcmV0dXJuICdXb3JrZXJGUyc7XHJcbiAgfVxyXG5cclxuICBwcml2YXRlIF9hcmdSZW1vdGUyTG9jYWwoYXJnOiBhbnkpOiBhbnkge1xyXG4gICAgaWYgKGFyZyA9PSBudWxsKSB7XHJcbiAgICAgIHJldHVybiBhcmc7XHJcbiAgICB9XHJcbiAgICBzd2l0Y2ggKHR5cGVvZiBhcmcpIHtcclxuICAgICAgY2FzZSAnb2JqZWN0JzpcclxuICAgICAgICBpZiAoYXJnWyd0eXBlJ10gIT0gbnVsbCAmJiB0eXBlb2YgYXJnWyd0eXBlJ10gPT09ICdudW1iZXInKSB7XHJcbiAgICAgICAgICB2YXIgc3BlY2lhbEFyZyA9IDxJU3BlY2lhbEFyZ3VtZW50PiBhcmc7XHJcbiAgICAgICAgICBzd2l0Y2ggKHNwZWNpYWxBcmcudHlwZSkge1xyXG4gICAgICAgICAgICBjYXNlIFNwZWNpYWxBcmdUeXBlLkFQSV9FUlJPUjpcclxuICAgICAgICAgICAgICByZXR1cm4gYXBpRXJyb3JSZW1vdGUyTG9jYWwoPElBUElFcnJvckFyZ3VtZW50PiBzcGVjaWFsQXJnKTtcclxuICAgICAgICAgICAgY2FzZSBTcGVjaWFsQXJnVHlwZS5GRDpcclxuICAgICAgICAgICAgICB2YXIgZmRBcmcgPSA8SUZpbGVEZXNjcmlwdG9yQXJndW1lbnQ+IHNwZWNpYWxBcmc7XHJcbiAgICAgICAgICAgICAgcmV0dXJuIG5ldyBXb3JrZXJGaWxlKHRoaXMsIGZkQXJnLnBhdGgsIGZpbGVfZmxhZy5GaWxlRmxhZy5nZXRGaWxlRmxhZyhmZEFyZy5mbGFnKSwgU3RhdHMuZnJvbUJ1ZmZlcih0cmFuc2ZlcnJhYmxlT2JqZWN0VG9CdWZmZXIoZmRBcmcuc3RhdCkpLCBmZEFyZy5pZCwgdHJhbnNmZXJyYWJsZU9iamVjdFRvQnVmZmVyKGZkQXJnLmRhdGEpKTtcclxuICAgICAgICAgICAgY2FzZSBTcGVjaWFsQXJnVHlwZS5TVEFUUzpcclxuICAgICAgICAgICAgICByZXR1cm4gc3RhdHNSZW1vdGUyTG9jYWwoPElTdGF0c0FyZ3VtZW50PiBzcGVjaWFsQXJnKTtcclxuICAgICAgICAgICAgY2FzZSBTcGVjaWFsQXJnVHlwZS5GSUxFRkxBRzpcclxuICAgICAgICAgICAgICByZXR1cm4gZmlsZUZsYWdSZW1vdGUyTG9jYWwoPElGaWxlRmxhZ0FyZ3VtZW50PiBzcGVjaWFsQXJnKTtcclxuICAgICAgICAgICAgY2FzZSBTcGVjaWFsQXJnVHlwZS5CVUZGRVI6XHJcbiAgICAgICAgICAgICAgcmV0dXJuIGJ1ZmZlclJlbW90ZTJMb2NhbCg8SUJ1ZmZlckFyZ3VtZW50PiBzcGVjaWFsQXJnKTtcclxuICAgICAgICAgICAgY2FzZSBTcGVjaWFsQXJnVHlwZS5FUlJPUjpcclxuICAgICAgICAgICAgICByZXR1cm4gZXJyb3JSZW1vdGUyTG9jYWwoPElFcnJvckFyZ3VtZW50PiBzcGVjaWFsQXJnKTtcclxuICAgICAgICAgICAgZGVmYXVsdDpcclxuICAgICAgICAgICAgICByZXR1cm4gYXJnO1xyXG4gICAgICAgICAgfVxyXG4gICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICByZXR1cm4gYXJnO1xyXG4gICAgICAgIH1cclxuICAgICAgZGVmYXVsdDpcclxuICAgICAgICByZXR1cm4gYXJnO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogQ29udmVydHMgYSBsb2NhbCBhcmd1bWVudCBpbnRvIGEgcmVtb3RlIGFyZ3VtZW50LiBQdWJsaWMgc28gV29ya2VyRmlsZSBvYmplY3RzIGNhbiBjYWxsIGl0LlxyXG4gICAqL1xyXG4gIHB1YmxpYyBfYXJnTG9jYWwyUmVtb3RlKGFyZzogYW55KTogYW55IHtcclxuICAgIGlmIChhcmcgPT0gbnVsbCkge1xyXG4gICAgICByZXR1cm4gYXJnO1xyXG4gICAgfVxyXG4gICAgc3dpdGNoICh0eXBlb2YgYXJnKSB7XHJcbiAgICAgIGNhc2UgXCJvYmplY3RcIjpcclxuICAgICAgICBpZiAoYXJnIGluc3RhbmNlb2YgU3RhdHMpIHtcclxuICAgICAgICAgIHJldHVybiBzdGF0c0xvY2FsMlJlbW90ZShhcmcpO1xyXG4gICAgICAgIH0gZWxzZSBpZiAoYXJnIGluc3RhbmNlb2YgQXBpRXJyb3IpIHtcclxuICAgICAgICAgIHJldHVybiBhcGlFcnJvckxvY2FsMlJlbW90ZShhcmcpO1xyXG4gICAgICAgIH0gZWxzZSBpZiAoYXJnIGluc3RhbmNlb2YgV29ya2VyRmlsZSkge1xyXG4gICAgICAgICAgcmV0dXJuICg8V29ya2VyRmlsZT4gYXJnKS50b1JlbW90ZUFyZygpO1xyXG4gICAgICAgIH0gZWxzZSBpZiAoYXJnIGluc3RhbmNlb2YgZmlsZV9mbGFnLkZpbGVGbGFnKSB7XHJcbiAgICAgICAgICByZXR1cm4gZmlsZUZsYWdMb2NhbDJSZW1vdGUoYXJnKTtcclxuICAgICAgICB9IGVsc2UgaWYgKGFyZyBpbnN0YW5jZW9mIEJ1ZmZlcikge1xyXG4gICAgICAgICAgcmV0dXJuIGJ1ZmZlckxvY2FsMlJlbW90ZShhcmcpO1xyXG4gICAgICAgIH0gZWxzZSBpZiAoYXJnIGluc3RhbmNlb2YgRXJyb3IpIHtcclxuICAgICAgICAgIHJldHVybiBlcnJvckxvY2FsMlJlbW90ZShhcmcpO1xyXG4gICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICByZXR1cm4gXCJVbmtub3duIGFyZ3VtZW50XCI7XHJcbiAgICAgICAgfVxyXG4gICAgICBjYXNlIFwiZnVuY3Rpb25cIjpcclxuICAgICAgICByZXR1cm4gdGhpcy5fY2FsbGJhY2tDb252ZXJ0ZXIudG9SZW1vdGVBcmcoYXJnKTtcclxuICAgICAgZGVmYXVsdDpcclxuICAgICAgICByZXR1cm4gYXJnO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogQ2FsbGVkIG9uY2UgYm90aCBsb2NhbCBhbmQgcmVtb3RlIHNpZGVzIGFyZSBzZXQgdXAuXHJcbiAgICovXHJcbiAgcHVibGljIGluaXRpYWxpemUoY2I6ICgpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIGlmICghdGhpcy5faXNJbml0aWFsaXplZCkge1xyXG4gICAgICB2YXIgbWVzc2FnZTogSUFQSVJlcXVlc3QgPSB7XHJcbiAgICAgICAgYnJvd3NlcmZzTWVzc2FnZTogdHJ1ZSxcclxuICAgICAgICBtZXRob2Q6ICdwcm9iZScsXHJcbiAgICAgICAgYXJnczogW3RoaXMuX2FyZ0xvY2FsMlJlbW90ZShuZXcgQnVmZmVyKDApKSwgdGhpcy5fY2FsbGJhY2tDb252ZXJ0ZXIudG9SZW1vdGVBcmcoKHByb2JlUmVzcG9uc2U6IElQcm9iZVJlc3BvbnNlKSA9PiB7XHJcbiAgICAgICAgICB0aGlzLl9pc0luaXRpYWxpemVkID0gdHJ1ZTtcclxuICAgICAgICAgIHRoaXMuX2lzUmVhZE9ubHkgPSBwcm9iZVJlc3BvbnNlLmlzUmVhZE9ubHk7XHJcbiAgICAgICAgICB0aGlzLl9zdXBwb3J0TGlua3MgPSBwcm9iZVJlc3BvbnNlLnN1cHBvcnRzTGlua3M7XHJcbiAgICAgICAgICB0aGlzLl9zdXBwb3J0UHJvcHMgPSBwcm9iZVJlc3BvbnNlLnN1cHBvcnRzUHJvcHM7XHJcbiAgICAgICAgICBjYigpO1xyXG4gICAgICAgIH0pXVxyXG4gICAgICB9O1xyXG4gICAgICB0aGlzLl93b3JrZXIucG9zdE1lc3NhZ2UobWVzc2FnZSk7XHJcbiAgICB9IGVsc2Uge1xyXG4gICAgICBjYigpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgcHVibGljIGlzUmVhZE9ubHkoKTogYm9vbGVhbiB7IHJldHVybiB0aGlzLl9pc1JlYWRPbmx5OyB9XHJcbiAgcHVibGljIHN1cHBvcnRzU3luY2goKTogYm9vbGVhbiB7IHJldHVybiBmYWxzZTsgfVxyXG4gIHB1YmxpYyBzdXBwb3J0c0xpbmtzKCk6IGJvb2xlYW4geyByZXR1cm4gdGhpcy5fc3VwcG9ydExpbmtzOyB9XHJcbiAgcHVibGljIHN1cHBvcnRzUHJvcHMoKTogYm9vbGVhbiB7IHJldHVybiB0aGlzLl9zdXBwb3J0UHJvcHM7IH1cclxuXHJcbiAgcHJpdmF0ZSBfcnBjKG1ldGhvZE5hbWU6IHN0cmluZywgYXJnczogSUFyZ3VtZW50cykge1xyXG4gICAgdmFyIG1lc3NhZ2U6IElBUElSZXF1ZXN0ID0ge1xyXG4gICAgICBicm93c2VyZnNNZXNzYWdlOiB0cnVlLFxyXG4gICAgICBtZXRob2Q6IG1ldGhvZE5hbWUsXHJcbiAgICAgIGFyZ3M6IG51bGxcclxuICAgIH0sIGZpeGVkQXJncyA9IG5ldyBBcnJheShhcmdzLmxlbmd0aCksIGk6IG51bWJlcjtcclxuICAgIGZvciAoaSA9IDA7IGkgPCBhcmdzLmxlbmd0aDsgaSsrKSB7XHJcbiAgICAgIGZpeGVkQXJnc1tpXSA9IHRoaXMuX2FyZ0xvY2FsMlJlbW90ZShhcmdzW2ldKTtcclxuICAgIH1cclxuICAgIG1lc3NhZ2UuYXJncyA9IGZpeGVkQXJncztcclxuICAgIHRoaXMuX3dvcmtlci5wb3N0TWVzc2FnZShtZXNzYWdlKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyByZW5hbWUob2xkUGF0aDogc3RyaW5nLCBuZXdQYXRoOiBzdHJpbmcsIGNiOiAoZXJyPzogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHRoaXMuX3JwYygncmVuYW1lJywgYXJndW1lbnRzKTtcclxuICB9XHJcbiAgcHVibGljIHN0YXQocDogc3RyaW5nLCBpc0xzdGF0OiBib29sZWFuLCBjYjogKGVycjogQXBpRXJyb3IsIHN0YXQ/OiBTdGF0cykgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgdGhpcy5fcnBjKCdzdGF0JywgYXJndW1lbnRzKTtcclxuICB9XHJcbiAgcHVibGljIG9wZW4ocDogc3RyaW5nLCBmbGFnOiBmaWxlX2ZsYWcuRmlsZUZsYWcsIG1vZGU6IG51bWJlciwgY2I6IChlcnI6IEFwaUVycm9yLCBmZD86IGZpbGUuRmlsZSkgPT4gYW55KTogdm9pZCB7XHJcbiAgICB0aGlzLl9ycGMoJ29wZW4nLCBhcmd1bWVudHMpO1xyXG4gIH1cclxuICBwdWJsaWMgdW5saW5rKHA6IHN0cmluZywgY2I6IEZ1bmN0aW9uKTogdm9pZCB7XHJcbiAgICB0aGlzLl9ycGMoJ3VubGluaycsIGFyZ3VtZW50cyk7XHJcbiAgfVxyXG4gIHB1YmxpYyBybWRpcihwOiBzdHJpbmcsIGNiOiBGdW5jdGlvbik6IHZvaWQge1xyXG4gICAgdGhpcy5fcnBjKCdybWRpcicsIGFyZ3VtZW50cyk7XHJcbiAgfVxyXG4gIHB1YmxpYyBta2RpcihwOiBzdHJpbmcsIG1vZGU6IG51bWJlciwgY2I6IEZ1bmN0aW9uKTogdm9pZCB7XHJcbiAgICB0aGlzLl9ycGMoJ21rZGlyJywgYXJndW1lbnRzKTtcclxuICB9XHJcbiAgcHVibGljIHJlYWRkaXIocDogc3RyaW5nLCBjYjogKGVycjogQXBpRXJyb3IsIGZpbGVzPzogc3RyaW5nW10pID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHRoaXMuX3JwYygncmVhZGRpcicsIGFyZ3VtZW50cyk7XHJcbiAgfVxyXG4gIHB1YmxpYyBleGlzdHMocDogc3RyaW5nLCBjYjogKGV4aXN0czogYm9vbGVhbikgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgdGhpcy5fcnBjKCdleGlzdHMnLCBhcmd1bWVudHMpO1xyXG4gIH1cclxuICBwdWJsaWMgcmVhbHBhdGgocDogc3RyaW5nLCBjYWNoZTogeyBbcGF0aDogc3RyaW5nXTogc3RyaW5nIH0sIGNiOiAoZXJyOiBBcGlFcnJvciwgcmVzb2x2ZWRQYXRoPzogc3RyaW5nKSA9PiBhbnkpOiB2b2lkIHtcclxuICAgIHRoaXMuX3JwYygncmVhbHBhdGgnLCBhcmd1bWVudHMpO1xyXG4gIH1cclxuICBwdWJsaWMgdHJ1bmNhdGUocDogc3RyaW5nLCBsZW46IG51bWJlciwgY2I6IEZ1bmN0aW9uKTogdm9pZCB7XHJcbiAgICB0aGlzLl9ycGMoJ3RydW5jYXRlJywgYXJndW1lbnRzKTtcclxuICB9XHJcbiAgcHVibGljIHJlYWRGaWxlKGZuYW1lOiBzdHJpbmcsIGVuY29kaW5nOiBzdHJpbmcsIGZsYWc6IGZpbGVfZmxhZy5GaWxlRmxhZywgY2I6IChlcnI6IEFwaUVycm9yLCBkYXRhPzogYW55KSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICB0aGlzLl9ycGMoJ3JlYWRGaWxlJywgYXJndW1lbnRzKTtcclxuICB9XHJcbiAgcHVibGljIHdyaXRlRmlsZShmbmFtZTogc3RyaW5nLCBkYXRhOiBhbnksIGVuY29kaW5nOiBzdHJpbmcsIGZsYWc6IGZpbGVfZmxhZy5GaWxlRmxhZywgbW9kZTogbnVtYmVyLCBjYjogKGVycjogQXBpRXJyb3IpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgIHRoaXMuX3JwYygnd3JpdGVGaWxlJywgYXJndW1lbnRzKTtcclxuICB9XHJcbiAgcHVibGljIGFwcGVuZEZpbGUoZm5hbWU6IHN0cmluZywgZGF0YTogYW55LCBlbmNvZGluZzogc3RyaW5nLCBmbGFnOiBmaWxlX2ZsYWcuRmlsZUZsYWcsIG1vZGU6IG51bWJlciwgY2I6IChlcnI6IEFwaUVycm9yKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICB0aGlzLl9ycGMoJ2FwcGVuZEZpbGUnLCBhcmd1bWVudHMpO1xyXG4gIH1cclxuICBwdWJsaWMgY2htb2QocDogc3RyaW5nLCBpc0xjaG1vZDogYm9vbGVhbiwgbW9kZTogbnVtYmVyLCBjYjogRnVuY3Rpb24pOiB2b2lkIHtcclxuICAgIHRoaXMuX3JwYygnY2htb2QnLCBhcmd1bWVudHMpO1xyXG4gIH1cclxuICBwdWJsaWMgY2hvd24ocDogc3RyaW5nLCBpc0xjaG93bjogYm9vbGVhbiwgdWlkOiBudW1iZXIsIGdpZDogbnVtYmVyLCBjYjogRnVuY3Rpb24pOiB2b2lkIHtcclxuICAgIHRoaXMuX3JwYygnY2hvd24nLCBhcmd1bWVudHMpO1xyXG4gIH1cclxuICBwdWJsaWMgdXRpbWVzKHA6IHN0cmluZywgYXRpbWU6IERhdGUsIG10aW1lOiBEYXRlLCBjYjogRnVuY3Rpb24pOiB2b2lkIHtcclxuICAgIHRoaXMuX3JwYygndXRpbWVzJywgYXJndW1lbnRzKTtcclxuICB9XHJcbiAgcHVibGljIGxpbmsoc3JjcGF0aDogc3RyaW5nLCBkc3RwYXRoOiBzdHJpbmcsIGNiOiBGdW5jdGlvbik6IHZvaWQge1xyXG4gICAgdGhpcy5fcnBjKCdsaW5rJywgYXJndW1lbnRzKTtcclxuICB9XHJcbiAgcHVibGljIHN5bWxpbmsoc3JjcGF0aDogc3RyaW5nLCBkc3RwYXRoOiBzdHJpbmcsIHR5cGU6IHN0cmluZywgY2I6IEZ1bmN0aW9uKTogdm9pZCB7XHJcbiAgICB0aGlzLl9ycGMoJ3N5bWxpbmsnLCBhcmd1bWVudHMpO1xyXG4gIH1cclxuICBwdWJsaWMgcmVhZGxpbmsocDogc3RyaW5nLCBjYjogRnVuY3Rpb24pOiB2b2lkIHtcclxuICAgIHRoaXMuX3JwYygncmVhZGxpbmsnLCBhcmd1bWVudHMpO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIHN5bmNDbG9zZShtZXRob2Q6IHN0cmluZywgZmQ6IGZpbGUuRmlsZSwgY2I6IChlOiBBcGlFcnJvcikgPT4gdm9pZCk6IHZvaWQge1xyXG4gICAgdGhpcy5fd29ya2VyLnBvc3RNZXNzYWdlKDxJQVBJUmVxdWVzdD4ge1xyXG4gICAgICBicm93c2VyZnNNZXNzYWdlOiB0cnVlLFxyXG4gICAgICBtZXRob2Q6IG1ldGhvZCxcclxuICAgICAgYXJnczogWyg8V29ya2VyRmlsZT4gZmQpLnRvUmVtb3RlQXJnKCksIHRoaXMuX2NhbGxiYWNrQ29udmVydGVyLnRvUmVtb3RlQXJnKGNiKV1cclxuICAgIH0pO1xyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogQXR0YWNoZXMgYSBsaXN0ZW5lciB0byB0aGUgcmVtb3RlIHdvcmtlciBmb3IgZmlsZSBzeXN0ZW0gcmVxdWVzdHMuXHJcbiAgICovXHJcbiAgcHVibGljIHN0YXRpYyBhdHRhY2hSZW1vdGVMaXN0ZW5lcih3b3JrZXI6IFdvcmtlcikge1xyXG4gICAgdmFyIGZkQ29udmVydGVyID0gbmV3IEZpbGVEZXNjcmlwdG9yQXJndW1lbnRDb252ZXJ0ZXIoKTtcclxuXHJcbiAgICBmdW5jdGlvbiBhcmdMb2NhbDJSZW1vdGUoYXJnOiBhbnksIHJlcXVlc3RBcmdzOiBhbnlbXSwgY2I6IChlcnI6IEFwaUVycm9yLCBhcmc/OiBhbnkpID0+IHZvaWQpOiB2b2lkIHtcclxuICAgICAgc3dpdGNoICh0eXBlb2YgYXJnKSB7XHJcbiAgICAgICAgY2FzZSAnb2JqZWN0JzpcclxuICAgICAgICAgIGlmIChhcmcgaW5zdGFuY2VvZiBTdGF0cykge1xyXG4gICAgICAgICAgICBjYihudWxsLCBzdGF0c0xvY2FsMlJlbW90ZShhcmcpKTtcclxuICAgICAgICAgIH0gZWxzZSBpZiAoYXJnIGluc3RhbmNlb2YgQXBpRXJyb3IpIHtcclxuICAgICAgICAgICAgY2IobnVsbCwgYXBpRXJyb3JMb2NhbDJSZW1vdGUoYXJnKSk7XHJcbiAgICAgICAgICB9IGVsc2UgaWYgKGFyZyBpbnN0YW5jZW9mIGZpbGUuQmFzZUZpbGUpIHtcclxuICAgICAgICAgICAgLy8gUGFzcyBpbiBwIGFuZCBmbGFncyBmcm9tIG9yaWdpbmFsIHJlcXVlc3QuXHJcbiAgICAgICAgICAgIGNiKG51bGwsIGZkQ29udmVydGVyLnRvUmVtb3RlQXJnKGFyZywgcmVxdWVzdEFyZ3NbMF0sIHJlcXVlc3RBcmdzWzFdLCBjYikpO1xyXG4gICAgICAgICAgfSBlbHNlIGlmIChhcmcgaW5zdGFuY2VvZiBmaWxlX2ZsYWcuRmlsZUZsYWcpIHtcclxuICAgICAgICAgICAgY2IobnVsbCwgZmlsZUZsYWdMb2NhbDJSZW1vdGUoYXJnKSk7XHJcbiAgICAgICAgICB9IGVsc2UgaWYgKGFyZyBpbnN0YW5jZW9mIEJ1ZmZlcikge1xyXG4gICAgICAgICAgICBjYihudWxsLCBidWZmZXJMb2NhbDJSZW1vdGUoYXJnKSk7XHJcbiAgICAgICAgICB9IGVsc2UgaWYgKGFyZyBpbnN0YW5jZW9mIEVycm9yKSB7XHJcbiAgICAgICAgICAgIGNiKG51bGwsIGVycm9yTG9jYWwyUmVtb3RlKGFyZykpO1xyXG4gICAgICAgICAgfSBlbHNlIHtcclxuICAgICAgICAgICAgY2IobnVsbCwgYXJnKTtcclxuICAgICAgICAgIH1cclxuICAgICAgICAgIGJyZWFrO1xyXG4gICAgICAgIGRlZmF1bHQ6XHJcbiAgICAgICAgICBjYihudWxsLCBhcmcpO1xyXG4gICAgICAgICAgYnJlYWs7XHJcbiAgICAgIH1cclxuICAgIH1cclxuXHJcbiAgICBmdW5jdGlvbiBhcmdSZW1vdGUyTG9jYWwoYXJnOiBhbnksIGZpeGVkUmVxdWVzdEFyZ3M6IGFueVtdKTogYW55IHtcclxuICAgICAgaWYgKGFyZyA9PSBudWxsKSB7XHJcbiAgICAgICAgcmV0dXJuIGFyZztcclxuICAgICAgfVxyXG4gICAgICBzd2l0Y2ggKHR5cGVvZiBhcmcpIHtcclxuICAgICAgICBjYXNlICdvYmplY3QnOlxyXG4gICAgICAgICAgaWYgKHR5cGVvZiBhcmdbJ3R5cGUnXSA9PT0gJ251bWJlcicpIHtcclxuICAgICAgICAgICAgdmFyIHNwZWNpYWxBcmcgPSA8SVNwZWNpYWxBcmd1bWVudD4gYXJnO1xyXG4gICAgICAgICAgICBzd2l0Y2ggKHNwZWNpYWxBcmcudHlwZSkge1xyXG4gICAgICAgICAgICAgIGNhc2UgU3BlY2lhbEFyZ1R5cGUuQ0I6XHJcbiAgICAgICAgICAgICAgICB2YXIgY2JJZCA9ICg8SUNhbGxiYWNrQXJndW1lbnQ+IGFyZykuaWQ7XHJcbiAgICAgICAgICAgICAgICByZXR1cm4gZnVuY3Rpb24oKSB7XHJcbiAgICAgICAgICAgICAgICAgIHZhciBpOiBudW1iZXIsIGZpeGVkQXJncyA9IG5ldyBBcnJheShhcmd1bWVudHMubGVuZ3RoKSxcclxuICAgICAgICAgICAgICAgICAgICBtZXNzYWdlOiBJQVBJUmVzcG9uc2UsXHJcbiAgICAgICAgICAgICAgICAgICAgY291bnRkb3duID0gYXJndW1lbnRzLmxlbmd0aDtcclxuXHJcbiAgICAgICAgICAgICAgICAgIGZ1bmN0aW9uIGFib3J0QW5kU2VuZEVycm9yKGVycjogQXBpRXJyb3IpIHtcclxuICAgICAgICAgICAgICAgICAgICBpZiAoY291bnRkb3duID4gMCkge1xyXG4gICAgICAgICAgICAgICAgICAgICAgY291bnRkb3duID0gLTE7XHJcbiAgICAgICAgICAgICAgICAgICAgICBtZXNzYWdlID0ge1xyXG4gICAgICAgICAgICAgICAgICAgICAgICBicm93c2VyZnNNZXNzYWdlOiB0cnVlLFxyXG4gICAgICAgICAgICAgICAgICAgICAgICBjYklkOiBjYklkLFxyXG4gICAgICAgICAgICAgICAgICAgICAgICBhcmdzOiBbYXBpRXJyb3JMb2NhbDJSZW1vdGUoZXJyKV1cclxuICAgICAgICAgICAgICAgICAgICAgIH07XHJcbiAgICAgICAgICAgICAgICAgICAgICB3b3JrZXIucG9zdE1lc3NhZ2UobWVzc2FnZSk7XHJcbiAgICAgICAgICAgICAgICAgICAgfVxyXG4gICAgICAgICAgICAgICAgICB9XHJcblxyXG5cclxuICAgICAgICAgICAgICAgICAgZm9yIChpID0gMDsgaSA8IGFyZ3VtZW50cy5sZW5ndGg7IGkrKykge1xyXG4gICAgICAgICAgICAgICAgICAgIC8vIENhcHR1cmUgaSBhbmQgYXJndW1lbnQuXHJcbiAgICAgICAgICAgICAgICAgICAgKChpOiBudW1iZXIsIGFyZzogYW55KSA9PiB7XHJcbiAgICAgICAgICAgICAgICAgICAgICBhcmdMb2NhbDJSZW1vdGUoYXJnLCBmaXhlZFJlcXVlc3RBcmdzLCAoZXJyLCBmaXhlZEFyZz8pID0+IHtcclxuICAgICAgICAgICAgICAgICAgICAgICAgZml4ZWRBcmdzW2ldID0gZml4ZWRBcmc7XHJcbiAgICAgICAgICAgICAgICAgICAgICAgIGlmIChlcnIpIHtcclxuICAgICAgICAgICAgICAgICAgICAgICAgICBhYm9ydEFuZFNlbmRFcnJvcihlcnIpO1xyXG4gICAgICAgICAgICAgICAgICAgICAgICB9IGVsc2UgaWYgKC0tY291bnRkb3duID09PSAwKSB7XHJcbiAgICAgICAgICAgICAgICAgICAgICAgICAgbWVzc2FnZSA9IHtcclxuICAgICAgICAgICAgICAgICAgICAgICAgICAgIGJyb3dzZXJmc01lc3NhZ2U6IHRydWUsXHJcbiAgICAgICAgICAgICAgICAgICAgICAgICAgICBjYklkOiBjYklkLFxyXG4gICAgICAgICAgICAgICAgICAgICAgICAgICAgYXJnczogZml4ZWRBcmdzXHJcbiAgICAgICAgICAgICAgICAgICAgICAgICAgfTtcclxuICAgICAgICAgICAgICAgICAgICAgICAgICB3b3JrZXIucG9zdE1lc3NhZ2UobWVzc2FnZSk7XHJcbiAgICAgICAgICAgICAgICAgICAgICAgIH1cclxuICAgICAgICAgICAgICAgICAgICAgIH0pO1xyXG4gICAgICAgICAgICAgICAgICAgIH0pKGksIGFyZ3VtZW50c1tpXSk7XHJcbiAgICAgICAgICAgICAgICAgIH1cclxuXHJcbiAgICAgICAgICAgICAgICAgIGlmIChhcmd1bWVudHMubGVuZ3RoID09PSAwKSB7XHJcbiAgICAgICAgICAgICAgICAgICAgbWVzc2FnZSA9IHtcclxuICAgICAgICAgICAgICAgICAgICAgIGJyb3dzZXJmc01lc3NhZ2U6IHRydWUsXHJcbiAgICAgICAgICAgICAgICAgICAgICBjYklkOiBjYklkLFxyXG4gICAgICAgICAgICAgICAgICAgICAgYXJnczogZml4ZWRBcmdzXHJcbiAgICAgICAgICAgICAgICAgICAgfTtcclxuICAgICAgICAgICAgICAgICAgICB3b3JrZXIucG9zdE1lc3NhZ2UobWVzc2FnZSk7XHJcbiAgICAgICAgICAgICAgICAgIH1cclxuXHJcbiAgICAgICAgICAgICAgICB9O1xyXG4gICAgICAgICAgICAgIGNhc2UgU3BlY2lhbEFyZ1R5cGUuQVBJX0VSUk9SOlxyXG4gICAgICAgICAgICAgICAgcmV0dXJuIGFwaUVycm9yUmVtb3RlMkxvY2FsKDxJQVBJRXJyb3JBcmd1bWVudD4gc3BlY2lhbEFyZyk7XHJcbiAgICAgICAgICAgICAgY2FzZSBTcGVjaWFsQXJnVHlwZS5TVEFUUzpcclxuICAgICAgICAgICAgICAgIHJldHVybiBzdGF0c1JlbW90ZTJMb2NhbCg8SVN0YXRzQXJndW1lbnQ+IHNwZWNpYWxBcmcpO1xyXG4gICAgICAgICAgICAgIGNhc2UgU3BlY2lhbEFyZ1R5cGUuRklMRUZMQUc6XHJcbiAgICAgICAgICAgICAgICByZXR1cm4gZmlsZUZsYWdSZW1vdGUyTG9jYWwoPElGaWxlRmxhZ0FyZ3VtZW50PiBzcGVjaWFsQXJnKTtcclxuICAgICAgICAgICAgICBjYXNlIFNwZWNpYWxBcmdUeXBlLkJVRkZFUjpcclxuICAgICAgICAgICAgICAgIHJldHVybiBidWZmZXJSZW1vdGUyTG9jYWwoPElCdWZmZXJBcmd1bWVudD4gc3BlY2lhbEFyZyk7XHJcbiAgICAgICAgICAgICAgY2FzZSBTcGVjaWFsQXJnVHlwZS5FUlJPUjpcclxuICAgICAgICAgICAgICAgIHJldHVybiBlcnJvclJlbW90ZTJMb2NhbCg8SUVycm9yQXJndW1lbnQ+IHNwZWNpYWxBcmcpO1xyXG4gICAgICAgICAgICAgIGRlZmF1bHQ6XHJcbiAgICAgICAgICAgICAgICAvLyBObyBpZGVhIHdoYXQgdGhpcyBpcy5cclxuICAgICAgICAgICAgICAgIHJldHVybiBhcmc7XHJcbiAgICAgICAgICAgIH1cclxuICAgICAgICAgIH0gZWxzZSB7XHJcbiAgICAgICAgICAgIHJldHVybiBhcmc7XHJcbiAgICAgICAgICB9XHJcbiAgICAgICAgZGVmYXVsdDpcclxuICAgICAgICAgIHJldHVybiBhcmc7XHJcbiAgICAgIH1cclxuICAgIH1cclxuXHJcbiAgICB3b3JrZXIuYWRkRXZlbnRMaXN0ZW5lcignbWVzc2FnZScsKGU6IE1lc3NhZ2VFdmVudCkgPT4ge1xyXG4gICAgICB2YXIgcmVxdWVzdDogT2JqZWN0ID0gZS5kYXRhO1xyXG4gICAgICBpZiAoaXNBUElSZXF1ZXN0KHJlcXVlc3QpKSB7XHJcbiAgICAgICAgdmFyIGFyZ3MgPSByZXF1ZXN0LmFyZ3MsXHJcbiAgICAgICAgICBmaXhlZEFyZ3MgPSBuZXcgQXJyYXk8YW55PihhcmdzLmxlbmd0aCksXHJcbiAgICAgICAgICBpOiBudW1iZXI7XHJcblxyXG4gICAgICAgIHN3aXRjaCAocmVxdWVzdC5tZXRob2QpIHtcclxuICAgICAgICAgIGNhc2UgJ2Nsb3NlJzpcclxuICAgICAgICAgIGNhc2UgJ3N5bmMnOlxyXG4gICAgICAgICAgICAoKCkgPT4ge1xyXG4gICAgICAgICAgICAgIC8vIEZpbGUgZGVzY3JpcHRvci1yZWxhdGl2ZSBtZXRob2RzLlxyXG4gICAgICAgICAgICAgIHZhciByZW1vdGVDYiA9IDxJQ2FsbGJhY2tBcmd1bWVudD4gYXJnc1sxXTtcclxuICAgICAgICAgICAgICBmZENvbnZlcnRlci5hcHBseUZkQVBJUmVxdWVzdChyZXF1ZXN0LCAoZXJyPzogQXBpRXJyb3IpID0+IHtcclxuICAgICAgICAgICAgICAgIC8vIFNlbmQgcmVzcG9uc2UuXHJcbiAgICAgICAgICAgICAgICB2YXIgcmVzcG9uc2U6IElBUElSZXNwb25zZSA9IHtcclxuICAgICAgICAgICAgICAgICAgYnJvd3NlcmZzTWVzc2FnZTogdHJ1ZSxcclxuICAgICAgICAgICAgICAgICAgY2JJZDogcmVtb3RlQ2IuaWQsXHJcbiAgICAgICAgICAgICAgICAgIGFyZ3M6IGVyciA/IFthcGlFcnJvckxvY2FsMlJlbW90ZShlcnIpXSA6IFtdXHJcbiAgICAgICAgICAgICAgICB9O1xyXG4gICAgICAgICAgICAgICAgd29ya2VyLnBvc3RNZXNzYWdlKHJlc3BvbnNlKTtcclxuICAgICAgICAgICAgICB9KTtcclxuICAgICAgICAgICAgfSkoKTtcclxuICAgICAgICAgICAgYnJlYWs7XHJcbiAgICAgICAgICBjYXNlICdwcm9iZSc6XHJcbiAgICAgICAgICAgICgoKSA9PiB7XHJcbiAgICAgICAgICAgICAgdmFyIHJvb3RGcyA9IDxmaWxlX3N5c3RlbS5GaWxlU3lzdGVtPiBmcy5nZXRSb290RlMoKSxcclxuICAgICAgICAgICAgICAgIHJlbW90ZUNiID0gPElDYWxsYmFja0FyZ3VtZW50PiBhcmdzWzFdLFxyXG4gICAgICAgICAgICAgICAgcHJvYmVSZXNwb25zZTogSVByb2JlUmVzcG9uc2UgPSB7XHJcbiAgICAgICAgICAgICAgICAgIHR5cGU6IFNwZWNpYWxBcmdUeXBlLlBST0JFLFxyXG4gICAgICAgICAgICAgICAgICBpc1JlYWRPbmx5OiByb290RnMuaXNSZWFkT25seSgpLFxyXG4gICAgICAgICAgICAgICAgICBzdXBwb3J0c0xpbmtzOiByb290RnMuc3VwcG9ydHNMaW5rcygpLFxyXG4gICAgICAgICAgICAgICAgICBzdXBwb3J0c1Byb3BzOiByb290RnMuc3VwcG9ydHNQcm9wcygpXHJcbiAgICAgICAgICAgICAgICB9LFxyXG4gICAgICAgICAgICAgICAgcmVzcG9uc2U6IElBUElSZXNwb25zZSA9IHtcclxuICAgICAgICAgICAgICAgICAgYnJvd3NlcmZzTWVzc2FnZTogdHJ1ZSxcclxuICAgICAgICAgICAgICAgICAgY2JJZDogcmVtb3RlQ2IuaWQsXHJcbiAgICAgICAgICAgICAgICAgIGFyZ3M6IFtwcm9iZVJlc3BvbnNlXVxyXG4gICAgICAgICAgICAgICAgfTtcclxuXHJcbiAgICAgICAgICAgICAgd29ya2VyLnBvc3RNZXNzYWdlKHJlc3BvbnNlKTtcclxuICAgICAgICAgICAgfSkoKTtcclxuICAgICAgICAgICAgYnJlYWs7XHJcbiAgICAgICAgICBkZWZhdWx0OlxyXG4gICAgICAgICAgICAvLyBGaWxlIHN5c3RlbSBtZXRob2RzLlxyXG4gICAgICAgICAgICBmb3IgKGkgPSAwOyBpIDwgYXJncy5sZW5ndGg7IGkrKykge1xyXG4gICAgICAgICAgICAgIGZpeGVkQXJnc1tpXSA9IGFyZ1JlbW90ZTJMb2NhbChhcmdzW2ldLCBmaXhlZEFyZ3MpO1xyXG4gICAgICAgICAgICB9XHJcbiAgICAgICAgICAgIHZhciByb290RlMgPSBmcy5nZXRSb290RlMoKTtcclxuICAgICAgICAgICAgKDxGdW5jdGlvbj4gcm9vdEZTW3JlcXVlc3QubWV0aG9kXSkuYXBwbHkocm9vdEZTLCBmaXhlZEFyZ3MpO1xyXG4gICAgICAgICAgICBicmVhaztcclxuICAgICAgICB9XHJcbiAgICAgIH1cclxuICAgIH0pO1xyXG4gIH1cclxufVxyXG4iXX0=