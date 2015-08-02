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
}

UserPublicKey.fromPublicKeys = function(both) {
    var pSign = slice(both, 0, 32);
    var pBox = slice(both, 32, 64);
    return new UserPublicKey(pSign, pBox);
}

UserPublicKey.HASH_BYTES = 32;
// Uint8Array => Uint8Array
UserPublicKey.hash = function(arr) {
    return sha256(arr);
}

function createNonce(){
    return window.nacl.randomBytes(24);
}

/////////////////////////////
// User methods
// (string, string, (User -> ())
function generateKeyPairs(username, password, cb) {
    var hash = UserPublicKey.hash(nacl.util.decodeUTF8(password));
    var salt = nacl.util.decodeUTF8(username)
    
    return new Promise(function(resolve, reject) {
        scrypt(hash, salt, 17, 8, 64, 1000, function(keyBytes) {
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
    if (key.length != nacl.secretbox.keyLength)
	throw "Invalid symmetric key: "+key;
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
        var buf = new ByteArrayOutputStream();
        buf.writeString(name);
        buf.writeDouble(size);
        return buf.toByteArray();
    }

    this.getSize = function() {
        return size;
    }
}

FileProperties.deserialize = function(raw) {
    const buf = new ByteArrayInputStream(raw);
    var name = buf.readString();
    var size = buf.readDouble();
    return new FileProperties(name, size);
}

function Fragment(data) {
    this.data = data;

    this.getHash = function() {
        return UserPublicKey.hash(data);
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


function FileUploader(name, contents, key, parentLocation, parentparentKey) {
    this.props = new FileProperties(name, contents.length);
    this.chunks = [];
    if (key == null) key = SymmetricKey.random();
    for (var i=0; i < contents.length; i+= Chunk.MAX_SIZE)
        this.chunks.push(new Chunk(slice(contents, i, Math.min(contents.length, i + Chunk.MAX_SIZE)), key));

    this.upload = function(context, owner, writer) {
        var proms = [];
        const chunk0 = this.chunks[0];
        const that = this;
        for (var i=0; i < this.chunks.length; i++)
            proms.push(new Promise(function(resolve, reject) {
                const chunk = that.chunks[i];
                const encryptedChunk = chunk.encrypt();
                const fragments = encryptedChunk.generateFragments();
                console.log("Uploading chunk with %d fragments\n", fragments.length);
                var hashes = [];
                for (var f in fragments)
                    hashes.push(fragments[f].getHash());
                const retriever = new EncryptedChunkRetriever(chunk.nonce, encryptedChunk.getAuth(), hashes, i+1 < that.chunks.length ? new Location(owner, writer, that.chunks[i+1].mapKey) : null);
                const metaBlob = FileAccess.create(chunk.key, that.props, retriever, parentLocation, parentparentKey);
                resolve(context.uploadChunk(metaBlob, fragments, owner, writer, chunk.mapKey));
            }));
        return Promise.all(proms).then(function(res){
            return Promise.resolve(new Location(owner, writer, chunk0.mapKey));
        });
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

function DHTClient() {
    //
    //put
    //
    this.put = function(keyData, valueData, owner, sharingKeyData, mapKeyData, proofData) {
        var arrays = [keyData, valueData, owner, sharingKeyData, mapKeyData, proofData];
        var buffer = new ByteArrayOutputStream();
        buffer.writeInt(0); // PUT Message
        for (var iArray=0; iArray < arrays.length; iArray++) 
            buffer.writeArray(arrays[iArray]);
        return postProm("dht/put", buffer.toByteArray()).then(function(resBuf){
            var res = new ByteArrayInputStream(resBuf).readInt();
            if (res == 1) return Promise.resolve(true);
            return Promise.reject("Fragment upload failed");
        });
    };
    //
    //get
    //
    this.get = function(keyData) { 
        var buffer = new ByteArrayOutputStream();
        buffer.writeInt(1); // GET Message
        buffer.writeArray(keyData);
        return postProm("dht/get", buffer.toByteArray()).then(function(res) {
            var buf = new ByteArrayInputStream(res);
            var success = buf.readInt();
            if (success == 1)
                return Promise.resolve(buf.readArray());
            return Promise.reject("Fragment download failed");
        });
    };
    
    //
    //contains
    //
    this.contains = function(keyData) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeInt(2); // CONTAINS Message
        buffer.writeArray(keyData);
        return postProm("dht/contains", buffer.toByteArray()); 
    };
}

function CoreNodeClient() {
    //String -> fn- >fn -> void
    this.getPublicKey = function(username) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeInt(username.length);
        buffer.writeString(username);
        return postProm("core/getPublicKey", buffer.toByteArray());
    };
    
    //UserPublicKey -> Uint8Array -> Uint8Array -> fn -> fn -> void
    this.updateStaticData = function(owner, signedStaticData) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeArray(owner.getPublicKeys());
        buffer.writeArray(signedStaticData);
        return postProm("core/updateStaticData", buffer.toByteArray()); 
    };
    
    //String -> fn- >fn -> void
    this.getStaticData = function(owner) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeArray(owner.getPublicKeys());
        return postProm("core/getStaticData", buffer.toByteArray());
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
    
    
    //String -> Uint8Array -> Uint8Array -> fn -> fn ->  boolean 
    this.addUsername = function(username, encodedUserKey, signed, staticData) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeString(username);
        buffer.writeArray(encodedUserKey);
        buffer.writeArray(signed);
        buffer.writeArray(staticData);
        return postProm("core/addUsername", buffer.toByteArray()).then(
	    function(res){
		return Promise.resolve(res[0]);
	    });
    };
    
    //Uint8Array -> Uint8Array -> fn -> fn -> void
    this.followRequest = function( target,  encryptedPermission) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeArray(target);
        buffer.writeArray(encryptedPermission);
        return postProm("core/followRequest", buffer.toByteArray());
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
    
    //String -> Uint8Array -> Uint8Array -> fn -> fn -> void
    this.removeFollowRequest = function( target,  data,  signed) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeString(target);
        buffer.writeArray(data);
        buffer.writeArray(signed);
        return postProm("core/removeFollowRequest", buffer.toByteArray());
    };

    //String -> Uint8Array -> Uint8Array -> fn -> fn -> void
    this.allowSharingKey = function(owner, signedWriter) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeArray(owner);
        buffer.writeArray(signedWriter); 
        return postProm("core/allowSharingKey", buffer.toByteArray());
    };
    
    //String -> Uint8Array -> Uint8Array -> fn -> fn -> void
    this.banSharingKey = function(username,  encodedSharingPublicKey,  signedHash) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeString(username);
        buffer.writeArray(encodedSharingPublicKey);
        buffer.writeArray(signedHash); 
        return postProm("core/banSharingKey", buffer.toByteArray());
    };

    //Uint8Array -> Uint8Array -> Uint8Array -> Uint8Array  -> Uint8Array -> fn -> fn -> void
    this.addMetadataBlob = function( owner,  encodedSharingPublicKey,  mapKey,  metadataBlob,  sharingKeySignedHash) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeArray(owner);
        buffer.writeArray(encodedSharingPublicKey);
        buffer.writeArray(mapKey);
        buffer.writeArray(metadataBlob);
        buffer.writeArray(sharingKeySignedHash);
        return postProm("core/addMetadataBlob", buffer.toByteArray()).then(function(res) {
            return Promise.resolve(new ByteArrayInputStream(res).readByte() == 1);
        });
    };
    
    //String -> Uint8Array -> Uint8Array  -> Uint8Array -> fn -> fn -> void
    this.removeMetadataBlob = function( owner,  writer,  mapKey) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeArray(owner.getPublicKeys());
        buffer.writeArray(writer.getPublicKeys());
        buffer.writeArray(mapKey);
        buffer.writeArray(writer.signMessage(mapKey));
        return postProm("core/removeMetadataBlob", buffer.toByteArray());
    };

    //String  -> Uint8Array  -> Uint8Array -> fn -> fn -> void
    this.removeUsername = function( username,  userKey,  signedHash) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeString(username);
        buffer.writeArray(userKey);
        buffer.writeArray(signedHash);
        return post("core/removeUsername", buffer.toByteArray());
    };

    //String -> fn -> fn -> void
    this.getSharingKeys = function( username) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeString(username);
        return postProm("core/getSharingKeys", buffer.toByteArray());
    };
    
    //String  -> Uint8Array  -> fn -> fn -> void
    this.getMetadataBlob = function( owner,  encodedSharingKey,  mapKey) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeArray(owner.getPublicKeys());
        buffer.writeArray(encodedSharingKey.getPublicKeys());
        buffer.writeArray(mapKey);
        return postProm("core/getMetadataBlob", buffer.toByteArray());
    };
    
    //String  -> Uint8Array  -> Uint8Array -> fn -> fn -> void
    this.isFragmentAllowed = function( owner,  encodedSharingKey,  mapkey,  hash) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeString(owner);
        buffer.writeArray(encodedSharingKey);
        buffer.writeArray(mapKey);
        buffer.writeArray(hash);
        return postProm("core/isFragmentAllowed", buffer.toByteArray());
    };
    
    //String -> fn -> fn -> void
    this.getQuota = function(user) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeArray(user.getPublicKeys());
        return postProm("core/getQuota", buffer.toByteArray()).then(function(res) {
            var buf = new ByteArrayInputStream(res);
            var quota = buf.readInt() << 32;
            quota += buf.readInt();
            return Promise.resolve(quota);
        });
    };
    
    //String -> fn -> fn -> void
    this.getUsage = function(username) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeString(username);
        return postProm("core/getUsage", buffer.toByteArray());
    };
    
    //String  -> Uint8Array  -> Uint8Array -> fn -> fn -> void
    this.getFragmentHashes = function( username, sharingKey, mapKey) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeString(username);
        buffer.writeArray(sharingKey);
        buffer.writeArray(mapKey);
        return postProm("core/getFragmentHashes", buffer.toByteArray());
    };

    //String  -> Uint8Array  -> Uint8Array -> Uint8Array -> [Uint8Array] -> Uint8Array -> fn -> fn -> void
    this.addFragmentHashes = function(username, encodedSharingPublicKey, mapKey, metadataBlob, allHashes, sharingKeySignedHash) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeString(username);
        buffer.writeArray(encodedShaaringPublicKey);
        buffer.writeArray(mapKey);
        buffer.writeArray(metadataBlob);

        buffer.writeInt(allHashes.length);
        for (var iHash=0; iHash  <  allHashes.length; iHash++) 
            buffer.writeArray(allHashes[iHash]);

        buffer.writeArray(sharingKeySignedHash);
        
        return postProm("core/addFragmentHashes", buffer.toByteArray());
    };

    
    this.registerFragmentStorage = function(spaceDonor, address, port, owner, signedKeyPlusHash) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeArray(spaceDonor.getPublicKeys());
        buffer.writeArray(address);
        buffer.writeInt(port);
        buffer.writeArray(owner.getPublicKeys());
        buffer.writeArray(signedKeyPlusHash);
        return postProm("core/registerFragmentStorage", buffer.toByteArray());
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
        var buf = new ByteArrayOutputStream();
        buf.writeInt(this.staticData.length);
        for (var i = 0; i < this.staticData.length; i++)
            buf.writeArray(this.staticData[i][1].serializeAndEncrypt(this.user, this.user));
        return buf.toByteArray();
    }

    this.register = function() {
        console.log("registering "+username);
        var rawStatic = this.serializeStatic();
        var signed = user.signMessage(concat(nacl.util.decodeUTF8(username), user.getPublicKeys(), rawStatic));
        return corenodeClient.addUsername(username, user.getPublicKeys(), signed, rawStatic);
    }

    this.createEntryDirectory = function(directoryName) {
        var writer = User.random();
        var rootMapKey = window.nacl.randomBytes(32); // root will be stored under this in the core node
        var rootRKey = SymmetricKey.random();

        // add a note to our static data so we know who we sent the private key to
        // and authorise the writer key
        const rootPointer = new ReadableFilePointer(this.user, writer, rootMapKey, rootRKey);
        const entry = new EntryPoint(rootPointer, this.username, [], []);
        return this.addSharingKey(writer).then(function(res) {
            return this.addToStaticData(entry);
        }.bind(this)).then(function(res) {
            var root = DirAccess.create(writer, rootRKey, new FileProperties(directoryName, 0));
            return this.uploadChunk(root, [], this.user, writer, rootMapKey);
        }.bind(this));
    }

    this.sendFollowRequest = function(targetUser) {
        // create sharing keypair and give it write access
        var sharing = User.random();
        var rootMapKey = window.nacl.randomBytes(32);

        // add a note to our static data so we know who we sent the private key to
        var friendRoot = new ReadableFilePointer(user, sharing, rootMapKey, SymmetricKey.random());
        return this.addSharingKey(sharing).then(function(res) {
            return this.corenodeClient.getUsername(targetUser.getPublicKeys()).then(function(name) {
                const entry = new EntryPoint(friendRoot, this.username, [], [name]);
                return this.addToStaticData(entry).then(function(res) {
                    // send details to allow friend to share with us (i.e. we follow them)

                    // create a tmp keypair whose public key we can append to the request without leaking information
                    var tmp = User.random();
                    var payload = entry.serializeAndEncrypt(tmp, targetUser);
                    return corenodeClient.followRequest(targetUser.getPublicKeys(), concat(tmp.pBoxKey, payload));
                });
            }.bind(this));
        }.bind(this));
    }.bind(this);

    this.addSharingKey = function(pub) {
        var signed = user.signMessage(pub.getPublicKeys());
        return corenodeClient.allowSharingKey(user.getPublicKeys(), signed);
    }

    this.addToStaticData = function(entry) {
        this.staticData.push([entry.pointer.writer, entry]);
        var rawStatic = new Uint8Array(this.serializeStatic());
        return corenodeClient.updateStaticData(user, user.signMessage(rawStatic));
    }

    this.getFollowRequests = function() {
        return corenodeClient.getFollowRequests(user.getPublicKeys());
    }

    this.decodeFollowRequest = function(raw) {
        var pBoxKey = new Uint8Array(32);
        for (var i=0; i < 32; i++)
            pBoxKey[i] = raw[i]; // signing key is not used
        var tmp = new UserPublicKey(null, pBoxKey);
        var buf = new ByteArrayInputStream(raw);
        buf.read(32);
        var cipher = new Uint8Array(buf.read(raw.length - 32));
        return EntryPoint.decryptAndDeserialize(cipher, user, tmp);
    }
    
    this.uploadFragment = function(f, targetUser, sharer, mapKey) {
        return dhtClient.put(f.getHash(), f.getData(), targetUser.getPublicKeys(), sharer.getPublicKeys(), mapKey, sharer.signMessage(concat(sharer.getPublicKeys(), f.getHash())));
    }

    this.uploadChunk = function(metadata, fragments, owner, sharer, mapKey) {
        var buf = new ByteArrayOutputStream();
        metadata.serialize(buf);
        var metaBlob = buf.toByteArray();
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

    this.getRoots = function() {
        const context = this;
        return corenodeClient.getStaticData(user).then(function(raw) {
            var buf = new ByteArrayInputStream(raw);
            var totalStaticLength = buf.readInt();
            var count = buf.readInt();
            var res = [];
            for (var i=0; i < count; i++) {
                var entry = EntryPoint.decryptAndDeserialize(buf.readArray(), context.user, context.user);
                res.push(entry);
            }
            // download the metadata blobs for these entry points
            var proms = [];
            for (var i=0; i < res.length; i++)
            proms[i] = corenodeClient.getMetadataBlob(res[i].pointer.owner, res[i].pointer.writer, res[i].pointer.mapKey);
            return Promise.all(proms).then(function(result) {
                var entryPoints = [];
                for (var i=0; i < result.length; i++) {
                    if (result[i].byteLength > 8) {
                        var unwrapped = new ByteArrayInputStream(result[i]).readArray();
                        entryPoints.push([res[i], FileAccess.deserialize(unwrapped)]);
                    } else
                        entryPoints.push([res[i], null]);
                }
                return Promise.resolve(entryPoints);
            });
        });
    }
// [SymmetricLocationLink], SymmetricKey
    this.retrieveAllMetadata = function(links, baseKey) {
        var proms = [];
        for (var i=0; i < links.length; i++) {
            var loc = links[i].targetLocation(baseKey);
            proms[i] = corenodeClient.getMetadataBlob(loc.owner, loc.writer, loc.mapKey);
        }
        return Promise.all(proms).then(function(rawBlobs) {
            var accesses = [];
            for (var i=0; i < rawBlobs.length; i++) {
                var unwrapped = new ByteArrayInputStream(rawBlobs[i]).readArray();
                accesses[i] = [links[i].toReadableFilePointer(baseKey), unwrapped.length > 0 ? FileAccess.deserialize(unwrapped) : null];
            }
	    const res = [];
	    for (var i=0; i < accesses.length; i++)
		if (accesses[i][1] != null)
		    res.push(accesses[i]);
            return Promise.resolve(res);
        });
    }

    this.getMetadata = function(loc) {
        return corenodeClient.getMetadataBlob(loc.owner, loc.writer, loc.mapKey).then(function(buf) {
            var unwrapped = new ByteArrayInputStream(buf).readArray();
            return FileAccess.deserialize(unwrapped);
        });
    }

    this.downloadFragments = function(hashes, nRequired) {
        var result = {}; 
        result.fragments = [];
        result.nError = 0;
        
        var proms = [];
        for (var i=0; i < hashes.length; i++)
            proms.push(dhtClient.get(hashes[i]).then(function(val) {
                result.fragments.push(val);
                console.log("Got Fragment.");
            }).catch(function() {
                result.nError++;
            }));

        return Promise.all(proms).then(function (all) {
            console.log("All done.");
            if (result.fragments.length < nRequired)
                throw "Not enough fragments!";
            return Promise.resolve(result.fragments);
        });
    }
}

function ReadableFilePointer(owner, writer, mapKey, baseKey) {
    this.owner = owner; //UserPublicKey
    this.writer = writer; //User / UserPublicKey
    this.mapKey = mapKey; //ByteArrayWrapper
    this.baseKey = baseKey; //SymmetricKey

    this.serialize = function() {
        var bout = new ByteArrayOutputStream();
        bout.writeArray(owner.getPublicKeys());
        if (writer instanceof User)
            bout.writeArray(writer.getSecretKeys());
        else
            bout.writeArray(writer.getPublicKeys());
        bout.writeArray(mapKey);
        bout.writeArray(baseKey.key);
        return bout.toByteArray();
    }

    this.isWritable = function() {
        return this.writer instanceof User;
    }
}

ReadableFilePointer.deserialize = function(arr) {
    const bin = new ByteArrayInputStream(arr);
    const owner = bin.readArray();
    const writerRaw = bin.readArray();
    const mapKey = bin.readArray();
    const rootDirKeySecret = bin.readArray();
    const writer = writerRaw.length == window.nacl.box.secretKeyLength + window.nacl.sign.secretKeyLength ?
    User.fromSecretKeys(writerRaw) :
    UserPublicKey.fromPublicKeys(writerRaw);
    return new ReadableFilePointer(UserPublicKey.fromPublicKeys(owner), writer, mapKey, new SymmetricKey(rootDirKeySecret));
}

//ReadableFilePointer, FileAccess
function RetrievedFilePointer(pointer, access) {
    this.filePointer = pointer;
    this.fileAccess = access;

    this.remove = function(context, parentRetrievedFilePointer) {
	if (!this.filePointer.isWritable())
	    return Promise.resolve(false);
	if (!this.fileAccess.isDirectory())
	    return this.fileAccess.removeFragments(context).then(function() {
		context.corenodeClient.removeMetadataBlob(this.filePointer.owner, this.filePointer.writer, this.filePointer.mapKey);
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
		return context.corenodeClient.removeMetadataBlob(this.filePointer.owner, this.filePointer.writer, this.filePointer.mapKey);
	    }.bind(this)).then(function() {
		// remove from parent
		if (parentRetrievedFilePointer != null)
		    parentRetrievedFilePointer.fileAccess.removeChild(this, parentRetrievedFilePointer.filePointer, context);
	    });
	}.bind(this));
    }.bind(this);
}

// ReadableFilePinter, String, [String], [String]
function EntryPoint(pointer, owner, readers, writers) {
    this.pointer = pointer;
    this.owner = owner;
    this.readers = readers;
    this.writers = writers;

    // User, UserPublicKey
    this.serializeAndEncrypt = function(user, target) {
        return target.encryptMessageFor(this.serialize(), user);
    }

    this.serialize = function() {
        const dout = new ByteArrayOutputStream();
        dout.writeArray(this.pointer.serialize());
        dout.writeString(this.owner);
        dout.writeInt(this.readers.length);
        for (var i = 0; i < this.readers.length; i++) {
            dout.writetring(this.readers[i]);
        }
        dout.writeInt(this.writers.length);
        for (var i=0; i < this.writers.length; i++) {
            dout.writeString(this.writers[i]);
        }
        return dout.toByteArray();
    }
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

function SymmetricLink(link) {
    this.link = slice(link, SymmetricKey.NONCE_BYTES, link.length);
    this.nonce = slice(link, 0, SymmetricKey.NONCE_BYTES);

    this.serialize = function() {
    return concat(this.nonce, this.link);
    }

    this.target = function(from) {
    var encoded = from.decrypt(this.link, this.nonce);
    return new SymmetricKey(encoded);
    }
}
SymmetricLink.fromPair = function(from, to, nonce) {
    return new SymmetricLink(concat(nonce, from.encrypt(to.key, nonce)));
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
        return new SymmetricKey(encoded);
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
    var link = concat(nonce, fromKey.encrypt(toKey.key, nonce));
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
	// TODO delete fragments
	return Promise.resolve(true);
    }

    this.getFileProperties = function(parentKey) {
        var nonce = slice(this.properties, 0, SymmetricKey.NONCE_BYTES);
        var cipher = slice(this.properties, SymmetricKey.NONCE_BYTES, this.properties.length);
        return FileProperties.deserialize(this.getMetaKey(parentKey).decrypt(cipher, nonce));
    }

    this.getParent = function(parentKey, context) {
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
	    return context.uploadChunk(dira, [], writableFilePointer.owner, writableFilePointer.writer, writableFilePointer.mapKey);
	} else {
	    metaKey = this.getMetaKey(writableFilePointer.baseKey);
	    const nonce = metaKey.createNonce();
	    const fa = new FileAccess(this.parent2meta, concat(nonce, metaKey.encrypt(newProps.serialize(), nonce)), this.retriever, this.parentLink);
	    return context.uploadChunk(fa, [], writableFilePointer.owner, writableFilePointer.writer, writableFilePointer.mapKey);
	}
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

function DirAccess(subfolders2files, subfolders2parent, subfolders, files, parent2meta, properties, retriever) {
    FileAccess.call(this, parent2meta, properties, retriever);
    this.subfolders2files = subfolders2files;
    this.subfolders2parent = subfolders2parent;
    this.subfolders = subfolders;
    this.files = files;

    this.superSerialize = this.serialize;
    this.serialize = function(bout) {
        this.superSerialize(bout);
        bout.writeArray(subfolders2parent.serialize());
        bout.writeArray(subfolders2files.serialize());
        bout.writeInt(0);
        bout.writeInt(subfolders.length)
        for (var i=0; i < subfolders.length; i++)
            bout.writeArray(subfolders[i].serialize());
        bout.writeInt(files.length)
        for (var i=0; i < files.length; i++)
            bout.writeArray(files[i].serialize());
    }

    // Location, SymmetricKey, SymmetricKey
    this.addFile = function(location, ourSubfolders, targetParent) {
        const filesKey = subfolders2files.target(ourSubfolders);
        var nonce = filesKey.createNonce();
        var loc = location.encrypt(filesKey, nonce);
        var link = concat(nonce, filesKey.encrypt(targetParent.key, nonce));
        var buf = new ByteArrayOutputStream();
        buf.writeArray(link);
        buf.writeArray(loc);
        this.files.push(SymmetricLocationLink.create(filesKey, targetParent, location));
    }

    this.removeChild = function(childRetrievedPointer, readablePointer, context) {
	if (childRetrievedPointer.fileAccess.isDirectory()) {
	    const newsubfolders = [];
	    for (var i=0; i < subfolders.length; i++) {
		const target = subfolders[i].targetLocation(readablePointer.baseKey);
		var keep = true;
		if (arraysEqual(target.mapKey, childRetrievedPointer.filePointer.mapKey))
		    if (arraysEqual(target.writer.getPublicKeys(), childRetrievedPointer.filePointer.writer.getPublicKeys()))
			if (arraysEqual(target.owner.getPublicKeys(), childRetrievedPointer.filePointer.owner.getPublicKeys()))
			    keep = false;
		if (keep)
		    newsubfolders.push(subfolders[i]);
	    }
	    this.subfolders = newsubfolders;
	} else {
	    const newfiles = [];
	    const filesKey = subfolders2files.target(readablePointer.baseKey)
	    for (var i=0; i < files.length; i++) {
		const target = files[i].targetLocation(filesKey);
		var keep = true;
		if (arraysEqual(target.mapKey, childRetrievedPointer.filePointer.mapKey))
		    if (arraysEqual(target.writer.getPublicKeys(), childRetrievedPointer.filePointer.writer.getPublicKeys()))
			if (arraysEqual(target.owner.getPublicKeys(), childRetrievedPointer.filePointer.owner.getPublicKeys()))
			    keep = false;
		if (keep)
		    newfiles.push(files[i]);
	    }
	    this.files = newfiles;
	}
	return context.uploadChunk(this, [], readablePointer.owner, readablePointer.writer, readablePointer.mapKey);
    }

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

    this.getParentKey = function(subfoldersKey) {
        return this.subfolders2parent.target(subfoldersKey);
    }

    this.getFilesKey = function(subfoldersKey) {
        return this.subfolders2files.target(subfoldersKey);
    }

    //String, UserContext, User -> 
    this.mkdir  = function(name, userContext, writer, ourMapKey, baseKey) {
        if (!(writer instanceof User))
            throw "Can't modify a directory without write permission (writer must be a User)!";    
        const dirReadKey = SymmetricKey.random();
        const dirMapKey = window.nacl.randomBytes(32); // root will be stored under this in the core node
        const dir = DirAccess.create(null, dirReadKey, new FileProperties(name, 0));
	const that = this;
	    return userContext.uploadChunk(dir, [], userContext.user, writer, dirMapKey)
                .then(function(success) {
                    if (success) {
                        that.addSubdir(new Location(userContext.user, writer, dirMapKey), baseKey, dirReadKey);
			// now upload the changed metadata blob for dir
			return userContext.uploadChunk(that, [], userContext.user, writer, ourMapKey);
                    }
		    return Promise.resolve(false);
                });
    }

    this.addSubdir = function(location, ourSubfolders, targetBaseKey) {
        var nonce = ourSubfolders.createNonce();
        var loc = location.encrypt(ourSubfolders, nonce);
        var link = concat(nonce, ourSubfolders.encrypt(targetBaseKey.key, nonce));
        var buf = new ByteArrayOutputStream();
        buf.writeArray(link);
        buf.writeArray(loc);
        this.subfolders.push(new SymmetricLocationLink(buf.toByteArray()));
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
                         subfolders, files, base.parent2meta, base.properties, base.retriever);
}

// User, SymmetricKey, FileProperties
//TODO remove owner arg.
DirAccess.create = function(owner, subfoldersKey, metadata) {
    var metaKey = SymmetricKey.random();
    var parentKey = SymmetricKey.random();
    var filesKey = SymmetricKey.random();
    var metaNonce = metaKey.createNonce();
    return new DirAccess(SymmetricLink.fromPair(subfoldersKey, filesKey, subfoldersKey.createNonce()),
             SymmetricLink.fromPair(subfoldersKey, parentKey, subfoldersKey.createNonce()),
             [], [],
             SymmetricLink.fromPair(parentKey, metaKey, parentKey.createNonce()),
             concat(metaNonce, metaKey.encrypt(metadata.serialize(), metaNonce))
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

    this.getFile = function(context, dataKey) {
        const stream = this;
        return this.getChunkInputStream(context, dataKey).then(function(chunk) {
            return Promise.resolve(new LazyInputStreamCombiner(stream, context, dataKey, chunk));
        });
    }

    this.getNext = function() {
        return nextChunk;
    }

    this.getChunkInputStream = function(context, dataKey) {
        var fragmentsProm = context.downloadFragments(fragmentHashes);
        return fragmentsProm.then(function(fragments) {
            fragments = Erasure.reorder(fragments, fragmentHashes);
            var cipherText = Erasure.recombine(fragments, Chunk.MAX_SIZE, EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);
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
        buf.writeByte(nextChunk != null ? 1 : 0);
        if (nextChunk != null)
            buf.write(nextChunk.serialize());
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

function LazyInputStreamCombiner(stream, context, dataKey, chunk) {
    if (!chunk)
	throw "Invalid current chunk!";
    this.context = context;
    this.dataKey = dataKey;
    this.current = chunk;
    this.index = 0;
    this.next = stream.getNext();

    this.getNextStream = function() {
        if (this.next != null) {
            const lazy = this;
            return context.getMetadata(this.next).then(function(meta) {
                var nextRet = meta.retriever;
                lazy.next = nextRet.getNext();
                return nextRet.getChunkInputStream(context, dataKey);
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
        return this.getNextStream().then(function(chunk){
            lazy.index = 0;
            lazy.current = chunk;
            return lazy.read(len-toRead, res, offset + toRead);
        });
    }
}

const GF = new (function(){
    this.size = 256;
    this.expa = new Uint8Array(2*this.size);
    this.loga = new Uint8Array(this.size);
    this.expa[0] = 1;
    var x = 1;
    for (var i=1; i < 255; i++)
    {
        x <<= 1;
        // field generator polynomial is p(x) = x^8 + x^4 + x^3 + x^2 + 1
        if ((x & this.size) != 0)
            x ^= (this.size | 0x1D); // x^8 = x^4 + x^3 + x^2 + 1  ==> 0001_1101
        this.expa[i] = x;
        this.loga[x] = i;
    }
    for (var i=255; i < 512; i++)
        this.expa[i] = this.expa[i-255];

    this.mask = function()
    {
        return this.size-1;
    }

    this.exp = function(y)
    {
        return this.exp[y];
    }

    this.mul = function(x, y)
    {
        if ((x==0) || (y==0))
            return 0;
        return this.expa[this.loga[x]+this.loga[y]];
    }

    this.div = function(x, y)
    {
        if (y==0)
            throw new IllegalStateException("Divided by zero! Blackhole created.. ");
        if (x==0)
            return 0;
        return this.expa[this.loga[x]+255-this.loga[y]];
    }
})();

// Uint8Array, GaloisField
GaloisPolynomial = function(coefficients, f) {
    this.coefficients = coefficients;
    this.f= f;

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
GaloisPolynomial.generator = function(nECSymbols, f)
{
    const one = new Uint8Array(1);
    one[0] = 1;
    var g = new GaloisPolynomial(one, f);
    for (var i=0; i < nECSymbols; i++) {
	var multiplicand = new Uint8Array(2);
	multiplicand[0] = 1;
	multiplicand[0] = f.exp(i);
	
        g = g.mul(new GaloisPolynomial(multiplicand, f));
    }
    return g;
}

// (Uint8Array, int, GaloisField) -> Uint8Array
GaloisPolynomial.encode = function(input, nEC, f)
{
    const gen = GaloisPolynomial.generator(nEC, f);
    const res = new Uint8Array(input.length + nEC);
    for (var i=0; i < input.length; i++)
	res[i] = input[i];
    for (var i=0; i < input.length; i++)
    {
        const c = res[i];
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
        res[i] = poly.eval(f.exp(i));
    return res;
}

// (Uint8Array, Uint8Array, Int[], GaloisField) -> ()
GaloisPolynomial.correctErrata = function(input, synd, pos, f)
{
    if (pos.length == 0)
        return;
    const one = new Uint8Array(1);
    one[0] = 1;
    var q = new GaloisPolynomial(one, f);
    for (var j=0; j < pos.length; j++)
    {
	var i = pos[j];
        const x = f.exp(input.length - 1 - i);
        q = q.mul(GaloisPolynomial.create([x, 1], f));
    }
    var t = new Uint8Array(pos.size());
    for (var i=0; i < t.length; i++)
        t[i] = synd[t.length-1-i];
    var p = new GaloisPolynomial(t, f).mul(q);
    t = new Uint8Array(pos.size());
    for (var i=0; i < t.length; i++)
	t[i] = p.coefficients[i + p.order()-t.length];
    p = new GaloisPolynomial(t, f);
    t = new int[(q.order()- (q.order() & 1))/2];
    for (var i=q.order() & 1; i < q.order(); i+= 2)
        t[i/2] = q.coefficients[i];
    const qprime = new GaloisPolynomial(t,f);
    for (var j=0; j < pos.length; j++)
    {
	const i = pos[j];
        const x = f.exp(i + f.size() - input.length);
        const y = p.eval(x);
        const z = qprime.eval(f.mul(x, x));
        input[i] ^= f.div(y, f.mul(x, z));
    }
}

// (Int[], int, GaloisField) -> Int[]
GaloisPolynomial.findErrors = function(synd, nmess, f)
{
    const errPoly = GaloisPolynomial.create([1], f);
    const oldPoly = GaloisPolynomial.create([1], f);
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
    const errs = errPoly.order()-1;
    if (2*errs > synd.length)
        throw "Too many errors to correct! ("+errs+")";
    const errorPos = [];
    for (var i=0; i < nmess; i++)
        if (errPoly.eval(f.exp(f.size() - 1 - i)) == 0)
            errorPos.push(nmess - 1 - i);
    if (errorPos.length != errs)
        throw "couldn't find error positions! ("+errorPos.size()+"!="+errs+") ( missing fragments)";
    return errorPos;
}

// (Uint8Array, int, GaloisField) -> Uint8Array
GaloisPolynomial.decode = function(message, nec, f)
{
    const out = slice(message, 0, message.length);
    const synd = GaloisPolynomial.syndromes(out, nec, f);
    var max = 0;
    for (var j=0; j < synd.length; j++)
        if (synd[j] > max)
            max = synd[j];
        if (max == 0)
            return out;
    const errPos = GaloisPolynomial.findErrors(synd, out.length, f);
    GaloisPolynomial.correctErrata(out, synd, errPos, f);
    return out;
}
GaloisPolynomial.create = function(coeffs, f) {
    const c = new Uint8Array(coeffs.length);
    for (var i=0; i < coeffs.length; i++)
	c[i] = coeffs[i];
    return new GaloisPolynomial(c, f);
}

const Erasure = {};
Erasure.reorder = function(fragments, hashes) {
    var hashMap = new Map(); //ba dum che
    for (var i=0; i < hashes.length; i++)
        hashMap.set(nacl.util.encodeBase64(hashes[i]), i); // Seems Map can't handle array contents equality
    var res = [];
    for (var i=0; i < fragments.length; i++) {
        var hash = nacl.util.encodeBase64(UserPublicKey.hash(fragments[i]));
        var index = hashMap.get(hash);
        res[index] = fragments[i];
    }
    return res;
}
var doErasure = true;
if (!doErasure) {
Erasure.split = function(input, originalBlobs, allowedFailures) {
    //TO DO port erasure code implementation and Galois groups
    var size = (input.length/originalBlobs)|0;
    if (size*originalBlobs != input.length)
	size++;
    var bfrags = [];
    for (var i=0; i < input.length/size; i++)
        bfrags.push(slice(input, i*size, Math.min(input.length, (i+1)*size)));
    return bfrags;
}
Erasure.recombine = function(fragments, truncateTo, originalBlobs, allowedFailures) {
    var buf = new ByteArrayOutputStream();
    // assume we have all fragments in original order for now
    for (var i=0; i < originalBlobs; i++)
	buf.write(fragments[i]);
    return buf.toByteArray();
}
} else {
// (Uint8Array, int, int)-> Uint8Array
Erasure.split = function(ints, originalBlobs, allowedFailures)
{
    const n = originalBlobs + allowedFailures*2;
    const bouts = [];
    for (var i=0; i < n; i++)
        bouts.push(new ByteArrayOutputStream());
    const encodeSize = ((GF.size/n)|0)*n;
    const inputSize = encodeSize*originalBlobs/n;
    const nec = encodeSize-inputSize;
    const symbolSize = inputSize/originalBlobs;
    if (symbolSize * originalBlobs != inputSize)
        throw "Bad alignment of bytes in chunking. "+inputSize+" != "+symbolSize+" * "+ originalBlobs;
    
    for (var i=0; i < ints.length; i += inputSize)
    {
        const copy = slice(ints, i, i + inputSize);
        const encoded = GaloisPolynomial.encode(copy, nec, GF);
        for (var j=0; j < n; j++)
        {
            bouts[j].write(encoded, j*symbolSize, symbolSize);
        }
    }
    
    const res = [];
    for (var i=0; i < n; i++)
        res.push(bouts[i].toByteArray());
    return res;
}

// (Uint8Array[], int, int, int) -> Uint8Array
Erasure.recombine = function(encoded, truncateTo, originalBlobs, allowedFailures)
{
    const n = originalBlobs + allowedFailures*2;
    const encodeSize = ((GF.size/n)|0)*n;
    const inputSize = encodeSize*originalBlobs/n;
    const nec = encodeSize-inputSize;
    const symbolSize = inputSize/originalBlobs;
    const tbSize = encoded[0].length;
    
    const res = new ByteArrayOutputStream();
    for (var i=0; i < tbSize; i += symbolSize)
    {
        var bout = new ByteArrayOutputStream();
        // take a symbol from each stream
        for (var j=0; j < n; j++)
            bout.write(encoded[j], i, symbolSize);
        var decodedInts = GaloisPolynomial.decode(bout.toByteArray(), nec, GF);
        res.write(decodedInts, 0, inputSize);
    }
    return slice(res.toByteArray(), 0, truncateTo);
}
}

function string2arraybuffer(str) {
    var buf = new ArrayBuffer(str.length);
    var bufView = new Uint8Array(buf);
    for (var i=0, strLen=str.length; i<strLen; i++) {
        bufView[i] = str.charCodeAt(i);
    }
    return bufView;
}


if (typeof module !== "undefined"){
    module.exports.randomSymmetricKey = randomSymmetricKey;
    module.exports.SymmetricKey = SymmetricKey;
}
