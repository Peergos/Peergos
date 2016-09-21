"use strict";
var __extends = (this && this.__extends) || function (d, b) {
    for (var p in b) if (b.hasOwnProperty(p)) d[p] = b[p];
    function __() { this.constructor = d; }
    d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
};
var kvfs = require('../generic/key_value_filesystem');
var api_error_1 = require('../core/api_error');
var global = require('../core/global');
var supportsBinaryString = false, binaryEncoding;
try {
    global.localStorage.setItem("__test__", String.fromCharCode(0xD800));
    supportsBinaryString = global.localStorage.getItem("__test__") === String.fromCharCode(0xD800);
}
catch (e) {
    supportsBinaryString = false;
}
binaryEncoding = supportsBinaryString ? 'binary_string' : 'binary_string_ie';
if (!Buffer.isEncoding(binaryEncoding)) {
    binaryEncoding = "base64";
}
var LocalStorageStore = (function () {
    function LocalStorageStore() {
    }
    LocalStorageStore.prototype.name = function () {
        return 'LocalStorage';
    };
    LocalStorageStore.prototype.clear = function () {
        global.localStorage.clear();
    };
    LocalStorageStore.prototype.beginTransaction = function (type) {
        return new kvfs.SimpleSyncRWTransaction(this);
    };
    LocalStorageStore.prototype.get = function (key) {
        try {
            var data = global.localStorage.getItem(key);
            if (data !== null) {
                return new Buffer(data, binaryEncoding);
            }
        }
        catch (e) {
        }
        return undefined;
    };
    LocalStorageStore.prototype.put = function (key, data, overwrite) {
        try {
            if (!overwrite && global.localStorage.getItem(key) !== null) {
                return false;
            }
            global.localStorage.setItem(key, data.toString(binaryEncoding));
            return true;
        }
        catch (e) {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.ENOSPC, "LocalStorage is full.");
        }
    };
    LocalStorageStore.prototype.del = function (key) {
        try {
            global.localStorage.removeItem(key);
        }
        catch (e) {
            throw new api_error_1.ApiError(api_error_1.ErrorCode.EIO, "Unable to delete key " + key + ": " + e);
        }
    };
    return LocalStorageStore;
}());
exports.LocalStorageStore = LocalStorageStore;
var LocalStorageFileSystem = (function (_super) {
    __extends(LocalStorageFileSystem, _super);
    function LocalStorageFileSystem() {
        _super.call(this, { store: new LocalStorageStore() });
    }
    LocalStorageFileSystem.isAvailable = function () {
        return typeof global.localStorage !== 'undefined';
    };
    return LocalStorageFileSystem;
}(kvfs.SyncKeyValueFileSystem));
exports.__esModule = true;
exports["default"] = LocalStorageFileSystem;
//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJmaWxlIjoiTG9jYWxTdG9yYWdlLmpzIiwic291cmNlUm9vdCI6IiIsInNvdXJjZXMiOlsiLi4vLi4vLi4vc3JjL2JhY2tlbmQvTG9jYWxTdG9yYWdlLnRzIl0sIm5hbWVzIjpbXSwibWFwcGluZ3MiOiI7Ozs7OztBQUFBLElBQU8sSUFBSSxXQUFXLGlDQUFpQyxDQUFDLENBQUM7QUFDekQsMEJBQWtDLG1CQUFtQixDQUFDLENBQUE7QUFDdEQsSUFBTyxNQUFNLFdBQVcsZ0JBQWdCLENBQUMsQ0FBQztBQUsxQyxJQUFJLG9CQUFvQixHQUFZLEtBQUssRUFDdkMsY0FBc0IsQ0FBQztBQUN6QixJQUFJLENBQUM7SUFDSCxNQUFNLENBQUMsWUFBWSxDQUFDLE9BQU8sQ0FBQyxVQUFVLEVBQUUsTUFBTSxDQUFDLFlBQVksQ0FBQyxNQUFNLENBQUMsQ0FBQyxDQUFDO0lBQ3JFLG9CQUFvQixHQUFHLE1BQU0sQ0FBQyxZQUFZLENBQUMsT0FBTyxDQUFDLFVBQVUsQ0FBQyxLQUFLLE1BQU0sQ0FBQyxZQUFZLENBQUMsTUFBTSxDQUFDLENBQUM7QUFDakcsQ0FBRTtBQUFBLEtBQUssQ0FBQyxDQUFDLENBQUMsQ0FBQyxDQUFDLENBQUM7SUFFWCxvQkFBb0IsR0FBRyxLQUFLLENBQUM7QUFDL0IsQ0FBQztBQUNELGNBQWMsR0FBRyxvQkFBb0IsR0FBRyxlQUFlLEdBQUcsa0JBQWtCLENBQUM7QUFDN0UsRUFBRSxDQUFDLENBQUMsQ0FBQyxNQUFNLENBQUMsVUFBVSxDQUFDLGNBQWMsQ0FBQyxDQUFDLENBQUMsQ0FBQztJQUd2QyxjQUFjLEdBQUcsUUFBUSxDQUFDO0FBQzVCLENBQUM7QUFLRDtJQUNFO0lBQWdCLENBQUM7SUFFVixnQ0FBSSxHQUFYO1FBQ0UsTUFBTSxDQUFDLGNBQWMsQ0FBQztJQUN4QixDQUFDO0lBRU0saUNBQUssR0FBWjtRQUNFLE1BQU0sQ0FBQyxZQUFZLENBQUMsS0FBSyxFQUFFLENBQUM7SUFDOUIsQ0FBQztJQUVNLDRDQUFnQixHQUF2QixVQUF3QixJQUFZO1FBRWxDLE1BQU0sQ0FBQyxJQUFJLElBQUksQ0FBQyx1QkFBdUIsQ0FBQyxJQUFJLENBQUMsQ0FBQztJQUNoRCxDQUFDO0lBRU0sK0JBQUcsR0FBVixVQUFXLEdBQVc7UUFDcEIsSUFBSSxDQUFDO1lBQ0gsSUFBSSxJQUFJLEdBQUcsTUFBTSxDQUFDLFlBQVksQ0FBQyxPQUFPLENBQUMsR0FBRyxDQUFDLENBQUM7WUFDNUMsRUFBRSxDQUFDLENBQUMsSUFBSSxLQUFLLElBQUksQ0FBQyxDQUFDLENBQUM7Z0JBQ2xCLE1BQU0sQ0FBQyxJQUFJLE1BQU0sQ0FBQyxJQUFJLEVBQUUsY0FBYyxDQUFDLENBQUM7WUFDMUMsQ0FBQztRQUNILENBQUU7UUFBQSxLQUFLLENBQUMsQ0FBQyxDQUFDLENBQUMsQ0FBQyxDQUFDO1FBRWIsQ0FBQztRQUVELE1BQU0sQ0FBQyxTQUFTLENBQUM7SUFDbkIsQ0FBQztJQUVNLCtCQUFHLEdBQVYsVUFBVyxHQUFXLEVBQUUsSUFBZ0IsRUFBRSxTQUFrQjtRQUMxRCxJQUFJLENBQUM7WUFDSCxFQUFFLENBQUMsQ0FBQyxDQUFDLFNBQVMsSUFBSSxNQUFNLENBQUMsWUFBWSxDQUFDLE9BQU8sQ0FBQyxHQUFHLENBQUMsS0FBSyxJQUFJLENBQUMsQ0FBQyxDQUFDO2dCQUU1RCxNQUFNLENBQUMsS0FBSyxDQUFDO1lBQ2YsQ0FBQztZQUNELE1BQU0sQ0FBQyxZQUFZLENBQUMsT0FBTyxDQUFDLEdBQUcsRUFBRSxJQUFJLENBQUMsUUFBUSxDQUFDLGNBQWMsQ0FBQyxDQUFDLENBQUM7WUFDaEUsTUFBTSxDQUFDLElBQUksQ0FBQztRQUNkLENBQUU7UUFBQSxLQUFLLENBQUMsQ0FBQyxDQUFDLENBQUMsQ0FBQyxDQUFDO1lBQ1gsTUFBTSxJQUFJLG9CQUFRLENBQUMscUJBQVMsQ0FBQyxNQUFNLEVBQUUsdUJBQXVCLENBQUMsQ0FBQztRQUNoRSxDQUFDO0lBQ0gsQ0FBQztJQUVNLCtCQUFHLEdBQVYsVUFBVyxHQUFXO1FBQ3BCLElBQUksQ0FBQztZQUNILE1BQU0sQ0FBQyxZQUFZLENBQUMsVUFBVSxDQUFDLEdBQUcsQ0FBQyxDQUFDO1FBQ3RDLENBQUU7UUFBQSxLQUFLLENBQUMsQ0FBQyxDQUFDLENBQUMsQ0FBQyxDQUFDO1lBQ1gsTUFBTSxJQUFJLG9CQUFRLENBQUMscUJBQVMsQ0FBQyxHQUFHLEVBQUUsdUJBQXVCLEdBQUcsR0FBRyxHQUFHLElBQUksR0FBRyxDQUFDLENBQUMsQ0FBQztRQUM5RSxDQUFDO0lBQ0gsQ0FBQztJQUNILHdCQUFDO0FBQUQsQ0FBQyxBQWpERCxJQWlEQztBQWpEWSx5QkFBaUIsb0JBaUQ3QixDQUFBO0FBTUQ7SUFBb0QsMENBQTJCO0lBQzdFO1FBQWdCLGtCQUFNLEVBQUUsS0FBSyxFQUFFLElBQUksaUJBQWlCLEVBQUUsRUFBRSxDQUFDLENBQUM7SUFBQyxDQUFDO0lBQzlDLGtDQUFXLEdBQXpCO1FBQ0UsTUFBTSxDQUFDLE9BQU8sTUFBTSxDQUFDLFlBQVksS0FBSyxXQUFXLENBQUM7SUFDcEQsQ0FBQztJQUNILDZCQUFDO0FBQUQsQ0FBQyxBQUxELENBQW9ELElBQUksQ0FBQyxzQkFBc0IsR0FLOUU7QUFMRDsyQ0FLQyxDQUFBIiwic291cmNlc0NvbnRlbnQiOlsiaW1wb3J0IGt2ZnMgPSByZXF1aXJlKCcuLi9nZW5lcmljL2tleV92YWx1ZV9maWxlc3lzdGVtJyk7XHJcbmltcG9ydCB7QXBpRXJyb3IsIEVycm9yQ29kZX0gZnJvbSAnLi4vY29yZS9hcGlfZXJyb3InO1xyXG5pbXBvcnQgZ2xvYmFsID0gcmVxdWlyZSgnLi4vY29yZS9nbG9iYWwnKTtcclxuXHJcbi8vIFNvbWUgdmVyc2lvbnMgb2YgRkYgYW5kIGFsbCB2ZXJzaW9ucyBvZiBJRSBkbyBub3Qgc3VwcG9ydCB0aGUgZnVsbCByYW5nZSBvZlxyXG4vLyAxNi1iaXQgbnVtYmVycyBlbmNvZGVkIGFzIGNoYXJhY3RlcnMsIGFzIHRoZXkgZW5mb3JjZSBVVEYtMTYgcmVzdHJpY3Rpb25zLlxyXG4vLyBodHRwOi8vc3RhY2tvdmVyZmxvdy5jb20vcXVlc3Rpb25zLzExMTcwNzE2L2FyZS10aGVyZS1hbnktY2hhcmFjdGVycy10aGF0LWFyZS1ub3QtYWxsb3dlZC1pbi1sb2NhbHN0b3JhZ2UvMTExNzM2NzMjMTExNzM2NzNcclxudmFyIHN1cHBvcnRzQmluYXJ5U3RyaW5nOiBib29sZWFuID0gZmFsc2UsXHJcbiAgYmluYXJ5RW5jb2Rpbmc6IHN0cmluZztcclxudHJ5IHtcclxuICBnbG9iYWwubG9jYWxTdG9yYWdlLnNldEl0ZW0oXCJfX3Rlc3RfX1wiLCBTdHJpbmcuZnJvbUNoYXJDb2RlKDB4RDgwMCkpO1xyXG4gIHN1cHBvcnRzQmluYXJ5U3RyaW5nID0gZ2xvYmFsLmxvY2FsU3RvcmFnZS5nZXRJdGVtKFwiX190ZXN0X19cIikgPT09IFN0cmluZy5mcm9tQ2hhckNvZGUoMHhEODAwKTtcclxufSBjYXRjaCAoZSkge1xyXG4gIC8vIElFIHRocm93cyBhbiBleGNlcHRpb24uXHJcbiAgc3VwcG9ydHNCaW5hcnlTdHJpbmcgPSBmYWxzZTtcclxufVxyXG5iaW5hcnlFbmNvZGluZyA9IHN1cHBvcnRzQmluYXJ5U3RyaW5nID8gJ2JpbmFyeV9zdHJpbmcnIDogJ2JpbmFyeV9zdHJpbmdfaWUnO1xyXG5pZiAoIUJ1ZmZlci5pc0VuY29kaW5nKGJpbmFyeUVuY29kaW5nKSkge1xyXG4gIC8vIEZhbGxiYWNrIGZvciBub24gQnJvd3NlckZTIGltcGxlbWVudGF0aW9ucyBvZiBidWZmZXIgdGhhdCBsYWNrIGFcclxuICAvLyBiaW5hcnlfc3RyaW5nIGZvcm1hdC5cclxuICBiaW5hcnlFbmNvZGluZyA9IFwiYmFzZTY0XCI7XHJcbn1cclxuXHJcbi8qKlxyXG4gKiBBIHN5bmNocm9ub3VzIGtleS12YWx1ZSBzdG9yZSBiYWNrZWQgYnkgbG9jYWxTdG9yYWdlLlxyXG4gKi9cclxuZXhwb3J0IGNsYXNzIExvY2FsU3RvcmFnZVN0b3JlIGltcGxlbWVudHMga3Zmcy5TeW5jS2V5VmFsdWVTdG9yZSwga3Zmcy5TaW1wbGVTeW5jU3RvcmUge1xyXG4gIGNvbnN0cnVjdG9yKCkgeyB9XHJcblxyXG4gIHB1YmxpYyBuYW1lKCk6IHN0cmluZyB7XHJcbiAgICByZXR1cm4gJ0xvY2FsU3RvcmFnZSc7XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgY2xlYXIoKTogdm9pZCB7XHJcbiAgICBnbG9iYWwubG9jYWxTdG9yYWdlLmNsZWFyKCk7XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgYmVnaW5UcmFuc2FjdGlvbih0eXBlOiBzdHJpbmcpOiBrdmZzLlN5bmNLZXlWYWx1ZVJXVHJhbnNhY3Rpb24ge1xyXG4gICAgLy8gTm8gbmVlZCB0byBkaWZmZXJlbnRpYXRlLlxyXG4gICAgcmV0dXJuIG5ldyBrdmZzLlNpbXBsZVN5bmNSV1RyYW5zYWN0aW9uKHRoaXMpO1xyXG4gIH1cclxuXHJcbiAgcHVibGljIGdldChrZXk6IHN0cmluZyk6IE5vZGVCdWZmZXIge1xyXG4gICAgdHJ5IHtcclxuICAgICAgdmFyIGRhdGEgPSBnbG9iYWwubG9jYWxTdG9yYWdlLmdldEl0ZW0oa2V5KTtcclxuICAgICAgaWYgKGRhdGEgIT09IG51bGwpIHtcclxuICAgICAgICByZXR1cm4gbmV3IEJ1ZmZlcihkYXRhLCBiaW5hcnlFbmNvZGluZyk7XHJcbiAgICAgIH1cclxuICAgIH0gY2F0Y2ggKGUpIHtcclxuXHJcbiAgICB9XHJcbiAgICAvLyBLZXkgZG9lc24ndCBleGlzdCwgb3IgYSBmYWlsdXJlIG9jY3VycmVkLlxyXG4gICAgcmV0dXJuIHVuZGVmaW5lZDtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBwdXQoa2V5OiBzdHJpbmcsIGRhdGE6IE5vZGVCdWZmZXIsIG92ZXJ3cml0ZTogYm9vbGVhbik6IGJvb2xlYW4ge1xyXG4gICAgdHJ5IHtcclxuICAgICAgaWYgKCFvdmVyd3JpdGUgJiYgZ2xvYmFsLmxvY2FsU3RvcmFnZS5nZXRJdGVtKGtleSkgIT09IG51bGwpIHtcclxuICAgICAgICAvLyBEb24ndCB3YW50IHRvIG92ZXJ3cml0ZSB0aGUga2V5IVxyXG4gICAgICAgIHJldHVybiBmYWxzZTtcclxuICAgICAgfVxyXG4gICAgICBnbG9iYWwubG9jYWxTdG9yYWdlLnNldEl0ZW0oa2V5LCBkYXRhLnRvU3RyaW5nKGJpbmFyeUVuY29kaW5nKSk7XHJcbiAgICAgIHJldHVybiB0cnVlO1xyXG4gICAgfSBjYXRjaCAoZSkge1xyXG4gICAgICB0aHJvdyBuZXcgQXBpRXJyb3IoRXJyb3JDb2RlLkVOT1NQQywgXCJMb2NhbFN0b3JhZ2UgaXMgZnVsbC5cIik7XHJcbiAgICB9XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgZGVsKGtleTogc3RyaW5nKTogdm9pZCB7XHJcbiAgICB0cnkge1xyXG4gICAgICBnbG9iYWwubG9jYWxTdG9yYWdlLnJlbW92ZUl0ZW0oa2V5KTtcclxuICAgIH0gY2F0Y2ggKGUpIHtcclxuICAgICAgdGhyb3cgbmV3IEFwaUVycm9yKEVycm9yQ29kZS5FSU8sIFwiVW5hYmxlIHRvIGRlbGV0ZSBrZXkgXCIgKyBrZXkgKyBcIjogXCIgKyBlKTtcclxuICAgIH1cclxuICB9XHJcbn1cclxuXHJcbi8qKlxyXG4gKiBBIHN5bmNocm9ub3VzIGZpbGUgc3lzdGVtIGJhY2tlZCBieSBsb2NhbFN0b3JhZ2UuIENvbm5lY3RzIG91clxyXG4gKiBMb2NhbFN0b3JhZ2VTdG9yZSB0byBvdXIgU3luY0tleVZhbHVlRmlsZVN5c3RlbS5cclxuICovXHJcbmV4cG9ydCBkZWZhdWx0IGNsYXNzIExvY2FsU3RvcmFnZUZpbGVTeXN0ZW0gZXh0ZW5kcyBrdmZzLlN5bmNLZXlWYWx1ZUZpbGVTeXN0ZW0ge1xyXG4gIGNvbnN0cnVjdG9yKCkgeyBzdXBlcih7IHN0b3JlOiBuZXcgTG9jYWxTdG9yYWdlU3RvcmUoKSB9KTsgfVxyXG4gIHB1YmxpYyBzdGF0aWMgaXNBdmFpbGFibGUoKTogYm9vbGVhbiB7XHJcbiAgICByZXR1cm4gdHlwZW9mIGdsb2JhbC5sb2NhbFN0b3JhZ2UgIT09ICd1bmRlZmluZWQnO1xyXG4gIH1cclxufVxyXG4iXX0=