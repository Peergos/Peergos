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
	return nacl.box(input, nonce, this.pBoxKey, us.sBoxKey).concat(nonce);
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
    this.decryptMessage = function(cipher, nonce, them) {
	return nacl.box.open(cipher, nonce, them.pBoxKey, this.sBoxKey);
    }

    this.getSecretKeys = function() {
	var tmp = new Uint8Array(this.sSignKey.length + this.sBoxKey.length);
	tmp.set(this.sSignKey, 0);
	tmp.set(this.sBoxKey, this.sSignKey.length);
	return tmp;
    }
}

function toKeyPair(pub, sec) {
    return {publicKey:pub, secretKey:sec};
}

/////////////////////////////
// SymmetricKey methods

// () => Uint8Array
function randomSymmetricKey() {
    return SymmetricKey(nacl.randomBytes(32));
}

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
            var prepared  = prepare(arrays);
            post("dht/put", prepared, onSuccess, onError);

    };
    //
    //get
    //
    this.get = function(keyData, onSuccess, onError) { 
        post("dht/get", prepared([keyData]), onSuccess, onError); 
    };
    
    //
    //contains
    //
    this.contains = function(keyData, onSuccess, onError) {
            post("dht/contains", prepared([keyData]), onSuccess, onError); 
    };
}

function CoreNodeClient() {
        //String -> fn- >fn -> void
        this.getPublicKey = function(username, onSuccess, onError) {
            post("core/getPublicKey", username, onSuccess, onError);
        };
        
        //String -> Uint8Array -> Uint8Array -> fn -> fn -> void
        this.updateStaticData = function(username, signedHash, staticData, onSuccess, onError) {
            var prepared = prepare([toUint8Array(username), signedHash, staticData]);
            post("core/updateStaticData", prepared, onSuccess, onError); 
        };

        //String -> fn- >fn -> void
        this.getStaticData = function(username, onSuccess, onError) {
            var prepared = prepare([toUint8Array(username)]);
            post("core/getStaticData", prepared, onSuccess, onError);
        };

        //Uint8Array -> fn -> fn -> void
        this.getUsername = function(publicKey, onSuccess, onError) {
            post("core/getUsername", publicKey, onSuccess, onError);
        };

        
        //String -> Uint8Array -> Uint8Array -> fn -> fn -> void
        this.addUsername = function(username, encodedUserKey, signedHash, staticData) {
            var prepared = prepare([toUint8Array(username), encodedUserKey, signedHash, staticData]);
            post("core/addUsername", prepared, onSuccess, onError);
        };

        //String -> Uint8Array -> fn -> fn -> void
        this.followRequest = function( target,  encryptedPermission, onSuccess, onError) {
             var prepared = prepare([ target,  encryptedPermission]);
             post("core/followRequest", prepared, onSuccess, onError);
        };

        //String -> Uint8Array -> fn -> fn -> void
        this.getFollowRequests = function( username, onSuccess, onError) {
             var prepared = prepare([toUint8Array(username)]);
             post("core/getFollowRequests", prepared, onSuccess, onError);
        };

        //String -> Uint8Array -> Uint8Array -> fn -> fn -> void
        this.removeFollowRequest = function( target,  data,  signedHash, onSuccess, onError) {
             var prepared = prepare([ toUint8Array(target),  data,  signedHash]);
             post("core/removeFollowRequest", prepared, onSuccess, onError);
        };

        //String -> Uint8Array -> Uint8Array -> fn -> fn -> void
        this.allowSharingKey = function( username,  encodedSharingPublicKey,  signedHash, onSuccess, onError) {
            var prepared = prepare([ toUint8Array(username),  encodedSharingPublicKey,  signedHash]);
            post("core/allowSharingKey", prepared, onSuccess, onError);
        };

        //String -> Uint8Array -> Uint8Array -> fn -> fn -> void
        this.banSharingKey = function( username,  encodedSharingPublicKey,  signedHash, onSuccess, onError) {
            var prepared = prepare([ toUint8Array(username),  encodedSharingPublicKey,  signedHash]);
            post("core/banSharingKey", prepared, onSuccess, onError);
};

        //String -> Uint8Array -> Uint8Array -> Uint8Array  -> Uint8Array -> fn -> fn -> void
        this.addMetadataBlob = function( username,  encodedSharingPublicKey,  mapKey,  metadataBlob,  sharingKeySignedHash, onSuccess, onError) {
             var prepared = prepare([ toUint8Array(username),  encodedSharingPublicKey,  mapKey,  metadataBlob,  sharingKeySignedHash]);
             post("core/addMetadataBlob", prepared, onSuccess, onError);
        };

        //String -> Uint8Array -> Uint8Array  -> Uint8Array -> fn -> fn -> void
        this.removeMetadataBlob = function( username,  encodedSharingKey,  mapKey,  sharingKeySignedMapKey, onSuccess, onError) {
            var prepared = prepare([ toUint8Array(username),  encodedSharingKey,  mapKey,  sharingKeySignedMapKey]);
            post("core/removeMetadataBlob", prepared, onSuccess, onError);
        };

        //String  -> Uint8Array  -> Uint8Array -> fn -> fn -> void
        this.removeUsername = function( username,  userKey,  signedHash, onSuccess, onError) {
            var prepared = prepare([ toUint8Array(username),  userKey,  signedHash]);
            post("core/removeUsername", prepared, onSuccess, onError);
        };

        //String -> fn -> fn -> void
        this.getSharingKeys = function( username, onSuccess, onError) {
            var prepared = prepare([ toUint8Array(username)]);
            post("core/getSharingKeys", prepared, onSuccess, onError);
        };

        //String  -> Uint8Array  -> fn -> fn -> void
        this.getMetadataBlob = function( username,  encodedSharingKey,  mapKey, onSuccess, onError) {
            var prepared = prepare([ toUint8Array(username),  encodedSharingKey,  mapKey]);
            post("core/getMetadataBlob", prepared, onSuccess, onError);
        };

        //String  -> Uint8Array  -> Uint8Array -> fn -> fn -> void
        this.isFragmentAllowed = function( owner,  encodedSharingKey,  mapkey,  hash, onSuccess, onError) {
            var prepared = prepare([ toUint8Array(owner),  encodedSharingKey,  mapkey,  hash]);
            post("core/isFragmentAllowed", prepared, onSuccess, onError);
        };


        //String -> fn -> fn -> void
        this.getQuota = function( user, onSuccess, onError) {
            var prepared = prepare([ toUint8Array(user)]);
            post("core/getQuota", prepared, onSuccess, onError);
        };

        //String -> fn -> fn -> void
        this.getUsage = function(username, onSuccess, onError) {
            var prepared = prepare([toUint8Array(username)]);
            post("core/getUsage", prepared, onSuccess, onError);
        };


        //String  -> Uint8Array  -> Uint8Array -> fn -> fn -> void
        this.getFragmentHashes = function( username, sharingKey, mapKey, onSuccess, onError) {
            var prepared = prepare([ toUint8Array(username), sharingKey, mapKey]);
            post("core/getFragmentHashes", prepared, onSuccess, onError);
        };

        //String  -> Uint8Array  -> Uint8Array -> Uint8Array -> [Uint8Array] -> Uint8Array -> fn -> fn -> void
        this.addFragmentHashes = function(username, encodedSharingPublicKey, mapKey, metadataBlob, allHashes, sharingKeySignedHash) {
                var prepared1 = prepare([toUint8Array(username), encodedSharingPublicKey, mapKey, metadatablob]);
                var prepared2 = prepare(allHashes);
                var prepared3 = prepare([sharingKeySignedHash]);
                
                var comb = new Uint8Array(prepared1.length + prepared2.length + prepared3.length);
                var current = 0;
                comb.set(prepared1, current);
                current += prepared1.length;
                comb.set(prepared2, current);
                current += prepared2.length;
                comb.set(prepared3, current);

                post("core/addFragmentHashes", comb, onSuccess, onError);

        };

        //String -> Uint8Array -> -> String -> Uint8Array -> Uint8Array -> Uint8Array -> fn -> fn -> void
        this.registerFragmentStorage = function(spaceDonor, nodeAddress,  owner,  encodedSharingKey,  hash,  signedKeyPlusHash, onSuccess, onError) {
                var prepared = prepare([toUint8Array(spaceDonor), nodeAddress, toUint8Array(owner), encodedSharingKey, hash, signedKeyPlusHash]);
                post("core/registerFragmentStorage", comb, onSuccess, onError);
        };
}
function UserContext() {

}

//String -> Uint8Array
function toUint8Array(string) {
        var array = new Uint8Array(string.length);
        for (var i=0; i < string.length; i++) 
                array[i] = string.charCodeAt(i);
        return array;
}
function prepare(arrays) {
    var length = 0;
    for (var i=0; i < arrays.length; i++)
            length += arrays[i].length;
    
    var lengths = arrays.length * 4;
    var prepared = new Uint8Array(length + lengths);
    var current = 0;
    for (var i=0; i < arrays.length; i++){
        writeInt(prepared, current, arrays[i].length);
        current +=4;
        var arr = arrays[i];
        prepared.set(arr, current);
        current += arr.length;
    } 
    return prepared;
}

//module.exports.encryptStringToHex = encryptStringToHex;
//module.exports.decryptHexToString = decryptHexToString;

if (typeof module !== "undefined"){
    module.exports.randomSymmetricKey = randomSymmetricKey;
    module.exports.SymmetricKey = SymmetricKey;
}
 
