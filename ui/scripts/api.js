if (typeof module !== "undefined")
    var nacl = require("./nacl");
if (typeof module !== "undefined")
    var erasure = require("./erasure");

function humanSort(l,r) {
    const comp = l.toLowerCase().localeCompare(r.toLowerCase());
    return comp != 0 ? comp : l.localeCompare(r);
};
// API for the User interface to use
/////////////////////////////

const EC_TYPE = 0xEC; // TweetNaCl elliptic curve type (25519)
function Ed25519PublicKey(key) {
    this.key = key;
    
    // Uint8Array => Uint8Array
    this.unsignMessage = function(sig) {
        return nacl.sign.open(sig, this.key);
    }

    // () => Uint8Array
    this.serialize = function(buf) {
	buf.writeByte(EC_TYPE);
	buf.write(this.key, 0, this.key.length);
    }.bind(this);
}
function Ed25519SecretKey(key) {
    this.key = key;
    
    // (Uint8Array => Uint8array)
    this.signMessage = function(input) {
        return nacl.sign(input, this.key);
    }
    
    // () => Uint8Array
    this.serialize = function(buf) {
	buf.writeByte(EC_TYPE);
	buf.write(this.key, 0, this.key.length);
    }.bind(this);
}
function Curve25519PublicKey(key) {
    this.key = key;
    
    // (Uint8Array, SecretBoxingKey -> Uint8Array)
    this.encryptMessageFor = function(input, secretKey) {
        var nonce = createNonce();
        return concat(nacl.box(input, nonce, this.key, secretKey.key), nonce);
    }
    
    // () => Uint8Array
    this.serialize = function(buf) {
	buf.writeByte(EC_TYPE);
	buf.write(this.key, 0, this.key.length);
    }.bind(this);
}
function Curve25519SecretKey(key) {
    this.key = key;
    
    // (Uint8Array, PublicBoxingKey -> Uint8Array)
    this.decryptMessage = function(cipher, them) {
        var nonce = slice(cipher, cipher.length-24, cipher.length);
        cipher = slice(cipher, 0, cipher.length-24);
        return nacl.box.open(cipher, nonce, them.key, this.key);
    }

    // () => Uint8Array
    this.serialize = function(buf) {
	buf.writeByte(EC_TYPE);
	buf.write(this.key, 0, this.key.length);
    }.bind(this);
}
function PublicSigningKey() {
}
PublicSigningKey.deserialize = function(din) {
    var type = 0xff & din.readByte();
    if (type == EC_TYPE) {
	return new Ed25519PublicKey(din.read(32));
    }
    else
	throw "Unknown Public Signing Key type: "+type;
}
function SecretSigningKey() {
}
SecretSigningKey.deserialize = function(din) {
    var type = 0xff & din.readByte();
    if (type == EC_TYPE) {
	return new Ed25519SecretKey(din.read(64));
    }
    else
	throw "Unknown Secret Signing Key type: "+type;
}
function PublicBoxingKey() {
}
PublicBoxingKey.deserialize = function(din) {
    var type = 0xff & din.readByte();
    if (type == EC_TYPE) {
	return new Curve25519PublicKey(din.read(32));
    }
    else
	throw "Unknown Public Boxing Key type: "+type;
}
function SecretBoxingKey() {
}
SecretBoxingKey.deserialize = function(din) {
    var type = 0xff & din.readByte();
    if (type == EC_TYPE) {
	return new Curve25519PublicKey(din.read(32));
    }
    else
	throw "Unknown Secret Boxing Key type: "+type;
}

/////////////////////////////
// UserPublicKey methods
function UserPublicKey(publicSignKey, publicBoxKey) {
    this.pSignKey = publicSignKey;
    this.pBoxKey = publicBoxKey;

    // (Uint8Array, SecretBoxingKey -> Uint8Array)
    this.encryptMessageFor = function(input, from) {
        return this.pBoxKey.encryptMessageFor(input, from);
    }
    
    // Uint8Array => boolean
    this.unsignMessage = function(sig) {
        return this.pSignKey.unsignMessage(sig);
    }

    // (ByteArrayOutputStream) => ()
    this.serialize = function(buf) {
	this.pSignKey.serialize(buf);
	this.pBoxKey.serialize(buf);
    }.bind(this);

    this.getPublicKeys = function() {
	var buf = new ByteArrayOutputStream();
	this.pSignKey.serialize(buf);
	this.pBoxKey.serialize(buf);
	return buf.toByteArray();
    }.bind(this);
}
//Uint8Array => UserPublicKey
UserPublicKey.fromPublicKeys = function(both) {
    if (both.length == 0)
	throw "Null keys returned";
    var din = new ByteArrayInputStream(both);
    return UserPublicKey.deserialize(din);
}
UserPublicKey.deserialize = function(din) {
    var pSign = PublicSigningKey.deserialize(din);
    var pBox = PublicBoxingKey.deserialize(din);
    return new UserPublicKey(pSign, pBox);
}
UserPublicKey.createNull = function() {
    return new UserPublicKey(new Ed25519PublicKey(new Uint8Array(32)), new Curve25519PublicKey(new Uint8Array(32)));
}

UserPublicKey.HASH_BYTES = 34;
// Uint8Array => Uint8Array
UserPublicKey.hash = function(arr) {
    const hash = sha256(arr);
    const multihash = new Uint8Array(hash.length+2);
    multihash[0] = 0x12;
    multihash[1] = 0x20;
    for(var i=0; i < hash.length; i++) multihash[i+2] = hash[i];

    return multihash;
}

function UsernameClaim(username, expiry, signedContents) {
    this.username = username;
    this.expiry = expiry;
    this.signedContents = signedContents;

    this.toByteArray = function() {
	const dout = new ByteArrayOutputStream();
	dout.writeArray(this.signedContents);
	return dout.toByteArray();
    }.bind(this);
}
UsernameClaim.fromByteArray = function(owner, raw) {
    const buf = new ByteArrayInputStream(raw);
    var signed = buf.readArray();
    var unsigned = owner.unsignMessage(signed);
    const bin = new ByteArrayInputStream(unsigned);
    var username = bin.readString();
    var expiryDate = bin.readString();
    return new UsernameClaim(username, expiryDate, signed);
}
UsernameClaim.create = function(username, user, expiryDate) {
    var bout = new ByteArrayOutputStream();
    bout.writeString(username);
    bout.writeString(expiryDate);
    var payload = bout.toByteArray();
    var signed = user.signMessage(payload);
    return new UsernameClaim(username, expiryDate, signed);
}

function UserPublicKeyLink(owner, claim, keyChangeProof) {
    this.owner = owner;
    this.claim = claim;
    this.keyChangeProof = keyChangeProof;

    this.toByteArray = function() {
	var dout = new ByteArrayOutputStream();
	dout.writeArray(this.claim.toByteArray());
	dout.writeByte(this.keyChangeProof == null ? 0 : 1);
	if (this.keyChangeProof != null)
	    dout.writeArray(this.keyChangeProof);
	return dout.toByteArray();
    }.bind(this);
}
UserPublicKeyLink.fromByteArray = function(raw) {
    const buf = new ByteArrayInputStream(raw);
    const proof = UsernameClaim.fromByteArray(buf.readArray());
    const hasLink = buf.readByte();
    const link = hasLink ? buf.readArray : null;
    return new UserPublicKeyLink(proof, link);
}
UserPublicKeyLink.createInitial = function(user, username, expiry) {
    // sign new claim to username, with provided expiry
    const newClaim = UsernameClaim.create(username, user, expiry);
    
    return [new UserPublicKeyLink(user.toUserPublicKey(), newClaim)];
}
UserPublicKeyLink.createChain = function(oldUser, newUser, username, expiry) {
    // sign new claim to username, with provided expiry
    const newClaim = UsernameClaim.create(username, newUser, expiry);
    
    // sign new keys with old
    const link = oldUser.signMessage(newUser.toUserPublicKey().serialize());
    
    // create link from old that never expires
    const fromOld = new UserPublicKeyLink(oldUser.toUserPublicKey(), UsernameClaim.create(username, oldUser, "+999999999-12-31"), link);
    
    return [fromOld, new UserPublicKeyLink(newUser.toUserPublicKey(), newClaim)];
}


function createNonce(){
    return window.nacl.randomBytes(24);
}

/////////////////////////////
// User methods
// (string, string, (User -> ())
function generateKeyPairs(username, password) {
    var hash = sha256(nacl.util.decodeUTF8(password));
    var salt = nacl.util.decodeUTF8(username)
    
    return new Promise(function(resolve, reject) {
        scrypt(hash, salt, 17, 8, 96, 1000, function(keyBytes) {
            var bothBytes = nacl.util.decodeBase64(keyBytes);
            var signBytes = bothBytes.subarray(0, 32);
            var boxBytes = bothBytes.subarray(32, 64);
	    var rootKeyBytes = bothBytes.subarray(64, 96);
	    var sPair = nacl.sign.keyPair.fromSeed(signBytes);
	    var bPair = nacl.box.keyPair.fromSecretKey(new Uint8Array(boxBytes));
	    var pSignKey = new Ed25519PublicKey(sPair.publicKey);
	    var pBoxKey = new Curve25519PublicKey(bPair.publicKey);
	    var sSignKey = new Ed25519SecretKey(sPair.secretKey);
	    var sBoxKey = new Curve25519SecretKey(bPair.secretKey);
            resolve({
		user:new User(pSignKey, pBoxKey, sSignKey, sBoxKey),
		root:new SymmetricKey(new Uint8Array(rootKeyBytes), 1)
	    });
        }, 'base64');
    });
}

function User(publicSignKey, publicBoxKey, secretSignKey, secretBoxKey) {
    UserPublicKey.call(this, publicSignKey, publicBoxKey);
    this.sSignKey = secretSignKey;
    this.sBoxKey = secretBoxKey;
    
    // (Uint8Array => Uint8array)
    this.signMessage = function(input) {
        return this.sSignKey.signMessage(input);
    }.bind(this);
    
    // (Uint8Array, PublicBoxingKey) -> Uint8Array)
    this.decryptMessage = function(cipher, them) {
        return this.sBoxKey.decryptMessage(cipher, them);
    }.bind(this);

    // ByteArrayOutputStream -> ()
    this.serialize = function(buf) {
	this.sSignKey.serialize(buf);
	this.sBoxKey.serialize(buf);
	this.pSignKey.serialize(buf);
	this.pBoxKey.serialize(buf);
    }.bind(this);

    this.toUserPublicKey = function() {
	return new UserPublicKey(this.pSignKey, this.pBoxKey);
    }
}

User.deserialize = function(din) {
    var sSignKey = SecretSigningKey.deserialize(din);
    var sBoxKey = SecretBoxingKey.deserialize(din);
    var pSignKey = PublicSigningKey.deserialize(din);
    var pBoxKey = PublicBoxingKey.deserialize(din);
    return new User(pSignKey, pBoxKey, sSignKey, sBoxKey);
}

User.random = function() {
    var secretBoxKey = window.nacl.randomBytes(32);
    var signSeed = window.nacl.randomBytes(32);
    var signPair = nacl.sign.keyPair.fromSeed(signSeed);
    var boxPair = nacl.box.keyPair.fromSecretKey(new Uint8Array(secretBoxKey));
    var pSignKey = new Ed25519PublicKey(signPair.publicKey);
    var pBoxKey = new Curve25519PublicKey(boxPair.publicKey);
    var sSignKey = new Ed25519SecretKey(signPair.secretKey);
    var sBoxKey = new Curve25519SecretKey(boxPair.secretKey);
    return new User(pSignKey, pBoxKey, sSignKey, sBoxKey);
}

/////////////////////////////
// SymmetricKey methods

function SymmetricKey(key, type) {
    if (key.length != nacl.secretbox.keyLength)
	throw "Invalid symmetric key: "+key;
    this.key = key;
    // only current type is 1 = TweetNaCl 256 bit symmetric key
    this.type = type;

    // (Uint8Array, Uint8Array[24]) => Uint8Array
    this.encrypt = function(data, nonce) {
	return nacl.secretbox(data, nonce, this.key);
    }

    // (Uint8Array, Uint8Array) => Uint8Array
    this.decrypt = function(cipher, nonce) {
	return nacl.secretbox.open(cipher, nonce, this.key);
    }

    // () => Uint8Array
    this.createNonce = function() {
	return nacl.randomBytes(24);
    }

    this.serialize = function() {
	var buf = new ByteArrayOutputStream();
	buf.writeByte(this.type);
	buf.write(this.key, 0, this.key.length);
	return buf.toByteArray();
    }.bind(this);
}
SymmetricKey.NONCE_BYTES = 24;
SymmetricKey.random = function() {
    return new SymmetricKey(nacl.randomBytes(32), 1);
}
SymmetricKey.createNull = function() {
    return new SymmetricKey(new Uint8Array(32), 1);
}
SymmetricKey.deserialize = function(raw) {
    const buf = new ByteArrayInputStream(raw);
    const type = buf.readByte();
    if (type != 1)
	throw "Unknown SymmetricKey type: " + type;
    var key = buf.read(32);
    return new SymmetricKey(key, 1);
}


function FileProperties(name, size, modified, attr, thumbnail) {
    this.name = name;
    this.size = size;
    this.modified = modified;
    this.attr = attr; //  | HIDDEN
    if (thumbnail == null)
	thumbnail = new Uint8Array(0);
    this.thumbnail = thumbnail;

    this.serialize = function() {
        var buf = new ByteArrayOutputStream();
        buf.writeString(this.name);
        buf.writeDouble(this.size);
	buf.writeDouble(this.modified);
	buf.writeByte(this.attr);
	buf.writeArray(this.thumbnail);
        return buf.toByteArray();
    }.bind(this);

    this.getSize = function() {
        return size;
    }

    this.getModified = function() {
	return new Date(modified);
    }

    this.isHidden = function() {
	return this.attr & 1 != 0;
    }

    this.hasThumbnail = function() {
	return this.thumbnail.length > 0;
    }.bind(this);

    this.getThumbURL = function() {
	var blob = new Blob([this.thumbnail], {type: 'image/png'});
	return URL.createObjectURL(blob);
    }.bind(this);
}

FileProperties.deserialize = function(raw) {
    const buf = new ByteArrayInputStream(raw);
    var name = buf.readString();
    var size = buf.readDouble();
    var modified = buf.readDouble();
    var attr = buf.readByte();
    var thumb = buf.readArray();
    return new FileProperties(name, size, modified, attr, thumb);
}

function asyncErasureEncode(original, originalBlobs, allowedFailures) {
    var worker = new Worker("scripts/erasure.js");
    var prom = new Promise(function(resolve, reject){
	worker.onmessage = function(e) {
	    var bfrags = e.data;
	    resolve(bfrags);
	};
	worker.postMessage({original:original, originalBlobs:EncryptedChunk.ERASURE_ORIGINAL, allowedFailures:EncryptedChunk.ERASURE_ALLOWED_FAILURES});
    });
    return prom;
}

function Fragment(data) {
    this.data = data;

    this.getData = function() {
        return data;
    }
}
Fragment.SIZE = 128*1024;

function EncryptedChunk(encrypted) {
    this.auth = slice(encrypted, 0, window.nacl.secretbox.overheadLength);
    this.cipher = slice(encrypted, window.nacl.secretbox.overheadLength, encrypted.length);

    this.generateFragments = function() {
        return asyncErasureEncode(this.cipher, EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES).then(function(bfrags) {
            var frags = [];
            for (var i=0; i < bfrags.length; i++)
		frags[i] = new Fragment(bfrags[i]);
	    return Promise.resolve(frags);
	});
    }

    this.getAuth = function() {
        return this.auth;
    }

    this.decrypt = function(key, nonce) {
        return key.decrypt(concat(this.auth, this.cipher), nonce);
    }
}
EncryptedChunk.ERASURE_ORIGINAL = 40;
EncryptedChunk.ERASURE_ALLOWED_FAILURES = 10;

function Chunk(data, key) {
    this.data = data;
    this.key = key;
    this.nonce = window.nacl.randomBytes(SymmetricKey.NONCE_BYTES);
    this.mapKey = window.nacl.randomBytes(32);

    this.encrypt = function() {
        return new EncryptedChunk(key.encrypt(data, this.nonce));
    }
}
Chunk.MAX_SIZE = Fragment.SIZE*EncryptedChunk.ERASURE_ORIGINAL
// string, File, SymmetricKey, Location, SymmetricKey -> 
function FileUploader(name, file, key, parentLocation, parentparentKey, setProgressPercentage, fileProperties) {
    if (fileProperties == null)
	this.props = new FileProperties(name, file.size, Date.now(), 0);
    else
	this.props = fileProperties;
    if (key == null) key = SymmetricKey.random();

    // Process and upload chunk by chunk to avoid running out of RAM, in reverse order to build linked list
    this.nchunks = Math.ceil(file.size/Chunk.MAX_SIZE);
    
    this.file = file;
    this.key = key;
    this.parentLocation = parentLocation;
    this.parentparentKey = parentparentKey;
    this.setProgressPercentage = setProgressPercentage;

    this.uploadChunk = function(context, owner, writer, chunkIndex, file, nextLocation) {

	var that = this;
	return new Promise(function(resolve, reject) {
	    console.log("uploading chunk: "+chunkIndex + " of "+file.name);
	    var filereader = new FileReader();
	    filereader.file_name = file.name;
	    filereader.onload = function(){
		const data = new Uint8Array(this.result);
		var chunk = new Chunk(data, key)
		const encryptedChunk = chunk.encrypt();
		encryptedChunk.generateFragments().then(function(fragments){
                    console.log("Uploading chunk with %d fragments\n", fragments.length);
		    context.uploadFragments(fragments, owner, writer, chunk.mapKey, setProgressPercentage).then(function(hashes){
			const retriever = new EncryptedChunkRetriever(chunk.nonce, encryptedChunk.getAuth(), hashes, nextLocation);
			const metaBlob = FileAccess.create(chunk.key, that.props, retriever, parentLocation, parentparentKey);
			context.uploadChunk(metaBlob, owner, writer, chunk.mapKey, hashes).then(function() {
			    resolve(new Location(owner, writer, chunk.mapKey));
			});
		    });
		});
	    }
	    filereader.readAsArrayBuffer(file.slice(chunkIndex*Chunk.MAX_SIZE, Math.min((1+chunkIndex)*Chunk.MAX_SIZE, file.size)));
	}).then(function(nextL) {
	    if (chunkIndex > 0)
		return that.uploadChunk(context, owner, writer, chunkIndex-1, file, nextL, setProgressPercentage);
	    return Promise.resolve(nextL);
	});
    }.bind(this);
    
    this.upload = function(context, owner, writer) {
	const t1 = Date.now();
	return this.uploadChunk(context, owner, writer, this.nchunks-1, this.file, null).then(function(res) {
	    console.log("File encryption, erasure coding and upload took: " +(Date.now()-t1) + " mS");
	    return Promise.resolve(res);
	});
    }.bind(this);
}

function generateThumbnail(imageBlob, fileName) {
    return new Promise(function(resolve, reject) {
	var filereader = new FileReader();
	filereader.file_name = imageBlob.name;
	filereader.readAsArrayBuffer(imageBlob.slice(0, 20));
	filereader.onload = function(){
	    const data = new Uint8Array(this.result);
	    resolve(data);
	}
    }).then(function(data) {
	const BMP = new Uint8Array([66, 77]);
	const GIF = new Uint8Array([71, 73, 70]);
	const JPEG = new Uint8Array([255, 216]);
	const PNG = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]);
	if (!arraysEqual(data.slice(0, BMP.length), BMP) && !arraysEqual(data.slice(0, GIF.length), GIF) && !arraysEqual(data.slice(0, PNG.length), PNG) && !arraysEqual(data.slice(0, 2), JPEG))
	    return Promise.resolve(new Uint8Array(0));
	return new Promise(function(resolve, reject) {
	    var canvas = document.createElement('canvas');
	    var ctx = canvas.getContext('2d');
	    var img = new Image();
	    img.onload = function(){
		var w = 100, h = 100;
		canvas.width = w;
		canvas.height = h;
		ctx.drawImage(img,0,0,img.width, img.height, 0, 0, w, h);
		
		var b64Thumb = canvas.toDataURL().substring("data:image/png;base64,".length);
		resolve(nacl.util.decodeBase64(b64Thumb));
	    }
	    var url = URL.createObjectURL(imageBlob);
	    img.src = url;
	});
    });
}

/////////////////////////////
// Util methods

function slice(arr, start, end) {
    if (end < start)
        throw "negative slice size! "+start + " -> " + end;
    var r = new Uint8Array(end-start);
    for (var i=start; i < end; i++)
        r[i-start] = arr[i];
    return r;
}

function concat(a, b, c) {
    if (a instanceof Array) {
        var size = 0;
        for (var i=0; i < a.length; i++)
            size += a[i].length;
        var r = new Uint8Array(size);
        var index = 0;
        for (var i=0; i < a.length; i++)
            for (var j=0; j < a[i].length; j++)
                r[index++] = a[i][j];
        return r;
    }
    var r = new Uint8Array(a.length+b.length+(c != null ? c.length : 0));
    for (var i=0; i < a.length; i++)
        r[i] = a[i];
    for (var i=0; i < b.length; i++)
        r[a.length+i] = b[i];
    if (c != null)
        for (var i=0; i < c.length; i++)
            r[a.length+b.length+i] = c[i];
    return r;
}

function arraysEqual(a, b) {
    if (a.length != b.length)
        return false;

    for (var i=0; i < a.length; i++)
        if (a[i] != b[i])
            return false;
    return true;
}

function get(path, onSuccess, onError) {

    var request = new XMLHttpRequest();
    request.open("GET", path);

    request.onreadystatechange=function()
    {
        if (request.readyState != 4)
            return;

        if (request.status == 200) 
            onSuccess(request.response);
        else
            onError(request.status);
    }

    request.send();
}

function getProm(url) {
    return new Promise(function(resolve, reject) {
    var req = new XMLHttpRequest();
    req.open('GET', url);
    req.responseType = 'arraybuffer';

    req.onload = function() {
        // This is called even on 404 etc
        // so check the status
        if (req.status == 200) {
        resolve(new Uint8Array(req.response));
        }
        else {
        reject(Error(req.statusText));
        }
    };

    req.onerror = function() {
        reject(Error("Network Error"));
    };

    req.send();
    });
}

function postProm(url, data) {
    return new Promise(function(resolve, reject) {
    var req = new XMLHttpRequest();
    req.open('POST', window.location.origin + "/" + url);
    req.responseType = 'arraybuffer';

    req.onload = function() {
        // This is called even on 404 etc
        // so check the status
        if (req.status == 200) {
        resolve(new Uint8Array(req.response));
        }
        else {
        reject(Error(req.statusText));
        }
    };

    req.onerror = function() {
        reject(Error("Network Error"));
    };

    req.send(data);
    });
}

function DHTClient() {
    //
    //put
    //
    this.put = function(valueData, owner, linkHashes) {        
        var arrays = [valueData, owner];
        var buffer = new ByteArrayOutputStream();
        buffer.writeInt(0); // PUT Message
        for (var iArray=0; iArray < arrays.length; iArray++) 
            buffer.writeArray(arrays[iArray]);
	buffer.writeInt(linkHashes != null ? linkHashes.length : 0);
	if (linkHashes != null) {
	    for (var i=0; i < linkHashes.length; i++)
		buffer.writeArray(linkHashes[i]);
	}
        return postProm("dht/put", buffer.toByteArray()).then(function(resBuf){
            var stream = new ByteArrayInputStream(resBuf);
	    var res = stream.readInt();
            if (res == 1) {
		var key = stream.readArray();
		if (key.length == 0)
		    throw "Invalid hash returned by DHT (zero length)";
		return Promise.resolve(key);
	    }
            return Promise.reject("DHT put failed");
        });
    };
    //
    //get
    //
    this.get = function(keyData) {
	if (keyData.length == 0)
	    throw "Invalid hash: length = 0";
        var buffer = new ByteArrayOutputStream();
        buffer.writeInt(1); // GET Message
        buffer.writeArray(keyData);
        return postProm("dht/get", buffer.toByteArray()).then(function(res) {
            var buf = new ByteArrayInputStream(res);
            var success = buf.readInt();
            if (success == 1)
                return Promise.resolve({hash:keyData,data:buf.readArray()});
            return Promise.reject("Fragment download failed");
        });
    };
}

function CoreNodeClient() {
    //String -> fn- >fn -> void
    this.getPublicKey = function(username) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeString(username);
        return postProm("core/getPublicKey", buffer.toByteArray()).then(function(raw) {
	    var arr = new ByteArrayInputStream(raw).readArray();
	    var res = arr.length == 0 ? null : UserPublicKey.fromPublicKeys(arr);
	    if (res == null)
		return Promise.reject("No such user");
	    return Promise.resolve(res);
	});
    };
    
    //Uint8Array -> fn -> fn -> void
    this.getUsername = function(publicKey) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeArray(publicKey);
        return postProm("core/getUsername", buffer.toByteArray()).then(function(res) {
            const name = new ByteArrayInputStream(res).readString();
            return Promise.resolve(name);
        });
    };
    
    // () -> List<String>
    this.fetchUsernames = function() {
        return getProm("core/getAllUsernamesGzip").then(function(res) {
            const din = new ByteArrayInputStream(res);
	    var res = [];
	    for (;;) {
		var uname = din.readString();
		if (uname == "")
		    break;
		res.push(uname);
	    }
            return Promise.resolve(res);
        });
    };    
    
    //String -> Uint8Array -> Uint8Array -> fn -> fn ->  boolean 
    this.updateChain = function(username, chain) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeString(username);
        buffer.writeInt(chain.length);
	for (var i=0; i < chain.length; i++) {
            chain[i].owner.serialize(buffer);
            buffer.writeArray(chain[i].toByteArray());
	}
        return postProm("core/updateChain", buffer.toByteArray()).then(
	    function(res){
		return Promise.resolve(res[0]);
	    });
    };
    
    //Uint8Array -> Uint8Array -> fn -> fn -> void
    this.followRequest = function( target,  encryptedPermission) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeArray(target);
        buffer.writeArray(encryptedPermission);
        return postProm("core/followRequest", buffer.toByteArray()).then(function(res){
	    return Promise.resolve(res[0]==1);
	});
    };
    
    //String -> Uint8Array -> fn -> fn -> void
    this.getFollowRequests = function( user) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeArray(user);
        return postProm("core/getFollowRequests", buffer.toByteArray()).then(function(res) {
            var buf = new ByteArrayInputStream(res);
            var size = buf.readInt();
            var n = buf.readInt();
            var arr = [];
            for (var i=0; i < n; i++) {
                var t = buf.readArray();
                arr.push(t);
            }
            return Promise.resolve(arr);
        });
    };
    
    //Uint8Array -> Uint8Array -> Uint8Array -> fn -> fn -> void
    this.removeFollowRequest = function( target,  signedRequest) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeArray(target);
        buffer.writeArray(signedRequest);
        return postProm("core/removeFollowRequest", buffer.toByteArray()).then(function(res) {
            return Promise.resolve(new ByteArrayInputStream(res).readByte() == 1);
        });
    };

    //Uint8Array -> Uint8Array -> Uint8Array -> Uint8Array  -> Uint8Array -> fn -> fn -> void
    this.addMetadataBlob = function( owner,  encodedSharingPublicKey, sharingKeySignedPayload) {
	if (sharingKeySignedPayload.length != 64 + 2*34 + 2*4 && sharingKeySignedPayload.length  != 64 + 34 + 8)
	    throw "Invalid signed pair of hashes!";
        var buffer = new ByteArrayOutputStream();
        buffer.writeArray(owner);
        buffer.writeArray(encodedSharingPublicKey);
        buffer.writeArray(sharingKeySignedPayload);
        return postProm("core/addMetadataBlob", buffer.toByteArray()).then(function(res) {
            return Promise.resolve(new ByteArrayInputStream(res).readByte() == 1);
        });
    };
    
    //String -> Uint8Array -> Uint8Array  -> Uint8Array -> fn -> fn -> void
    this.removeMetadataBlob = function( owner,  writer,  mapKey) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeArray(owner.getPublicKeys());
        buffer.writeArray(writer.getPublicKeys());
        buffer.writeArray(writer.signMessage(mapKey));
        return postProm("core/removeMetadataBlob", buffer.toByteArray());
    };

    //writing key -> btree root hash
    this.getMetadataBlob = function(encodedSharingKey) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeArray(encodedSharingKey.getPublicKeys());
        return postProm("core/getMetadataBlob", buffer.toByteArray()).then(function(res) {
            var buf = new ByteArrayInputStream(res);
            return Promise.resolve(buf.readArray());
        });
    };
};

function BTree() {
    
    // encoded public key, mapkey, multihash
    this.put = function(sharingKey, mapKey, value) {
	if (value.length == 0)
	    throw "Invalid multihash in btree put: length = 0";
	var buffer = new ByteArrayOutputStream();
	buffer.writeInt(0); // PUT
        buffer.writeArray(sharingKey);
        buffer.writeArray(mapKey);
        buffer.writeArray(value);
        return postProm("btree/put", buffer.toByteArray()).then(function(resBuf){
            var stream = new ByteArrayInputStream(resBuf);
	    var res = stream.readInt();
            if (res == 1) {
		var newRootHash = stream.readArray();
		if (newRootHash.length == 0)
		    throw "Invalid hash returned from BTree put: length = 0";
		return Promise.resolve(newRootHash);
	    }
            return Promise.reject("BTree put failed");
        });
    }

    this.get = function(sharingKey, mapKey) {
	var buffer = new ByteArrayOutputStream();
	buffer.writeInt(1); // GET
        buffer.writeArray(sharingKey);
        buffer.writeArray(mapKey);
        return postProm("btree/get", buffer.toByteArray()).then(function(res) {
            var buf = new ByteArrayInputStream(res);
            var success = buf.readInt();
            if (success == 1) {
		var multihash = buf.readArray();
		if (multihash.length == 0)
		    throw "Invalid hash returned from BTree get("+bytesToHex(mapKey)+"): length = 0";
                return Promise.resolve(multihash);
	    }
            return Promise.reject("BTree get failed");
        });
    }

    this.remove = function(sharingKey, mapKey) {
	var buffer = new ByteArrayOutputStream();
	buffer.writeInt(2); // DELETE
        buffer.writeArray(sharingKey);
        buffer.writeArray(mapKey);
        return postProm("btree/delete", buffer.toByteArray()).then(function(res) {
            var buf = new ByteArrayInputStream(res);
            var success = buf.readInt();
            if (success == 1) {
		var multihash = buf.readArray();
		if (multihash.length == 0)
		    throw "Invalid hash returned from BTree remove: length = 0";
                return Promise.resolve(multihash);
	    }
            return Promise.reject("BTree delete failed");
        });
    }
}

function UserContext(username, user, rootKey, dhtClient,  corenodeClient) {
    this.username  = username;
    this.user = user;
    this.rootKey = rootKey;
    this.dhtClient = dhtClient;
    this.corenodeClient = corenodeClient;
    this.btree = new BTree();
    this.staticData = []; // array of map entry pairs
    this.rootNode = null;
    this.sharingFolder = null;
    this.usernames = [];

    this.init = function() {
	FileTreeNode.ROOT.clear();
	this.staticData = [];
	var context = this;
	return createFileTree().then(function (rootNode) {
	    context.rootNode = rootNode;
	    return rootNode.getChildren(context).then(function (children) {
		for (var i=0; i < children.length; i++) {
		    if (children[i].getFileProperties().name == context.username)
			return children[i].getChildren().then(function(ourdirs) {
			    for (var j=0; j < ourdirs.length; j++) {
				if (ourdirs[j].getFileProperties().name == "shared") {
				    context.sharingFolder = ourdirs[j];
				    return context.corenodeClient.fetchUsernames().then(function(unames){
					context.usernames = unames;
					return Promise.resolve(true);
				    });
				}
			    }
			    return Promise.reject("Couldn't find shared folder!");
			});
		}
		return Promise.reject("No root directory found!");
	    });
	});
    }.bind(this);

    this.getUsernames= function() {
        return this.usernames;
    }

    this.isRegistered = function() {
        return corenodeClient.getUsername(user.getPublicKeys()).then(function(res){
            return Promise.resolve(username == res);
        });
    }

    this.serializeStatic = function() {
        var buf = new ByteArrayOutputStream();
        buf.writeInt(this.staticData.length);
        for (var i = 0; i < this.staticData.length; i++)
            buf.writeArray(this.staticData[i][1].serializeAndSymmetricallyEncrypt(this.rootKey));
        return buf.toByteArray();
    }

    this.register = function() {
        console.log("claiming username: "+username);
	var now = new Date();
	// set claim expiry to two months from now
	now.setMonth(now.getMonth() + 2);
	var month = ""+now.getMonth();
	if (month.length == 1)
	    month = "0"+month;
	var day = ""+now.getDate();
	if (day.length == 1)
	    day = "0"+day;
	const expiry = now.getFullYear()+"-"+month+"-"+day; //YYYY-MM-DD
        const claimChain = UserPublicKeyLink.createInitial(user, username, expiry);
        return corenodeClient.updateChain(username, claimChain);
    }

    this.createEntryDirectory = function(directoryName) {
        var writer = User.random();
        var rootMapKey = window.nacl.randomBytes(32); // root will be stored under this in the core node
        var rootRKey = SymmetricKey.random();

        // and authorise the writer key
        const rootPointer = new ReadableFilePointer(this.user, writer, rootMapKey, rootRKey);
        const entry = new EntryPoint(rootPointer, this.username, [], []);
        
        return this.addToStaticDataAndCommit(entry).then(function(res) {
	    var root = DirAccess.create(rootRKey, new FileProperties(directoryName, 0, Date.now(), 0));
	    return this.uploadChunk(root, this.user, writer, rootMapKey).then(function(res) {
		if (res)
		    return Promise.resolve(new RetrievedFilePointer(rootPointer, root));
		return Promise.reject();
	    });
        }.bind(this));
    }

    var getSharingFolder = function() {
	return this.sharingFolder;
    }.bind(this);
    
    var getFriendRoots = function() {
	var ourname = this.username;
	return this.rootNode.getChildren(this).then(function (children) {
	    return Promise.resolve(children.filter(function(froot){return froot.getOwner() != ourname}));
	}.bind(this));
    }.bind(this);

    var getFollowers = function() {
	return getSharingFolder().getChildren(this).then(function(friendFolders){
	    return Promise.resolve(
                friendFolders.map(function(froot){
                    return froot.getFileProperties().name;
                }).sort(humanSort));
	});
    }.bind(this);

    var getFollowing = function() {
	var that = this;
	return getFriendRoots().then(function(friendRoots) {
	    return Promise.resolve(friendRoots.map(function(froot){return froot.getOwner()}).filter(function(name){return name != that.username;}));
	});
    }.bind(this)

    var getFollowerRoots = function() {
	return getSharingFolder().getChildren(this).then(function(friendFolders){
	    var res = {};
	    friendFolders.map(function(froot){return res[froot.getFileProperties().name] = froot;});
	    return Promise.resolve(res);
	});
    }.bind(this);

    this.getSocialState = function() {
	return this.getFollowRequests().then(function(pending) {
	    return getFollowerRoots().then(function(followerRoots) {
		return getFriendRoots().then(function(followingRoots) {
		    return Promise.resolve(new SocialState(pending, followerRoots, followingRoots));
		});
	    });
	});
    }.bind(this);
    
    this.sendInitialFollowRequest = function(targetUsername) {
	return this.sendFollowRequest(targetUsername, SymmetricKey.random());
    }

    // FollowRequest, boolean, boolean
    this.sendReplyFollowRequest = function(initialRequest, accept, reciprocate) {
	var context = this;
	var theirUsername = initialRequest.entry.owner;
	// if accept, create directory to share with them, note in entry points (they follow us)
	if (!accept) {
	    // send a null entry and null key (full rejection)

	    var buf = new ByteArrayOutputStream();
	    // write a null entry point
	    var entry = new EntryPoint(ReadableFilePointer.createNull(), context.username, [], []);
	    buf.writeArray(entry.serialize());
	    buf.writeArray(new Uint8Array(0)); // tell them we're not reciprocating
	    var plaintext = buf.toByteArray();
	    const targetUser = initialRequest.entry.pointer.owner;
	    // create a tmp keypair whose public key we can prepend to the request without leaking information
            var tmp = User.random();
            var payload = targetUser.encryptMessageFor(plaintext, tmp.sBoxKey);

            return corenodeClient.followRequest(initialRequest.entry.pointer.owner.getPublicKeys(), concat(tmp.pBoxKey, payload)).then(function(res) {
		// remove pending follow request from them
		return corenodeClient.removeFollowRequest(context.user.getPublicKeys(), context.user.signMessage(initialRequest.rawCipher));
	    });
	}
	return getSharingFolder().mkdir(theirUsername, context, initialRequest.key).then(function(friendRoot) {
	    // add a note to our static data so we know who we sent the read access to
	    const entry = new EntryPoint(friendRoot.readOnly(), context.username, [theirUsername], []);
            const targetUser = initialRequest.entry.pointer.owner;
	    return context.addToStaticDataAndCommit(entry).then(function(res) {
		// create a tmp keypair whose public key we can prepend to the request without leaking information
                var tmp = User.random();
		var buf = new ByteArrayOutputStream();
		buf.writeArray(entry.serialize());
		if (! reciprocate) {
		    buf.writeArray(new Uint8Array(0)); // tell them we're not reciprocating
		} else {
		    // if reciprocate, add entry point to their shared dirctory (we follow them) and then 
		    buf.writeArray(initialRequest.entry.pointer.baseKey.serialize()); // tell them we are reciprocating
		}
		var plaintext = buf.toByteArray();
                var payload = targetUser.encryptMessageFor(plaintext, tmp.sBoxKey);
		
		var resp = new ByteArrayOutputStream();
		resp.writeArray(tmp.getPublicKeys());
		resp.writeArray(payload);
                return corenodeClient.followRequest(initialRequest.entry.pointer.owner.getPublicKeys(), resp.toByteArray()).then(function(res) {
		    return context.addToStaticDataAndCommit(initialRequest.entry);
		});
	    }).then(function(res) {
		// remove original request
		return corenodeClient.removeFollowRequest(context.user.getPublicKeys(), context.user.signMessage(initialRequest.rawCipher));
	    });
	});
    }

    // string, RetrievedFilePointer, SymmetricKey
    this.sendFollowRequest = function(targetUsername, requestedKey) {
	var sharing = getSharingFolder();
	var that = this;
	return sharing.getChildren(this).then(function(children) {
	    var alreadyFollowed = false;
	    for (var i=0; i < children.length; i++)
		if (children[i].getFileProperties().name == targetUsername)
		    alreadyFollowed = true;
	    if (alreadyFollowed)
		return Promise.resolve(false);
	    // check for them not reciprocating
	    return getFollowing().then(function(following) {
		var alreadyFollowed = false;
		for (var i=0; i < following.length; i++)
		    if (following[i] == targetUsername)
			alreadyFollowed = true;
		if (alreadyFollowed)
		    return Promise.resolve(false);
		
		return that.corenodeClient.getPublicKey(targetUsername).then(function(targetUser) {
		    return sharing.mkdir(targetUsername, that).then(function(friendRoot) {
			
			// add a note to our static data so we know who we sent the read access to
			const entry = new EntryPoint(friendRoot.readOnly(), that.username, [targetUsername], []);
			return that.addToStaticDataAndCommit(entry).then(function(res) {
			    // send details to allow friend to follow us, and optionally let us follow them		
			    // create a tmp keypair whose public key we can prepend to the request without leaking information
			    var tmp = User.random();
			    var buf = new ByteArrayOutputStream();
			    buf.writeArray(entry.serialize());
			    buf.writeArray(requestedKey != null ? requestedKey.serialize() : new Uint8Array(0));
			    var plaintext = buf.toByteArray();
			    var payload = targetUser.encryptMessageFor(plaintext, tmp.sBoxKey);
			    
			    var res = new ByteArrayOutputStream();
			    res.writeArray(tmp.getPublicKeys());
			    res.writeArray(payload);
			    return corenodeClient.followRequest(targetUser.getPublicKeys(), res.toByteArray());
			});
		    });
		});
            });
	});
    }.bind(this);

    this.sendWriteAccess = function(targetUser) {
        // create sharing keypair and give it write access
        var sharing = User.random();
        var rootMapKey = window.nacl.randomBytes(32);

        // add a note to our static data so we know who we sent the private key to
        var friendRoot = new ReadableFilePointer(user, sharing, rootMapKey, SymmetricKey.random());
        return this.corenodeClient.getUsername(targetUser.getPublicKeys()).then(function(name) {
            const entry = new EntryPoint(friendRoot, this.username, [], [name]);
            return this.addToStaticDataAndCommit(entry).then(function(res) {
                // create a tmp keypair whose public key we can append to the request without leaking information
                var tmp = User.random();
                var payload = entry.serializeAndEncrypt(tmp, targetUser);
                return corenodeClient.followRequest(targetUser.getPublicKeys(), concat(tmp.pBoxKey, payload));
            });
        }.bind(this));
    }.bind(this);

    this.addToStaticData = function(entry) {
	for (var i=0; i < this.staticData.length; i++)
	    if (this.staticData[i][1].equals(entry))
		return Promise.resolve(true);
        this.staticData.push([entry.pointer.writer, entry]);
        return Promise.resolve(true);
    }.bind(this);

    this.addToStaticDataAndCommit = function(entry) {
	return this.addToStaticData(entry).then(function(res) {
	    return this.commitStaticData();
	}.bind(this));
    }.bind(this);

    this.commitStaticData = function() {
	var rawStatic = new Uint8Array(this.serializeStatic());
	return this.dhtClient.put(rawStatic, user.getPublicKeys()).then(function(blobHash){
	    return this.corenodeClient.getMetadataBlob(this.user).then(function(currentHash) {
		var bout = new ByteArrayOutputStream();
		bout.writeArray(currentHash);
		bout.writeArray(blobHash);
		var signed = this.user.signMessage(bout.toByteArray());
		return corenodeClient.addMetadataBlob(this.user.getPublicKeys(), this.user.getPublicKeys(), signed)
		    .then(function(added) {
			if (!added) {
			    console.log("Static data store failed.");
			    return Promise.resolve(false);
			}
			return Promise.resolve(true);
		    });
	    }.bind(this));
	}.bind(this));
    }.bind(this);

    this.removeFromStaticData = function(fileTreeNode) {
	var pointer = fileTreeNode.getPointer().filePointer;
	// find and remove matching entry point
	for (var i=0; i < this.staticData.length; i++)
	    if (this.staticData[i][1].pointer.equals(pointer)) {
		this.staticData.splice(i, 1);
		return this.commitStaticData();
	    }
	return Promise.resolve(true);
    }.bind(this);

    this.getFollowRequests = function() {
	var that = this;
        return corenodeClient.getFollowRequests(user.getPublicKeys()).then(function(reqs){
	    var all = reqs.map(decodeFollowRequest);
	    return getFollowerRoots().then(function(followerRoots) {
		var initialRequests = all.filter(function(freq){
		    if (followerRoots[freq.entry.owner] != null) {
			// delete our folder if they didn't reciprocate
			var ourDirForThem = followerRoots[freq.entry.owner];
			var ourKeyForThem = ourDirForThem.getKey().serialize();
			var keyFromResponse = freq.key == null ? null : freq.key.serialize();
			if (keyFromResponse == null || !arraysEqual(keyFromResponse, ourKeyForThem)) {
			    ourDirForThem.remove(that, getSharingFolder());
			    // remove entry point as well
			    that.removeFromStaticData(ourDirForThem);
			    // clear their response follow req too
			    corenodeClient.removeFollowRequest(that.user.getPublicKeys(), that.user.signMessage(freq.rawCipher));
			} else // add new entry to tree root
			    that.downloadEntryPoints([freq.entry]).then(function(treenode) {
				that.getAncestorsAndAddToTree(treenode, that);
			    });
			// add entry point to static data
			if (!arraysEqual(freq.entry.pointer.baseKey.serialize(), SymmetricKey.createNull().serialize()))
			    that.addToStaticDataAndCommit(freq.entry).then(function(res) {
				return corenodeClient.removeFollowRequest(that.user.getPublicKeys(), that.user.signMessage(freq.rawCipher));
			    });
			return false;
		    }
		    return followerRoots[freq.entry.owner] == null;
		});
		return Promise.resolve(initialRequests);
	    });
	});
    };

    var decodeFollowRequest = function(raw) {
        var buf = new ByteArrayInputStream(raw);
        var tmp = UserPublicKey.deserialize(new ByteArrayInputStream(buf.readArray()));
        var cipher = buf.readArray();
	var plaintext = user.decryptMessage(cipher, tmp.pBoxKey);
	var input = new ByteArrayInputStream(plaintext);
	var rawEntry = input.readArray();
	var rawKey = input.readArray();
        return new FollowRequest(rawEntry.length > 0 ? EntryPoint.deserialize(rawEntry) : null, 
				 rawKey.length > 0 ? SymmetricKey.deserialize(rawKey) : null, raw);
    }
    
    this.uploadFragment = function(f, targetUser) {
        return dhtClient.put(f.getData(), targetUser.getPublicKeys());
    }

    this.uploadFragments = function(fragments, owner, sharer, mapKey, setProgressPercentage) {
	// now upload fragments to DHT
        var futures = [];
        for (var i=0; i < fragments.length; i++){
            if(setProgressPercentage != null){
                if(uploadFragmentTotal != 0){
                    var percentage = parseInt(++uploadFragmentCounter / uploadFragmentTotal * 100);
                    setProgressPercentage(percentage);
                    //document.title = "Peergos Uploading: " + percentage + "%" ;  
                }
            }
            futures[i] = this.uploadFragment(fragments[i], owner);
        }
        // wait for all fragments to upload
        return Promise.all(futures);
    }.bind(this);

    this.uploadChunk = function(metadata, owner, sharer, mapKey, linkHashes) {
        var buf = new ByteArrayOutputStream();
        metadata.serialize(buf);
        var metaBlob = buf.toByteArray();
	const btree = this.btree;
        console.log("Storing metadata blob of " + metaBlob.length + " bytes. to mapKey: "+bytesToHex(mapKey));
	return this.dhtClient.put(metaBlob, owner.getPublicKeys(), linkHashes).then(function(blobHash){
	    return btree.put(sharer.getPublicKeys(), mapKey, blobHash).then(function(newBtreeRootCAS) {
		var msg = newBtreeRootCAS;
		var signed = sharer.signMessage(msg);
		return corenodeClient.addMetadataBlob(owner.getPublicKeys(), sharer.getPublicKeys(), signed)
		    .then(function(added) {
			if (!added) {
			    console.log("Meta blob store failed.");
			    return Promise.resolve(false);
			}
			// double check
		//	return corenodeClient.getMetadataBlob(sharer).then(function(treeroot) {
		//	    if (!arraysEqual(treeroot, newBtreeRoot))
		//		throw "returned tree root different to written one!" + bytesToHex(treeroot) + " != " + bytesToHex(newBtreeRoot);
		//	    return btree.get(sharer.getPublicKeys(), mapKey).then(function(returnedBlobHash) {
		//		if (!arraysEqual(returnedBlobHash, blobHash))
		//		    throw "different hash in btree get after btree put! " + bytesToHex(returnedBlobHash) + " != " + bytesToHex(blobHash);
				return Promise.resolve(true);
		//	    });
		//	});
		    });
	    });
	});
    }.bind(this);

    this.getStaticData = function() {
	return this.corenodeClient.getMetadataBlob(this.user).then(function(staticHash) {
	    return this.dhtClient.get(staticHash);
	}.bind(this));
    }.bind(this);

    this.getRoots = function() {
        const context = this;
        return this.getStaticData().then(function(raw) {
            var buf = new ByteArrayInputStream(raw.data);
            var count = buf.readInt();
            var res = [];
            for (var i=0; i < count; i++) {
                var entry = EntryPoint.symmetricallyDecryptAndDeserialize(buf.readArray(), context.rootKey);
                res.push(entry);
		this.addToStaticData(entry);
            }
	    
            return this.downloadEntryPoints(res);
        }.bind(this));
    }.bind(this);

    this.downloadEntryPoints = function(entries) {
	// download the metadata blobs for these entry points
        var proms = [];
        for (var i=0; i < entries.length; i++)
	    proms[i] = this.btree.get(entries[i].pointer.writer.getPublicKeys(), entries[i].pointer.mapKey).then(function(hash) {
		return dhtClient.get(hash);
	    });

        return Promise.all(proms).then(function(result) {
            var entryPoints = [];
            for (var i=0; i < result.length; i++) {
                if (result[i].data.byteLength > 8) {
                    entryPoints.push([entries[i], FileAccess.deserialize(result[i].data)]);
                } else {
                    // these point to removed directories
		}
            }
            return Promise.resolve(entryPoints);
	});
    }

    this.getTreeRoot = function() {
	return Promise.resolve(this.rootNode);
    }
    
    this.getUserRoot = function() {
        return this.getTreeRoot().then(function(root) {
            return root.getChildren().then(function(children) {
                if (children.length == 0)
                    throw "no children in user root!";
                const userRoots = children.filter(function(e) {
                    return e.getFileProperties().name == this.username;
                }.bind(this));
                if (userRoots.length != 1)
                    throw  "user has "+ userRoots.length +" roots!";
                return Promise.resolve(userRoots[0]);
            }.bind(this));
        }.bind(this));
    }.bind(this);

    this.getAncestorsAndAddToTree = function(treeNode, context) {
	try {
	    // don't need to add our own files this way, as we'll find them going down from our root
	    if (treeNode.getOwner() == context.username && !treeNode.isWritable())
		return Promise.resolve(true);
	    return treeNode.retrieveParent(context).then(function(parent) {
		if (parent == null)
		    return Promise.resolve(true);
		parent.addChild(treeNode);
		return context.getAncestorsAndAddToTree(parent, context);
	    });
	} catch (e) {
	    console.log(e);
	    return Promise.resolve(null);
	}
    }
    
    var createFileTree = function() {
	return this.getRoots().then(function(roots){
	    var entrypoints = roots.map(function(x) {return new FileTreeNode(new RetrievedFilePointer(x[0].pointer, x[1]), x[0].owner, x[0].readers, x[0].writers, x[0].pointer.writer);});
	    console.log("Entry points "+entrypoints);
	    var globalRoot = FileTreeNode.ROOT;
	    var proms = [];
	    
	    for (var i=0; i < entrypoints.length; i++) {
		var current = entrypoints[i];
		proms.push(this.getAncestorsAndAddToTree(current, this));
	    }
	    return Promise.all(proms).then(function(res) {
		return Promise.resolve(globalRoot);
	    });
	}.bind(this));
    }.bind(this);

// [SymmetricLocationLink], SymmetricKey
    this.retrieveAllMetadata = function(links, baseKey) {
        var proms = [];
        for (var i=0; i < links.length; i++) {
            var loc = links[i].targetLocation(baseKey);
            proms[i] = this.btree.get(loc.writer.getPublicKeys(), loc.mapKey).then(function(hash) {
		return dhtClient.get(hash);
	    });
        }
        return Promise.all(proms).then(function(rawBlobs) {
            var accesses = [];
            for (var i=0; i < rawBlobs.length; i++) {
                accesses[i] = [links[i].toReadableFilePointer(baseKey), rawBlobs[i].data.length > 0 ? FileAccess.deserialize(rawBlobs[i].data) : null];
            }
	    const res = [];
	    for (var i=0; i < accesses.length; i++)
		if (accesses[i][1] != null)
		    res.push(accesses[i]);
            return Promise.resolve(res);
        });
    }

    this.getMetadata = function(loc) {
	return this.btree.get(loc.writer.getPublicKeys(), loc.mapKey).then(function(blobHash) {
	    return this.dhtClient.get(blobHash).then(function(raw) {
		return Promise.resolve(FileAccess.deserialize(raw.data));
	    });
	}.bind(this));
    }.bind(this);

    this.downloadFragments = function(hashes, setProgressPercentage) {
        var result = {}; 
        result.fragments = [];
        result.nError = 0;
        
        var proms = [];
        for (var i=0; i < hashes.length; i++)
            proms.push(dhtClient.get(hashes[i]).then(function(val) {
                result.fragments.push(val);
                //console.log("Got Fragment.");
                if(setProgressPercentage != null){
                    if(downloadFragmentTotal != 0){
                        var percentage = parseInt(++downloadFragmentCounter / downloadFragmentTotal * 100);
                        setProgressPercentage(percentage);
                        //document.title = "Peergos Downloading: " + percentage + "%" ;  
                    }
                }
            }).catch(function() {
                result.nError++;
            }));

        return Promise.all(proms).then(function (all) {
            console.log("All done.");
            //if (result.fragments.length < nRequired)
            //    throw "Not enough fragments!";
            return Promise.resolve(result.fragments);
        });
    }
    this.unfollow = function(username) {
	console.log("Unfollowing: "+username);
	// remove entry point from static data
	var that = this;
	FileTreeNode.ROOT.getDescendentByPath("/"+username+"/shared/"+this.username).then(function(dir) {
	    // remove our static data entry storing that we've granted them access
	    that.removeFromStaticData(dir);
	});
	FileTreeNode.ROOT.getDescendentByPath("/"+username).then(function(dir) {
	    dir.remove(that, FileTreeNode.ROOT);
	});
    };
    this.removeFollower  = function(username) {
	console.log("Remove follower: " + username);
	var that = this;
	// remove /$us/shared/$them
	FileTreeNode.ROOT.getDescendentByPath("/"+this.username+"/shared/"+username).then(function(dir) {
	    dir.remove(that, getSharingFolder());
	    // remove our static data entry storing that we've granted them access
	    that.removeFromStaticData(dir);
	});
    }.bind(this);

    this.logout = function() {
	this.rootNode = null;
	FileTreeNode.ROOT = new FileTreeNode(null, null, [], [], null);
    }.bind(this);
}

//List[FollowRequest],  List[String], List[FileTreeNode] 
function SocialState(pending, followerRootMap, followingRoots) {
    this.pending = pending;
    this.followerRootMap = followerRootMap;
    this.followingRoots = followingRoots;
    this.followers = Object.keys(followerRootMap);
    this.sharedLocations = {};

    this.buildSharedLocations = function(followerRoot) {
	var kids = followerRoot.getChildrenLocations();
	for (var child in kids) {
	    var key = kids[child].toString();
	    if (this.sharedLocations[key] == null)
		this.sharedLocations[key] = [];
	    this.sharedLocations[key].push(followerRoot.getFileProperties().name);
	}
    }
    
    for (var follower in this.followers)
	this.buildSharedLocations(followerRootMap[this.followers[follower]]);

    this.getFollowingNames = function() {
	return this.followingRoots.map(function(froot){return froot.getOwner()});
    }
    
    this.getFollowers = function() {
        return this.followers;
    }

    this.sharedWith = function(location) {
	    const sharedWith = this.sharedLocations[location.toString()];
        return sharedWith == null ? [] : sharedWith;
    }

    // FileTreeNode, String, UserContext
    this.share = function(file, targetUsername, context) {
	return context.sharingFolder.getChildren(context).then(function(children) {
	    for (var i=0; i < children.length; i++)
		if (children[i].getFileProperties().name == targetUsername)
		    return children[i].addLinkTo(file, context);
	    return Promise.reject("Unknown friend: "+targetUsername);
	});
    }
}

function ReadableFilePointer(owner, writer, mapKey, baseKey) {
    this.owner = owner; //UserPublicKey
    this.writer = writer; //User / UserPublicKey
    this.mapKey = mapKey; //ByteArrayWrapper
    this.baseKey = baseKey; //SymmetricKey

    this.equals = function(that) {
	if (that == null)
	    return false;
	return arraysEqual(this.owner.getPublicKeys(), that.owner.getPublicKeys()) &&
	    arraysEqual(this.writer.getPublicKeys(), that.writer.getPublicKeys()) && 
	    arraysEqual(this.mapKey, that.mapKey) &&
	    arraysEqual(this.baseKey.serialize(), that.baseKey.serialize());
    }

    this.serialize = function() {
        var bout = new ByteArrayOutputStream();
        bout.writeArray(owner.getPublicKeys());
	bout.writeByte(this.isWritable() ? 1 : 0);
        writer.serialize(bout);
        bout.writeArray(mapKey);
        bout.writeArray(baseKey.serialize());
        return bout.toByteArray();
    }

    this.readOnly = function() {
	if (!this.isWritable())
	    return this;
	var publicWriter = UserPublicKey.fromPublicKeys(this.writer.getPublicKeys());
	return new ReadableFilePointer(this.owner, publicWriter, this.mapKey, this.baseKey);
    }

    this.isWritable = function() {
        return this.writer instanceof User;
    }

    this.toLink = function() {
	return "#" + Base58.encode(owner.getPublicKeys()) + "/" + Base58.encode(writer.getPublicKeys()) + "/" + Base58.encode(mapKey) + "/" + Base58.encode(baseKey.serialize());
    }
}
ReadableFilePointer.fromLink = function(keysString) {
    const split = keysString.split("/");
    const owner = UserPublicKey.fromPublicKeys(Base58.decode(split[0]));
    const writer = UserPublicKey.fromPublicKeys(Base58.decode(split[1]));
    const mapKey = Base58.decode(split[2]);
    const baseKey = SymmetricKey.deserialize(Base58.decode(split[3]));
    return new ReadableFilePointer(owner, writer, mapKey, baseKey);
}

ReadableFilePointer.deserialize = function(arr) {
    const bin = new ByteArrayInputStream(arr);
    const owner = bin.readArray();
    const hasPrivateKeys = bin.readByte() == 1;
    const writer = hasPrivateKeys ? User.deserialize(bin) : UserPublicKey.deserialize(bin);
    const mapKey = bin.readArray();
    const rootDirKeySecret = bin.readArray();
    return new ReadableFilePointer(UserPublicKey.fromPublicKeys(owner), writer, mapKey, SymmetricKey.deserialize(rootDirKeySecret));
}
ReadableFilePointer.createNull = function() {
    return new ReadableFilePointer(UserPublicKey.createNull(), UserPublicKey.createNull(), new Uint8Array(32), SymmetricKey.createNull());
}

// RetrievedFilePointer, string, [string], [string], UserPublicKey
function FileTreeNode(pointer, ownername, readers, writers, entryWriterKey) {
    var pointer = pointer == null ? null : pointer.withWriter(entryWriterKey); // O/W/M/K + FileAccess
    var children = [];
    var childrenByName = {};
    var owner = ownername;
    var readers = readers;
    var writers = writers;
    var entryWriterKey = entryWriterKey;

    this.equals = function(other) {
	if (other == null)
	    return false;
	return pointer.equals(other.getPointer());
    }

    this.hasChildByName = function(name) {
	return childrenByName[name] != null;
    }

    this.getPointer = function() {
	return pointer;
    }

    this.addChild = function(child) {
	var name = child.getFileProperties().name;
	if (childrenByName[name] != null) {
	    if (pointer != null) {
		throw "Child already exists with name: "+name;
	    } else
		return;
	}
	children.push(child);
	childrenByName[name] = child;
    }

    this.getDescendentByPath = function(path, context) {
	if (path == "")
	    return Promise.resolve(this);
	if (path.startsWith("/"))
	    path = path.substring(1);
	var slash = path.indexOf("/");
	var prefix = slash > 0 ? path.substring(0, slash) : path;
	var suffix = slash > 0 ? path.substring(slash + 1) : "";
	var userContext = context;
	return this.getChildren(context).then(function(children) {
	    for (var i=0; i < children.length; i++)
		if (children[i].getFileProperties().name == prefix) {
		    return children[i].getDescendentByPath(suffix, userContext)
		}
	    return Promise.resolve(null);
	});
    }.bind(this);

    this.removeChild = function(child, context) {
	var name = child.getFileProperties().name;
	childrenByName[name] = null;
	var index = children.indexOf(child);
	if (index > -1)
	    children.splice(index, 1);
	return pointer.fileAccess.removeChild(child.getPointer(), pointer.filePointer, context)
    }

    this.addLinkTo = function(file, context) {
	if (!this.isDirectory())
	    return Promise.resolve(false);
	if (!this.isWritable())
	    return Promise.resolve(false);
	var name = file.getFileProperties().name;
	if (childrenByName[name] != null) {
	    console.log("Child already exists with name: "+name);
	    return Promise.resolve(false)
	}
	var loc = file.getLocation();
	if (file.isDirectory()) {
	    pointer.fileAccess.addSubdir(loc, this.getKey(), file.getKey());
	} else {
	    pointer.fileAccess.addFile(loc, this.getKey(), file.getKey());
	}
	this.addChild(file);
	return pointer.fileAccess.commit(pointer.filePointer.owner, entryWriterKey, pointer.filePointer.mapKey, context);
    }

    this.isLink = function() {
	return pointer.fileAcess.isLink();
    }

    this.toLink = function() {
	return pointer.filePointer.toLink();
    }

    this.isWritable = function() {
	return entryWriterKey instanceof User;
    }

    this.getKey = function() {
	return pointer.filePointer.baseKey;
    }

    this.getLocation = function() {
	return new Location(pointer.filePointer.owner, pointer.filePointer.writer, pointer.filePointer.mapKey);
    }

    this.getChildrenLocations = function() {
	if (!this.isDirectory())
	    return [];
	return pointer.fileAccess.getChildrenLocations(pointer.filePointer.baseKey);
    }

    this.clear = function() {
	children = [];
	childrenByName = {};
    }.bind(this);

    this.retrieveParent = function(context) {
	if (pointer == null)
	    return Promise.resolve(null);
	var parentKey = this.getParentKey();
	return pointer.fileAccess.getParent(parentKey, context).then(function(parentRFP) {
	    if (parentRFP == null)
		return Promise.resolve(FileTreeNode.ROOT);
	    return Promise.resolve(new FileTreeNode(parentRFP, owner, [], [], entryWriterKey));
	});
    }

    this.getParentKey = function() {
	var parentKey = pointer.filePointer.baseKey;
	if (this.isDirectory())
	    try {
		parentKey = pointer.fileAccess.getParentKey(parentKey);
	    } catch (e) {
		// if we don't have read access to this folder, then we must just have the parent key already
	    }
	return parentKey;
    }.bind(this);

    this.getChildren = function(context) {
	if (this == FileTreeNode.ROOT)
	    return Promise.resolve(children);
	const that = this;
	try {
	    return retrieveChildren(context).then(function(childrenRFPs){
		return Promise.resolve(childrenRFPs.map(function(x) {return new FileTreeNode(x, owner, readers, writers, entryWriterKey);}));
	    }).then(function(children){
		that.clear();
		for (var i=0; i < children.length; i++)
		    that.addChild(children[i]);
		return Promise.resolve(children);
	    });
	} catch (e) {
	    // directories we don't have read access to have children populated during tree creation
	    return Promise.resolve(children);
	}
    }

    var retrieveChildren = function(context) {
	const filePointer = pointer.filePointer;
        const fileAccess = pointer.fileAccess;
        const rootDirKey = filePointer.baseKey;
	var canGetChildren = true;
	try {
	    fileAccess.getMetaKey(rootDirKey);
	    canGetChildren = false;
	} catch (e) {}
	if (canGetChildren)
            return fileAccess.getChildren(userContext, rootDirKey);
	throw "No credentials to retrieve children!";
    }

    this.getOwner = function() {
	return owner;
    }

    this.isDirectory = function() {
	return pointer.fileAccess.isDirectory();
    }

    this.uploadFile = function(filename, file, context, setProgressPercentage) {
	if (!this.isLegalName(filename))
	    return Promise.resolve(false);
	if (childrenByName[filename] != null) {
	    console.log("Child already exists with name: "+filename);
	    return Promise.resolve(false)
	}
	const fileKey = SymmetricKey.random();
        const rootRKey = pointer.filePointer.baseKey;
        const owner = pointer.filePointer.owner;
        const dirMapKey = pointer.filePointer.mapKey;
        const writer = pointer.filePointer.writer;
        const dirAccess = pointer.fileAccess;
	const parentLocation = new Location(owner, writer, dirMapKey);
	const dirParentKey = dirAccess.getParentKey(rootRKey);
	
	return generateThumbnail(file, filename).then(function(thumbData) {
	    const fileProps = new FileProperties(filename, file.size, Date.now(), 0, thumbData);
	    const chunks = new FileUploader(filename, file, fileKey, parentLocation, dirParentKey, setProgressPercentage, fileProps);
            return chunks.upload(context, owner, entryWriterKey).then(function(fileLocation) {
		dirAccess.addFile(fileLocation, rootRKey, fileKey);
		return context.uploadChunk(dirAccess, owner, entryWriterKey, dirMapKey);
	    });
	});
    }.bind(this);

    this.isLegalName = function(name) {
	if (name.indexOf("/") != -1)
	    return false;
	return true;
    }

    this.mkdir = function(newFolderName, context, requestedBaseSymmetricKey, isSystemFolder) {
	if (!this.isDirectory())
	    return Promise.resolve(false);
	if (!this.isLegalName(newFolderName))
	    return Promise.resolve(false);
	if (childrenByName[newFolderName] != null) {
	    console.log("Child already exists with name: "+newFolderName);
	    return Promise.resolve(false);
	}
	const dirPointer = pointer.filePointer;
	const dirAccess = pointer.fileAccess;
    	var rootDirKey = dirPointer.baseKey;
	return dirAccess.mkdir(newFolderName, context, entryWriterKey, dirPointer.mapKey, rootDirKey, requestedBaseSymmetricKey, isSystemFolder);
    }

    this.rename = function(newName, context, parent) {
	if (!this.isLegalName(newName))
	    return Promise.resolve(false)
	if (parent != null && parent.hasChildByName(newName))
	    return Promise.resolve(false);
	//get current props
        const filePointer = pointer.filePointer;
        const baseKey = filePointer.baseKey;
        const fileAccess = pointer.fileAccess;
	
        const key = this.isDirectory() ? fileAccess.getParentKey(baseKey) : baseKey; 
        const currentProps = fileAccess.getFileProperties(key);
	
        const newProps = new FileProperties(newName, currentProps.size, currentProps.modified, currentProps.attr, currentProps.thumbnail);
	
        return fileAccess.rename(writableFilePointer(), newProps, context);
    }

    var writableFilePointer = function() {
	const filePointer = pointer.filePointer;
	const fileAccess = pointer.fileAccess;
	const baseKey = filePointer.baseKey;
	return new ReadableFilePointer(filePointer.owner, entryWriterKey, filePointer.mapKey, baseKey);
    }.bind(this);

    this.getEntryWriterKey = function() {
	return entryWriterKey;
    }

    //FileTreeNode -> FileTreeNode -> userContext -> void
    this.copyTo = function(target, context) {
        if (! target.isDirectory())
            return Promise.reject("CopyTo target "+ target +" must be a directory");
	if (target.hasChildByName(this.getFileProperties().name))
	    return Promise.resolve(false);
        //make new FileTreeNode pointing to the same file, but with a different location
        const newMapKey = window.nacl.randomBytes(32);
	const ourBaseKey = this.getKey();
	// a file baseKey is the key for the chunk, which hasn't changed, so this must stay the same
	const newBaseKey = this.isDirectory() ? SymmetricKey.random() : ourBaseKey;
	const newRFP = new ReadableFilePointer(context.user, target.getEntryWriterKey(), newMapKey, newBaseKey);
	const newParentLocation = target.getLocation();
	const newParentParentKey = target.getParentKey();
	
	return pointer.fileAccess.copyTo(ourBaseKey, newBaseKey, newParentLocation, newParentParentKey, target.getEntryWriterKey(), newMapKey, context).then(function(newAccess) {
	    // upload new metadatablob
            const newRetrievedFilePointer = new RetrievedFilePointer(newRFP, newAccess);
	    const newFileTreeNode = new FileTreeNode(newRetrievedFilePointer, context.username, [], [], target.getEntryWriterKey());
            return target.addLinkTo(newFileTreeNode, context);
	});
    }

    this.remove = function(context, parent) {
	var func = function() {
	    if (parent != null)
		return parent.removeChild(this, context);
	    return Promise.resolve(true);
	}.bind(this);
	return func().then(function(res) { 
	    return new RetrievedFilePointer(writableFilePointer(), pointer.fileAccess).remove(context);
	});
    }.bind(this);

    this.getInputStream = function(context, size, setProgressPercentage) {
	const baseKey = pointer.filePointer.baseKey;
	return pointer.fileAccess.retriever.getFile(context, baseKey, size, setProgressPercentage)
    }

    this.getFileProperties = function() {
	if (pointer == null)
	    return new FileProperties("/", 0, 0, 0);
	const parentKey = this.getParentKey();
	return pointer.fileAccess.getFileProperties(parentKey);
    }.bind(this);
}
FileTreeNode.ROOT = new FileTreeNode(null, null, [], [], null);

//ReadableFilePointer, FileAccess
function RetrievedFilePointer(pointer, access) {
    this.filePointer = pointer;
    this.fileAccess = access;
    if (access == null)
	throw "Null fileAccess!";

    this.equals = function(that) {
	if (that == null)
	    return false;
	return this.filePointer.equals(that.filePointer);
    }

    this.remove = function(context, parentRetrievedFilePointer) {
	if (!this.filePointer.isWritable())
	    return Promise.resolve(false);
	if (!this.fileAccess.isDirectory())
	    return this.fileAccess.removeFragments(context).then(function() {
		return context.btree.remove(this.filePointer.writer.getPublicKeys(), this.filePointer.mapKey);
	    }.bind(this)).then(function(treeRootHashCAS) {
		var signed = this.filePointer.writer.signMessage(treeRootHashCAS);
		return context.corenodeClient.addMetadataBlob(this.filePointer.owner.getPublicKeys(), this.filePointer.writer.getPublicKeys(), signed)
	    }.bind(this)).then(function() {
		// remove from parent
		if (parentRetrievedFilePointer != null)
		    parentRetrievedFilePointer.fileAccess.removeChild(this, parentRetrievedFilePointer.filePointer, context);
	    }.bind(this));
	return this.fileAccess.getChildren(context, this.filePointer.baseKey).then(function(files) {
	    const proms = [];
	    for (var i=0; i < files.length; i++)
		proms.push(files[i].remove(context, null));
	    return Promise.all(proms).then(function() {
		return context.btree.remove(this.filePointer.writer.getPublicKeys(), this.filePointer.mapKey);
	    }.bind(this)).then(function(treeRootHashCAS) {
		var signed = this.filePointer.writer.signMessage(treeRootHashCAS);
		return context.corenodeClient.addMetadataBlob(this.filePointer.owner.getPublicKeys(), this.filePointer.writer.getPublicKeys(), signed)
	    }.bind(this)).then(function() {
		// remove from parent
		if (parentRetrievedFilePointer != null)
		    parentRetrievedFilePointer.fileAccess.removeChild(this, parentRetrievedFilePointer.filePointer, context);
	    });
	}.bind(this));
    }.bind(this);

    this.withWriter = function(writer) {
	return new RetrievedFilePointer(new ReadableFilePointer(this.filePointer.owner, writer, this.filePointer.mapKey, this.filePointer.baseKey), this.fileAccess);
    }.bind(this);
}

// ReadableFilePinter, String, [String], [String]
function EntryPoint(pointer, owner, readers, writers) {
    this.pointer = pointer;
    this.owner = owner;
    this.readers = readers;
    this.writers = writers;

    this.equals = function(that) {
	if (that == null)
	    return false;
	return this.pointer.equals(that.pointer);
    }

    // User, UserPublicKey
    this.serializeAndEncrypt = function(user, target) {
        return target.encryptMessageFor(this.serialize(), user);
    }

    this.serializeAndSymmetricallyEncrypt = function(key) {
	var nonce = key.createNonce();
	return concat(nonce, key.encrypt(this.serialize(), nonce));
    }

    this.serialize = function() {
        const dout = new ByteArrayOutputStream();
        dout.writeArray(this.pointer.serialize());
        dout.writeString(this.owner);
        dout.writeInt(this.readers.length);
        for (var i = 0; i < this.readers.length; i++) {
            dout.writeString(this.readers[i]);
        }
        dout.writeInt(this.writers.length);
        for (var i=0; i < this.writers.length; i++) {
            dout.writeString(this.writers[i]);
        }
        return dout.toByteArray();
    }
}

// byte[], Key
EntryPoint.symmetricallyDecryptAndDeserialize = function(input, key) {
    const nonce = input.subarray(0, 24);
    const raw = new Uint8Array(key.decrypt(input.subarray(24, input.length), nonce));
    const din = new ByteArrayInputStream(raw);
    const pointer = ReadableFilePointer.deserialize(din.readArray());
    const owner = din.readString();
    const nReaders = din.readInt();
    const readers = [];
    for (var i=0; i < nReaders; i++)
        readers.push(din.readString());
    const nWriters = din.readInt();
    const writers = [];
    for (var i=0; i < nWriters; i++)
        writers.push(din.readString());
    return new EntryPoint(pointer, owner, readers, writers);
}
// byte[], User, UserPublicKey
EntryPoint.decryptAndDeserialize = function(input, user, from) {
    const raw = new Uint8Array(user.decryptMessage(input, from));
    const din = new ByteArrayInputStream(raw);
    const pointer = ReadableFilePointer.deserialize(din.readArray());
    const owner = din.readString();
    const nReaders = din.readInt();
    const readers = [];
    for (var i=0; i < nReaders; i++)
        readers.push(din.readString());
    const nWriters = din.readInt();
    const writers = [];
    for (var i=0; i < nWriters; i++)
        writers.push(din.readString());
    return new EntryPoint(pointer, owner, readers, writers);
}
EntryPoint.deserialize = function(raw) {
    const din = new ByteArrayInputStream(raw);
    const pointer = ReadableFilePointer.deserialize(din.readArray());
    const owner = din.readString();
    const nReaders = din.readInt();
    const readers = [];
    for (var i=0; i < nReaders; i++)
        readers.push(din.readString());
    const nWriters = din.readInt();
    const writers = [];
    for (var i=0; i < nWriters; i++)
        writers.push(din.readString());
    return new EntryPoint(pointer, owner, readers, writers);
}

//EntryPoint, SymmetricKey 
function FollowRequest(entry, key, rawCipher) {
    this.entry = entry;
    this.key = key;
    this.rawCipher = rawCipher;

    this.isAccepted = function() {
	return entry != null;
    }

    this.isReciprocated = function() {
	return key != null;
    }
}

function SymmetricLink(link) {
    this.link = slice(link, SymmetricKey.NONCE_BYTES, link.length);
    this.nonce = slice(link, 0, SymmetricKey.NONCE_BYTES);

    this.serialize = function() {
    return concat(this.nonce, this.link);
    }

    this.target = function(from) {
    var encoded = from.decrypt(this.link, this.nonce);
    return SymmetricKey.deserialize(encoded);
    }
}
SymmetricLink.fromPair = function(from, to, nonce) {
    return new SymmetricLink(concat(nonce, from.encrypt(to.serialize(), nonce)));
}

// UserPublicKey, UserPublicKey, Uint8Array
function Location(owner, writer, mapKey) {
    this.owner = owner;
    this.writer = writer;
    this.mapKey = mapKey;

    this.serialize = function() {
        var bout = new ByteArrayOutputStream();
        bout.writeArray(owner.getPublicKeys());
        bout.writeArray(writer.getPublicKeys());
        bout.writeArray(mapKey);
        return bout.toByteArray();
    }

    this.encrypt = function(key, nonce) {
        return key.encrypt(this.serialize(), nonce);
    }

    this.toString = function() {
	return nacl.util.encodeBase64(owner.getPublicKeys())+
	    nacl.util.encodeBase64(writer.getPublicKeys())+
	    nacl.util.encodeBase64(mapKey);
    }
}
Location.deserialize = function(raw) {
    const buf = raw instanceof ByteArrayInputStream ? raw : new ByteArrayInputStream(raw);
    var owner = buf.readArray();
    var writer = buf.readArray();
    var mapKey = buf.readArray();
    return new Location(UserPublicKey.fromPublicKeys(owner), UserPublicKey.fromPublicKeys(writer), mapKey);
}
Location.decrypt = function(from, nonce, loc) {
    var raw = from.decrypt(loc, nonce);
    return Location.deserialize(raw);
}

function SymmetricLocationLink(arr) {
    const buf = new ByteArrayInputStream(arr);
    this.link = buf.readArray();
    this.loc = buf.readArray();

    // SymmetricKey -> Location
    this.targetLocation = function(from) {
        var nonce = slice(this.link, 0, SymmetricKey.NONCE_BYTES);
        return Location.decrypt(from, nonce, this.loc);
    }

    this.target = function(from) {
        var nonce = slice(this.link, 0, SymmetricKey.NONCE_BYTES);
        var rest = slice(this.link, SymmetricKey.NONCE_BYTES, this.link.length);
        var encoded = from.decrypt(rest, nonce);
        return SymmetricKey.deserialize(encoded);
    }

    this.serialize = function() {
        var buf = new ByteArrayOutputStream();
        buf.writeArray(this.link);
        buf.writeArray(this.loc);
        return buf.toByteArray();
    }

    this.toReadableFilePointer = function(baseKey) {
       const loc =  this.targetLocation(baseKey);
       const key = this.target(baseKey);
       return new ReadableFilePointer(loc.owner, loc.writer, loc.mapKey, key);
    }
}
SymmetricLocationLink.create = function(fromKey, toKey, location) {
    var nonce = fromKey.createNonce();
    var loc = location.encrypt(fromKey, nonce);
    var link = concat(nonce, fromKey.encrypt(toKey.serialize(), nonce));
    var buf = new ByteArrayOutputStream();
    buf.writeArray(link);
    buf.writeArray(loc);
    return new SymmetricLocationLink(buf.toByteArray());
}


function FileAccess(parent2meta, properties, retriever, parentLink) {
    this.parent2meta = parent2meta;
    this.properties = properties;
    this.retriever = retriever;
    this.parentLink = parentLink;

    this.serialize = function(bout) {
        bout.writeArray(parent2meta.serialize());
        bout.writeArray(properties);
        bout.writeByte(retriever != null ? 1 : 0);
        if (retriever != null)
            retriever.serialize(bout);
        bout.writeByte(parentLink != null ? 1: 0);
        if (parentLink != null)
            bout.writeArray(this.parentLink.serialize());
        bout.writeByte(this.getType());
    }

    // 0=FILE, 1=DIR
    this.getType = function() {
        return 0;
    }
    
    this.isDirectory =  function() {
        return this.getType() == 1;
    }
    
    this.getMetaKey = function(parentKey) {
        return parent2meta.target(parentKey);
    }

    this.removeFragments = function(context) {
	if (this.isDirectory())
	    return Promise.resolve(true);
	return Promise.resolve(true);
    }

    this.getFileProperties = function(parentKey) {
        var nonce = slice(this.properties, 0, SymmetricKey.NONCE_BYTES);
        var cipher = slice(this.properties, SymmetricKey.NONCE_BYTES, this.properties.length);
        return FileProperties.deserialize(this.getMetaKey(parentKey).decrypt(cipher, nonce));
    }

    this.getParent = function(parentKey, context) {
	if (this.parentLink == null)
	    return Promise.resolve(null);
	return context.retrieveAllMetadata([this.parentLink], parentKey).then(
	    function(res) {
		const retrievedFilePointer = res.map(function(entry) {
		    return new RetrievedFilePointer(entry[0],  entry[1]); 
		})[0];
		return Promise.resolve(retrievedFilePointer);
            })
    }

    this.rename = function(writableFilePointer, newProps, context) {
	if (!writableFilePointer.isWritable())
	    throw "Need a writable pointer!";
	var metaKey;
	if (this.isDirectory()) {
	    const parentKey = this.subfolders2parent.target(writableFilePointer.baseKey);
	    metaKey = this.getMetaKey(parentKey);
	    const metaNonce = metaKey.createNonce();
	    const dira = new DirAccess(this.subfolders2files, this.subfolders2parent,
				       this.subfolders, this.files, this.parent2meta,
				       concat(metaNonce, metaKey.encrypt(newProps.serialize(), metaNonce))
				      );
	    return context.uploadChunk(dira, writableFilePointer.owner, writableFilePointer.writer, writableFilePointer.mapKey);
	} else {
	    metaKey = this.getMetaKey(writableFilePointer.baseKey);
	    const nonce = metaKey.createNonce();
	    const fa = new FileAccess(this.parent2meta, concat(nonce, metaKey.encrypt(newProps.serialize(), nonce)), this.retriever, this.parentLink);
	    return context.uploadChunk(fa, writableFilePointer.owner, writableFilePointer.writer, writableFilePointer.mapKey);
	}
    }

    this.copyTo = function(baseKey, newBaseKey, parentLocation, parentparentKey, entryWriterKey, newMapKey, context) {
	if (!arraysEqual(baseKey.serialize(), newBaseKey.serialize()))
	    throw "FileAcess clone must have same base key as original!";
	const props = this.getFileProperties(baseKey);
	const fa = FileAccess.create(newBaseKey, props, this.retriever, parentLocation, parentparentKey);
	return context.uploadChunk(fa, context.user, entryWriterKey, newMapKey).then(function(res) {
	    return Promise.resolve(fa);
	});
    }
}
FileAccess.deserialize = function(raw) {
    const buf = new ByteArrayInputStream(raw);
    var p2m = buf.readArray();
    var properties = buf.readArray();
    var hasRetreiver = buf.readByte();
    var retriever =  (hasRetreiver == 1) ? FileRetriever.deserialize(buf) : null;
    var hasParent = buf.readByte();
    var parentLink =  (hasParent == 1) ? new SymmetricLocationLink(buf.readArray()) : null;
    var type = buf.readByte();
    var fileAccess = new FileAccess(new SymmetricLink(p2m), properties, retriever, parentLink);
    switch(type) {
        case 0:
            return fileAccess;
        case 1:
            return DirAccess.deserialize(fileAccess, buf);
        default: throw new Error("Unknown Metadata type: "+type);
    }
}

FileAccess.create = function(parentKey, props, retriever, parentLocation, parentparentKey) {
    var metaKey = SymmetricKey.random();
    var nonce = metaKey.createNonce();
    return new FileAccess(SymmetricLink.fromPair(parentKey, metaKey, parentKey.createNonce()),
			  concat(nonce, metaKey.encrypt(props.serialize(), nonce)), retriever, SymmetricLocationLink.create(parentKey, parentparentKey, parentLocation));
}

function DirAccess(subfolders2files, subfolders2parent, subfolders, files, parent2meta, properties, retriever, parentLink) {
    FileAccess.call(this, parent2meta, properties, retriever, parentLink);
    this.subfolders2files = subfolders2files;
    this.subfolders2parent = subfolders2parent;
    this.subfolders = subfolders;
    this.files = files;

    this.superSerialize = this.serialize;
    this.serialize = function(bout) {
        this.superSerialize(bout);
        bout.writeArray(this.subfolders2parent.serialize());
        bout.writeArray(this.subfolders2files.serialize());
        bout.writeInt(0);
        bout.writeInt(this.subfolders.length)
        for (var i=0; i < this.subfolders.length; i++)
            bout.writeArray(this.subfolders[i].serialize());
        bout.writeInt(this.files.length)
        for (var i=0; i < this.files.length; i++)
            bout.writeArray(this.files[i].serialize());
    }.bind(this);

    // Location, SymmetricKey, SymmetricKey
    this.addFile = function(location, ourSubfolders, targetParent) {
        const filesKey = this.subfolders2files.target(ourSubfolders);
        var nonce = filesKey.createNonce();
        var loc = location.encrypt(filesKey, nonce);
        var link = concat(nonce, filesKey.encrypt(targetParent.serialize(), nonce));
        var buf = new ByteArrayOutputStream();
        buf.writeArray(link);
        buf.writeArray(loc);
        this.files.push(SymmetricLocationLink.create(filesKey, targetParent, location));
    }.bind(this);

    this.removeChild = function(childRetrievedPointer, readablePointer, context) {
	if (childRetrievedPointer.fileAccess.isDirectory()) {
	    const newsubfolders = [];
	    for (var i=0; i < this.subfolders.length; i++) {
		var target = this.subfolders[i].targetLocation(readablePointer.baseKey);
		var keep = true;
		if (arraysEqual(target.mapKey, childRetrievedPointer.filePointer.mapKey))
		    if (arraysEqual(target.writer.getPublicKeys(), childRetrievedPointer.filePointer.writer.getPublicKeys()))
			if (arraysEqual(target.owner.getPublicKeys(), childRetrievedPointer.filePointer.owner.getPublicKeys()))
			    keep = false;
		if (keep)
		    newsubfolders.push(this.subfolders[i]);
	    }
	    this.subfolders = newsubfolders;
	} else {
	    const newfiles = [];
	    const filesKey = subfolders2files.target(readablePointer.baseKey)
	    for (var i=0; i < this.files.length; i++) {
		var target = this.files[i].targetLocation(filesKey);
		var keep = true;
		if (arraysEqual(target.mapKey, childRetrievedPointer.filePointer.mapKey))
		    if (arraysEqual(target.writer.getPublicKeys(), childRetrievedPointer.filePointer.writer.getPublicKeys()))
			if (arraysEqual(target.owner.getPublicKeys(), childRetrievedPointer.filePointer.owner.getPublicKeys()))
			    keep = false;
		if (keep)
		    newfiles.push(this.files[i]);
	    }
	    this.files = newfiles;
	}
	return context.uploadChunk(this, readablePointer.owner, readablePointer.writer, readablePointer.mapKey);
    }.bind(this);

    // 0=FILE, 1=DIR
    this.getType = function() {
        return 1;
    }

    // returns [RetrievedFilePointer]
    this.getChildren = function(context, baseKey) {
        const prom1 = context.retrieveAllMetadata(this.subfolders, baseKey);
        const prom2 = context.retrieveAllMetadata(this.files, this.subfolders2files.target(baseKey));
        return Promise.all([prom1, prom2]).then(function(mapArr) {
            const res = mapArr[0];
            for (var i=0; i < mapArr[1].length; i++)
                res.push(mapArr[1][i]);
            const retrievedFilePointers = res.map(function(entry) {
               return new RetrievedFilePointer(entry[0],  entry[1]); 
            })
            return Promise.resolve(retrievedFilePointers);
        })
    }

    this.getChildrenLocations = function(baseKey) {
	var res = [];
	for (var i=0; i < this.subfolders.length; i++) {
	    var subfolderLink = this.subfolders[i];
	    res.push(subfolderLink.targetLocation(baseKey));
	}
	var filesKey = this.subfolders2files.target(baseKey);
	for (var i=0; i < this.files.length; i++) {
	    var fileLink = this.files[i];
	    res.push(fileLink.targetLocation(filesKey));
	}
	return res;
    }

    this.getParentKey = function(subfoldersKey) {
        return this.subfolders2parent.target(subfoldersKey);
    }

    this.getFilesKey = function(subfoldersKey) {
        return this.subfolders2files.target(subfoldersKey);
    }

    //String, UserContext, User -> 
    this.mkdir  = function(name, userContext, writer, ourMapKey, baseKey, optionalBaseKey, isSystemFolder) {
        if (!(writer instanceof User))
            throw "Can't modify a directory without write permission (writer must be a User)!";    
        const dirReadKey = optionalBaseKey != null ? optionalBaseKey : SymmetricKey.random();
        const dirMapKey = window.nacl.randomBytes(32); // root will be stored under this in the core node
	const ourParentKey = this.getParentKey(baseKey);
	const ourLocation = new Location(userContext.user, writer, ourMapKey);
        const dir = DirAccess.create(dirReadKey, new FileProperties(name, 0, Date.now(), (isSystemFolder == null || !isSystemFolder) ? 0 : 1), ourLocation, ourParentKey);
	const that = this;
	return userContext.uploadChunk(dir, userContext.user, writer, dirMapKey)
            .then(function(success) {
                if (success) {
                    that.addSubdir(new Location(userContext.user, writer, dirMapKey), baseKey, dirReadKey);
		    // now upload the changed metadata blob for dir
		    return userContext.uploadChunk(that, userContext.user, writer, ourMapKey).then(function(res) {
			return Promise.resolve(new ReadableFilePointer(userContext.user, writer, dirMapKey, dirReadKey));
		    });
                }
		return Promise.resolve(false);
            });
    }.bind(this);

    this.commit = function(owner, writer, ourMapKey, userContext) {
	return userContext.uploadChunk(this, userContext.user, writer, ourMapKey)
    }.bind(this);

    this.addSubdir = function(location, ourSubfolders, targetBaseKey) {
        this.subfolders.push(SymmetricLocationLink.create(ourSubfolders, targetBaseKey, location));
    }.bind(this);

    this.copyTo = function(baseKey, newBaseKey, parentLocation, parentparentKey, entryWriterKey, newMapKey, context) {
	const parentKey = this.getParentKey(baseKey);
	const props = this.getFileProperties(parentKey);
	const da = DirAccess.create(newBaseKey, props, this.retriever, parentLocation, parentparentKey, parentKey);
	const ourNewParentKey = da.getParentKey(newBaseKey);
	const ourNewLocation = new Location(context.user, entryWriterKey, newMapKey);

	return this.getChildren(context, baseKey).then(function(RFPs) {
	    // upload new metadata blob for each child and re-add child
	    var proms = RFPs.map(function(rfp) {
		var newChildBaseKey = rfp.fileAccess.isDirectory() ? SymmetricKey.random() : rfp.filePointer.baseKey;
		var newChildMapKey = window.nacl.randomBytes(32);
		var newChildLocation = new Location(context.user, entryWriterKey, newChildMapKey);
		return rfp.fileAccess.copyTo(rfp.filePointer.baseKey, newChildBaseKey, ourNewLocation, ourNewParentKey, entryWriterKey, newChildMapKey, context).then(function(newChildFileAccess){
		    if (newChildFileAccess.isDirectory())
			da.addSubdir(newChildLocation, newBaseKey, newChildBaseKey);
		    else
			da.addFile(newChildLocation, newBaseKey, newChildBaseKey);
		    return Promise.resolve(true);
		});
	    });
	    return Promise.all(proms);
	}).then(function(res) {
	    return context.uploadChunk(da, context.user, entryWriterKey, newMapKey).then(function(res) {
		return Promise.resolve(da);
	    });
	});
    }
}

DirAccess.deserialize = function(base, bin) {
    var s2p = bin.readArray();
    var s2f = bin.readArray();

    var nSharingKeys = bin.readInt();
    var files = [], subfolders = [];
    var nsubfolders = bin.readInt();
    for (var i=0; i < nsubfolders; i++)
        subfolders[i] = new SymmetricLocationLink(bin.readArray());
    var nfiles = bin.readInt();
    for (var i=0; i < nfiles; i++)
        files[i] = new SymmetricLocationLink(bin.readArray());
    return new DirAccess(new SymmetricLink(s2f),
                         new SymmetricLink(s2p),
                         subfolders, files, base.parent2meta, base.properties, base.retriever, base.parentLink);
}

// SymmetricKey -> FileProperties -> Location -> SymmetricKey
DirAccess.create = function(subfoldersKey, metadata, parentLocation, parentParentKey, parentKey) {
    var metaKey = SymmetricKey.random();
    if (parentKey == null)
	parentKey = SymmetricKey.random();
    var filesKey = SymmetricKey.random();
    var metaNonce = metaKey.createNonce();
    var parentLink = parentLocation == null ? null : SymmetricLocationLink.create(parentKey, parentParentKey, parentLocation);
    return new DirAccess(SymmetricLink.fromPair(subfoldersKey, filesKey, subfoldersKey.createNonce()),
			 SymmetricLink.fromPair(subfoldersKey, parentKey, subfoldersKey.createNonce()),
			 [], [],
			 SymmetricLink.fromPair(parentKey, metaKey, parentKey.createNonce()),
			 concat(metaNonce, metaKey.encrypt(metadata.serialize(), metaNonce)),
			 null,
			 parentLink
			);
}

function FileRetriever() {
}
FileRetriever.deserialize = function(bin) {
    var type = bin.readByte();
    switch (type) {
    case 0:
    throw new Exception("Simple FileRetriever not implemented!");
    case 1:
    return EncryptedChunkRetriever.deserialize(bin);
    default:
    throw new Exception("Unknown FileRetriever type: "+type);
    }
}

function EncryptedChunkRetriever(chunkNonce, chunkAuth, fragmentHashes, nextChunk) {
    this.chunkNonce = chunkNonce;
    this.chunkAuth = chunkAuth;
    this.fragmentHashes = fragmentHashes;
    this.nextChunk = nextChunk;
    this.getFile = function(context, dataKey, len, setProgressPercentage) {
        const stream = this;
        return this.getChunkInputStream(context, dataKey, len, setProgressPercentage).then(function(chunk) {
            return Promise.resolve(new LazyInputStreamCombiner(stream, context, dataKey, chunk, setProgressPercentage));
        });
    }

    this.getNext = function() {
        return this.nextChunk;
    }

    this.getChunkInputStream = function(context, dataKey, len, setProgressPercentage) {
        var fragmentsProm = context.downloadFragments(fragmentHashes, setProgressPercentage);
        return fragmentsProm.then(function(fragments) {
            fragments = reorder(fragments, fragmentHashes);
            var cipherText = erasure.recombine(fragments, len != 0 ? len : Chunk.MAX_SIZE, EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);
	    if (len != 0)
		cipherText = cipherText.subarray(0, len);
            var fullEncryptedChunk = new EncryptedChunk(concat(chunkAuth, cipherText));
            var original = fullEncryptedChunk.decrypt(dataKey, chunkNonce);
            return Promise.resolve(original);
        });
    }

    this.serialize = function(buf) {
        buf.writeByte(1); // This class
        buf.writeArray(chunkNonce);
        buf.writeArray(chunkAuth);
        buf.writeArray(concat(fragmentHashes));
        buf.writeByte(this.nextChunk != null ? 1 : 0);
        if (this.nextChunk != null)
            buf.write(this.nextChunk.serialize());
    }
}
EncryptedChunkRetriever.deserialize = function(buf) {
    var chunkNonce = buf.readArray();
    var chunkAuth = buf.readArray();
    var concatFragmentHashes = buf.readArray();
    var fragmentHashes = split(concatFragmentHashes, UserPublicKey.HASH_BYTES);
    var hasNext = buf.readByte();
    var nextChunk = null;
    if (hasNext == 1)
        nextChunk = Location.deserialize(buf);
    return new EncryptedChunkRetriever(chunkNonce, chunkAuth, fragmentHashes, nextChunk);
}

function split(arr, size) {
    var length = arr.byteLength/size;
    var res = [];
    for (var i=0; i < length; i++)
    res[i] = slice(arr, i*size, (i+1)*size);
    return res;
}

function LazyInputStreamCombiner(stream, context, dataKey, chunk, setProgressPercentage) {
    if (!chunk)
        throw "Invalid current chunk!";
    this.context = context;
    this.dataKey = dataKey;
    this.current = chunk;
    this.index = 0;
    this.next = stream.getNext();
    this.setProgressPercentage = setProgressPercentage;
    this.getNextStream = function(len) {
        if (this.next != null) {
            const lazy = this;
            return context.getMetadata(this.next).then(function(meta) {
                var nextRet = meta.retriever;
                lazy.next = nextRet.getNext();
                return nextRet.getChunkInputStream(context, dataKey, len, setProgressPercentage);
            });
        }
        throw "EOFException";
    }

    this.bytesReady = function() {
        return this.current.length - this.index;
    }

    this.readByte = function() {
        try {
            return this.current[this.index++];
        } catch (e) {}
        const lazy = this;
        this.getNextStream().then(function(res){
            lazy.index = 0;
            lazy.current = res;
            return lazy.current.readByte();
        });
    }

    this.read = function(len, res, offset) {
        const lazy = this;
        if (res == null) {
            res = new Uint8Array(len);
            offset = 0;
        }
        const available = lazy.bytesReady();
        const toRead = Math.min(available, len);
        for (var i=0; i < toRead; i++)
            res[offset + i] = lazy.readByte();
        if (available >= len)
            return Promise.resolve(res);
	var nextSize = len - toRead > Chunk.MAX_SIZE ? Chunk.MAX_SIZE : (len-toRead) % Chunk.MAX_SIZE;
        return this.getNextStream(nextSize).then(function(chunk){
            lazy.index = 0;
            lazy.current = chunk;
            return lazy.read(len-toRead, res, offset + toRead);
        });
    }
}

function reorder(fragments, hashes) {
    var hashMap = new Map(); //ba dum che
    for (var i=0; i < hashes.length; i++)
        hashMap.set(nacl.util.encodeBase64(hashes[i]), i); // Seems Map can't handle array contents equality
    var res = [];
    for (var i=0; i < fragments.length; i++) {
        var hash = nacl.util.encodeBase64(fragments[i].hash);
        var index = hashMap.get(hash);
        res[index] = fragments[i].data;
    }
    return res;
}

function string2arraybuffer(str) {
    var buf = new ArrayBuffer(str.length);
    var bufView = new Uint8Array(buf);
    for (var i=0, strLen=str.length; i<strLen; i++) {
        bufView[i] = str.charCodeAt(i);
    }
    return bufView;
}

function bytesToHex(p) {
    /** @const */
    var enc = '0123456789abcdef'.split('');
    
    var len = p.length,
    arr = [],
    i = 0;
    
    for (; i < len; i++) {
        arr.push(enc[(p[i]>>>4) & 15]);
        arr.push(enc[(p[i]>>>0) & 15]);
    }
    return arr.join('');
}

function hexToBytes(hex) {
    var result = new Uint8Array(hex.length/2);
    for (var i=0; i + 1 < 2*result.length; i+= 2)
        result[i/2] = parseInt(hex.substring(i, i+2), 16);
    return result;
}

var commonPasswords;
get("/passwords.json", function(res){
    commonPasswords = JSON.parse(res);
});

if (typeof module !== "undefined"){
    module.exports.randomSymmetricKey = randomSymmetricKey;
    module.exports.SymmetricKey = SymmetricKey;
}
