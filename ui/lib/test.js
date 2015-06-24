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

// UserPublicKey, User, UserCOntext, UserContext -> ()
function mediumFileTest(owner, sharer, receiver, sender) {
    // create a root dir and a file to it, then retrieve and decrypt the file using the receiver
    // create root cryptree
    var rootRKey = SymmetricKey.random();
    var name = "/";
    var rootMapKey = window.nacl.randomBytes(32); // root will be stored under this in the core node
    var root = DirAccess.create(sharer, rootRKey, new FileProperties(name, 0));
    
    // generate file (two chunks)
    var nonce1 = window.nacl.randomBytes(SymmetricKey.NONCE_BYTES);
    var raw1 = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
    var raw2 = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
    var template = nacl.util.decodeUTF8("Hello secure cloud! Goodbye NSA!");
    var template2 = nacl.util.decodeUTF8("Second hi safe cloud! Adios NSA!");
    for (var i = 0; i < Chunk.MAX_SIZE / 32; i++)
        raw1.write(template);
    for (var i = 0; i < Chunk.MAX_SIZE / 32; i++)
        raw2.write(template2);
    raw1 = new Uint8Array(raw1.toArray());
    raw2 = new Uint8Array(raw2.toArray());
    
    // add file to root dir
    var filename = "HiNSA.bin"; // /photos/tree.jpg
    var fileKey = SymmetricKey.random();
    var fileMapKey = window.nacl.randomBytes(32); // file metablob will be stored under this in the core node
    var chunk2MapKey = window.nacl.randomBytes(32); // file metablob 2 will be stored under this in the core node
    var fileLocation = new Location(owner, sharer, new ByteBuffer(fileMapKey));
    var chunk2Location = new Location(owner, sharer, new ByteBuffer(chunk2MapKey));
    
    root.addFile(fileLocation, rootRKey, fileKey);
    
    // 1st chunk
    var chunk1 = new Chunk(raw1, fileKey);
    var encryptedChunk1 = new EncryptedChunk(chunk1.encrypt(nonce1));
    var fragments1 = encryptedChunk1.generateFragments();
    var hashes1 = [];
    for (var f in fragments1)
        hashes1.push(new ByteBuffer(fragments1[f].getHash()));
    var props1 = new FileProperties(filename, raw1.length + raw2.length);
    var ret = new EncryptedChunkRetriever(nonce1, encryptedChunk1.getAuth(), hashes1, chunk2Location);
    var file = FileAccess.create(fileKey, props1, ret);

    // 2nd chunk
    var chunk2 = new Chunk(raw2, fileKey);
    var nonce2 = window.nacl.randomBytes(SymmetricKey.NONCE_BYTES);
    var encryptedChunk2 = new EncryptedChunk(chunk2.encrypt(nonce2));
    var fragments2 = encryptedChunk2.generateFragments();
    var hashes2 = [];
    for (var f in fragments2)
        hashes2.push(new ByteBuffer(fragments2[f].getHash()));
    var ret2 = new EncryptedChunkRetriever(nonce2, encryptedChunk2.getAuth(), hashes2, null);
    var meta2 = FileAccess.create(fileKey, new FileProperties("", raw2.length), ret2);
    
    // now write the root to the core nodes
    receiver.addToStaticData(sharer, new WritableFilePointer(receiver.user, sharer, new ByteBuffer(rootMapKey), rootRKey));
    return sender.uploadChunk(root, [], owner, sharer, rootMapKey)
    .then(function() {
	// now upload the file meta blobs
	console.log("Uploading chunk with %d fragments\n", fragments1.length);
	return sender.uploadChunk(file, fragments1, owner, sharer, fileMapKey);
    }).then(function() {
	console.log("Uploading chunk with %d fragments\n", fragments2.length);
	return sender.uploadChunk(meta2, fragments2, owner, sharer, chunk2MapKey);
    }).then(function() {
    
	// now check the retrieval from zero knowledge
	/*[[WritableFilePointer, FileAccess]]*/
	return receiver.getRoots();
    }).then(function(roots) {
	for (var i=0; i < roots.length; i++) {
	    var dirPointer = roots[i][0];
	    var rootDirKey = dirPointer.baseKey;
	    var dir = roots[i][1];
	    if (dir == null)
		continue;
	    /*[[SymmetricLocationLink, FileAccess]]*/
	    return receiver.retrieveAllMetadata(dir.files, rootDirKey).then(function(files) {
		for (var i=0; i < files.length; i++) {
		    var baseKey = files[i][0].target(rootDirKey);
		    var fileBlob = files[i][1];
		    // download fragments in chunk
		    var fileProps = fileBlob.getFileProperties(baseKey);
		    
		    return fileBlob.retriever.getFile(receiver, baseKey).then(function(buf) {
			var original = buf.read(fileProps.getSize()[0]);
		    
			// checks
			if (fileProps.name != filename)
			    throw "Incorrect filename!";
			if (! Arrays.equals(original, concat(raw1, raw2)))
			    throw "Incorrect file contents!";
		    });
		}
	    });
	}
    });
}

function contextTests(dht, core) {
    var ourname = "Bob";
    generateKeyPairs(ourname, "password").then(function(us) {
	return Promise.resolve(new UserContext(ourname, us, dht, core));
    }).then(function(bob) {
	var alicesName = "Alice";
	return generateKeyPairs(alicesName, "password")
	    .then(function(them) {
		return Promise.resolve(new UserContext(alicesName, them, dht, core));
	    }).then(function(alice) {
		bob.isRegistered().then(function(registered) {
		    if (!registered)
			return bob.register();
		    else
			return Promise.resolve(true);
		}).then(function(bobIsRegistered) {
		    if (!bobIsRegistered)
			reject(Error("Couldn't register user!"));
		    else
			return Promise.resolve(true);
		}).then(function() {
		    console.log("bob registered");
		    return alice.isRegistered();
		}).then(function(aregistered) {
		    if (!aregistered)
			return alice.register();
		    else
			return Promise.resolve(true);
		}).then(function(aliceRegistered){
		    if (!aliceRegistered)
			reject(Error("Couldn't register user!"));
		    else
			return Promise.resolve(true);
		}).then(function() {
		    console.log("both registered");
		    return bob.sendFollowRequest(alice.user);
		}).then(function(followed) {
		    if (!followed)
			reject(Error("Follow request rejected!"));
		    else
			return Promise.resolve(true);
		}).then (function() {
		    return alice.getFollowRequests();
		}).then(function (reqs) {
		    //assert(reqs.size() == 1);
		    var /*WritableFilePointer*/ root = alice.decodeFollowRequest(reqs[0]);
		    var /*User*/ sharer = root.writer;
		    
		    // store a chunk in alice's space using the permitted sharing key (this could be alice or bob at this point)
		    var frags = 120;
		    var port = 25 + 1024;
		    var address = [127, 0, 0, 1];
		    for (var i = 0; i < frags; i++) {
			var frag = window.nacl.randomBytes(32);
			var message = concat(sharer.getPublicKeys(), frag);
			var signed = sharer.signMessage(message);
			core.registerFragmentStorage(bob.user, address, port, bob.user, signed, function(res) {
			    if (!res)
				console.log("Failed to register fragment storage!");
			});
		    }
		    return core.getQuota(bob.user).then(function(quota){
			console.log("Generated quota: " + quota/1024 + " KiB");
			return Promise.resolve(true);
		    }).then(function() {
			return Promise.resolve(sharer);
		    });
		}).then(function(sharer) {
		    var t1 = Date.now();
		    mediumFileTest(bob.user, sharer, bob, alice).then(function() {
			var t2 = Date.now();
			console.log("File test took %d mS\n", (t2 - t1) / 1000000);
		    });
		});
	    });
    });
}

contextTests(new DHTClient(), new CoreNodeClient());
