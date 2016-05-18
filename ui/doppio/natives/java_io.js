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
//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJzb3VyY2VzIjpbIi4uLy4uLy4uLy4uL3NyYy9uYXRpdmVzL2phdmFfaW8udHMiXSwibmFtZXMiOltdLCJtYXBwaW5ncyI6IjtBQUFBLElBQU8sRUFBQSxHQUFFLE9BQUEsQ0FBVyxJQUFYLENBQVQ7QUFDQSxJQUFPLElBQUEsR0FBSSxPQUFBLENBQVcsTUFBWCxDQUFYO0FBQ0EsSUFBWSxNQUFBLEdBQU0sT0FBQSxDQUFNLGNBQU4sQ0FBbEI7QUFJQSxJQUFPLElBQUEsR0FBTyxNQUFBLENBQU8sRUFBUCxDQUFVLElBQXhCO0FBQ0EsSUFBTyxZQUFBLEdBQWUsTUFBQSxDQUFPLEVBQVAsQ0FBVSxLQUFWLENBQWdCLFlBQXRDO0FBQ0EsSUFBTyxJQUFBLEdBQU8sTUFBQSxDQUFPLEVBQVAsQ0FBVSxJQUF4QjtBQUtBLFNBQUEsY0FBQSxDQUF3QixNQUF4QixFQUEyQyxHQUEzQyxFQUFxRTtBQUFBLElBQ25FLElBQUksSUFBQSxHQUFPLHVCQUFYLENBRG1FO0FBQUEsSUFFbkUsSUFBSSxHQUFBLENBQUksSUFBSixLQUFhLFFBQWpCLEVBQTJCO0FBQUEsUUFDekIsSUFBQSxHQUFPLGlDQUFQLENBRHlCO0FBQUEsS0FGd0M7QUFBQSxJQUtuRSxNQUFBLENBQU8saUJBQVAsQ0FBeUIsSUFBekIsRUFBK0IsR0FBQSxDQUFJLE9BQW5DLEVBTG1FO0FBQUE7QUFZckUsU0FBQSxXQUFBLENBQXFCLE9BQXJCLEVBQXNDLE1BQXRDLEVBQW9FO0FBQUEsSUFFbEUsSUFBSSxJQUFBLEdBQU8sVUFBVSxNQUFWLEVBQXdCO0FBQUEsWUFFakMsSUFBSSxLQUFBLEdBQWlCLE9BQUEsQ0FBUSxLQUFSLENBQWMsSUFBZCxDQUFtQixNQUFuQixDQUFyQixDQUZpQztBQUFBLFlBR2pDLElBQUksS0FBQSxLQUFVLElBQWQsRUFBb0I7QUFBQSxnQkFHbEIsS0FBQSxHQUFpQixPQUFBLENBQVEsS0FBUixDQUFjLElBQWQsRUFBakIsQ0FIa0I7QUFBQSxhQUhhO0FBQUEsWUFTakMsSUFBSSxLQUFBLEtBQVUs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QUEsQ0FBTSxJQUFOLEdBQWEsSUFBaEMsRUFBc0MsVUFBQyxHQUFELEVBQTRCO0FBQUEsb0JBQ2hFLE1BQUEsQ0FBTyxXQUFQLENBQW1CLEdBQUEsSUFBTyxJQUFQLEdBQWMsQ0FBZCxHQUFrQixDQUFyQyxFQURnRTtBQUFBLGlCQUFsRSxFQURLO0FBQUEsYUFIZ0I7QUFBQSxTQUF6QixFQU5vSTtBQUFBLEtBQXhILENBek1oQjtBQUFBLElBME5nQixzQkFBQSxDQUFBLDRCQUFBLElBQWQsVUFBMkMsTUFBM0MsRUFBOEQsUUFBOUQsRUFBeUcsSUFBekcsRUFBc0ksSUFBdEksRUFBa0o7QUFBQSxRQUNoSixNQUFBLENBQU8saUJBQVAsQ0FBeUIsa0NBQXpCLEVBQTZELGdDQUE3RCxFQURnSjtBQUFBLFFBR2hKLE9BQU8sSUFBUCxDQUhnSjtBQUFBLEtBQXBJLENBMU5oQjtBQUFBLElBZ09nQixzQkFBQSxDQUFBLFlBQUEsSUFBZCxVQUEyQixNQUEzQixFQUE0QztBQUFBLFFBQzFDLE1BQUEsQ0FBTyxpQkFBUCxDQUF5QixrQ0FBekIsRUFBNkQsZ0NBQTdELEVBRDBDO0FBQUEsS0FBOUIsQ0FoT2hCO0FBQUEsSUFvT0EsT0FBQSxzQkFBQSxDQXBPQTtBQUFBLENBQUEsRUFBQTtBQXNPQSxlQUFBLENBQWdCO0FBQUEsSUFDZCxtQkFBbUIsZUFETDtBQUFBLElBRWQsMEJBQTBCLHNCQUZaO0FBQUEsSUFHZCwyQkFBMkIsdUJBSGI7QUFBQSxJQUlkLDRCQUE0Qix3QkFKZDtBQUFBLElBS2QsNkJBQTZCLHlCQUxmO0FBQUEsSUFNZCw4QkFBOEIsMEJBTmhCO0FBQUEsSUFPZCw2QkFBNkIseUJBUGY7QUFBQSxJQVFkLDRCQUE0Qix3QkFSZDtBQUFBLElBU2QsMEJBQTBCLHNCQVRaO0FBQUEsQ0FBaEIiLCJzb3VyY2VzQ29udGVudCI6WyJpbXBvcnQgZnMgPSByZXF1aXJlKCdmcycpO1xuaW1wb3J0IHBhdGggPSByZXF1aXJlKCdwYXRoJyk7XG5pbXBvcnQgKiBhcyBEb3BwaW8gZnJvbSAnLi4vZG9wcGlvanZtJztcbmltcG9ydCBKVk1UaHJlYWQgPSBEb3BwaW8uVk0uVGhyZWFkaW5nLkpWTVRocmVhZDtcbmltcG9ydCBSZWZlcmVuY2VDbGFzc0RhdGEgPSBEb3BwaW8uVk0uQ2xhc3NGaWxlLlJlZmVyZW5jZUNsYXNzRGF0YTtcbmltcG9ydCBsb2dnaW5nID0gRG9wcGlvLkRlYnVnLkxvZ2dpbmc7XG5pbXBvcnQgdXRpbCA9IERvcHBpby5WTS5VdGlsO1xuaW1wb3J0IFRocmVhZFN0YXR1cyA9IERvcHBpby5WTS5FbnVtcy5UaHJlYWRTdGF0dXM7XG5pbXBvcnQgTG9uZyA9IERvcHBpby5WTS5Mb25nO1xuaW1wb3J0IGFzc2VydCA9IERvcHBpby5EZWJ1Zy5Bc3NlcnQ7XG5pbXBvcnQgSlZNVHlwZXMgPSByZXF1aXJlKCcuLi8uLi9pbmNsdWRlcy9KVk1UeXBlcycpO1xuZGVjbGFyZSB2YXIgcmVnaXN0ZXJOYXRpdmVzOiAoZGVmczogYW55KSA9PiB2b2lkO1xuXG5mdW5jdGlvbiB0aHJvd05vZGVFcnJvcih0aHJlYWQ6IEpWTVRocmVhZCwgZXJyOiBOb2RlSlMuRXJybm9FeGNlcHRpb24pOiB2b2lkIHtcbiAgbGV0IHR5cGUgPSBcIkxqYXZhL2lvL0lPRXhjZXB0aW9uO1wiO1xuICBpZiAoZXJyLmNvZGUgPT09IFwiRU5PRU5UXCIpIHtcbiAgICB0eXBlID0gJ0xqYXZhL2lvL0ZpbGVOb3RGb3VuZEV4Y2VwdGlvbjsnO1xuICB9XG4gIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbih0eXBlLCBlcnIubWVzc2FnZSk7XG59XG5cbi8qKlxuICogUHJvdmlkZSBidWZmZXJpbmcgZm9yIHRoZSB1bmRlcmx5aW5nIGlucHV0IGZ1bmN0aW9uLCByZXR1cm5pbmcgYXQgbW9zdFxuICogbl9ieXRlcyBvZiBkYXRhLlxuICovXG5mdW5jdGlvbiBhc3luY19pbnB1dChuX2J5dGVzOiBudW1iZXIsIHJlc3VtZTogKGRhdGE6IEJ1ZmZlcikgPT4gdm9pZCk6IHZvaWQge1xuICAvLyBUcnkgdG8gcmVhZCBuX2J5dGVzIGZyb20gc3RkaW4ncyBidWZmZXIuXG4gIHZhciByZWFkID0gZnVuY3Rpb24gKG5CeXRlczogbnVtYmVyKTogTm9kZUJ1ZmZlciB7XG4gICAgLy8gWFhYOiBSZXR1cm5zIGEgQnVmZmVyLCBidXQgRGVmaW5pdGVseVR5cGVkIHNheXMgc3RyaW5nfEJ1ZmZlci5cbiAgICB2YXIgYnl0ZXMgPSA8QnVmZmVyPiBwcm9jZXNzLnN0ZGluLnJlYWQobkJ5dGVzKTtcbiAgICBpZiAoYnl0ZXMgPT09IG51bGwpIHtcbiAgICAgIC8vIFdlIG1pZ2h0IGhhdmUgYXNrZWQgZm9yIHRvbyBtYW55IGJ5dGVzLiBSZXRyaWV2ZSB0aGUgZW50aXJlIHN0cmVhbVxuICAgICAgLy8gYnVmZmVyLlxuICAgICAgYnl0ZXMgPSA8QnVmZmVyPiBwcm9jZXNzLnN0ZGluLnJlYWQoKTtcbiAgICB9XG4gICAgLy8gXFwwID0+IEVPRi5cbiAgICBpZiAoYnl0ZXMgIT09IG51bGwgJiYgYnl0ZXMubGVuZ3RoID09PSAxICYmIGJ5dGVzLnJlYWRVSW50OCgwKSA9PT0gMCkge1xuICAgICAgYnl0ZXMgPSBuZXcgQnVmZmVyKDApO1xuICAgIH1cbiAgICByZXR1cm4gYnl0ZXM7XG4gIH0sIGJ5dGVzOiBOb2RlQnVmZmVyID0gcmVhZChuX2J5dGVzKTtcblxuICBpZiAoYnl0ZXMgPT09IG51bGwpIHtcbiAgICAvLyBObyBpbnB1dCBhdmFpbGFibGUuIFdhaXQgZm9yIGZ1cnRoZXIgaW5wdXQuXG4gICAgcHJvY2Vzcy5zdGRpbi5vbmNlKCdyZWFkYWJsZScsIGZ1bmN0aW9uIChkYXRhOiBOb2RlQnVmZmVyKSB7XG4gICAgICB2YXIgYnl0ZXMgPSByZWFkKG5fYnl0ZXMpO1xuICAgICAgaWYgKGJ5dGVzID09PSBudWxsKSB7XG4gICAgICAgIGJ5dGVzID0gbmV3IEJ1ZmZlcigwKTtcbiAgICAgIH1cbiAgICAgIHJlc3VtZShieXRlcyk7XG4gICAgfSk7XG4gIH0gZWxzZSB7XG4gICAgLy8gUmVzZXQgc3RhY2sgZGVwdGggYW5kIHJlc3VtZSB3aXRoIHRoZSBnaXZlbiBkYXRhLlxuICAgIHNldEltbWVkaWF0ZShmdW5jdGlvbiAoKSB7IHJlc3VtZShieXRlcyk7IH0pO1xuICB9XG59XG5cbmZ1bmN0aW9uIHN0YXRGaWxlKGZuYW1lOiBzdHJpbmcsIGNiOiAoc3RhdDogZnMuU3RhdHMpID0+IHZvaWQpOiB2b2lkIHtcbiAgZnMuc3RhdChmbmFtZSwgKGVyciwgc3RhdCkgPT4ge1xuICAgIGlmIChlcnIgIT0gbnVsbCkge1xuICAgICAgY2IobnVsbCk7XG4gICAgfSBlbHNlIHtcbiAgICAgIGNiKHN0YXQpO1xuICAgIH1cbiAgfSk7XG59XG5cbmNsYXNzIGphdmFfaW9fQ29uc29sZSB7XG5cbiAgcHVibGljIHN0YXRpYyAnZW5jb2RpbmcoKUxqYXZhL2xhbmcvU3RyaW5nOycodGhyZWFkOiBKVk1UaHJlYWQpOiBKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nIHtcbiAgICByZXR1cm4gbnVsbDtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2VjaG8oWilaJyh0aHJlYWQ6IEpWTVRocmVhZCwgZWNob09uOiBib29sZWFuKTogYm9vbGVhbiB7XG4gICAgdmFyIGVjaG9PZmY6IGJvb2xlYW4gPSAhZWNob09uO1xuICAgICg8YW55PiBwcm9jZXNzLnN0ZGluKS5zZXRSYXdNb2RlKGVjaG9PZmYpO1xuICAgIHJldHVybiBlY2hvT2ZmO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnaXN0dHkoKVonKHRocmVhZDogSlZNVGhyZWFkKTogYm9vbGVhbiB7XG4gICAgcmV0dXJuICg8YW55PiBwcm9jZXNzLnN0ZG91dCkuaXNUVFk7XG4gIH1cblxufVxuXG5jbGFzcyBqYXZhX2lvX0ZpbGVEZXNjcmlwdG9yIHtcblxuICBwdWJsaWMgc3RhdGljICdzeW5jKClWJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfaW9fRmlsZURlc2NyaXB0b3IpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnaW5pdElEcygpVicodGhyZWFkOiBKVk1UaHJlYWQpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbn1cblxuY2xhc3MgamF2YV9pb19GaWxlSW5wdXRTdHJlYW0ge1xuXG4gIHB1YmxpYyBzdGF0aWMgJ29wZW4wKExqYXZhL2xhbmcvU3RyaW5nOylWJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfaW9fRmlsZUlucHV0U3RyZWFtLCBmaWxlbmFtZTogSlZNVHlwZXMuamF2YV9sYW5nX1N0cmluZyk6IHZvaWQge1xuICAgIHZhciBmaWxlcGF0aCA9IGZpbGVuYW1lLnRvU3RyaW5nKCk7XG4gICAgLy8gVE9ETzogYWN0dWFsbHkgbG9vayBhdCB0aGUgbW9kZVxuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGZzLm9wZW4oZmlsZXBhdGgsICdyJywgZnVuY3Rpb24gKGUsIGZkKSB7XG4gICAgICBpZiAoZSAhPSBudWxsKSB7XG4gICAgICAgIGlmIChlLmNvZGUgPT09ICdFTk9FTlQnKSB7XG4gICAgICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9pby9GaWxlTm90Rm91bmRFeGNlcHRpb247JywgXCJcIiArIGZpbGVwYXRoICsgXCIgKE5vIHN1Y2ggZmlsZSBvciBkaXJlY3RvcnkpXCIpO1xuICAgICAgICB9IGVsc2Uge1xuICAgICAgICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9JbnRlcm5hbEVycm9yJywgJ0ludGVybmFsIEpWTSBlcnJvcjogJyArICBlKTtcbiAgICAgICAgfVxuICAgICAgfSBlbHNlIHtcbiAgICAgICAgdmFyIGZkT2JqID0gamF2YVRoaXNbJ2phdmEvaW8vRmlsZUlucHV0U3RyZWFtL2ZkJ107XG4gICAgICAgIGZkT2JqWydqYXZhL2lvL0ZpbGVEZXNjcmlwdG9yL2ZkJ10gPSBmZDtcbiAgICAgICAgZmRPYmouJHBvcyA9IDA7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybigpO1xuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAncmVhZDAoKUknKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9pb19GaWxlSW5wdXRTdHJlYW0pOiB2b2lkIHtcbiAgICB2YXIgZmRPYmogPSBqYXZhVGhpc1tcImphdmEvaW8vRmlsZUlucHV0U3RyZWFtL2ZkXCJdLFxuICAgICAgZmQgPSBmZE9ialtcImphdmEvaW8vRmlsZURlc2NyaXB0b3IvZmRcIl07XG4gICAgaWYgKC0xID09PSBmZCkge1xuICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKFwiTGphdmEvaW8vSU9FeGNlcHRpb247XCIsIFwiQmFkIGZpbGUgZGVzY3JpcHRvclwiKTtcbiAgICB9IGVsc2UgaWYgKDAgIT09IGZkKSB7XG4gICAgICAvLyB0aGlzIGlzIGEgcmVhbCBmaWxlIHRoYXQgd2UndmUgYWxyZWFkeSBvcGVuZWRcbiAgICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgICAgdmFyIGJ1ZiA9IG5ldyBCdWZmZXIoMSk7XG4gICAgICBmcy5yZWFkKGZkLCBidWYsIDAsIDEsIGZkT2JqLiRwb3MsIChlcnIsIGJ5dGVzX3JlYWQpID0+IHtcbiAgICAgICAgaWYgKGVycikge1xuICAgICAgICAgIHJldHVybiB0aHJvd05vZGVFcnJvcih0aHJlYWQsIGVycik7XG4gICAgICAgIH1cbiAgICAgICAgZmRPYmouJHBvcysrO1xuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oMCA9PT0gYnl0ZXNfcmVhZCA/IC0xIDogYnVmLnJlYWRVSW50OCgwKSk7XG4gICAgICB9KTtcbiAgICB9IGVsc2Uge1xuICAgICAgLy8gcmVhZGluZyBmcm9tIFN5c3RlbS5pbiwgZG8gaXQgYXN5bmNcbiAgICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgICAgYXN5bmNfaW5wdXQoMSwgKGJ5dGU6IE5vZGVCdWZmZXIpID0+IHtcbiAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKDAgPT09IGJ5dGUubGVuZ3RoID8gLTEgOiBieXRlLnJlYWRVSW50OCgwKSk7XG4gICAgICB9KTtcbiAgICB9XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdyZWFkQnl0ZXMoW0JJSSlJJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfaW9fRmlsZUlucHV0U3RyZWFtLCBieXRlQXJyOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+LCBvZmZzZXQ6IG51bWJlciwgbkJ5dGVzOiBudW1iZXIpOiBudW1iZXIge1xuICAgIHZhciBidWY6IEJ1ZmZlciwgcG9zOiBudW1iZXIsXG4gICAgICBmZE9iaiA9IGphdmFUaGlzW1wiamF2YS9pby9GaWxlSW5wdXRTdHJlYW0vZmRcIl0sXG4gICAgICBmZCA9IGZkT2JqW1wiamF2YS9pby9GaWxlRGVzY3JpcHRvci9mZFwiXTtcblxuICAgIGlmIChvZmZzZXQgKyBuQnl0ZXMgPiBieXRlQXJyLmFycmF5Lmxlbmd0aCkge1xuICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL0luZGV4T3V0T2ZCb3VuZHNFeGNlcHRpb247JywgXCJcIik7XG4gICAgICByZXR1cm47XG4gICAgfVxuXG4gICAgaWYgKG5CeXRlcyA9PT0gMCkge1xuICAgICAgcmV0dXJuIDA7XG4gICAgfSBlbHNlIGlmICgtMSA9PT0gZmQpIHtcbiAgICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbihcIkxqYXZhL2lvL0lPRXhjZXB0aW9uO1wiLCBcIkJhZCBmaWxlIGRlc2NyaXB0b3JcIik7XG4gICAgfSBlbHNlIGlmICgwICE9PSBmZCkge1xuICAgICAgLy8gdGhpcyBpcyBhIHJlYWwgZmlsZSB0aGF0IHdlJ3ZlIGFscmVhZHkgb3BlbmVkXG4gICAgICBwb3MgPSBmZE9iai4kcG9zO1xuICAgICAgYnVmID0gbmV3IEJ1ZmZlcihuQnl0ZXMpO1xuICAgICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgICBmcy5yZWFkKGZkLCBidWYsIDAsIG5CeXRlcywgcG9zLCAoZXJyLCBieXRlc1JlYWQpID0+IHtcbiAgICAgICAgaWYgKG51bGwgIT0gZXJyKSB7XG4gICAgICAgICAgdGhyb3dOb2RlRXJyb3IodGhyZWFkLCBlcnIpO1xuICAgICAgICB9IGVsc2Uge1xuICAgICAgICAgIC8vIG5vdCBjbGVhciB3aHksIGJ1dCBzb21ldGltZXMgbm9kZSBkb2Vzbid0IG1vdmUgdGhlXG4gICAgICAgICAgLy8gZmlsZSBwb2ludGVyLCBzbyB3ZSBkbyBpdCBoZXJlIG91cnNlbHZlcy5cbiAgICAgICAgICBmZE9iai4kcG9zICs9IGJ5dGVzUmVhZDtcbiAgICAgICAgICBmb3IgKGxldCBpID0gMDsgaSA8IGJ5dGVzUmVhZDsgaSsrKSB7XG4gICAgICAgICAgICBieXRlQXJyLmFycmF5W29mZnNldCArIGldID0gYnVmLnJlYWRJbnQ4KGkpO1xuICAgICAgICAgIH1cbiAgICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oMCA9PT0gYnl0ZXNSZWFkID8gLTEgOiBieXRlc1JlYWQpO1xuICAgICAgICB9XG4gICAgICB9KTtcbiAgICB9IGVsc2Uge1xuICAgICAgLy8gcmVhZGluZyBmcm9tIFN5c3RlbS5pbiwgZG8gaXQgYXN5bmNcbiAgICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgICAgYXN5bmNfaW5wdXQobkJ5dGVzLCAoYnl0ZXM6IE5vZGVCdWZmZXIpID0+IHtcbiAgICAgICAgdmFyIGI6IG51bWJlciwgaWR4OiBudW1iZXI7XG4gICAgICAgIGZvciAoaWR4ID0gMDsgaWR4IDwgYnl0ZXMubGVuZ3RoOyBpZHgrKykge1xuICAgICAgICAgIGIgPSBieXRlcy5yZWFkVUludDgoaWR4KTtcbiAgICAgICAgICBieXRlQXJyLmFycmF5W29mZnNldCArIGlkeF0gPSBiO1xuICAgICAgICB9XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybihieXRlcy5sZW5ndGggPT09IDAgPyAtMSA6IGJ5dGVzLmxlbmd0aCk7XG4gICAgICB9KTtcbiAgICB9XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdza2lwKEopSicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2lvX0ZpbGVJbnB1dFN0cmVhbSwgbkJ5dGVzOiBMb25nKTogdm9pZCB7XG4gICAgdmFyIGZkT2JqID0gamF2YVRoaXNbXCJqYXZhL2lvL0ZpbGVJbnB1dFN0cmVhbS9mZFwiXTtcbiAgICB2YXIgZmQgPSBmZE9ialtcImphdmEvaW8vRmlsZURlc2NyaXB0b3IvZmRcIl07XG4gICAgaWYgKC0xID09PSBmZCkge1xuICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKFwiTGphdmEvaW8vSU9FeGNlcHRpb247XCIsIFwiQmFkIGZpbGUgZGVzY3JpcHRvclwiKTtcbiAgICB9IGVsc2UgaWYgKDAgIT09IGZkKSB7XG4gICAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICAgIGZzLmZzdGF0KGZkLCAoZXJyLCBzdGF0cykgPT4ge1xuICAgICAgICBpZiAoZXJyKSB7XG4gICAgICAgICAgcmV0dXJuIHRocm93Tm9kZUVycm9yKHRocmVhZCwgZXJyKTtcbiAgICAgICAgfVxuICAgICAgICB2YXIgYnl0ZXNMZWZ0ID0gc3RhdHMuc2l6ZSAtIGZkT2JqLiRwb3MsXG4gICAgICAgICAgdG9Ta2lwID0gTWF0aC5taW4obkJ5dGVzLnRvTnVtYmVyKCksIGJ5dGVzTGVmdCk7XG4gICAgICAgIGZkT2JqLiRwb3MgKz0gdG9Ta2lwO1xuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oTG9uZy5mcm9tTnVtYmVyKHRvU2tpcCksIG51bGwpO1xuICAgICAgfSk7XG4gICAgfSBlbHNlIHtcbiAgICAgIC8vIHJlYWRpbmcgZnJvbSBTeXN0ZW0uaW4sIGRvIGl0IGFzeW5jXG4gICAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICAgIGFzeW5jX2lucHV0KG5CeXRlcy50b051bWJlcigpLCAoYnl0ZXMpID0+IHtcbiAgICAgICAgLy8gd2UgZG9uJ3QgY2FyZSBhYm91dCB3aGF0IHRoZSBpbnB1dCBhY3R1YWxseSB3YXNcbiAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKExvbmcuZnJvbU51bWJlcihieXRlcy5sZW5ndGgpLCBudWxsKTtcbiAgICAgIH0pO1xuICAgIH1cbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2F2YWlsYWJsZSgpSScodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2lvX0ZpbGVJbnB1dFN0cmVhbSk6IG51bWJlciB7XG4gICAgdmFyIGZkT2JqID0gamF2YVRoaXNbXCJqYXZhL2lvL0ZpbGVJbnB1dFN0cmVhbS9mZFwiXSxcbiAgICAgIGZkID0gZmRPYmpbXCJqYXZhL2lvL0ZpbGVEZXNjcmlwdG9yL2ZkXCJdO1xuXG4gICAgaWYgKGZkID09PSAtMSkge1xuICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKFwiTGphdmEvaW8vSU9FeGNlcHRpb247XCIsIFwiQmFkIGZpbGUgZGVzY3JpcHRvclwiKTtcbiAgICB9IGVsc2UgaWYgKGZkID09PSAwKSB7XG4gICAgICAvLyBubyBidWZmZXJpbmcgZm9yIHN0ZGluIChpZiBmZCBpcyAwKVxuICAgICAgcmV0dXJuIDA7XG4gICAgfSBlbHNlIHtcbiAgICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgICAgZnMuZnN0YXQoZmQsIChlcnIsIHN0YXRzKSA9PiB7XG4gICAgICAgIGlmIChlcnIpIHtcbiAgICAgICAgICByZXR1cm4gdGhyb3dOb2RlRXJyb3IodGhyZWFkLCBlcnIpO1xuICAgICAgICB9XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybihzdGF0cy5zaXplIC0gZmRPYmouJHBvcyk7XG4gICAgICB9KTtcbiAgICB9XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdpbml0SURzKClWJyh0aHJlYWQ6IEpWTVRocmVhZCk6IHZvaWQge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdjbG9zZTAoKVYnKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9pb19GaWxlSW5wdXRTdHJlYW0pOiB2b2lkIHtcbiAgICB2YXIgZmRPYmogPSBqYXZhVGhpc1snamF2YS9pby9GaWxlSW5wdXRTdHJlYW0vZmQnXSxcbiAgICAgIGZkID0gZmRPYmpbJ2phdmEvaW8vRmlsZURlc2NyaXB0b3IvZmQnXTtcbiAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICBmcy5jbG9zZShmZCwgKGVycj86IE5vZGVKUy5FcnJub0V4Y2VwdGlvbikgPT4ge1xuICAgICAgaWYgKGVycikge1xuICAgICAgICB0aHJvd05vZGVFcnJvcih0aHJlYWQsIGVycik7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICBmZE9ialsnamF2YS9pby9GaWxlRGVzY3JpcHRvci9mZCddID0gLTE7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybigpO1xuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbn1cblxuY2xhc3MgamF2YV9pb19GaWxlT3V0cHV0U3RyZWFtIHtcbiAgLyoqXG4gICAqIE9wZW5zIGEgZmlsZSwgd2l0aCB0aGUgc3BlY2lmaWVkIG5hbWUsIGZvciBvdmVyd3JpdGluZyBvciBhcHBlbmRpbmcuXG4gICAqIEBwYXJhbSBuYW1lIG5hbWUgb2YgZmlsZSB0byBiZSBvcGVuZWRcbiAgICogQHBhcmFtIGFwcGVuZCB3aGV0aGVyIHRoZSBmaWxlIGlzIHRvIGJlIG9wZW5lZCBpbiBhcHBlbmQgbW9kZVxuICAgKi9cbiAgcHVibGljIHN0YXRpYyAnb3BlbjAoTGphdmEvbGFuZy9TdHJpbmc7WilWJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfaW9fRmlsZU91dHB1dFN0cmVhbSwgbmFtZTogSlZNVHlwZXMuamF2YV9sYW5nX1N0cmluZywgYXBwZW5kOiBudW1iZXIpOiB2b2lkIHtcbiAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICBmcy5vcGVuKG5hbWUudG9TdHJpbmcoKSwgYXBwZW5kID8gJ2EnIDogJ3cnLCAoZXJyLCBmZCkgPT4ge1xuICAgICAgaWYgKGVycikge1xuICAgICAgICByZXR1cm4gdGhyb3dOb2RlRXJyb3IodGhyZWFkLCBlcnIpO1xuICAgICAgfVxuICAgICAgdmFyIGZkT2JqID0gamF2YVRoaXNbJ2phdmEvaW8vRmlsZU91dHB1dFN0cmVhbS9mZCddO1xuICAgICAgZmRPYmpbJ2phdmEvaW8vRmlsZURlc2NyaXB0b3IvZmQnXSA9IGZkO1xuICAgICAgZnMuZnN0YXQoZmQsIChlcnIsIHN0YXRzKSA9PiB7XG4gICAgICAgIGZkT2JqLiRwb3MgPSBzdGF0cy5zaXplO1xuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oKTtcbiAgICAgIH0pO1xuICAgIH0pO1xuICB9XG5cbiAgLyoqXG4gICAqIFdyaXRlcyB0aGUgc3BlY2lmaWVkIGJ5dGUgdG8gdGhpcyBmaWxlIG91dHB1dCBzdHJlYW0uXG4gICAqXG4gICAqIEBwYXJhbSAgIGIgICB0aGUgYnl0ZSB0byBiZSB3cml0dGVuLlxuICAgKiBAcGFyYW0gICBhcHBlbmQgICB7QGNvZGUgdHJ1ZX0gaWYgdGhlIHdyaXRlIG9wZXJhdGlvbiBmaXJzdFxuICAgKiAgICAgYWR2YW5jZXMgdGhlIHBvc2l0aW9uIHRvIHRoZSBlbmQgb2YgZmlsZVxuICAgKi9cbiAgcHVibGljIHN0YXRpYyAnd3JpdGUoSVopVicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2lvX0ZpbGVPdXRwdXRTdHJlYW0sIGI6IG51bWJlciwgYXBwZW5kOiBudW1iZXIpOiB2b2lkIHtcbiAgICAvLyBIQUNLOiBBdm9pZCByZWltcGxlbWVudGluZyBiZWxvdyBmb3Igc2luZ2xlIGJ5dGUgY2FzZS5cbiAgICBqYXZhX2lvX0ZpbGVPdXRwdXRTdHJlYW1bJ3dyaXRlQnl0ZXMoW0JJSVopViddKHRocmVhZCwgamF2YVRoaXMsIDxhbnk+IHthcnJheTogW2JdfSwgMCwgMSwgYXBwZW5kKTtcbiAgfVxuXG4gIC8qKlxuICAgKiBXcml0ZXMgYSBzdWIgYXJyYXkgYXMgYSBzZXF1ZW5jZSBvZiBieXRlcy5cbiAgICogQHBhcmFtIGIgdGhlIGRhdGEgdG8gYmUgd3JpdHRlblxuICAgKiBAcGFyYW0gb2ZmIHRoZSBzdGFydCBvZmZzZXQgaW4gdGhlIGRhdGFcbiAgICogQHBhcmFtIGxlbiB0aGUgbnVtYmVyIG9mIGJ5dGVzIHRoYXQgYXJlIHdyaXR0ZW5cbiAgICogQHBhcmFtIGFwcGVuZCB7QGNvZGUgdHJ1ZX0gdG8gZmlyc3QgYWR2YW5jZSB0aGUgcG9zaXRpb24gdG8gdGhlXG4gICAqICAgICBlbmQgb2YgZmlsZVxuICAgKiBAZXhjZXB0aW9uIElPRXhjZXB0aW9uIElmIGFuIEkvTyBlcnJvciBoYXMgb2NjdXJyZWQuXG4gICAqL1xuICBwdWJsaWMgc3RhdGljICd3cml0ZUJ5dGVzKFtCSUlaKVYnKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9pb19GaWxlT3V0cHV0U3RyZWFtLCBieXRlczogSlZNVHlwZXMuSlZNQXJyYXk8bnVtYmVyPiwgb2Zmc2V0OiBudW1iZXIsIGxlbjogbnVtYmVyLCBhcHBlbmQ6IG51bWJlcik6IHZvaWQge1xuICAgIHZhciBidWY6IEJ1ZmZlciA9IG5ldyBCdWZmZXIoYnl0ZXMuYXJyYXkpLFxuICAgICAgZmRPYmogPSBqYXZhVGhpc1snamF2YS9pby9GaWxlT3V0cHV0U3RyZWFtL2ZkJ10sXG4gICAgICBmZCA9IGZkT2JqWydqYXZhL2lvL0ZpbGVEZXNjcmlwdG9yL2ZkJ107XG4gICAgaWYgKGZkID09PSAtMSkge1xuICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9pby9JT0V4Y2VwdGlvbjsnLCBcIkJhZCBmaWxlIGRlc2NyaXB0b3JcIik7XG4gICAgfSBlbHNlIGlmIChmZCAhPT0gMSAmJiBmZCAhPT0gMikge1xuICAgICAgLy8gbm9ybWFsIGZpbGVcbiAgICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgICAgZnMud3JpdGUoZmQsIGJ1Ziwgb2Zmc2V0LCBsZW4sIGZkT2JqLiRwb3MsIChlcnIsIG51bUJ5dGVzKSA9PiB7XG4gICAgICAgIGlmIChlcnIpIHtcbiAgICAgICAgICByZXR1cm4gdGhyb3dOb2RlRXJyb3IodGhyZWFkLCBlcnIpO1xuICAgICAgICB9XG4gICAgICAgIGZkT2JqLiRwb3MgKz0gbnVtQnl0ZXM7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybigpO1xuICAgICAgfSk7XG4gICAgfSBlbHNlIHtcbiAgICAgIC8vIFRoZSBzdHJpbmcgaXMgaW4gVVRGLTggZm9ybWF0LiBCdXQgbm93IHdlIG5lZWQgdG8gY29udmVydCB0aGVtIHRvIFVURi0xNiB0byBwcmludCAnZW0gb3V0LiA6KFxuICAgICAgdmFyIG91dHB1dDogc3RyaW5nID0gYnVmLnRvU3RyaW5nKFwidXRmOFwiLCBvZmZzZXQsIG9mZnNldCArIGxlbik7XG4gICAgICBpZiAoZmQgPT09IDEpIHtcbiAgICAgICAgcHJvY2Vzcy5zdGRvdXQud3JpdGUob3V0cHV0KTtcbiAgICAgIH0gZWxzZSBpZiAoZmQgPT09IDIpIHtcbiAgICAgICAgcHJvY2Vzcy5zdGRlcnIud3JpdGUob3V0cHV0KTtcbiAgICAgIH1cbiAgICAgIC8vIEZvciB0aGUgYnJvd3NlciBpbXBsZW1lbnRhdGlvbiAtLSB0aGUgRE9NIGRvZXNuJ3QgZ2V0IHJlcGFpbnRlZFxuICAgICAgLy8gdW5sZXNzIHdlIGdpdmUgdGhlIGV2ZW50IGxvb3AgYSBjaGFuY2UgdG8gc3Bpbi5cbiAgICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgICAgc2V0SW1tZWRpYXRlKCgpID0+IHtcbiAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKCk7XG4gICAgICB9KTtcbiAgICB9XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdjbG9zZTAoKVYnKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9pb19GaWxlT3V0cHV0U3RyZWFtKTogdm9pZCB7XG4gICAgdmFyIGZkT2JqID0gamF2YVRoaXNbJ2phdmEvaW8vRmlsZU91dHB1dFN0cmVhbS9mZCddLFxuICAgICAgZmQgPSBmZE9ialsnamF2YS9pby9GaWxlRGVzY3JpcHRvci9mZCddO1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGZzLmNsb3NlKGZkLCAoZXJyPzogTm9kZUpTLkVycm5vRXhjZXB0aW9uKSA9PiB7XG4gICAgICBpZiAoZXJyKSB7XG4gICAgICAgIHJldHVybiB0aHJvd05vZGVFcnJvcih0aHJlYWQsIGVycik7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICBmZE9ialsnamF2YS9pby9GaWxlRGVzY3JpcHRvci9mZCddID0gLTE7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybigpO1xuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnaW5pdElEcygpVicodGhyZWFkOiBKVk1UaHJlYWQpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbn1cblxuY2xhc3MgamF2YV9pb19PYmplY3RJbnB1dFN0cmVhbSB7XG5cbiAgcHVibGljIHN0YXRpYyAnYnl0ZXNUb0Zsb2F0cyhbQklbRklJKVYnKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+LCBhcmcxOiBudW1iZXIsIGFyZzI6IEpWTVR5cGVzLkpWTUFycmF5PG51bWJlcj4sIGFyZzM6IG51bWJlciwgYXJnNDogbnVtYmVyKTogdm9pZCB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL1Vuc2F0aXNmaWVkTGlua0Vycm9yOycsICdOYXRpdmUgbWV0aG9kIG5vdCBpbXBsZW1lbnRlZC4nKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2J5dGVzVG9Eb3VibGVzKFtCSVtESUkpVicodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IEpWTVR5cGVzLkpWTUFycmF5PG51bWJlcj4sIGFyZzE6IG51bWJlciwgYXJnMjogSlZNVHlwZXMuSlZNQXJyYXk8bnVtYmVyPiwgYXJnMzogbnVtYmVyLCBhcmc0OiBudW1iZXIpOiB2b2lkIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICB9XG5cbn1cblxuY2xhc3MgamF2YV9pb19PYmplY3RPdXRwdXRTdHJlYW0ge1xuXG4gIHB1YmxpYyBzdGF0aWMgJ2Zsb2F0c1RvQnl0ZXMoW0ZJW0JJSSlWJyh0aHJlYWQ6IEpWTVRocmVhZCwgYXJnMDogSlZNVHlwZXMuSlZNQXJyYXk8bnVtYmVyPiwgYXJnMTogbnVtYmVyLCBhcmcyOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+LCBhcmczOiBudW1iZXIsIGFyZzQ6IG51bWJlcik6IHZvaWQge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdkb3VibGVzVG9CeXRlcyhbRElbQklJKVYnKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+LCBhcmcxOiBudW1iZXIsIGFyZzI6IEpWTVR5cGVzLkpWTUFycmF5PG51bWJlcj4sIGFyZzM6IG51bWJlciwgYXJnNDogbnVtYmVyKTogdm9pZCB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL1Vuc2F0aXNmaWVkTGlua0Vycm9yOycsICdOYXRpdmUgbWV0aG9kIG5vdCBpbXBsZW1lbnRlZC4nKTtcbiAgfVxuXG59XG5cbmNsYXNzIGphdmFfaW9fT2JqZWN0U3RyZWFtQ2xhc3Mge1xuXG4gIHB1YmxpYyBzdGF0aWMgJ2luaXROYXRpdmUoKVYnKHRocmVhZDogSlZNVGhyZWFkKTogdm9pZCB7XG4gICAgLy8gTk9QXG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdoYXNTdGF0aWNJbml0aWFsaXplcihMamF2YS9sYW5nL0NsYXNzOylaJyh0aHJlYWQ6IEpWTVRocmVhZCwgamNvOiBKVk1UeXBlcy5qYXZhX2xhbmdfQ2xhc3MpOiBib29sZWFuIHtcbiAgICAvLyBjaGVjayBpZiBjbHMgaGFzIGEgPGNsaW5pdD4gbWV0aG9kXG4gICAgcmV0dXJuIGpjby4kY2xzLmdldE1ldGhvZCgnPGNsaW5pdD4oKVYnKSAhPT0gbnVsbDtcbiAgfVxuXG59XG5cbmNsYXNzIGphdmFfaW9fUmFuZG9tQWNjZXNzRmlsZSB7XG5cbiAgcHVibGljIHN0YXRpYyAnb3BlbjAoTGphdmEvbGFuZy9TdHJpbmc7SSlWJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfaW9fUmFuZG9tQWNjZXNzRmlsZSwgZmlsZW5hbWU6IEpWTVR5cGVzLmphdmFfbGFuZ19TdHJpbmcsIG1vZGU6IG51bWJlcik6IHZvaWQge1xuICAgIHZhciBmaWxlcGF0aCA9IGZpbGVuYW1lLnRvU3RyaW5nKCksXG4gICAgICByYWZTdGF0aWNzID0gPHR5cGVvZiBKVk1UeXBlcy5qYXZhX2lvX1JhbmRvbUFjY2Vzc0ZpbGU+ICg8UmVmZXJlbmNlQ2xhc3NEYXRhPEpWTVR5cGVzLmphdmFfaW9fUmFuZG9tQWNjZXNzRmlsZT4+IGphdmFUaGlzLmdldENsYXNzKCkpLmdldENvbnN0cnVjdG9yKHRocmVhZCksXG4gICAgICBtb2RlU3RyOiBzdHJpbmc7XG4gICAgc3dpdGNoIChtb2RlKSB7XG4gICAgICBjYXNlIHJhZlN0YXRpY3NbXCJqYXZhL2lvL1JhbmRvbUFjY2Vzc0ZpbGUvT19SRE9OTFlcIl06XG4gICAgICAgIG1vZGVTdHIgPSAncic7XG4gICAgICAgIGJyZWFrO1xuICAgICAgY2FzZSByYWZTdGF0aWNzW1wiamF2YS9pby9SYW5kb21BY2Nlc3NGaWxlL09fUkRXUlwiXTpcbiAgICAgICAgbW9kZVN0ciA9ICdyKyc7XG4gICAgICAgIGJyZWFrO1xuICAgICAgY2FzZSByYWZTdGF0aWNzW1wiamF2YS9pby9SYW5kb21BY2Nlc3NGaWxlL09fU1lOQ1wiXTpcbiAgICAgIGNhc2UgcmFmU3RhdGljc1tcImphdmEvaW8vUmFuZG9tQWNjZXNzRmlsZS9PX0RTWU5DXCJdOlxuICAgICAgICBtb2RlU3RyID0gJ3JzKyc7XG4gICAgICAgIGJyZWFrO1xuICAgIH1cbiAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICBmcy5vcGVuKGZpbGVwYXRoLCBtb2RlU3RyLCAoZSwgZmQpID0+IHtcbiAgICAgIGlmIChlKSB7XG4gICAgICAgIHJldHVybiB0aHJvd05vZGVFcnJvcih0aHJlYWQsIGUpO1xuICAgICAgfSBlbHNlIHtcbiAgICAgICAgdmFyIGZkT2JqID0gamF2YVRoaXNbJ2phdmEvaW8vUmFuZG9tQWNjZXNzRmlsZS9mZCddO1xuICAgICAgICBmZE9ialsnamF2YS9pby9GaWxlRGVzY3JpcHRvci9mZCddID0gZmQ7XG4gICAgICAgIGZkT2JqLiRwb3MgPSAwO1xuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oKTtcbiAgICAgIH1cbiAgICB9KTtcbiAgfVxuXG4gIC8qKlxuICAgKiBSZWFkcyBhIGJ5dGUgb2YgZGF0YSBmcm9tIHRoaXMgZmlsZS4gVGhlIGJ5dGUgaXMgcmV0dXJuZWQgYXMgYW5cbiAgICogaW50ZWdlciBpbiB0aGUgcmFuZ2UgMCB0byAyNTUgKHtAY29kZSAweDAwLTB4MGZmfSkuIFRoaXNcbiAgICogbWV0aG9kIGJsb2NrcyBpZiBubyBpbnB1dCBpcyB5ZXQgYXZhaWxhYmxlLlxuICAgKiA8cD5cbiAgICogQWx0aG91Z2gge0Bjb2RlIFJhbmRvbUFjY2Vzc0ZpbGV9IGlzIG5vdCBhIHN1YmNsYXNzIG9mXG4gICAqIHtAY29kZSBJbnB1dFN0cmVhbX0sIHRoaXMgbWV0aG9kIGJlaGF2ZXMgaW4gZXhhY3RseSB0aGUgc2FtZVxuICAgKiB3YXkgYXMgdGhlIHtAbGluayBJbnB1dFN0cmVhbSNyZWFkKCl9IG1ldGhvZCBvZlxuICAgKiB7QGNvZGUgSW5wdXRTdHJlYW19LlxuICAgKlxuICAgKiBAcmV0dXJuICAgICB0aGUgbmV4dCBieXRlIG9mIGRhdGEsIG9yIHtAY29kZSAtMX0gaWYgdGhlIGVuZCBvZiB0aGVcbiAgICogICAgICAgICAgICAgZmlsZSBoYXMgYmVlbiByZWFjaGVkLlxuICAgKiBAZXhjZXB0aW9uICBJT0V4Y2VwdGlvbiAgaWYgYW4gSS9PIGVycm9yIG9jY3Vycy4gTm90IHRocm93biBpZlxuICAgKiAgICAgICAgICAgICAgICAgICAgICAgICAgZW5kLW9mLWZpbGUgaGFzIGJlZW4gcmVhY2hlZC5cbiAgICovXG4gIHB1YmxpYyBzdGF0aWMgJ3JlYWQwKClJJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfaW9fUmFuZG9tQWNjZXNzRmlsZSk6IHZvaWQge1xuICAgIHZhciBmZE9iaiA9IGphdmFUaGlzW1wiamF2YS9pby9SYW5kb21BY2Nlc3NGaWxlL2ZkXCJdLFxuICAgICAgZmQgPSBmZE9ialtcImphdmEvaW8vRmlsZURlc2NyaXB0b3IvZmRcIl0sXG4gICAgICBidWYgPSBuZXcgQnVmZmVyKDEpO1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGZzLnJlYWQoZmQsIGJ1ZiwgMCwgMSwgZmRPYmouJHBvcywgZnVuY3Rpb24gKGVyciwgYnl0ZXNSZWFkKSB7XG4gICAgICBpZiAoZXJyKSB7XG4gICAgICAgIHJldHVybiB0aHJvd05vZGVFcnJvcih0aHJlYWQsIGVycik7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICBmZE9iai4kcG9zICs9IGJ5dGVzUmVhZDtcbiAgICAgICAgLy8gUmVhZCBhcyB1aW50LCBzaW5jZSByZXR1cm4gdmFsdWUgaXMgdW5zaWduZWQuXG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybihieXRlc1JlYWQgPT09IDAgPyAtMSA6IGJ1Zi5yZWFkVUludDgoMCkpO1xuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAncmVhZEJ5dGVzKFtCSUkpSScodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2lvX1JhbmRvbUFjY2Vzc0ZpbGUsIGJ5dGVfYXJyOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+LCBvZmZzZXQ6IG51bWJlciwgbGVuOiBudW1iZXIpOiB2b2lkIHtcbiAgICB2YXIgZmRPYmogPSBqYXZhVGhpc1tcImphdmEvaW8vUmFuZG9tQWNjZXNzRmlsZS9mZFwiXSxcbiAgICAgIGZkID0gZmRPYmpbXCJqYXZhL2lvL0ZpbGVEZXNjcmlwdG9yL2ZkXCJdLFxuICAgICAgYnVmID0gbmV3IEJ1ZmZlcihsZW4pO1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGZzLnJlYWQoZmQsIGJ1ZiwgMCwgbGVuLCBmZE9iai4kcG9zLCBmdW5jdGlvbiAoZXJyLCBieXRlc1JlYWQpIHtcbiAgICAgIGlmIChlcnIpIHtcbiAgICAgICAgcmV0dXJuIHRocm93Tm9kZUVycm9yKHRocmVhZCwgZXJyKTtcbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIGZvciAobGV0IGkgPSAwOyBpIDwgYnl0ZXNSZWFkOyBpKyspIHtcbiAgICAgICAgICBieXRlX2Fyci5hcnJheVtvZmZzZXQgKyBpXSA9IGJ1Zi5yZWFkSW50OChpKTtcbiAgICAgICAgfVxuICAgICAgICBmZE9iai4kcG9zICs9IGJ5dGVzUmVhZDtcbiAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKDAgPT09IGJ5dGVzUmVhZCAmJiAwICE9PSBsZW4gPyAtMSA6IGJ5dGVzUmVhZCk7XG4gICAgICB9XG4gICAgfSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICd3cml0ZTAoSSlWJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfaW9fUmFuZG9tQWNjZXNzRmlsZSwgdmFsdWU6IG51bWJlcik6IHZvaWQge1xuICAgIGxldCBmZE9iaiA9IGphdmFUaGlzW1wiamF2YS9pby9SYW5kb21BY2Nlc3NGaWxlL2ZkXCJdO1xuICAgIGxldCBmZCA9IGZkT2JqW1wiamF2YS9pby9GaWxlRGVzY3JpcHRvci9mZFwiXTtcbiAgICBsZXQgZGF0YSA9IG5ldyBCdWZmZXIoMSk7XG4gICAgZGF0YS53cml0ZUludDgodmFsdWUsIDApO1xuXG4gICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgZnMud3JpdGUoZmQsIGRhdGEsIDAsIDEsIGZkT2JqLiRwb3MsIChlcnIsIG51bUJ5dGVzKSA9PiB7XG4gICAgICBpZiAoZXJyKSB7XG4gICAgICAgIHJldHVybiB0aHJvd05vZGVFcnJvcih0aHJlYWQsIGVycik7XG4gICAgICB9XG5cbiAgICAgIGZkT2JqLiRwb3MgKz0gbnVtQnl0ZXM7XG4gICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oKTtcbiAgICB9KTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3dyaXRlQnl0ZXMoW0JJSSlWJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfaW9fUmFuZG9tQWNjZXNzRmlsZSwgYnl0ZUFycjogSlZNVHlwZXMuSlZNQXJyYXk8bnVtYmVyPiwgb2Zmc2V0OiBudW1iZXIsIGxlbjogbnVtYmVyKTogdm9pZCB7XG4gICAgdmFyIGZkT2JqID0gamF2YVRoaXNbXCJqYXZhL2lvL1JhbmRvbUFjY2Vzc0ZpbGUvZmRcIl0sXG4gICAgICBmZCA9IGZkT2JqW1wiamF2YS9pby9GaWxlRGVzY3JpcHRvci9mZFwiXSxcbiAgICAgIGJ1ZiA9IG5ldyBCdWZmZXIoYnl0ZUFyci5hcnJheSk7XG4gICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgZnMud3JpdGUoZmQsIGJ1Ziwgb2Zmc2V0LCBsZW4sIGZkT2JqLiRwb3MsIChlcnIsIG51bUJ5dGVzKSA9PiB7XG4gICAgICBpZiAoZXJyKSB7XG4gICAgICAgIHJldHVybiB0aHJvd05vZGVFcnJvcih0aHJlYWQsIGVycik7XG4gICAgICB9XG4gICAgICBmZE9iai4kcG9zICs9IG51bUJ5dGVzO1xuICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKCk7XG4gICAgfSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdnZXRGaWxlUG9pbnRlcigpSicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2lvX1JhbmRvbUFjY2Vzc0ZpbGUpOiBMb25nIHtcbiAgICByZXR1cm4gTG9uZy5mcm9tTnVtYmVyKGphdmFUaGlzWydqYXZhL2lvL1JhbmRvbUFjY2Vzc0ZpbGUvZmQnXS4kcG9zKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3NlZWswKEopVicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2lvX1JhbmRvbUFjY2Vzc0ZpbGUsIHBvczogTG9uZyk6IHZvaWQge1xuICAgIGphdmFUaGlzWydqYXZhL2lvL1JhbmRvbUFjY2Vzc0ZpbGUvZmQnXS4kcG9zID0gcG9zLnRvTnVtYmVyKCk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdsZW5ndGgoKUonKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9pb19SYW5kb21BY2Nlc3NGaWxlKTogdm9pZCB7XG4gICAgdmFyIGZkT2JqID0gamF2YVRoaXNbJ2phdmEvaW8vUmFuZG9tQWNjZXNzRmlsZS9mZCddLFxuICAgICAgZmQgPSBmZE9ialsnamF2YS9pby9GaWxlRGVzY3JpcHRvci9mZCddO1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGZzLmZzdGF0KGZkLCAoZXJyLCBzdGF0cykgPT4ge1xuICAgICAgaWYgKGVycikge1xuICAgICAgICByZXR1cm4gdGhyb3dOb2RlRXJyb3IodGhyZWFkLCBlcnIpO1xuICAgICAgfVxuICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKExvbmcuZnJvbU51bWJlcihzdGF0cy5zaXplKSwgbnVsbCk7XG4gICAgfSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdzZXRMZW5ndGgoSilWJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfaW9fUmFuZG9tQWNjZXNzRmlsZSwgYXJnMDogTG9uZyk6IHZvaWQge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdpbml0SURzKClWJyh0aHJlYWQ6IEpWTVRocmVhZCk6IHZvaWQge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdjbG9zZTAoKVYnKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9pb19SYW5kb21BY2Nlc3NGaWxlKTogdm9pZCB7XG4gICAgdmFyIGZkT2JqID0gamF2YVRoaXNbJ2phdmEvaW8vUmFuZG9tQWNjZXNzRmlsZS9mZCddLFxuICAgICAgZmQgPSBmZE9ialsnamF2YS9pby9GaWxlRGVzY3JpcHRvci9mZCddO1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGZzLmNsb3NlKGZkLCAoZXJyPzogTm9kZUpTLkVycm5vRXhjZXB0aW9uKSA9PiB7XG4gICAgICBpZiAoZXJyKSB7XG4gICAgICAgIHJldHVybiB0aHJvd05vZGVFcnJvcih0aHJlYWQsIGVycik7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICBmZE9ialsnamF2YS9pby9GaWxlRGVzY3JpcHRvci9mZCddID0gLTE7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybigpO1xuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbn1cblxuY2xhc3MgamF2YV9pb19Vbml4RmlsZVN5c3RlbSB7XG5cbiAgcHVibGljIHN0YXRpYyAnY2Fub25pY2FsaXplMChMamF2YS9sYW5nL1N0cmluZzspTGphdmEvbGFuZy9TdHJpbmc7Jyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfaW9fVW5peEZpbGVTeXN0ZW0sIGp2bVBhdGhTdHI6IEpWTVR5cGVzLmphdmFfbGFuZ19TdHJpbmcpOiBKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nIHtcbiAgICB2YXIganNTdHIgPSBqdm1QYXRoU3RyLnRvU3RyaW5nKCk7XG4gICAgcmV0dXJuIHV0aWwuaW5pdFN0cmluZyh0aHJlYWQuZ2V0QnNDbCgpLCBwYXRoLnJlc29sdmUocGF0aC5ub3JtYWxpemUoanNTdHIpKSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdnZXRCb29sZWFuQXR0cmlidXRlczAoTGphdmEvaW8vRmlsZTspSScodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2lvX1VuaXhGaWxlU3lzdGVtLCBmaWxlOiBKVk1UeXBlcy5qYXZhX2lvX0ZpbGUpOiB2b2lkIHtcbiAgICB2YXIgZmlsZXBhdGggPSBmaWxlWydqYXZhL2lvL0ZpbGUvcGF0aCddLFxuICAgICAgZmlsZVN5c3RlbSA9IDx0eXBlb2YgSlZNVHlwZXMuamF2YV9pb19GaWxlU3lzdGVtPiAoPFJlZmVyZW5jZUNsYXNzRGF0YTxKVk1UeXBlcy5qYXZhX2lvX0ZpbGVTeXN0ZW0+PiB0aHJlYWQuZ2V0QnNDbCgpLmdldEluaXRpYWxpemVkQ2xhc3ModGhyZWFkLCAnTGphdmEvaW8vRmlsZVN5c3RlbTsnKSkuZ2V0Q29uc3RydWN0b3IodGhyZWFkKTtcblxuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIHN0YXRGaWxlKGZpbGVwYXRoLnRvU3RyaW5nKCksIChzdGF0cykgPT4ge1xuICAgICAgLy8gUmV0dXJucyAwIGlmIGZpbGUgZG9lcyBub3QgZXhpc3QsIG9yIGFueSBvdGhlciBlcnJvciBvY2N1cnMuXG4gICAgICB2YXIgcnY6IG51bWJlciA9IDA7XG4gICAgICBpZiAoc3RhdHMgIT09IG51bGwpIHtcbiAgICAgICAgcnYgfD0gZmlsZVN5c3RlbVsnamF2YS9pby9GaWxlU3lzdGVtL0JBX0VYSVNUUyddO1xuICAgICAgICBpZiAoc3RhdHMuaXNGaWxlKCkpIHtcbiAgICAgICAgICBydiB8PSBmaWxlU3lzdGVtWydqYXZhL2lvL0ZpbGVTeXN0ZW0vQkFfUkVHVUxBUiddO1xuICAgICAgICB9IGVsc2UgaWYgKHN0YXRzLmlzRGlyZWN0b3J5KCkpIHtcbiAgICAgICAgICBydiB8PSBmaWxlU3lzdGVtWydqYXZhL2lvL0ZpbGVTeXN0ZW0vQkFfRElSRUNUT1JZJ107XG4gICAgICAgIH1cbiAgICAgIH1cbiAgICAgIHRocmVhZC5hc3luY1JldHVybihydik7XG4gICAgfSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdjaGVja0FjY2VzcyhMamF2YS9pby9GaWxlO0kpWicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2lvX1VuaXhGaWxlU3lzdGVtLCBmaWxlOiBKVk1UeXBlcy5qYXZhX2lvX0ZpbGUsIGFjY2VzczogbnVtYmVyKTogdm9pZCB7XG4gICAgdmFyIGZpbGVwYXRoID0gZmlsZVsnamF2YS9pby9GaWxlL3BhdGgnXTtcbiAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICBzdGF0RmlsZShmaWxlcGF0aC50b1N0cmluZygpLCAoc3RhdHMpID0+IHtcbiAgICAgIGlmIChzdGF0cyA9PSBudWxsKSB7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybigwKTtcbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIC8vIFhYWDogQXNzdW1pbmcgd2UncmUgb3duZXIvZ3JvdXAvb3RoZXIuIDopXG4gICAgICAgIC8vIFNoaWZ0IGFjY2VzcyBzbyBpdCdzIHByZXNlbnQgaW4gb3duZXIvZ3JvdXAvb3RoZXIuXG4gICAgICAgIC8vIFRoZW4sIEFORCB3aXRoIHRoZSBhY3R1YWwgbW9kZSwgYW5kIGNoZWNrIGlmIHRoZSByZXN1bHQgaXMgYWJvdmUgMC5cbiAgICAgICAgLy8gVGhhdCBpbmRpY2F0ZXMgdGhhdCB0aGUgYWNjZXNzIGJpdCB3ZSdyZSBsb29raW5nIGZvciB3YXMgc2V0IG9uXG4gICAgICAgIC8vIG9uZSBvZiBvd25lci9ncm91cC9vdGhlci5cbiAgICAgICAgdmFyIG1hc2sgPSBhY2Nlc3MgfCAoYWNjZXNzIDw8IDMpIHwgKGFjY2VzcyA8PCA2KTtcbiAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKChzdGF0cy5tb2RlICYgbWFzaykgPiAwID8gMSA6IDApO1xuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0TGFzdE1vZGlmaWVkVGltZShMamF2YS9pby9GaWxlOylKJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfaW9fVW5peEZpbGVTeXN0ZW0sIGZpbGU6IEpWTVR5cGVzLmphdmFfaW9fRmlsZSk6IHZvaWQge1xuICAgIHZhciBmaWxlcGF0aCA9IGZpbGVbJ2phdmEvaW8vRmlsZS9wYXRoJ107XG4gICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgc3RhdEZpbGUoZmlsZXBhdGgudG9TdHJpbmcoKSwgZnVuY3Rpb24gKHN0YXRzKSB7XG4gICAgICBpZiAoc3RhdHMgPT0gbnVsbCkge1xuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oTG9uZy5aRVJPLCBudWxsKTtcbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybihMb25nLmZyb21OdW1iZXIoc3RhdHMubXRpbWUuZ2V0VGltZSgpKSwgbnVsbCk7XG4gICAgICB9XG4gICAgfSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdnZXRMZW5ndGgoTGphdmEvaW8vRmlsZTspSicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2lvX1VuaXhGaWxlU3lzdGVtLCBmaWxlOiBKVk1UeXBlcy5qYXZhX2lvX0ZpbGUpOiB2b2lkIHtcbiAgICB2YXIgZmlsZXBhdGggPSBmaWxlWydqYXZhL2lvL0ZpbGUvcGF0aCddO1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGZzLnN0YXQoZmlsZXBhdGgudG9TdHJpbmcoKSwgKGVyciwgc3RhdCkgPT4ge1xuICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKGVyciAhPSBudWxsID8gTG9uZy5aRVJPIDogTG9uZy5mcm9tTnVtYmVyKHN0YXQuc2l6ZSksIG51bGwpO1xuICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnc2V0UGVybWlzc2lvbihMamF2YS9pby9GaWxlO0laWilaJyh0aHJlYWQ6IEpWTVRocmVhZCwgamF2YVRoaXM6IEpWTVR5cGVzLmphdmFfaW9fVW5peEZpbGVTeXN0ZW0sIGZpbGU6IEpWTVR5cGVzLmphdmFfaW9fRmlsZSwgYWNjZXNzOiBudW1iZXIsIGVuYWJsZTogbnVtYmVyLCBvd25lcm9ubHk6IG51bWJlcik6IHZvaWQge1xuICAgIC8vIEFjY2VzcyBpcyBlcXVhbCB0byBvbmUgb2YgdGhlIGZvbGxvd2luZyBzdGF0aWMgZmllbGRzOlxuICAgIC8vICogRmlsZVN5c3RlbS5BQ0NFU1NfUkVBRCAoMHgwNClcbiAgICAvLyAqIEZpbGVTeXN0ZW0uQUNDRVNTX1dSSVRFICgweDAyKVxuICAgIC8vICogRmlsZVN5c3RlbS5BQ0NFU1NfRVhFQ1VURSAoMHgwMSlcbiAgICAvLyBUaGVzZSBhcmUgY29udmVuaWVudGx5IGlkZW50aWNhbCB0byB0aGVpciBVbml4IGVxdWl2YWxlbnRzLCB3aGljaFxuICAgIC8vIHdlIGhhdmUgdG8gY29udmVydCB0byBmb3IgTm9kZS5cbiAgICAvLyBYWFg6IEN1cnJlbnRseSBhc3N1bWluZyB0aGF0IHRoZSBhYm92ZSBhc3N1bXB0aW9uIGhvbGRzIGFjcm9zcyBKQ0xzLlxuICAgIHZhciBmaWxlcGF0aCA9IGZpbGVbJ2phdmEvaW8vRmlsZS9wYXRoJ10udG9TdHJpbmcoKTtcbiAgICBpZiAob3duZXJvbmx5KSB7XG4gICAgICAvLyBTaGlmdCBpdCA2IGJpdHMgb3ZlciBpbnRvIHRoZSAnb3duZXInIHJlZ2lvbiBvZiB0aGUgYWNjZXNzIG1vZGUuXG4gICAgICBhY2Nlc3MgPDw9IDY7XG4gICAgfSBlbHNlIHtcbiAgICAgIC8vIENsb25lIGl0IGludG8gdGhlICdvd25lcicgYW5kICdncm91cCcgcmVnaW9ucy5cbiAgICAgIGFjY2VzcyB8PSAoYWNjZXNzIDw8IDYpIHwgKGFjY2VzcyA8PCAzKTtcbiAgICB9XG4gICAgaWYgKCFlbmFibGUpIHtcbiAgICAgIC8vIERvIGFuIGludmVydCBhbmQgd2UnbGwgQU5EIHJhdGhlciB0aGFuIE9SLlxuICAgICAgYWNjZXNzID0gfmFjY2VzcztcbiAgICB9XG4gICAgLy8gUmV0dXJucyB0cnVlIG9uIHN1Y2Nlc3MsIGZhbHNlIG9uIGZhaWx1cmUuXG4gICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgLy8gRmV0Y2ggZXhpc3RpbmcgcGVybWlzc2lvbnMgb24gZmlsZS5cbiAgICBzdGF0RmlsZShmaWxlcGF0aCwgKHN0YXRzOiBmcy5TdGF0cykgPT4ge1xuICAgICAgaWYgKHN0YXRzID09IG51bGwpIHtcbiAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKDApO1xuICAgICAgfSBlbHNlIHtcbiAgICAgICAgdmFyIGV4aXN0aW5nX2FjY2VzcyA9IHN0YXRzLm1vZGU7XG4gICAgICAgIC8vIEFwcGx5IG1hc2suXG4gICAgICAgIGFjY2VzcyA9IGVuYWJsZSA/IGV4aXN0aW5nX2FjY2VzcyB8IGFjY2VzcyA6IGV4aXN0aW5nX2FjY2VzcyAmIGFjY2VzcztcbiAgICAgICAgLy8gU2V0IG5ldyBwZXJtaXNzaW9ucy5cbiAgICAgICAgZnMuY2htb2QoZmlsZXBhdGgsIGFjY2VzcywgKGVycj86IE5vZGVKUy5FcnJub0V4Y2VwdGlvbikgPT4ge1xuICAgICAgICAgIHRocmVhZC5hc3luY1JldHVybihlcnIgIT0gbnVsbCA/IDAgOiAxKTtcbiAgICAgICAgfSk7XG4gICAgICB9XG4gICAgfSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdjcmVhdGVGaWxlRXhjbHVzaXZlbHkoTGphdmEvbGFuZy9TdHJpbmc7KVonKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9pb19Vbml4RmlsZVN5c3RlbSwgcGF0aDogSlZNVHlwZXMuamF2YV9sYW5nX1N0cmluZyk6IHZvaWQge1xuICAgIHZhciBmaWxlcGF0aCA9IHBhdGgudG9TdHJpbmcoKTtcbiAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICBzdGF0RmlsZShmaWxlcGF0aCwgKHN0YXQpID0+IHtcbiAgICAgIGlmIChzdGF0ICE9IG51bGwpIHtcbiAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKDApO1xuICAgICAgfSBlbHNlIHtcbiAgICAgICAgZnMub3BlbihmaWxlcGF0aCwgJ3cnLCAoZXJyLCBmZCkgPT4ge1xuICAgICAgICAgIGlmIChlcnIgIT0gbnVsbCkge1xuICAgICAgICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9pby9JT0V4Y2VwdGlvbjsnLCBlcnIubWVzc2FnZSk7XG4gICAgICAgICAgfSBlbHNlIHtcbiAgICAgICAgICAgIGZzLmNsb3NlKGZkLCAoZXJyPzogTm9kZUpTLkVycm5vRXhjZXB0aW9uKSA9PiB7XG4gICAgICAgICAgICAgIGlmIChlcnIgIT0gbnVsbCkge1xuICAgICAgICAgICAgICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvaW8vSU9FeGNlcHRpb247JywgZXJyLm1lc3NhZ2UpO1xuICAgICAgICAgICAgICB9IGVsc2Uge1xuICAgICAgICAgICAgICAgIHRocmVhZC5hc3luY1JldHVybigxKTtcbiAgICAgICAgICAgICAgfVxuICAgICAgICAgICAgfSk7XG4gICAgICAgICAgfVxuICAgICAgICB9KTtcbiAgICAgIH1cbiAgICB9KTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2RlbGV0ZTAoTGphdmEvaW8vRmlsZTspWicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2lvX1VuaXhGaWxlU3lzdGVtLCBmaWxlOiBKVk1UeXBlcy5qYXZhX2lvX0ZpbGUpOiB2b2lkIHtcbiAgICAvLyBEZWxldGUgdGhlIGZpbGUgb3IgZGlyZWN0b3J5IGRlbm90ZWQgYnkgdGhlIGdpdmVuIGFic3RyYWN0XG4gICAgLy8gcGF0aG5hbWUsIHJldHVybmluZyB0cnVlIGlmIGFuZCBvbmx5IGlmIHRoZSBvcGVyYXRpb24gc3VjY2VlZHMuXG4gICAgLy8gSWYgZmlsZSBpcyBhIGRpcmVjdG9yeSwgaXQgbXVzdCBiZSBlbXB0eS5cbiAgICB2YXIgZmlsZXBhdGggPSBmaWxlWydqYXZhL2lvL0ZpbGUvcGF0aCddLnRvU3RyaW5nKCk7XG4gICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgc3RhdEZpbGUoZmlsZXBhdGgsIChzdGF0cykgPT4ge1xuICAgICAgaWYgKHN0YXRzID09IG51bGwpIHtcbiAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKDApO1xuICAgICAgfSBlbHNlIGlmIChzdGF0cy5pc0RpcmVjdG9yeSgpKSB7XG4gICAgICAgIGZzLnJlYWRkaXIoZmlsZXBhdGgsIChlcnIsIGZpbGVzKSA9PiB7XG4gICAgICAgICAgaWYgKGZpbGVzLmxlbmd0aCA+IDApIHtcbiAgICAgICAgICAgIHRocmVhZC5hc3luY1JldHVybigwKTtcbiAgICAgICAgICB9IGVsc2Uge1xuICAgICAgICAgICAgZnMucm1kaXIoZmlsZXBhdGgsIChlcnI/OiBOb2RlSlMuRXJybm9FeGNlcHRpb24pID0+IHtcbiAgICAgICAgICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKDEpO1xuICAgICAgICAgICAgfSk7XG4gICAgICAgICAgfVxuICAgICAgICB9KTtcbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIGZzLnVubGluayhmaWxlcGF0aCwgKGVycj86IE5vZGVKUy5FcnJub0V4Y2VwdGlvbikgPT4ge1xuICAgICAgICAgIHRocmVhZC5hc3luY1JldHVybigxKTtcbiAgICAgICAgfSk7XG4gICAgICB9XG4gICAgfSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdsaXN0KExqYXZhL2lvL0ZpbGU7KVtMamF2YS9sYW5nL1N0cmluZzsnKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9pb19Vbml4RmlsZVN5c3RlbSwgZmlsZTogSlZNVHlwZXMuamF2YV9pb19GaWxlKTogdm9pZCB7XG4gICAgdmFyIGZpbGVwYXRoID0gZmlsZVsnamF2YS9pby9GaWxlL3BhdGgnXSxcbiAgICAgIGJzQ2wgPSB0aHJlYWQuZ2V0QnNDbCgpO1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGZzLnJlYWRkaXIoZmlsZXBhdGgudG9TdHJpbmcoKSwgKGVyciwgZmlsZXMpID0+IHtcbiAgICAgIGlmIChlcnIgIT0gbnVsbCkge1xuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4obnVsbCk7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4odXRpbC5uZXdBcnJheUZyb21EYXRhPEpWTVR5cGVzLmphdmFfbGFuZ19TdHJpbmc+KHRocmVhZCwgdGhyZWFkLmdldEJzQ2woKSwgJ1tMamF2YS9sYW5nL1N0cmluZzsnLCBmaWxlcy5tYXAoKGZpbGU6IHN0cmluZykgPT4gdXRpbC5pbml0U3RyaW5nKHRocmVhZC5nZXRCc0NsKCksIGZpbGUpKSkpO1xuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnY3JlYXRlRGlyZWN0b3J5KExqYXZhL2lvL0ZpbGU7KVonKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9pb19Vbml4RmlsZVN5c3RlbSwgZmlsZTogSlZNVHlwZXMuamF2YV9pb19GaWxlKTogdm9pZCB7XG4gICAgdmFyIGZpbGVwYXRoID0gZmlsZVsnamF2YS9pby9GaWxlL3BhdGgnXS50b1N0cmluZygpO1xuICAgIC8vIEFscmVhZHkgZXhpc3RzLlxuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIHN0YXRGaWxlKGZpbGVwYXRoLCAoc3RhdCkgPT4ge1xuICAgICAgaWYgKHN0YXQgIT0gbnVsbCkge1xuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oMCk7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICBmcy5ta2RpcihmaWxlcGF0aCwgKGVycj86IE5vZGVKUy5FcnJub0V4Y2VwdGlvbikgPT4ge1xuICAgICAgICAgIHRocmVhZC5hc3luY1JldHVybihlcnIgIT0gbnVsbCA/IDAgOiAxKTtcbiAgICAgICAgfSk7XG4gICAgICB9XG4gICAgfSk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdyZW5hbWUwKExqYXZhL2lvL0ZpbGU7TGphdmEvaW8vRmlsZTspWicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2lvX1VuaXhGaWxlU3lzdGVtLCBmaWxlMTogSlZNVHlwZXMuamF2YV9pb19GaWxlLCBmaWxlMjogSlZNVHlwZXMuamF2YV9pb19GaWxlKTogdm9pZCB7XG4gICAgdmFyIGZpbGUxcGF0aCA9IGZpbGUxWydqYXZhL2lvL0ZpbGUvcGF0aCddLnRvU3RyaW5nKCksXG4gICAgICBmaWxlMnBhdGggPSBmaWxlMlsnamF2YS9pby9GaWxlL3BhdGgnXS50b1N0cmluZygpO1xuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGZzLnJlbmFtZShmaWxlMXBhdGgsIGZpbGUycGF0aCwgKGVycj86IE5vZGVKUy5FcnJub0V4Y2VwdGlvbikgPT4ge1xuICAgICAgdGhyZWFkLmFzeW5jUmV0dXJuKGVyciAhPSBudWxsID8gMCA6IDEpO1xuICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnc2V0TGFzdE1vZGlmaWVkVGltZShMamF2YS9pby9GaWxlO0opWicodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX2lvX1VuaXhGaWxlU3lzdGVtLCBmaWxlOiBKVk1UeXBlcy5qYXZhX2lvX0ZpbGUsIHRpbWU6IExvbmcpOiB2b2lkIHtcbiAgICB2YXIgbXRpbWUgPSB0aW1lLnRvTnVtYmVyKCksXG4gICAgICBhdGltZSA9IChuZXcgRGF0ZSkuZ2V0VGltZSgpLFxuICAgICAgZmlsZXBhdGggPSBmaWxlWydqYXZhL2lvL0ZpbGUvcGF0aCddLnRvU3RyaW5nKCk7XG4gICAgdGhyZWFkLnNldFN0YXR1cyhUaHJlYWRTdGF0dXMuQVNZTkNfV0FJVElORyk7XG4gICAgZnMudXRpbWVzKGZpbGVwYXRoLCBhdGltZSwgbXRpbWUsIChlcnI/OiBOb2RlSlMuRXJybm9FeGNlcHRpb24pID0+IHtcbiAgICAgIHRocmVhZC5hc3luY1JldHVybigxKTtcbiAgICB9KTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3NldFJlYWRPbmx5KExqYXZhL2lvL0ZpbGU7KVonKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9pb19Vbml4RmlsZVN5c3RlbSwgZmlsZTogSlZNVHlwZXMuamF2YV9pb19GaWxlKTogdm9pZCB7XG4gICAgLy8gV2UnbGwgYmUgdW5zZXR0aW5nIHdyaXRlIHBlcm1pc3Npb25zLlxuICAgIC8vIExlYWRpbmcgMG8gaW5kaWNhdGVzIG9jdGFsLlxuICAgIHZhciBmaWxlcGF0aCA9IGZpbGVbJ2phdmEvaW8vRmlsZS9wYXRoJ10udG9TdHJpbmcoKSxcbiAgICAgIG1hc2sgPSB+MHg5MjtcbiAgICB0aHJlYWQuc2V0U3RhdHVzKFRocmVhZFN0YXR1cy5BU1lOQ19XQUlUSU5HKTtcbiAgICBzdGF0RmlsZShmaWxlcGF0aCwgKHN0YXRzKSA9PiB7XG4gICAgICBpZiAoc3RhdHMgPT0gbnVsbCkge1xuICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oMCk7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICBmcy5jaG1vZChmaWxlcGF0aCwgc3RhdHMubW9kZSAmIG1hc2ssIChlcnI/OiBOb2RlSlMuRXJybm9FeGNlcHRpb24pID0+IHtcbiAgICAgICAgICB0aHJlYWQuYXN5bmNSZXR1cm4oZXJyICE9IG51bGwgPyAwIDogMSk7XG4gICAgICAgIH0pO1xuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0U3BhY2UoTGphdmEvaW8vRmlsZTtJKUonKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV9pb19Vbml4RmlsZVN5c3RlbSwgZmlsZTogSlZNVHlwZXMuamF2YV9pb19GaWxlLCBhcmcxOiBudW1iZXIpOiBMb25nIHtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvVW5zYXRpc2ZpZWRMaW5rRXJyb3I7JywgJ05hdGl2ZSBtZXRob2Qgbm90IGltcGxlbWVudGVkLicpO1xuICAgIC8vIFNhdGlzZnkgVHlwZVNjcmlwdCByZXR1cm4gdHlwZS5cbiAgICByZXR1cm4gbnVsbDtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2luaXRJRHMoKVYnKHRocmVhZDogSlZNVGhyZWFkKTogdm9pZCB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL1Vuc2F0aXNmaWVkTGlua0Vycm9yOycsICdOYXRpdmUgbWV0aG9kIG5vdCBpbXBsZW1lbnRlZC4nKTtcbiAgfVxuXG59XG5cbnJlZ2lzdGVyTmF0aXZlcyh7XG4gICdqYXZhL2lvL0NvbnNvbGUnOiBqYXZhX2lvX0NvbnNvbGUsXG4gICdqYXZhL2lvL0ZpbGVEZXNjcmlwdG9yJzogamF2YV9pb19GaWxlRGVzY3JpcHRvcixcbiAgJ2phdmEvaW8vRmlsZUlucHV0U3RyZWFtJzogamF2YV9pb19GaWxlSW5wdXRTdHJlYW0sXG4gICdqYXZhL2lvL0ZpbGVPdXRwdXRTdHJlYW0nOiBqYXZhX2lvX0ZpbGVPdXRwdXRTdHJlYW0sXG4gICdqYXZhL2lvL09iamVjdElucHV0U3RyZWFtJzogamF2YV9pb19PYmplY3RJbnB1dFN0cmVhbSxcbiAgJ2phdmEvaW8vT2JqZWN0T3V0cHV0U3RyZWFtJzogamF2YV9pb19PYmplY3RPdXRwdXRTdHJlYW0sXG4gICdqYXZhL2lvL09iamVjdFN0cmVhbUNsYXNzJzogamF2YV9pb19PYmplY3RTdHJlYW1DbGFzcyxcbiAgJ2phdmEvaW8vUmFuZG9tQWNjZXNzRmlsZSc6IGphdmFfaW9fUmFuZG9tQWNjZXNzRmlsZSxcbiAgJ2phdmEvaW8vVW5peEZpbGVTeXN0ZW0nOiBqYXZhX2lvX1VuaXhGaWxlU3lzdGVtXG59KTtcbiJdfQ==