(function(exports){
    "use strict";
   
var ByteArrayOutputStream = function(size) {
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

var ByteArrayInputStream = function(arr) {
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
}

function arraysEqual(a, b) {
    if (a.length != b.length)
        return false;

    for (var i=0; i < a.length; i++)
        if (a[i] != b[i])
            return false;
    return true;
}

var Galois = function(){
    this.size = 256;
    this.exp = new Uint8Array(2*this.size);
    this.log = new Uint8Array(this.size);
    this.exp[0] = 1;
    var x = 1;
    for (var i=1; i < 255; i++)
    {
        x <<= 1;
        // field generator polynomial is p(x) = x^8 + x^4 + x^3 + x^2 + 1
        if ((x & this.size) != 0)
            x ^= (this.size | 0x1D); // x^8 = x^4 + x^3 + x^2 + 1  ==> 0001_1101
        this.exp[i] = x;
        this.log[x] = i;
    }
    for (var i=255; i < 512; i++)
        this.exp[i] = this.exp[i-255];

    this.mask = function()
    {
        return this.size-1;
    }

    this.get_exp = function(y)
    {
        return this.exp[y];
    }

    this.mul = function(x, y)
    {
        if ((x==0) || (y==0))
            return 0;
        return this.exp[this.log[x]+this.log[y]];
    }

    this.div = function(x, y)
    {
        if (y==0)
            throw "Divided by zero! Blackhole created.. ";
        if (x==0)
            return 0;
        return this.exp[this.log[x]+255-this.log[y]];
    }
}

var GaloisPolynomial = function(coefficients, f) {
    this.coefficients = coefficients;
    this.f= f;
    if (f == null)
	throw "Null Galois Field!";

    if (this.coefficients.length > this.f.size)
        throw "Polynomial order must be less than or equal to the degree of the Galois field. " + this.coefficients.length + " !> " + this.f.size;

    this.order = function()
    {
        return this.coefficients.length;
    }

    this.eval = function(x)
    {
        var y = this.coefficients[0];
        for (var i=1; i < this.coefficients.length; i++)
            y = this.f.mul(y, x) ^ this.coefficients[i];
        return y;
    }

    // Uint8 -> GaloisPolynomial
    this.scale = function(x)
    {
        const res = new Uint8Array(this.coefficients.length);
        for (var i=0; i < res.length; i++)
            res[i] = this.f.mul(x, this.coefficients[i]);
        return new GaloisPolynomial(res, this.f);
    }

    // GaloisPolynomial -> GaloisPolynomial
    this.add = function(other)
    {
        const res = new Uint8Array(Math.max(this.order(), other.order()));
        for (var i=0; i < this.order(); i++)
            res[i + res.length - this.order()] = this.coefficients[i];
        for (var i=0; i < other.order(); i++)
            res[i + res.length - other.order()] ^= other.coefficients[i];
        return new GaloisPolynomial(res, this.f);
    }

    // GaloisPolynomial -> GaloisPolynomial
    this.mul = function(other)
    {
        const res = new Uint8Array(this.order() + other.order() - 1);
        for (var i=0; i < this.order(); i++)
            for (var j=0; j < other.order(); j++)
                res[i+j] ^= this.f.mul(this.coefficients[i], other.coefficients[j]);
        return new GaloisPolynomial(res, this.f);
    }

    // Uint8 -> GaloisPolynomial
    this.append = function(x)
    {
        const res = new Uint8Array(this.coefficients.length+1);
	for (var i=0; i < this.coefficients.length; i++)
	    res[i] = this.coefficients[i];
        res[res.length-1] = x;
        return new GaloisPolynomial(res, this.f);
    }
}

// (int, GaloisField) -> GaloisPolynomial
var generators = {};
GaloisPolynomial.generator = function(nECSymbols, f)
{
    if (generators[nECSymbols] != null)
	return generators[nECSymbols];
    const one = new Uint8Array(1);
    one[0] = 1;
    var g = new GaloisPolynomial(one, f);
    for (var i=0; i < nECSymbols; i++) {
	var multiplicand = new Uint8Array(2);
	multiplicand[0] = 1;
	multiplicand[1] = f.get_exp(i);
	
        g = g.mul(new GaloisPolynomial(multiplicand, f));
    }
    generators[nECSymbols] = g;
    return g;
}

// (Uint8Array, int, GaloisField) -> Uint8Array
GaloisPolynomial.encode = function(input, nEC, f)
{
    var gen = GaloisPolynomial.generator(nEC, f);
    var res = new Uint8Array(input.length + nEC);
    for (var i=0; i < input.length; i++)
	res[i] = input[i];
    for (var i=0; i < input.length; i++)
    {
        var c = res[i];
        if (c != 0)
            for (var j=0; j < gen.order(); j++)
                res[i+j] ^= f.mul(gen.coefficients[j], c);
    }
    for (var i=0; i < input.length; i++)
	res[i] = input[i];
    return res;
}

// -> Uint8Array
GaloisPolynomial.syndromes = function(input, nEC, f)
{
    const res = new Uint8Array(nEC);
    const poly = new GaloisPolynomial(input, f);
    for (var i=0; i < nEC; i++)
        res[i] = poly.eval(f.get_exp(i));
    return res;
}

// (Uint8Array, Uint8Array, Int[], GaloisField) -> ()
GaloisPolynomial.correctErrata = function(input, synd, pos, f)
{
    if (pos.length == 0)
        return;
    var one = new Uint8Array(1);
    one[0] = 1;
    var q = new GaloisPolynomial(one, f);
    for (var j=0; j < pos.length; j++)
    {
	var i = pos[j];
        var x = f.get_exp(input.length - 1 - i);
        q = q.mul(GaloisPolynomial.create([x, 1], f));
    }
    var t = new Uint8Array(pos.length);
    for (var i=0; i < t.length; i++)
        t[i] = synd[t.length-1-i];
    var p = new GaloisPolynomial(t, f).mul(q);
    t = new Uint8Array(pos.length);
    for (var i=0; i < t.length; i++)
	t[i] = p.coefficients[i + p.order()-t.length];
    p = new GaloisPolynomial(t, f);
    t = new Uint8Array((q.order()- (q.order() & 1))/2);
    for (var i=q.order() & 1; i < q.order(); i+= 2)
        t[(i/2)|0] = q.coefficients[i];
    var qprime = new GaloisPolynomial(t,f);
    for (var j=0; j < pos.length; j++)
    {
	var i = pos[j];
        var x = f.get_exp(i + f.size - input.length);
        var y = p.eval(x);
        var z = qprime.eval(f.mul(x, x));
        input[i] ^= f.div(y, f.mul(x, z));
    }
}

// (Int[], int, GaloisField) -> Int[]
GaloisPolynomial.findErrors = function(synd, nmess, f)
{
    var errPoly = GaloisPolynomial.create([1], f);
    var oldPoly = GaloisPolynomial.create([1], f);
    for (var i=0; i < synd.length; i++)
    {
        oldPoly = oldPoly.append(0);
        var delta = synd[i];
        for (var j=1; j < errPoly.order(); j++)
            delta ^= f.mul(errPoly.coefficients[errPoly.order() - 1 - j], synd[i - j]);
        if (delta != 0)
        {
            if (oldPoly.order() > errPoly.order())
            {
                var newPoly = oldPoly.scale(delta);
                oldPoly = errPoly.scale(f.div(1, delta));
                errPoly = newPoly;
            }
            errPoly = errPoly.add(oldPoly.scale(delta));
        }
    }
    var errs = errPoly.order()-1;
    if (2*errs > synd.length)
        throw "Too many errors to correct! ("+errs+")";
    var errorPos = [];
    for (var i=0; i < nmess; i++)
        if (errPoly.eval(f.get_exp(f.size - 1 - i)) == 0)
            errorPos.push(nmess - 1 - i);
    if (errorPos.length != errs)
        throw "couldn't find error positions! ("+errorPos.length+"!="+errs+") ( missing fragments)";
    return errorPos;
}

// (Uint8Array, int, GaloisField) -> Uint8Array
GaloisPolynomial.decode = function(message, nec, f)
{
    var synd = GaloisPolynomial.syndromes(message, nec, f);
    var max = 0;
    for (var j=0; j < synd.length; j++)
        if (synd[j] > max)
            max = synd[j];
        if (max == 0)
            return message;
    var errPos = GaloisPolynomial.findErrors(synd, message.length, f);
    GaloisPolynomial.correctErrata(message, synd, errPos, f);
    return message;
}
GaloisPolynomial.create = function(coeffs, f) {
    const c = new Uint8Array(coeffs.length);
    for (var i=0; i < coeffs.length; i++)
	c[i] = coeffs[i];
    return new GaloisPolynomial(c, f);
}

    var f = new Galois();
    
    // (Uint8Array, int, int)-> Uint8Array
    exports.split = function(ints, originalBlobs, allowedFailures)
    {
        var n = originalBlobs + allowedFailures*2;
        var bouts = [];
        for (var i=0; i < n; i++)
            bouts.push(new ByteArrayOutputStream((symbolSize*ints.length/inputSize)|0));
        var encodeSize = ((f.size/n)|0)*n;
        var inputSize = encodeSize*originalBlobs/n;
        var nec = encodeSize-inputSize;
        var symbolSize = inputSize/originalBlobs;
        if (symbolSize * originalBlobs != inputSize)
            throw "Bad alignment of bytes in chunking. "+inputSize+" != "+symbolSize+" * "+ originalBlobs;

        for (var i=0; i < ints.length; i += inputSize)
        {
            var copy = ints.subarray(i, i + inputSize);
            var encoded = GaloisPolynomial.encode(copy, nec, f);
            for (var j=0; j < n; j++)
            {
                bouts[j].write(encoded, j*symbolSize, symbolSize);
            }
        }

        var res = [];
        for (var i=0; i < n; i++)
            res.push(bouts[i].toByteArray());
        return res;
    }

    // (Uint8Array[], int, int, int) -> Uint8Array
    exports.recombine = function(encoded, truncateTo, originalBlobs, allowedFailures)
    {
        const n = originalBlobs + allowedFailures*2;
        const encodeSize = ((f.size/n)|0)*n;
        const inputSize = encodeSize*originalBlobs/n;
        const nec = encodeSize-inputSize;
        const symbolSize = inputSize/originalBlobs;
        const tbSize = encoded[0].length;

        const res = new ByteArrayOutputStream();
	var bout = new ByteArrayOutputStream(symbolSize * n);
        for (var i=0; i < tbSize; i += symbolSize)
        {
            // take a symbol from each stream
            for (var j=0; j < n; j++)
                bout.write(encoded[j], i, symbolSize);
            var decodedInts = GaloisPolynomial.decode(bout.toByteArray(), nec, f);
	    bout.reset();
            res.write(decodedInts, 0, inputSize);
        }
        return res.toByteArray().subarray(0, truncateTo);
    }
}(typeof module !== 'undefined' && module.exports ? module.exports : (window.erasure = window.erasure || {})));
