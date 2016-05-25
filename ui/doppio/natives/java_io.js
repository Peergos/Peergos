'use strict';
var fs = require('fs');
var path = require('path');
var Doppio = require('../doppiojvm');
var util = Doppio.VM.Util;
var ThreadStatus = Doppio.VM.Enums.ThreadStatus;
var Long = Doppio.VM.Long;
function throwNodeError(thread, err) {
    var type = 'Ljava/io/IOException;';
    if (err.code === 'ENOENT') {
        type = 'Ljava/io/FileNotFoundException;';
    }
    thread.throwNewException(type, err.message);
}
function async_input(n_bytes, resume) {
    var read = function (nBytes) {
            var bytes = process.stdin.read(nBytes);
            if (bytes === null) {
                bytes = process.stdin.read();
            }
            if (bytes !== null && bytes.length === 1 && bytes.readUInt8(0) === 0) {
                bytes = new Buffer(0);
            }
            return bytes;
        }, bytes = read(n_bytes);
    if (bytes === null) {
        process.stdin.once('readable', function (data) {
            var bytes = read(n_bytes);
            if (bytes === null) {
                bytes = new Buffer(0);
            }
            resume(bytes);
        });
    } else {
        setImmediate(function () {
            resume(bytes);
        });
    }
}
function statFile(fname, cb) {
    fs.stat(fname, function (err, stat) {
        if (err != null) {
            cb(null);
        } else {
            cb(stat);
        }
    });
}
var java_io_Console = function () {
    function java_io_Console() {
    }
    java_io_Console['encoding()Ljava/lang/String;'] = function (thread) {
        return null;
    };
    java_io_Console['echo(Z)Z'] = function (thread, echoOn) {
        var echoOff = !echoOn;
        process.stdin.setRawMode(echoOff);
        return echoOff;
    };
    java_io_Console['istty()Z'] = function (thread) {
        return process.stdout.isTTY;
    };
    return java_io_Console;
}();
var java_io_FileDescriptor = function () {
    function java_io_FileDescriptor() {
    }
    java_io_FileDescriptor['sync()V'] = function (thread, javaThis) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_io_FileDescriptor['initIDs()V'] = function (thread) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    return java_io_FileDescriptor;
}();
var java_io_FileInputStream = function () {
    function java_io_FileInputStream() {
    }
    java_io_FileInputStream['open0(Ljava/lang/String;)V'] = function (thread, javaThis, filename) {
        var filepath = filename.toString();
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.open(filepath, 'r', function (e, fd) {
            if (e != null) {
                if (e.code === 'ENOENT') {
                    thread.throwNewException('Ljava/io/FileNotFoundException;', '' + filepath + ' (No such file or directory)');
                } else {
                    thread.throwNewException('Ljava/lang/InternalError', 'Internal JVM error: ' + e);
                }
            } else {
                var fdObj = javaThis['java/io/FileInputStream/fd'];
                fdObj['java/io/FileDescriptor/fd'] = fd;
                fdObj.$pos = 0;
                thread.asyncReturn();
            }
        });
    };
    java_io_FileInputStream['read0()I'] = function (thread, javaThis) {
        var fdObj = javaThis['java/io/FileInputStream/fd'], fd = fdObj['java/io/FileDescriptor/fd'];
        if (-1 === fd) {
            thread.throwNewException('Ljava/io/IOException;', 'Bad file descriptor');
        } else if (0 !== fd) {
            thread.setStatus(ThreadStatus.ASYNC_WAITING);
            var buf = new Buffer(1);
            fs.read(fd, buf, 0, 1, fdObj.$pos, function (err, bytes_read) {
                if (err) {
                    return throwNodeError(thread, err);
                }
                fdObj.$pos++;
                thread.asyncReturn(0 === bytes_read ? -1 : buf.readUInt8(0));
            });
        } else {
            thread.setStatus(ThreadStatus.ASYNC_WAITING);
            async_input(1, function (byte) {
                thread.asyncReturn(0 === byte.length ? -1 : byte.readUInt8(0));
            });
        }
    };
    java_io_FileInputStream['readBytes([BII)I'] = function (thread, javaThis, byteArr, offset, nBytes) {
        var buf, pos, fdObj = javaThis['java/io/FileInputStream/fd'], fd = fdObj['java/io/FileDescriptor/fd'];
        if (offset + nBytes > byteArr.array.length) {
            thread.throwNewException('Ljava/lang/IndexOutOfBoundsException;', '');
            return;
        }
        if (nBytes === 0) {
            return 0;
        } else if (-1 === fd) {
            thread.throwNewException('Ljava/io/IOException;', 'Bad file descriptor');
        } else if (0 !== fd) {
            pos = fdObj.$pos;
            buf = new Buffer(nBytes);
            thread.setStatus(ThreadStatus.ASYNC_WAITING);
            fs.read(fd, buf, 0, nBytes, pos, function (err, bytesRead) {
                if (null != err) {
                    throwNodeError(thread, err);
                } else {
                    fdObj.$pos += bytesRead;
                    for (var i = 0; i < bytesRead; i++) {
                        byteArr.array[offset + i] = buf.readInt8(i);
                    }
                    thread.asyncReturn(0 === bytesRead ? -1 : bytesRead);
                }
            });
        } else {
            thread.setStatus(ThreadStatus.ASYNC_WAITING);
            async_input(nBytes, function (bytes) {
                var b, idx;
                for (idx = 0; idx < bytes.length; idx++) {
                    b = bytes.readUInt8(idx);
                    byteArr.array[offset + idx] = b;
                }
                thread.asyncReturn(bytes.length === 0 ? -1 : bytes.length);
            });
        }
    };
    java_io_FileInputStream['skip(J)J'] = function (thread, javaThis, nBytes) {
        var fdObj = javaThis['java/io/FileInputStream/fd'];
        var fd = fdObj['java/io/FileDescriptor/fd'];
        if (-1 === fd) {
            thread.throwNewException('Ljava/io/IOException;', 'Bad file descriptor');
        } else if (0 !== fd) {
            thread.setStatus(ThreadStatus.ASYNC_WAITING);
            fs.fstat(fd, function (err, stats) {
                if (err) {
                    return throwNodeError(thread, err);
                }
                var bytesLeft = stats.size - fdObj.$pos, toSkip = Math.min(nBytes.toNumber(), bytesLeft);
                fdObj.$pos += toSkip;
                thread.asyncReturn(Long.fromNumber(toSkip), null);
            });
        } else {
            thread.setStatus(ThreadStatus.ASYNC_WAITING);
            async_input(nBytes.toNumber(), function (bytes) {
                thread.asyncReturn(Long.fromNumber(bytes.length), null);
            });
        }
    };
    java_io_FileInputStream['available()I'] = function (thread, javaThis) {
        var fdObj = javaThis['java/io/FileInputStream/fd'], fd = fdObj['java/io/FileDescriptor/fd'];
        if (fd === -1) {
            thread.throwNewException('Ljava/io/IOException;', 'Bad file descriptor');
        } else if (fd === 0) {
            return 0;
        } else {
            thread.setStatus(ThreadStatus.ASYNC_WAITING);
            fs.fstat(fd, function (err, stats) {
                if (err) {
                    return throwNodeError(thread, err);
                }
                thread.asyncReturn(stats.size - fdObj.$pos);
            });
        }
    };
    java_io_FileInputStream['initIDs()V'] = function (thread) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_io_FileInputStream['close0()V'] = function (thread, javaThis) {
        var fdObj = javaThis['java/io/FileInputStream/fd'], fd = fdObj['java/io/FileDescriptor/fd'];
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.close(fd, function (err) {
            if (err) {
                throwNodeError(thread, err);
            } else {
                fdObj['java/io/FileDescriptor/fd'] = -1;
                thread.asyncReturn();
            }
        });
    };
    return java_io_FileInputStream;
}();
var java_io_FileOutputStream = function () {
    function java_io_FileOutputStream() {
    }
    java_io_FileOutputStream['open0(Ljava/lang/String;Z)V'] = function (thread, javaThis, name, append) {
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.open(name.toString(), append ? 'a' : 'w', function (err, fd) {
            if (err) {
                return throwNodeError(thread, err);
            }
            var fdObj = javaThis['java/io/FileOutputStream/fd'];
            fdObj['java/io/FileDescriptor/fd'] = fd;
            fs.fstat(fd, function (err, stats) {
                fdObj.$pos = stats.size;
                thread.asyncReturn();
            });
        });
    };
    java_io_FileOutputStream['write(IZ)V'] = function (thread, javaThis, b, append) {
        java_io_FileOutputStream['writeBytes([BIIZ)V'](thread, javaThis, { array: [b] }, 0, 1, append);
    };
    java_io_FileOutputStream['writeBytes([BIIZ)V'] = function (thread, javaThis, bytes, offset, len, append) {
        var buf = new Buffer(bytes.array), fdObj = javaThis['java/io/FileOutputStream/fd'], fd = fdObj['java/io/FileDescriptor/fd'];
        if (fd === -1) {
            thread.throwNewException('Ljava/io/IOException;', 'Bad file descriptor');
        } else if (fd !== 1 && fd !== 2) {
            thread.setStatus(ThreadStatus.ASYNC_WAITING);
            fs.write(fd, buf, offset, len, fdObj.$pos, function (err, numBytes) {
                if (err) {
                    return throwNodeError(thread, err);
                }
                fdObj.$pos += numBytes;
                thread.asyncReturn();
            });
        } else {
            var output = buf.toString('utf8', offset, offset + len);
            if (fd === 1) {
                process.stdout.write(output);
            } else if (fd === 2) {
                process.stderr.write(output);
            }
            thread.setStatus(ThreadStatus.ASYNC_WAITING);
            setImmediate(function () {
                thread.asyncReturn();
            });
        }
    };
    java_io_FileOutputStream['close0()V'] = function (thread, javaThis) {
        var fdObj = javaThis['java/io/FileOutputStream/fd'], fd = fdObj['java/io/FileDescriptor/fd'];
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.close(fd, function (err) {
            if (err) {
                return throwNodeError(thread, err);
            } else {
                fdObj['java/io/FileDescriptor/fd'] = -1;
                thread.asyncReturn();
            }
        });
    };
    java_io_FileOutputStream['initIDs()V'] = function (thread) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    return java_io_FileOutputStream;
}();
var java_io_ObjectInputStream = function () {
    function java_io_ObjectInputStream() {
    }
    java_io_ObjectInputStream['bytesToFloats([BI[FII)V'] = function (thread, arg0, arg1, arg2, arg3, arg4) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_io_ObjectInputStream['bytesToDoubles([BI[DII)V'] = function (thread, arg0, arg1, arg2, arg3, arg4) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    return java_io_ObjectInputStream;
}();
var java_io_ObjectOutputStream = function () {
    function java_io_ObjectOutputStream() {
    }
    java_io_ObjectOutputStream['floatsToBytes([FI[BII)V'] = function (thread, arg0, arg1, arg2, arg3, arg4) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_io_ObjectOutputStream['doublesToBytes([DI[BII)V'] = function (thread, arg0, arg1, arg2, arg3, arg4) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    return java_io_ObjectOutputStream;
}();
var java_io_ObjectStreamClass = function () {
    function java_io_ObjectStreamClass() {
    }
    java_io_ObjectStreamClass['initNative()V'] = function (thread) {
    };
    java_io_ObjectStreamClass['hasStaticInitializer(Ljava/lang/Class;)Z'] = function (thread, jco) {
        return jco.$cls.getMethod('<clinit>()V') !== null;
    };
    return java_io_ObjectStreamClass;
}();
var java_io_RandomAccessFile = function () {
    function java_io_RandomAccessFile() {
    }
    java_io_RandomAccessFile['open0(Ljava/lang/String;I)V'] = function (thread, javaThis, filename, mode) {
        var filepath = filename.toString(), rafStatics = javaThis.getClass().getConstructor(thread), modeStr;
        switch (mode) {
        case rafStatics['java/io/RandomAccessFile/O_RDONLY']:
            modeStr = 'r';
            break;
        case rafStatics['java/io/RandomAccessFile/O_RDWR']:
            modeStr = 'r+';
            break;
        case rafStatics['java/io/RandomAccessFile/O_SYNC']:
        case rafStatics['java/io/RandomAccessFile/O_DSYNC']:
            modeStr = 'rs+';
            break;
        }
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.open(filepath, modeStr, function (e, fd) {
            if (e) {
                return throwNodeError(thread, e);
            } else {
                var fdObj = javaThis['java/io/RandomAccessFile/fd'];
                fdObj['java/io/FileDescriptor/fd'] = fd;
                fdObj.$pos = 0;
                thread.asyncReturn();
            }
        });
    };
    java_io_RandomAccessFile['read0()I'] = function (thread, javaThis) {
        var fdObj = javaThis['java/io/RandomAccessFile/fd'], fd = fdObj['java/io/FileDescriptor/fd'], buf = new Buffer(1);
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.read(fd, buf, 0, 1, fdObj.$pos, function (err, bytesRead) {
            if (err) {
                return throwNodeError(thread, err);
            } else {
                fdObj.$pos += bytesRead;
                thread.asyncReturn(bytesRead === 0 ? -1 : buf.readUInt8(0));
            }
        });
    };
    java_io_RandomAccessFile['readBytes([BII)I'] = function (thread, javaThis, byte_arr, offset, len) {
        var fdObj = javaThis['java/io/RandomAccessFile/fd'], fd = fdObj['java/io/FileDescriptor/fd'], buf = new Buffer(len);
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.read(fd, buf, 0, len, fdObj.$pos, function (err, bytesRead) {
            if (err) {
                return throwNodeError(thread, err);
            } else {
                for (var i = 0; i < bytesRead; i++) {
                    byte_arr.array[offset + i] = buf.readInt8(i);
                }
                fdObj.$pos += bytesRead;
                thread.asyncReturn(0 === bytesRead && 0 !== len ? -1 : bytesRead);
            }
        });
    };
    java_io_RandomAccessFile['write0(I)V'] = function (thread, javaThis, value) {
        var fdObj = javaThis['java/io/RandomAccessFile/fd'];
        var fd = fdObj['java/io/FileDescriptor/fd'];
        var data = new Buffer(1);
        data.writeInt8(value, 0);
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.write(fd, data, 0, 1, fdObj.$pos, function (err, numBytes) {
            if (err) {
                return throwNodeError(thread, err);
            }
            fdObj.$pos += numBytes;
            thread.asyncReturn();
        });
    };
    java_io_RandomAccessFile['writeBytes([BII)V'] = function (thread, javaThis, byteArr, offset, len) {
        var fdObj = javaThis['java/io/RandomAccessFile/fd'], fd = fdObj['java/io/FileDescriptor/fd'], buf = new Buffer(byteArr.array);
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.write(fd, buf, offset, len, fdObj.$pos, function (err, numBytes) {
            if (err) {
                return throwNodeError(thread, err);
            }
            fdObj.$pos += numBytes;
            thread.asyncReturn();
        });
    };
    java_io_RandomAccessFile['getFilePointer()J'] = function (thread, javaThis) {
        return Long.fromNumber(javaThis['java/io/RandomAccessFile/fd'].$pos);
    };
    java_io_RandomAccessFile['seek0(J)V'] = function (thread, javaThis, pos) {
        javaThis['java/io/RandomAccessFile/fd'].$pos = pos.toNumber();
    };
    java_io_RandomAccessFile['length()J'] = function (thread, javaThis) {
        var fdObj = javaThis['java/io/RandomAccessFile/fd'], fd = fdObj['java/io/FileDescriptor/fd'];
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.fstat(fd, function (err, stats) {
            if (err) {
                return throwNodeError(thread, err);
            }
            thread.asyncReturn(Long.fromNumber(stats.size), null);
        });
    };
    java_io_RandomAccessFile['setLength(J)V'] = function (thread, javaThis, arg0) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_io_RandomAccessFile['initIDs()V'] = function (thread) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_io_RandomAccessFile['close0()V'] = function (thread, javaThis) {
        var fdObj = javaThis['java/io/RandomAccessFile/fd'], fd = fdObj['java/io/FileDescriptor/fd'];
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.close(fd, function (err) {
            if (err) {
                return throwNodeError(thread, err);
            } else {
                fdObj['java/io/FileDescriptor/fd'] = -1;
                thread.asyncReturn();
            }
        });
    };
    return java_io_RandomAccessFile;
}();
var java_io_UnixFileSystem = function () {
    function java_io_UnixFileSystem() {
    }
    java_io_UnixFileSystem['canonicalize0(Ljava/lang/String;)Ljava/lang/String;'] = function (thread, javaThis, jvmPathStr) {
        var jsStr = jvmPathStr.toString();
        return util.initString(thread.getBsCl(), path.resolve(path.normalize(jsStr)));
    };
    java_io_UnixFileSystem['getBooleanAttributes0(Ljava/io/File;)I'] = function (thread, javaThis, file) {
        var filepath = file['java/io/File/path'], fileSystem = thread.getBsCl().getInitializedClass(thread, 'Ljava/io/FileSystem;').getConstructor(thread);
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        statFile(filepath.toString(), function (stats) {
            var rv = 0;
            if (stats !== null) {
                rv |= fileSystem['java/io/FileSystem/BA_EXISTS'];
                if (stats.isFile()) {
                    rv |= fileSystem['java/io/FileSystem/BA_REGULAR'];
                } else if (stats.isDirectory()) {
                    rv |= fileSystem['java/io/FileSystem/BA_DIRECTORY'];
                }
            }
            thread.asyncReturn(rv);
        });
    };
    java_io_UnixFileSystem['checkAccess(Ljava/io/File;I)Z'] = function (thread, javaThis, file, access) {
        var filepath = file['java/io/File/path'];
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        statFile(filepath.toString(), function (stats) {
            if (stats == null) {
                thread.asyncReturn(0);
            } else {
                var mask = access | access << 3 | access << 6;
                thread.asyncReturn((stats.mode & mask) > 0 ? 1 : 0);
            }
        });
    };
    java_io_UnixFileSystem['getLastModifiedTime(Ljava/io/File;)J'] = function (thread, javaThis, file) {
        var filepath = file['java/io/File/path'];
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        statFile(filepath.toString(), function (stats) {
            if (stats == null) {
                thread.asyncReturn(Long.ZERO, null);
            } else {
                thread.asyncReturn(Long.fromNumber(stats.mtime.getTime()), null);
            }
        });
    };
    java_io_UnixFileSystem['getLength(Ljava/io/File;)J'] = function (thread, javaThis, file) {
        var filepath = file['java/io/File/path'];
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.stat(filepath.toString(), function (err, stat) {
            thread.asyncReturn(err != null ? Long.ZERO : Long.fromNumber(stat.size), null);
        });
    };
    java_io_UnixFileSystem['setPermission(Ljava/io/File;IZZ)Z'] = function (thread, javaThis, file, access, enable, owneronly) {
        var filepath = file['java/io/File/path'].toString();
        if (owneronly) {
            access <<= 6;
        } else {
            access |= access << 6 | access << 3;
        }
        if (!enable) {
            access = ~access;
        }
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        statFile(filepath, function (stats) {
            if (stats == null) {
                thread.asyncReturn(0);
            } else {
                var existing_access = stats.mode;
                access = enable ? existing_access | access : existing_access & access;
                fs.chmod(filepath, access, function (err) {
                    thread.asyncReturn(err != null ? 0 : 1);
                });
            }
        });
    };
    java_io_UnixFileSystem['createFileExclusively(Ljava/lang/String;)Z'] = function (thread, javaThis, path) {
        var filepath = path.toString();
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        statFile(filepath, function (stat) {
            if (stat != null) {
                thread.asyncReturn(0);
            } else {
                fs.open(filepath, 'w', function (err, fd) {
                    if (err != null) {
                        thread.throwNewException('Ljava/io/IOException;', err.message);
                    } else {
                        fs.close(fd, function (err) {
                            if (err != null) {
                                thread.throwNewException('Ljava/io/IOException;', err.message);
                            } else {
                                thread.asyncReturn(1);
                            }
                        });
                    }
                });
            }
        });
    };
    java_io_UnixFileSystem['delete0(Ljava/io/File;)Z'] = function (thread, javaThis, file) {
        var filepath = file['java/io/File/path'].toString();
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        statFile(filepath, function (stats) {
            if (stats == null) {
                thread.asyncReturn(0);
            } else if (stats.isDirectory()) {
                fs.readdir(filepath, function (err, files) {
                    if (files.length > 0) {
                        thread.asyncReturn(0);
                    } else {
                        fs.rmdir(filepath, function (err) {
                            thread.asyncReturn(1);
                        });
                    }
                });
            } else {
                fs.unlink(filepath, function (err) {
                    thread.asyncReturn(1);
                });
            }
        });
    };
    java_io_UnixFileSystem['list(Ljava/io/File;)[Ljava/lang/String;'] = function (thread, javaThis, file) {
        var filepath = file['java/io/File/path'], bsCl = thread.getBsCl();
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.readdir(filepath.toString(), function (err, files) {
            if (err != null) {
                thread.asyncReturn(null);
            } else {
                thread.asyncReturn(util.newArrayFromData(thread, thread.getBsCl(), '[Ljava/lang/String;', files.map(function (file) {
                    return util.initString(thread.getBsCl(), file);
                })));
            }
        });
    };
    java_io_UnixFileSystem['createDirectory(Ljava/io/File;)Z'] = function (thread, javaThis, file) {
        var filepath = file['java/io/File/path'].toString();
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        statFile(filepath, function (stat) {
            if (stat != null) {
                thread.asyncReturn(0);
            } else {
                fs.mkdir(filepath, function (err) {
                    thread.asyncReturn(err != null ? 0 : 1);
                });
            }
        });
    };
    java_io_UnixFileSystem['rename0(Ljava/io/File;Ljava/io/File;)Z'] = function (thread, javaThis, file1, file2) {
        var file1path = file1['java/io/File/path'].toString(), file2path = file2['java/io/File/path'].toString();
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.rename(file1path, file2path, function (err) {
            thread.asyncReturn(err != null ? 0 : 1);
        });
    };
    java_io_UnixFileSystem['setLastModifiedTime(Ljava/io/File;J)Z'] = function (thread, javaThis, file, time) {
        var mtime = time.toNumber(), atime = new Date().getTime(), filepath = file['java/io/File/path'].toString();
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.utimes(filepath, atime, mtime, function (err) {
            thread.asyncReturn(1);
        });
    };
    java_io_UnixFileSystem['setReadOnly(Ljava/io/File;)Z'] = function (thread, javaThis, file) {
        var filepath = file['java/io/File/path'].toString(), mask = ~146;
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        statFile(filepath, function (stats) {
            if (stats == null) {
                thread.asyncReturn(0);
            } else {
                fs.chmod(filepath, stats.mode & mask, function (err) {
                    thread.asyncReturn(err != null ? 0 : 1);
                });
            }
        });
    };
    java_io_UnixFileSystem['getSpace(Ljava/io/File;I)J'] = function (thread, javaThis, file, arg1) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
        return null;
    };
    java_io_UnixFileSystem['initIDs()V'] = function (thread) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    return java_io_UnixFileSystem;
}();
registerNatives({
    'java/io/Console': java_io_Console,
    'java/io/FileDescriptor': java_io_FileDescriptor,
    'java/io/FileInputStream': java_io_FileInputStream,
    'java/io/FileOutputStream': java_io_FileOutputStream,
    'java/io/ObjectInputStream': java_io_ObjectInputStream,
    'java/io/ObjectOutputStream': java_io_ObjectOutputStream,
    'java/io/ObjectStreamClass': java_io_ObjectStreamClass,
    'java/io/RandomAccessFile': java_io_RandomAccessFile,
    'java/io/UnixFileSystem': java_io_UnixFileSystem
});
//# sourceMappingURL=java_io.js.map