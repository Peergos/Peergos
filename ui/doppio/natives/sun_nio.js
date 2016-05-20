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
//# sourceMappingURL=sun_nio.js.map