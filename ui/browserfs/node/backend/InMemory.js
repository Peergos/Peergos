"use strict";
var __extends = (this && this.__extends) || function (d, b) {
    for (var p in b) if (b.hasOwnProperty(p)) d[p] = b[p];
    function __() { this.constructor = d; }
    d.prototype = b === null ? Object.create(b) : (__.prototype = b.prototype, new __());
};
var kvfs = require('../generic/key_value_filesystem');
var InMemoryStore = (function () {
    function InMemoryStore() {
        this.store = {};
    }
    InMemoryStore.prototype.name = function () { return 'In-memory'; };
    InMemoryStore.prototype.clear = function () { this.store = {}; };
    InMemoryStore.prototype.beginTransaction = function (type) {
        return new kvfs.SimpleSyncRWTransaction(this);
    };
    InMemoryStore.prototype.get = function (key) {
        return this.store[key];
    };
    InMemoryStore.prototype.put = function (key, data, overwrite) {
        if (!overwrite && this.store.hasOwnProperty(key)) {
            return false;
        }
        this.store[key] = data;
        return true;
    };
    InMemoryStore.prototype.del = function (key) {
        delete this.store[key];
    };
    return InMemoryStore;
}());
exports.InMemoryStore = InMemoryStore;
var InMemoryFileSystem = (function (_super) {
    __extends(InMemoryFileSystem, _super);
    function InMemoryFileSystem() {
        _super.call(this, { store: new InMemoryStore() });
    }
    return InMemoryFileSystem;
}(kvfs.SyncKeyValueFileSystem));
exports.__esModule = true;
exports["default"] = InMemoryFileSystem;
//# sourceMappingURL=data:application/json;base64,eyJ2ZXJzaW9uIjozLCJmaWxlIjoiSW5NZW1vcnkuanMiLCJzb3VyY2VSb290IjoiIiwic291cmNlcyI6WyIuLi8uLi8uLi9zcmMvYmFja2VuZC9Jbk1lbW9yeS50cyJdLCJuYW1lcyI6W10sIm1hcHBpbmdzIjoiOzs7Ozs7QUFBQSxJQUFPLElBQUksV0FBVyxpQ0FBaUMsQ0FBQyxDQUFDO0FBS3pEO0lBQUE7UUFDVSxVQUFLLEdBQWtDLEVBQUUsQ0FBQztJQXdCcEQsQ0FBQztJQXRCUSw0QkFBSSxHQUFYLGNBQWdCLE1BQU0sQ0FBQyxXQUFXLENBQUMsQ0FBQyxDQUFDO0lBQzlCLDZCQUFLLEdBQVosY0FBaUIsSUFBSSxDQUFDLEtBQUssR0FBRyxFQUFFLENBQUMsQ0FBQyxDQUFDO0lBRTVCLHdDQUFnQixHQUF2QixVQUF3QixJQUFZO1FBQ2xDLE1BQU0sQ0FBQyxJQUFJLElBQUksQ0FBQyx1QkFBdUIsQ0FBQyxJQUFJLENBQUMsQ0FBQztJQUNoRCxDQUFDO0lBRU0sMkJBQUcsR0FBVixVQUFXLEdBQVc7UUFDcEIsTUFBTSxDQUFDLElBQUksQ0FBQyxLQUFLLENBQUMsR0FBRyxDQUFDLENBQUM7SUFDekIsQ0FBQztJQUVNLDJCQUFHLEdBQVYsVUFBVyxHQUFXLEVBQUUsSUFBZ0IsRUFBRSxTQUFrQjtRQUMxRCxFQUFFLENBQUMsQ0FBQyxDQUFDLFNBQVMsSUFBSSxJQUFJLENBQUMsS0FBSyxDQUFDLGNBQWMsQ0FBQyxHQUFHLENBQUMsQ0FBQyxDQUFDLENBQUM7WUFDakQsTUFBTSxDQUFDLEtBQUssQ0FBQztRQUNmLENBQUM7UUFDRCxJQUFJLENBQUMsS0FBSyxDQUFDLEdBQUcsQ0FBQyxHQUFHLElBQUksQ0FBQztRQUN2QixNQUFNLENBQUMsSUFBSSxDQUFDO0lBQ2QsQ0FBQztJQUVNLDJCQUFHLEdBQVYsVUFBVyxHQUFXO1FBQ3BCLE9BQU8sSUFBSSxDQUFDLEtBQUssQ0FBQyxHQUFHLENBQUMsQ0FBQztJQUN6QixDQUFDO0lBQ0gsb0JBQUM7QUFBRCxDQUFDLEFBekJELElBeUJDO0FBekJZLHFCQUFhLGdCQXlCekIsQ0FBQTtBQUtEO0lBQWdELHNDQUEyQjtJQUN6RTtRQUNFLGtCQUFNLEVBQUUsS0FBSyxFQUFFLElBQUksYUFBYSxFQUFFLEVBQUUsQ0FBQyxDQUFDO0lBQ3hDLENBQUM7SUFDSCx5QkFBQztBQUFELENBQUMsQUFKRCxDQUFnRCxJQUFJLENBQUMsc0JBQXNCLEdBSTFFO0FBSkQ7dUNBSUMsQ0FBQSIsInNvdXJjZXNDb250ZW50IjpbImltcG9ydCBrdmZzID0gcmVxdWlyZSgnLi4vZ2VuZXJpYy9rZXlfdmFsdWVfZmlsZXN5c3RlbScpO1xyXG5cclxuLyoqXHJcbiAqIEEgc2ltcGxlIGluLW1lbW9yeSBrZXktdmFsdWUgc3RvcmUgYmFja2VkIGJ5IGEgSmF2YVNjcmlwdCBvYmplY3QuXHJcbiAqL1xyXG5leHBvcnQgY2xhc3MgSW5NZW1vcnlTdG9yZSBpbXBsZW1lbnRzIGt2ZnMuU3luY0tleVZhbHVlU3RvcmUsIGt2ZnMuU2ltcGxlU3luY1N0b3JlIHtcclxuICBwcml2YXRlIHN0b3JlOiB7IFtrZXk6IHN0cmluZ106IE5vZGVCdWZmZXIgfSA9IHt9O1xyXG5cclxuICBwdWJsaWMgbmFtZSgpIHsgcmV0dXJuICdJbi1tZW1vcnknOyB9XHJcbiAgcHVibGljIGNsZWFyKCkgeyB0aGlzLnN0b3JlID0ge307IH1cclxuXHJcbiAgcHVibGljIGJlZ2luVHJhbnNhY3Rpb24odHlwZTogc3RyaW5nKToga3Zmcy5TeW5jS2V5VmFsdWVSV1RyYW5zYWN0aW9uIHtcclxuICAgIHJldHVybiBuZXcga3Zmcy5TaW1wbGVTeW5jUldUcmFuc2FjdGlvbih0aGlzKTtcclxuICB9XHJcblxyXG4gIHB1YmxpYyBnZXQoa2V5OiBzdHJpbmcpOiBOb2RlQnVmZmVyIHtcclxuICAgIHJldHVybiB0aGlzLnN0b3JlW2tleV07XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgcHV0KGtleTogc3RyaW5nLCBkYXRhOiBOb2RlQnVmZmVyLCBvdmVyd3JpdGU6IGJvb2xlYW4pOiBib29sZWFuIHtcclxuICAgIGlmICghb3ZlcndyaXRlICYmIHRoaXMuc3RvcmUuaGFzT3duUHJvcGVydHkoa2V5KSkge1xyXG4gICAgICByZXR1cm4gZmFsc2U7XHJcbiAgICB9XHJcbiAgICB0aGlzLnN0b3JlW2tleV0gPSBkYXRhO1xyXG4gICAgcmV0dXJuIHRydWU7XHJcbiAgfVxyXG5cclxuICBwdWJsaWMgZGVsKGtleTogc3RyaW5nKTogdm9pZCB7XHJcbiAgICBkZWxldGUgdGhpcy5zdG9yZVtrZXldO1xyXG4gIH1cclxufVxyXG5cclxuLyoqXHJcbiAqIEEgc2ltcGxlIGluLW1lbW9yeSBmaWxlIHN5c3RlbSBiYWNrZWQgYnkgYW4gSW5NZW1vcnlTdG9yZS5cclxuICovXHJcbmV4cG9ydCBkZWZhdWx0IGNsYXNzIEluTWVtb3J5RmlsZVN5c3RlbSBleHRlbmRzIGt2ZnMuU3luY0tleVZhbHVlRmlsZVN5c3RlbSB7XHJcbiAgY29uc3RydWN0b3IoKSB7XHJcbiAgICBzdXBlcih7IHN0b3JlOiBuZXcgSW5NZW1vcnlTdG9yZSgpIH0pO1xyXG4gIH1cclxufVxyXG4iXX0=