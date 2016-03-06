ByteArrayOutputStream = function(size) {
    if (size == null)
	size = 64;
    this.arr = new Uint8Array(size);
    this.index = 0;

    this.free = function() {
	return this.arr.length - this.index;
    }

    this.reset = function() {
	this.index = 0;
    }

    this.resize = function(size) {
	if (size < this.arr.length)
	    return;
	const newarr = new Uint8Array(size);
	for (var i=0; i < this.index; i++)
	    newarr[i] = this.arr[i];
	this.arr = newarr;
    }

    this.ensureFree = function(size) {
	if (this.free() >= size)
	    return;
	this.resize(Math.max(2*this.arr.length, this.index + size));
    }

    this.writeByte = function(b) {
	this.ensureFree(1);
	this.arr[this.index++] = b;
    }

    this.writeInt = function(i) {
	this.ensureFree(4);
	this.arr[this.index++] = (i >> 24) & 0xff;
	this.arr[this.index++] = (i >> 16) & 0xff;
	this.arr[this.index++] = (i >> 8) & 0xff;
	this.arr[this.index++] = i & 0xff;
    }

    this.writeDouble = function(d) {
	const tmp = new Float64Array(1);
	tmp[0] = d;
	const tmpBytes = new Uint8Array(tmp.buffer);
	for (var i=0; i < 8; i++)
	    this.arr[this.index++] = tmpBytes[i];
    }

    this.writeArray = function(a) {
	this.ensureFree(a.length+4);
	this.writeInt(a.length);
	for (var i=0; i < a.length; i++)
	    this.arr[this.index++] = a[i];
    }

    this.write = function(array, start, len) {
	if (start == null)
	    start = 0;
	if (len == null)
	    len = array.length;
	this.ensureFree(len);
	for (var i=0; i < len; i++)
	    this.arr[this.index++] = array[start + i];
    }

    this.writeString = function(s) {
	this.writeArray(nacl.util.decodeUTF8(s));
    }

    this.toByteArray = function() {
	return this.arr.subarray(0, this.index);
    }
}

ByteArrayInputStream = function(arr) {
    this.arr = arr;
    this.index = 0;

    this.readByte = function() {
	return this.arr[this.index++];
    }

    this.readInt = function() {
	var res = 0;
	for (var i=0; i < 4; i++)
	    res |= (this.arr[this.index++] << ((3-i)*8));
	return res;
    }

    this.readDouble = function() {
	const darr = new Uint8Array(8);
	for (var i=0; i < 8; i++)
	    darr[i] = this.arr[this.index++];
	return new Float64Array(darr.buffer)[0];
    }

    this.readArray = function() {
	const len = this.readInt();
	if (len < 0)
	    throw "negative array size! "+len;
	const res = new Uint8Array(len);
	for (var i=0; i < len; i++)
	    res[i] = this.arr[this.index++];
	return res;
    }

    this.read = function(len) {
	const res = new Uint8Array(len);
	for (var i=0; i < len; i++)
	    res[i] = this.arr[this.index++];
	return res;
    }

    this.readString = function() {
	return nacl.util.encodeUTF8(this.readArray());
    }

    this.skip = function(bytes) {
	this.index += bytes;
	if (this.index < 0 || this.index > this.arr.length)
	    throw "Illegal offset into array! " + this.index;
    }
}
