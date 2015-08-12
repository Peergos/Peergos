// overwrite this function to speed up scrypt for testing purposes
function generateKeyPairs(username, password, cb) {
    var hash = UserPublicKey.hash(nacl.util.decodeUTF8(password));
    salt = nacl.util.decodeUTF8(username)
    
    return new Promise(function(resolve, reject) {
	scrypt(hash, salt, 1, 8, 64, 1000, function(keyBytes) {
	    var bothBytes = nacl.util.decodeBase64(keyBytes);
	    var signBytes = bothBytes.subarray(0, 32);
	    var boxBytes = bothBytes.subarray(32, 64);
	    resolve(new User(nacl.sign.keyPair.fromSeed(signBytes), nacl.box.keyPair.fromSecretKey(new Uint8Array(boxBytes))));
	}, 'base64');
    });
}

testErasure = function(original) {
    const t1 = Date.now();
    var bfrags = erasure.split(original, EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);
    const t2 = Date.now();
    console.log("Erasure encode took "+ (t2-t1) + " mS");
    if (document.getElementById("encode") != null)
	document.getElementById("encode").innerHTML = "Encode took "+(t2-t1)+"mS to generate "+bfrags.length + " fragments";
    var decoded = erasure.recombine(bfrags, Chunk.MAX_SIZE, EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);
    if (decoded.length > original.length)
	decoded = decoded.subarray(0, original.length);
    const t3 = Date.now();
    
    if (!arraysEqual(original, decoded))
	throw "Decoded contents different from original!";
    if (document.getElementById("decode") != null)
	document.getElementById("decode").innerHTML = "Decode took "+(t3-t2)+"mS";
    console.log("Erasure decode took "+ (t3-t2) + " mS"); 
}

testChunkErasure = function() {
    const raw = new Uint8Array(5*1024*1024);
    for (var i = 0; i < raw; i++)
        raw[i] = i & 0xff;

    testErasure(raw);
}

testSmallFileErasure = function() {
    const size = 10*1024 + 17;
    const raw = new Uint8Array(size);
    for (var i=0; i < raw.length; i++)
	raw[i] = i & 0xff;
    testErasure(raw);
}

//testSmallFileErasure();
testChunkErasure();
