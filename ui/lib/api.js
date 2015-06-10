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
    scrypt(hash.digest(), salt, 17, 8, 64, 1000, function(keyBytes) {
	var bothBytes = nacl.util.decodeBase64(keyBytes);
	var signBytes = bothBytes.subarray(0, 32);
	var boxBytes = bothBytes.subarray(32, 64);
	return cb(new User(nacl.sign.keyPair.fromSeed(signBytes), nacl.box.keyPair.fromSecretKey(new Uint8Array(boxBytes))));
    }, 'base64');
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

function toKeyPair(pub, sec) {
    return {publicKey:pub, secretKey:sec};
}

/////////////////////////////
// SymmetricKey methods

// () => Uint8Array
function randomIV() {
    return nacl.randomBytes(24);
}

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
}
SymmetricKey.NONCE_BYTES = 24;
SymmetricKey.random = function() {
    return new SymmetricKey(nacl.randomBytes(32));
}

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

function concat(a, b) {
    var r = new Uint8Array(a.length+b.length);
    for (var i=0; i < a.length; i++)
	r[i] = a[i];
    for (var i=0; i < b.length; i++)
	r[a.length+i] = b[i];
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

function post(path, data, onSuccess, onError) {

    var request = new XMLHttpRequest();
    request.open("POST" , path);

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
    this.put = function(keyData, valueData, username, sharingKeyData, mapKeyData, proofData, onSuccess, onError) {
        var arrays = [keyData, valueData, username, sharingKeyData, mapKeyData, proofData];
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        for (var iArray=0; iArray < arrays.length; iArray++) 
            buffer.writeArray(arrays[iArray]);
        post("dht/put", buffer, onSuccess, onError);
    };
    //
    //get
    //
    this.get = function(keyData, onSuccess, onError) { 
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeArray(keyData);
        post("dht/get", buffer, onSuccess, onError); 
    };
    
    //
    //contains
    //
    this.contains = function(keyData, onSuccess, onError) {
        var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
        buffer.writeArray(keyData);
        post("dht/contains", buffer, onSuccess, onError); 
    };
}

function CoreNodeClient() {
        //String -> fn- >fn -> void
        this.getPublicKey = function(username, onSuccess, onError) {
            var buffer = new  ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeString(username);
            post("core/getPublicKey", buffer, onSuccess, onError);
        };
        
        //String -> Uint8Array -> Uint8Array -> fn -> fn -> void
        this.updateStaticData = function(username, signedHash, staticData, onSuccess, onError) {
            var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeString(username);
            buffer.writeArray(signedHash);
            buffer.writeArray(staticData);
            post("core/updateStaticData", buffer, onSuccess, onError); 
        };

        //String -> fn- >fn -> void
        this.getStaticData = function(username, onSuccess, onError) {
            var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeString(username);
            post("core/getStaticData", buffer, onSuccess, onError);
        };

        //Uint8Array -> fn -> fn -> void
        this.getUsername = function(publicKey, onSuccess, onError) {
            var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeArray(publicKey);
            post("core/getUsername", new Uint8Array(buffer.toArray()), onSuccess, onError);
        };

        
        //String -> Uint8Array -> Uint8Array -> fn -> fn -> void
        this.addUsername = function(username, encodedUserKey, signedHash, staticData) {
            var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeString(username);
            buffer.writeArray(encodedUserKey);
            buffer.writeArray(signedHash);
            buffer.writeArray(staticData);
            post("core/addUsername", buffer, onSuccess, onError);
        };

        //String -> Uint8Array -> fn -> fn -> void
        this.followRequest = function( target,  encryptedPermission, onSuccess, onError) {
            var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeString(target);
            buffer.writeArray(encryptedPermission);
             post("core/followRequest", buffer, onSuccess, onError);
        };

        //String -> Uint8Array -> fn -> fn -> void
        this.getFollowRequests = function( username, onSuccess, onError) {
            var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeString(username);
            post("core/getFollowRequests", buffer, onSuccess, onError);
        };

        //String -> Uint8Array -> Uint8Array -> fn -> fn -> void
        this.removeFollowRequest = function( target,  data,  signedHash, onSuccess, onError) {
            var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeString(target);
            buffer.writeArray(data);
            buffer.writeArray(signedHash);
             post("core/removeFollowRequest", buffer, onSuccess, onError);
        };

        //String -> Uint8Array -> Uint8Array -> fn -> fn -> void
        this.allowSharingKey = function( username,  encodedSharingPublicKey,  signedHash, onSuccess, onError) {
            var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeString(username);
            buffer.writeArray(encodedSharingKey);
            buffer.writeArray(signedHash); 
            post("core/allowSharingKey", buffer, onSuccess, onError);
        };

        //String -> Uint8Array -> Uint8Array -> fn -> fn -> void
        this.banSharingKey = function( username,  encodedSharingPublicKey,  signedHash, onSuccess, onError) {
            var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeString(username);
            buffer.writeArray(encodedSharingPublicKey);
            buffer.writeArray(signedHash); 
            post("core/banSharingKey", buffer, onSuccess, onError);
        };

        //String -> Uint8Array -> Uint8Array -> Uint8Array  -> Uint8Array -> fn -> fn -> void
        this.addMetadataBlob = function( username,  encodedSharingPublicKey,  mapKey,  metadataBlob,  sharingKeySignedHash, onSuccess, onError) {
            var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeString(username);
            buffer.writeArray(encodedSharingPublicKey);
            buffer.writeArray(mapKey);
            buffer.writeArray(metadataBlob);
            buffer.writeArray(sharingKeySignedHash);
            post("core/addMetadataBlob", buffer, onSuccess, onError);
        };

        //String -> Uint8Array -> Uint8Array  -> Uint8Array -> fn -> fn -> void
        this.removeMetadataBlob = function( username,  encodedSharingKey,  mapKey,  sharingKeySignedMapKey, onSuccess, onError) {
            var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeString(username);
            buffer.writeArray(encodedSharingKey);
            buffer.writeArray(mapKey);
            buffer.writeArray(sharingKeySignedMapKey);
            post("core/removeMetadataBlob", buffer, onSuccess, onError);
        };

        //String  -> Uint8Array  -> Uint8Array -> fn -> fn -> void
        this.removeUsername = function( username,  userKey,  signedHash, onSuccess, onError) {
            var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeString(username);
            buffer.writeArray(userKey);
            buffer.writeArray(signedHash);
            post("core/removeUsername", buffer, onSuccess, onError);
        };

        //String -> fn -> fn -> void
        this.getSharingKeys = function( username, onSuccess, onError) {
            var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeString(username);
            post("core/getSharingKeys", buffer, onSuccess, onError);
        };

        //String  -> Uint8Array  -> fn -> fn -> void
        this.getMetadataBlob = function( username,  encodedSharingKey,  mapKey, onSuccess, onError) {
            var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeString(username);
            buffer.writeArray(encodedSharingKey);
            buffer.writeArray(mapKey);
            post("core/getMetadataBlob", buffer, onSuccess, onError);
        };

        //String  -> Uint8Array  -> Uint8Array -> fn -> fn -> void
        this.isFragmentAllowed = function( owner,  encodedSharingKey,  mapkey,  hash, onSuccess, onError) {
            var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeString(owner);
            buffer.writeArray(encodedSharingKey);
            buffer.writeArray(mapKey);
            buffer.writeArray(hash);
            post("core/isFragmentAllowed", buffer, onSuccess, onError);
        };


        //String -> fn -> fn -> void
        this.getQuota = function(username, onSuccess, onError) {
            var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeString(username);
            post("core/getQuota", buffer, onSuccess, onError);
        };

        //String -> fn -> fn -> void
        this.getUsage = function(username, onSuccess, onError) {
            var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeString(username);
            post("core/getUsage", buffer, onSuccess, onError);
        };


        //String  -> Uint8Array  -> Uint8Array -> fn -> fn -> void
        this.getFragmentHashes = function( username, sharingKey, mapKey, onSuccess, onError) {
            var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
            buffer.writeString(username);
            buffer.writeArray(sharingKey);
            buffer.writeArray(mapKey);
            post("core/getFragmentHashes", buffer, onSuccess, onError);
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
            
                post("core/addFragmentHashes", buffer, onSuccess, onError);
        };

        //String -> Uint8Array -> -> String -> Uint8Array -> Uint8Array -> Uint8Array -> fn -> fn -> void
        this.registerFragmentStorage = function(spaceDonor, nodeAddress,  owner,  encodedSharingKey,  hash,  signedKeyPlusHash, onSuccess, onError) {
                var buffer = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
                buffer.writeString(spaceDonor);
                buffer.writeArray(nodeAddress);
                buffer.writeString(owner);
                buffer.writeArray(encodedSharingKey);
                buffer.writeArray(hash);
                buffer.writeArray(signedKeyPlusHash);
                post("core/registerFragmentStorage", buffer, onSuccess, onError);
        };
};

function UserContext(username, user, dhtClient,  corenodeClient) {
    this.username  = username;
    this.user = user;
    this.dhtClient = dhtClient;
    this.corenodeClient = corenodeClient;

    this.isRegistered = function(cb) {
	corenodeClient.getUsername(user.getPublicKeys(), function(res){
            cb(username == res);
	});
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
 
