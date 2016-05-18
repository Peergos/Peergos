"use strict";
var __extends = (this && this.__extends) || function (d, b) {
    for (var p in b) if (b.hasOwnProperty(p)) d[p] = b[p];
    function __() { this.constructor = d; }
    d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
};
var api_error_1 = require('../core/api_error');
var node_fs_stats_1 = require('../core/node_fs_stats');
var file_system = require('../core/file_system');
var file_flag_1 = require('../core/file_flag');
var preload_file = require('../generic/preload_file');
var util_1 = require('../core/util');
var extended_ascii_1 = require('bfs-buffer/js/extended_ascii');
var inflateRaw = require('pako/dist/pako_inflate.min').inflateRaw;
var file_index_1 = require('../generic/file_index');
(function (ExternalFileAttributeType) {
    ExternalFileAttributeType[ExternalFileAttributeType["MSDOS"] = 0] = "MSDOS";
    ExternalFileAttributeType[ExternalFileAttributeType["AMIGA"] = 1] = "AMIGA";
    ExternalFileAttributeType[ExternalFileAttributeType["OPENVMS"] = 2] = "OPENVMS";
    ExternalFileAttributeType[ExternalFileAttributeType["UNIX"] = 3] = "UNIX";
    ExternalFileAttributeType[ExternalFileAttributeType["VM_CMS"] = 4] = "VM_CMS";
    ExternalFileAttributeType[ExternalFileAttributeType["ATARI_ST"] = 5] = "ATARI_ST";
    ExternalFileAttributeType[ExternalFileAttributeType["OS2_HPFS"] = 6] = "OS2_HPFS";
    ExternalFileAttributeType[ExternalFileAttributeType["MAC"] = 7] = "MAC";
    ExternalFileAttributeType[ExternalFileAttributeType["Z_SYSTEM"] = 8] = "Z_SYSTEM";
    ExternalFileAttributeType[ExternalFileAttributeType["CP_M"] = 9] = "CP_M";
    ExternalFileAttributeType[ExternalFileAttributeType["NTFS"] = 10] = "NTFS";
    ExternalFileAttributeType[ExternalFileAttributeType["MVS"] = 11] = "MVS";
    ExternalFileAttributeType[ExternalFileAttributeType["VSE"] = 12] = "VSE";
    ExternalFileAttributeType[ExternalFileAttributeType["ACORN_RISC"] = 13] = "ACORN_RISC";
    ExternalFileAttributeType[ExternalFileAttributeType["VFAT"] = 14] = "VFAT";
    ExternalFileAttributeType[ExternalFileAttributeType["ALT_MVS"] = 15] = "ALT_MVS";
    ExternalFileAttributeType[ExternalFileAttributeType["BEOS"] = 16] = "BEOS";
    ExternalFileAttributeType[ExternalFileAttributeType["TANDEM"] = 17] = "TANDEM";
    ExternalFileAttributeType[ExternalFileAttributeType["OS_400"] = 18] = "OS_400";
    ExternalFileAttributeType[ExternalFileAttributeType["OSX"] = 19] = "OSX";
})(exports.ExternalFileAttributeType || (exports.ExternalFileAttributeType = {}));
var ExternalFileAttributeType = exports.ExternalFileAttributeType;
(function (CompressionMethod) {
    CompressionMethod[CompressionMethod["STORED"] = 0] = "STORED";
    CompressionMethod[CompressionMethod["SHRUNK"] = 1] = "SHRUNK";
    CompressionMethod[CompressionMethod["REDUCED_1"] = 2] = "REDUCED_1";
    CompressionMethod[CompressionMethod["REDUCED_2"] = 3] = "REDUCED_2";
    CompressionMethod[CompressionMethod["REDUCED_3"] = 4] = "REDUCED_3";
    CompressionMethod[CompressionMethod["REDUCED_4"] = 5] = "REDUCED_4";
    CompressionMethod[CompressionMethod["IMPLODE"] = 6] = "IMPLODE";
    CompressionMethod[CompressionMethod["DEFLATE"] = 8] = "DEFLATE";
    CompressionMethod[CompressionMethod["DEFLATE64"] = 9] = "DEFLATE64";
    CompressionMethod[CompressionMethod["TERSE_OLD"] = 10] = "TERSE_OLD";
    CompressionMethod[CompressionMethod["BZIP2"] = 12] = "BZIP2";
    CompressionMethod[CompressionMethod["LZMA"] = 14] = "LZMA";
    CompressionMethod[CompressionMethod["TERSE_NEW"] = 18] = "TERSE_NEW";
    CompressionMethod[CompressionMethod["LZ77"] = 19] = "LZ77";
    CompressionMethod[CompressionMethod["WAVPACK"] = 97] = "WAVPACK";
    CompressionMethod[CompressionMethod["PPMD"] = 98] = "PPMD";
})(exports.CompressionMethod || (exports.CompressionMethod = {}));
var CompressionMethod = exports.CompressionMethod;
function msdos2date(time, date) {
    var day = date & 0x1F;
    var month = ((date >> 5) & 0xF) - 1;
    var year = (date >> 9) + 1980;
    var second = time & 0x1F;
    var minute = (time >> 5) & 0x3F;
    var hour = time >> 11;
    return new Date(year, month, day, hour, minute, second);
}
function safeToString(buff, useUTF8, start, length) {
    if (length === 0) {
        return "";
    }
    else if (useUTF8) {
        return buff.toString('utf8', start, start + length);
    }
    else {
        return extended_ascii_1["default"].byte2str(buff.slice(start, start + length));
    }
}
var FileHeader = (function () {
    function FileHeader(data) {
        this.data = data;
        if (data.readUInt32LE(0) !== 0x04034b50) {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "Invalid Zip file: Local file header has invalid signature: " + this.data.readUInt32LE(0));
        }
    }
    FileHeader.prototype.versionNeeded = function () { return this.data.readUInt16LE(4); };
    FileHeader.prototype.flags = function () { return this.data.readUInt16LE(6); };
    FileHeader.prototype.compressionMethod = function () { return this.data.readUInt16LE(8); };
    FileHeader.prototype.lastModFileTime = function () {
        return msdos2date(this.data.readUInt16LE(10), this.data.readUInt16LE(12));
    };
    FileHeader.prototype.rawLastModFileTime = function () {
        return this.data.readUInt32LE(10);
    };
    FileHeader.prototype.crc32 = function () { return this.data.readUInt32LE(14); };
    FileHeader.prototype.fileNameLength = function () { return this.data.readUInt16LE(26); };
    FileHeader.prototype.extraFieldLength = function () { return this.data.readUInt16LE(28); };
    FileHeader.prototype.fileName = function () {
        return safeToString(this.data, this.useUTF8(), 30, this.fileNameLength());
    };
    FileHeader.prototype.extraField = function () {
        var start = 30 + this.fileNameLength();
        return this.data.slice(start, start + this.extraFieldLength());
    };
    FileHeader.prototype.totalSize = function () { return 30 + this.fileNameLength() + this.extraFieldLength(); };
    FileHeader.prototype.useUTF8 = function () { return (this.flags() & 0x800) === 0x800; };
    return FileHeader;
}());
exports.FileHeader = FileHeader;
var FileData = (function () {
    function FileData(header, record, data) {
        this.header = header;
        this.record = record;
        this.data = data;
    }
    FileData.prototype.decompress = function () {
        var compressionMethod = this.header.compressionMethod();
        switch (compressionMethod) {
            case CompressionMethod.DEFLATE:
                var data = inflateRaw(util_1.buffer2Arrayish(this.data.slice(0, this.record.compressedSize())), { chunkSize: this.record.uncompressedSize() });
                return util_1.arrayish2Buffer(data);
            case CompressionMethod.STORED:
                return util_1.copyingSlice(this.data, 0, this.record.uncompressedSize());
            default:
                var name = CompressionMethod[compressionMethod];
                name = name ? name : "Unknown: " + compressionMethod;
                throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "Invalid compression method on file '" + this.header.fileName() + "': " + name);
        }
    };
    FileData.prototype.getHeader = function () {
        return this.header;
    };
    FileData.prototype.getRecord = function () {
        return this.record;
    };
    FileData.prototype.getRawData = function () {
        return this.data;
    };
    return FileData;
}());
exports.FileData = FileData;
var DataDescriptor = (function () {
    function DataDescriptor(data) {
        this.data = data;
    }
    DataDescriptor.prototype.crc32 = function () { return this.data.readUInt32LE(0); };
    DataDescriptor.prototype.compressedSize = function () { return this.data.readUInt32LE(4); };
    DataDescriptor.prototype.uncompressedSize = function () { return this.data.readUInt32LE(8); };
    return DataDescriptor;
}());
exports.DataDescriptor = DataDescriptor;
var ArchiveExtraDataRecord = (function () {
    function ArchiveExtraDataRecord(data) {
        this.data = data;
        if (this.data.readUInt32LE(0) !== 0x08064b50) {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "Invalid archive extra data record signature: " + this.data.readUInt32LE(0));
        }
    }
    ArchiveExtraDataRecord.prototype.length = function () { return this.data.readUInt32LE(4); };
    ArchiveExtraDataRecord.prototype.extraFieldData = function () { return this.data.slice(8, 8 + this.length()); };
    return ArchiveExtraDataRecord;
}());
exports.ArchiveExtraDataRecord = ArchiveExtraDataRecord;
var DigitalSignature = (function () {
    function DigitalSignature(data) {
        this.data = data;
        if (this.data.readUInt32LE(0) !== 0x05054b50) {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "Invalid digital signature signature: " + this.data.readUInt32LE(0));
        }
    }
    DigitalSignature.prototype.size = function () { return this.data.readUInt16LE(4); };
    DigitalSignature.prototype.signatureData = function () { return this.data.slice(6, 6 + this.size()); };
    return DigitalSignature;
}());
exports.DigitalSignature = DigitalSignature;
var CentralDirectory = (function () {
    function CentralDirectory(zipData, data) {
        this.zipData = zipData;
        this.data = data;
        if (this.data.readUInt32LE(0) !== 0x02014b50)
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "Invalid Zip file: Central directory record has invalid signature: " + this.data.readUInt32LE(0));
        this._filename = this.produceFilename();
    }
    CentralDirectory.prototype.versionMadeBy = function () { return this.data.readUInt16LE(4); };
    CentralDirectory.prototype.versionNeeded = function () { return this.data.readUInt16LE(6); };
    CentralDirectory.prototype.flag = function () { return this.data.readUInt16LE(8); };
    CentralDirectory.prototype.compressionMethod = function () { return this.data.readUInt16LE(10); };
    CentralDirectory.prototype.lastModFileTime = function () {
        return msdos2date(this.data.readUInt16LE(12), this.data.readUInt16LE(14));
    };
    CentralDirectory.prototype.rawLastModFileTime = function () {
        return this.data.readUInt32LE(12);
    };
    CentralDirectory.prototype.crc32 = function () { return this.data.readUInt32LE(16); };
    CentralDirectory.prototype.compressedSize = function () { return this.data.readUInt32LE(20); };
    CentralDirectory.prototype.uncompressedSize = function () { return this.data.readUInt32LE(24); };
    CentralDirectory.prototype.fileNameLength = function () { return this.data.readUInt16LE(28); };
    CentralDirectory.prototype.extraFieldLength = function () { return this.data.readUInt16LE(30); };
    CentralDirectory.prototype.fileCommentLength = function () { return this.data.readUInt16LE(32); };
    CentralDirectory.prototype.diskNumberStart = function () { return this.data.readUInt16LE(34); };
    CentralDirectory.prototype.internalAttributes = function () { return this.data.readUInt16LE(36); };
    CentralDirectory.prototype.externalAttributes = function () { return this.data.readUInt32LE(38); };
    CentralDirectory.prototype.headerRelativeOffset = function () { return this.data.readUInt32LE(42); };
    CentralDirectory.prototype.produceFilename = function () {
        var fileName = safeToString(this.data, this.useUTF8(), 46, this.fileNameLength());
        return fileName.replace(/\\/g, "/");
    };
    CentralDirectory.prototype.fileName = function () {
        return this._filename;
    };
    CentralDirectory.prototype.rawFileName = function () {
        return this.data.slice(46, 46 + this.fileNameLength());
    };
    CentralDirectory.prototype.extraField = function () {
        var start = 44 + this.fileNameLength();
        return this.data.slice(start, start + this.extraFieldLength());
    };
    CentralDirectory.prototype.fileComment = function () {
        var start = 46 + this.fileNameLength() + this.extraFieldLength();
        return safeToString(this.data, this.useUTF8(), start, this.fileCommentLength());
    };
    CentralDirectory.prototype.rawFileComment = function () {
        var start = 46 + this.fileNameLength() + this.extraFieldLength();
        return this.data.slice(start, start + this.fileCommentLength());
    };
    CentralDirectory.prototype.totalSize = function () {
        return 46 + this.fileNameLength() + this.extraFieldLength() + this.fileCommentLength();
    };
    CentralDirectory.prototype.isDirectory = function () {
        var fileName = this.fileName();
        return (this.externalAttributes() & 0x10 ? true : false) || (fileName.charAt(fileName.length - 1) === '/');
    };
    CentralDirectory.prototype.isFile = function () { return !this.isDirectory(); };
    CentralDirectory.prototype.useUTF8 = function () { return (this.flag() & 0x800) === 0x800; };
    CentralDirectory.prototype.isEncrypted = function () { return (this.flag() & 0x1) === 0x1; };
    CentralDirectory.prototype.getFileData = function () {
        var start = this.headerRelativeOffset();
        var header = new FileHeader(this.zipData.slice(start));
        return new FileData(header, this, this.zipData.slice(start + header.totalSize()));
    };
    CentralDirectory.prototype.getData = function () {
        return this.getFileData().decompress();
    };
    CentralDirectory.prototype.getRawData = function () {
        return this.getFileData().getRawData();
    };
    CentralDirectory.prototype.getStats = function () {
        return new node_fs_stats_1["default"](node_fs_stats_1.FileType.FILE, this.uncompressedSize(), 0x16D, new Date(), this.lastModFileTime());
    };
    return CentralDirectory;
}());
exports.CentralDirectory = CentralDirectory;
var EndOfCentralDirectory = (function () {
    function EndOfCentralDirectory(data) {
        this.data = data;
        if (this.data.readUInt32LE(0) !== 0x06054b50)
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "Invalid Zip file: End of central directory record has invalid signature: " + this.data.readUInt32LE(0));
    }
    EndOfCentralDirectory.prototype.diskNumber = function () { return this.data.readUInt16LE(4); };
    EndOfCentralDirectory.prototype.cdDiskNumber = function () { return this.data.readUInt16LE(6); };
    EndOfCentralDirectory.prototype.cdDiskEntryCount = function () { return this.data.readUInt16LE(8); };
    EndOfCentralDirectory.prototype.cdTotalEntryCount = function () { return this.data.readUInt16LE(10); };
    EndOfCentralDirectory.prototype.cdSize = function () { return this.data.readUInt32LE(12); };
    EndOfCentralDirectory.prototype.cdOffset = function () { return this.data.readUInt32LE(16); };
    EndOfCentralDirectory.prototype.cdZipCommentLength = function () { return this.data.readUInt16LE(20); };
    EndOfCentralDirectory.prototype.cdZipComment = function () {
        return safeToString(this.data, true, 22, this.cdZipCommentLength());
    };
    EndOfCentralDirectory.prototype.rawCdZipComment = function () {
        return this.data.slice(22, 22 + this.cdZipCommentLength());
    };
    return EndOfCentralDirectory;
}());
exports.EndOfCentralDirectory = EndOfCentralDirectory;
var ZipTOC = (function () {
    function ZipTOC(index, directoryEntries, eocd, data) {
        this.index = index;
        this.directoryEntries = directoryEntries;
        this.eocd = eocd;
        this.data = data;
    }
    return ZipTOC;
}());
exports.ZipTOC = ZipTOC;
var ZipFS = (function (_super) {
    __extends(ZipFS, _super);
    function ZipFS(input, name) {
        if (name === void 0) { name = ''; }
        _super.call(this);
        this.input = input;
        this.name = name;
        this._index = new file_index_1.FileIndex();
        this._directoryEntries = [];
        this._eocd = null;
        if (input instanceof ZipTOC) {
            this._index = input.index;
            this._directoryEntries = input.directoryEntries;
            this._eocd = input.eocd;
            this.data = input.data;
        }
        else {
            this.data = input;
            this.populateIndex();
        }
    }
    ZipFS.prototype.getName = function () {
        return 'ZipFS' + (this.name !== '' ? ' ' + this.name : '');
    };
    ZipFS.prototype.getCentralDirectoryEntry = function (path) {
        var inode = this._index.getInode(path);
        if (inode === null) {
            throw api_error_1.ApiError.ENOENT(path);
        }
        if (file_index_1.isFileInode(inode)) {
            return inode.getData();
        }
        else if (file_index_1.isDirInode(inode)) {
            return inode.getData();
        }
    };
    ZipFS.prototype.getCentralDirectoryEntryAt = function (index) {
        var dirEntry = this._directoryEntries[index];
        if (!dirEntry) {
            throw new RangeError("Invalid directory index: " + index + ".");
        }
        return dirEntry;
    };
    ZipFS.prototype.getNumberOfCentralDirectoryEntries = function () {
        return this._directoryEntries.length;
    };
    ZipFS.prototype.getEndOfCentralDirectory = function () {
        return this._eocd;
    };
    ZipFS.isAvailable = function () { return true; };
    ZipFS.prototype.diskSpace = function (path, cb) {
        cb(this.data.length, 0);
    };
    ZipFS.prototype.isReadOnly = function () {
        return true;
    };
    ZipFS.prototype.supportsLinks = function () {
        return false;
    };
    ZipFS.prototype.supportsProps = function () {
        return false;
    };
    ZipFS.prototype.supportsSynch = function () {
        return true;
    };
    ZipFS.prototype.statSync = function (path, isLstat) {
        var inode = this._index.getInode(path);
        if (inode === null) {
            throw api_error_1.ApiError.ENOENT(path);
        }
        var stats;
        if (file_index_1.isFileInode(inode)) {
            stats = inode.getData().getStats();
        }
        else if (file_index_1.isDirInode(inode)) {
            stats = inode.getStats();
        }
        else {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "Invalid inode.");
        }
        return stats;
    };
    ZipFS.prototype.openSync = function (path, flags, mode) {
        if (flags.isWriteable()) {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EPERM, path);
        }
        var inode = this._index.getInode(path);
        if (!inode) {
            throw api_error_1.ApiError.ENOENT(path);
        }
        else if (file_index_1.isFileInode(inode)) {
            var cdRecord = inode.getData();
            var stats = cdRecord.getStats();
            switch (flags.pathExistsAction()) {
                case file_flag_1.ActionType.THROW_EXCEPTION:
                case file_flag_1.ActionType.TRUNCATE_FILE:
                    throw api_error_1.ApiError.EEXIST(path);
                case file_flag_1.ActionType.NOP:
                    return new preload_file.NoSyncFile(this, path, flags, stats, cdRecord.getData());
                default:
                    throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, 'Invalid FileMode object.');
            }
        }
        else {
            throw api_error_1.ApiError.EISDIR(path);
        }
    };
    ZipFS.prototype.readdirSync = function (path) {
        var inode = this._index.getInode(path);
        if (!inode) {
            throw api_error_1.ApiError.ENOENT(path);
        }
        else if (file_index_1.isDirInode(inode)) {
            return inode.getListing();
        }
        else {
            throw api_error_1.ApiError.ENOTDIR(path);
        }
    };
    ZipFS.prototype.readFileSync = function (fname, encoding, flag) {
        var fd = this.openSync(fname, flag, 0x1a4);
        try {
            var fdCast = fd;
            var fdBuff = fdCast.getBuffer();
            if (encoding === null) {
                return util_1.copyingSlice(fdBuff);
            }
            return fdBuff.toString(encoding);
        }
        finally {
            fd.closeSync();
        }
    };
    ZipFS.getEOCD = function (data) {
        var startOffset = 22;
        var endOffset = Math.min(startOffset + 0xFFFF, data.length - 1);
        for (var i = startOffset; i < endOffset; i++) {
            if (data.readUInt32LE(data.length - i) === 0x06054b50) {
                return new EndOfCentralDirectory(data.slice(data.length - i));
            }
        }
        throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "Invalid ZIP file: Could not locate End of Central Directory signature.");
    };
    ZipFS.addToIndex = function (cd, index) {
        var filename = cd.fileName();
        if (filename.charAt(0) === '/')
            throw new Error("WHY IS THIS ABSOLUTE");
        if (filename.charAt(filename.length - 1) === '/') {
            filename = filename.substr(0, filename.length - 1);
        }
        if (cd.isDirectory()) {
            index.addPathFast('/' + filename, new file_index_1.DirInode(cd));
        }
        else {
            index.addPathFast('/' + filename, new file_index_1.FileInode(cd));
        }
    };
    ZipFS.computeIndexResponsive = function (data, index, cdPtr, cdEnd, cb, cdEntries, eocd) {
        if (cdPtr < cdEnd) {
            var count = 0;
            while (count++ < 200 && cdPtr < cdEnd) {
                var cd = new CentralDirectory(data, data.slice(cdPtr));
                ZipFS.addToIndex(cd, index);
                cdPtr += cd.totalSize();
                cdEntries.push(cd);
            }
            setImmediate(function () {
                ZipFS.computeIndexResponsive(data, index, cdPtr, cdEnd, cb, cdEntries, eocd);
            });
        }
        else {
            cb(new ZipTOC(index, cdEntries, eocd, data));
        }
    };
    ZipFS.computeIndex = function (data, cb) {
        var index = new file_index_1.FileIndex();
        var eocd = ZipFS.getEOCD(data);
        if (eocd.diskNumber() !== eocd.cdDiskNumber())
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "ZipFS does not support spanned zip files.");
        var cdPtr = eocd.cdOffset();
        if (cdPtr === 0xFFFFFFFF)
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "ZipFS does not support Zip64.");
        var cdEnd = cdPtr + eocd.cdSize();
        ZipFS.computeIndexResponsive(data, index, cdPtr, cdEnd, cb, [], eocd);
    };
    ZipFS.prototype.populateIndex = function () {
        var eocd = this._eocd = ZipFS.getEOCD(this.data);
        if (eocd.diskNumber() !== eocd.cdDiskNumber())
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "ZipFS does not support spanned zip files.");
        var cdPtr = eocd.cdOffset();
        if (cdPtr === 0xFFFFFFFF)
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EINVAL, "ZipFS does not support Zip64.");
        var cdEnd = cdPtr + eocd.cdSize();
        while (cdPtr < cdEnd) {
            var cd = new CentralDirectory(this.data, this.data.slice(cdPtr));
            cdPtr += cd.totalSize();
            ZipFS.addToIndex(cd, this._index);
            this._directoryEntries.push(cd);
        }
    };
    return ZipFS;
}(file_system.SynchronousFileSystem));
exports.__esModule = true;
exports["default"] = ZipFS;
//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJmaWxlIjoiWmlwRlMuanMiLCJzb3VyY2VSb290IjoiIiwic291cmNlcyI6WyIuLi8uLi8uLi9zcmMvYmFja2VuZC9aaXBGUy50cyJdLCJuYW1lcyI6W10sIm1hcHBpbmdzIjoiOzs7Ozs7QUErQ0EsMEJBQWtDLG1CQUFtQixDQUFDLENBQUE7QUFDdEQsOEJBQXlDLHVCQUF1QixDQUFDLENBQUE7QUFDakUsSUFBTyxXQUFXLFdBQVcscUJBQXFCLENBQUMsQ0FBQztBQUVwRCwwQkFBbUMsbUJBQW1CLENBQUMsQ0FBQTtBQUN2RCxJQUFPLFlBQVksV0FBVyx5QkFBeUIsQ0FBQyxDQUFDO0FBQ3pELHFCQUF1RSxjQUFjLENBQUMsQ0FBQTtBQUN0RiwrQkFBMEIsOEJBQThCLENBQUMsQ0FBQTtBQUN6RCxJQUFJLFVBQVUsR0FJVixPQUFPLENBQUMsNEJBQTRCLENBQUMsQ0FBQyxVQUFVLENBQUM7QUFDckQsMkJBQXNFLHVCQUF1QixDQUFDLENBQUE7QUFNOUYsV0FBWSx5QkFBeUI7SUFDbkMsMkVBQVMsQ0FBQTtJQUFFLDJFQUFTLENBQUE7SUFBRSwrRUFBVyxDQUFBO0lBQUUseUVBQVEsQ0FBQTtJQUFFLDZFQUFVLENBQUE7SUFBRSxpRkFBWSxDQUFBO0lBQ3JFLGlGQUFZLENBQUE7SUFBRSx1RUFBTyxDQUFBO0lBQUUsaUZBQVksQ0FBQTtJQUFFLHlFQUFRLENBQUE7SUFBRSwwRUFBUyxDQUFBO0lBQUUsd0VBQVEsQ0FBQTtJQUFFLHdFQUFRLENBQUE7SUFDNUUsc0ZBQWUsQ0FBQTtJQUFFLDBFQUFTLENBQUE7SUFBRSxnRkFBWSxDQUFBO0lBQUUsMEVBQVMsQ0FBQTtJQUFFLDhFQUFXLENBQUE7SUFBRSw4RUFBVyxDQUFBO0lBQzdFLHdFQUFRLENBQUE7QUFDVixDQUFDLEVBTFcsaUNBQXlCLEtBQXpCLGlDQUF5QixRQUtwQztBQUxELElBQVkseUJBQXlCLEdBQXpCLGlDQUtYLENBQUE7QUFLRCxXQUFZLGlCQUFpQjtJQUMzQiw2REFBVSxDQUFBO0lBQ1YsNkRBQVUsQ0FBQTtJ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QyxxQkFBUyxDQUFDLE1BQU0sRUFBRSwyQ0FBMkMsQ0FBQyxDQUFDO1FBRXBGLElBQUksS0FBSyxHQUFHLElBQUksQ0FBQyxRQUFRLEVBQUUsQ0FBQztRQUM1QixFQUFFLENBQUMsQ0FBQyxLQUFLLEtBQUssVUFBVSxDQUFDO1lBQ3ZCLE1BQU0sSUFBSSxvQkFBUSxDQUFDLHFCQUFTLENBQUMsTUFBTSxFQUFFLCtCQUErQixDQUFDLENBQUM7UUFDeEUsSUFBSSxLQUFLLEdBQUcsS0FBSyxHQUFHLElBQUksQ0FBQyxNQUFNLEVBQUUsQ0FBQztRQUNsQyxPQUFPLEtBQUssR0FBRyxLQUFLLEVBQUUsQ0FBQztZQUNyQixJQUFNLEVBQUUsR0FBcUIsSUFBSSxnQkFBZ0IsQ0FBQyxJQUFJLENBQUMsSUFBSSxFQUFFLElBQUksQ0FBQyxJQUFJLENBQUMsS0FBSyxDQUFDLEtBQUssQ0FBQyxDQUFDLENBQUM7WUFDckYsS0FBSyxJQUFJLEVBQUUsQ0FBQyxTQUFTLEVBQUUsQ0FBQztZQUN4QixLQUFLLENBQUMsVUFBVSxDQUFDLEVBQUUsRUFBRSxJQUFJLENBQUMsTUFBTSxDQUFDLENBQUM7WUFDbEMsSUFBSSxDQUFDLGlCQUFpQixDQUFDLElBQUksQ0FBQyxFQUFFLENBQUMsQ0FBQztRQUNsQyxDQUFDO0lBQ0gsQ0FBQztJQUNILFlBQUM7QUFBRCxDQUFDLEFBalBELENBQW1DLFdBQVcsQ0FBQyxxQkFBcUIsR0FpUG5FO0FBalBEOzBCQWlQQyxDQUFBIiwic291cmNlc0NvbnRlbnQiOlsiLyoqXHJcbiAqIFppcCBmaWxlLWJhY2tlZCBmaWxlc3lzdGVtXHJcbiAqIEltcGxlbWVudGVkIGFjY29yZGluZyB0byB0aGUgc3RhbmRhcmQ6XHJcbiAqIGh0dHA6Ly93d3cucGt3YXJlLmNvbS9kb2N1bWVudHMvY2FzZXN0dWRpZXMvQVBQTk9URS5UWFRcclxuICpcclxuICogV2hpbGUgdGhlcmUgYXJlIGEgZmV3IHppcCBsaWJyYXJpZXMgZm9yIEphdmFTY3JpcHQgKGUuZy4gSlNaaXAgYW5kIHppcC5qcyksXHJcbiAqIHRoZXkgYXJlIG5vdCBhIGdvb2QgbWF0Y2ggZm9yIEJyb3dzZXJGUy4gSW4gcGFydGljdWxhciwgdGhlc2UgbGlicmFyaWVzXHJcbiAqIHBlcmZvcm0gYSBsb3Qgb2YgdW5uZWVkZWQgZGF0YSBjb3B5aW5nLCBhbmQgZWFnZXJseSBkZWNvbXByZXNzIGV2ZXJ5IGZpbGVcclxuICogaW4gdGhlIHppcCBmaWxlIHVwb24gbG9hZGluZyB0byBjaGVjayB0aGUgQ1JDMzIuIFRoZXkgYWxzbyBlYWdlcmx5IGRlY29kZVxyXG4gKiBzdHJpbmdzLiBGdXJ0aGVybW9yZSwgdGhlc2UgbGlicmFyaWVzIGR1cGxpY2F0ZSBmdW5jdGlvbmFsaXR5IGFscmVhZHkgcHJlc2VudFxyXG4gKiBpbiBCcm93c2VyRlMgKGUuZy4gVVRGLTggZGVjb2RpbmcgYW5kIGJpbmFyeSBkYXRhIG1hbmlwdWxhdGlvbikuXHJcbiAqXHJcbiAqIFRoaXMgZmlsZXN5c3RlbSB0YWtlcyBhZHZhbnRhZ2Ugb2YgQnJvd3NlckZTJ3MgQnVmZmVyIGltcGxlbWVudGF0aW9uLCB3aGljaFxyXG4gKiBlZmZpY2llbnRseSByZXByZXNlbnRzIHRoZSB6aXAgZmlsZSBpbiBtZW1vcnkgKGluIGJvdGggQXJyYXlCdWZmZXItZW5hYmxlZFxyXG4gKiBicm93c2VycyAqYW5kKiBub24tQXJyYXlCdWZmZXIgYnJvd3NlcnMpLCBhbmQgd2hpY2ggY2FuIG5lYXRseSBiZSAnc2xpY2VkJ1xyXG4gKiB3aXRob3V0IGNvcHlpbmcgZGF0YS4gRWFjaCBzdHJ1Y3QgZGVmaW5lZCBpbiB0aGUgc3RhbmRhcmQgaXMgcmVwcmVzZW50ZWQgd2l0aFxyXG4gKiBhIGJ1ZmZlciBzbGljZSBwb2ludGluZyB0byBhbiBvZmZzZXQgaW4gdGhlIHppcCBmaWxlLCBhbmQgaGFzIGdldHRlcnMgZm9yXHJcbiAqIGVhY2ggZmllbGQuIEFzIHdlIGFudGljaXBhdGUgdGhhdCB0aGlzIGRhdGEgd2lsbCBub3QgYmUgcmVhZCBvZnRlbiwgd2UgY2hvb3NlXHJcbiAqIG5vdCB0byBzdG9yZSBlYWNoIHN0cnVjdCBmaWVsZCBpbiB0aGUgSmF2YVNjcmlwdCBvYmplY3Q7IGluc3RlYWQsIHRvIHJlZHVjZVxyXG4gKiBtZW1vcnkgY29uc3VtcHRpb24sIHdlIHJldHJpZXZlIGl0IGRpcmVjdGx5IGZyb20gdGhlIGJpbmFyeSBkYXRhIGVhY2ggdGltZSBpdFxyXG4gKiBpcyByZXF1ZXN0ZWQuXHJcbiAqXHJcbiAqIFdoZW4gdGhlIGZpbGVzeXN0ZW0gaXMgaW5zdGFudGlhdGVkLCB3ZSBkZXRlcm1pbmUgdGhlIGRpcmVjdG9yeSBzdHJ1Y3R1cmVcclxuICogb2YgdGhlIHppcCBmaWxlIGFzIHF1aWNrbHkgYXMgcG9zc2libGUuIFdlIGxhemlseSBkZWNvbXByZXNzIGFuZCBjaGVjayB0aGVcclxuICogQ1JDMzIgb2YgZmlsZXMuIFdlIGRvIG5vdCBjYWNoZSBkZWNvbXByZXNzZWQgZmlsZXM7IGlmIHRoaXMgaXMgYSBkZXNpcmVkXHJcbiAqIGZlYXR1cmUsIGl0IGlzIGJlc3QgaW1wbGVtZW50ZWQgYXMgYSBnZW5lcmljIGZpbGUgc3lzdGVtIHdyYXBwZXIgdGhhdCBjYW5cclxuICogY2FjaGUgZGF0YSBmcm9tIGFyYml0cmFyeSBmaWxlIHN5c3RlbXMuXHJcbiAqXHJcbiAqIEZvciBpbmZsYXRpb24sIHdlIHVzZSBgcGFqb2AncyBpbXBsZW1lbnRhdGlvbjpcclxuICogaHR0cHM6Ly9naXRodWIuY29tL25vZGVjYS9wYWtvXHJcbiAqXHJcbiAqIFVuZm9ydHVuYXRlbHksIHRoZWlyIGltcGxlbWVudGF0aW9uIGZhbGxzIGJhY2sgdG8gYW4gYXJyYXkgb2YgYnl0ZXMgZm9yIG5vbi1cclxuICogVHlwZWRBcnJheSBicm93c2Vycywgd2hpY2ggaXMgcmVzdWx0cyBpbiBhIG11Y2ggbGFyZ2VyIG1lbW9yeSBmb290cHJpbnQgaW5cclxuICogdGhvc2UgYnJvd3NlcnMuIFBlcmhhcHMgb25lIGRheSB3ZSdsbCBoYXZlIGFuIGltcGxlbWVudGF0aW9uIG9mIGluZmxhdGUgdGhhdFxyXG4gKiB3b3JrcyBvbiBCdWZmZXJzPyA6KVxyXG4gKlxyXG4gKiBDdXJyZW50IGxpbWl0YXRpb25zOlxyXG4gKiAqIE5vIGVuY3J5cHRpb24uXHJcbiAqICogTm8gWklQNjQgc3VwcG9ydC5cclxuICogKiBSZWFkLW9ubHkuXHJcbiAqICAgV3JpdGUgc3VwcG9ydCB3b3VsZCByZXF1aXJlIHRoYXQgd2U6XHJcbiAqICAgLSBLZWVwIHRyYWNrIG9mIGNoYW5nZWQvbmV3IGZpbGVzLlxyXG4gKiAgIC0gQ29tcHJlc3MgY2hhbmdlZCBmaWxlcywgYW5kIGdlbmVyYXRlIGFwcHJvcHJpYXRlIG1ldGFkYXRhIGZvciBlYWNoLlxyXG4gKiAgIC0gVXBkYXRlIGZpbGUgb2Zmc2V0cyBmb3Igb3RoZXIgZmlsZXMgaW4gdGhlIHppcCBmaWxlLlxyXG4gKiAgIC0gU3RyZWFtIGl0IG91dCB0byBhIGxvY2F0aW9uLlxyXG4gKiAgIFRoaXMgaXNuJ3QgdGhhdCBiYWQsIHNvIHdlIG1pZ2h0IGRvIHRoaXMgYXQgYSBsYXRlciBkYXRlLlxyXG4gKi9cclxuaW1wb3J0IHtBcGlFcnJvciwgRXJyb3JDb2RlfSBmcm9tICcuLi9jb3JlL2FwaV9lcnJvcic7XHJcbmltcG9ydCB7ZGVmYXVsdCBhcyBTdGF0cywgRmlsZVR5cGV9IGZyb20gJy4uL2NvcmUvbm9kZV9mc19zdGF0cyc7XHJcbmltcG9ydCBmaWxlX3N5c3RlbSA9IHJlcXVpcmUoJy4uL2NvcmUvZmlsZV9zeXN0ZW0nKTtcclxuaW1wb3J0IGZpbGUgPSByZXF1aXJlKCcuLi9jb3JlL2ZpbGUnKTtcclxuaW1wb3J0IHtGaWxlRmxhZywgQWN0aW9uVHlwZX0gZnJvbSAnLi4vY29yZS9maWxlX2ZsYWcnO1xyXG5pbXBvcnQgcHJlbG9hZF9maWxlID0gcmVxdWlyZSgnLi4vZ2VuZXJpYy9wcmVsb2FkX2ZpbGUnKTtcclxuaW1wb3J0IHtBcnJheWlzaCwgYnVmZmVyMkFycmF5aXNoLCBhcnJheWlzaDJCdWZmZXIsIGNvcHlpbmdTbGljZX0gZnJvbSAnLi4vY29yZS91dGlsJztcclxuaW1wb3J0IEV4dGVuZGVkQVNDSUkgZnJvbSAnYmZzLWJ1ZmZlci9qcy9leHRlbmRlZF9hc2NpaSc7XHJcbnZhciBpbmZsYXRlUmF3OiB7XHJcbiAgKGRhdGE6IEFycmF5aXNoPG51bWJlcj4sIG9wdGlvbnM/OiB7XHJcbiAgICBjaHVua1NpemU6IG51bWJlcjtcclxuICB9KTogQXJyYXlpc2g8bnVtYmVyPjtcclxufSA9IHJlcXVpcmUoJ3Bha28vZGlzdC9wYWtvX2luZmxhdGUubWluJykuaW5mbGF0ZVJhdztcclxuaW1wb3J0IHtGaWxlSW5kZXgsIERpcklub2RlLCBGaWxlSW5vZGUsIGlzRGlySW5vZGUsIGlzRmlsZUlub2RlfSBmcm9tICcuLi9nZW5lcmljL2ZpbGVfaW5kZXgnO1xyXG5cclxuXHJcbi8qKlxyXG4gKiA0LjQuMi4yOiBJbmRpY2F0ZXMgdGhlIGNvbXBhdGliaWx0aXkgb2YgYSBmaWxlJ3MgZXh0ZXJuYWwgYXR0cmlidXRlcy5cclxuICovXHJcbmV4cG9ydCBlbnVtIEV4dGVybmFsRmlsZUF0dHJpYnV0ZVR5cGUge1xyXG4gIE1TRE9TID0gMCwgQU1JR0EgPSAxLCBPUEVOVk1TID0gMiwgVU5JWCA9IDMsIFZNX0NNUyA9IDQsIEFUQVJJX1NUID0gNSxcclxuICBPUzJfSFBGUyA9IDYsIE1BQyA9IDcsIFpfU1lTVEVNID0gOCwgQ1BfTSA9IDksIE5URlMgPSAxMCwgTVZTID0gMTEsIFZTRSA9IDEyLFxyXG4gIEFDT1JOX1JJU0MgPSAxMywgVkZBVCA9IDE0LCBBTFRfTVZTID0gMTUsIEJFT1MgPSAxNiwgVEFOREVNID0gMTcsIE9TXzQwMCA9IDE4LFxyXG4gIE9TWCA9IDE5XHJcbn1cclxuXHJcbi8qKlxyXG4gKiA0LjQuNVxyXG4gKi9cclxuZXhwb3J0IGVudW0gQ29tcHJlc3Npb25NZXRob2Qge1xyXG4gIFNUT1JFRCA9IDAsICAgICAvLyBUaGUgZmlsZSBpcyBzdG9yZWQgKG5vIGNvbXByZXNzaW9uKVxyXG4gIFNIUlVOSyA9IDEsICAgICAvLyBUaGUgZmlsZSBpcyBTaHJ1bmtcclxuICBSRURVQ0VEXzEgPSAyLCAgLy8gVGhlIGZpbGUgaXMgUmVkdWNlZCB3aXRoIGNvbXByZXNzaW9uIGZhY3RvciAxXHJcbiAgUkVEVUNFRF8yID0gMywgIC8vIFRoZSBmaWxlIGlzIFJlZHVjZWQgd2l0aCBjb21wcmVzc2lvbiBmYWN0b3IgMlxyXG4gIFJFRFVDRURfMyA9IDQsICAvLyBUaGUgZmlsZSBpcyBSZWR1Y2VkIHdpdGggY29tcHJlc3Npb24gZmFjdG9yIDNcclxuICBSRURVQ0VEXzQgPSA1LCAgLy8gVGhlIGZpbGUgaXMgUmVkdWNlZCB3aXRoIGNvbXByZXNzaW9uIGZhY3RvciA0XHJcbiAgSU1QTE9ERSA9IDYsICAgIC8vIFRoZSBmaWxlIGlzIEltcGxvZGVkXHJcbiAgREVGTEFURSA9IDgsICAgIC8vIFRoZSBmaWxlIGlzIERlZmxhdGVkXHJcbiAgREVGTEFURTY0ID0gOSwgIC8vIEVuaGFuY2VkIERlZmxhdGluZyB1c2luZyBEZWZsYXRlNjQodG0pXHJcbiAgVEVSU0VfT0xEID0gMTAsIC8vIFBLV0FSRSBEYXRhIENvbXByZXNzaW9uIExpYnJhcnkgSW1wbG9kaW5nIChvbGQgSUJNIFRFUlNFKVxyXG4gIEJaSVAyID0gMTIsICAgICAvLyBGaWxlIGlzIGNvbXByZXNzZWQgdXNpbmcgQlpJUDIgYWxnb3JpdGhtXHJcbiAgTFpNQSA9IDE0LCAgICAgIC8vIExaTUEgKEVGUylcclxuICBURVJTRV9ORVcgPSAxOCwgLy8gRmlsZSBpcyBjb21wcmVzc2VkIHVzaW5nIElCTSBURVJTRSAobmV3KVxyXG4gIExaNzcgPSAxOSwgICAgICAvLyBJQk0gTFo3NyB6IEFyY2hpdGVjdHVyZSAoUEZTKVxyXG4gIFdBVlBBQ0sgPSA5NywgICAvLyBXYXZQYWNrIGNvbXByZXNzZWQgZGF0YVxyXG4gIFBQTUQgPSA5OCAgICAgICAvLyBQUE1kIHZlcnNpb24gSSwgUmV2IDFcclxufVxyXG5cclxuLyoqXHJcbiAqIENvbnZlcnRzIHRoZSBpbnB1dCB0aW1lIGFuZCBkYXRlIGluIE1TLURPUyBmb3JtYXQgaW50byBhIEphdmFTY3JpcHQgRGF0ZVxyXG4gKiBvYmplY3QuXHJcbiAqL1xyXG5mdW5jdGlvbiBtc2RvczJkYXRlKHRpbWU6IG51bWJlciwgZGF0ZTogbnVtYmVyKTogRGF0ZSB7XHJcbiAgLy8gTVMtRE9TIERhdGVcclxuICAvL3wwIDAgMCAwICAwfDAgMCAwICAwfDAgMCAwICAwIDAgMCAwXHJcbiAgLy8gIEQgKDEtMzEpICBNICgxLTIzKSAgWSAoZnJvbSAxOTgwKVxyXG4gIHZhciBkYXkgPSBkYXRlICYgMHgxRjtcclxuICAvLyBKUyBkYXRlIGlzIDAtaW5kZXhlZCwgRE9TIGlzIDEtaW5kZXhlZC5cclxuICB2YXIgbW9udGggPSAoKGRhdGUgPj4gNSkgJiAweEYpIC0gMTtcclxuICB2YXIgeWVhciA9IChkYXRlID4+IDkpICsgMTk4MDtcclxuICAvLyBNUyBET1MgVGltZVxyXG4gIC8vfDAgMCAwIDAgIDB8MCAwIDAgIDAgMCAwfDAgIDAgMCAwIDBcclxuICAvLyAgIFNlY29uZCAgICAgIE1pbnV0ZSAgICAgICBIb3VyXHJcbiAgdmFyIHNlY29uZCA9IHRpbWUgJiAweDFGO1xyXG4gIHZhciBtaW51dGUgPSAodGltZSA+PiA1KSAmIDB4M0Y7XHJcbiAgdmFyIGhvdXIgPSB0aW1lID4+IDExO1xyXG4gIHJldHVybiBuZXcgRGF0ZSh5ZWFyLCBtb250aCwgZGF5LCBob3VyLCBtaW51dGUsIHNlY29uZCk7XHJcbn1cclxuXHJcbi8qKlxyXG4gKiBTYWZlbHkgcmV0dXJucyB0aGUgc3RyaW5nIGZyb20gdGhlIGJ1ZmZlciwgZXZlbiBpZiBpdCBpcyAwIGJ5dGVzIGxvbmcuXHJcbiAqIChOb3JtYWxseSwgY2FsbGluZyB0b1N0cmluZygpIG9uIGEgYnVmZmVyIHdpdGggc3RhcnQgPT09IGVuZCBjYXVzZXMgYW5cclxuICogZXhjZXB0aW9uKS5cclxuICovXHJcbmZ1bmN0aW9uIHNhZmVUb1N0cmluZyhidWZmOiBOb2RlQnVmZmVyLCB1c2VVVEY4OiBib29sZWFuLCBzdGFydDogbnVtYmVyLCBsZW5ndGg6IG51bWJlcik6IHN0cmluZyB7XHJcbiAgaWYgKGxlbmd0aCA9PT0gMCkge1xyXG4gICAgcmV0dXJuIFwiXCI7XHJcbiAgfSBlbHNlIGlmICh1c2VVVEY4KSB7XHJcbiAgICByZXR1cm4gYnVmZi50b1N0cmluZygndXRmOCcsIHN0YXJ0LCBzdGFydCArIGxlbmd0aCk7XHJcbiAgfSBlbHNlIHtcclxuICAgIHJldHVybiBFeHRlbmRlZEFTQ0lJLmJ5dGUyc3RyKGJ1ZmYuc2xpY2Uoc3RhcnQsIHN0YXJ0ICsgbGVuZ3RoKSk7XHJcbiAgfVxyXG59XHJcblxyXG4vKlxyXG4gICA0LjMuNiBPdmVyYWxsIC5aSVAgZmlsZSBmb3JtYXQ6XHJcblxyXG4gICAgICBbbG9jYWwgZmlsZSBoZWFkZXIgMV1cclxuICAgICAgW2VuY3J5cHRpb24gaGVhZGVyIDFdXHJcbiAgICAgIFtmaWxlIGRhdGEgMV1cclxuICAgICAgW2RhdGEgZGVzY3JpcHRvciAxXVxyXG4gICAgICAuXHJcbiAgICAgIC5cclxuICAgICAgLlxyXG4gICAgICBbbG9jYWwgZmlsZSBoZWFkZXIgbl1cclxuICAgICAgW2VuY3J5cHRpb24gaGVhZGVyIG5dXHJcbiAgICAgIFtmaWxlIGRhdGEgbl1cclxuICAgICAgW2RhdGEgZGVzY3JpcHRvciBuXVxyXG4gICAgICBbYXJjaGl2ZSBkZWNyeXB0aW9uIGhlYWRlcl1cclxuICAgICAgW2FyY2hpdmUgZXh0cmEgZGF0YSByZWNvcmRdXHJcbiAgICAgIFtjZW50cmFsIGRpcmVjdG9yeSBoZWFkZXIgMV1cclxuICAgICAgLlxyXG4gICAgICAuXHJcbiAgICAgIC5cclxuICAgICAgW2NlbnRyYWwgZGlyZWN0b3J5IGhlYWRlciBuXVxyXG4gICAgICBbemlwNjQgZW5kIG9mIGNlbnRyYWwgZGlyZWN0b3J5IHJlY29yZF1cclxuICAgICAgW3ppcDY0IGVuZCBvZiBjZW50cmFsIGRpcmVjdG9yeSBsb2NhdG9yXVxyXG4gICAgICBbZW5kIG9mIGNlbnRyYWwgZGlyZWN0b3J5IHJlY29yZF1cclxuKi9cclxuXHJcbi8qXHJcbiA0LjMuNyAgTG9jYWwgZmlsZSBoZWFkZXI6XHJcblxyXG4gICAgICBsb2NhbCBmaWxlIGhlYWRlciBzaWduYXR1cmUgICAgIDQgYnl0ZXMgICgweDA0MDM0YjUwKVxyXG4gICAgICB2ZXJzaW9uIG5lZWRlZCB0byBleHRyYWN0ICAgICAgIDIgYnl0ZXNcclxuICAgICAgZ2VuZXJhbCBwdXJwb3NlIGJpdCBmbGFnICAgICAgICAyIGJ5dGVzXHJcbiAgICAgIGNvbXByZXNzaW9uIG1ldGhvZCAgICAgICAgICAgICAgMiBieXRlc1xyXG4gICAgICBsYXN0IG1vZCBmaWxlIHRpbWUgICAgICAgICAgICAgIDIgYnl0ZXNcclxuICAgICAgbGFzdCBtb2QgZmlsZSBkYXRlICAgICAgICAgICAgICAyIGJ5dGVzXHJcbiAgICAgIGNyYy0zMiAgICAgICAgICAgICAgICAgICAgICAgICAgNCBieXRlc1xyXG4gICAgICBjb21wcmVzc2VkIHNpemUgICAgICAgICAgICAgICAgIDQgYnl0ZXNcclxuICAgICAgdW5jb21wcmVzc2VkIHNpemUgICAgICAgICAgICAgICA0IGJ5dGVzXHJcbiAgICAgIGZpbGUgbmFtZSBsZW5ndGggICAgICAgICAgICAgICAgMiBieXRlc1xyXG4gICAgICBleHRyYSBmaWVsZCBsZW5ndGggICAgICAgICAgICAgIDIgYnl0ZXNcclxuXHJcbiAgICAgIGZpbGUgbmFtZSAodmFyaWFibGUgc2l6ZSlcclxuICAgICAgZXh0cmEgZmllbGQgKHZhcmlhYmxlIHNpemUpXHJcbiAqL1xyXG5leHBvcnQgY2xhc3MgRmlsZUhlYWRlciB7XHJcbiAgY29uc3RydWN0b3IocHJpdmF0ZSBkYXRhOiBOb2RlQnVmZmVyKSB7XHJcbiAgICBpZiAoZGF0YS5yZWFkVUludDMyTEUoMCkgIT09IDB4MDQwMzRiNTApIHtcclxuICAgICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FSU5WQUwsIFwiSW52YWxpZCBaaXAgZmlsZTogTG9jYWwgZmlsZSBoZWFkZXIgaGFzIGludmFsaWQgc2lnbmF0dXJlOiBcIiArIHRoaXMuZGF0YS5yZWFkVUludDMyTEUoMCkpO1xyXG4gICAgfVxyXG4gIH1cclxuICBwdWJsaWMgdmVyc2lvbk5lZWRlZCgpOiBudW1iZXIgeyByZXR1cm4gdGhpcy5kYXRhLnJlYWRVSW50MTZMRSg0KTsgfVxyXG4gIHB1YmxpYyBmbGFncygpOiBudW1iZXIgeyByZXR1cm4gdGhpcy5kYXRhLnJlYWRVSW50MTZMRSg2KTsgfVxyXG4gIHB1YmxpYyBjb21wcmVzc2lvbk1ldGhvZCgpOiBDb21wcmVzc2lvbk1ldGhvZCB7IHJldHVybiB0aGlzLmRhdGEucmVhZFVJbnQxNkxFKDgpOyB9XHJcbiAgcHVibGljIGxhc3RNb2RGaWxlVGltZSgpOiBEYXRlIHtcclxuICAgIC8vIFRpbWUgYW5kIGRhdGUgaXMgaW4gTVMtRE9TIGZvcm1hdC5cclxuICAgIHJldHVybiBtc2RvczJkYXRlKHRoaXMuZGF0YS5yZWFkVUludDE2TEUoMTApLCB0aGlzLmRhdGEucmVhZFVJbnQxNkxFKDEyKSk7XHJcbiAgfVxyXG4gIHB1YmxpYyByYXdMYXN0TW9kRmlsZVRpbWUoKTogbnVtYmVyIHtcclxuICAgIHJldHVybiB0aGlzLmRhdGEucmVhZFVJbnQzMkxFKDEwKTtcclxuICB9XHJcbiAgcHVibGljIGNyYzMyKCk6IG51bWJlciB7IHJldHVybiB0aGlzLmRhdGEucmVhZFVJbnQzMkxFKDE0KTsgfVxyXG4gIC8qKlxyXG4gICAqIFRoZXNlIHR3byB2YWx1ZXMgYXJlIENPTVBMRVRFTFkgVVNFTEVTUy5cclxuICAgKlxyXG4gICAqIFNlY3Rpb24gNC40Ljk6XHJcbiAgICogICBJZiBiaXQgMyBvZiB0aGUgZ2VuZXJhbCBwdXJwb3NlIGJpdCBmbGFnIGlzIHNldCxcclxuICAgKiAgIHRoZXNlIGZpZWxkcyBhcmUgc2V0IHRvIHplcm8gaW4gdGhlIGxvY2FsIGhlYWRlciBhbmQgdGhlXHJcbiAgICogICBjb3JyZWN0IHZhbHVlcyBhcmUgcHV0IGluIHRoZSBkYXRhIGRlc2NyaXB0b3IgYW5kXHJcbiAgICogICBpbiB0aGUgY2VudHJhbCBkaXJlY3RvcnkuXHJcbiAgICpcclxuICAgKiBTbyB3ZSdsbCBqdXN0IHVzZSB0aGUgY2VudHJhbCBkaXJlY3RvcnkncyB2YWx1ZXMuXHJcbiAgICovXHJcbiAgLy8gcHVibGljIGNvbXByZXNzZWRTaXplKCk6IG51bWJlciB7IHJldHVybiB0aGlzLmRhdGEucmVhZFVJbnQzMkxFKDE4KTsgfVxyXG4gIC8vIHB1YmxpYyB1bmNvbXByZXNzZWRTaXplKCk6IG51bWJlciB7IHJldHVybiB0aGlzLmRhdGEucmVhZFVJbnQzMkxFKDIyKTsgfVxyXG4gIHB1YmxpYyBmaWxlTmFtZUxlbmd0aCgpOiBudW1iZXIgeyByZXR1cm4gdGhpcy5kYXRhLnJlYWRVSW50MTZMRSgyNik7IH1cclxuICBwdWJsaWMgZXh0cmFGaWVsZExlbmd0aCgpOiBudW1iZXIgeyByZXR1cm4gdGhpcy5kYXRhLnJlYWRVSW50MTZMRSgyOCk7IH1cclxuICBwdWJsaWMgZmlsZU5hbWUoKTogc3RyaW5nIHtcclxuICAgIHJldHVybiBzYWZlVG9TdHJpbmcodGhpcy5kYXRhLCB0aGlzLnVzZVVURjgoKSwgMzAsIHRoaXMuZmlsZU5hbWVMZW5ndGgoKSk7XHJcbiAgfVxyXG4gIHB1YmxpYyBleHRyYUZpZWxkKCk6IE5vZGVCdWZmZXIge1xyXG4gICAgdmFyIHN0YXJ0ID0gMzAgKyB0aGlzLmZpbGVOYW1lTGVuZ3RoKCk7XHJcbiAgICByZXR1cm4gdGhpcy5kYXRhLnNsaWNlKHN0YXJ0LCBzdGFydCArIHRoaXMuZXh0cmFGaWVsZExlbmd0aCgpKTtcclxuICB9XHJcbiAgcHVibGljIHRvdGFsU2l6ZSgpOiBudW1iZXIgeyByZXR1cm4gMzAgKyB0aGlzLmZpbGVOYW1lTGVuZ3RoKCkgKyB0aGlzLmV4dHJhRmllbGRMZW5ndGgoKTsgfVxyXG4gIHB1YmxpYyB1c2VVVEY4KCk6IGJvb2xlYW4geyByZXR1cm4gKHRoaXMuZmxhZ3MoKSAmIDB4ODAwKSA9PT0gMHg4MDA7IH1cclxufVxyXG5cclxuLyoqXHJcbiAgNC4zLjggIEZpbGUgZGF0YVxyXG5cclxuICAgIEltbWVkaWF0ZWx5IGZvbGxvd2luZyB0aGUgbG9jYWwgaGVhZGVyIGZvciBhIGZpbGVcclxuICAgIFNIT1VMRCBiZSBwbGFjZWQgdGhlIGNvbXByZXNzZWQgb3Igc3RvcmVkIGRhdGEgZm9yIHRoZSBmaWxlLlxyXG4gICAgSWYgdGhlIGZpbGUgaXMgZW5jcnlwdGVkLCB0aGUgZW5jcnlwdGlvbiBoZWFkZXIgZm9yIHRoZSBmaWxlXHJcbiAgICBTSE9VTEQgYmUgcGxhY2VkIGFmdGVyIHRoZSBsb2NhbCBoZWFkZXIgYW5kIGJlZm9yZSB0aGUgZmlsZVxyXG4gICAgZGF0YS4gVGhlIHNlcmllcyBvZiBbbG9jYWwgZmlsZSBoZWFkZXJdW2VuY3J5cHRpb24gaGVhZGVyXVxyXG4gICAgW2ZpbGUgZGF0YV1bZGF0YSBkZXNjcmlwdG9yXSByZXBlYXRzIGZvciBlYWNoIGZpbGUgaW4gdGhlXHJcbiAgICAuWklQIGFyY2hpdmUuXHJcblxyXG4gICAgWmVyby1ieXRlIGZpbGVzLCBkaXJlY3RvcmllcywgYW5kIG90aGVyIGZpbGUgdHlwZXMgdGhhdFxyXG4gICAgY29udGFpbiBubyBjb250ZW50IE1VU1Qgbm90IGluY2x1ZGUgZmlsZSBkYXRhLlxyXG4qL1xyXG5leHBvcnQgY2xhc3MgRmlsZURhdGEge1xyXG4gIGNvbnN0cnVjdG9yKHByaXZhdGUgaGVhZGVyOiBGaWxlSGVhZGVyLCBwcml2YXRlIHJlY29yZDogQ2VudHJhbERpcmVjdG9yeSwgcHJpdmF0ZSBkYXRhOiBOb2RlQnVmZmVyKSB7fVxyXG4gIHB1YmxpYyBkZWNvbXByZXNzKCk6IE5vZGVCdWZmZXIge1xyXG4gICAgLy8gQ2hlY2sgdGhlIGNvbXByZXNzaW9uXHJcbiAgICB2YXIgY29tcHJlc3Npb25NZXRob2Q6IENvbXByZXNzaW9uTWV0aG9kID0gdGhpcy5oZWFkZXIuY29tcHJlc3Npb25NZXRob2QoKTtcclxuICAgIHN3aXRjaCAoY29tcHJlc3Npb25NZXRob2QpIHtcclxuICAgICAgY2FzZSBDb21wcmVzc2lvbk1ldGhvZC5ERUZMQVRFOlxyXG4gICAgICAgIHZhciBkYXRhID0gaW5mbGF0ZVJhdyhcclxuICAgICAgICAgIGJ1ZmZlcjJBcnJheWlzaCh0aGlzLmRhdGEuc2xpY2UoMCwgdGhpcy5yZWNvcmQuY29tcHJlc3NlZFNpemUoKSkpLFxyXG4gICAgICAgICAgeyBjaHVua1NpemU6IHRoaXMucmVjb3JkLnVuY29tcHJlc3NlZFNpemUoKSB9XHJcbiAgICAgICAgKTtcclxuICAgICAgICByZXR1cm4gYXJyYXlpc2gyQnVmZmVyKGRhdGEpO1xyXG4gICAgICBjYXNlIENvbXByZXNzaW9uTWV0aG9kLlNUT1JFRDpcclxuICAgICAgICAvLyBHcmFiIGFuZCBjb3B5LlxyXG4gICAgICAgIHJldHVybiBjb3B5aW5nU2xpY2UodGhpcy5kYXRhLCAwLCB0aGlzLnJlY29yZC51bmNvbXByZXNzZWRTaXplKCkpO1xyXG4gICAgICBkZWZhdWx0OlxyXG4gICAgICAgIHZhciBuYW1lOiBzdHJpbmcgPSBDb21wcmVzc2lvbk1ldGhvZFtjb21wcmVzc2lvbk1ldGhvZF07XHJcbiAgICAgICAgbmFtZSA9IG5hbWUgPyBuYW1lIDogXCJVbmtub3duOiBcIiArIGNvbXByZXNzaW9uTWV0aG9kO1xyXG4gICAgICAgIHRocm93IG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRUlOVkFMLCBcIkludmFsaWQgY29tcHJlc3Npb24gbWV0aG9kIG9uIGZpbGUgJ1wiICsgdGhpcy5oZWFkZXIuZmlsZU5hbWUoKSArIFwiJzogXCIgKyBuYW1lKTtcclxuICAgIH1cclxuICB9XHJcbiAgcHVibGljIGdldEhlYWRlcigpOiBGaWxlSGVhZGVyIHtcclxuICAgIHJldHVybiB0aGlzLmhlYWRlcjtcclxuICB9XHJcbiAgcHVibGljIGdldFJlY29yZCgpOiBDZW50cmFsRGlyZWN0b3J5IHtcclxuICAgIHJldHVybiB0aGlzLnJlY29yZDtcclxuICB9XHJcbiAgcHVibGljIGdldFJhd0RhdGEoKTogTm9kZUJ1ZmZlciB7XHJcbiAgICByZXR1cm4gdGhpcy5kYXRhO1xyXG4gIH1cclxufVxyXG5cclxuLypcclxuIDQuMy45ICBEYXRhIGRlc2NyaXB0b3I6XHJcblxyXG4gICAgICBjcmMtMzIgICAgICAgICAgICAgICAgICAgICAgICAgIDQgYnl0ZXNcclxuICAgICAgY29tcHJlc3NlZCBzaXplICAgICAgICAgICAgICAgICA0IGJ5dGVzXHJcbiAgICAgIHVuY29tcHJlc3NlZCBzaXplICAgICAgICAgICAgICAgNCBieXRlc1xyXG4gKi9cclxuZXhwb3J0IGNsYXNzIERhdGFEZXNjcmlwdG9yIHtcclxuICBjb25zdHJ1Y3Rvcihwcml2YXRlIGRhdGE6IE5vZGVCdWZmZXIpIHt9XHJcbiAgcHVibGljIGNyYzMyKCk6IG51bWJlciB7IHJldHVybiB0aGlzLmRhdGEucmVhZFVJbnQzMkxFKDApOyB9XHJcbiAgcHVibGljIGNvbXByZXNzZWRTaXplKCk6IG51bWJlciB7IHJldHVybiB0aGlzLmRhdGEucmVhZFVJbnQzMkxFKDQpOyB9XHJcbiAgcHVibGljIHVuY29tcHJlc3NlZFNpemUoKTogbnVtYmVyIHsgcmV0dXJuIHRoaXMuZGF0YS5yZWFkVUludDMyTEUoOCk7IH1cclxufVxyXG5cclxuLypcclxuYCA0LjMuMTAgIEFyY2hpdmUgZGVjcnlwdGlvbiBoZWFkZXI6XHJcblxyXG4gICAgICA0LjMuMTAuMSBUaGUgQXJjaGl2ZSBEZWNyeXB0aW9uIEhlYWRlciBpcyBpbnRyb2R1Y2VkIGluIHZlcnNpb24gNi4yXHJcbiAgICAgIG9mIHRoZSBaSVAgZm9ybWF0IHNwZWNpZmljYXRpb24uICBUaGlzIHJlY29yZCBleGlzdHMgaW4gc3VwcG9ydFxyXG4gICAgICBvZiB0aGUgQ2VudHJhbCBEaXJlY3RvcnkgRW5jcnlwdGlvbiBGZWF0dXJlIGltcGxlbWVudGVkIGFzIHBhcnQgb2ZcclxuICAgICAgdGhlIFN0cm9uZyBFbmNyeXB0aW9uIFNwZWNpZmljYXRpb24gYXMgZGVzY3JpYmVkIGluIHRoaXMgZG9jdW1lbnQuXHJcbiAgICAgIFdoZW4gdGhlIENlbnRyYWwgRGlyZWN0b3J5IFN0cnVjdHVyZSBpcyBlbmNyeXB0ZWQsIHRoaXMgZGVjcnlwdGlvblxyXG4gICAgICBoZWFkZXIgTVVTVCBwcmVjZWRlIHRoZSBlbmNyeXB0ZWQgZGF0YSBzZWdtZW50LlxyXG4gKi9cclxuLypcclxuICA0LjMuMTEgIEFyY2hpdmUgZXh0cmEgZGF0YSByZWNvcmQ6XHJcblxyXG4gICAgICAgIGFyY2hpdmUgZXh0cmEgZGF0YSBzaWduYXR1cmUgICAgNCBieXRlcyAgKDB4MDgwNjRiNTApXHJcbiAgICAgICAgZXh0cmEgZmllbGQgbGVuZ3RoICAgICAgICAgICAgICA0IGJ5dGVzXHJcbiAgICAgICAgZXh0cmEgZmllbGQgZGF0YSAgICAgICAgICAgICAgICAodmFyaWFibGUgc2l6ZSlcclxuXHJcbiAgICAgIDQuMy4xMS4xIFRoZSBBcmNoaXZlIEV4dHJhIERhdGEgUmVjb3JkIGlzIGludHJvZHVjZWQgaW4gdmVyc2lvbiA2LjJcclxuICAgICAgb2YgdGhlIFpJUCBmb3JtYXQgc3BlY2lmaWNhdGlvbi4gIFRoaXMgcmVjb3JkIE1BWSBiZSB1c2VkIGluIHN1cHBvcnRcclxuICAgICAgb2YgdGhlIENlbnRyYWwgRGlyZWN0b3J5IEVuY3J5cHRpb24gRmVhdHVyZSBpbXBsZW1lbnRlZCBhcyBwYXJ0IG9mXHJcbiAgICAgIHRoZSBTdHJvbmcgRW5jcnlwdGlvbiBTcGVjaWZpY2F0aW9uIGFzIGRlc2NyaWJlZCBpbiB0aGlzIGRvY3VtZW50LlxyXG4gICAgICBXaGVuIHByZXNlbnQsIHRoaXMgcmVjb3JkIE1VU1QgaW1tZWRpYXRlbHkgcHJlY2VkZSB0aGUgY2VudHJhbFxyXG4gICAgICBkaXJlY3RvcnkgZGF0YSBzdHJ1Y3R1cmUuXHJcbiovXHJcbmV4cG9ydCBjbGFzcyBBcmNoaXZlRXh0cmFEYXRhUmVjb3JkIHtcclxuICBjb25zdHJ1Y3Rvcihwcml2YXRlIGRhdGE6IE5vZGVCdWZmZXIpIHtcclxuICAgIGlmICh0aGlzLmRhdGEucmVhZFVJbnQzMkxFKDApICE9PSAweDA4MDY0YjUwKSB7XHJcbiAgICAgIHRocm93IG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRUlOVkFMLCBcIkludmFsaWQgYXJjaGl2ZSBleHRyYSBkYXRhIHJlY29yZCBzaWduYXR1cmU6IFwiICsgdGhpcy5kYXRhLnJlYWRVSW50MzJMRSgwKSk7XHJcbiAgICB9XHJcbiAgfVxyXG4gIHB1YmxpYyBsZW5ndGgoKTogbnVtYmVyIHsgcmV0dXJuIHRoaXMuZGF0YS5yZWFkVUludDMyTEUoNCk7IH1cclxuICBwdWJsaWMgZXh0cmFGaWVsZERhdGEoKTogTm9kZUJ1ZmZlciB7IHJldHVybiB0aGlzLmRhdGEuc2xpY2UoOCwgOCArIHRoaXMubGVuZ3RoKCkpOyB9XHJcbn1cclxuXHJcbi8qXHJcbiAgNC4zLjEzIERpZ2l0YWwgc2lnbmF0dXJlOlxyXG5cclxuICAgICAgICBoZWFkZXIgc2lnbmF0dXJlICAgICAgICAgICAgICAgIDQgYnl0ZXMgICgweDA1MDU0YjUwKVxyXG4gICAgICAgIHNpemUgb2YgZGF0YSAgICAgICAgICAgICAgICAgICAgMiBieXRlc1xyXG4gICAgICAgIHNpZ25hdHVyZSBkYXRhICh2YXJpYWJsZSBzaXplKVxyXG5cclxuICAgICAgV2l0aCB0aGUgaW50cm9kdWN0aW9uIG9mIHRoZSBDZW50cmFsIERpcmVjdG9yeSBFbmNyeXB0aW9uXHJcbiAgICAgIGZlYXR1cmUgaW4gdmVyc2lvbiA2LjIgb2YgdGhpcyBzcGVjaWZpY2F0aW9uLCB0aGUgQ2VudHJhbFxyXG4gICAgICBEaXJlY3RvcnkgU3RydWN0dXJlIE1BWSBiZSBzdG9yZWQgYm90aCBjb21wcmVzc2VkIGFuZCBlbmNyeXB0ZWQuXHJcbiAgICAgIEFsdGhvdWdoIG5vdCByZXF1aXJlZCwgaXQgaXMgYXNzdW1lZCB3aGVuIGVuY3J5cHRpbmcgdGhlXHJcbiAgICAgIENlbnRyYWwgRGlyZWN0b3J5IFN0cnVjdHVyZSwgdGhhdCBpdCB3aWxsIGJlIGNvbXByZXNzZWRcclxuICAgICAgZm9yIGdyZWF0ZXIgc3RvcmFnZSBlZmZpY2llbmN5LiAgSW5mb3JtYXRpb24gb24gdGhlXHJcbiAgICAgIENlbnRyYWwgRGlyZWN0b3J5IEVuY3J5cHRpb24gZmVhdHVyZSBjYW4gYmUgZm91bmQgaW4gdGhlIHNlY3Rpb25cclxuICAgICAgZGVzY3JpYmluZyB0aGUgU3Ryb25nIEVuY3J5cHRpb24gU3BlY2lmaWNhdGlvbi4gVGhlIERpZ2l0YWxcclxuICAgICAgU2lnbmF0dXJlIHJlY29yZCB3aWxsIGJlIG5laXRoZXIgY29tcHJlc3NlZCBub3IgZW5jcnlwdGVkLlxyXG4qL1xyXG5leHBvcnQgY2xhc3MgRGlnaXRhbFNpZ25hdHVyZSB7XHJcbiAgY29uc3RydWN0b3IocHJpdmF0ZSBkYXRhOiBOb2RlQnVmZmVyKSB7XHJcbiAgICBpZiAodGhpcy5kYXRhLnJlYWRVSW50MzJMRSgwKSAhPT0gMHgwNTA1NGI1MCkge1xyXG4gICAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVJTlZBTCwgXCJJbnZhbGlkIGRpZ2l0YWwgc2lnbmF0dXJlIHNpZ25hdHVyZTogXCIgKyB0aGlzLmRhdGEucmVhZFVJbnQzMkxFKDApKTtcclxuICAgIH1cclxuICB9XHJcbiAgcHVibGljIHNpemUoKTogbnVtYmVyIHsgcmV0dXJuIHRoaXMuZGF0YS5yZWFkVUludDE2TEUoNCk7IH1cclxuICBwdWJsaWMgc2lnbmF0dXJlRGF0YSgpOiBOb2RlQnVmZmVyIHsgcmV0dXJuIHRoaXMuZGF0YS5zbGljZSg2LCA2ICsgdGhpcy5zaXplKCkpOyB9XHJcbn1cclxuXHJcbi8qXHJcbiAgNC4zLjEyICBDZW50cmFsIGRpcmVjdG9yeSBzdHJ1Y3R1cmU6XHJcblxyXG4gICAgY2VudHJhbCBmaWxlIGhlYWRlciBzaWduYXR1cmUgICA0IGJ5dGVzICAoMHgwMjAxNGI1MClcclxuICAgIHZlcnNpb24gbWFkZSBieSAgICAgICAgICAgICAgICAgMiBieXRlc1xyXG4gICAgdmVyc2lvbiBuZWVkZWQgdG8gZXh0cmFjdCAgICAgICAyIGJ5dGVzXHJcbiAgICBnZW5lcmFsIHB1cnBvc2UgYml0IGZsYWcgICAgICAgIDIgYnl0ZXNcclxuICAgIGNvbXByZXNzaW9uIG1ldGhvZCAgICAgICAgICAgICAgMiBieXRlc1xyXG4gICAgbGFzdCBtb2QgZmlsZSB0aW1lICAgICAgICAgICAgICAyIGJ5dGVzXHJcbiAgICBsYXN0IG1vZCBmaWxlIGRhdGUgICAgICAgICAgICAgIDIgYnl0ZXNcclxuICAgIGNyYy0zMiAgICAgICAgICAgICAgICAgICAgICAgICAgNCBieXRlc1xyXG4gICAgY29tcHJlc3NlZCBzaXplICAgICAgICAgICAgICAgICA0IGJ5dGVzXHJcbiAgICB1bmNvbXByZXNzZWQgc2l6ZSAgICAgICAgICAgICAgIDQgYnl0ZXNcclxuICAgIGZpbGUgbmFtZSBsZW5ndGggICAgICAgICAgICAgICAgMiBieXRlc1xyXG4gICAgZXh0cmEgZmllbGQgbGVuZ3RoICAgICAgICAgICAgICAyIGJ5dGVzXHJcbiAgICBmaWxlIGNvbW1lbnQgbGVuZ3RoICAgICAgICAgICAgIDIgYnl0ZXNcclxuICAgIGRpc2sgbnVtYmVyIHN0YXJ0ICAgICAgICAgICAgICAgMiBieXRlc1xyXG4gICAgaW50ZXJuYWwgZmlsZSBhdHRyaWJ1dGVzICAgICAgICAyIGJ5dGVzXHJcbiAgICBleHRlcm5hbCBmaWxlIGF0dHJpYnV0ZXMgICAgICAgIDQgYnl0ZXNcclxuICAgIHJlbGF0aXZlIG9mZnNldCBvZiBsb2NhbCBoZWFkZXIgNCBieXRlc1xyXG5cclxuICAgIGZpbGUgbmFtZSAodmFyaWFibGUgc2l6ZSlcclxuICAgIGV4dHJhIGZpZWxkICh2YXJpYWJsZSBzaXplKVxyXG4gICAgZmlsZSBjb21tZW50ICh2YXJpYWJsZSBzaXplKVxyXG4gKi9cclxuZXhwb3J0IGNsYXNzIENlbnRyYWxEaXJlY3Rvcnkge1xyXG4gIC8vIE9wdGltaXphdGlvbjogVGhlIGZpbGVuYW1lIGlzIGZyZXF1ZW50bHkgcmVhZCwgc28gc3Rhc2ggaXQgaGVyZS5cclxuICBwcml2YXRlIF9maWxlbmFtZTogc3RyaW5nO1xyXG4gIGNvbnN0cnVjdG9yKHByaXZhdGUgemlwRGF0YTogTm9kZUJ1ZmZlciwgcHJpdmF0ZSBkYXRhOiBOb2RlQnVmZmVyKSB7XHJcbiAgICAvLyBTYW5pdHkgY2hlY2suXHJcbiAgICBpZiAodGhpcy5kYXRhLnJlYWRVSW50MzJMRSgwKSAhPT0gMHgwMjAxNGI1MClcclxuICAgICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FSU5WQUwsIFwiSW52YWxpZCBaaXAgZmlsZTogQ2VudHJhbCBkaXJlY3RvcnkgcmVjb3JkIGhhcyBpbnZhbGlkIHNpZ25hdHVyZTogXCIgKyB0aGlzLmRhdGEucmVhZFVJbnQzMkxFKDApKTtcclxuICAgIHRoaXMuX2ZpbGVuYW1lID0gdGhpcy5wcm9kdWNlRmlsZW5hbWUoKTtcclxuICB9XHJcbiAgcHVibGljIHZlcnNpb25NYWRlQnkoKTogbnVtYmVyIHsgcmV0dXJuIHRoaXMuZGF0YS5yZWFkVUludDE2TEUoNCk7IH1cclxuICBwdWJsaWMgdmVyc2lvbk5lZWRlZCgpOiBudW1iZXIgeyByZXR1cm4gdGhpcy5kYXRhLnJlYWRVSW50MTZMRSg2KTsgfVxyXG4gIHB1YmxpYyBmbGFnKCk6IG51bWJlciB7IHJldHVybiB0aGlzLmRhdGEucmVhZFVJbnQxNkxFKDgpOyB9XHJcbiAgcHVibGljIGNvbXByZXNzaW9uTWV0aG9kKCk6IENvbXByZXNzaW9uTWV0aG9kIHsgcmV0dXJuIHRoaXMuZGF0YS5yZWFkVUludDE2TEUoMTApOyB9XHJcbiAgcHVibGljIGxhc3RNb2RGaWxlVGltZSgpOiBEYXRlIHtcclxuICAgIC8vIFRpbWUgYW5kIGRhdGUgaXMgaW4gTVMtRE9TIGZvcm1hdC5cclxuICAgIHJldHVybiBtc2RvczJkYXRlKHRoaXMuZGF0YS5yZWFkVUludDE2TEUoMTIpLCB0aGlzLmRhdGEucmVhZFVJbnQxNkxFKDE0KSk7XHJcbiAgfVxyXG4gIHB1YmxpYyByYXdMYXN0TW9kRmlsZVRpbWUoKTogbnVtYmVyIHtcclxuICAgIHJldHVybiB0aGlzLmRhdGEucmVhZFVJbnQzMkxFKDEyKTtcclxuICB9XHJcbiAgcHVibGljIGNyYzMyKCk6IG51bWJlciB7IHJldHVybiB0aGlzLmRhdGEucmVhZFVJbnQzMkxFKDE2KTsgfVxyXG4gIHB1YmxpYyBjb21wcmVzc2VkU2l6ZSgpOiBudW1iZXIgeyByZXR1cm4gdGhpcy5kYXRhLnJlYWRVSW50MzJMRSgyMCk7IH1cclxuICBwdWJsaWMgdW5jb21wcmVzc2VkU2l6ZSgpOiBudW1iZXIgeyByZXR1cm4gdGhpcy5kYXRhLnJlYWRVSW50MzJMRSgyNCk7IH1cclxuICBwdWJsaWMgZmlsZU5hbWVMZW5ndGgoKTogbnVtYmVyIHsgcmV0dXJuIHRoaXMuZGF0YS5yZWFkVUludDE2TEUoMjgpOyB9XHJcbiAgcHVibGljIGV4dHJhRmllbGRMZW5ndGgoKTogbnVtYmVyIHsgcmV0dXJuIHRoaXMuZGF0YS5yZWFkVUludDE2TEUoMzApOyB9XHJcbiAgcHVibGljIGZpbGVDb21tZW50TGVuZ3RoKCk6IG51bWJlciB7IHJldHVybiB0aGlzLmRhdGEucmVhZFVJbnQxNkxFKDMyKTsgfVxyXG4gIHB1YmxpYyBkaXNrTnVtYmVyU3RhcnQoKTogbnVtYmVyIHsgcmV0dXJuIHRoaXMuZGF0YS5yZWFkVUludDE2TEUoMzQpOyB9XHJcbiAgcHVibGljIGludGVybmFsQXR0cmlidXRlcygpOiBudW1iZXIgeyByZXR1cm4gdGhpcy5kYXRhLnJlYWRVSW50MTZMRSgzNik7IH1cclxuICBwdWJsaWMgZXh0ZXJuYWxBdHRyaWJ1dGVzKCk6IG51bWJlciB7IHJldHVybiB0aGlzLmRhdGEucmVhZFVJbnQzMkxFKDM4KTsgfVxyXG4gIHB1YmxpYyBoZWFkZXJSZWxhdGl2ZU9mZnNldCgpOiBudW1iZXIgeyByZXR1cm4gdGhpcy5kYXRhLnJlYWRVSW50MzJMRSg0Mik7IH1cclxuICBwdWJsaWMgcHJvZHVjZUZpbGVuYW1lKCk6IHN0cmluZyB7XHJcbiAgICAvKlxyXG4gICAgICA0LjQuMTcuMSBjbGFpbXM6XHJcbiAgICAgICogQWxsIHNsYXNoZXMgYXJlIGZvcndhcmQgKCcvJykgc2xhc2hlcy5cclxuICAgICAgKiBGaWxlbmFtZSBkb2Vzbid0IGJlZ2luIHdpdGggYSBzbGFzaC5cclxuICAgICAgKiBObyBkcml2ZSBsZXR0ZXJzIG9yIGFueSBub25zZW5zZSBsaWtlIHRoYXQuXHJcbiAgICAgICogSWYgZmlsZW5hbWUgaXMgbWlzc2luZywgdGhlIGlucHV0IGNhbWUgZnJvbSBzdGFuZGFyZCBpbnB1dC5cclxuXHJcbiAgICAgIFVuZm9ydHVuYXRlbHksIHRoaXMgaXNuJ3QgdHJ1ZSBpbiBwcmFjdGljZS4gU29tZSBXaW5kb3dzIHppcCB1dGlsaXRpZXMgdXNlXHJcbiAgICAgIGEgYmFja3NsYXNoIGhlcmUsIGJ1dCB0aGUgY29ycmVjdCBVbml4LXN0eWxlIHBhdGggaW4gZmlsZSBoZWFkZXJzLlxyXG5cclxuICAgICAgVG8gYXZvaWQgc2Vla2luZyBhbGwgb3ZlciB0aGUgZmlsZSB0byByZWNvdmVyIHRoZSBrbm93bi1nb29kIGZpbGVuYW1lc1xyXG4gICAgICBmcm9tIGZpbGUgaGVhZGVycywgd2Ugc2ltcGx5IGNvbnZlcnQgJy8nIHRvICdcXCcgaGVyZS5cclxuICAgICovXHJcbiAgICB2YXIgZmlsZU5hbWU6IHN0cmluZyA9IHNhZmVUb1N0cmluZyh0aGlzLmRhdGEsIHRoaXMudXNlVVRGOCgpLCA0NiwgdGhpcy5maWxlTmFtZUxlbmd0aCgpKTtcclxuICAgIHJldHVybiBmaWxlTmFtZS5yZXBsYWNlKC9cXFxcL2csIFwiL1wiKTtcclxuICB9XHJcbiAgcHVibGljIGZpbGVOYW1lKCk6IHN0cmluZyB7XHJcbiAgICByZXR1cm4gdGhpcy5fZmlsZW5hbWU7XHJcbiAgfVxyXG4gIHB1YmxpYyByYXdGaWxlTmFtZSgpOiBOb2RlQnVmZmVyIHtcclxuICAgIHJldHVybiB0aGlzLmRhdGEuc2xpY2UoNDYsIDQ2ICsgdGhpcy5maWxlTmFtZUxlbmd0aCgpKTtcclxuICB9XHJcbiAgcHVibGljIGV4dHJhRmllbGQoKTogTm9kZUJ1ZmZlciB7XHJcbiAgICB2YXIgc3RhcnQgPSA0NCArIHRoaXMuZmlsZU5hbWVMZW5ndGgoKTtcclxuICAgIHJldHVybiB0aGlzLmRhdGEuc2xpY2Uoc3RhcnQsIHN0YXJ0ICsgdGhpcy5leHRyYUZpZWxkTGVuZ3RoKCkpO1xyXG4gIH1cclxuICBwdWJsaWMgZmlsZUNvbW1lbnQoKTogc3RyaW5nIHtcclxuICAgIHZhciBzdGFydCA9IDQ2ICsgdGhpcy5maWxlTmFtZUxlbmd0aCgpICsgdGhpcy5leHRyYUZpZWxkTGVuZ3RoKCk7XHJcbiAgICByZXR1cm4gc2FmZVRvU3RyaW5nKHRoaXMuZGF0YSwgdGhpcy51c2VVVEY4KCksIHN0YXJ0LCB0aGlzLmZpbGVDb21tZW50TGVuZ3RoKCkpO1xyXG4gIH1cclxuICBwdWJsaWMgcmF3RmlsZUNvbW1lbnQoKTogTm9kZUJ1ZmZlciB7XHJcbiAgICBsZXQgc3RhcnQgPSA0NiArIHRoaXMuZmlsZU5hbWVMZW5ndGgoKSArIHRoaXMuZXh0cmFGaWVsZExlbmd0aCgpO1xyXG4gICAgcmV0dXJuIHRoaXMuZGF0YS5zbGljZShzdGFydCwgc3RhcnQgKyB0aGlzLmZpbGVDb21tZW50TGVuZ3RoKCkpO1xyXG4gIH1cclxuICBwdWJsaWMgdG90YWxTaXplKCk6IG51bWJlciB7XHJcbiAgICByZXR1cm4gNDYgKyB0aGlzLmZpbGVOYW1lTGVuZ3RoKCkgKyB0aGlzLmV4dHJhRmllbGRMZW5ndGgoKSArIHRoaXMuZmlsZUNvbW1lbnRMZW5ndGgoKTtcclxuICB9XHJcbiAgcHVibGljIGlzRGlyZWN0b3J5KCk6IGJvb2xlYW4ge1xyXG4gICAgLy8gTk9URTogVGhpcyBhc3N1bWVzIHRoYXQgdGhlIHppcCBmaWxlIGltcGxlbWVudGF0aW9uIHVzZXMgdGhlIGxvd2VyIGJ5dGVcclxuICAgIC8vICAgICAgIG9mIGV4dGVybmFsIGF0dHJpYnV0ZXMgZm9yIERPUyBhdHRyaWJ1dGVzIGZvclxyXG4gICAgLy8gICAgICAgYmFja3dhcmRzLWNvbXBhdGliaWxpdHkuIFRoaXMgaXMgbm90IG1hbmRhdGVkLCBidXQgYXBwZWFycyB0byBiZVxyXG4gICAgLy8gICAgICAgY29tbW9ucGxhY2UuXHJcbiAgICAvLyAgICAgICBBY2NvcmRpbmcgdG8gdGhlIHNwZWMsIHRoZSBsYXlvdXQgb2YgZXh0ZXJuYWwgYXR0cmlidXRlcyBpc1xyXG4gICAgLy8gICAgICAgcGxhdGZvcm0tZGVwZW5kZW50LlxyXG4gICAgLy8gICAgICAgSWYgdGhhdCBmYWlscywgd2UgYWxzbyBjaGVjayBpZiB0aGUgbmFtZSBvZiB0aGUgZmlsZSBlbmRzIGluICcvJyxcclxuICAgIC8vICAgICAgIHdoaWNoIGlzIHdoYXQgSmF2YSdzIFppcEZpbGUgaW1wbGVtZW50YXRpb24gZG9lcy5cclxuICAgIHZhciBmaWxlTmFtZSA9IHRoaXMuZmlsZU5hbWUoKTtcclxuICAgIHJldHVybiAodGhpcy5leHRlcm5hbEF0dHJpYnV0ZXMoKSAmIDB4MTAgPyB0cnVlIDogZmFsc2UpIHx8IChmaWxlTmFtZS5jaGFyQXQoZmlsZU5hbWUubGVuZ3RoLTEpID09PSAnLycpO1xyXG4gIH1cclxuICBwdWJsaWMgaXNGaWxlKCk6IGJvb2xlYW4geyByZXR1cm4gIXRoaXMuaXNEaXJlY3RvcnkoKTsgfVxyXG4gIHB1YmxpYyB1c2VVVEY4KCk6IGJvb2xlYW4geyByZXR1cm4gKHRoaXMuZmxhZygpICYgMHg4MDApID09PSAweDgwMDsgfVxyXG4gIHB1YmxpYyBpc0VuY3J5cHRlZCgpOiBib29sZWFuIHsgcmV0dXJuICh0aGlzLmZsYWcoKSAmIDB4MSkgPT09IDB4MTsgfVxyXG4gIHB1YmxpYyBnZXRGaWxlRGF0YSgpOiBGaWxlRGF0YSB7XHJcbiAgICAvLyBOZWVkIHRvIGdyYWIgdGhlIGhlYWRlciBiZWZvcmUgd2UgY2FuIGZpZ3VyZSBvdXQgd2hlcmUgdGhlIGFjdHVhbFxyXG4gICAgLy8gY29tcHJlc3NlZCBkYXRhIHN0YXJ0cy5cclxuICAgIHZhciBzdGFydCA9IHRoaXMuaGVhZGVyUmVsYXRpdmVPZmZzZXQoKTtcclxuICAgIHZhciBoZWFkZXIgPSBuZXcgRmlsZUhlYWRlcih0aGlzLnppcERhdGEuc2xpY2Uoc3RhcnQpKTtcclxuICAgIHJldHVybiBuZXcgRmlsZURhdGEoaGVhZGVyLCB0aGlzLCB0aGlzLnppcERhdGEuc2xpY2Uoc3RhcnQgKyBoZWFkZXIudG90YWxTaXplKCkpKTtcclxuICB9XHJcbiAgcHVibGljIGdldERhdGEoKTogTm9kZUJ1ZmZlciB7XHJcbiAgICByZXR1cm4gdGhpcy5nZXRGaWxlRGF0YSgpLmRlY29tcHJlc3MoKTtcclxuICB9XHJcbiAgcHVibGljIGdldFJhd0RhdGEoKTogTm9kZUJ1ZmZlciB7XHJcbiAgICByZXR1cm4gdGhpcy5nZXRGaWxlRGF0YSgpLmdldFJhd0RhdGEoKTtcclxuICB9XHJcbiAgcHVibGljIGdldFN0YXRzKCk6IFN0YXRzIHtcclxuICAgIHJldHVybiBuZXcgU3RhdHMoRmlsZVR5cGUuRklMRSwgdGhpcy51bmNvbXByZXNzZWRTaXplKCksIDB4MTZELCBuZXcgRGF0ZSgpLCB0aGlzLmxhc3RNb2RGaWxlVGltZSgpKTtcclxuICB9XHJcbn1cclxuXHJcbi8qXHJcbiAgNC4zLjE2OiBlbmQgb2YgY2VudHJhbCBkaXJlY3RvcnkgcmVjb3JkXHJcbiAgICBlbmQgb2YgY2VudHJhbCBkaXIgc2lnbmF0dXJlICAgIDQgYnl0ZXMgICgweDA2MDU0YjUwKVxyXG4gICAgbnVtYmVyIG9mIHRoaXMgZGlzayAgICAgICAgICAgICAyIGJ5dGVzXHJcbiAgICBudW1iZXIgb2YgdGhlIGRpc2sgd2l0aCB0aGVcclxuICAgIHN0YXJ0IG9mIHRoZSBjZW50cmFsIGRpcmVjdG9yeSAgMiBieXRlc1xyXG4gICAgdG90YWwgbnVtYmVyIG9mIGVudHJpZXMgaW4gdGhlXHJcbiAgICBjZW50cmFsIGRpcmVjdG9yeSBvbiB0aGlzIGRpc2sgIDIgYnl0ZXNcclxuICAgIHRvdGFsIG51bWJlciBvZiBlbnRyaWVzIGluXHJcbiAgICB0aGUgY2VudHJhbCBkaXJlY3RvcnkgICAgICAgICAgIDIgYnl0ZXNcclxuICAgIHNpemUgb2YgdGhlIGNlbnRyYWwgZGlyZWN0b3J5ICAgNCBieXRlc1xyXG4gICAgb2Zmc2V0IG9mIHN0YXJ0IG9mIGNlbnRyYWxcclxuICAgIGRpcmVjdG9yeSB3aXRoIHJlc3BlY3QgdG9cclxuICAgIHRoZSBzdGFydGluZyBkaXNrIG51bWJlciAgICAgICAgNCBieXRlc1xyXG4gICAgLlpJUCBmaWxlIGNvbW1lbnQgbGVuZ3RoICAgICAgICAyIGJ5dGVzXHJcbiAgICAuWklQIGZpbGUgY29tbWVudCAgICAgICAodmFyaWFibGUgc2l6ZSlcclxuKi9cclxuZXhwb3J0IGNsYXNzIEVuZE9mQ2VudHJhbERpcmVjdG9yeSB7XHJcbiAgY29uc3RydWN0b3IocHJpdmF0ZSBkYXRhOiBOb2RlQnVmZmVyKSB7XHJcbiAgICBpZiAodGhpcy5kYXRhLnJlYWRVSW50MzJMRSgwKSAhPT0gMHgwNjA1NGI1MClcclxuICAgICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FSU5WQUwsIFwiSW52YWxpZCBaaXAgZmlsZTogRW5kIG9mIGNlbnRyYWwgZGlyZWN0b3J5IHJlY29yZCBoYXMgaW52YWxpZCBzaWduYXR1cmU6IFwiICsgdGhpcy5kYXRhLnJlYWRVSW50MzJMRSgwKSk7XHJcbiAgfVxyXG4gIHB1YmxpYyBkaXNrTnVtYmVyKCk6IG51bWJlciB7IHJldHVybiB0aGlzLmRhdGEucmVhZFVJbnQxNkxFKDQpOyB9XHJcbiAgcHVibGljIGNkRGlza051bWJlcigpOiBudW1iZXIgeyByZXR1cm4gdGhpcy5kYXRhLnJlYWRVSW50MTZMRSg2KTsgfVxyXG4gIHB1YmxpYyBjZERpc2tFbnRyeUNvdW50KCk6IG51bWJlciB7IHJldHVybiB0aGlzLmRhdGEucmVhZFVJbnQxNkxFKDgpOyB9XHJcbiAgcHVibGljIGNkVG90YWxFbnRyeUNvdW50KCk6IG51bWJlciB7IHJldHVybiB0aGlzLmRhdGEucmVhZFVJbnQxNkxFKDEwKTsgfVxyXG4gIHB1YmxpYyBjZFNpemUoKTogbnVtYmVyIHsgcmV0dXJuIHRoaXMuZGF0YS5yZWFkVUludDMyTEUoMTIpOyB9XHJcbiAgcHVibGljIGNkT2Zmc2V0KCk6IG51bWJlciB7IHJldHVybiB0aGlzLmRhdGEucmVhZFVJbnQzMkxFKDE2KTsgfVxyXG4gIHB1YmxpYyBjZFppcENvbW1lbnRMZW5ndGgoKTogbnVtYmVyIHsgcmV0dXJuIHRoaXMuZGF0YS5yZWFkVUludDE2TEUoMjApOyB9XHJcbiAgcHVibGljIGNkWmlwQ29tbWVudCgpOiBzdHJpbmcge1xyXG4gICAgLy8gQXNzdW1pbmcgVVRGLTguIFRoZSBzcGVjaWZpY2F0aW9uIGRvZXNuJ3Qgc3BlY2lmeS5cclxuICAgIHJldHVybiBzYWZlVG9TdHJpbmcodGhpcy5kYXRhLCB0cnVlLCAyMiwgdGhpcy5jZFppcENvbW1lbnRMZW5ndGgoKSk7XHJcbiAgfVxyXG4gIHB1YmxpYyByYXdDZFppcENvbW1lbnQoKTogTm9kZUJ1ZmZlciB7XHJcbiAgICByZXR1cm4gdGhpcy5kYXRhLnNsaWNlKDIyLCAyMiArIHRoaXMuY2RaaXBDb21tZW50TGVuZ3RoKCkpXHJcbiAgfVxyXG59XHJcblxyXG5leHBvcnQgY2xhc3MgWmlwVE9DIHtcclxuICBjb25zdHJ1Y3RvcihwdWJsaWMgaW5kZXg6IEZpbGVJbmRleDxDZW50cmFsRGlyZWN0b3J5PiwgcHVibGljIGRpcmVjdG9yeUVudHJpZXM6IENlbnRyYWxEaXJlY3RvcnlbXSwgcHVibGljIGVvY2Q6IEVuZE9mQ2VudHJhbERpcmVjdG9yeSwgcHVibGljIGRhdGE6IE5vZGVCdWZmZXIpIHtcclxuICB9XHJcbn1cclxuXHJcbmV4cG9ydCBkZWZhdWx0IGNsYXNzIFppcEZTIGV4dGVuZHMgZmlsZV9zeXN0ZW0uU3luY2hyb25vdXNGaWxlU3lzdGVtIGltcGxlbWVudHMgZmlsZV9zeXN0ZW0uRmlsZVN5c3RlbSB7XHJcbiAgcHJpdmF0ZSBfaW5kZXg6IEZpbGVJbmRleDxDZW50cmFsRGlyZWN0b3J5PiA9IG5ldyBGaWxlSW5kZXg8Q2VudHJhbERpcmVjdG9yeT4oKTtcclxuICBwcml2YXRlIF9kaXJlY3RvcnlFbnRyaWVzOiBDZW50cmFsRGlyZWN0b3J5W10gPSBbXTtcclxuICBwcml2YXRlIF9lb2NkOiBFbmRPZkNlbnRyYWxEaXJlY3RvcnkgPSBudWxsO1xyXG4gIHByaXZhdGUgZGF0YTogTm9kZUJ1ZmZlcjtcclxuXHJcbiAgLyoqXHJcbiAgICogQ29uc3RydWN0cyBhIFppcEZTIGZyb20gdGhlIGdpdmVuIHppcCBmaWxlIGRhdGEuIE5hbWUgaXMgb3B0aW9uYWwsIGFuZCBpc1xyXG4gICAqIHVzZWQgcHJpbWFyaWx5IGZvciBvdXIgdW5pdCB0ZXN0cycgcHVycG9zZXMgdG8gZGlmZmVyZW50aWF0ZSBkaWZmZXJlbnRcclxuICAgKiB0ZXN0IHppcCBmaWxlcyBpbiB0ZXN0IG91dHB1dC5cclxuICAgKi9cclxuICBjb25zdHJ1Y3Rvcihwcml2YXRlIGlucHV0OiBOb2RlQnVmZmVyIHwgWmlwVE9DLCBwcml2YXRlIG5hbWU6IHN0cmluZyA9ICcnKSB7XHJcbiAgICBzdXBlcigpO1xyXG4gICAgaWYgKGlucHV0IGluc3RhbmNlb2YgWmlwVE9DKSB7XHJcbiAgICAgIHRoaXMuX2luZGV4ID0gaW5wdXQuaW5kZXg7XHJcbiAgICAgIHRoaXMuX2RpcmVjdG9yeUVudHJpZXMgPSBpbnB1dC5kaXJlY3RvcnlFbnRyaWVzO1xyXG4gICAgICB0aGlzLl9lb2NkID0gaW5wdXQuZW9jZDtcclxuICAgICAgdGhpcy5kYXRhID0gaW5wdXQuZGF0YTtcclxuICAgIH0gZWxzZSB7XHJcbiAgICAgIHRoaXMuZGF0YSA9IGlucHV0IGFzIE5vZGVCdWZmZXI7XHJcbiAgICAgIHRoaXMucG9wdWxhdGVJbmRleCgpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgcHVibGljIGdldE5hbWUoKTogc3RyaW5nIHtcclxuICAgIHJldHVybiAnWmlwRlMnICsgKHRoaXMubmFtZSAhPT0gJycgPyAnICcgKyB0aGlzLm5hbWUgOiAnJyk7XHJcbiAgfVxyXG5cclxuICAvKipcclxuICAgKiBHZXQgdGhlIENlbnRyYWxEaXJlY3Rvcnkgb2JqZWN0IGZvciB0aGUgZ2l2ZW4gcGF0aC5cclxuICAgKi9cclxuICBwdWJsaWMgZ2V0Q2VudHJhbERpcmVjdG9yeUVudHJ5KHBhdGg6IHN0cmluZyk6IENlbnRyYWxEaXJlY3Rvcnkge1xyXG4gICAgbGV0IGlub2RlID0gdGhpcy5faW5kZXguZ2V0SW5vZGUocGF0aCk7XHJcbiAgICBpZiAoaW5vZGUgPT09IG51bGwpIHtcclxuICAgICAgdGhyb3cgQXBpRXJyb3IuRU5PRU5UKHBhdGgpO1xyXG4gICAgfVxyXG4gICAgaWYgKGlzRmlsZUlub2RlPENlbnRyYWxEaXJlY3Rvcnk+KGlub2RlKSkge1xyXG4gICAgICByZXR1cm4gaW5vZGUuZ2V0RGF0YSgpO1xyXG4gICAgfSBlbHNlIGlmIChpc0Rpcklub2RlPENlbnRyYWxEaXJlY3Rvcnk+KGlub2RlKSkge1xyXG4gICAgICByZXR1cm4gaW5vZGUuZ2V0RGF0YSgpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgcHVibGljIGdldENlbnRyYWxEaXJlY3RvcnlFbnRyeUF0KGluZGV4OiBudW1iZXIpOiBDZW50cmFsRGlyZWN0b3J5IHtcclxuICAgIGxldCBkaXJFbnRyeSA9IHRoaXMuX2RpcmVjdG9yeUVudHJpZXNbaW5kZXhdO1xyXG4gICAgaWYgKCFkaXJFbnRyeSkge1xyXG4gICAgICB0aHJvdyBuZXcgUmFuZ2VFcnJvcihgSW52YWxpZCBkaXJlY3RvcnkgaW5kZXg6ICR7aW5kZXh9LmApO1xyXG4gICAgfVxyXG4gICAgcmV0dXJuIGRpckVudHJ5O1xyXG4gIH1cclxuXHJcbiAgcHVibGljIGdldE51bWJlck9mQ2VudHJhbERpcmVjdG9yeUVudHJpZXMoKTogbnVtYmVyIHtcclxuICAgIHJldHVybiB0aGlzLl9kaXJlY3RvcnlFbnRyaWVzLmxlbmd0aDtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBnZXRFbmRPZkNlbnRyYWxEaXJlY3RvcnkoKTogRW5kT2ZDZW50cmFsRGlyZWN0b3J5IHtcclxuICAgIHJldHVybiB0aGlzLl9lb2NkO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIHN0YXRpYyBpc0F2YWlsYWJsZSgpOiBib29sZWFuIHsgcmV0dXJuIHRydWU7IH1cclxuXHJcbiAgcHVibGljIGRpc2tTcGFjZShwYXRoOiBzdHJpbmcsIGNiOiAodG90YWw6IG51bWJlciwgZnJlZTogbnVtYmVyKSA9PiB2b2lkKTogdm9pZCB7XHJcbiAgICAvLyBSZWFkLW9ubHkgZmlsZSBzeXN0ZW0uXHJcbiAgICBjYih0aGlzLmRhdGEubGVuZ3RoLCAwKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBpc1JlYWRPbmx5KCk6IGJvb2xlYW4ge1xyXG4gICAgcmV0dXJuIHRydWU7XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgc3VwcG9ydHNMaW5rcygpOiBib29sZWFuIHtcclxuICAgIHJldHVybiBmYWxzZTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBzdXBwb3J0c1Byb3BzKCk6IGJvb2xlYW4ge1xyXG4gICAgcmV0dXJuIGZhbHNlO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIHN1cHBvcnRzU3luY2goKTogYm9vbGVhbiB7XHJcbiAgICByZXR1cm4gdHJ1ZTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBzdGF0U3luYyhwYXRoOiBzdHJpbmcsIGlzTHN0YXQ6IGJvb2xlYW4pOiBTdGF0cyB7XHJcbiAgICB2YXIgaW5vZGUgPSB0aGlzLl9pbmRleC5nZXRJbm9kZShwYXRoKTtcclxuICAgIGlmIChpbm9kZSA9PT0gbnVsbCkge1xyXG4gICAgICB0aHJvdyBBcGlFcnJvci5FTk9FTlQocGF0aCk7XHJcbiAgICB9XHJcbiAgICB2YXIgc3RhdHM6IFN0YXRzO1xyXG4gICAgaWYgKGlzRmlsZUlub2RlPENlbnRyYWxEaXJlY3Rvcnk+KGlub2RlKSkge1xyXG4gICAgICBzdGF0cyA9IGlub2RlLmdldERhdGEoKS5nZXRTdGF0cygpO1xyXG4gICAgfSBlbHNlIGlmIChpc0Rpcklub2RlKGlub2RlKSkge1xyXG4gICAgICBzdGF0cyA9IGlub2RlLmdldFN0YXRzKCk7XHJcbiAgICB9IGVsc2Uge1xyXG4gICAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVJTlZBTCwgXCJJbnZhbGlkIGlub2RlLlwiKTtcclxuICAgIH1cclxuICAgIHJldHVybiBzdGF0cztcclxuICB9XHJcblxyXG4gIHB1YmxpYyBvcGVuU3luYyhwYXRoOiBzdHJpbmcsIGZsYWdzOiBGaWxlRmxhZywgbW9kZTogbnVtYmVyKTogZmlsZS5GaWxlIHtcclxuICAgIC8vIElOVkFSSUFOVDogQ2Fubm90IHdyaXRlIHRvIFJPIGZpbGUgc3lzdGVtcy5cclxuICAgIGlmIChmbGFncy5pc1dyaXRlYWJsZSgpKSB7XHJcbiAgICAgIHRocm93IG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRVBFUk0sIHBhdGgpO1xyXG4gICAgfVxyXG4gICAgLy8gQ2hlY2sgaWYgdGhlIHBhdGggZXhpc3RzLCBhbmQgaXMgYSBmaWxlLlxyXG4gICAgdmFyIGlub2RlID0gdGhpcy5faW5kZXguZ2V0SW5vZGUocGF0aCk7XHJcbiAgICBpZiAoIWlub2RlKSB7XHJcbiAgICAgIHRocm93IEFwaUVycm9yLkVOT0VOVChwYXRoKTtcclxuICAgIH0gZWxzZSBpZiAoaXNGaWxlSW5vZGU8Q2VudHJhbERpcmVjdG9yeT4oaW5vZGUpKSB7XHJcbiAgICAgIHZhciBjZFJlY29yZCA9IGlub2RlLmdldERhdGEoKTtcclxuICAgICAgdmFyIHN0YXRzID0gY2RSZWNvcmQuZ2V0U3RhdHMoKTtcclxuICAgICAgc3dpdGNoIChmbGFncy5wYXRoRXhpc3RzQWN0aW9uKCkpIHtcclxuICAgICAgICBjYXNlIEFjdGlvblR5cGUuVEhST1dfRVhDRVBUSU9OOlxyXG4gICAgICAgIGNhc2UgQWN0aW9uVHlwZS5UUlVOQ0FURV9GSUxFOlxyXG4gICAgICAgICAgdGhyb3cgQXBpRXJyb3IuRUVYSVNUKHBhdGgpO1xyXG4gICAgICAgIGNhc2UgQWN0aW9uVHlwZS5OT1A6XHJcbiAgICAgICAgICByZXR1cm4gbmV3IHByZWxvYWRfZmlsZS5Ob1N5bmNGaWxlKHRoaXMsIHBhdGgsIGZsYWdzLCBzdGF0cywgY2RSZWNvcmQuZ2V0RGF0YSgpKTtcclxuICAgICAgICBkZWZhdWx0OlxyXG4gICAgICAgICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FSU5WQUwsICdJbnZhbGlkIEZpbGVNb2RlIG9iamVjdC4nKTtcclxuICAgICAgfVxyXG4gICAgfSBlbHNlIHtcclxuICAgICAgdGhyb3cgQXBpRXJyb3IuRUlTRElSKHBhdGgpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgcHVibGljIHJlYWRkaXJTeW5jKHBhdGg6IHN0cmluZyk6IHN0cmluZ1tdIHtcclxuICAgIC8vIENoZWNrIGlmIGl0IGV4aXN0cy5cclxuICAgIHZhciBpbm9kZSA9IHRoaXMuX2luZGV4LmdldElub2RlKHBhdGgpO1xyXG4gICAgaWYgKCFpbm9kZSkge1xyXG4gICAgICB0aHJvdyBBcGlFcnJvci5FTk9FTlQocGF0aCk7XHJcbiAgICB9IGVsc2UgaWYgKGlzRGlySW5vZGUoaW5vZGUpKSB7XHJcbiAgICAgIHJldHVybiBpbm9kZS5nZXRMaXN0aW5nKCk7XHJcbiAgICB9IGVsc2Uge1xyXG4gICAgICB0aHJvdyBBcGlFcnJvci5FTk9URElSKHBhdGgpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgLyoqXHJcbiAgICogU3BlY2lhbGx5LW9wdGltaXplZCByZWFkZmlsZS5cclxuICAgKi9cclxuICBwdWJsaWMgcmVhZEZpbGVTeW5jKGZuYW1lOiBzdHJpbmcsIGVuY29kaW5nOiBzdHJpbmcsIGZsYWc6IEZpbGVGbGFnKTogYW55IHtcclxuICAgIC8vIEdldCBmaWxlLlxyXG4gICAgdmFyIGZkID0gdGhpcy5vcGVuU3luYyhmbmFtZSwgZmxhZywgMHgxYTQpO1xyXG4gICAgdHJ5IHtcclxuICAgICAgdmFyIGZkQ2FzdCA9IDxwcmVsb2FkX2ZpbGUuTm9TeW5jRmlsZTxaaXBGUz4+IGZkO1xyXG4gICAgICB2YXIgZmRCdWZmID0gPEJ1ZmZlcj4gZmRDYXN0LmdldEJ1ZmZlcigpO1xyXG4gICAgICBpZiAoZW5jb2RpbmcgPT09IG51bGwpIHtcclxuICAgICAgICByZXR1cm4gY29weWluZ1NsaWNlKGZkQnVmZik7XHJcbiAgICAgIH1cclxuICAgICAgcmV0dXJuIGZkQnVmZi50b1N0cmluZyhlbmNvZGluZyk7XHJcbiAgICB9IGZpbmFsbHkge1xyXG4gICAgICBmZC5jbG9zZVN5bmMoKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIC8qKlxyXG4gICAqIExvY2F0ZXMgdGhlIGVuZCBvZiBjZW50cmFsIGRpcmVjdG9yeSByZWNvcmQgYXQgdGhlIGVuZCBvZiB0aGUgZmlsZS5cclxuICAgKiBUaHJvd3MgYW4gZXhjZXB0aW9uIGlmIGl0IGNhbm5vdCBiZSBmb3VuZC5cclxuICAgKi9cclxuICBwcml2YXRlIHN0YXRpYyBnZXRFT0NEKGRhdGE6IE5vZGVCdWZmZXIpOiBFbmRPZkNlbnRyYWxEaXJlY3Rvcnkge1xyXG4gICAgLy8gVW5mb3J0dW5hdGVseSwgdGhlIGNvbW1lbnQgaXMgdmFyaWFibGUgc2l6ZSBhbmQgdXAgdG8gNjRLIGluIHNpemUuXHJcbiAgICAvLyBXZSBhc3N1bWUgdGhhdCB0aGUgbWFnaWMgc2lnbmF0dXJlIGRvZXMgbm90IGFwcGVhciBpbiB0aGUgY29tbWVudCwgYW5kXHJcbiAgICAvLyBpbiB0aGUgYnl0ZXMgYmV0d2VlbiB0aGUgY29tbWVudCBhbmQgdGhlIHNpZ25hdHVyZS4gT3RoZXIgWklQXHJcbiAgICAvLyBpbXBsZW1lbnRhdGlvbnMgbWFrZSB0aGlzIHNhbWUgYXNzdW1wdGlvbiwgc2luY2UgdGhlIGFsdGVybmF0aXZlIGlzIHRvXHJcbiAgICAvLyByZWFkIHRocmVhZCBldmVyeSBlbnRyeSBpbiB0aGUgZmlsZSB0byBnZXQgdG8gaXQuIDooXHJcbiAgICAvLyBUaGVzZSBhcmUgKm5lZ2F0aXZlKiBvZmZzZXRzIGZyb20gdGhlIGVuZCBvZiB0aGUgZmlsZS5cclxuICAgIHZhciBzdGFydE9mZnNldCA9IDIyO1xyXG4gICAgdmFyIGVuZE9mZnNldCA9IE1hdGgubWluKHN0YXJ0T2Zmc2V0ICsgMHhGRkZGLCBkYXRhLmxlbmd0aCAtIDEpO1xyXG4gICAgLy8gVGhlcmUncyBub3QgZXZlbiBhIGJ5dGUgYWxpZ25tZW50IGd1YXJhbnRlZSBvbiB0aGUgY29tbWVudCBzbyB3ZSBuZWVkIHRvXHJcbiAgICAvLyBzZWFyY2ggYnl0ZSBieSBieXRlLiAqZ3J1bWJsZSBncnVtYmxlKlxyXG4gICAgZm9yICh2YXIgaSA9IHN0YXJ0T2Zmc2V0OyBpIDwgZW5kT2Zmc2V0OyBpKyspIHtcclxuICAgICAgLy8gTWFnaWMgbnVtYmVyOiBFT0NEIFNpZ25hdHVyZVxyXG4gICAgICBpZiAoZGF0YS5yZWFkVUludDMyTEUoZGF0YS5sZW5ndGggLSBpKSA9PT0gMHgwNjA1NGI1MCkge1xyXG4gICAgICAgIHJldHVybiBuZXcgRW5kT2ZDZW50cmFsRGlyZWN0b3J5KGRhdGEuc2xpY2UoZGF0YS5sZW5ndGggLSBpKSk7XHJcbiAgICAgIH1cclxuICAgIH1cclxuICAgIHRocm93IG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRUlOVkFMLCBcIkludmFsaWQgWklQIGZpbGU6IENvdWxkIG5vdCBsb2NhdGUgRW5kIG9mIENlbnRyYWwgRGlyZWN0b3J5IHNpZ25hdHVyZS5cIik7XHJcbiAgfVxyXG5cclxuICBwcml2YXRlIHN0YXRpYyBhZGRUb0luZGV4KGNkOiBDZW50cmFsRGlyZWN0b3J5LCBpbmRleDogRmlsZUluZGV4PENlbnRyYWxEaXJlY3Rvcnk+KSB7XHJcbiAgICAvLyBQYXRocyBtdXN0IGJlIGFic29sdXRlLCB5ZXQgemlwIGZpbGUgcGF0aHMgYXJlIGFsd2F5cyByZWxhdGl2ZSB0byB0aGVcclxuICAgIC8vIHppcCByb290LiBTbyB3ZSBhcHBlbmQgJy8nIGFuZCBjYWxsIGl0IGEgZGF5LlxyXG4gICAgbGV0IGZpbGVuYW1lID0gY2QuZmlsZU5hbWUoKTtcclxuICAgIGlmIChmaWxlbmFtZS5jaGFyQXQoMCkgPT09ICcvJykgdGhyb3cgbmV3IEVycm9yKFwiV0hZIElTIFRISVMgQUJTT0xVVEVcIik7XHJcbiAgICAvLyBYWFg6IEZvciB0aGUgZmlsZSBpbmRleCwgc3RyaXAgdGhlIHRyYWlsaW5nICcvJy5cclxuICAgIGlmIChmaWxlbmFtZS5jaGFyQXQoZmlsZW5hbWUubGVuZ3RoIC0gMSkgPT09ICcvJykge1xyXG4gICAgICBmaWxlbmFtZSA9IGZpbGVuYW1lLnN1YnN0cigwLCBmaWxlbmFtZS5sZW5ndGgtMSk7XHJcbiAgICB9XHJcblxyXG4gICAgaWYgKGNkLmlzRGlyZWN0b3J5KCkpIHtcclxuICAgICAgaW5kZXguYWRkUGF0aEZhc3QoJy8nICsgZmlsZW5hbWUsIG5ldyBEaXJJbm9kZTxDZW50cmFsRGlyZWN0b3J5PihjZCkpO1xyXG4gICAgfSBlbHNlIHtcclxuICAgICAgaW5kZXguYWRkUGF0aEZhc3QoJy8nICsgZmlsZW5hbWUsIG5ldyBGaWxlSW5vZGU8Q2VudHJhbERpcmVjdG9yeT4oY2QpKTtcclxuICAgIH1cclxuICB9XHJcblxyXG4gIHByaXZhdGUgc3RhdGljIGNvbXB1dGVJbmRleFJlc3BvbnNpdmUoZGF0YTogTm9kZUJ1ZmZlciwgaW5kZXg6IEZpbGVJbmRleDxDZW50cmFsRGlyZWN0b3J5PiwgY2RQdHI6IG51bWJlciwgY2RFbmQ6IG51bWJlciwgY2I6ICh6aXBUT0M6IFppcFRPQykgPT4gdm9pZCwgY2RFbnRyaWVzOiBDZW50cmFsRGlyZWN0b3J5W10sIGVvY2Q6IEVuZE9mQ2VudHJhbERpcmVjdG9yeSkge1xyXG4gICAgaWYgKGNkUHRyIDwgY2RFbmQpIHtcclxuICAgICAgbGV0IGNvdW50ID0gMDtcclxuICAgICAgd2hpbGUgKGNvdW50KysgPCAyMDAgJiYgY2RQdHIgPCBjZEVuZCkge1xyXG4gICAgICAgIGNvbnN0IGNkOiBDZW50cmFsRGlyZWN0b3J5ID0gbmV3IENlbnRyYWxEaXJlY3RvcnkoZGF0YSwgZGF0YS5zbGljZShjZFB0cikpO1xyXG4gICAgICAgIFppcEZTLmFkZFRvSW5kZXgoY2QsIGluZGV4KTtcclxuICAgICAgICBjZFB0ciArPSBjZC50b3RhbFNpemUoKTtcclxuICAgICAgICBjZEVudHJpZXMucHVzaChjZCk7XHJcbiAgICAgIH1cclxuICAgICAgc2V0SW1tZWRpYXRlKCgpID0+IHtcclxuICAgICAgICBaaXBGUy5jb21wdXRlSW5kZXhSZXNwb25zaXZlKGRhdGEsIGluZGV4LCBjZFB0ciwgY2RFbmQsIGNiLCBjZEVudHJpZXMsIGVvY2QpO1xyXG4gICAgICB9KTtcclxuICAgIH0gZWxzZSB7XHJcbiAgICAgIGNiKG5ldyBaaXBUT0MoaW5kZXgsIGNkRW50cmllcywgZW9jZCwgZGF0YSkpO1xyXG4gICAgfVxyXG4gIH1cclxuXHJcbiAgc3RhdGljIGNvbXB1dGVJbmRleChkYXRhOiBOb2RlQnVmZmVyLCBjYjogKHppcFRPQzogWmlwVE9DKSA9PiB2b2lkKSB7XHJcbiAgICBjb25zdCBpbmRleDogRmlsZUluZGV4PENlbnRyYWxEaXJlY3Rvcnk+ID0gbmV3IEZpbGVJbmRleDxDZW50cmFsRGlyZWN0b3J5PigpO1xyXG4gICAgY29uc3QgZW9jZDogRW5kT2ZDZW50cmFsRGlyZWN0b3J5ID0gWmlwRlMuZ2V0RU9DRChkYXRhKTtcclxuICAgIGlmIChlb2NkLmRpc2tOdW1iZXIoKSAhPT0gZW9jZC5jZERpc2tOdW1iZXIoKSlcclxuICAgICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FSU5WQUwsIFwiWmlwRlMgZG9lcyBub3Qgc3VwcG9ydCBzcGFubmVkIHppcCBmaWxlcy5cIik7XHJcblxyXG4gICAgY29uc3QgY2RQdHIgPSBlb2NkLmNkT2Zmc2V0KCk7XHJcbiAgICBpZiAoY2RQdHIgPT09IDB4RkZGRkZGRkYpXHJcbiAgICAgIHRocm93IG5ldyBBcGlFcnJvcihFcnJvckNvZGUuRUlOVkFMLCBcIlppcEZTIGRvZXMgbm90IHN1cHBvcnQgWmlwNjQuXCIpO1xyXG4gICAgY29uc3QgY2RFbmQgPSBjZFB0ciArIGVvY2QuY2RTaXplKCk7XHJcbiAgICBaaXBGUy5jb21wdXRlSW5kZXhSZXNwb25zaXZlKGRhdGEsIGluZGV4LCBjZFB0ciwgY2RFbmQsIGNiLCBbXSwgZW9jZCk7XHJcbiAgfVxyXG5cclxuICBwcml2YXRlIHBvcHVsYXRlSW5kZXgoKSB7XHJcbiAgICB2YXIgZW9jZDogRW5kT2ZDZW50cmFsRGlyZWN0b3J5ID0gdGhpcy5fZW9jZCA9IFppcEZTLmdldEVPQ0QodGhpcy5kYXRhKTtcclxuICAgIGlmIChlb2NkLmRpc2tOdW1iZXIoKSAhPT0gZW9jZC5jZERpc2tOdW1iZXIoKSlcclxuICAgICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FSU5WQUwsIFwiWmlwRlMgZG9lcyBub3Qgc3VwcG9ydCBzcGFubmVkIHppcCBmaWxlcy5cIik7XHJcblxyXG4gICAgdmFyIGNkUHRyID0gZW9jZC5jZE9mZnNldCgpO1xyXG4gICAgaWYgKGNkUHRyID09PSAweEZGRkZGRkZGKVxyXG4gICAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVJTlZBTCwgXCJaaXBGUyBkb2VzIG5vdCBzdXBwb3J0IFppcDY0LlwiKTtcclxuICAgIHZhciBjZEVuZCA9IGNkUHRyICsgZW9jZC5jZFNpemUoKTtcclxuICAgIHdoaWxlIChjZFB0ciA8IGNkRW5kKSB7XHJcbiAgICAgIGNvbnN0IGNkOiBDZW50cmFsRGlyZWN0b3J5ID0gbmV3IENlbnRyYWxEaXJlY3RvcnkodGhpcy5kYXRhLCB0aGlzLmRhdGEuc2xpY2UoY2RQdHIpKTtcclxuICAgICAgY2RQdHIgKz0gY2QudG90YWxTaXplKCk7XHJcbiAgICAgIFppcEZTLmFkZFRvSW5kZXgoY2QsIHRoaXMuX2luZGV4KTtcclxuICAgICAgdGhpcy5fZGlyZWN0b3J5RW50cmllcy5wdXNoKGNkKTtcclxuICAgIH1cclxuICB9XHJcbn1cclxuIl19