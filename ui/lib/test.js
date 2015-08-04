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
function mediumFileShareTest(owner, sharer, receiver, sender) {
    // create a root dir and a file to it, then retrieve and decrypt the file using the receiver
    // create root cryptree
    var rootRKey = SymmetricKey.random();
    var name = "/";
    var rootMapKey = window.nacl.randomBytes(32); // root will be stored under this in the core node
    var root = DirAccess.create(sharer, rootRKey, new FileProperties(name, 0));
    
    // generate file (two chunks)
    const raw = new ByteArrayOutputStream();
    const template = nacl.util.decodeUTF8("Hello secure cloud! Goodbye NSA!");
    const template2 = nacl.util.decodeUTF8("Second hi safe cloud! Adios NSA!");
    const twoChunks = false;
    if (twoChunks) {
	for (var i = 0; i < Chunk.MAX_SIZE / 32; i++)
            raw.write(template);
	for (var i = 0; i < Chunk.MAX_SIZE / 32; i++)
            raw.write(template2);
    } else {
	for (var i = 0; i < Chunk.MAX_SIZE / 32 / 2; i++)
            raw.write(template);
	for (var i = 0; i < 13; i++)
	    raw.writeByte(i);
    }
    
    // add file to root dir
    var filename = "HiNSA.bin";
    var fileKey = SymmetricKey.random();
    const file = new FileUploader(filename, raw.toByteArray(), fileKey, new Location(owner, sharer, rootMapKey), root.getParentKey(rootRKey));
    return file.upload(sender, owner, sharer).then(function(fileLocation) {
	root.addFile(fileLocation, rootRKey, fileKey);
	
	// now write the root to the core nodes
	const rootEntry = new EntryPoint(new ReadableFilePointer(receiver.user, sharer, rootMapKey, rootRKey), receiver.username, [], []);
	receiver.addToStaticData(rootEntry);
	return sender.uploadChunk(root, [], owner, sharer, rootMapKey);
    }).then(function() {
    
	// now check the retrieval from zero knowledge
	/*[[ReadableFilePointer, FileAccess]]*/
	return receiver.getRoots();
    }).then(function(roots) {
	for (var i=0; i < roots.length; i++) {
	    var dirPointer = roots[i][0];
	    var rootDirKey = dirPointer.pointer.baseKey;
	    var dir = roots[i][1];
	    if (dir == null)
		continue;
	    const rootFilesKey = dir.subfolders2files.target(rootDirKey);
	    /*[[SymmetricLocationLink, FileAccess]]*/
	    dir.getChildren(receiver, rootDirKey).then(function(files) {
		for (var i=0; i < files.length; i++) {
		    var fileBlob = files[i].fileAccess;
		    var baseKey = files[i].filePointer.baseKey;
		    // test parent link navigation
		    fileBlob.getParent(baseKey, receiver).then(function(parent){
			parent.fileAccess.getChildren(receiver, rootDirKey).then(function(files){
			    // download fragments in chunk
			    var fileProps = fileBlob.getFileProperties(baseKey);
			    
			    return fileBlob.retriever.getFile(receiver, baseKey).then(function(buf) {
				return buf.read(fileProps.getSize()).then(function(original) {
				    
				    // checks
				    if (fileProps.name != filename)
					throw "Incorrect filename!";
				    if (! arraysEqual(original, raw.toByteArray()))
					throw "Incorrect file contents!";
				    console.log("Medium file share test passed! Found file "+fileProps.name);
				    return Promise.resolve(true);
				});
			    });
			});
		    });
		}
	    });
	}
    });
}

function twoUserTests(dht, core) {
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
		    var /*ReadableFilePointer*/ root = alice.decodeFollowRequest(reqs[0]);
		    var /*User*/ sharer = root.pointer.writer;
		    
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
		    mediumFileShareTest(bob.user, sharer, bob, alice).then(function() {
			var t2 = Date.now();
			console.log("File test took %d mS\n", (t2 - t1) / 1000000);
		    });
		});
	    });
    });
}

function rootDirCreation(context) {
    // create a root dir, then retrieve and decrypt the dir using the receiver
    const writer = User.random();
    var rootRKey = SymmetricKey.random();
    var name = "/";
    var rootMapKey = window.nacl.randomBytes(32); // root will be stored under this in the core node
    var root = DirAccess.create(writer, rootRKey, new FileProperties(name, 0));

    // now write the root to the core nodes
    context.addToStaticData(writer, new ReadableFilePointer(context.user, writer, rootMapKey, rootRKey));
    return context.addSharingKey(writer).then(function(res) {
	context.uploadChunk(root, [], context.user, writer, rootMapKey)
	    .then(function(res) {
		// now check the retrieval from zero knowledge
		/*[[ReadableFilePointer, FileAccess]]*/
		return context.getRoots();
	    }).then(function(roots) {
		for (var i=0; i < roots.length; i++) {
		    var dirPointer = roots[i][0];
		    var rootDirKey = dirPointer.baseKey;
		    var dir = roots[i][1];
		    if (dir == null)
			continue;
		    console.log("Found entry dir");
		}
	    });
    });
}

function singleUserTests(dht, core) {
    var ourname = "Bob";
    generateKeyPairs(ourname, "password").then(function(us) {
	return Promise.resolve(new UserContext(ourname, us, dht, core));
    }).then(function(bob) {
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
	}).then(function(registered) {
	    rootDirCreation(bob);
	});
    });
}

//twoUserTests(new DHTClient(), new CoreNodeClient());
//singleUserTests(new DHTClient(), new CoreNodeClient());

testErasure = function() {
    const raw = new ByteArrayOutputStream();
    const template = nacl.util.decodeUTF8("Hello secure cloud! Goodbye NSA!");
    for (var i = 0; i < Chunk.MAX_SIZE / 32; i++)
        raw.write(template);

    const original = raw.toByteArray();

    const t1 = Date.now();
    var bfrags = Erasure.split(original, EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);
    const t2 = Date.now();
    var decoded = Erasure.recombine(bfrags, Chunk.MAX_SIZE, EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);
    const t3 = Date.now();
    if (!arraysEqual(original, decoded))
	throw "Decoded contents different from original!";

    console.log("Erasure encode took "+ (t2-t1) + " mS"); 
    console.log("Erasure decode took "+ (t3-t2) + " mS"); 
}
//testErasure();
