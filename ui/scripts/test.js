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

function arraysEqual(a, b) {
    if (a.length != b.length)
        return false;

    for (var i=0; i < a.length; i++)
        if (a[i] != b[i])
            return false;
    return true;
}

testErasure = function(original, errors) {
    const t1 = Date.now();
    var bfrags = erasure.split(original, 40, 10);
    const t2 = Date.now();
    console.log("Erasure encode took "+ (t2-t1) + " mS");
    if (document.getElementById("encode") != null)
	document.getElementById("encode").innerHTML = "Encode took "+(t2-t1)+"mS to generate "+bfrags.length + " fragments";
    for (var i=0; i < errors; i++)
	bfrags[i] = new Uint8Array(bfrags[i].length);
    var decoded = erasure.recombine(bfrags, 5*1024*1024, 40, 10);
    if (decoded.length > original.length)
	decoded = decoded.subarray(0, original.length);
    const t3 = Date.now();
    
    if (!arraysEqual(original, decoded))
	throw "Decoded contents different from original!";
    if (document.getElementById("decode") != null)
	document.getElementById("decode").innerHTML += "Decode with "+errors+" errors suceeded in "+(t3-t2)+"mS</br>";
    console.log("Erasure decode took "+ (t3-t2) + " mS");
    return Promise.resolve(true);
}

testChunkErasure = function() {
    const raw = new Uint8Array(5*1024*1024);
    for (var i = 0; i < raw.length; i++)
        raw[i] = i & 0xff;

    // create a worker to do the erasure coding
    var blob = new Blob(["onmessage = function(e) { postMessage(e.data); }"]);    
    // Obtain a blob URL reference to our worker 'file'.
    var blobURL = window.URL.createObjectURL(blob);
    var worker = new Worker(blobURL);
    worker.onmessage = function(e) {
	console.log(e.data);
	testErasure(e.data.input, e.data.errors);
	var nerrors = e.data.errors+1;
	if (nerrors < 12)
	    this.postMessage({errors:nerrors,input:e.data.input});
    };
    worker.postMessage({errors:0,input:raw});
}

testSmallFileErasure = function() {
    const size = 10*1024 + 17;
    const raw = new Uint8Array(size);
    for (var i=0; i < raw.length; i++)
	raw[i] = i & 0xff;
    for (var i=0; i < 1; i++)
	testErasure(raw, i);
}

//testSmallFileErasure();
testChunkErasure();
