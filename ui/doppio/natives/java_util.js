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
//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJzb3VyY2VzIjpbIi4uLy4uLy4uLy4uL3NyYy9uYXRpdmVzL2phdmFfdXRpbC50cyJdLCJuYW1lcyI6W10sIm1hcHBpbmdzIjoiO0FBRUEsSUFBWSxTQUFBLEdBQVMsT0FBQSxDQUFNLGNBQU4sQ0FBckI7QUFJQSxJQUFPLElBQUEsR0FBTyxTQUFBLENBQVUsRUFBVixDQUFhLElBQTNCO0FBQ0EsSUFBTyxJQUFBLEdBQU8sU0FBQSxDQUFVLEVBQVYsQ0FBYSxJQUEzQjtBQUNBLElBQU8sb0JBQUEsR0FBdUIsU0FBQSxDQUFVLEVBQVYsQ0FBYSxTQUFiLENBQXVCLG9CQUFyRDtBQUNBLElBQU8sU0FBQSxHQUFTLE9BQUEsQ0FBVyxXQUFYLENBQWhCO0FBQ0EsSUFBTyxJQUFBLEdBQUksT0FBQSxDQUFXLE1BQVgsQ0FBWDtBQUNBLElBQU8sRUFBQSxHQUFFLE9BQUEsQ0FBVyxJQUFYLENBQVQ7QUFDQSxJQUFPLFlBQUEsR0FBZSxTQUFBLENBQVUsRUFBVixDQUFhLEtBQWIsQ0FBbUIsWUFBekM7QUFJQSxJQUFPLE9BQUEsR0FBTyxPQUFBLENBQVcsdUJBQVgsQ0FBZDtBQUNBLElBQU8sT0FBQSxHQUFPLE9BQUEsQ0FBVyx1QkFBWCxDQUFkO0FBQ0EsSUFBTyxLQUFBLEdBQUssT0FBQSxDQUFXLHFCQUFYLENBQVo7QUFDQSxJQUFPLE9BQUEsR0FBTyxPQUFBLENBQVcsdUJBQVgsQ0FBZDtBQUNBLElBQU8sV0FBQSxHQUFXLE9BQUEsQ0FBVyx1QkFBWCxDQUFsQjtBQU1BLElBQUksUUFBQSxHQUFXLFNBQUEsQ0FBVSxVQUFWLENBQXFCLFdBQXJCLENBQWY7QUFDQSxJQUFNLFNBQUEsR0FBWSxFQUFsQjtBQU1BLElBQUksUUFBQSxHQUFtQyxFQUF2QztBQUNBLElBQUksVUFBQSxHQUFnRCxFQUFwRDtBQUNBLElBQUksUUFBQSxHQUFvQyxFQUF4QztBQUVBLElBQUksTUFBQSxHQUFpQixDQUFyQjtBQUNBLFNBQUEsUUFBQSxDQUFxQixJQUFyQixFQUE4QixHQUE5QixFQUFvRDtBQUFB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QUFwRSxDQTNMaEI7QUFBQSxJQStMQSxPQUFBLHFCQUFBLENBL0xBO0FBQUEsQ0FBQSxFQUFBO0FBaU1BLGVBQUEsQ0FBZ0I7QUFBQSxJQUNkLDBDQUEwQyxzQ0FENUI7QUFBQSxJQUVkLHlCQUF5QixxQkFGWDtBQUFBLElBR2QsaUNBQWlDLDZCQUhuQjtBQUFBLElBSWQsc0JBQXNCLGtCQUpSO0FBQUEsSUFLZCx5QkFBeUIscUJBTFg7QUFBQSxJQU1kLHVCQUF1QixtQkFOVDtBQUFBLElBT2QsMEJBQTBCLHNCQVBaO0FBQUEsSUFRZCwwQkFBMEIsc0JBUlo7QUFBQSxJQVNkLHlCQUF5QixxQkFUWDtBQUFBLENBQWhCIiwic291cmNlc0NvbnRlbnQiOlsiLy8vIDxyZWZlcmVuY2UgcGF0aD1cIi4uLy4uL3ZlbmRvci9wYWtvLmQudHNcIiAvPlxuaW1wb3J0IEpWTVR5cGVzID0gcmVxdWlyZSgnLi4vLi4vaW5jbHVkZXMvSlZNVHlwZXMnKTtcbmltcG9ydCAqIGFzIERvcHBpb0pWTSBmcm9tICcuLi9kb3BwaW9qdm0nO1xuaW1wb3J0IEpWTVRocmVhZCA9IERvcHBpb0pWTS5WTS5UaHJlYWRpbmcuSlZNVGhyZWFkO1xuaW1wb3J0IFJlZmVyZW5jZUNsYXNzRGF0YSA9IERvcHBpb0pWTS5WTS5DbGFzc0ZpbGUuUmVmZXJlbmNlQ2xhc3NEYXRhO1xuaW1wb3J0IGxvZ2dpbmcgPSBEb3BwaW9KVk0uRGVidWcuTG9nZ2luZztcbmltcG9ydCB1dGlsID0gRG9wcGlvSlZNLlZNLlV0aWw7XG5pbXBvcnQgTG9uZyA9IERvcHBpb0pWTS5WTS5Mb25nO1xuaW1wb3J0IEFic3RyYWN0Q2xhc3NwYXRoSmFyID0gRG9wcGlvSlZNLlZNLkNsYXNzRmlsZS5BYnN0cmFjdENsYXNzcGF0aEphcjtcbmltcG9ydCBCcm93c2VyRlMgPSByZXF1aXJlKCdicm93c2VyZnMnKTtcbmltcG9ydCBwYXRoID0gcmVxdWlyZSgncGF0aCcpO1xuaW1wb3J0IGZzID0gcmVxdWlyZSgnZnMnKTtcbmltcG9ydCBUaHJlYWRTdGF0dXMgPSBEb3BwaW9KVk0uVk0uRW51bXMuVGhyZWFkU3RhdHVzO1xuaW1wb3J0IEFycmF5Q2xhc3NEYXRhID0gRG9wcGlvSlZNLlZNLkNsYXNzRmlsZS5BcnJheUNsYXNzRGF0YTtcbmltcG9ydCBQcmltaXRpdmVDbGFzc0RhdGEgPSBEb3BwaW9KVk0uVk0uQ2xhc3NGaWxlLlByaW1pdGl2ZUNsYXNzRGF0YTtcbmltcG9ydCBhc3NlcnQgPSBEb3BwaW9KVk0uRGVidWcuQXNzZXJ0O1xuaW1wb3J0IGRlZmxhdGUgPSByZXF1aXJlKCdwYWtvL2xpYi96bGliL2RlZmxhdGUnKTtcbmltcG9ydCBpbmZsYXRlID0gcmVxdWlyZSgncGFrby9saWIvemxpYi9pbmZsYXRlJyk7XG5pbXBvcnQgY3JjMzIgPSByZXF1aXJlKCdwYWtvL2xpYi96bGliL2NyYzMyJyk7XG5pbXBvcnQgYWRsZXIzMiA9IHJlcXVpcmUoJ3Bha28vbGliL3psaWIvYWRsZXIzMicpO1xuaW1wb3J0IFpTdHJlYW1Db25zID0gcmVxdWlyZSgncGFrby9saWIvemxpYi96c3RyZWFtJyk7XG5pbXBvcnQgR1pIZWFkZXIgPSByZXF1aXJlKCdwYWtvL2xpYi96bGliL2d6aGVhZGVyJyk7XG5cbmltcG9ydCBaU3RyZWFtID0gUGFrby5aU3RyZWFtO1xuaW1wb3J0IFpsaWJSZXR1cm5Db2RlID0gUGFrby5abGliUmV0dXJuQ29kZTtcbmltcG9ydCBabGliRmx1c2hWYWx1ZSA9IFBha28uWmxpYkZsdXNoVmFsdWU7XG5sZXQgQkZTVXRpbHMgPSBCcm93c2VyRlMuQkZTUmVxdWlyZSgnYmZzX3V0aWxzJyk7XG5jb25zdCBNQVhfV0JJVFMgPSAxNTtcblxuLy8gRm9yIHR5cGUgaW5mb3JtYXRpb24gb25seS5cbmltcG9ydCB7ZGVmYXVsdCBhcyBUWmlwRlMsIENlbnRyYWxEaXJlY3RvcnkgYXMgVENlbnRyYWxEaXJlY3Rvcnl9IGZyb20gJ2Jyb3dzZXJmcy9kaXN0L25vZGUvYmFja2VuZC9aaXBGUyc7XG5kZWNsYXJlIHZhciByZWdpc3Rlck5hdGl2ZXM6IChkZWZzOiBhbnkpID0+IHZvaWQ7XG5cbmxldCBaaXBGaWxlczoge1tpZDogbnVtYmVyXTogVFppcEZTfSA9IHt9O1xubGV0IFppcEVudHJpZXM6IHtbaWQ6IG51bWJlcl06IFRDZW50cmFsRGlyZWN0b3J5fSA9IHt9O1xubGV0IFpTdHJlYW1zOiB7W2lkOiBudW1iZXJdOiBaU3RyZWFtfSA9IHt9O1xuLy8gU3RhcnQgYXQgMSwgYXMgMCBpcyBpbnRlcnByZXRlZCBhcyBhbiBlcnJvci5cbmxldCBOZXh0SWQ6IG51bWJlciA9IDE7XG5mdW5jdGlvbiBPcGVuSXRlbTxUPihpdGVtOiBULCBtYXA6IHtbaWQ6IG51bWJlcl06IFR9KTogbnVtYmVyIHtcbiAgbGV0IGlkID0gTmV4dElkKys7XG4gIG1hcFtpZF0gPSBpdGVtO1xuICByZXR1cm4gaWQ7XG59XG5mdW5jdGlvbiBHZXRJdGVtPFQ+KHRocmVhZDogSlZNVGhyZWFkLCBpZDogbnVtYmVyLCBtYXA6IHtbaWQ6IG51bWJlcl06IFR9LCBlcnJNc2c6IHN0cmluZyk6IFQge1xuICBsZXQgaXRlbSA9IG1hcFtpZF07XG4gIGlmICghaXRlbSkge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbihcIkxqYXZhL2xhbmcvSWxsZWdhbFN0YXRlRXhjZXB0aW9uO1wiLCBlcnJNc2cpO1xuICAgIHJldHVybiBudWxsO1xuICB9IGVsc2Uge1xuICAgIHJldHVybiBpdGVtO1xuICB9XG59XG5mdW5jdGlvbiBDbG9zZUl0ZW08VD4oaWQ6IG51bWJlciwgbWFwOiB7W2lkOiBudW1iZXJdOiBUfSk6IHZvaWQge1xuICBkZWxldGUgbWFwW2lkXTtcbn1cblxuZnVuY3Rpb24gT3BlblppcEZpbGUoemZpbGU6IFRaaXBGUyk6IG51bWJlciB7XG4gIHJldHVybiBPcGVuSXRlbSh6ZmlsZSwgWmlwRmlsZXMpO1xufVxuZnVuY3Rpb24gQ2xvc2VaaXBGaWxlKGlkOiBudW1iZXIpOiB2b2lkIHtcbiAgQ2xvc2VJdGVtKGlkLCBaaXBGaWxlcyk7XG59XG4vKipcbiAqIFJldHVybnMgdGhlIHppcCBmaWxlLCBpZiBpdCBleGlzdHMuXG4gKiBPdGhlcndpc2UsIHRocm93cyBhbiBJbGxlZ2FsU3RhdGVFeGNlcHRpb24uXG4gKi9cbmZ1bmN0aW9uIEdldFppcEZpbGUodGhyZWFkOiBKVk1UaHJlYWQsIGlkOiBudW1iZXIpOiBUWmlwRlMge1xuICByZXR1cm4gR2V0SXRlbSh0aHJlYWQsIGlkLCBaaXBGaWxlcywgYFppcEZpbGUgbm90IGZvdW5kLmApO1xufVxuZnVuY3Rpb24gT3BlblppcEVudHJ5KHplbnRyeTogVENlbnRyYWxEaXJlY3RvcnkpOiBudW1iZXIge1xuICByZXR1cm4gT3Blbkl0ZW0oemVudHJ5LCBaaXBFbnRyaWVzKTtcbn1cbmZ1bmN0aW9uIENsb3NlWmlwRW50cnkoaWQ6IG51bWJlcik6IHZvaWQge1xuICBDbG9zZUl0ZW0oaWQsIFppcEVudHJpZXMpO1xufVxuLyoqXG4gKiBSZXR1cm5zIHRoZSB6aXAgZW50cnksIGlmIGl0IGV4aXN0cy5cbiAqIE90aGVyd2lzZSwgdGhyb3dzIGFuIElsbGVnYWxTdGF0ZUV4Y2VwdGlvbi5cbiAqL1xuZnVuY3Rpb24gR2V0WmlwRW50cnkodGhyZWFkOiBKVk1UaHJlYWQsIGlkOiBudW1iZXIpOiBUQ2VudHJhbERpcmVjdG9yeSB7XG4gIHJldHVybiBHZXRJdGVtKHRocmVhZCwgaWQsIFppcEVudHJpZXMsIGBJbnZhbGlkIFppcEVudHJ5LmApO1xufVxuZnVuY3Rpb24gT3BlblpTdHJlYW0oaW5mbGF0ZXJTdGF0ZTogWlN0cmVhbSk6IG51bWJlciB7XG4gIHJldHVybiBPcGVuSXRlbShpbmZsYXRlclN0YXRlLCBaU3RyZWFtcyk7XG59XG5mdW5jdGlvbiBDbG9zZVpTdHJlYW0oaWQ6IG51bWJlcik6IHZvaWQge1xuICBDbG9zZUl0ZW0oaWQsIFpTdHJlYW1zKTtcbn1cbmZ1bmN0aW9uIEdldFpTdHJlYW0odGhyZWFkOiBKVk1UaHJlYWQsIGlkOiBudW1iZXIpOiBaU3RyZWFtIHtcbiAgcmV0dXJuIEdldEl0ZW0odGhyZWFkLCBpZCwgWlN0cmVhbXMsIGBJbmZsYXRlciBub3QgZm91bmQuYCk7XG59XG5cbmxldCBDYW5Vc2VDb3B5RmFzdFBhdGggPSBmYWxzZTtcbmlmICh0eXBlb2YgSW50OEFycmF5ICE9PSBcInVuZGVmaW5lZFwiKSB7XG4gIGxldCBpOGFyciA9IG5ldyBJbnQ4QXJyYXkoMSk7XG4gIGxldCBiID0gbmV3IEJ1ZmZlcig8YW55PiBpOGFyci5idWZmZXIpO1xuICBpOGFyclswXSA9IDEwMDtcbiAgQ2FuVXNlQ29weUZhc3RQYXRoID0gaThhcnJbMF0gPT0gYi5yZWFkSW50OCgwKTtcbn1cblxuaW50ZXJmYWNlIEFycmF5aXNoIHtcbiAgW2lkeDogbnVtYmVyXTogbnVtYmVyO1xufVxuXG5mdW5jdGlvbiBpc1VpbnQ4QXJyYXkoYXJyOiBBcnJheWlzaCk6IGFyciBpcyBVaW50OEFycmF5IHtcbiAgaWYgKGFyciAmJiB0eXBlb2YoVWludDhBcnJheSkgIT09IFwidW5kZWZpbmVkXCIgJiYgYXJyIGluc3RhbmNlb2YgVWludDhBcnJheSkge1xuICAgIHJldHVybiB0cnVlO1xuICB9XG4gIHJldHVybiBmYWxzZTtcbn1cblxuZnVuY3Rpb24gaXNJbnQ4QXJyYXkoYXJyOiBBcnJheWlzaCk6IGFyciBpcyBJbnQ4QXJyYXkge1xuICBpZiAoYXJyICYmIHR5cGVvZihJbnQ4QXJyYXkpICE9PSBcInVuZGVmaW5lZFwiICYmIGFyciBpbnN0YW5jZW9mIEludDhBcnJheSkge1xuICAgIHJldHVybiB0cnVlO1xuICB9XG4gIHJldHVybiBmYWxzZTtcbn1cblxuLyoqXG4gKiBDb252ZXJ0cyBhbiBJbnQ4QXJyYXkgb3IgYW4gYXJyYXkgb2YgOC1iaXQgc2lnbmVkIGludHMgaW50b1xuICogYSBVaW50OEFycmF5IG9yIGFuIGFycmF5IG9mIDgtYml0IHVuc2lnbmVkIGludHMuXG4gKi9cbmZ1bmN0aW9uIGk4MnU4KGFycjogbnVtYmVyW10gfCBJbnQ4QXJyYXksIHN0YXJ0OiBudW1iZXIsIGxlbjogbnVtYmVyKTogbnVtYmVyW10gfCBVaW50OEFycmF5IHtcbiAgaWYgKGlzSW50OEFycmF5KGFycikpIHtcbiAgICByZXR1cm4gbmV3IFVpbnQ4QXJyYXkoYXJyLmJ1ZmZlciwgYXJyLmJ5dGVPZmZzZXQgKyBzdGFydCwgbGVuKTtcbiAgfSBlbHNlIGlmIChBcnJheS5pc0FycmF5KGFycikpIHtcbiAgICBpZiAodHlwZW9mKFVpbnQ4QXJyYXkpICE9PSBcInVuZGVmaW5lZFwiKSB7XG4gICAgICB2YXIgaThhcnIgPSBuZXcgSW50OEFycmF5KGxlbik7XG4gICAgICBpZiAoc3RhcnQgPT09IDAgJiYgbGVuID09PSBhcnIubGVuZ3RoKSB7XG4gICAgICAgIGk4YXJyLnNldChhcnIsIDApO1xuICAgICAgfSBlbHNlIHtcbiAgICAgICAgaThhcnIuc2V0KGFyci5zbGljZShzdGFydCwgc3RhcnQgKyBsZW4pLCAwKTtcbiAgICAgIH1cbiAgICAgIHJldHVybiBuZXcgVWludDhBcnJheShpOGFyci5idWZmZXIpO1xuICAgIH0gZWxzZSB7XG4gICAgICAvLyBTbG93IHdheS5cbiAgICAgIGxldCBydiA9IG5ldyBBcnJheTxudW1iZXI+KGxlbik7XG4gICAgICBmb3IgKGxldCBpID0gMDsgaSA8IGxlbjsgaSsrKSB7XG4gICAgICAgIHJ2W2ldID0gYXJyW3N0YXJ0ICsgaV0gJiAweEZGO1xuICAgICAgfVxuICAgICAgcmV0dXJuIHJ2O1xuICAgIH1cbiAgfSBlbHNlIHtcbiAgICB0aHJvdyBuZXcgVHlwZUVycm9yKGBJbnZhbGlkIGFycmF5LmApO1xuICB9XG59XG5cbi8qKlxuICogQ29udmVydHMgYW4gVWludDhBcnJheSBvciBhbiBhcnJheSBvZiA4LWJpdCB1bnNpZ25lZCBpbnRzIGludG9cbiAqIGFuIEludDhBcnJheSBvciBhbiBhcnJheSBvZiA4LWJpdCBzaWduZWQgaW50cy5cbiAqL1xuZnVuY3Rpb24gdTgyaTgoYXJyOiBudW1iZXJbXSB8IFVpbnQ4QXJyYXksIHN0YXJ0OiBudW1iZXIsIGxlbjogbnVtYmVyKTogbnVtYmVyW10gfCBJbnQ4QXJyYXkge1xuICBpZiAoaXNVaW50OEFycmF5KGFycikpIHtcbiAgICByZXR1cm4gbmV3IEludDhBcnJheShhcnIuYnVmZmVyLCBhcnIuYnl0ZU9mZnNldCArIHN0YXJ0LCBsZW4pO1xuICB9IGVsc2UgaWYgKEFycmF5LmlzQXJyYXkoYXJyKSkge1xuICAgIGlmICh0eXBlb2YoSW50OEFycmF5KSAhPT0gXCJ1bmRlZmluZWRcIikge1xuICAgICAgdmFyIHU4YXJyID0gbmV3IFVpbnQ4QXJyYXkobGVuKTtcbiAgICAgIGlmIChzdGFydCA9PT0gMCAmJiBsZW4gPT09IGFyci5sZW5ndGgpIHtcbiAgICAgICAgdThhcnIuc2V0KGFyciwgMCk7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICB1OGFyci5zZXQoYXJyLnNsaWNlKHN0YXJ0LCBzdGFydCArIGxlbiksIDApO1xuICAgICAgfVxuICAgICAgcmV0dXJuIG5ldyBJbnQ4QXJyYXkodThhcnIuYnVmZmVyKTtcbiAgICB9IGVsc2Uge1xuICAgICAgLy8gU2xvdyB3YXkuXG4gICAgICBsZXQgcnYgPSBuZXcgQXJyYXk8bnVtYmVyPihsZW4pO1xuICAgICAgZm9yIChsZXQgaSA9IDA7IGkgPCBsZW47IGkrKykge1xuICAgICAgICBydltpXSA9IGFycltzdGFydCArIGldO1xuICAgICAgICBpZiAocnZbaV0gPiAxMjcpIHtcbiAgICAgICAgICAvLyBTaWduIGV4dGVuZC5cbiAgICAgICAgICBydltpXSB8PSAweEZGRkZGRjgwXG4gICAgICAgIH1cbiAgICAgIH1cbiAgICAgIHJldHVybiBydjtcbiAgICB9XG4gIH0gZWxzZSB7XG4gICAgdGhyb3cgbmV3IFR5cGVFcnJvcihgSW52YWxpZCBhcnJheS5gKTtcbiAgfVxufVxuXG4vKipcbiAqIENvbnZlcnRzIGEgYnVmZmVyIGludG8gZWl0aGVyIGFuIEludDhBcnJheSwgb3IgYW4gYXJyYXkgb2Ygc2lnbmVkIDgtYml0IGludHMuXG4gKi9cbmZ1bmN0aW9uIGJ1ZmYyaTgoYnVmZjogTm9kZUJ1ZmZlcik6IEludDhBcnJheSB8IG51bWJlcltdIHtcbiAgbGV0IGFycmF5aXNoID0gQkZTVXRpbHMuYnVmZmVyMkFycmF5aXNoKGJ1ZmYpO1xuICByZXR1cm4gdTgyaTgoPGFueT4gYXJyYXlpc2gsIDAsIGFycmF5aXNoLmxlbmd0aCk7XG59XG5cbi8qKlxuICogVGhlIHR5cGUgb2YgYSBKWkVudHJ5IGZpZWxkLiBDb3BpZWQgZnJvbSBqYXZhLnV0aWwuemlwLlppcEZpbGUuXG4gKi9cbmNvbnN0IGVudW0gSlpFbnRyeVR5cGUge1xuICBKWkVOVFJZX05BTUUgPSAwLFxuICBKWkVOVFJZX0VYVFJBID0gMSxcbiAgSlpFTlRSWV9DT01NRU5UID0gMixcbn1cblxuY2xhc3MgamF2YV91dGlsX2NvbmN1cnJlbnRfYXRvbWljX0F0b21pY0xvbmcge1xuXG4gIHB1YmxpYyBzdGF0aWMgJ1ZNU3VwcG9ydHNDUzgoKVonKHRocmVhZDogSlZNVGhyZWFkKTogYm9vbGVhbiB7XG4gICAgcmV0dXJuIHRydWU7XG4gIH1cblxufVxuXG5jbGFzcyBqYXZhX3V0aWxfamFyX0phckZpbGUge1xuXG4gIC8qKlxuICAgKiBSZXR1cm5zIGFuIGFycmF5IG9mIHN0cmluZ3MgcmVwcmVzZW50aW5nIHRoZSBuYW1lcyBvZiBhbGwgZW50cmllc1xuICAgKiB0aGF0IGJlZ2luIHdpdGggXCJNRVRBLUlORi9cIiAoY2FzZSBpZ25vcmVkKS4gVGhpcyBuYXRpdmUgbWV0aG9kIGlzXG4gICAqIHVzZWQgaW4gSmFyRmlsZSBhcyBhbiBvcHRpbWl6YXRpb24gd2hlbiBsb29raW5nIHVwIG1hbmlmZXN0IGFuZFxuICAgKiBzaWduYXR1cmUgZmlsZSBlbnRyaWVzLiBSZXR1cm5zIG51bGwgaWYgbm8gZW50cmllcyB3ZXJlIGZvdW5kLlxuICAgKi9cbiAgcHVibGljIHN0YXRpYyAnZ2V0TWV0YUluZkVudHJ5TmFtZXMoKVtMamF2YS9sYW5nL1N0cmluZzsnKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV91dGlsX2phcl9KYXJGaWxlKTogSlZNVHlwZXMuSlZNQXJyYXk8SlZNVHlwZXMuamF2YV9sYW5nX1N0cmluZz4ge1xuICAgIGxldCB6aXAgPSBHZXRaaXBGaWxlKHRocmVhZCwgamF2YVRoaXNbJ2phdmEvdXRpbC96aXAvWmlwRmlsZS9qemZpbGUnXS50b051bWJlcigpKTtcbiAgICBpZiAoemlwKSB7XG4gICAgICBpZiAoIXppcC5leGlzdHNTeW5jKCcvTUVUQS1JTkYnKSkge1xuICAgICAgICByZXR1cm4gbnVsbDtcbiAgICAgIH1cblxuICAgICAgbGV0IGV4cGxvcmVQYXRoOiBzdHJpbmdbXSA9IFsnL01FVEEtSU5GJ107XG4gICAgICBsZXQgYnNDbCA9IHRocmVhZC5nZXRCc0NsKCk7XG4gICAgICBsZXQgZm91bmRGaWxlczogSlZNVHlwZXMuamF2YV9sYW5nX1N0cmluZ1tdID0gW3V0aWwuaW5pdFN0cmluZyhic0NsLCAnTUVUQS1JTkYvJyldO1xuICAgICAgd2hpbGUgKGV4cGxvcmVQYXRoLmxlbmd0aCA+IDApIHtcbiAgICAgICAgbGV0IHAgPSBleHBsb3JlUGF0aC5wb3AoKTtcbiAgICAgICAgbGV0IGRpckxpc3RpbmcgPSB6aXAucmVhZGRpclN5bmMocCk7XG4gICAgICAgIGZvciAobGV0IGkgPSAwOyBpIDwgZGlyTGlzdGluZy5sZW5ndGg7IGkrKykge1xuICAgICAgICAgIGxldCBuZXdQID0gYCR7cH0vJHtkaXJMaXN0aW5nW2ldfWA7XG4gICAgICAgICAgaWYgKHppcC5zdGF0U3luYyhuZXdQLCBmYWxzZSkuaXNEaXJlY3RvcnkoKSkge1xuICAgICAgICAgICAgZXhwbG9yZVBhdGgucHVzaChuZXdQKTtcbiAgICAgICAgICAgIC8vIEFkZCBhIGZpbmFsIC8sIGFuZCBzdHJpcCBvZmYgZmlyc3QgLy5cbiAgICAgICAgICAgIGZvdW5kRmlsZXMucHVzaCh1dGlsLmluaXRTdHJpbmcoYnNDbCwgYCR7bmV3UC5zbGljZSgxKX0vYCkpO1xuICAgICAgICAgIH0gZWxzZSB7XG4gICAgICAgICAgICAvLyBTdHJpcCBvZmYgZmlyc3QgLy5cbiAgICAgICAgICAgIGZvdW5kRmlsZXMucHVzaCh1dGlsLmluaXRTdHJpbmcoYnNDbCwgbmV3UC5zbGljZSgxKSkpO1xuICAgICAgICAgIH1cbiAgICAgICAgfVxuICAgICAgICByZXR1cm4gdXRpbC5uZXdBcnJheUZyb21EYXRhPEpWTVR5cGVzLmphdmFfbGFuZ19TdHJpbmc+KHRocmVhZCwgYnNDbCwgXCJbTGphdmEvbGFuZy9TdHJpbmc7XCIsIGZvdW5kRmlsZXMpO1xuICAgICAgfVxuICAgIH1cbiAgfVxuXG59XG5cbmNsYXNzIGphdmFfdXRpbF9sb2dnaW5nX0ZpbGVIYW5kbGVyIHtcblxuICBwdWJsaWMgc3RhdGljICdpc1NldFVJRCgpWicodGhyZWFkOiBKVk1UaHJlYWQpOiBib29sZWFuIHtcbiAgICAvLyBPdXIgRlMgZG9lcyBub3Qgc3VwcG9ydCBzZXRVSUQuXG4gICAgcmV0dXJuIGZhbHNlO1xuICB9XG5cbn1cblxuY2xhc3MgamF2YV91dGlsX1RpbWVab25lIHtcblxuICBwdWJsaWMgc3RhdGljICdnZXRTeXN0ZW1UaW1lWm9uZUlEKExqYXZhL2xhbmcvU3RyaW5nOylMamF2YS9sYW5nL1N0cmluZzsnKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nKTogSlZNVHlwZXMuamF2YV9sYW5nX1N0cmluZyB7XG4gICAgLy8gTk9URTogQ2FuIGJlIGhhbGYgb2YgYW4gaG91ciAoZS5nLiBOZXdmb3VuZGxhbmQgaXMgR01ULTMuNSlcbiAgICAvLyBOT1RFOiBJcyBwb3NpdGl2ZSBmb3IgbmVnYXRpdmUgb2Zmc2V0LlxuICAgIGxldCBvZmZzZXQgPSBuZXcgRGF0ZSgpLmdldFRpbWV6b25lT2Zmc2V0KCkgLyA2MDtcbiAgICByZXR1cm4gdGhyZWFkLmdldEpWTSgpLmludGVyblN0cmluZyhgR01UJHtvZmZzZXQgPiAwID8gJy0nIDogJysnfSR7b2Zmc2V0fWApO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0U3lzdGVtR01UT2Zmc2V0SUQoKUxqYXZhL2xhbmcvU3RyaW5nOycodGhyZWFkOiBKVk1UaHJlYWQpOiBKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nIHtcbiAgICAvLyBYWFggbWF5IG5vdCBiZSBjb3JyZWN0XG4gICAgcmV0dXJuIG51bGw7XG4gIH1cblxufVxuXG5cbmNsYXNzIGphdmFfdXRpbF96aXBfQWRsZXIzMiB7XG5cbiAgcHVibGljIHN0YXRpYyAndXBkYXRlKElJKUknKHRocmVhZDogSlZNVGhyZWFkLCBhZGxlcjogbnVtYmVyLCBieXRlOiBudW1iZXIpOiBudW1iZXIge1xuICAgIHJldHVybiBhZGxlcjMyKGFkbGVyLCBbYnl0ZSAmIDB4RkZdLCAxLCAwKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3VwZGF0ZUJ5dGVzKElbQklJKUknKHRocmVhZDogSlZNVGhyZWFkLCBhZGxlcjogbnVtYmVyLCBiOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+LCBvZmY6IG51bWJlciwgbGVuOiBudW1iZXIpOiBudW1iZXIge1xuICAgIHJldHVybiBhZGxlcjMyKGFkbGVyLCBpODJ1OChiLmFycmF5LCBvZmYsIGxlbiksIGxlbiwgMCk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICd1cGRhdGVCeXRlQnVmZmVyKElKSUkpSScodGhyZWFkOiBKVk1UaHJlYWQsIGFkbGVyOiBudW1iZXIsIGFkZHI6IExvbmcsIG9mZjogbnVtYmVyLCBsZW46IG51bWJlcik6IG51bWJlciB7XG4gICAgbGV0IGhlYXAgPSB0aHJlYWQuZ2V0SlZNKCkuZ2V0SGVhcCgpO1xuICAgIGxldCBidWZmID0gPFVpbnQ4QXJyYXk+IEJGU1V0aWxzLmJ1ZmZlcjJBcnJheWlzaChoZWFwLmdldF9idWZmZXIoYWRkci50b051bWJlcigpICsgb2ZmLCBsZW4pKTtcbiAgICByZXR1cm4gYWRsZXIzMihhZGxlciwgYnVmZiwgYnVmZi5sZW5ndGgsIDApO1xuICB9XG5cbn1cblxuXG5jbGFzcyBqYXZhX3V0aWxfemlwX0NSQzMyIHtcblxuICBwdWJsaWMgc3RhdGljICd1cGRhdGUoSUkpSScodGhyZWFkOiBKVk1UaHJlYWQsIGNyYzogbnVtYmVyLCBieXRlOiBudW1iZXIpOiBudW1iZXIge1xuICAgIHJldHVybiBjcmMzMihjcmMsIFtieXRlICYgMHhGRl0sIDEsIDApO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAndXBkYXRlQnl0ZXMoSVtCSUkpSScodGhyZWFkOiBKVk1UaHJlYWQsIGNyYzogbnVtYmVyLCBiOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+LCBvZmY6IG51bWJlciwgbGVuOiBudW1iZXIpOiBudW1iZXIge1xuICAgIHJldHVybiBjcmMzMihjcmMsIGk4MnU4KGIuYXJyYXksIG9mZiwgbGVuKSwgbGVuLCAwKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3VwZGF0ZUJ5dGVCdWZmZXIoSUpJSSlJJyh0aHJlYWQ6IEpWTVRocmVhZCwgY3JjOiBudW1iZXIsIGFkZHI6IExvbmcsIG9mZjogbnVtYmVyLCBsZW46IG51bWJlcik6IG51bWJlciB7XG4gICAgbGV0IGhlYXAgPSB0aHJlYWQuZ2V0SlZNKCkuZ2V0SGVhcCgpO1xuICAgIGxldCBidWZmID0gPFVpbnQ4QXJyYXk+IEJGU1V0aWxzLmJ1ZmZlcjJBcnJheWlzaChoZWFwLmdldF9idWZmZXIoYWRkci50b051bWJlcigpICsgb2ZmLCBsZW4pKTtcbiAgICByZXR1cm4gY3JjMzIoY3JjLCBidWZmLCBidWZmLmxlbmd0aCwgMCk7XG4gIH1cblxufVxuXG5jbGFzcyBqYXZhX3V0aWxfemlwX0RlZmxhdGVyIHtcblxuICBwdWJsaWMgc3RhdGljICdpbml0SURzKClWJyh0aHJlYWQ6IEpWTVRocmVhZCk6IHZvaWQge31cblxuICAvKipcbiAgICogSW5pdGlhbGl6ZSBhIG5ldyBkZWZsYXRlci4gVXNpbmcgdGhlIHpsaWIgcmVjb21tZW5kZWQgZGVmYXVsdCB2YWx1ZXMuXG4gICAqL1xuICBwdWJsaWMgc3RhdGljICdpbml0KElJWilKJyh0aHJlYWQ6IEpWTVRocmVhZCwgbGV2ZWw6IG51bWJlciwgc3RyYXRlZ3k6IG51bWJlciwgbm93cmFwOiBudW1iZXIpOiBMb25nIHtcbiAgICBsZXQgREVGX01FTV9MRVZFTCA9IDg7IC8vIFpsaWIgcmVjb21tZW5kZWQgZGVmYXVsdFxuICAgIGxldCBaX0RFRkxBVEVEID0gODsgICAgLy8gVGhpcyB2YWx1ZSBpcyBpbiB0aGUganMgdmVyc2lvbiBvZiBwYWtvIHVuZGVyIHBha28uWl9ERUZMQVRFRC4gXG4gICAgLy8gUG9zc2libHkgaXQgaXMgc2V0IHRvIHByaXZhdGUgaW4gdGhlIFR5cGVzY3JpcHQgdmVyc2lvbi4gVGhlIGRlZmF1bHQgdmFsdWUgaXMgOCwgc28gdGhpcyBzaG91bGQgd29yayBmaW5lXG5cbiAgICBsZXQgc3RybSA9IG5ldyBaU3RyZWFtQ29ucygpO1xuICAgIGxldCByZXQgPSBkZWZsYXRlLmRlZmxhdGVJbml0MihzdHJtLCBsZXZlbCwgWl9ERUZMQVRFRCwgbm93cmFwID8gLU1BWF9XQklUUyA6IE1BWF9XQklUUywgREVGX01FTV9MRVZFTCwgc3RyYXRlZ3kpO1xuICAgIFxuICAgIGlmIChyZXQgIT0gWmxpYlJldHVybkNvZGUuWl9PSykge1xuICAgIGxldCBtc2cgPSAoKHN0cm0ubXNnKSA/IHN0cm0ubXNnIDpcbiAgICAgIChyZXQgPT0gWmxpYlJldHVybkNvZGUuWl9TVFJFQU1fRVJST1IpID9cbiAgICAgICAgICAgICAgICBcImluZmxhdGVJbml0MiByZXR1cm5lZCBaX1NUUkVBTV9FUlJPUlwiIDpcbiAgICAgICAgICAgICAgICBcInVua25vd24gZXJyb3IgaW5pdGlhbGl6aW5nIHpsaWIgbGlicmFyeVwiKTtcbiAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oXCJMamF2YS9sYW5nL0ludGVybmFsRXJyb3I7XCIsIG1zZyk7XG4gICAgfSBlbHNlIHtcbiAgICAgIGxldCBudW0gPSBPcGVuWlN0cmVhbShzdHJtKTtcbiAgICAgIHJldHVybiBMb25nLmZyb21OdW1iZXIobnVtKTtcbiAgICB9XG4gIH1cblxuICAvKipcbiAgICogQXBwYXJlbnRseSB0aGlzIGlzIGV4cGxpY2l0bHkgbm90IHN1cHBvcnRlZCBieSBwYWtvLlxuICAgKiBAc2VlIE5vdGVzIGF0IGh0dHA6Ly9ub2RlY2EuZ2l0aHViLmlvL3Bha28vXG4gICAqL1xuICBwdWJsaWMgc3RhdGljICdzZXREaWN0aW9uYXJ5KEpbQklJKVYnKHRocmVhZDogSlZNVGhyZWFkLCBhcmcwOiBMb25nLCBhcmcxOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+LCBhcmcyOiBudW1iZXIsIGFyZzM6IG51bWJlcik6IHZvaWQge1xuICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9VbnNhdGlzZmllZExpbmtFcnJvcjsnLCAnTmF0aXZlIG1ldGhvZCBub3QgaW1wbGVtZW50ZWQuJyk7XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdkZWZsYXRlQnl0ZXMoSltCSUlJKUknKHRocmVhZDogSlZNVGhyZWFkLCBqYXZhVGhpczogSlZNVHlwZXMuamF2YV91dGlsX3ppcF9EZWZsYXRlciwgYWRkcjogTG9uZywgYjogSlZNVHlwZXMuSlZNQXJyYXk8bnVtYmVyPiwgb2ZmOiBudW1iZXIsIGxlbjogbnVtYmVyLCBmbHVzaDogbnVtYmVyKTogbnVtYmVyIHtcbiAgICBsZXQgc3RybSA9IEdldFpTdHJlYW0odGhyZWFkLCBhZGRyLnRvTnVtYmVyKCkpO1xuICAgIGlmICghc3RybSkgcmV0dXJuO1xuXG4gICAgbGV0IHRoaXNCdWYgPSBqYXZhVGhpc1snamF2YS91dGlsL3ppcC9EZWZsYXRlci9idWYnXTtcbiAgICBsZXQgdGhpc09mZiA9IGphdmFUaGlzWydqYXZhL3V0aWwvemlwL0RlZmxhdGVyL29mZiddO1xuICAgIGxldCB0aGlzTGVuID0gamF2YVRoaXNbJ2phdmEvdXRpbC96aXAvRGVmbGF0ZXIvbGVuJ107XG5cbiAgICBsZXQgaW5CdWYgPSB0aGlzQnVmLmFycmF5O1xuICAgIGxldCBvdXRCdWYgPSBiLmFycmF5O1xuXG4gICAgc3RybS5pbnB1dCA9IGk4MnU4KGluQnVmLCAwLCBpbkJ1Zi5sZW5ndGgpO1xuICAgIHN0cm0ubmV4dF9pbiA9IHRoaXNPZmY7XG4gICAgc3RybS5hdmFpbF9pbiA9IHRoaXNMZW47XG5cbiAgICBzdHJtLm91dHB1dCA9IGk4MnU4KG91dEJ1ZiwgMCwgb3V0QnVmLmxlbmd0aCk7XG4gICAgc3RybS5uZXh0X291dCA9IG9mZjtcbiAgICBzdHJtLmF2YWlsX291dCA9IGxlbjtcblxuICAgIGlmIChqYXZhVGhpc1snamF2YS91dGlsL3ppcC9EZWZsYXRlci9zZXRQYXJhbXMnXSkge1xuICAgICAgbGV0IGxldmVsID0gamF2YVRoaXNbJ2phdmEvdXRpbC96aXAvRGVmbGF0ZXIvbGV2ZWwnXTtcbiAgICAgIGxldCBzdHJhdGVneSA9IGphdmFUaGlzWydqYXZhL3V0aWwvemlwL0RlZmxhdGVyL2xldmVsJ107XG4gICAgICAvL2RlZmxhdGVQYXJhbXMgaXMgbm90IHlldCBzdXBwb3J0ZWQgYnkgcGFrby4gV2UnbGwgb3BlbiBhIG5ldyBaU3RyZWFtIHdpdGggdGhlIG5ldyBwYXJhbWV0ZXJzIGluc3RlYWQuXG4gICAgICAvLyByZXMgPSBkZWZsYXRlLmRlZmxhdGVQYXJhbXMoc3RybSwgbGV2ZWwsIHN0cmF0ZWd5KTsgXG4gICAgICBsZXQgbmV3U3RyZWFtID0gbmV3IFpTdHJlYW1Db25zKCk7XG4gICAgICBsZXQgcmVzID0gZGVmbGF0ZS5kZWZsYXRlSW5pdDIobmV3U3RyZWFtLCBsZXZlbCwgc3RybS5zdGF0ZS5tZXRob2QsIHN0cm0uc3RhdGUud2luZG93Qml0cywgc3RybS5zdGF0ZS5tZW1MZXZlbCwgc3RyYXRlZ3kpO1xuICAgICAgWlN0cmVhbXNbYWRkci50b051bWJlcigpXSA9IG5ld1N0cmVhbTtcbiAgICAgIHN3aXRjaCAocmVzKSB7XG4gICAgICAgIGNhc2UgWmxpYlJldHVybkNvZGUuWl9PSzpcbiAgICAgICAgICBqYXZhVGhpc1snamF2YS91dGlsL3ppcC9EZWZsYXRlci9zZXRQYXJhbXMnXSA9IDA7XG4gICAgICAgICAgdGhpc09mZiArPSB0aGlzTGVuIC0gc3RybS5hdmFpbF9pbjtcbiAgICAgICAgICBqYXZhVGhpc1snamF2YS91dGlsL3ppcC9EZWZsYXRlci9vZmYnXSA9IHRoaXNPZmY7XG4gICAgICAgICAgamF2YVRoaXNbJ2phdmEvdXRpbC96aXAvRGVmbGF0ZXIvbGVuJ10gPSBzdHJtLmF2YWlsX2luO1xuICAgICAgICAgIHJldHVybiBsZW4gLSBzdHJtLmF2YWlsX291dDtcbiAgICAgICAgY2FzZSBabGliUmV0dXJuQ29kZS5aX0JVRl9FUlJPUjpcbiAgICAgICAgICBqYXZhVGhpc1snamF2YS91dGlsL3ppcC9EZWZsYXRlci9zZXRQYXJhbXMnXSA9IDA7XG4gICAgICAgICAgcmV0dXJuIDA7XG4gICAgICAgIGRlZmF1bHQ6XG4gICAgICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKFwiTGphdmEvbGFuZy9JbnRlcm5hbEVycm9yO1wiLCBzdHJtLm1zZyk7XG4gICAgICB9XG4gICAgfSBlbHNlIHtcbiAgICAgIGxldCBmaW5pc2ggPSBqYXZhVGhpc1snamF2YS91dGlsL3ppcC9EZWZsYXRlci9maW5pc2gnXTtcblxuICAgICAgbGV0IHJlcyA9IGRlZmxhdGUuZGVmbGF0ZShzdHJtLCBmaW5pc2ggPyBabGliRmx1c2hWYWx1ZS5aX0ZJTklTSCA6IGZsdXNoKTtcblxuICAgICAgc3dpdGNoIChyZXMpIHtcbiAgICAgICAgY2FzZSBabGliUmV0dXJuQ29kZS5aX1NUUkVBTV9FTkQ6XG4gICAgICAgICAgamF2YVRoaXNbJ2phdmEvdXRpbC96aXAvRGVmbGF0ZXIvZmluaXNoZWQnXSA9IDE7XG4gICAgICAgICAgLy8gaW50ZW50aW9uYWxseSBmYWxsIHRocm91Z2hcbiAgICAgICAgY2FzZSBabGliUmV0dXJuQ29kZS5aX09LOlxuICAgICAgICAgIHRoaXNPZmYgKz0gdGhpc0xlbiAtIHN0cm0uYXZhaWxfaW47XG4gICAgICAgICAgamF2YVRoaXNbJ2phdmEvdXRpbC96aXAvRGVmbGF0ZXIvb2ZmJ10gPSB0aGlzT2ZmO1xuICAgICAgICAgIGphdmFUaGlzWydqYXZhL3V0aWwvemlwL0RlZmxhdGVyL2xlbiddID0gc3RybS5hdmFpbF9pbjtcbiAgICAgICAgICByZXR1cm4gbGVuIC0gc3RybS5hdmFpbF9vdXQ7XG4gICAgICAgIGNhc2UgWmxpYlJldHVybkNvZGUuWl9CVUZfRVJST1I6XG4gICAgICAgICAgcmV0dXJuIDA7XG4gICAgICAgIGRlZmF1bHQ6XG4gICAgICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL0ludGVybmFsRXJyb3I7Jywgc3RybS5tc2cpO1xuICAgICAgfVxuICAgIH1cbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2dldEFkbGVyKEopSScodGhyZWFkOiBKVk1UaHJlYWQsIGFkZHI6IExvbmcpOiBudW1iZXIge1xuICAgIGxldCBzdHJtID0gR2V0WlN0cmVhbSh0aHJlYWQsIGFkZHIudG9OdW1iZXIoKSk7XG4gICAgaWYgKHN0cm0pIHtcbiAgICAgIHJldHVybiBzdHJtLmFkbGVyO1xuICAgIH1cbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3Jlc2V0KEopVicodGhyZWFkOiBKVk1UaHJlYWQsIGFkZHI6IExvbmcpOiB2b2lkIHtcbiAgICBsZXQgc3RybSA9IEdldFpTdHJlYW0odGhyZWFkLCBhZGRyLnRvTnVtYmVyKCkpO1xuICAgIGlmIChzdHJtKSB7XG4gICAgICBpZiAoZGVmbGF0ZS5kZWZsYXRlUmVzZXQoc3RybSkgIT09IFpsaWJSZXR1cm5Db2RlLlpfT0spIHtcbiAgICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL0ludGVybmFsRXJyb3I7Jywgc3RybS5tc2cpO1xuICAgICAgfVxuICAgIH1cbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2VuZChKKVYnKHRocmVhZDogSlZNVGhyZWFkLCBhZGRyOiBMb25nKTogdm9pZCB7XG4gICAgbGV0IHN0cm0gPSBHZXRaU3RyZWFtKHRocmVhZCwgYWRkci50b051bWJlcigpKTtcbiAgICBpZiAoc3RybSkge1xuICAgICAgaWYgKGRlZmxhdGUuZGVmbGF0ZUVuZChzdHJtKSA9PT0gWmxpYlJldHVybkNvZGUuWl9TVFJFQU1fRVJST1IpIHtcbiAgICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL0ludGVybmFsRXJyb3I7Jywgc3RybS5tc2cpO1xuICAgICAgfSBlbHNlIHtcbiAgICAgICAgQ2xvc2VaU3RyZWFtKGFkZHIudG9OdW1iZXIoKSk7XG4gICAgICB9XG4gICAgfVxuICB9XG5cbn1cblxuY2xhc3MgamF2YV91dGlsX3ppcF9JbmZsYXRlciB7XG5cbiAgcHVibGljIHN0YXRpYyAnaW5pdElEcygpVicodGhyZWFkOiBKVk1UaHJlYWQpOiB2b2lkIHtcbiAgICAvLyBOT1AuXG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdpbml0KFopSicodGhyZWFkOiBKVk1UaHJlYWQsIG5vd3JhcDogbnVtYmVyKTogTG9uZyB7XG4gICAgLy8gQ29weWluZyBsb2dpYyBleGFjdGx5IGZyb20gSmF2YSdzIG5hdGl2ZS5cbiAgICBsZXQgc3RybSA9IG5ldyBaU3RyZWFtQ29ucygpO1xuICAgIGxldCByZXQgPSBpbmZsYXRlLmluZmxhdGVJbml0MihzdHJtLCBub3dyYXAgPyAtTUFYX1dCSVRTIDogTUFYX1dCSVRTKTtcblxuICAgIHN3aXRjaChyZXQpIHtcbiAgICAgIGNhc2UgWmxpYlJldHVybkNvZGUuWl9PSzpcbiAgICAgICAgbGV0IG51bSA9IE9wZW5aU3RyZWFtKHN0cm0pO1xuICAgICAgICByZXR1cm4gTG9uZy5mcm9tTnVtYmVyKG51bSk7XG4gICAgICBkZWZhdWx0OlxuICAgICAgICBsZXQgbXNnID0gKHN0cm0ubXNnKSA/IHN0cm0ubXNnIDpcbiAgICAgICAgICAgICAgICAgIChyZXQgPT0gWmxpYlJldHVybkNvZGUuWl9TVFJFQU1fRVJST1IpID9cbiAgICAgICAgICAgICAgICAgIFwiaW5mbGF0ZUluaXQyIHJldHVybmVkIFpfU1RSRUFNX0VSUk9SXCIgOlxuICAgICAgICAgICAgICAgICAgXCJ1bmtub3duIGVycm9yIGluaXRpYWxpemluZyB6bGliIGxpYnJhcnlcIjtcbiAgICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKFwiTGphdmEvbGFuZy9JbnRlcm5hbEVycm9yO1wiLCBtc2cpO1xuICAgICAgICBicmVhaztcbiAgICB9XG4gIH1cblxuICAvKipcbiAgICogTm90ZTogVGhpcyBmdW5jdGlvbiBpcyBleHBsaWNpdGx5IG5vdCBzdXBwb3J0ZWQgYnkgcGFrbywgdGhlIGxpYnJhcnkgd2UgdXNlXG4gICAqIGZvciBpbmZsYXRpb24uXG4gICAqIEBzZWUgTm90ZXMgYXQgaHR0cDovL25vZGVjYS5naXRodWIuaW8vcGFrby9cbiAgICovXG4gIHB1YmxpYyBzdGF0aWMgJ3NldERpY3Rpb25hcnkoSltCSUkpVicodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IExvbmcsIGFyZzE6IEpWTVR5cGVzLkpWTUFycmF5PG51bWJlcj4sIGFyZzI6IG51bWJlciwgYXJnMzogbnVtYmVyKTogdm9pZCB7XG4gICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL1Vuc2F0aXNmaWVkTGlua0Vycm9yOycsICdOYXRpdmUgbWV0aG9kIG5vdCBpbXBsZW1lbnRlZC4nKTtcbiAgfVxuXG4gIC8qKlxuICAgKiBOT1RFOiBpbmZsYXRlQnl0ZXMgbW9kaWZpZXMgdGhlIGZvbGxvd2luZyBwcm9wZXJ0aWVzIG9uIHRoZSBJbmZsYXRlIG9iamVjdDpcbiAgICpcbiAgICogLSBvZmZcbiAgICogLSBsZW5cbiAgICogLSBuZWVkRGljdFxuICAgKiAtIGZpbmlzaGVkXG4gICAqL1xuICBwdWJsaWMgc3RhdGljICdpbmZsYXRlQnl0ZXMoSltCSUkpSScodGhyZWFkOiBKVk1UaHJlYWQsIGphdmFUaGlzOiBKVk1UeXBlcy5qYXZhX3V0aWxfemlwX0luZmxhdGVyLCBhZGRyOiBMb25nLCBiOiBKVk1UeXBlcy5KVk1BcnJheTxudW1iZXI+LCBvZmY6IG51bWJlciwgbGVuOiBudW1iZXIpOiBudW1iZXIge1xuICAgIGxldCBzdHJtID0gR2V0WlN0cmVhbSh0aHJlYWQsIGFkZHIudG9OdW1iZXIoKSk7XG4gICAgaWYgKCFzdHJtKSB7XG4gICAgICByZXR1cm47XG4gICAgfVxuXG4gICAgbGV0IHRoaXNCdWYgPSBqYXZhVGhpc1snamF2YS91dGlsL3ppcC9JbmZsYXRlci9idWYnXTtcbiAgICBsZXQgdGhpc09mZiA9IGphdmFUaGlzWydqYXZhL3V0aWwvemlwL0luZmxhdGVyL29mZiddO1xuICAgIGxldCB0aGlzTGVuID0gamF2YVRoaXNbJ2phdmEvdXRpbC96aXAvSW5mbGF0ZXIvbGVuJ107XG5cbiAgICAvLyBSZXR1cm4gMCB3aGVuIHRoZSBidWZmZXIgaXMgZW1wdHksIHdoaWNoIHRlbGxzIEphdmEgdG8gcmVmaWxsIGl0cyBidWZmZXIuXG4gICAgaWYgKHRoaXNMZW4gPT09IDAgfHwgbGVuID09PSAwKSB7XG4gICAgICByZXR1cm4gMDtcbiAgICB9XG5cbiAgICBsZXQgaW5CdWYgPSB0aGlzQnVmLmFycmF5O1xuICAgIGxldCBvdXRCdWYgPSBiLmFycmF5O1xuXG4gICAgLy8gU2V0IHVwIHRoZSB6c3RyZWFtLlxuICAgIHN0cm0uaW5wdXQgPSBpODJ1OChpbkJ1ZiwgMCwgaW5CdWYubGVuZ3RoKTtcbiAgICBzdHJtLm5leHRfaW4gPSB0aGlzT2ZmO1xuICAgIHN0cm0uYXZhaWxfaW4gPSB0aGlzTGVuO1xuXG4gICAgc3RybS5vdXRwdXQgPSBpODJ1OChvdXRCdWYsIDAsIG91dEJ1Zi5sZW5ndGgpO1xuICAgIHN0cm0ubmV4dF9vdXQgPSBvZmY7XG4gICAgc3RybS5hdmFpbF9vdXQgPSBsZW47XG5cbiAgICAvLyBOT1RFOiBKVk0gY29kZSBkb2VzIGEgcGFydGlhbCBmbHVzaCwgYnV0IFBha28gZG9lc24ndCBzdXBwb3J0IGl0LlxuICAgIC8vIFRodXMsIHdlIGRvIGEgc3luYyBmbHVzaCBpbnN0ZWFkLlxuICAgIGxldCByZXQgPSBpbmZsYXRlLmluZmxhdGUoc3RybSwgWmxpYkZsdXNoVmFsdWUuWl9TWU5DX0ZMVVNIKTtcbiAgICBsZXQgbGVuUmVhZCA9IGxlbiAtIHN0cm0uYXZhaWxfb3V0O1xuICAgIGlmICghaXNJbnQ4QXJyYXkob3V0QnVmKSkge1xuICAgICAgLy8gU2xvdyBwYXRoOiBObyB0eXBlZCBhcnJheXMuIENvcHkgZGVjb21wcmVzc2VkIGRhdGEuXG4gICAgICAvLyB1OCAtPiBpOFxuICAgICAgbGV0IHJlc3VsdCA9IHN0cm0ub3V0cHV0O1xuICAgICAgZm9yIChsZXQgaSA9IDA7IGkgPCBsZW5SZWFkOyBpKyspIHtcbiAgICAgICAgbGV0IGJ5dGUgPSByZXN1bHRbaSArIG9mZl07XG4gICAgICAgIGlmIChieXRlID4gMTI3KSB7XG4gICAgICAgICAgLy8gU2lnbiBleHRlbmQuXG4gICAgICAgICAgYnl0ZSB8PSAweEZGRkZGRjgwXG4gICAgICAgIH1cbiAgICAgICAgb3V0QnVmW2kgKyBvZmZdID0gYnl0ZTtcbiAgICAgIH1cbiAgICB9XG5cbiAgICBzd2l0Y2gocmV0KSB7XG4gICAgICBjYXNlIFpsaWJSZXR1cm5Db2RlLlpfU1RSRUFNX0VORDpcbiAgICAgICAgamF2YVRoaXNbJ2phdmEvdXRpbC96aXAvSW5mbGF0ZXIvZmluaXNoZWQnXSA9IDE7XG4gICAgICAgIC8qIGZhbGwgdGhyb3VnaCAqL1xuICAgICAgY2FzZSBabGliUmV0dXJuQ29kZS5aX09LOlxuICAgICAgICB0aGlzT2ZmICs9IHRoaXNMZW4gLSBzdHJtLmF2YWlsX2luO1xuICAgICAgICBqYXZhVGhpc1snamF2YS91dGlsL3ppcC9JbmZsYXRlci9vZmYnXSA9IHRoaXNPZmY7XG4gICAgICAgIGphdmFUaGlzWydqYXZhL3V0aWwvemlwL0luZmxhdGVyL2xlbiddID0gc3RybS5hdmFpbF9pbjtcbiAgICAgICAgcmV0dXJuIGxlblJlYWQ7XG4gICAgICBjYXNlIFpsaWJSZXR1cm5Db2RlLlpfTkVFRF9ESUNUOlxuICAgICAgICBqYXZhVGhpc1snamF2YS91dGlsL3ppcC9JbmZsYXRlci9uZWVkRGljdCddID0gMTtcbiAgICAgICAgLyogTWlnaHQgaGF2ZSBjb25zdW1lZCBzb21lIGlucHV0IGhlcmUhICovXG4gICAgICAgIHRoaXNPZmYgKz0gdGhpc0xlbiAtIHN0cm0uYXZhaWxfaW47XG4gICAgICAgIGphdmFUaGlzWydqYXZhL3V0aWwvemlwL0luZmxhdGVyL29mZiddID0gdGhpc09mZjtcbiAgICAgICAgamF2YVRoaXNbJ2phdmEvdXRpbC96aXAvSW5mbGF0ZXIvbGVuJ10gPSBzdHJtLmF2YWlsX2luO1xuICAgICAgICByZXR1cm4gMDtcbiAgICAgIGNhc2UgWmxpYlJldHVybkNvZGUuWl9CVUZfRVJST1I6XG4gICAgICAgIHJldHVybiAwO1xuICAgICAgY2FzZSBabGliUmV0dXJuQ29kZS5aX0RBVEFfRVJST1I6XG4gICAgICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvdXRpbC96aXAvRGF0YUZvcm1hdEV4Y2VwdGlvbjsnLCBzdHJtLm1zZyk7XG4gICAgICAgIHJldHVybjtcbiAgICAgIGRlZmF1bHQ6XG4gICAgICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbignTGphdmEvbGFuZy9JbnRlcm5hbEVycm9yOycsIHN0cm0ubXNnKTtcbiAgICAgICAgcmV0dXJuO1xuICAgIH1cbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2dldEFkbGVyKEopSScodGhyZWFkOiBKVk1UaHJlYWQsIGFkZHI6IExvbmcpOiBudW1iZXIge1xuICAgIGxldCBzdHJtID0gR2V0WlN0cmVhbSh0aHJlYWQsIGFkZHIudG9OdW1iZXIoKSk7XG4gICAgaWYgKHN0cm0pIHtcbiAgICAgIHJldHVybiBzdHJtLmFkbGVyO1xuICAgIH1cbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ3Jlc2V0KEopVicodGhyZWFkOiBKVk1UaHJlYWQsIGFkZHI6IExvbmcpOiB2b2lkIHtcbiAgICBsZXQgYWRkck51bSA9IGFkZHIudG9OdW1iZXIoKTtcbiAgICBsZXQgc3RybSA9IEdldFpTdHJlYW0odGhyZWFkLCBhZGRyTnVtKTtcblxuICAgIGlmIChzdHJtKSB7XG4gICAgICAvKiBUaGVyZSdzIGEgYnVnIGluIFBha28gdGhhdCBwcmV2ZW50cyByZXNldCBmcm9tIHdvcmtpbmcuXG4gICAgICBpZiAoaW5mbGF0ZS5pbmZsYXRlUmVzZXQoc3RybSkgIT09IFpsaWJSZXR1cm5Db2RlLlpfT0spIHtcbiAgICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKCdMamF2YS9sYW5nL0ludGVybmFsRXJyb3I7JywgJycpO1xuICAgICAgfVxuICAgICAgKi9cbiAgICAgIC8vIEFsbG9jYXRlIGEgbmV3IHN0cmVhbSwgaW5zdGVhZC5cbiAgICAgIGxldCBuZXdTdHJtID0gbmV3IFpTdHJlYW1Db25zKCk7XG4gICAgICBsZXQgcmV0ID0gaW5mbGF0ZS5pbmZsYXRlSW5pdDIobmV3U3RybSwgc3RybS5zdGF0ZS53cmFwID8gTUFYX1dCSVRTIDogLU1BWF9XQklUUyk7XG4gICAgICBaU3RyZWFtc1thZGRyTnVtXSA9IG5ld1N0cm07XG4gICAgfVxuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZW5kKEopVicodGhyZWFkOiBKVk1UaHJlYWQsIGFkZHI6IExvbmcpOiB2b2lkIHtcbiAgICBsZXQgc3RybSA9IEdldFpTdHJlYW0odGhyZWFkLCBhZGRyLnRvTnVtYmVyKCkpO1xuICAgIGlmIChzdHJtKSB7XG4gICAgICBpZiAoaW5mbGF0ZS5pbmZsYXRlRW5kKHN0cm0pID09PSBabGliUmV0dXJuQ29kZS5aX1NUUkVBTV9FUlJPUikge1xuICAgICAgICB0aHJlYWQudGhyb3dOZXdFeGNlcHRpb24oJ0xqYXZhL2xhbmcvSW50ZXJuYWxFcnJvcjsnLCBzdHJtLm1zZyk7XG4gICAgICB9IGVsc2Uge1xuICAgICAgICBDbG9zZVpTdHJlYW0oYWRkci50b051bWJlcigpKTtcbiAgICAgIH1cbiAgICB9XG4gIH1cblxufVxuXG5jbGFzcyBqYXZhX3V0aWxfemlwX1ppcEZpbGUge1xuXG4gIHB1YmxpYyBzdGF0aWMgJ2luaXRJRHMoKVYnKHRocmVhZDogSlZNVGhyZWFkKTogdm9pZCB7XG4gICAgLy8gTk9QLlxuICB9XG5cbiAgLyoqXG4gICAqIE5vdGU6IFJldHVybnMgMCB3aGVuIGVudHJ5IGRvZXMgbm90IGV4aXN0LlxuICAgKi9cbiAgcHVibGljIHN0YXRpYyAnZ2V0RW50cnkoSltCWilKJyh0aHJlYWQ6IEpWTVRocmVhZCwganpmaWxlOiBMb25nLCBuYW1lQnl0ZXM6IEpWTVR5cGVzLkpWTUFycmF5PG51bWJlcj4sIGFkZFNsYXNoOiBudW1iZXIpOiBMb25nIHtcbiAgICAvLyBBU1NVTVBUSU9OOiBOYW1lIGlzIFVURi04LlxuICAgIC8vIFNob3VsZCBhY3R1YWxseSBjb21wYXJlIHRoZSByYXcgYnl0ZXMuXG4gICAgbGV0IHppcGZzID0gR2V0WmlwRmlsZSh0aHJlYWQsIGp6ZmlsZS50b051bWJlcigpKTtcbiAgICBpZiAoemlwZnMpIHtcbiAgICAgIGxldCBuYW1lID0gbmV3IEJ1ZmZlcihuYW1lQnl0ZXMuYXJyYXkpLnRvU3RyaW5nKCd1dGY4Jyk7XG4gICAgICBpZiAobmFtZVswXSAhPT0gJy8nKSB7XG4gICAgICAgIG5hbWUgPSBgLyR7bmFtZX1gO1xuICAgICAgfVxuICAgICAgbmFtZSA9IHBhdGgucmVzb2x2ZShuYW1lKTtcbiAgICAgIHRyeSB7XG4gICAgICAgIHJldHVybiBMb25nLmZyb21OdW1iZXIoT3BlblppcEVudHJ5KHppcGZzLmdldENlbnRyYWxEaXJlY3RvcnlFbnRyeShuYW1lKSkpO1xuICAgICAgfSBjYXRjaCAoZSkge1xuICAgICAgICByZXR1cm4gTG9uZy5aRVJPO1xuICAgICAgfVxuICAgIH1cbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2ZyZWVFbnRyeShKSilWJyh0aHJlYWQ6IEpWTVRocmVhZCwganpmaWxlOiBMb25nLCBqemVudHJ5OiBMb25nKTogdm9pZCB7XG4gICAgQ2xvc2VaaXBFbnRyeShqemVudHJ5LnRvTnVtYmVyKCkpO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0TmV4dEVudHJ5KEpJKUonKHRocmVhZDogSlZNVGhyZWFkLCBqemZpbGU6IExvbmcsIGluZGV4OiBudW1iZXIpOiBMb25nIHtcbiAgICBsZXQgemlwZnMgPSBHZXRaaXBGaWxlKHRocmVhZCwganpmaWxlLnRvTnVtYmVyKCkpO1xuICAgIGlmICh6aXBmcykge1xuICAgICAgdHJ5IHtcbiAgICAgICAgcmV0dXJuIExvbmcuZnJvbU51bWJlcihPcGVuWmlwRW50cnkoemlwZnMuZ2V0Q2VudHJhbERpcmVjdG9yeUVudHJ5QXQoaW5kZXgpKSk7XG4gICAgICB9IGNhdGNoIChlKSB7XG4gICAgICAgIHJldHVybiBMb25nLlpFUk87XG4gICAgICB9XG4gICAgfVxuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnY2xvc2UoSilWJyh0aHJlYWQ6IEpWTVRocmVhZCwganpmaWxlOiBMb25nKTogdm9pZCB7XG4gICAgQ2xvc2VaaXBGaWxlKGp6ZmlsZS50b051bWJlcigpKTtcbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ29wZW4oTGphdmEvbGFuZy9TdHJpbmc7SUpaKUonKHRocmVhZDogSlZNVGhyZWFkLCBuYW1lT2JqOiBKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nLCBtb2RlOiBudW1iZXIsIG1vZGlmaWVkOiBMb25nLCB1c2VtbWFwOiBudW1iZXIpOiBMb25nIHtcbiAgICAvLyBJZ25vcmUgbW1hcCBvcHRpb24uXG4gICAgbGV0IG5hbWUgPSBuYW1lT2JqLnRvU3RyaW5nKCk7XG4gICAgLy8gT3B0aW1pemF0aW9uOiBDaGVjayBpZiB0aGlzIGlzIGEgSkFSIGZpbGUgb24gdGhlIGNsYXNzcGF0aC5cbiAgICBsZXQgY3BhdGggPSB0aHJlYWQuZ2V0QnNDbCgpLmdldENsYXNzUGF0aEl0ZW1zKCk7XG4gICAgZm9yIChsZXQgaSA9IDA7IGkgPCBjcGF0aC5sZW5ndGg7IGkrKykge1xuICAgICAgbGV0IGNwYXRoSXRlbSA9IGNwYXRoW2ldO1xuICAgICAgaWYgKGNwYXRoSXRlbSBpbnN0YW5jZW9mIEFic3RyYWN0Q2xhc3NwYXRoSmFyKSB7XG4gICAgICAgIGlmIChwYXRoLnJlc29sdmUoY3BhdGhJdGVtLmdldFBhdGgoKSkgPT09IHBhdGgucmVzb2x2ZShuYW1lKSkge1xuICAgICAgICAgIHJldHVybiBMb25nLmZyb21OdW1iZXIoT3BlblppcEZpbGUoKDxBYnN0cmFjdENsYXNzcGF0aEphcj4gPGFueT4gY3BhdGhJdGVtKS5nZXRGUygpKSk7XG4gICAgICAgIH1cbiAgICAgIH1cbiAgICB9XG5cbiAgICAvLyBBc3luYyBwYXRoLlxuICAgIHRocmVhZC5zZXRTdGF0dXMoVGhyZWFkU3RhdHVzLkFTWU5DX1dBSVRJTkcpO1xuICAgIGZzLnJlYWRGaWxlKG5hbWUsIChlcnIsIGRhdGEpID0+IHtcbiAgICAgIGlmIChlcnIpIHtcbiAgICAgICAgdGhyZWFkLnRocm93TmV3RXhjZXB0aW9uKFwiTGphdmEvaW8vSU9FeGNlcHRpb247XCIsIGVyci5tZXNzYWdlKTtcbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIHRocmVhZC5hc3luY1JldHVybihMb25nLmZyb21OdW1iZXIoT3BlblppcEZpbGUobmV3IEJyb3dzZXJGUy5GaWxlU3lzdGVtLlppcEZTKGRhdGEsIG5hbWUpKSksIG51bGwpO1xuICAgICAgfVxuICAgIH0pO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0VG90YWwoSilJJyh0aHJlYWQ6IEpWTVRocmVhZCwganpmaWxlOiBMb25nKTogbnVtYmVyIHtcbiAgICBsZXQgemlwZnMgPSBHZXRaaXBGaWxlKHRocmVhZCwganpmaWxlLnRvTnVtYmVyKCkpO1xuICAgIGlmICh6aXBmcykge1xuICAgICAgcmV0dXJuIHppcGZzLmdldE51bWJlck9mQ2VudHJhbERpcmVjdG9yeUVudHJpZXMoKTtcbiAgICB9XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdzdGFydHNXaXRoTE9DKEopWicodGhyZWFkOiBKVk1UaHJlYWQsIGFyZzA6IExvbmcpOiBudW1iZXIge1xuICAgIC8vIFdlIGRvIG5vdCBzdXBwb3J0IGFueSB6aXAgZmlsZXMgdGhhdCBkbyBub3QgYmVnaW4gd2l0aCB0aGUgcHJvcGVyIHNpZ25hdHVyZS5cbiAgICAvLyBCb29sZWFuLCBzbyAxID09PSB0cnVlLlxuICAgIHJldHVybiAxO1xuICB9XG5cbiAgcHVibGljIHN0YXRpYyAncmVhZChKSkpbQklJKUknKHRocmVhZDogSlZNVGhyZWFkLCBqemZpbGU6IExvbmcsIGp6ZW50cnk6IExvbmcsIHBvczogTG9uZywgYjogSlZNVHlwZXMuSlZNQXJyYXk8bnVtYmVyPiwgb2ZmOiBudW1iZXIsIGxlbjogbnVtYmVyKTogbnVtYmVyIHtcbiAgICBsZXQgemlwZW50cnkgPSBHZXRaaXBFbnRyeSh0aHJlYWQsIGp6ZW50cnkudG9OdW1iZXIoKSk7XG4gICAgbGV0IHBvc051bSA9IHBvcy50b051bWJlcigpO1xuICAgIGlmICh6aXBlbnRyeSkge1xuICAgICAgaWYgKGxlbiA8PSAwKSB7XG4gICAgICAgIHJldHVybiAwO1xuICAgICAgfVxuICAgICAgbGV0IGRhdGEgPSB6aXBlbnRyeS5nZXRSYXdEYXRhKCk7XG4gICAgICAvLyBTYW5pdHkgY2hlY2s6IFdpbGwgbGlrZWx5IG5ldmVyIGhhcHBlbiwgYXMgSmF2YSBjb2RlIGVuc3VyZXMgdGhhdCB0aGlzIG1ldGhvZCBpc1xuICAgICAgLy8gY2FsbGVkIGluIGEgc2FuZSBtYW5uZXIuXG4gICAgICBpZiAocG9zTnVtID49IGRhdGEubGVuZ3RoKSB7XG4gICAgICAgIHRocmVhZC50aHJvd05ld0V4Y2VwdGlvbihcIkxqYXZhL2lvL0lPRXhjZXB0aW9uO1wiLCBcIkVuZCBvZiB6aXAgZmlsZS5cIik7XG4gICAgICAgIHJldHVybjtcbiAgICAgIH1cbiAgICAgIGlmIChwb3NOdW0gKyBsZW4gPiBkYXRhLmxlbmd0aCkge1xuICAgICAgICBsZW4gPSBkYXRhLmxlbmd0aCAtIHBvc051bTtcbiAgICAgIH1cbiAgICAgIGxldCBhcnIgPSBiLmFycmF5O1xuICAgICAgaWYgKENhblVzZUNvcHlGYXN0UGF0aCkge1xuICAgICAgICBsZXQgaThhcnI6IEludDhBcnJheSA9IDxhbnk+IGFycjtcbiAgICAgICAgLy8gWFhYOiBEZWZpbml0ZWx5VHlwZWQgdHlwaW5ncyBhcmUgb3V0IG9mIGRhdGUuXG4gICAgICAgIGxldCBiID0gbmV3IEJ1ZmZlcig8YW55PiBpOGFyci5idWZmZXIpO1xuICAgICAgICByZXR1cm4gZGF0YS5jb3B5KGIsIG9mZiArIGk4YXJyLmJ5dGVPZmZzZXQsIHBvc051bSwgcG9zTnVtICsgbGVuKTtcbiAgICAgIH0gZWxzZSB7XG4gICAgICAgIGZvciAobGV0IGkgPSAwOyBpIDwgbGVuOyBpKyspIHtcbiAgICAgICAgICBhcnJbb2ZmICsgaV0gPSBkYXRhLnJlYWRJbnQ4KHBvc051bSArIGkpO1xuICAgICAgICB9XG4gICAgICAgIHJldHVybiBsZW47XG4gICAgICB9XG4gICAgfVxuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0RW50cnlUaW1lKEopSicodGhyZWFkOiBKVk1UaHJlYWQsIGp6ZW50cnk6IExvbmcpOiBMb25nIHtcbiAgICBsZXQgemlwZW50cnkgPSBHZXRaaXBFbnRyeSh0aHJlYWQsIGp6ZW50cnkudG9OdW1iZXIoKSk7XG4gICAgaWYgKHppcGVudHJ5KSB7XG4gICAgICByZXR1cm4gTG9uZy5mcm9tTnVtYmVyKHppcGVudHJ5LnJhd0xhc3RNb2RGaWxlVGltZSgpKTtcbiAgICB9XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdnZXRFbnRyeUNyYyhKKUonKHRocmVhZDogSlZNVGhyZWFkLCBqemVudHJ5OiBMb25nKTogTG9uZyB7XG4gICAgbGV0IHppcGVudHJ5ID0gR2V0WmlwRW50cnkodGhyZWFkLCBqemVudHJ5LnRvTnVtYmVyKCkpO1xuICAgIGlmICh6aXBlbnRyeSkge1xuICAgICAgcmV0dXJuIExvbmcuZnJvbU51bWJlcih6aXBlbnRyeS5jcmMzMigpKTtcbiAgICB9XG4gIH1cblxuICBwdWJsaWMgc3RhdGljICdnZXRFbnRyeUNTaXplKEopSicodGhyZWFkOiBKVk1UaHJlYWQsIGp6ZW50cnk6IExvbmcpOiBMb25nIHtcbiAgICBsZXQgemlwZW50cnkgPSBHZXRaaXBFbnRyeSh0aHJlYWQsIGp6ZW50cnkudG9OdW1iZXIoKSk7XG4gICAgaWYgKHppcGVudHJ5KSB7XG4gICAgICByZXR1cm4gTG9uZy5mcm9tTnVtYmVyKHppcGVudHJ5LmNvbXByZXNzZWRTaXplKCkpO1xuICAgIH1cbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2dldEVudHJ5U2l6ZShKKUonKHRocmVhZDogSlZNVGhyZWFkLCBqemVudHJ5OiBMb25nKTogTG9uZyB7XG4gICAgbGV0IHppcGVudHJ5ID0gR2V0WmlwRW50cnkodGhyZWFkLCBqemVudHJ5LnRvTnVtYmVyKCkpO1xuICAgIGlmICh6aXBlbnRyeSkge1xuICAgICAgcmV0dXJuIExvbmcuZnJvbU51bWJlcih6aXBlbnRyeS51bmNvbXByZXNzZWRTaXplKCkpO1xuICAgIH1cbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2dldEVudHJ5TWV0aG9kKEopSScodGhyZWFkOiBKVk1UaHJlYWQsIGp6ZW50cnk6IExvbmcpOiBudW1iZXIge1xuICAgIGxldCB6aXBlbnRyeSA9IEdldFppcEVudHJ5KHRocmVhZCwganplbnRyeS50b051bWJlcigpKTtcbiAgICBpZiAoemlwZW50cnkpIHtcbiAgICAgIHJldHVybiB6aXBlbnRyeS5jb21wcmVzc2lvbk1ldGhvZCgpO1xuICAgIH1cbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2dldEVudHJ5RmxhZyhKKUknKHRocmVhZDogSlZNVGhyZWFkLCBqemVudHJ5OiBMb25nKTogbnVtYmVyIHtcbiAgICBsZXQgemlwZW50cnkgPSBHZXRaaXBFbnRyeSh0aHJlYWQsIGp6ZW50cnkudG9OdW1iZXIoKSk7XG4gICAgaWYgKHppcGVudHJ5KSB7XG4gICAgICByZXR1cm4gemlwZW50cnkuZmxhZygpO1xuICAgIH1cbiAgfVxuXG4gIHB1YmxpYyBzdGF0aWMgJ2dldENvbW1lbnRCeXRlcyhKKVtCJyh0aHJlYWQ6IEpWTVRocmVhZCwganpmaWxlOiBMb25nKTogSlZNVHlwZXMuSlZNQXJyYXk8bnVtYmVyPiB7XG4gICAgbGV0IHppcGZpbGUgPSBHZXRaaXBGaWxlKHRocmVhZCwganpmaWxlLnRvTnVtYmVyKCkpO1xuICAgIGlmICh6aXBmaWxlKSB7XG4gICAgICBsZXQgZW9jZCA9IHppcGZpbGUuZ2V0RW5kT2ZDZW50cmFsRGlyZWN0b3J5KCk7XG4gICAgICBsZXQgY29tbWVudCA9IGVvY2QucmF3Q2RaaXBDb21tZW50KCk7XG4gICAgICAvLyBTaG91bGQgYmUgemVyby1jb3B5IGluIG1vc3Qgc2l0dWF0aW9ucy5cbiAgICAgIHJldHVybiB1dGlsLm5ld0FycmF5RnJvbURhdGFXaXRoQ2xhc3ModGhyZWFkLCA8QXJyYXlDbGFzc0RhdGE8bnVtYmVyPj4gdGhyZWFkLmdldEJzQ2woKS5nZXRJbml0aWFsaXplZENsYXNzKHRocmVhZCwgJ1tCJyksIDxudW1iZXJbXT4gYnVmZjJpOChjb21tZW50KSk7XG4gICAgfVxuICB9XG5cbiAgcHVibGljIHN0YXRpYyAnZ2V0RW50cnlCeXRlcyhKSSlbQicodGhyZWFkOiBKVk1UaHJlYWQsIGp6ZW50cnk6IExvbmcsIHR5cGU6IEpaRW50cnlUeXBlKTogSlZNVHlwZXMuSlZNQXJyYXk8bnVtYmVyPiB7XG4gICAgbGV0IHppcGVudHJ5ID0gR2V0WmlwRW50cnkodGhyZWFkLCBqemVudHJ5LnRvTnVtYmVyKCkpO1xuICAgIGlmICh6aXBlbnRyeSkge1xuICAgICAgc3dpdGNoKHR5cGUpIHtcbiAgICAgICAgY2FzZSBKWkVudHJ5VHlwZS5KWkVOVFJZX0NPTU1FTlQ6XG4gICAgICAgICAgcmV0dXJuIHV0aWwubmV3QXJyYXlGcm9tRGF0YVdpdGhDbGFzcyh0aHJlYWQsIDxBcnJheUNsYXNzRGF0YTxudW1iZXI+PiB0aHJlYWQuZ2V0QnNDbCgpLmdldEluaXRpYWxpemVkQ2xhc3ModGhyZWFkLCAnW0InKSwgPG51bWJlcltdPiBidWZmMmk4KHppcGVudHJ5LnJhd0ZpbGVDb21tZW50KCkpKTtcbiAgICAgICAgY2FzZSBKWkVudHJ5VHlwZS5KWkVOVFJZX0VYVFJBOlxuICAgICAgICAgIHJldHVybiB1dGlsLm5ld0FycmF5RnJvbURhdGFXaXRoQ2xhc3ModGhyZWFkLCA8QXJyYXlDbGFzc0RhdGE8bnVtYmVyPj4gdGhyZWFkLmdldEJzQ2woKS5nZXRJbml0aWFsaXplZENsYXNzKHRocmVhZCwgJ1tCJyksIDxudW1iZXJbXT4gYnVmZjJpOCh6aXBlbnRyeS5leHRyYUZpZWxkKCkpKTtcbiAgICAgICAgY2FzZSBKWkVudHJ5VHlwZS5KWkVOVFJZX05BTUU6XG4gICAgICAgICAgcmV0dXJuIHV0aWwubmV3QXJyYXlGcm9tRGF0YVdpdGhDbGFzcyh0aHJlYWQsIDxBcnJheUNsYXNzRGF0YTxudW1iZXI+PiB0aHJlYWQuZ2V0QnNDbCgpLmdldEluaXRpYWxpemVkQ2xhc3ModGhyZWFkLCAnW0InKSwgPG51bWJlcltdPiBidWZmMmk4KHppcGVudHJ5LnJhd0ZpbGVOYW1lKCkpKTtcbiAgICAgICAgZGVmYXVsdDpcbiAgICAgICAgICByZXR1cm4gbnVsbDtcbiAgICAgIH1cbiAgICB9XG4gIH1cblxuICAvKipcbiAgICogQ2FsbGVkIHRvIGdldCBhbiBleGNlcHRpb24gbWVzc2FnZS4gU2hvdWxkIG5ldmVyIHJlYWxseSBuZWVkIHRvIGJlIGNhbGxlZC5cbiAgICovXG4gIHB1YmxpYyBzdGF0aWMgJ2dldFppcE1lc3NhZ2UoSilMamF2YS9sYW5nL1N0cmluZzsnKHRocmVhZDogSlZNVGhyZWFkLCBqemZpbGU6IExvbmcpOiBKVk1UeXBlcy5qYXZhX2xhbmdfU3RyaW5nIHtcbiAgICByZXR1cm4gdXRpbC5pbml0U3RyaW5nKHRocmVhZC5nZXRCc0NsKCksIFwiU29tZXRoaW5nIGJhZCBoYXBwZW5lZC5cIik7XG4gIH1cblxufVxuXG5yZWdpc3Rlck5hdGl2ZXMoe1xuICAnamF2YS91dGlsL2NvbmN1cnJlbnQvYXRvbWljL0F0b21pY0xvbmcnOiBqYXZhX3V0aWxfY29uY3VycmVudF9hdG9taWNfQXRvbWljTG9uZyxcbiAgJ2phdmEvdXRpbC9qYXIvSmFyRmlsZSc6IGphdmFfdXRpbF9qYXJfSmFyRmlsZSxcbiAgJ2phdmEvdXRpbC9sb2dnaW5nL0ZpbGVIYW5kbGVyJzogamF2YV91dGlsX2xvZ2dpbmdfRmlsZUhhbmRsZXIsXG4gICdqYXZhL3V0aWwvVGltZVpvbmUnOiBqYXZhX3V0aWxfVGltZVpvbmUsXG4gICdqYXZhL3V0aWwvemlwL0FkbGVyMzInOiBqYXZhX3V0aWxfemlwX0FkbGVyMzIsXG4gICdqYXZhL3V0aWwvemlwL0NSQzMyJzogamF2YV91dGlsX3ppcF9DUkMzMixcbiAgJ2phdmEvdXRpbC96aXAvRGVmbGF0ZXInOiBqYXZhX3V0aWxfemlwX0RlZmxhdGVyLFxuICAnamF2YS91dGlsL3ppcC9JbmZsYXRlcic6IGphdmFfdXRpbF96aXBfSW5mbGF0ZXIsXG4gICdqYXZhL3V0aWwvemlwL1ppcEZpbGUnOiBqYXZhX3V0aWxfemlwX1ppcEZpbGVcbn0pO1xuIl19