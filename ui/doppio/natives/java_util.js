'use strict';
var DoppioJVM = require('../doppiojvm');
var util = DoppioJVM.VM.Util;
var Long = DoppioJVM.VM.Long;
var AbstractClasspathJar = DoppioJVM.VM.ClassFile.AbstractClasspathJar;
var BrowserFS = require('browserfs');
var path = require('path');
var fs = require('fs');
var ThreadStatus = DoppioJVM.VM.Enums.ThreadStatus;
var deflate = require('pako/lib/zlib/deflate');
var inflate = require('pako/lib/zlib/inflate');
var crc32 = require('pako/lib/zlib/crc32');
var adler32 = require('pako/lib/zlib/adler32');
var ZStreamCons = require('pako/lib/zlib/zstream');
var BFSUtils = BrowserFS.BFSRequire('bfs_utils');
var MAX_WBITS = 15;
var ZipFiles = {};
var ZipEntries = {};
var ZStreams = {};
var NextId = 1;
function OpenItem(item, map) {
    var id = NextId++;
    map[id] = item;
    return id;
}
function GetItem(thread, id, map, errMsg) {
    var item = map[id];
    if (!item) {
        thread.throwNewException('Ljava/lang/IllegalStateException;', errMsg);
        return null;
    } else {
        return item;
    }
}
function CloseItem(id, map) {
    delete map[id];
}
function OpenZipFile(zfile) {
    return OpenItem(zfile, ZipFiles);
}
function CloseZipFile(id) {
    CloseItem(id, ZipFiles);
}
function GetZipFile(thread, id) {
    return GetItem(thread, id, ZipFiles, 'ZipFile not found.');
}
function OpenZipEntry(zentry) {
    return OpenItem(zentry, ZipEntries);
}
function CloseZipEntry(id) {
    CloseItem(id, ZipEntries);
}
function GetZipEntry(thread, id) {
    return GetItem(thread, id, ZipEntries, 'Invalid ZipEntry.');
}
function OpenZStream(inflaterState) {
    return OpenItem(inflaterState, ZStreams);
}
function CloseZStream(id) {
    CloseItem(id, ZStreams);
}
function GetZStream(thread, id) {
    return GetItem(thread, id, ZStreams, 'Inflater not found.');
}
var CanUseCopyFastPath = false;
if (typeof Int8Array !== 'undefined') {
    var i8arr = new Int8Array(1);
    var b = new Buffer(i8arr.buffer);
    i8arr[0] = 100;
    CanUseCopyFastPath = i8arr[0] == b.readInt8(0);
}
function isUint8Array(arr) {
    if (arr && typeof Uint8Array !== 'undefined' && arr instanceof Uint8Array) {
        return true;
    }
    return false;
}
function isInt8Array(arr) {
    if (arr && typeof Int8Array !== 'undefined' && arr instanceof Int8Array) {
        return true;
    }
    return false;
}
function i82u8(arr, start, len) {
    if (isInt8Array(arr)) {
        return new Uint8Array(arr.buffer, arr.byteOffset + start, len);
    } else if (Array.isArray(arr)) {
        if (typeof Uint8Array !== 'undefined') {
            var i8arr = new Int8Array(len);
            if (start === 0 && len === arr.length) {
                i8arr.set(arr, 0);
            } else {
                i8arr.set(arr.slice(start, start + len), 0);
            }
            return new Uint8Array(i8arr.buffer);
        } else {
            var rv = new Array(len);
            for (var i = 0; i < len; i++) {
                rv[i] = arr[start + i] & 255;
            }
            return rv;
        }
    } else {
        throw new TypeError('Invalid array.');
    }
}
function u82i8(arr, start, len) {
    if (isUint8Array(arr)) {
        return new Int8Array(arr.buffer, arr.byteOffset + start, len);
    } else if (Array.isArray(arr)) {
        if (typeof Int8Array !== 'undefined') {
            var u8arr = new Uint8Array(len);
            if (start === 0 && len === arr.length) {
                u8arr.set(arr, 0);
            } else {
                u8arr.set(arr.slice(start, start + len), 0);
            }
            return new Int8Array(u8arr.buffer);
        } else {
            var rv = new Array(len);
            for (var i = 0; i < len; i++) {
                rv[i] = arr[start + i];
                if (rv[i] > 127) {
                    rv[i] |= 4294967168;
                }
            }
            return rv;
        }
    } else {
        throw new TypeError('Invalid array.');
    }
}
function buff2i8(buff) {
    var arrayish = BFSUtils.buffer2Arrayish(buff);
    return u82i8(arrayish, 0, arrayish.length);
}
var java_util_concurrent_atomic_AtomicLong = function () {
    function java_util_concurrent_atomic_AtomicLong() {
    }
    java_util_concurrent_atomic_AtomicLong['VMSupportsCS8()Z'] = function (thread) {
        return true;
    };
    return java_util_concurrent_atomic_AtomicLong;
}();
var java_util_jar_JarFile = function () {
    function java_util_jar_JarFile() {
    }
    java_util_jar_JarFile['getMetaInfEntryNames()[Ljava/lang/String;'] = function (thread, javaThis) {
        var zip = GetZipFile(thread, javaThis['java/util/zip/ZipFile/jzfile'].toNumber());
        if (zip) {
            if (!zip.existsSync('/META-INF')) {
                return null;
            }
            var explorePath = ['/META-INF'];
            var bsCl = thread.getBsCl();
            var foundFiles = [util.initString(bsCl, 'META-INF/')];
            while (explorePath.length > 0) {
                var p = explorePath.pop();
                var dirListing = zip.readdirSync(p);
                for (var i = 0; i < dirListing.length; i++) {
                    var newP = p + '/' + dirListing[i];
                    if (zip.statSync(newP, false).isDirectory()) {
                        explorePath.push(newP);
                        foundFiles.push(util.initString(bsCl, newP.slice(1) + '/'));
                    } else {
                        foundFiles.push(util.initString(bsCl, newP.slice(1)));
                    }
                }
                return util.newArrayFromData(thread, bsCl, '[Ljava/lang/String;', foundFiles);
            }
        }
    };
    return java_util_jar_JarFile;
}();
var java_util_logging_FileHandler = function () {
    function java_util_logging_FileHandler() {
    }
    java_util_logging_FileHandler['isSetUID()Z'] = function (thread) {
        return false;
    };
    return java_util_logging_FileHandler;
}();
var java_util_TimeZone = function () {
    function java_util_TimeZone() {
    }
    java_util_TimeZone['getSystemTimeZoneID(Ljava/lang/String;)Ljava/lang/String;'] = function (thread, arg0) {
        var offset = new Date().getTimezoneOffset() / 60;
        return thread.getJVM().internString('GMT' + (offset > 0 ? '-' : '+') + offset);
    };
    java_util_TimeZone['getSystemGMTOffsetID()Ljava/lang/String;'] = function (thread) {
        return null;
    };
    return java_util_TimeZone;
}();
var java_util_zip_Adler32 = function () {
    function java_util_zip_Adler32() {
    }
    java_util_zip_Adler32['update(II)I'] = function (thread, adler, byte) {
        return adler32(adler, [byte & 255], 1, 0);
    };
    java_util_zip_Adler32['updateBytes(I[BII)I'] = function (thread, adler, b, off, len) {
        return adler32(adler, i82u8(b.array, off, len), len, 0);
    };
    java_util_zip_Adler32['updateByteBuffer(IJII)I'] = function (thread, adler, addr, off, len) {
        var heap = thread.getJVM().getHeap();
        var buff = BFSUtils.buffer2Arrayish(heap.get_buffer(addr.toNumber() + off, len));
        return adler32(adler, buff, buff.length, 0);
    };
    return java_util_zip_Adler32;
}();
var java_util_zip_CRC32 = function () {
    function java_util_zip_CRC32() {
    }
    java_util_zip_CRC32['update(II)I'] = function (thread, crc, byte) {
        return crc32(crc, [byte & 255], 1, 0);
    };
    java_util_zip_CRC32['updateBytes(I[BII)I'] = function (thread, crc, b, off, len) {
        return crc32(crc, i82u8(b.array, off, len), len, 0);
    };
    java_util_zip_CRC32['updateByteBuffer(IJII)I'] = function (thread, crc, addr, off, len) {
        var heap = thread.getJVM().getHeap();
        var buff = BFSUtils.buffer2Arrayish(heap.get_buffer(addr.toNumber() + off, len));
        return crc32(crc, buff, buff.length, 0);
    };
    return java_util_zip_CRC32;
}();
var java_util_zip_Deflater = function () {
    function java_util_zip_Deflater() {
    }
    java_util_zip_Deflater['initIDs()V'] = function (thread) {
    };
    java_util_zip_Deflater['init(IIZ)J'] = function (thread, level, strategy, nowrap) {
        var DEF_MEM_LEVEL = 8;
        var Z_DEFLATED = 8;
        var strm = new ZStreamCons();
        var ret = deflate.deflateInit2(strm, level, Z_DEFLATED, nowrap ? -MAX_WBITS : MAX_WBITS, DEF_MEM_LEVEL, strategy);
        if (ret != 0) {
            var msg = strm.msg ? strm.msg : ret == -2 ? 'inflateInit2 returned Z_STREAM_ERROR' : 'unknown error initializing zlib library';
            thread.throwNewException('Ljava/lang/InternalError;', msg);
        } else {
            var num = OpenZStream(strm);
            return Long.fromNumber(num);
        }
    };
    java_util_zip_Deflater['setDictionary(J[BII)V'] = function (thread, arg0, arg1, arg2, arg3) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_util_zip_Deflater['deflateBytes(J[BIII)I'] = function (thread, javaThis, addr, b, off, len, flush) {
        var strm = GetZStream(thread, addr.toNumber());
        if (!strm)
            return;
        var thisBuf = javaThis['java/util/zip/Deflater/buf'];
        var thisOff = javaThis['java/util/zip/Deflater/off'];
        var thisLen = javaThis['java/util/zip/Deflater/len'];
        var inBuf = thisBuf.array;
        var outBuf = b.array;
        strm.input = i82u8(inBuf, 0, inBuf.length);
        strm.next_in = thisOff;
        strm.avail_in = thisLen;
        strm.output = i82u8(outBuf, 0, outBuf.length);
        strm.next_out = off;
        strm.avail_out = len;
        if (javaThis['java/util/zip/Deflater/setParams']) {
            var level = javaThis['java/util/zip/Deflater/level'];
            var strategy = javaThis['java/util/zip/Deflater/level'];
            var newStream = new ZStreamCons();
            var res = deflate.deflateInit2(newStream, level, strm.state.method, strm.state.windowBits, strm.state.memLevel, strategy);
            ZStreams[addr.toNumber()] = newStream;
            switch (res) {
            case 0:
                javaThis['java/util/zip/Deflater/setParams'] = 0;
                thisOff += thisLen - strm.avail_in;
                javaThis['java/util/zip/Deflater/off'] = thisOff;
                javaThis['java/util/zip/Deflater/len'] = strm.avail_in;
                return len - strm.avail_out;
            case -5:
                javaThis['java/util/zip/Deflater/setParams'] = 0;
                return 0;
            default:
                thread.throwNewException('Ljava/lang/InternalError;', strm.msg);
            }
        } else {
            var finish = javaThis['java/util/zip/Deflater/finish'];
            var res = deflate.deflate(strm, finish ? 4 : flush);
            switch (res) {
            case 1:
                javaThis['java/util/zip/Deflater/finished'] = 1;
            case 0:
                thisOff += thisLen - strm.avail_in;
                javaThis['java/util/zip/Deflater/off'] = thisOff;
                javaThis['java/util/zip/Deflater/len'] = strm.avail_in;
                return len - strm.avail_out;
            case -5:
                return 0;
            default:
                thread.throwNewException('Ljava/lang/InternalError;', strm.msg);
            }
        }
    };
    java_util_zip_Deflater['getAdler(J)I'] = function (thread, addr) {
        var strm = GetZStream(thread, addr.toNumber());
        if (strm) {
            return strm.adler;
        }
    };
    java_util_zip_Deflater['reset(J)V'] = function (thread, addr) {
        var strm = GetZStream(thread, addr.toNumber());
        if (strm) {
            if (deflate.deflateReset(strm) !== 0) {
                thread.throwNewException('Ljava/lang/InternalError;', strm.msg);
            }
        }
    };
    java_util_zip_Deflater['end(J)V'] = function (thread, addr) {
        var strm = GetZStream(thread, addr.toNumber());
        if (strm) {
            if (deflate.deflateEnd(strm) === -2) {
                thread.throwNewException('Ljava/lang/InternalError;', strm.msg);
            } else {
                CloseZStream(addr.toNumber());
            }
        }
    };
    return java_util_zip_Deflater;
}();
var java_util_zip_Inflater = function () {
    function java_util_zip_Inflater() {
    }
    java_util_zip_Inflater['initIDs()V'] = function (thread) {
    };
    java_util_zip_Inflater['init(Z)J'] = function (thread, nowrap) {
        var strm = new ZStreamCons();
        var ret = inflate.inflateInit2(strm, nowrap ? -MAX_WBITS : MAX_WBITS);
        switch (ret) {
        case 0:
            var num = OpenZStream(strm);
            return Long.fromNumber(num);
        default:
            var msg = strm.msg ? strm.msg : ret == -2 ? 'inflateInit2 returned Z_STREAM_ERROR' : 'unknown error initializing zlib library';
            thread.throwNewException('Ljava/lang/InternalError;', msg);
            break;
        }
    };
    java_util_zip_Inflater['setDictionary(J[BII)V'] = function (thread, arg0, arg1, arg2, arg3) {
        thread.throwNewException('Ljava/lang/UnsatisfiedLinkError;', 'Native method not implemented.');
    };
    java_util_zip_Inflater['inflateBytes(J[BII)I'] = function (thread, javaThis, addr, b, off, len) {
        var strm = GetZStream(thread, addr.toNumber());
        if (!strm) {
            return;
        }
        var thisBuf = javaThis['java/util/zip/Inflater/buf'];
        var thisOff = javaThis['java/util/zip/Inflater/off'];
        var thisLen = javaThis['java/util/zip/Inflater/len'];
        if (thisLen === 0 || len === 0) {
            return 0;
        }
        var inBuf = thisBuf.array;
        var outBuf = b.array;
        strm.input = i82u8(inBuf, 0, inBuf.length);
        strm.next_in = thisOff;
        strm.avail_in = thisLen;
        strm.output = i82u8(outBuf, 0, outBuf.length);
        strm.next_out = off;
        strm.avail_out = len;
        var ret = inflate.inflate(strm, 2);
        var lenRead = len - strm.avail_out;
        if (!isInt8Array(outBuf)) {
            var result = strm.output;
            for (var i = 0; i < lenRead; i++) {
                var byte = result[i + off];
                if (byte > 127) {
                    byte |= 4294967168;
                }
                outBuf[i + off] = byte;
            }
        }
        switch (ret) {
        case 1:
            javaThis['java/util/zip/Inflater/finished'] = 1;
        case 0:
            thisOff += thisLen - strm.avail_in;
            javaThis['java/util/zip/Inflater/off'] = thisOff;
            javaThis['java/util/zip/Inflater/len'] = strm.avail_in;
            return lenRead;
        case 2:
            javaThis['java/util/zip/Inflater/needDict'] = 1;
            thisOff += thisLen - strm.avail_in;
            javaThis['java/util/zip/Inflater/off'] = thisOff;
            javaThis['java/util/zip/Inflater/len'] = strm.avail_in;
            return 0;
        case -5:
            return 0;
        case -3:
            thread.throwNewException('Ljava/util/zip/DataFormatException;', strm.msg);
            return;
        default:
            thread.throwNewException('Ljava/lang/InternalError;', strm.msg);
            return;
        }
    };
    java_util_zip_Inflater['getAdler(J)I'] = function (thread, addr) {
        var strm = GetZStream(thread, addr.toNumber());
        if (strm) {
            return strm.adler;
        }
    };
    java_util_zip_Inflater['reset(J)V'] = function (thread, addr) {
        var addrNum = addr.toNumber();
        var strm = GetZStream(thread, addrNum);
        if (strm) {
            var newStrm = new ZStreamCons();
            var ret = inflate.inflateInit2(newStrm, strm.state.wrap ? MAX_WBITS : -MAX_WBITS);
            ZStreams[addrNum] = newStrm;
        }
    };
    java_util_zip_Inflater['end(J)V'] = function (thread, addr) {
        var strm = GetZStream(thread, addr.toNumber());
        if (strm) {
            if (inflate.inflateEnd(strm) === -2) {
                thread.throwNewException('Ljava/lang/InternalError;', strm.msg);
            } else {
                CloseZStream(addr.toNumber());
            }
        }
    };
    return java_util_zip_Inflater;
}();
var java_util_zip_ZipFile = function () {
    function java_util_zip_ZipFile() {
    }
    java_util_zip_ZipFile['initIDs()V'] = function (thread) {
    };
    java_util_zip_ZipFile['getEntry(J[BZ)J'] = function (thread, jzfile, nameBytes, addSlash) {
        var zipfs = GetZipFile(thread, jzfile.toNumber());
        if (zipfs) {
            var name_1 = new Buffer(nameBytes.array).toString('utf8');
            if (name_1[0] !== '/') {
                name_1 = '/' + name_1;
            }
            name_1 = path.resolve(name_1);
            try {
                return Long.fromNumber(OpenZipEntry(zipfs.getCentralDirectoryEntry(name_1)));
            } catch (e) {
                return Long.ZERO;
            }
        }
    };
    java_util_zip_ZipFile['freeEntry(JJ)V'] = function (thread, jzfile, jzentry) {
        CloseZipEntry(jzentry.toNumber());
    };
    java_util_zip_ZipFile['getNextEntry(JI)J'] = function (thread, jzfile, index) {
        var zipfs = GetZipFile(thread, jzfile.toNumber());
        if (zipfs) {
            try {
                return Long.fromNumber(OpenZipEntry(zipfs.getCentralDirectoryEntryAt(index)));
            } catch (e) {
                return Long.ZERO;
            }
        }
    };
    java_util_zip_ZipFile['close(J)V'] = function (thread, jzfile) {
        CloseZipFile(jzfile.toNumber());
    };
    java_util_zip_ZipFile['open(Ljava/lang/String;IJZ)J'] = function (thread, nameObj, mode, modified, usemmap) {
        var name = nameObj.toString();
        var cpath = thread.getBsCl().getClassPathItems();
        for (var i = 0; i < cpath.length; i++) {
            var cpathItem = cpath[i];
            if (cpathItem instanceof AbstractClasspathJar) {
                if (path.resolve(cpathItem.getPath()) === path.resolve(name)) {
                    return Long.fromNumber(OpenZipFile(cpathItem.getFS()));
                }
            }
        }
        thread.setStatus(ThreadStatus.ASYNC_WAITING);
        fs.readFile(name, function (err, data) {
            if (err) {
                thread.throwNewException('Ljava/io/IOException;', err.message);
            } else {
                thread.asyncReturn(Long.fromNumber(OpenZipFile(new BrowserFS.FileSystem.ZipFS(data, name))), null);
            }
        });
    };
    java_util_zip_ZipFile['getTotal(J)I'] = function (thread, jzfile) {
        var zipfs = GetZipFile(thread, jzfile.toNumber());
        if (zipfs) {
            return zipfs.getNumberOfCentralDirectoryEntries();
        }
    };
    java_util_zip_ZipFile['startsWithLOC(J)Z'] = function (thread, arg0) {
        return 1;
    };
    java_util_zip_ZipFile['read(JJJ[BII)I'] = function (thread, jzfile, jzentry, pos, b, off, len) {
        var zipentry = GetZipEntry(thread, jzentry.toNumber());
        var posNum = pos.toNumber();
        if (zipentry) {
            if (len <= 0) {
                return 0;
            }
            var data = zipentry.getRawData();
            if (posNum >= data.length) {
                thread.throwNewException('Ljava/io/IOException;', 'End of zip file.');
                return;
            }
            if (posNum + len > data.length) {
                len = data.length - posNum;
            }
            var arr = b.array;
            if (CanUseCopyFastPath) {
                var i8arr = arr;
                var b_1 = new Buffer(i8arr.buffer);
                return data.copy(b_1, off + i8arr.byteOffset, posNum, posNum + len);
            } else {
                for (var i = 0; i < len; i++) {
                    arr[off + i] = data.readInt8(posNum + i);
                }
                return len;
            }
        }
    };
    java_util_zip_ZipFile['getEntryTime(J)J'] = function (thread, jzentry) {
        var zipentry = GetZipEntry(thread, jzentry.toNumber());
        if (zipentry) {
            return Long.fromNumber(zipentry.rawLastModFileTime());
        }
    };
    java_util_zip_ZipFile['getEntryCrc(J)J'] = function (thread, jzentry) {
        var zipentry = GetZipEntry(thread, jzentry.toNumber());
        if (zipentry) {
            return Long.fromNumber(zipentry.crc32());
        }
    };
    java_util_zip_ZipFile['getEntryCSize(J)J'] = function (thread, jzentry) {
        var zipentry = GetZipEntry(thread, jzentry.toNumber());
        if (zipentry) {
            return Long.fromNumber(zipentry.compressedSize());
        }
    };
    java_util_zip_ZipFile['getEntrySize(J)J'] = function (thread, jzentry) {
        var zipentry = GetZipEntry(thread, jzentry.toNumber());
        if (zipentry) {
            return Long.fromNumber(zipentry.uncompressedSize());
        }
    };
    java_util_zip_ZipFile['getEntryMethod(J)I'] = function (thread, jzentry) {
        var zipentry = GetZipEntry(thread, jzentry.toNumber());
        if (zipentry) {
            return zipentry.compressionMethod();
        }
    };
    java_util_zip_ZipFile['getEntryFlag(J)I'] = function (thread, jzentry) {
        var zipentry = GetZipEntry(thread, jzentry.toNumber());
        if (zipentry) {
            return zipentry.flag();
        }
    };
    java_util_zip_ZipFile['getCommentBytes(J)[B'] = function (thread, jzfile) {
        var zipfile = GetZipFile(thread, jzfile.toNumber());
        if (zipfile) {
            var eocd = zipfile.getEndOfCentralDirectory();
            var comment = eocd.rawCdZipComment();
            return util.newArrayFromDataWithClass(thread, thread.getBsCl().getInitializedClass(thread, '[B'), buff2i8(comment));
        }
    };
    java_util_zip_ZipFile['getEntryBytes(JI)[B'] = function (thread, jzentry, type) {
        var zipentry = GetZipEntry(thread, jzentry.toNumber());
        if (zipentry) {
            switch (type) {
            case 2:
                return util.newArrayFromDataWithClass(thread, thread.getBsCl().getInitializedClass(thread, '[B'), buff2i8(zipentry.rawFileComment()));
            case 1:
                return util.newArrayFromDataWithClass(thread, thread.getBsCl().getInitializedClass(thread, '[B'), buff2i8(zipentry.extraField()));
            case 0:
                return util.newArrayFromDataWithClass(thread, thread.getBsCl().getInitializedClass(thread, '[B'), buff2i8(zipentry.rawFileName()));
            default:
                return null;
            }
        }
    };
    java_util_zip_ZipFile['getZipMessage(J)Ljava/lang/String;'] = function (thread, jzfile) {
        return util.initString(thread.getBsCl(), 'Something bad happened.');
    };
    return java_util_zip_ZipFile;
}();
registerNatives({
    'java/util/concurrent/atomic/AtomicLong': java_util_concurrent_atomic_AtomicLong,
    'java/util/jar/JarFile': java_util_jar_JarFile,
    'java/util/logging/FileHandler': java_util_logging_FileHandler,
    'java/util/TimeZone': java_util_TimeZone,
    'java/util/zip/Adler32': java_util_zip_Adler32,
    'java/util/zip/CRC32': java_util_zip_CRC32,
    'java/util/zip/Deflater': java_util_zip_Deflater,
    'java/util/zip/Inflater': java_util_zip_Inflater,
    'java/util/zip/ZipFile': java_util_zip_ZipFile
});
//# sourceMappingURL=java_util.js.map