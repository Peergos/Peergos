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
    var blob = new Blob(["var window = {};importScripts('https://localhost:8000/scripts/erasure.js');"
			 +"self.addEventListener('message', function(e) {"
			 +"var data = e.data;"
			 +"switch (data.cmd) {"
			 +"  case 'encode':"
			 +"    var bfrags = window.erasure.split(data.original, 40, 10);"
			 +"    self.postMessage({cmd:data.cmd,bfrags:bfrags,start:data.start});"
			 +"    break;"
			 +"  case 'decode':"
			 +"    var decoded = window.erasure.recombine(data.bfrags, 5*1024*1024, 40, 10);"
			 +"    self.postMessage({cmd:data.cmd,decoded:decoded,start:data.start});"
			 +"    self.close();"
			 +"    break;"
			 +"  default:"
			 +"    self.postMessage('Unknown command: ' + data.cmd);"
			 +"};"
			 +"}, false);"]);
    // Obtain a blob URL reference to our worker 'file'.
    var blobURL = window.URL.createObjectURL(blob);
    var worker = new Worker(blobURL);
    var prom = new Promise(function(resolve, reject){
	worker.onmessage = function(e) {
	    var data = e.data;
	    switch (data.cmd) {
	    case 'encode':
		var bfrags = data.bfrags;
		const t2 = Date.now();
		console.log("Erasure encode took "+ (t2-data.start) + " mS");
		if (document.getElementById("encode") != null)
		    document.getElementById("encode").innerHTML = "Encode took "+(t2-data.start)+"mS to generate "+bfrags.length + " fragments";
		for (var i=0; i < errors; i++)
		    bfrags[i] = new Uint8Array(bfrags[i].length);
		worker.postMessage({cmd:'decode',bfrags:bfrags,start:t2});
		break;
	    case 'decode':
		var decoded = data.decoded;
		if (decoded.length > original.length)
		    decoded = decoded.subarray(0, original.length);
		const t3 = Date.now();
		if (!arraysEqual(original, decoded))
		    throw "Decoded contents different from original!";
		if (document.getElementById("decode") != null)
		    document.getElementById("decode").innerHTML += "Decode with "+errors+" errors suceeded in "+(t3-data.start)+"mS</br>";
		console.log("Erasure decode took "+ (t3-data.start) + " mS");
		resolve(true);
		break;
	    }
	};
	worker.postMessage({cmd:'encode',original:original,start:t1});
    });
    return prom;
}

recurse = function(raw, err) {
    if (err < 11)
	return testErasure(raw, err).then(function(){recurse(raw, err+1);});
}

testChunkErasure = function() {
    const raw = new Uint8Array(5*1024*1024);
    for (var i = 0; i < raw.length; i++)
        raw[i] = i & 0xff;
    
    recurse(raw, 0);
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
