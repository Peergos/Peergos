if (typeof module !== "undefined")
    var nacl = require("./nacl");

// API for the User interface to use
/////////////////////////////

/////////////////////////////
// UserPublicKey methods
function UserPublicKey(publicSignKey, publicBoxKey) {
    this.pSignKey = publicSignKey; // 32 bytes
    this.pBoxKey = publicBoxKey; // 32 bytes

    // ((err, publicKeyString) -> ())
    this.getPublicKeys = function() {
	var tmp = new Uint8Array(this.pSignKey.length + this.pBoxKey.length);
	tmp.set(this.pSignKey, 0);
	tmp.set(this.pBoxKey, this.pSignKey.length);
	return tmp;
    }
    
    // (Uint8Array, User, (nonce, cipher) -> ())
    this.encryptMessageFor = function(input, us) {
	var nonce = createNonce();
	return concat(nacl.box(input, nonce, this.pBoxKey, us.sBoxKey), nonce);
    }
    
    // Uint8Array => boolean
    this.unsignMessage = function(sig) {
	return nacl.sign.open(sig, this.pSignKey);
    }
    
    this.isValidSignature = function(signedHash, raw) {
	var a = hash(raw);
	var b = unsignMessage(signedHash);
	return arraysEqual(a, b);
    }

    // Uint8Array => Uint8Array
    this.hash = function(input) {
	var hasher = new BLAKE2s(32);
	hasher.update(input);
	return hasher.digest();
    }
}

UserPublicKey.fromPublicKeys = function(both) {
    var pSign = slice(both, 0, 32);
    var pBox = slice(both, 32, 64);
    return new UserPublicKey(pSign, pBox);
}

function createNonce(){
    return window.nacl.randomBytes(24);
}

/////////////////////////////
// User methods
// (string, string, (User -> ())
function generateKeyPairs(username, password, cb) {
    var hash = new BLAKE2s(32)
    hash.update(nacl.util.decodeUTF8(password))
    salt = nacl.util.decodeUTF8(username)
    
    return new Promise(function(resolve, reject) {
	scrypt(hash.digest(), salt, 17, 8, 64, 1000, function(keyBytes) {
	    var bothBytes = nacl.util.decodeBase64(keyBytes);
	    var signBytes = bothBytes.subarray(0, 32);
	    var boxBytes = bothBytes.subarray(32, 64);
	    resolve(new User(nacl.sign.keyPair.fromSeed(signBytes), nacl.box.keyPair.fromSecretKey(new Uint8Array(boxBytes))));
	}, 'base64');
    });
}

function User(signKeyPair, boxKeyPair) {
    UserPublicKey.call(this, signKeyPair.publicKey, boxKeyPair.publicKey);
    this.sSignKey = signKeyPair.secretKey; // 64 bytes
    this.sBoxKey = boxKeyPair.secretKey; // 32 bytes
    
    // (Uint8Array, (nonce, sig) => ())
    this.hashAndSignMessage = function(input, cb) {
	signMessage(this.hash(input), cb);
    }
    
    // (Uint8Array, (nonce, sig) => ())
    this.signMessage = function(input) {
	return nacl.sign(input, this.sSignKey);
    }
    
    // (Uint8Array, (err, literals) -> ())
    this.decryptMessage = function(cipher, them) {
	var nonce = slice(cipher, cipher.length-24, cipher.length);
	cipher = slice(cipher, 0, cipher.length-24);
	return nacl.box.open(cipher, nonce, them.pBoxKey, this.sBoxKey);
    }

    this.getSecretKeys = function() {
	var tmp = new Uint8Array(this.sSignKey.length + this.sBoxKey.length);
	tmp.set(this.sSignKey, 0);
	tmp.set(this.sBoxKey, this.sSignKey.length);
	return tmp;
    }
}

User.fromEncodedKeys = function(publicKeys, secretKeys) {
    return new User(toKeyPair(slice(publicKeys, 0, 32), slice(secretKeys, 0, 64)), toKeyPair(slice(publicKeys, 32, 64), slice(secretKeys, 64, 96)));
}

User.fromSecretKeys = function(secretKeys) {
    var publicBoxKey = new Uint8Array(32);
    nacl.lowlevel.crypto_scalarmult_base(publicBoxKey, slice(secretKeys, 64, 96))
    return User.fromEncodedKeys(concat(slice(secretKeys, 32, 64), 
				publicBoxKey), 
				secretKeys);
}

User.random = function() {
    var secretKeys = window.nacl.randomBytes(96);
    return User.fromSecretKeys(secretKeys);
}

function toKeyPair(pub, sec) {
    return {publicKey:pub, secretKey:sec};
}

/////////////////////////////
// SymmetricKey methods

function SymmetricKey(key) {
    this.key = key;

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
}
SymmetricKey.NONCE_BYTES = 24;
SymmetricKey.random = function() {
    return new SymmetricKey(nacl.randomBytes(32));
}

function FileProperties(name, size) {
    this.name = name;
    this.size = size;

    this.serialize = function() {
	var buf = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
	buf.writeUnsignedInt(name.length);
	buf.writeString(name);
	buf.writeUnsignedInt(size >> 32);
	buf.writeUnsignedInt(size & 0xffffffff);
	return new Uint8Array(buf.toArray());
    }
}

function Fragment(data) {
    this.data = data;
    this.getHash = function() {
	return nacl.hash(data); // SHA-512
    }

    this.getData = function() {
	return data;
    }
}
Fragment.SIZE = 128*1024;

function EncryptedChunk(encrypted) {
    this.auth = slice(encrypted, 0, window.nacl.secretbox.overheadLength);
    this.cipher = slice(encrypted, window.nacl.secretbox.overheadLength, encrypted.length);

    this.generateFragments = function() {
	var bfrags = Erasure.split(this.cipher, EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);
        var frags = [];
        for (var i=0; i < bfrags.length; i++)
            frags[i] = new Fragment(bfrags[i]);
        return frags;
    }

    this.getAuth = function() {
	return this.auth;
    }
}
EncryptedChunk.ERASURE_ORIGINAL = 40;
EncryptedChunk.ERASURE_ALLOWED_FAILURES = 10;

function Chunk(data, key) {
    this.data = data;
    this.key = key;

    this.encrypt = function(nonce) {
	return key.encrypt(new Uint8Array(data.toArray()), nonce);
    }
}
Chunk.MAX_SIZE = Fragment.SIZE*EncryptedChunk.ERASURE_ORIGINAL


/////////////////////////////
// Util methods

// byte[] input and return
function encryptBytesToBytes(input, pubKey) {
    return Java.to(encryptUint8ToUint8(input, pubKey), "byte[]");
}

// byte[] cipher and return
function decryptBytesToBytes(cipher, privKey) {
    return Java.to(decryptUint8ToUint8(cipher, privKey), "byte[]");
}

function uint8ArrayToByteArray(arr) {
    return Java.to(arr, "byte[]");
}

function slice(arr, start, end) {
    var r = new Uint8Array(end-start);
    if (arr instanceof ByteBuffer) {
	for (var i=start; i < end; i++)
	    r[i-start] = arr.raw[i];
    } else {
	for (var i=start; i < end; i++)
	    r[i-start] = arr[i];
    }
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
	
	req.onload = function() {
	    // This is called even on 404 etc
	    // so check the status
	    if (req.status == 200) {
		resolve(req.response);
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

function post(path, data, onSuccess, onError) {

    var request = new XMLHttpRequest();
    request.open("POST" , path);
    request.responseType = 'arraybuffer';

    request.onreadystatechange=function()
    {
        if (request.readyState != 4)
            return;

        if (request.status == 200) 
            onSuccess(request.response);
        else
            onError(request.status);
    }

    request.send(data);
}

function postProm(url, data) {
    return new Promise(function(resolve, reject) {
	var req = new XMLHttpRequest();
	req.open('POST', url);
	req.responseType = 'arraybuffer';
	
	req.onload = function() {
	    // This is called even on 404 etc
	    // so check the status
	    if (req.status == 200) {
		resolve(req.response);
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

//Java is Big-endian
function readInt(bytes, position) {
    var count = 0;
    for(var i = position; i <  position +4 ; i++)
        count = count << 8 + bytes[i];
    return count;
}

//Java is Big-endian
function writeInt(bytes, position, intValue) {
    intValue |= 0;
    for(var i = position + 3; position <= i ; i--)
        bytes[position] = intValue & 0xff;
        intValue >>= 8;
}

function arraysEqual(leftArray, rightArray) {
    if (leftArray.length != rightArray.length)
        return false;
    
    for (var i=0; i < leftArray.length; i++) 
        if (leftArray[i] != rightArray[i])
            return false;
    
    return true;
}

function DHTClient() {
    //
    //put
    //
    this.put = function(keyData, valueData, username, sharingKeyData, mapKeyData, proofData) {
        var arrays = [keyData, valueData, username, sharingKeyData, mapKeyData, proofData];
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        for (var iArray=0; iArray < arrays.length; iArray++) 
            buffer.writeArray(arrays[iArray]);
        return postProm("dht/put", new Uint8Array(buffer.toArray()));
    };
    //
    //get
    //
    this.get = function(keyData) { 
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeArray(keyData);
        return postProm("dht/get", new Uint8Array(buffer.toArray())); 
    };
    
    //
    //contains
    //
    this.contains = function(keyData) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeArray(keyData);
        return postProm("dht/contains", new Uint8Array(buffer.toArray())); 
    };
}

function CoreNodeClient() {
    //String -> fn- >fn -> void
    this.getPublicKey = function(username) {
        var buffer = new  ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
	buffer.writeUnsignedInt(username.length);
        buffer.writeString(username);
        return postProm("core/getPublicKey", new Uint8Array(buffer.toArray()));
    };
    
    //String -> Uint8Array -> Uint8Array -> fn -> fn -> void
    this.updateStaticData = function(username, signedHash, staticData) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
	buffer.writeUnsignedInt(username.length);
        buffer.writeString(username);
        buffer.writeArray(signedHash);
        buffer.writeArray(staticData);
        return postProm("core/updateStaticData", new Uint8Array(buffer.toArray())); 
    };
    
    //String -> fn- >fn -> void
    this.getStaticData = function(username) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeString(username);
        return postProm("core/getStaticData", new Uint8Array(buffer.toArray()));
    };
    
    //Uint8Array -> fn -> fn -> void
    this.getUsername = function(publicKey) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeArray(publicKey);
        return postProm("core/getUsername", new Uint8Array(buffer.toArray()));
    };
    
    
    //String -> Uint8Array -> Uint8Array -> fn -> fn -> void
    this.addUsername = function(username, encodedUserKey, signed, staticData) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
	buffer.writeUnsignedInt(username.length);
        buffer.writeString(username);
        buffer.writeArray(encodedUserKey);
        buffer.writeArray(signed);
        buffer.writeArray(staticData);
        return postProm("core/addUsername", new Uint8Array(buffer.toArray()));
    };
    
    //Uint8Array -> Uint8Array -> fn -> fn -> void
    this.followRequest = function( target,  encryptedPermission) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeArray(target);
        buffer.writeArray(encryptedPermission);
        return postProm("core/followRequest", new Uint8Array(buffer.toArray()));
    };
    
    //String -> Uint8Array -> fn -> fn -> void
    this.getFollowRequests = function( user) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeArray(user);
        return postProm("core/getFollowRequests", new Uint8Array(buffer.toArray()))
	    .then(function(res) {
		var buf = new ByteBuffer(res);
		var size = buf.readUnsignedInt();
		var n = buf.readUnsignedInt();
		var arr = [];
		for (var i=0; i < n; i++) {
		    var t = buf.readArray();
		    arr.push(new Uint8Array(t.toArray()));
		}
		return Promise.resolve(arr);
	    });
    };
    
    //String -> Uint8Array -> Uint8Array -> fn -> fn -> void
    this.removeFollowRequest = function( target,  data,  signed) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeString(target);
        buffer.writeArray(data);
        buffer.writeArray(signed);
        return postProm("core/removeFollowRequest", new Uint8Array(buffer.toArray()));
    };

    //String -> Uint8Array -> Uint8Array -> fn -> fn -> void
    this.allowSharingKey = function(owner, signedWriter) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeArray(owner);
        buffer.writeArray(signedWriter); 
        return postProm("core/allowSharingKey", new Uint8Array(buffer.toArray()));
    };
    
    //String -> Uint8Array -> Uint8Array -> fn -> fn -> void
    this.banSharingKey = function(username,  encodedSharingPublicKey,  signedHash) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeString(username);
        buffer.writeArray(encodedSharingPublicKey);
        buffer.writeArray(signedHash); 
        return postProm("core/banSharingKey", new Uint8Array(buffer.toArray()));
    };

    //Uint8Array -> Uint8Array -> Uint8Array -> Uint8Array  -> Uint8Array -> fn -> fn -> void
    this.addMetadataBlob = function( owner,  encodedSharingPublicKey,  mapKey,  metadataBlob,  sharingKeySignedHash) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeArray(owner);
        buffer.writeArray(encodedSharingPublicKey);
        buffer.writeArray(mapKey);
        buffer.writeArray(metadataBlob);
        buffer.writeArray(sharingKeySignedHash);
        return postProm("core/addMetadataBlob", new Uint8Array(buffer.toArray()));
    };
    
    //String -> Uint8Array -> Uint8Array  -> Uint8Array -> fn -> fn -> void
    this.removeMetadataBlob = function( username,  encodedSharingKey,  mapKey,  sharingKeySignedMapKey) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeString(username);
        buffer.writeArray(encodedSharingKey);
        buffer.writeArray(mapKey);
        buffer.writeArray(sharingKeySignedMapKey);
        return postProm("core/removeMetadataBlob", new Uint8Array(buffer.toArray()));
    };

    //String  -> Uint8Array  -> Uint8Array -> fn -> fn -> void
    this.removeUsername = function( username,  userKey,  signedHash) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeString(username);
        buffer.writeArray(userKey);
        buffer.writeArray(signedHash);
        return post("core/removeUsername", new Uint8Array(buffer.toArray()));
    };

    //String -> fn -> fn -> void
    this.getSharingKeys = function( username) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeString(username);
        return postProm("core/getSharingKeys", new Uint8Array(buffer.toArray()));
    };
    
    //String  -> Uint8Array  -> fn -> fn -> void
    this.getMetadataBlob = function( username,  encodedSharingKey,  mapKey) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeString(username);
        buffer.writeArray(encodedSharingKey);
        buffer.writeArray(mapKey);
        return postProm("core/getMetadataBlob", new Uint8Array(buffer.toArray()));
    };
    
    //String  -> Uint8Array  -> Uint8Array -> fn -> fn -> void
    this.isFragmentAllowed = function( owner,  encodedSharingKey,  mapkey,  hash) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeString(owner);
        buffer.writeArray(encodedSharingKey);
        buffer.writeArray(mapKey);
        buffer.writeArray(hash);
        return postProm("core/isFragmentAllowed", new Uint8Array(buffer.toArray()));
    };
    
    //String -> fn -> fn -> void
    this.getQuota = function(user) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeArray(user.getPublicKeys());
        return postProm("core/getQuota", new Uint8Array(buffer.toArray()))
	    .then(function(res) {
		var buf = new ByteBuffer(new Uint8Array(res));
		var quota = buf.readUnsignedInt() << 32;
		quota += buf.readUnsignedInt();
		return Promise.resolve(quota);
	    });
    };
    
    //String -> fn -> fn -> void
    this.getUsage = function(username) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeString(username);
        return postProm("core/getUsage", new Uint8Array(buffer.toArray()));
    };
    
    //String  -> Uint8Array  -> Uint8Array -> fn -> fn -> void
    this.getFragmentHashes = function( username, sharingKey, mapKey) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeString(username);
        buffer.writeArray(sharingKey);
        buffer.writeArray(mapKey);
        return postProm("core/getFragmentHashes", new Uint8Array(buffer.toArray()));
    };

    //String  -> Uint8Array  -> Uint8Array -> Uint8Array -> [Uint8Array] -> Uint8Array -> fn -> fn -> void
    this.addFragmentHashes = function(username, encodedSharingPublicKey, mapKey, metadataBlob, allHashes, sharingKeySignedHash) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeString(username);
        buffer.writeArray(encodedShaaringPublicKey);
        buffer.writeArray(mapKey);
        buffer.writeArray(metadataBlob);
	
        buffer.writeInt(allHashes.length);
        for (var iHash=0; iHash  <  allHashes.length; iHash++) 
            buffer.writeArray(allHashes[iHash]);
	
        buffer.writeArray(sharingKeySignedHash);
        
        return postProm("core/addFragmentHashes", new Uint8Array(buffer.toArray()));
    };

    
    this.registerFragmentStorage = function(spaceDonor, address, port, owner, signedKeyPlusHash) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeArray(spaceDonor.getPublicKeys());
        buffer.writeArray(address);
	buffer.writeInt(port);
        buffer.writeArray(owner.getPublicKeys());
        buffer.writeArray(signedKeyPlusHash);
        return postProm("core/registerFragmentStorage", new Uint8Array(buffer.toArray()));
    };
};

function UserContext(username, user, dhtClient,  corenodeClient) {
    this.username  = username;
    this.user = user;
    this.dhtClient = dhtClient;
    this.corenodeClient = corenodeClient;
    this.staticData = []; // array of map entry pairs

    this.isRegistered = function() {
	return corenodeClient.getUsername(user.getPublicKeys()).then(function(res){
            return Promise.resolve(username == res);
	});
    }

    this.serializeStatic = function() {
        var buf = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buf.writeUnsignedInt(this.staticData.length);
        for (var i = 0; i < this.staticData.length; i++)
            buf.writeArray(this.staticData[i][1].serialize());
        return buf.toArray();
    }

    this.register = function() {
	console.log("registering "+username);
	var rawStatic = this.serializeStatic();
        var signed = user.signMessage(concat(nacl.util.decodeUTF8(username), user.getPublicKeys(), rawStatic));
        return corenodeClient.addUsername(username, user.getPublicKeys(), signed, rawStatic)
    }

    this.sendFollowRequest = function(targetUser) {
	// create sharing keypair and give it write access
        var sharing = User.random();
	var rootMapKey = new ByteBuffer(window.nacl.randomBytes(32));
	
        // add a note to our static data so we know who we sent the private key to
        var friendRoot = new WritableFilePointer(user, sharing, rootMapKey, SymmetricKey.random());
        return this.addSharingKey(sharing).then(function() {
            return this.addToStaticData(sharing, friendRoot);
	}.bind(this)).then(function() {	    
	    // send details to allow friend to share with us (i.e. we follow them)
	    var raw = friendRoot.serialize();
	    
	    // create a tmp keypair whose public key we can append to the request without leaking information
	    var tmp = User.random();
	    var payload = targetUser.encryptMessageFor(new Uint8Array(raw), tmp);
	    return corenodeClient.followRequest(targetUser.getPublicKeys(), concat(tmp.pBoxKey, payload));
	});
    }

    this.addSharingKey = function(pub) {
	var signed = user.signMessage(pub.getPublicKeys());
        return corenodeClient.allowSharingKey(user.getPublicKeys(), signed);
    }

    this.addToStaticData = function(writer, root) {
	this.staticData.push([writer, root]);
        var rawStatic = new Uint8Array(this.serializeStatic());
        return corenodeClient.updateStaticData(username, user.signMessage(rawStatic), rawStatic);
    }

    this.getFollowRequests = function() {
	return corenodeClient.getFollowRequests(user.getPublicKeys());
    }

    this.decodeFollowRequest = function(raw) {
	var pBoxKey = new Uint8Array(32);
	for (var i=0; i < 32; i++)
            pBoxKey[i] = raw[i]; // signing key is not used
        var tmp = new UserPublicKey(null, pBoxKey);
	var buf = new ByteBuffer(raw);
	buf.read(32);
	var cipher = buf.read(raw.length - 32);
        var decrypted = user.decryptMessage(cipher.toArray(), tmp);
        return WritableFilePointer.deserialize(new Uint8Array(decrypted)); // somehow not creating a new uint8array keeps the extra 32 bytes...
    }
    
    this.uploadFragment = function(f, targetUser, sharer, mapKey) {
	return dhtClient.put(f.getHash(), f.getData(), targetUser.getPublicKeys(), sharer.getPublicKeys(), mapKey, sharer.signMessage(concat(sharer.getPublicKeys(), f.getHash())));
    }

    this.uploadChunk = function(metadata, fragments, owner, sharer, mapKey) {
	var buf = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        metadata.serialize(buf);
        var metaBlob = buf.toArray();
        console.log("Storing metadata blob of " + metaBlob.length + " bytes.");
        return corenodeClient.addMetadataBlob(owner.getPublicKeys(), sharer.getPublicKeys(), mapKey, metaBlob, sharer.signMessage(concat(mapKey, metaBlob)))
	    .then(function(added) {
		if (!added) {
		    console.log("Meta blob store failed.");
		}
	    }).then(function() {
		if (fragments.length == 0 )
		    return Promise.resolve(true);
		
		// now upload fragments to DHT
		var futures = [];
		for (var i=0; i < fragments.length; i++)
		    futures[i] = this.uploadFragment(fragments[i], owner, sharer, mapKey);
		
		// wait for all fragments to upload
		return Promise.all(futures);
            }.bind(this));
    }

    this.downloadFragments = function(hashes) {
        var result = {}; 
        result.fragments = [];
        result.nSuccess = 0;
        result.nError = 0;
        
        var completion  = function() {
            if (this.nSuccess + this.nError < this.fragments.length)
                return;
            console.log("Completed");
            if (this.nError  > 0)
                throw "found "+ nError +" errors.";
            return this.fragments; 
        }.bind(result);

        var success = function(fragmentData, index) {
            this.fragments.index = fragmentData;
            this.nSuccess += 1;         
            completion();
        }.bind(result);

        var error = function(index) {
            this.nError +=1;
            completion(fragments);
        }.bind(result);


        for (var iHash=0; iHash < hashes.length; iHash++) {
            var hash = hashes[iHash];
            var onSuccess = onSuccess()  
            dhtClient.get(hash) 
        }
    }

    this.getMetadata = function(location) {

    }
}

if (typeof module !== "undefined"){
    module.exports.randomSymmetricKey = randomSymmetricKey;
    module.exports.SymmetricKey = SymmetricKey;
}
 
