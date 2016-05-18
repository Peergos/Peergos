'use strict';
var Doppio = require('../doppiojvm');
var util = Doppio.VM.Util;
var Long = Doppio.VM.Long;
var ThreadStatus = Doppio.VM.Enums.ThreadStatus;
var fs = require('fs');
var sun_nio_ch_FileChannelImpl = function () {
    function sun_nio_ch_FileChannelImpl() {
    }
    sun_nio_ch_FileChannelImpl['map0(IJJ)J'] = function (thread, javaThis, arg0, arg1, arg2) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_nio_ch_FileChannelImpl['unmap0(JJ)I'] = function (thread, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_nio_ch_FileChannelImpl['position0(Ljava/io/FileDescriptor;J)J'] = function (thread, javaThis, fd, offset) {
        return Long.fromNumber(offset.equals(Long.NEG_ONE) ? fd.$pos : fd.$pos = offset.toNumber());
    };
    sun_nio_ch_FileChannelImpl['initIDs()J'] = function (thread) {
        return Long.fromNumber(4096);
    };
    return sun_nio_ch_FileChannelImpl;
}();
var sun_nio_ch_NativeThread = function () {
    function sun_nio_ch_NativeThread() {
    }
    sun_nio_ch_NativeThread['current()J'] = function (thread) {
        return Long.fromNumber(-1);
    };
    sun_nio_ch_NativeThread['signal(J)V'] = function (thread, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_nio_ch_NativeThread['init()V'] = function (thread) {
    };
    return sun_nio_ch_NativeThread;
}();
var sun_nio_ch_IOUtil = function () {
    function sun_nio_ch_IOUtil() {
    }
    sun_nio_ch_IOUtil['iovMax()I'] = function (thread) {
        return 0;
    };
    return sun_nio_ch_IOUtil;
}();
var sun_nio_ch_FileDispatcherImpl = function () {
    function sun_nio_ch_FileDispatcherImpl() {
    }
    sun_nio_ch_FileDispatcherImpl['init()V'] = function (thread) {
    };
    sun_nio_ch_FileDispatcherImpl['read0(Ljava/io/FileDescriptor;JI)I'] = function (thread, fdObj, address, len) {
        var fd = fdObj['java/io/FileDescriptor/fd'], addr = address.toNumber(), buf = thread.getJVM().getHeap().get_buffer(addr, len);
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.read(fd, buf, 0, len, null, function (err, bytesRead) {
            if (err) {
                thread.throwNewException('Ljava/io/IOException;', 'Error reading file: ' + err);
            } else {
                thread.asyncReturn(bytesRead);
            }
        });
    };
    sun_nio_ch_FileDispatcherImpl['preClose0(Ljava/io/FileDescriptor;)V'] = function (thread, arg0) {
    };
    sun_nio_ch_FileDispatcherImpl['close0(Ljava/io/FileDescriptor;)V'] = function (thread, fdObj) {
        sun_nio_ch_FileDispatcherImpl['closeIntFD(I)V'](thread, fdObj['java/io/FileDescriptor/fd']);
    };
    sun_nio_ch_FileDispatcherImpl['size0(Ljava/io/FileDescriptor;)J'] = function (thread, fdObj) {
        var fd = fdObj['java/io/FileDescriptor/fd'];
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.fstat(fd, function (err, stats) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                thread.asyncReturn(Long.fromNumber(stats.size), null);
            }
        });
    };
    sun_nio_ch_FileDispatcherImpl['truncate0(Ljava/io/FileDescriptor;J)I'] = function (thread, fdObj, size) {
        var fd = fdObj['java/io/FileDescriptor/fd'];
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.ftruncate(fd, size.toNumber(), function (err) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                thread.asyncReturn(0);
            }
        });
    };
    sun_nio_ch_FileDispatcherImpl['closeIntFD(I)V'] = function (thread, fd) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.close(fd, function (err) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                thread.asyncReturn();
            }
        });
    };
    return sun_nio_ch_FileDispatcherImpl;
}();
var DirFd = function () {
    function DirFd(listing) {
        this._pos = 0;
        this._listing = listing;
    }
    DirFd.prototype.next = function () {
        var next = this._listing[this._pos++];
        if (next === undefined) {
            next = null;
        }
        return next;
    };
    return DirFd;
}();
var FDMap = function () {
    function FDMap() {
        this._map = {};
    }
    FDMap.prototype.newEntry = function (entry) {
        var fd = FDMap._nextFd++;
        this._map[fd] = entry;
        return fd;
    };
    FDMap.prototype.removeEntry = function (thread, fd, exceptionType) {
        if (this._map[fd]) {
            delete this._map[fd];
        } else {
            thread.throwNewException(exceptionType, 'Invalid file descriptor: ' + fd);
        }
    };
    FDMap.prototype.getEntry = function (thread, exceptionType, fd) {
        var entry = this._map[fd];
        if (!entry) {
            thread.throwNewException(exceptionType, 'Invalid file descriptor: ' + fd);
            return null;
        } else {
            return entry;
        }
    };
    FDMap._nextFd = 1;
    return FDMap;
}();
var dirMap = new FDMap(), fileMap = new FDMap();
function getStringFromHeap(thread, ptrLong) {
    var heap = thread.getJVM().getHeap(), ptr = ptrLong.toNumber(), len = 0;
    while (heap.get_signed_byte(ptr + len) !== 0) {
        len++;
    }
    return heap.get_buffer(ptr, len).toString();
}
function stringToByteArray(thread, str) {
    if (!str) {
        return null;
    }
    var buff = new Buffer(str, 'utf8'), len = buff.length, arr = util.newArray(thread, thread.getBsCl(), '[B', len);
    for (var i = 0; i < len; i++) {
        arr.array[i] = buff.readUInt8(i);
    }
    return arr;
}
function convertError(thread, err, cb) {
    thread.setStatus(ThreadStatus.ASYNC_WAITING);
    if (err.code === 'ENOENT') {
        thread.getBsCl().initializeClass(thread, 'Ljava/nio/file/NoSuchFileException;', function (noSuchFileException) {
            var cons = noSuchFileException.getConstructor(thread), rv = new cons(thread);
            rv['<init>(Ljava/lang/String;)V'](thread, [util.initString(thread.getBsCl(), err.path)], function (e) {
                thread.throwException(rv);
            });
        });
    } else if (err.code === 'EEXIST') {
        thread.getBsCl().initializeClass(thread, 'Ljava/nio/file/FileAlreadyExistsException;', function (fileAlreadyExistsException) {
            var cons = fileAlreadyExistsException.getConstructor(thread), rv = new cons(thread);
            rv['<init>(Ljava/lang/String;)V'](thread, [util.initString(thread.getBsCl(), err.path)], function (e) {
                cb(rv);
            });
        });
    } else {
        thread.getBsCl().initializeClass(thread, 'Lsun/nio/fs/UnixException;', function (unixException) {
            thread.getBsCl().initializeClass(thread, 'Lsun/nio/fs/UnixConstants;', function (unixConstants) {
                var cons = unixException.getConstructor(thread), rv = new cons(thread), unixCons = unixConstants.getConstructor(thread), errCode = unixCons['sun/nio/fs/UnixConstants/' + err.code];
                if (typeof errCode !== 'number') {
                    errCode = -1;
                }
                rv['sun/nio/fs/UnixException/errno'] = errCode;
                rv['sun/nio/fs/UnixException/msg'] = util.initString(thread.getBsCl(), err.message);
                cb(rv);
            });
        });
    }
}
function convertStats(stats, jvmStats) {
    jvmStats['sun/nio/fs/UnixFileAttributes/st_mode'] = stats.mode;
    jvmStats['sun/nio/fs/UnixFileAttributes/st_ino'] = Long.fromNumber(stats.ino);
    jvmStats['sun/nio/fs/UnixFileAttributes/st_dev'] = Long.fromNumber(stats.dev);
    jvmStats['sun/nio/fs/UnixFileAttributes/st_rdev'] = Long.fromNumber(stats.rdev);
    jvmStats['sun/nio/fs/UnixFileAttributes/st_nlink'] = stats.nlink;
    jvmStats['sun/nio/fs/UnixFileAttributes/st_uid'] = stats.uid;
    jvmStats['sun/nio/fs/UnixFileAttributes/st_gid'] = stats.gid;
    jvmStats['sun/nio/fs/UnixFileAttributes/st_size'] = Long.fromNumber(stats.size);
    var atime = date2components(stats.atime), mtime = date2components(stats.mtime), ctime = date2components(stats.ctime);
    jvmStats['sun/nio/fs/UnixFileAttributes/st_atime_sec'] = Long.fromNumber(atime[0]);
    jvmStats['sun/nio/fs/UnixFileAttributes/st_atime_nsec'] = Long.fromNumber(atime[1]);
    jvmStats['sun/nio/fs/UnixFileAttributes/st_mtime_sec'] = Long.fromNumber(mtime[0]);
    jvmStats['sun/nio/fs/UnixFileAttributes/st_mtime_nsec'] = Long.fromNumber(mtime[1]);
    jvmStats['sun/nio/fs/UnixFileAttributes/st_ctime_sec'] = Long.fromNumber(ctime[0]);
    jvmStats['sun/nio/fs/UnixFileAttributes/st_ctime_nsec'] = Long.fromNumber(ctime[1]);
    jvmStats['sun/nio/fs/UnixFileAttributes/st_birthtime_sec'] = Long.fromNumber(Math.floor(stats.birthtime.getTime() / 1000));
}
var UnixConstants = null;
function flagTest(flag, mask) {
    return (flag & mask) === mask;
}
function flag2nodeflag(thread, flag) {
    if (UnixConstants === null) {
        var UCCls = thread.getBsCl().getInitializedClass(thread, 'Lsun/nio/fs/UnixConstants;');
        if (UCCls === null) {
            thread.throwNewException('Ljava/lang/InternalError;', 'UnixConstants is not initialized?');
            return null;
        }
        UnixConstants = UCCls.getConstructor(thread);
    }
    var sync = flagTest(flag, UnixConstants['sun/nio/fs/UnixConstants/O_SYNC']) || flagTest(flag, UnixConstants['sun/nio/fs/UnixConstants/O_DSYNC']);
    var failIfExists = flagTest(flag, UnixConstants['sun/nio/fs/UnixConstants/O_EXCL'] | UnixConstants['sun/nio/fs/UnixConstants/O_CREAT']);
    if (flagTest(flag, UnixConstants['sun/nio/fs/UnixConstants/O_RDONLY'])) {
        return sync ? 'rs' : 'r';
    } else if (flagTest(flag, UnixConstants['sun/nio/fs/UnixConstants/O_WRONLY'])) {
        if (flag & UnixConstants['sun/nio/fs/UnixConstants/O_APPEND']) {
            return failIfExists ? 'ax' : 'a';
        } else {
            return failIfExists ? 'wx' : 'w';
        }
    } else if (flagTest(flag, UnixConstants['sun/nio/fs/UnixConstants/O_RDWR'])) {
        if (flagTest(flag, UnixConstants['sun/nio/fs/UnixConstants/O_APPEND'])) {
            return failIfExists ? 'ax+' : 'a+';
        } else if (flagTest(flag, UnixConstants['sun/nio/fs/UnixConstants/O_CREAT'])) {
            return failIfExists ? 'wx+' : 'w+';
        } else {
            return sync ? 'rs+' : 'r+';
        }
    } else {
        thread.throwNewException('Lsun/nio/fs/UnixException;', 'Invalid open flag: ' + flag + '.');
        return null;
    }
}
function throwNodeError(thread, err) {
    convertError(thread, err, function (convertedErr) {
        thread.throwException(convertedErr);
    });
}
function date2components(date) {
    var dateInMs = date.getTime();
    return [
        Math.floor(dateInMs / 1000),
        dateInMs % 1000 * 1000000
    ];
}
var sun_nio_fs_UnixNativeDispatcher = function () {
    function sun_nio_fs_UnixNativeDispatcher() {
    }
    sun_nio_fs_UnixNativeDispatcher['getcwd()[B'] = function (thread) {
        var buff = new Buffer(process.cwd() + '\0', 'utf8'), len = buff.length, rv = util.newArray(thread, thread.getBsCl(), '[B', len), i;
        for (i = 0; i < len; i++) {
            rv.array[i] = buff.readInt8(i);
        }
        return rv;
    };
    sun_nio_fs_UnixNativeDispatcher['dup(I)I'] = function (thread, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_nio_fs_UnixNativeDispatcher['open0(JII)I'] = function (thread, pathAddress, flags, mode) {
        var flagStr = flag2nodeflag(thread, flags);
        if (flagStr !== null) {
            thread.setStatus(ThreadStatus.ASYNC_WAITING);
            var pathStr = getStringFromHeap(thread, pathAddress);
            fs.open(pathStr, flagStr, mode, function (err, fd) {
                if (err) {
                    throwNodeError(thread, err);
                } else {
                    thread.asyncReturn(fd);
                }
            });
        }
    };
    sun_nio_fs_UnixNativeDispatcher['openat0(IJII)I'] = function (thread, arg0, arg1, arg2, arg3) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_nio_fs_UnixNativeDispatcher['close(I)V'] = function (thread, fd) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.close(fd, function (err) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                thread.asyncReturn();
            }
        });
    };
    sun_nio_fs_UnixNativeDispatcher['fopen0(JJ)J'] = function (thread, pathAddress, flagsAddress) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        var pathStr = getStringFromHeap(thread, pathAddress);
        var flagsStr = getStringFromHeap(thread, flagsAddress);
        fs.open(pathStr, flagsStr, function (err, fd) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                thread.asyncReturn(Long.fromNumber(fd), null);
            }
        });
    };
    sun_nio_fs_UnixNativeDispatcher['fclose(J)V'] = function (thread, fd) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.close(fd.toNumber(), function (err) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                thread.asyncReturn();
            }
        });
    };
    sun_nio_fs_UnixNativeDispatcher['link0(JJ)V'] = function (thread, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_nio_fs_UnixNativeDispatcher['unlink0(J)V'] = function (thread, pathAddress) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.unlink(getStringFromHeap(thread, pathAddress), function (err) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                thread.asyncReturn();
            }
        });
    };
    sun_nio_fs_UnixNativeDispatcher['unlinkat0(IJI)V'] = function (thread, arg0, arg1, arg2) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_nio_fs_UnixNativeDispatcher['mknod0(JIJ)V'] = function (thread, arg0, arg1, arg2) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_nio_fs_UnixNativeDispatcher['rename0(JJ)V'] = function (thread, oldAddr, newAddr) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.rename(getStringFromHeap(thread, oldAddr), getStringFromHeap(thread, newAddr), function (err) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                thread.asyncReturn();
            }
        });
    };
    sun_nio_fs_UnixNativeDispatcher['renameat0(IJIJ)V'] = function (thread, arg0, arg1, arg2, arg3) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_nio_fs_UnixNativeDispatcher['mkdir0(JI)V'] = function (thread, pathAddr, mode) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.mkdir(getStringFromHeap(thread, pathAddr), mode, function (err) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                thread.asyncReturn();
            }
        });
    };
    sun_nio_fs_UnixNativeDispatcher['rmdir0(J)V'] = function (thread, pathAddr) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.rmdir(getStringFromHeap(thread, pathAddr), function (err) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                thread.asyncReturn();
            }
        });
    };
    sun_nio_fs_UnixNativeDispatcher['readlink0(J)[B'] = function (thread, pathAddr) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.readlink(getStringFromHeap(thread, pathAddr), function (err, linkPath) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                thread.asyncReturn(util.initCarr(thread.getBsCl(), linkPath));
            }
        });
    };
    sun_nio_fs_UnixNativeDispatcher['realpath0(J)[B'] = function (thread, pathAddress) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.realpath(getStringFromHeap(thread, pathAddress), function (err, resolvedPath) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                thread.asyncReturn(util.initCarr(thread.getBsCl(), resolvedPath));
            }
        });
    };
    sun_nio_fs_UnixNativeDispatcher['symlink0(JJ)V'] = function (thread, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_nio_fs_UnixNativeDispatcher['stat0(JLsun/nio/fs/UnixFileAttributes;)V'] = function (thread, pathAddress, jvmStats) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.stat(getStringFromHeap(thread, pathAddress), function (err, stats) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                convertStats(stats, jvmStats);
                thread.asyncReturn();
            }
        });
    };
    sun_nio_fs_UnixNativeDispatcher['lstat0(JLsun/nio/fs/UnixFileAttributes;)V'] = function (thread, pathAddress, jvmStats) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.lstat(getStringFromHeap(thread, pathAddress), function (err, stats) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                convertStats(stats, jvmStats);
                thread.asyncReturn();
            }
        });
    };
    sun_nio_fs_UnixNativeDispatcher['fstat(ILsun/nio/fs/UnixFileAttributes;)V'] = function (thread, fd, jvmStats) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.fstat(fd, function (err, stats) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                convertStats(stats, jvmStats);
                thread.asyncReturn();
            }
        });
    };
    sun_nio_fs_UnixNativeDispatcher['fstatat0(IJILsun/nio/fs/UnixFileAttributes;)V'] = function (thread, arg0, arg1, arg2, arg3) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_nio_fs_UnixNativeDispatcher['chown0(JII)V'] = function (thread, arg0, arg1, arg2) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_nio_fs_UnixNativeDispatcher['lchown0(JII)V'] = function (thread, arg0, arg1, arg2) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_nio_fs_UnixNativeDispatcher['fchown(III)V'] = function (thread, arg0, arg1, arg2) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_nio_fs_UnixNativeDispatcher['chmod0(JI)V'] = function (thread, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_nio_fs_UnixNativeDispatcher['fchmod(II)V'] = function (thread, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_nio_fs_UnixNativeDispatcher['utimes0(JJJ)V'] = function (thread, arg0, arg1, arg2) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_nio_fs_UnixNativeDispatcher['futimes(IJJ)V'] = function (thread, arg0, arg1, arg2) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_nio_fs_UnixNativeDispatcher['opendir0(J)J'] = function (thread, ptr) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.readdir(getStringFromHeap(thread, ptr), function (err, files) {
            if (err) {
                convertError(thread, err, function (errObj) {
                    thread.throwException(errObj);
                });
            } else {
                thread.asyncReturn(Long.fromNumber(dirMap.newEntry(new DirFd(files))), null);
            }
        });
    };
    sun_nio_fs_UnixNativeDispatcher['fdopendir(I)J'] = function (thread, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_nio_fs_UnixNativeDispatcher['closedir(J)V'] = function (thread, arg0) {
        dirMap.removeEntry(thread, arg0.toNumber(), 'Lsun/nio/fs/UnixException;');
    };
    sun_nio_fs_UnixNativeDispatcher['readdir(J)[B'] = function (thread, fd) {
        var dirFd = dirMap.getEntry(thread, 'Lsun/nio/fs/UnixException;', fd.toNumber());
        if (dirFd) {
            return stringToByteArray(thread, dirFd.next());
        }
    };
    sun_nio_fs_UnixNativeDispatcher['read(IJI)I'] = function (thread, fd, buf, nbyte) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        var buff = thread.getJVM().getHeap().get_buffer(buf.toNumber(), nbyte);
        fs.read(fd, buff, 0, nbyte, null, function (err, bytesRead) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                thread.asyncReturn(bytesRead);
            }
        });
    };
    sun_nio_fs_UnixNativeDispatcher['write(IJI)I'] = function (thread, fd, buf, nbyte) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        var buff = thread.getJVM().getHeap().get_buffer(buf.toNumber(), nbyte);
        fs.write(fd, buff, 0, nbyte, null, function (err, bytesWritten) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                thread.asyncReturn(bytesWritten);
            }
        });
    };
    sun_nio_fs_UnixNativeDispatcher['access0(JI)V'] = function (thread, pathAddress, arg1) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        var pathString = getStringFromHeap(thread, pathAddress);
        var checker = util.are_in_browser() ? fs.stat : fs.access;
        checker(pathString, function (err, stat) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                thread.asyncReturn();
            }
        });
    };
    sun_nio_fs_UnixNativeDispatcher['getpwuid(I)[B'] = function (thread, arg0) {
        return util.initCarr(thread.getBsCl(), 'doppio');
    };
    sun_nio_fs_UnixNativeDispatcher['getgrgid(I)[B'] = function (thread, arg0) {
        return util.initCarr(thread.getBsCl(), 'doppio');
    };
    sun_nio_fs_UnixNativeDispatcher['getpwnam0(J)I'] = function (thread, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_nio_fs_UnixNativeDispatcher['getgrnam0(J)I'] = function (thread, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return 0;
    };
    sun_nio_fs_UnixNativeDispatcher['statvfs0(JLsun/nio/fs/UnixFileStoreAttributes;)V'] = function (thread, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    sun_nio_fs_UnixNativeDispatcher['pathconf0(JI)J'] = function (thread, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_nio_fs_UnixNativeDispatcher['fpathconf(II)J'] = function (thread, arg0, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_nio_fs_UnixNativeDispatcher['strerror(I)[B'] = function (thread, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    sun_nio_fs_UnixNativeDispatcher['init()I'] = function (thread) {
        return 0;
    };
    return sun_nio_fs_UnixNativeDispatcher;
}();
registerNatives({
    'sun/nio/ch/FileChannelImpl': sun_nio_ch_FileChannelImpl,
    'sun/nio/ch/NativeThread': sun_nio_ch_NativeThread,
    'sun/nio/ch/IOUtil': sun_nio_ch_IOUtil,
    'sun/nio/ch/FileDispatcherImpl': sun_nio_ch_FileDispatcherImpl,
    'sun/nio/fs/UnixNativeDispatcher': sun_nio_fs_UnixNativeDispatcher
});
//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJzb3VyY2VzIjpbIi4uLy4uLy4uLy4uL3NyYy9uYXRpdmVzL3N1bl9uaW8udHMiXSwibmFtZXMiOltdLCJtYXBwaW5ncyI6IjtBQUNBLElBQVksTUFBQSxHQUFNLE9BQUEsQ0FBTSxjQUFOLENBQWxCO0FBSUEsSUFBTyxJQUFBLEdBQU8sTUFBQSxDQUFPLEVBQVAsQ0FBVSxJQUF4QjtBQUNBLElBQU8sSUFBQSxHQUFPLE1BQUEsQ0FBTyxFQUFQLENBQVUsSUFBeEI7QUFDQSxJQUFPLFlBQUEsR0FBZSxNQUFBLENBQU8sRUFBUCxDQUFVLEtBQVYsQ0FBZ0IsWUFBdEM7QUFDQSxJQUFPLEVBQUEsR0FBRSxPQUFBLENBQVcsSUFBWCxDQUFUO0FBR0EsSUFBQSwwQkFBQSxHQUFBLFlBQUE7QUFBQSxJQUFBLFNBQUEsMEJBQUEsR0FBQTtBQUFBLEtBQUE7QUFBQSxJQUVnQiwwQkFBQSxDQUFBLFlBQUEsSUFBZCxVQUEyQixNQUEzQixFQUE4QyxRQUE5QyxFQUE2RixJQUE3RixFQUEyRyxJQUEzRyxFQUF1SCxJQUF2SCxFQUFpSTtBQUFBLFFBQy9ILE1BQUEsQ0FBTyxpQkFBUCxDQUF5QixrQ0FBekIsRUFBNkQsZ0NBQTdELEVBRCtIO0FBQUEsUUFHL0gsT0FBTyxJQUFQLENBSCtIO0FBQUEsS0FBbkgsQ0FGaEI7QUFBQSxJQVFnQiwwQkFBQSxDQUFBLGFBQUEsSUFBZCxVQUE0QixNQUE1QixFQUErQyxJQUEvQyxFQUEyRCxJQUEzRCxFQUFxRTtBQUFBLFFBQ25FLE1BQUEsQ0FBTyxpQkFBUCxDQUF5QixrQ0FBekIsRUFBNkQsZ0NBQTdELEVBRG1FO0FBQUEsUUFHbkUsT0FBTyxDQUFQLENBSG1FO0FBQUEsS0FBdkQsQ0FSaEI7QUFBQSxJQWNnQiwwQkFBQSxDQUFBLHVDQUFBLElBQWQsVUFBc0QsTUFBdEQsRUFBeUUsUUFBekUsRUFBd0gsRUFBeEgsRUFBNkosTUFBN0osRUFBeUs7QUFBQSxRQUN2SyxPQUFPLElBQUEsQ0FBSyxVQUFMLENBQWdCLE1BQUEsQ0FBTyxNQUFQLENBQWMs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LEVBQXlDO0FBQUEsUUFDdkMsT0FBTyxDQUFQLENBRHVDO0FBQUEsS0FBM0IsQ0FoVmhCO0FBQUEsSUFvVkEsT0FBQSwrQkFBQSxDQXBWQTtBQUFBLENBQUEsRUFBQTtBQXNWQSxlQUFBLENBQWdCO0FBQUEsSUFDZCw4QkFBOEIsMEJBRGhCO0FBQUEsSUFFZCwyQkFBMkIsdUJBRmI7QUFBQSxJQUdkLHFCQUFxQixpQkFIUDtBQUFBLElBSWQsaUNBQWlDLDZCQUpuQjtBQUFBLElBS2QsbUNBQW1DLCtCQUxyQjtBQUFBLENBQWhCIiwic291cmNlc0NvbnRlbnQiOlsiaW1wb3J0IEpWTVR5cGVzID0gcmVxdWlyZSgnLi4vLi4vaW5jbHVkZXMvSlZNVHlwZXMnKTtcbmltcG9ydCAqIGFzIERvcHBpbyBmcm9tICcuLi9kb3BwaW9qdm0nO1xuaW1wb3J0IEpWTVRocmVhZCA9IERvcHBpby5WTS5UaHJlYWRpbmcuSlZNVGhyZWFkO1xuaW1wb3J0IFJlZmVyZW5jZUNsYXNzRGF0YSA9IERvcHBpby5WTS5DbGFzc0ZpbGUuUmVmZXJlbmNlQ2xhc3NEYXRhO1xuaW1wb3J0IGxvZ2dpbmcgPSBEb3BwaW8uRGVidWcuTG9nZ2luZztcbmltcG9ydCB1dGlsID0gRG9wcGlvLlZNLlV0aWw7XG5pbXBvcnQgTG9uZyA9IERvcHBpby5WTS5Mb25nO1xuaW1wb3J0IFRocmVhZFN0YXR1cyA9IERvcHBpby5WTS5FbnVtcy5UaHJlYWRTdGF0dXM7XG5pbXBvcnQgZnMgPSByZXF1aXJlKCdmcycpO1xuZGVjbGFyZSB2YXIgcmVnaXN0ZXJOYXRpdmVzOiAoZGVmczogYW55KSA9PiB2b2lkO1xuXG5jbGFzcyBzdW5fbmlvX2NoX0ZpbGVDaGFubmVsSW1wbCB7XG5cbiAgcHVibGljIHN0YXRpYyAnbWFwMChJSkopSicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5zdW5fbmlvX2NoX0ZpbGVDaGFubmVsSW1wbCwgYXJnMDogbnVtYmVyLCBhcmcxOiBMb25nLCBhcmcyOiBMb25nKTogTG9uZyB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL1Vuc2F0aXNmaWVkTGlua0Vycm9yOycsICdOYXRpdmUgbWV0aG9kIG5vdCBpbXBsZW1lbnRlZC4nKTtcbiAgICAvLyBTYXRpc2Z5IFR5cGVTY3JpcHQgcmV0dXJuIHR5cGUuXG4gICAgcmV0dXJuIG51bGw7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICd1bm1hcDAoSkopSScodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IExvbmcsIGFyZzE6IExvbmcpOiBudW1iZXIge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gICAgLy8gU2F0aXNmeSBUeXBlU2NyaXB0IHJldHVybiB0eXBlLlxuICAgIHJldHVybiAwO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAncG9zaXRpb24wKExqYXZhL2lvL0ZpbGVEZXNjcmlwdG9yO0opSicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5zdW5fbmlvX2NoX0ZpbGVDaGFubmVsSW1wbCwgZmQ6IEpWTVR5cGVzLmphdmFfaW9fRmlsZURlc2NyaXB0b3IsIG9mZnNldDogTG9uZyk6IExvbmcge1xuICAgIHJldHVybiBMb25nLmZyb21OdW1iZXIob2Zmc2V0LmVxdWFscyhMb25nLk5FR19PTkUpID8gZmQuJHBvcyA6IGZkLiRwb3MgPSBvZmZzZXQudG9OdW1iZXIoKSk7XG4gIH1cblxuICAvKipcbiAgICogdGhpcyBwb29ybHktbmFtZWQgbWV0aG9kIGFjdHVhbGx5IHNwZWNpZmllcyB0aGUgcGFnZSBzaXplIGZvciBtbWFwXG4gICAqIFRoaXMgaXMgdGhlIE1hYyBuYW1lIGZvciBzdW4vbWlzYy9VbnNhZmU6OnBhZ2VTaXplLiBBcHBhcmVudGx5IHRoZXlcbiAgICogd2FudGVkIHRvIGVuc3VyZSBwYWdlIHNpemVzIGNhbiBiZSA+IDJHQi4uLlxuICAgKi9cbiAgcHVibGljIHN0YXRpYyAnaW5pdElEcygpSicodGhyZWFkOiBKVk1UaHJlYWQpOiBMb25nIHtcbiAgICAvLyBTaXplIG9mIGhlYXAgcGFnZXMuXG4gICAgcmV0dXJuIExvbmcuZnJvbU51bWJlcig0MDk2KTtcbiAgfVxuXG59XG5cbmNsYXNzIHN1bl9uaW9fY2hfTmF0aXZlVGhyZWFkIHtcblxuICBwdWJsaWMgc3RhdGljICdjdXJyZW50KClKJyh0aHJlYWQ6IEpWTVRocmVhZCk6IExvbmcge1xuICAgIC8vIC0xIG1lYW5zIHRoYXQgd2UgZG8gbm90IHJlcXVpcmUgc2lnbmFsaW5nIGFjY29yZGluZyB0byB0aGVcbiAgICAvLyBkb2NzLlxuICAgIHJldHVybiBMb25nLmZyb21OdW1iZXIoLTEpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnc2lnbmFsKEopVicodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IExvbmcpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnaW5pdCgpVicodGhyZWFkOiBKVk1UaHJlYWQpOiB2b2lkIHtcbiAgICAvLyBOT1BcbiAgfVxuXG59XG5cbmNsYXNzIHN1bl9uaW9fY2hfSU9VdGlsIHtcblxuICBwdWJsaWMgc3RhdGljICdpb3ZNYXgoKUknKHRocmVhZDogSlZNVGhyZWFkKTogbnVtYmVyIHtcbiAgICAvLyBNYXhpbXVtIG51bWJlciBvZiBJT1ZlY3RvcnMgc3VwcG9ydGVkLiBMZXQncyBwdW50IGFuZCBzYXkgemVyby5cbiAgICByZXR1cm4gMDtcbiAgfVxuXG59XG5cbmNsYXNzIHN1bl9uaW9fY2hfRmlsZURpc3BhdGNoZXJJbXBsIHtcblxuICBwdWJsaWMgc3RhdGljICdpbml0KClWJyh0aHJlYWQ6IEpWTVRocmVhZCk6IHZvaWQge1xuXG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdyZWFkMChMamF2YS9pby9GaWxlRGVzY3JpcHRvcjtKSSlJJyh0aHJlYWQ6IEpWTVRocmVhZCwgZmRPYmo6IEpWTVR5cGVzLmphdmFfaW9fRmlsZURlc2NyaXB0b3IsIGFkZHJlc3M6IExvbmcsIGxlbjogbnVtYmVyKTogdm9pZCB7XG4gICAgdmFyIGZkID0gZmRPYmpbXCJqYXZhL2lvL0ZpbGVEZXNjcmlwdG9yL2ZkXCJdLFxuICAgICAgLy8gcmVhZCB1cHRvIGxlbiBieXRlcyBhbmQgc3RvcmUgaW50byBtbWFwJ2QgYnVmZmVyIGF0IGFkZHJlc3NcbiAgICAgIGFkZHIgPSBhZGRyZXNzLnRvTnVtYmVyKCksXG4gICAgICBidWYgPSB0aHJlYWQuZ2V0SlZNKCkuZ2V0SGVhcCgpLmdldF9idWZmZXIoYWRkciwgbGVuKTtcbiAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICBmcy5yZWFkKGZkLCBidWYsIDAsIGxlbiwgbnVsbCwgKGVyciwgYnl0ZXNSZWFkKSA9PiB7XG4gICAgICBpZiAoZXJyKSB7XG4gICAgICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbihcIkxqYXZhL2lvL0lPRXhjZXB0aW9uO1wiLCAnRXJyb3IgcmVhZGluZyBmaWxlOiAnICsgZXJyKTtcbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybihieXRlc1JlYWQpO1xuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAncHJlQ2xvc2UwKExqYXZhL2lvL0ZpbGVEZXNjcmlwdG9yOylWJyh0aHJlYWQ6IEpWTVRocmVhZCwgYXJnMDogSlZNVHlwZXMuamF2YV9pb19GaWxlRGVzY3JpcHRvcik6IHZvaWQge1xuICAgIC8vIE5PUCwgSSB0aGluayB0aGUgYWN0dWFsIGZzLmNsb3NlIGlzIGNhbGxlZCBsYXRlci4gSWYgbm90LCBOQkQuXG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdjbG9zZTAoTGphdmEvaW8vRmlsZURlc2NyaXB0b3I7KVYnKHRocmVhZDogSlZNVGhyZWFkLCBmZE9iajogSlZNVHlwZXMuamF2YV9pb19GaWxlRGVzY3JpcHRvcik6IHZvaWQge1xuICAgIHN1bl9uaW9fY2hfRmlsZURpc3BhdGNoZXJJbXBsWydjbG9zZUludEZEKEkpViddKHRocmVhZCwgZmRPYmpbXCJqYXZhL2lvL0ZpbGVEZXNjcmlwdG9yL2ZkXCJdKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3NpemUwKExqYXZhL2lvL0ZpbGVEZXNjcmlwdG9yOylKJyh0aHJlYWQ6IEpWTVRocmVhZCwgZmRPYmo6IEpWTVR5cGVzLmphdmFfaW9fRmlsZURlc2NyaXB0b3IpOiB2b2lkIHtcbiAgICBsZXQgZmQgPSBmZE9ialtcImphdmEvaW8vRmlsZURlc2NyaXB0b3IvZmRcIl07XG4gICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgZnMuZnN0YXQoZmQsIChlcnIsIHN0YXRzKSA9PiB7XG4gICAgICBpZiAoZXJyKSB7XG4gICAgICAgIHRocm93Tm9kZUVycm9yKHRocmVhZCwgZXJyKTtcbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybihMb25nLmZyb21OdW1iZXIoc3RhdHMuc2l6ZSksIG51bGwpO1xuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAndHJ1bmNhdGUwKExqYXZhL2lvL0ZpbGVEZXNjcmlwdG9yO0opSScodGhyZWFkOiBKVk1UaHJlYWQsIGZkT2JqOiBKVk1UeXBlcy5qYXZhX2lvX0ZpbGVEZXNjcmlwdG9yLCBzaXplOiBMb25nKTogdm9pZCB7XG4gICAgbGV0IGZkID0gZmRPYmpbXCJqYXZhL2lvL0ZpbGVEZXNjcmlwdG9yL2ZkXCJdO1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGZzLmZ0cnVuY2F0ZShmZCwgc2l6ZS50b051bWJlcigpLCAoZXJyKSA9PiB7XG4gICAgICBpZiAoZXJyKSB7XG4gICAgICAgIHRocm93Tm9kZUVycm9yKHRocmVhZCwgZXJyKTtcbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIC8vIEZvciBzb21lIHJlYXNvbiwgdGhpcyBleHBlY3RzIGEgcmV0dXJuIHZhbHVlLlxuICAgICAgICAvLyBHaXZlIGl0IHRoZSBzdWNjZXNzIHN0YXR1cyBjb2RlLlxuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oMCk7XG4gICAgICB9XG4gICAgfSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdjbG9zZUludEZEKEkpVicodGhyZWFkOiBKVk1UaHJlYWQsIGZkOiBudW1iZXIpOiB2b2lkIHtcbiAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICBmcy5jbG9zZShmZCwgKGVycikgPT4ge1xuICAgICAgaWYgKGVycikge1xuICAgICAgICB0aHJvd05vZGVFcnJvcih0aHJlYWQsIGVycik7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oKTtcbiAgICAgIH1cbiAgICB9KTtcbiAgfVxuXG59XG5cbmNsYXNzIERpckZkIHtcbiAgcHJpdmF0ZSBfbGlzdGluZzogc3RyaW5nW107XG4gIHByaXZhdGUgX3BvczogbnVtYmVyID0gMDtcbiAgY29uc3RydWN0b3IobGlzdGluZzogc3RyaW5nW10pIHtcbiAgICB0aGlzLl9saXN0aW5nID0gbGlzdGluZztcbiAgfVxuXG4gIHB1YmxpYyBuZXh0KCk6IHN0cmluZyB7XG4gICAgdmFyIG5leHQgPSB0aGlzLl9saXN0aW5nW3RoaXMuX3BvcysrXTtcbiAgICBpZiAobmV4dCA9PT0gdW5kZWZpbmVkKSB7XG4gICAgICBuZXh0ID0gbnVsbDtcbiAgICB9XG4gICAgcmV0dXJuIG5leHQ7XG4gIH1cbn1cblxuY2xhc3MgRkRNYXA8VD4ge1xuICBwcml2YXRlIHN0YXRpYyBfbmV4dEZkID0gMTtcbiAgcHJpdmF0ZSBfbWFwOiB7W2ZkOiBudW1iZXJdOiBUfSA9IHt9O1xuXG4gIHB1YmxpYyBuZXdFbnRyeShlbnRyeTogVCk6IG51bWJlciB7XG4gICAgdmFyIGZkID0gRkRNYXAuX25leHRGZCsrO1xuICAgIHRoaXMuX21hcFtmZF0gPSBlbnRyeTtcbiAgICByZXR1cm4gZmQ7XG4gIH1cblxuICBwdWJsaWMgcmVtb3ZlRW50cnkodGhyZWFkOiBKVk1UaHJlYWQsIGZkOiBudW1iZXIsIGV4Y2VwdGlvblR5cGU6IHN0cmluZyk6IHZvaWQge1xuICAgIGlmICh0aGlzLl9tYXBbZmRdKSB7XG4gICAgICBkZWxldGUgdGhpcy5fbWFwW2ZkXTtcbiAgICB9IGVsc2Uge1xuICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKGV4Y2VwdGlvblR5cGUsIGBJbnZhbGlkIGZpbGUgZGVzY3JpcHRvcjogJHtmZH1gKTtcbiAgICB9XG4gIH1cblxuICBwdWJsaWMgZ2V0RW50cnkodGhyZWFkOiBKVk1UaHJlYWQsIGV4Y2VwdGlvblR5cGU6IHN0cmluZywgZmQ6IG51bWJlcik6IFQge1xuICAgIHZhciBlbnRyeSA9IHRoaXMuX21hcFtmZF07XG4gICAgaWYgKCFlbnRyeSkge1xuICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKGV4Y2VwdGlvblR5cGUsIGBJbnZhbGlkIGZpbGUgZGVzY3JpcHRvcjogJHtmZH1gKTtcbiAgICAgIHJldHVybiBudWxsO1xuICAgIH0gZWxzZSB7XG4gICAgICByZXR1cm4gZW50cnk7XG4gICAgfVxuICB9XG59XG5cbnZhciBkaXJNYXAgPSBuZXcgRkRNYXA8RGlyRmQ+KCksXG4gIGZpbGVNYXAgPSBuZXcgRkRNYXA8bnVtYmVyPigpO1xuXG5mdW5jdGlvbiBnZXRTdHJpbmdGcm9tSGVhcCh0aHJlYWQ6IEpWTVRocmVhZCwgcHRyTG9uZzogTG9uZyk6IHN0cmluZyB7XG4gIHZhciBoZWFwID0gdGhyZWFkLmdldEpWTSgpLmdldEhlYXAoKSxcbiAgICAgIHB0ciA9IHB0ckxvbmcudG9OdW1iZXIoKSxcbiAgICAgIGxlbiA9IDA7XG4gIHdoaWxlIChoZWFwLmdldF9zaWduZWRfYnl0ZShwdHIgKyBsZW4pICE9PSAwKSB7XG4gICAgbGVuKys7XG4gIH1cbiAgcmV0dXJuIGhlYXAuZ2V0X2J1ZmZlcihwdHIsIGxlbikudG9TdHJpbmcoKTtcbn1cblxuZnVuY3Rpb24gc3RyaW5nVG9CeXRlQXJyYXkodGhyZWFkOiBKVk1UaHJlYWQsIHN0cjogc3RyaW5nKTogSlZNVHlwZXMuSlZNQXJyYXk8bnVtYmVyPiB7XG4gIGlmICghc3RyKSB7XG4gICAgcmV0dXJuIG51bGw7XG4gIH1cblxuICBjb25zdCBidWZmID0gbmV3IEJ1ZmZlcihzdHIsICd1dGY4JyksIGxlbiA9IGJ1ZmYubGVuZ3RoLFxuICAgIGFyciA9IHV0aWwubmV3QXJyYXk8bnVtYmVyPih0aHJlYWQsIHRocmVhZC5nZXRCc0NsKCksICdbQicsIGxlbik7XG4gIGZvciAobGV0IGkgPSAwOyBpIDwgbGVuOyBpKyspIHtcbiAgICBhcnIuYXJyYXlbaV0gPSBidWZmLnJlYWRVSW50OChpKTtcbiAgfVxuICByZXR1cm4gYXJyO1xufVxuXG5mdW5jdGlvbiBjb252ZXJ0RXJyb3IodGhyZWFkOiBKVk1UaHJlYWQsIGVycjogTm9kZUpTLkVycm5vRXhjZXB0aW9uLCBjYjogKGVycjogSlZNVHlwZXMuamF2YV9sYW5nX0V4Y2VwdGlvbikgPT4gdm9pZCk6IHZvaWQge1xuICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgaWYgKGVyci5jb2RlID09PSAnRU5PRU5UJykge1xuICAgIHRocmVhZC5nZXRCc0NsKCkuaW5pdGlhbGl6ZUNsYXNzKHRocmVhZCwgJ0xqYXZhL25pby9maWxlL05vU3VjaEZpbGVFeGNlcHRpb247JywgKG5vU3VjaEZpbGVFeGNlcHRpb24pID0+IHtcbiAgICAgIGNvbnN0IGNvbnMgPSAoPFJlZmVyZW5jZUNsYXNzRGF0YTxKVk1UeXBlcy5qYXZhX25pb19maWxlX05vU3VjaEZpbGVFeGNlcHRpb24+PiBub1N1Y2hGaWxlRXhjZXB0aW9uKS5nZXRDb25zdHJ1Y3Rvcih0aHJlYWQpLFxuICAgICAgcnYgPSBuZXcgY29ucyh0aHJlYWQpO1xuICAgICAgcnZbJzxpbml0PihMamF2YS9sYW5nL1N0cmluZzspViddKHRocmVhZCwgW3V0aWwuaW5pdFN0cmluZyh0aHJlYWQuZ2V0QnNDbCgpLCBlcnIucGF0aCldLCAoZSkgPT4ge1xuICAgICAgICB0aHJlYWQudGhyb3dFeGNlcHRpb24ocnYpO1xuICAgICAgfSk7XG4gICAgfSk7XG4gIH0gZWxzZSBpZiAoZXJyLmNvZGUgPT09ICdFRVhJU1QnKSB7XG4gICAgdGhyZWFkLmdldEJzQ2woKS5pbml0aWFsaXplQ2xhc3ModGhyZWFkLCAnTGphdmEvbmlvL2ZpbGUvRmlsZUFscmVhZHlFeGlzdHNFeGNlcHRpb247JywgKGZpbGVBbHJlYWR5RXhpc3RzRXhjZXB0aW9uKSA9PiB7XG4gICAgICBjb25zdCBjb25zID0gKDxSZWZlcmVuY2VDbGFzc0RhdGE8SlZNVHlwZXMuamF2YV9uaW9fZmlsZV9GaWxlQWxyZWFkeUV4aXN0c0V4Y2VwdGlvbj4+IGZpbGVBbHJlYWR5RXhpc3RzRXhjZXB0aW9uKS5nZXRDb25zdHJ1Y3Rvcih0aHJlYWQpLFxuICAgICAgcnYgPSBuZXcgY29ucyh0aHJlYWQpO1xuICAgICAgcnZbJzxpbml0PihMamF2YS9sYW5nL1N0cmluZzspViddKHRocmVhZCwgW3V0aWwuaW5pdFN0cmluZyh0aHJlYWQuZ2V0QnNDbCgpLCBlcnIucGF0aCldLCAoZSkgPT4ge1xuICAgICAgICBjYihydik7XG4gICAgICB9KTtcbiAgICB9KTtcbiAgfSBlbHNlIHtcbiAgICB0aHJlYWQuZ2V0QnNDbCgpLmluaXRpYWxpemVDbGFzcyh0aHJlYWQsICdMc3VuL25pby9mcy9Vbml4RXhjZXB0aW9uOycsICh1bml4RXhjZXB0aW9uKSA9PiB7XG4gICAgICB0aHJlYWQuZ2V0QnNDbCgpLmluaXRpYWxpemVDbGFzcyh0aHJlYWQsICdMc3VuL25pby9mcy9Vbml4Q29uc3RhbnRzOycsICh1bml4Q29uc3RhbnRzKSA9PiB7XG4gICAgICAgICAgdmFyIGNvbnMgPSAoPFJlZmVyZW5jZUNsYXNzRGF0YTxKVk1UeXBlcy5zdW5fbmlvX2ZzX1VuaXhFeGNlcHRpb24+PiB1bml4RXhjZXB0aW9uKS5nZXRDb25zdHJ1Y3Rvcih0aHJlYWQpLFxuICAgICAgICAgICAgcnYgPSBuZXcgY29ucyh0aHJlYWQpLFxuICAgICAgICAgICAgdW5peENvbnM6IHR5cGVvZiBKVk1UeXBlcy5zdW5fbmlvX2ZzX1VuaXhDb25zdGFudHMgPSA8YW55PiAoPFJlZmVyZW5jZUNsYXNzRGF0YTxKVk1UeXBlcy5zdW5fbmlvX2ZzX1VuaXhDb25zdGFudHM+PiB1bml4Q29uc3RhbnRzKS5nZXRDb25zdHJ1Y3Rvcih0aHJlYWQpLFxuICAgICAgICAgICAgZXJyQ29kZTogbnVtYmVyID0gKDxhbnk+IHVuaXhDb25zKVtgc3VuL25pby9mcy9Vbml4Q29uc3RhbnRzLyR7ZXJyLmNvZGV9YF07XG4gICAgICAgICAgaWYgKHR5cGVvZihlcnJDb2RlKSAhPT0gJ251bWJlcicpIHtcbiAgICAgICAgICAgIGVyckNvZGUgPSAtMTtcbiAgICAgICAgICB9XG4gICAgICAgICAgcnZbJ3N1bi9uaW8vZnMvVW5peEV4Y2VwdGlvbi9lcnJubyddID0gZXJyQ29kZTtcbiAgICAgICAgICBydlsnc3VuL25pby9mcy9Vbml4RXhjZXB0aW9uL21zZyddID0gdXRpbC5pbml0U3RyaW5nKHRocmVhZC5nZXRCc0NsKCksIGVyci5tZXNzYWdlKTtcbiAgICAgICAgICBjYihydik7XG4gICAgICB9KTtcbiAgICB9KTtcbiAgfVxufVxuXG5mdW5jdGlvbiBjb252ZXJ0U3RhdHMoc3RhdHM6IGZzLlN0YXRzLCBqdm1TdGF0czogSlZNVHlwZXMuc3VuX25pb19mc19Vbml4RmlsZUF0dHJpYnV0ZXMpOiB2b2lkIHtcbiAganZtU3RhdHNbJ3N1bi9uaW8vZnMvVW5peEZpbGVBdHRyaWJ1dGVzL3N0X21vZGUnXSA9IHN0YXRzLm1vZGU7XG4gIGp2bVN0YXRzWydzdW4vbmlvL2ZzL1VuaXhGaWxlQXR0cmlidXRlcy9zdF9pbm8nXSA9IExvbmcuZnJvbU51bWJlcihzdGF0cy5pbm8pO1xuICBqdm1TdGF0c1snc3VuL25pby9mcy9Vbml4RmlsZUF0dHJpYnV0ZXMvc3RfZGV2J10gPSBMb25nLmZyb21OdW1iZXIoc3RhdHMuZGV2KTtcbiAganZtU3RhdHNbJ3N1bi9uaW8vZnMvVW5peEZpbGVBdHRyaWJ1dGVzL3N0X3JkZXYnXSA9IExvbmcuZnJvbU51bWJlcihzdGF0cy5yZGV2KTtcbiAganZtU3RhdHNbJ3N1bi9uaW8vZnMvVW5peEZpbGVBdHRyaWJ1dGVzL3N0X25saW5rJ10gPSBzdGF0cy5ubGluaztcbiAganZtU3RhdHNbJ3N1bi9uaW8vZnMvVW5peEZpbGVBdHRyaWJ1dGVzL3N0X3VpZCddID0gc3RhdHMudWlkO1xuICBqdm1TdGF0c1snc3VuL25pby9mcy9Vbml4RmlsZUF0dHJpYnV0ZXMvc3RfZ2lkJ10gPSBzdGF0cy5naWQ7XG4gIGp2bVN0YXRzWydzdW4vbmlvL2ZzL1VuaXhGaWxlQXR0cmlidXRlcy9zdF9zaXplJ10gPSBMb25nLmZyb21OdW1iZXIoc3RhdHMuc2l6ZSk7XG4gIGxldCBhdGltZSA9IGRhdGUyY29tcG9uZW50cyhzdGF0cy5hdGltZSksXG4gICAgbXRpbWUgPSBkYXRlMmNvbXBvbmVudHMoc3RhdHMubXRpbWUpLFxuICAgIGN0aW1lID0gZGF0ZTJjb21wb25lbnRzKHN0YXRzLmN0aW1lKTtcbiAganZtU3RhdHNbJ3N1bi9uaW8vZnMvVW5peEZpbGVBdHRyaWJ1dGVzL3N0X2F0aW1lX3NlYyddID0gTG9uZy5mcm9tTnVtYmVyKGF0aW1lWzBdKTtcbiAganZtU3RhdHNbJ3N1bi9uaW8vZnMvVW5peEZpbGVBdHRyaWJ1dGVzL3N0X2F0aW1lX25zZWMnXSA9IExvbmcuZnJvbU51bWJlcihhdGltZVsxXSk7XG4gIGp2bVN0YXRzWydzdW4vbmlvL2ZzL1VuaXhGaWxlQXR0cmlidXRlcy9zdF9tdGltZV9zZWMnXSA9IExvbmcuZnJvbU51bWJlcihtdGltZVswXSk7XG4gIGp2bVN0YXRzWydzdW4vbmlvL2ZzL1VuaXhGaWxlQXR0cmlidXRlcy9zdF9tdGltZV9uc2VjJ10gPSBMb25nLmZyb21OdW1iZXIobXRpbWVbMV0pO1xuICBqdm1TdGF0c1snc3VuL25pby9mcy9Vbml4RmlsZUF0dHJpYnV0ZXMvc3RfY3RpbWVfc2VjJ10gPSBMb25nLmZyb21OdW1iZXIoY3RpbWVbMF0pO1xuICBqdm1TdGF0c1snc3VuL25pby9mcy9Vbml4RmlsZUF0dHJpYnV0ZXMvc3RfY3RpbWVfbnNlYyddID0gTG9uZy5mcm9tTnVtYmVyKGN0aW1lWzFdKTtcbiAganZtU3RhdHNbJ3N1bi9uaW8vZnMvVW5peEZpbGVBdHRyaWJ1dGVzL3N0X2JpcnRodGltZV9zZWMnXSA9IExvbmcuZnJvbU51bWJlcihNYXRoLmZsb29yKHN0YXRzLmJpcnRodGltZS5nZXRUaW1lKCkgLyAxMDAwKSk7XG59XG5cbmxldCBVbml4Q29uc3RhbnRzOiB0eXBlb2YgSlZNVHlwZXMuc3VuX25pb19mc19Vbml4Q29uc3RhbnRzID0gbnVsbDtcbmZ1bmN0aW9uIGZsYWdUZXN0KGZsYWc6IG51bWJlciwgbWFzazogbnVtYmVyKTogYm9vbGVhbiB7XG4gIHJldHVybiAoZmxhZyAmIG1hc2spID09PSBtYXNrO1xufVxuXG4vKipcbiAqIENvbnZlcnRzIGEgbnVtZXJpY2FsIFVuaXggb3BlbigpIGZsYWcgdG8gYSBOb2RlSlMgc3RyaW5nIG9wZW4oKSBmbGFnLlxuICogUmV0dXJucyBOVUxMIHVwb24gZmFpbHVyZTsgdGhyb3dzIGEgVW5peEV4Y2VwdGlvbiBvbiB0aHJlYWQgd2hlbiB0aGF0IGhhcHBlbnMuXG4gKi9cbmZ1bmN0aW9uIGZsYWcybm9kZWZsYWcodGhyZWFkOiBKVk1UaHJlYWQsIGZsYWc6IG51bWJlcik6IHN0cmluZyB7XG4gIGlmIChVbml4Q29uc3RhbnRzID09PSBudWxsKSB7XG4gICAgbGV0IFVDQ2xzID0gPFJlZmVyZW5jZUNsYXNzRGF0YTxKVk1UeXBlcy5zdW5fbmlvX2ZzX1VuaXhDb25zdGFudHM+PiB0aHJlYWQuZ2V0QnNDbCgpLmdldEluaXRpYWxpemVkQ2xhc3ModGhyZWFkLCAnTHN1bi9uaW8vZnMvVW5peENvbnN0YW50czsnKTtcbiAgICBpZiAoVUNDbHMgPT09IG51bGwpIHtcbiAgICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbihcIkxqYXZhL2xhbmcvSW50ZXJuYWxFcnJvcjtcIiwgXCJVbml4Q29uc3RhbnRzIGlzIG5vdCBpbml0aWFsaXplZD9cIik7XG4gICAgICByZXR1cm4gbnVsbDtcbiAgICB9XG4gICAgVW5peENvbnN0YW50cyA9IDxhbnk+IFVDQ2xzLmdldENvbnN0cnVjdG9yKHRocmVhZCk7XG4gIH1cblxuICBsZXQgc3luYyA9IGZsYWdUZXN0KGZsYWcsIFVuaXhDb25zdGFudHNbJ3N1bi9uaW8vZnMvVW5peENvbnN0YW50cy9PX1NZTkMnXSkgfHwgZmxhZ1Rlc3QoZmxhZywgVW5peENvbnN0YW50c1snc3VuL25pby9mcy9Vbml4Q29uc3RhbnRzL09fRFNZTkMnXSk7XG4gIGxldCBmYWlsSWZFeGlzdHMgPSBmbGFnVGVzdChmbGFnLCBVbml4Q29uc3RhbnRzWydzdW4vbmlvL2ZzL1VuaXhDb25zdGFudHMvT19FWENMJ10gfCBVbml4Q29uc3RhbnRzWydzdW4vbmlvL2ZzL1VuaXhDb25zdGFudHMvT19DUkVBVCddKTtcblxuICBpZiAoZmxhZ1Rlc3QoZmxhZywgVW5peENvbnN0YW50c1snc3VuL25pby9mcy9Vbml4Q29uc3RhbnRzL09fUkRPTkxZJ10pKSB7XG4gICAgLy8gJ3InIC0gT3BlbiBmaWxlIGZvciByZWFkaW5nLiBBbiBleGNlcHRpb24gb2NjdXJzIGlmIHRoZSBmaWxlIGRvZXMgbm90IGV4aXN0LlxuICAgIC8vICdycycgLSBPcGVuIGZpbGUgZm9yIHJlYWRpbmcgaW4gc3luY2hyb25vdXMgbW9kZS4gSW5zdHJ1Y3RzIHRoZSBvcGVyYXRpbmcgc3lzdGVtIHRvIGJ5cGFzcyB0aGUgbG9jYWwgZmlsZSBzeXN0ZW0gY2FjaGUuXG4gICAgcmV0dXJuIHN5bmMgPyAncnMnIDogJ3InO1xuICB9IGVsc2UgaWYgKGZsYWdUZXN0KGZsYWcsIFVuaXhDb25zdGFudHNbJ3N1bi9uaW8vZnMvVW5peENvbnN0YW50cy9PX1dST05MWSddKSkge1xuICAgIGlmIChmbGFnICYgVW5peENvbnN0YW50c1snc3VuL25pby9mcy9Vbml4Q29uc3RhbnRzL09fQVBQRU5EJ10pIHtcbiAgICAgIC8vICdheCcgLSBMaWtlICdhJyBidXQgZmFpbHMgaWYgcGF0aCBleGlzdHMuXG4gICAgICAvLyAnYScgLSBPcGVuIGZpbGUgZm9yIGFwcGVuZGluZy4gVGhlIGZpbGUgaXMgY3JlYXRlZCBpZiBpdCBkb2VzIG5vdCBleGlzdC5cbiAgICAgIHJldHVybiBmYWlsSWZFeGlzdHMgPyAnYXgnIDogJ2EnO1xuICAgIH0gZWxzZSB7XG4gICAgICAvLyAndycgLSBPcGVuIGZpbGUgZm9yIHdyaXRpbmcuIFRoZSBmaWxlIGlzIGNyZWF0ZWQgKGlmIGl0IGRvZXMgbm90IGV4aXN0KSBvciB0cnVuY2F0ZWQgKGlmIGl0IGV4aXN0cykuXG4gICAgICAvLyAnd3gnIC0gTGlrZSAndycgYnV0IGZhaWxzIGlmIHBhdGggZXhpc3RzLlxuICAgICAgcmV0dXJuIGZhaWxJZkV4aXN0cyA/ICd3eCcgOiAndyc7XG4gICAgfVxuICB9IGVsc2UgaWYgKGZsYWdUZXN0KGZsYWcsIFVuaXhDb25zdGFudHNbJ3N1bi9uaW8vZnMvVW5peENvbnN0YW50cy9PX1JEV1InXSkpIHtcbiAgICBpZiAoZmxhZ1Rlc3QoZmxhZywgVW5peENvbnN0YW50c1snc3VuL25pby9mcy9Vbml4Q29uc3RhbnRzL09fQVBQRU5EJ10pKSB7XG4gICAgICAvLyAnYSsnIC0gT3BlbiBmaWxlIGZvciByZWFkaW5nIGFuZCBhcHBlbmRpbmcuIFRoZSBmaWxlIGlzIGNyZWF0ZWQgaWYgaXQgZG9lcyBub3QgZXhpc3QuXG4gICAgICAvLyAnYXgrJyAtIExpa2UgJ2ErJyBidXQgZmFpbHMgaWYgcGF0aCBleGlzdHMuXG4gICAgICByZXR1cm4gZmFpbElmRXhpc3RzID8gJ2F4KycgOiAnYSsnO1xuICAgIH0gZWxzZSBpZiAoZmxhZ1Rlc3QoZmxhZywgVW5peENvbnN0YW50c1snc3VuL25pby9mcy9Vbml4Q29uc3RhbnRzL09fQ1JFQVQnXSkpIHtcbiAgICAgIC8vICd3KycgLSBPcGVuIGZpbGUgZm9yIHJlYWRpbmcgYW5kIHdyaXRpbmcuIFRoZSBmaWxlIGlzIGNyZWF0ZWQgKGlmIGl0IGRvZXMgbm90IGV4aXN0KSBvciB0cnVuY2F0ZWQgKGlmIGl0IGV4aXN0cykuXG4gICAgICAvLyAnd3grJyAtIExpa2UgJ3crJyBidXQgZmFpbHMgaWYgcGF0aCBleGlzdHMuXG4gICAgICByZXR1cm4gZmFpbElmRXhpc3RzID8gJ3d4KycgOiAndysnO1xuICAgIH0gZWxzZSB7XG4gICAgICAvLyAncisnIC0gT3BlbiBmaWxlIGZvciByZWFkaW5nIGFuZCB3cml0aW5nLiBBbiBleGNlcHRpb24gb2NjdXJzIGlmIHRoZSBmaWxlIGRvZXMgbm90IGV4aXN0LlxuICAgICAgLy8gJ3JzKycgLSBPcGVuIGZpbGUgZm9yIHJlYWRpbmcgYW5kIHdyaXRpbmcsIHRlbGxpbmcgdGhlIE9TIHRvIG9wZW4gaXQgc3luY2hyb25vdXNseS5cbiAgICAgIHJldHVybiBzeW5jID8gJ3JzKycgOiAncisnO1xuICAgIH1cbiAgfSBlbHNlIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xzdW4vbmlvL2ZzL1VuaXhFeGNlcHRpb247JywgYEludmFsaWQgb3BlbiBmbGFnOiAke2ZsYWd9LmApO1xuICAgIHJldHVybiBudWxsO1xuICB9XG59XG5cbmZ1bmN0aW9uIHRocm93Tm9kZUVycm9yKHRocmVhZDogSlZNVGhyZWFkLCBlcnI6IE5vZGVKUy5FcnJub0V4Y2VwdGlvbik6IHZvaWQge1xuICBjb252ZXJ0RXJyb3IodGhyZWFkLCBlcnIsIChjb252ZXJ0ZWRFcnIpID0+IHtcbiAgICB0aHJlYWQudGhyb3dFeGNlcHRpb24oY29udmVydGVkRXJyKTtcbiAgfSk7XG59XG5cbi8qKlxuICogQ29udmVydHMgYSBEYXRlIG9iamVjdCBpbnRvIFtzZWNvbmRzLCBuYW5vc2Vjb25kc10uXG4gKi9cbmZ1bmN0aW9uIGRhdGUyY29tcG9uZW50cyhkYXRlOiBEYXRlKTogW251bWJlciwgbnVtYmVyXSB7XG4gIGxldCBkYXRlSW5NcyA9IGRhdGUuZ2V0VGltZSgpO1xuICByZXR1cm4gW01hdGguZmxvb3IoZGF0ZUluTXMgLyAxMDAwKSwgKGRhdGVJbk1zICUgMTAwMCkgKiAxMDAwMDAwXTtcbn1cblxuY2xhc3Mgc3VuX25pb19mc19Vbml4TmF0aXZlRGlzcGF0Y2hlciB7XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0Y3dkKClbQicodGhyZWFkOiBKVk1UaHJlYWQpOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+IHtcbiAgICB2YXIgYnVmZiA9IG5ldyBCdWZmZXIoYCR7cHJvY2Vzcy5jd2QoKX1cXDBgLCAndXRmOCcpLCBsZW4gPSBidWZmLmxlbmd0aCxcbiAgICAgIHJ2ID0gdXRpbC5uZXdBcnJheTxudW1iZXI+KHRocmVhZCwgdGhyZWFkLmdldEJzQ2woKSwgJ1tCJywgbGVuKSxcbiAgICAgIGk6IG51bWJlcjtcblxuICAgIGZvciAoaSA9IDA7IGkgPCBsZW47IGkrKykge1xuICAgICAgcnYuYXJyYXlbaV0gPSBidWZmLnJlYWRJbnQ4KGkpO1xuICAgIH1cblxuICAgIHJldHVybiBydjtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2R1cChJKUknKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBudW1iZXIpOiBudW1iZXIge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gICAgcmV0dXJuIDA7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdvcGVuMChKSUkpSScodGhyZWFkOiBKVk1UaHJlYWQsIHBhdGhBZGRyZXNzOiBMb25nLCBmbGFnczogbnVtYmVyLCBtb2RlOiBudW1iZXIpOiB2b2lkIHtcbiAgICAvLyBFc3NlbnRpYWxseSwgY29udmVydCBvcGVuKCkgYXJncyB0byBmb3BlbigpIGFyZ3MuXG4gICAgbGV0IGZsYWdTdHIgPSBmbGFnMm5vZGVmbGFnKHRocmVhZCwgZmxhZ3MpO1xuICAgIGlmIChmbGFnU3RyICE9PSBudWxsKSB7XG4gICAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICAgIGxldCBwYXRoU3RyID0gZ2V0U3RyaW5nRnJvbUhlYXAodGhyZWFkLCBwYXRoQWRkcmVzcyk7XG4gICAgICBmcy5vcGVuKHBhdGhTdHIsIGZsYWdTdHIsIG1vZGUsIChlcnIsIGZkKSA9PiB7XG4gICAgICAgIGlmIChlcnIpIHtcbiAgICAgICAgICB0aHJvd05vZGVFcnJvcih0aHJlYWQsIGVycik7XG4gICAgICAgIH0gZWxzZSB7XG4gICAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKGZkKTtcbiAgICAgICAgfVxuICAgICAgfSk7XG4gICAgfVxuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnb3BlbmF0MChJSklJKUknKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBudW1iZXIsIGFyZzE6IExvbmcsIGFyZzI6IG51bWJlciwgYXJnMzogbnVtYmVyKTogbnVtYmVyIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICAgIHJldHVybiAwO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnY2xvc2UoSSlWJyh0aHJlYWQ6IEpWTVRocmVhZCwgZmQ6IG51bWJlcik6IHZvaWQge1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGZzLmNsb3NlKGZkLCAoZXJyPykgPT4ge1xuICAgICAgaWYgKGVycikge1xuICAgICAgICB0aHJvd05vZGVFcnJvcih0aHJlYWQsIGVycik7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oKTtcbiAgICAgIH1cbiAgICB9KTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2ZvcGVuMChKSilKJyh0aHJlYWQ6IEpWTVRocmVhZCwgcGF0aEFkZHJlc3M6IExvbmcsIGZsYWdzQWRkcmVzczogTG9uZyk6IHZvaWQge1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGxldCBwYXRoU3RyID0gZ2V0U3RyaW5nRnJvbUhlYXAodGhyZWFkLCBwYXRoQWRkcmVzcyk7XG4gICAgbGV0IGZsYWdzU3RyID0gZ2V0U3RyaW5nRnJvbUhlYXAodGhyZWFkLCBmbGFnc0FkZHJlc3MpO1xuICAgIGZzLm9wZW4ocGF0aFN0ciwgZmxhZ3NTdHIsIChlcnIsIGZkKSA9PiB7XG4gICAgICBpZiAoZXJyKSB7XG4gICAgICAgIHRocm93Tm9kZUVycm9yKHRocmVhZCwgZXJyKTtcbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybihMb25nLmZyb21OdW1iZXIoZmQpLCBudWxsKTtcbiAgICAgIH1cbiAgICB9KTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2ZjbG9zZShKKVYnKHRocmVhZDogSlZNVGhyZWFkLCBmZDogTG9uZyk6IHZvaWQge1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGZzLmNsb3NlKGZkLnRvTnVtYmVyKCksIChlcnI/KSA9PiB7XG4gICAgICBpZiAoZXJyKSB7XG4gICAgICAgIHRocm93Tm9kZUVycm9yKHRocmVhZCwgZXJyKTtcbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybigpO1xuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnbGluazAoSkopVicodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IExvbmcsIGFyZzE6IExvbmcpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAndW5saW5rMChKKVYnKHRocmVhZDogSlZNVGhyZWFkLCBwYXRoQWRkcmVzczogTG9uZyk6IHZvaWQge1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGZzLnVubGluayhnZXRTdHJpbmdGcm9tSGVhcCh0aHJlYWQsIHBhdGhBZGRyZXNzKSwgKGVycikgPT4ge1xuICAgICAgaWYgKGVycikge1xuICAgICAgICB0aHJvd05vZGVFcnJvcih0aHJlYWQsIGVycik7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oKTtcbiAgICAgIH1cbiAgICB9KTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3VubGlua2F0MChJSkkpVicodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IG51bWJlciwgYXJnMTogTG9uZywgYXJnMjogbnVtYmVyKTogdm9pZCB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL1Vuc2F0aXNmaWVkTGlua0Vycm9yOycsICdOYXRpdmUgbWV0aG9kIG5vdCBpbXBsZW1lbnRlZC4nKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ21rbm9kMChKSUopVicodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IExvbmcsIGFyZzE6IG51bWJlciwgYXJnMjogTG9uZyk6IHZvaWQge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdyZW5hbWUwKEpKKVYnKHRocmVhZDogSlZNVGhyZWFkLCBvbGRBZGRyOiBMb25nLCBuZXdBZGRyOiBMb25nKTogdm9pZCB7XG4gICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgZnMucmVuYW1lKGdldFN0cmluZ0Zyb21IZWFwKHRocmVhZCwgb2xkQWRkciksIGdldFN0cmluZ0Zyb21IZWFwKHRocmVhZCwgbmV3QWRkciksIChlcnIpID0+IHtcbiAgICAgIGlmIChlcnIpIHtcbiAgICAgICAgdGhyb3dOb2RlRXJyb3IodGhyZWFkLCBlcnIpO1xuICAgICAgfSBlbHNlIHtcbiAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKCk7XG4gICAgICB9XG4gICAgfSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdyZW5hbWVhdDAoSUpJSilWJyh0aHJlYWQ6IEpWTVRocmVhZCwgYXJnMDogbnVtYmVyLCBhcmcxOiBMb25nLCBhcmcyOiBudW1iZXIsIGFyZzM6IExvbmcpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnbWtkaXIwKEpJKVYnKHRocmVhZDogSlZNVGhyZWFkLCBwYXRoQWRkcjogTG9uZywgbW9kZTogbnVtYmVyKTogdm9pZCB7XG4gICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgZnMubWtkaXIoZ2V0U3RyaW5nRnJvbUhlYXAodGhyZWFkLCBwYXRoQWRkciksIG1vZGUsIChlcnIpID0+IHtcbiAgICAgIGlmIChlcnIpIHtcbiAgICAgICAgdGhyb3dOb2RlRXJyb3IodGhyZWFkLCBlcnIpO1xuICAgICAgfSBlbHNlIHtcbiAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKCk7XG4gICAgICB9XG4gICAgfSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdybWRpcjAoSilWJyh0aHJlYWQ6IEpWTVRocmVhZCwgcGF0aEFkZHI6IExvbmcpOiB2b2lkIHtcbiAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICBmcy5ybWRpcihnZXRTdHJpbmdGcm9tSGVhcCh0aHJlYWQsIHBhdGhBZGRyKSwgKGVycikgPT4ge1xuICAgICAgaWYgKGVycikge1xuICAgICAgICB0aHJvd05vZGVFcnJvcih0aHJlYWQsIGVycik7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oKTtcbiAgICAgIH1cbiAgICB9KTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3JlYWRsaW5rMChKKVtCJyh0aHJlYWQ6IEpWTVRocmVhZCwgcGF0aEFkZHI6IExvbmcpOiB2b2lkIHtcbiAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICBmcy5yZWFkbGluayhnZXRTdHJpbmdGcm9tSGVhcCh0aHJlYWQsIHBhdGhBZGRyKSwgKGVyciwgbGlua1BhdGgpID0+IHtcbiAgICAgIGlmIChlcnIpIHtcbiAgICAgICAgdGhyb3dOb2RlRXJyb3IodGhyZWFkLCBlcnIpO1xuICAgICAgfSBlbHNlIHtcbiAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKHV0aWwuaW5pdENhcnIodGhyZWFkLmdldEJzQ2woKSwgbGlua1BhdGgpKTtcbiAgICAgIH1cbiAgICB9KTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3JlYWxwYXRoMChKKVtCJyh0aHJlYWQ6IEpWTVRocmVhZCwgcGF0aEFkZHJlc3M6IExvbmcpOiB2b2lkIHtcbiAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICBmcy5yZWFscGF0aChnZXRTdHJpbmdGcm9tSGVhcCh0aHJlYWQsIHBhdGhBZGRyZXNzKSwgKGVyciwgcmVzb2x2ZWRQYXRoKSA9PiB7XG4gICAgICBpZiAoZXJyKSB7XG4gICAgICAgIHRocm93Tm9kZUVycm9yKHRocmVhZCwgZXJyKTtcbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybih1dGlsLmluaXRDYXJyKHRocmVhZC5nZXRCc0NsKCksIHJlc29sdmVkUGF0aCkpO1xuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnc3ltbGluazAoSkopVicodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IExvbmcsIGFyZzE6IExvbmcpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnc3RhdDAoSkxzdW4vbmlvL2ZzL1VuaXhGaWxlQXR0cmlidXRlczspVicodGhyZWFkOiBKVk1UaHJlYWQsIHBhdGhBZGRyZXNzOiBMb25nLCBqdm1TdGF0czogSlZNVHlwZXMuc3VuX25pb19mc19Vbml4RmlsZUF0dHJpYnV0ZXMpOiB2b2lkIHtcbiAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICBmcy5zdGF0KGdldFN0cmluZ0Zyb21IZWFwKHRocmVhZCwgcGF0aEFkZHJlc3MpLCAoZXJyLCBzdGF0cykgPT4ge1xuICAgICAgaWYgKGVycikge1xuICAgICAgICB0aHJvd05vZGVFcnJvcih0aHJlYWQsIGVycik7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICBjb252ZXJ0U3RhdHMoc3RhdHMsIGp2bVN0YXRzKTtcbiAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKCk7XG4gICAgICB9XG4gICAgfSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdsc3RhdDAoSkxzdW4vbmlvL2ZzL1VuaXhGaWxlQXR0cmlidXRlczspVicodGhyZWFkOiBKVk1UaHJlYWQsIHBhdGhBZGRyZXNzOiBMb25nLCBqdm1TdGF0czogSlZNVHlwZXMuc3VuX25pb19mc19Vbml4RmlsZUF0dHJpYnV0ZXMpOiB2b2lkIHtcbiAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICBmcy5sc3RhdChnZXRTdHJpbmdGcm9tSGVhcCh0aHJlYWQsIHBhdGhBZGRyZXNzKSwgKGVyciwgc3RhdHMpID0+IHtcbiAgICAgIGlmIChlcnIpIHtcbiAgICAgICAgdGhyb3dOb2RlRXJyb3IodGhyZWFkLCBlcnIpO1xuICAgICAgfSBlbHNlIHtcbiAgICAgICAgY29udmVydFN0YXRzKHN0YXRzLCBqdm1TdGF0cyk7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybigpO1xuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZnN0YXQoSUxzdW4vbmlvL2ZzL1VuaXhGaWxlQXR0cmlidXRlczspVicodGhyZWFkOiBKVk1UaHJlYWQsIGZkOiBudW1iZXIsIGp2bVN0YXRzOiBKVk1UeXBlcy5zdW5fbmlvX2ZzX1VuaXhGaWxlQXR0cmlidXRlcyk6IHZvaWQge1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGZzLmZzdGF0KGZkLCAoZXJyLCBzdGF0cykgPT4ge1xuICAgICAgaWYgKGVycikge1xuICAgICAgICB0aHJvd05vZGVFcnJvcih0aHJlYWQsIGVycik7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICBjb252ZXJ0U3RhdHMoc3RhdHMsIGp2bVN0YXRzKTtcbiAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKCk7XG4gICAgICB9XG4gICAgfSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdmc3RhdGF0MChJSklMc3VuL25pby9mcy9Vbml4RmlsZUF0dHJpYnV0ZXM7KVYnKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBudW1iZXIsIGFyZzE6IExvbmcsIGFyZzI6IG51bWJlciwgYXJnMzogSlZNVHlwZXMuc3VuX25pb19mc19Vbml4RmlsZUF0dHJpYnV0ZXMpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnY2hvd24wKEpJSSlWJyh0aHJlYWQ6IEpWTVRocmVhZCwgYXJnMDogTG9uZywgYXJnMTogbnVtYmVyLCBhcmcyOiBudW1iZXIpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnbGNob3duMChKSUkpVicodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IExvbmcsIGFyZzE6IG51bWJlciwgYXJnMjogbnVtYmVyKTogdm9pZCB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL1Vuc2F0aXNmaWVkTGlua0Vycm9yOycsICdOYXRpdmUgbWV0aG9kIG5vdCBpbXBsZW1lbnRlZC4nKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2ZjaG93bihJSUkpVicodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IG51bWJlciwgYXJnMTogbnVtYmVyLCBhcmcyOiBudW1iZXIpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnY2htb2QwKEpJKVYnKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBMb25nLCBhcmcxOiBudW1iZXIpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZmNobW9kKElJKVYnKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBudW1iZXIsIGFyZzE6IG51bWJlcik6IHZvaWQge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICd1dGltZXMwKEpKSilWJyh0aHJlYWQ6IEpWTVRocmVhZCwgYXJnMDogTG9uZywgYXJnMTogTG9uZywgYXJnMjogTG9uZyk6IHZvaWQge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdmdXRpbWVzKElKSilWJyh0aHJlYWQ6IEpWTVRocmVhZCwgYXJnMDogbnVtYmVyLCBhcmcxOiBMb25nLCBhcmcyOiBMb25nKTogdm9pZCB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL1Vuc2F0aXNmaWVkTGlua0Vycm9yOycsICdOYXRpdmUgbWV0aG9kIG5vdCBpbXBsZW1lbnRlZC4nKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ29wZW5kaXIwKEopSicodGhyZWFkOiBKVk1UaHJlYWQsIHB0cjogTG9uZyk6IHZvaWQge1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGZzLnJlYWRkaXIoZ2V0U3RyaW5nRnJvbUhlYXAodGhyZWFkLCBwdHIpLCAoZXJyLCBmaWxlcykgPT4ge1xuICAgICAgaWYgKGVycikge1xuICAgICAgICBjb252ZXJ0RXJyb3IodGhyZWFkLCBlcnIsIChlcnJPYmopID0+IHtcbiAgICAgICAgICB0aHJlYWQudGhyb3dFeGNlcHRpb24oZXJyT2JqKTtcbiAgICAgICAgfSk7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oTG9uZy5mcm9tTnVtYmVyKGRpck1hcC5uZXdFbnRyeShuZXcgRGlyRmQoZmlsZXMpKSksIG51bGwpO1xuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZmRvcGVuZGlyKEkpSicodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IG51bWJlcik6IExvbmcge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gICAgcmV0dXJuIG51bGw7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdjbG9zZWRpcihKKVYnKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBMb25nKTogdm9pZCB7XG4gICAgZGlyTWFwLnJlbW92ZUVudHJ5KHRocmVhZCwgYXJnMC50b051bWJlcigpLCAnTHN1bi9uaW8vZnMvVW5peEV4Y2VwdGlvbjsnKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3JlYWRkaXIoSilbQicodGhyZWFkOiBKVk1UaHJlYWQsIGZkOiBMb25nKTogSlZNVHlwZXMuSlZNQXJyYXk8bnVtYmVyPiB7XG4gICAgdmFyIGRpckZkID0gZGlyTWFwLmdldEVudHJ5KHRocmVhZCwgJ0xzdW4vbmlvL2ZzL1VuaXhFeGNlcHRpb247JywgZmQudG9OdW1iZXIoKSk7XG4gICAgaWYgKGRpckZkKSB7XG4gICAgICByZXR1cm4gc3RyaW5nVG9CeXRlQXJyYXkodGhyZWFkLCBkaXJGZC5uZXh0KCkpO1xuICAgIH1cbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3JlYWQoSUpJKUknKHRocmVhZDogSlZNVGhyZWFkLCBmZDogbnVtYmVyLCBidWY6IExvbmcsIG5ieXRlOiBudW1iZXIpOiB2b2lkIHtcbiAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICBsZXQgYnVmZiA9IHRocmVhZC5nZXRKVk0oKS5nZXRIZWFwKCkuZ2V0X2J1ZmZlcihidWYudG9OdW1iZXIoKSwgbmJ5dGUpO1xuICAgIGZzLnJlYWQoZmQsIGJ1ZmYsIDAsIG5ieXRlLCBudWxsLCAoZXJyLCBieXRlc1JlYWQpID0+IHtcbiAgICAgIGlmIChlcnIpIHtcbiAgICAgICAgdGhyb3dOb2RlRXJyb3IodGhyZWFkLCBlcnIpO1xuICAgICAgfSBlbHNlIHtcbiAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKGJ5dGVzUmVhZCk7XG4gICAgICB9XG4gICAgfSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICd3cml0ZShJSkkpSScodGhyZWFkOiBKVk1UaHJlYWQsIGZkOiBudW1iZXIsIGJ1ZjogTG9uZywgbmJ5dGU6IG51bWJlcik6IHZvaWQge1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGxldCBidWZmID0gdGhyZWFkLmdldEpWTSgpLmdldEhlYXAoKS5nZXRfYnVmZmVyKGJ1Zi50b051bWJlcigpLCBuYnl0ZSk7XG4gICAgZnMud3JpdGUoZmQsIGJ1ZmYsIDAsIG5ieXRlLCBudWxsLCAoZXJyLCBieXRlc1dyaXR0ZW4pID0+IHtcbiAgICAgIGlmIChlcnIpIHtcbiAgICAgICAgdGhyb3dOb2RlRXJyb3IodGhyZWFkLCBlcnIpO1xuICAgICAgfSBlbHNlIHtcbiAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKGJ5dGVzV3JpdHRlbik7XG4gICAgICB9XG4gICAgfSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdhY2Nlc3MwKEpJKVYnKHRocmVhZDogSlZNVGhyZWFkLCBwYXRoQWRkcmVzczogTG9uZywgYXJnMTogbnVtYmVyKTogdm9pZCB7XG4gICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgLy8gVE9ETzogTmVlZCB0byBjaGVjayBzcGVjaWZpYyBmbGFnc1xuICAgIGNvbnN0IHBhdGhTdHJpbmcgPSBnZXRTdHJpbmdGcm9tSGVhcCh0aHJlYWQsIHBhdGhBZGRyZXNzKTtcbiAgICAvLyBUT0RPOiBmcy5hY2Nlc3MoKSBpcyBiZXR0ZXIgYnV0IG5vdCBjdXJyZW50bHkgc3VwcG9ydGVkIGluIGJyb3dzZXJmczogaHR0cHM6Ly9naXRodWIuY29tL2p2aWxrL0Jyb3dzZXJGUy9pc3N1ZXMvMTI4XG4gICAgY29uc3QgY2hlY2tlciA9IHV0aWwuYXJlX2luX2Jyb3dzZXIoKSA/IGZzLnN0YXQgOiBmcy5hY2Nlc3M7XG4gICAgY2hlY2tlcihwYXRoU3RyaW5nLCAoZXJyLCBzdGF0KSA9PiB7XG4gICAgICBpZiAoZXJyKSB7XG4gICAgICAgIHRocm93Tm9kZUVycm9yKHRocmVhZCwgZXJyKTtcbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybigpO1xuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0cHd1aWQoSSlbQicodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IG51bWJlcik6IEpWTVR5cGVzLkpWTUFycmF5PG51bWJlcj4ge1xuICAgIC8vIE1ha2Ugc29tZXRoaW5nIHVwLlxuICAgIHJldHVybiB1dGlsLmluaXRDYXJyKHRocmVhZC5nZXRCc0NsKCksICdkb3BwaW8nKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2dldGdyZ2lkKEkpW0InKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBudW1iZXIpOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+IHtcbiAgICAvLyBNYWtlIHNvbWV0aGluZyB1cC5cbiAgICByZXR1cm4gdXRpbC5pbml0Q2Fycih0aHJlYWQuZ2V0QnNDbCgpLCAnZG9wcGlvJyk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdnZXRwd25hbTAoSilJJyh0aHJlYWQ6IEpWTVRocmVhZCwgYXJnMDogTG9uZyk6IG51bWJlciB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL1Vuc2F0aXNmaWVkTGlua0Vycm9yOycsICdOYXRpdmUgbWV0aG9kIG5vdCBpbXBsZW1lbnRlZC4nKTtcbiAgICByZXR1cm4gMDtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2dldGdybmFtMChKKUknKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBMb25nKTogbnVtYmVyIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICAgIHJldHVybiAwO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnc3RhdHZmczAoSkxzdW4vbmlvL2ZzL1VuaXhGaWxlU3RvcmVBdHRyaWJ1dGVzOylWJyh0aHJlYWQ6IEpWTVRocmVhZCwgYXJnMDogTG9uZywgYXJnMTogSlZNVHlwZXMuc3VuX25pb19mc19Vbml4RmlsZVN0b3JlQXR0cmlidXRlcyk6IHZvaWQge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdwYXRoY29uZjAoSkkpSicodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IExvbmcsIGFyZzE6IG51bWJlcik6IExvbmcge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gICAgcmV0dXJuIG51bGw7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdmcGF0aGNvbmYoSUkpSicodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IG51bWJlciwgYXJnMTogbnVtYmVyKTogTG9uZyB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL1Vuc2F0aXNmaWVkTGlua0Vycm9yOycsICdOYXRpdmUgbWV0aG9kIG5vdCBpbXBsZW1lbnRlZC4nKTtcbiAgICByZXR1cm4gbnVsbDtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3N0cmVycm9yKEkpW0InKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBudW1iZXIpOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+IHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICAgIHJldHVybiBudWxsO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnaW5pdCgpSScodGhyZWFkOiBKVk1UaHJlYWQpOiBudW1iZXIge1xuICAgIHJldHVybiAwO1xuICB9XG5cbn1cblxucmVnaXN0ZXJOYXRpdmVzKHtcbiAgJ3N1bi9uaW8vY2gvRmlsZUNoYW5uZWxJbXBsJzogc3VuX25pb19jaF9GaWxlQ2hhbm5lbEltcGwsXG4gICdzdW4vbmlvL2NoL05hdGl2ZVRocmVhZCc6IHN1bl9uaW9fY2hfTmF0aXZlVGhyZWFkLFxuICAnc3VuL25pby9jaC9JT1V0aWwnOiBzdW5fbmlvX2NoX0lPVXRpbCxcbiAgJ3N1bi9uaW8vY2gvRmlsZURpc3BhdGNoZXJJbXBsJzogc3VuX25pb19jaF9GaWxlRGlzcGF0Y2hlckltcGwsXG4gICdzdW4vbmlvL2ZzL1VuaXhOYXRpdmVEaXNwYXRjaGVyJzogc3VuX25pb19mc19Vbml4TmF0aXZlRGlzcGF0Y2hlclxufSk7XG4iXX0=