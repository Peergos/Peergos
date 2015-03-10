// API for the User interface to use
/////////////////////////////

/////////////////////////////
// UserPublicKey methods
function UserPublicKey(publicKey) {
    this.publicKey = publicKey;

    // ((err, publicKeyString) -> ())
    this.getPublicKey = function(cb) {
	publicKey.export_pgp_public({}, cb);
    }
    
    // (Uint8Array, (err, resString, resBuffer) -> ())
    this.encryptMessageFor = function(input, cb) {
	var params = {
                msg: input,
                encrypt_for: publicKey
            };
	kbpgp.box(params, cb);
    }
    
    // Uint8Array => Uint8Array
    this.unsignMessage = function(input, cb) {
	kbpgp.unbox({"keyfetch": this.publicKey, "raw": input}, cb);
    }
    
    this.isValidSignature = function(signedHash, raw) {
	var a = hash(raw);
	var b = unsignMessage(signedHash);
	return arraysEqual(a, b);
    }

    // Uint8Array => Uint8Array
    this.hash = function(input) {
	return kbpgp.hash.SHA256(input);
    }
}
    
/////////////////////////////
// User methods
// (string, string, (keypair) -> ())
function generateKeyPair(username, password, cb) {
    var hash = new BLAKE2s(32)
    hash.update(nacl.util.decodeUTF8(password))
    salt = nacl.util.decodeUTF8(username)
    scrypt(hash.digest(), salt, 17, 8, 32, 1000, function(keyBytes) {
		return cb(nacl.box.keyPair.fromSecretKey(nacl.util.decodeBase64(keyBytes)))
	}, 'base64');
}

    function User(keyPair) {
	UserPublicKey.call(this, keyPair);
	
	// Uint8Array => Uint8Array
	this.hashAndSignMessage = function(input, cb) {
	    signMessage(hash(input), cb);
	}
	
	// Uint8Array => Uint8Array
	this.signMessage = function(input, cb) {
	    var params = {
	        msg : input,
                sign_with: this.publicKey
            };
	    kbpgp.box(params, cb);
	}
    
	// (Uint8Array, (err, literals) -> ())
	this.decryptMessage = function(input, cb) {
	    kbpgp.unbox({"keyfetch": this.publicKey, "raw": input}, cb);
	}
    }

/////////////////////////////
// SymmetricKey methods

// () => Uint8Array
function randomSymmetricKey() {
}

// () => Uint8Array
function randomIV() {
}

function SymmetricKey() {
    

    // (Uint8Array, Uint8Array) => Uint8Array
    this.encrypt = function(data, initVector) {
    }

    // (Uint8Array, Uint8Array) => Uint8Array
    this.decrypt = function(data, initVector) {
    }
}

// byte[] input and return
function encryptBytesToBytes(input, pubKey) {
    return Java.to(encryptUint8ToUint8(input, pubKey), "byte[]");
}

// byte[] cipher and return
function decryptBytesToBytes(cipher, privKey) {
    return Java.to(decryptUint8ToUint8(cipher, privKey), "byte[]");
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
