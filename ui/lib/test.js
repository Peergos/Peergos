// overwrite this function to speed up scrypt for testing purposes
function fastgenerateKeyPairs(username, password, cb) {
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

testErasure = function() {
    const raw = new ByteArrayOutputStream();
    const template = nacl.util.decodeUTF8("Hello secure cloud! Goodbye NSA!");
    for (var i = 0; i < Chunk.MAX_SIZE / 32; i++)
        raw.write(template);

    const original = raw.toByteArray();

    const t1 = Date.now();
    var bfrags = Erasure.split(original, EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);
    const t2 = Date.now();
    console.log("Erasure encode took "+ (t2-t1) + " mS"); 

    var decoded = Erasure.recombine(bfrags, Chunk.MAX_SIZE, EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);
    const t3 = Date.now();
    if (!arraysEqual(original, decoded))
	throw "Decoded contents different from original!";

    console.log("Erasure decode took "+ (t3-t2) + " mS"); 
}
//doErasure = true;
//testErasure();
