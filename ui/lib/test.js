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
    var raw1 = new ByteBuffer();
    var raw2 = new ByteBuffer();
    var template = "Hello secure cloud! Goodbye NSA!".getBytes();
    var template2 = "Second hi safe cloud! Adios NSA!".getBytes();
    for (var i = 0; i < raw1.length / 32; i++)
        raw1.write(template);
    for (var i = 0; i < raw2.length / 32; i++)
        raw2.write(template2);
    
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
    for (f in fragments1)
        hashes1.push(new ByteBuffer(f.getHash()));
    var props1 = new FileProperties(filename, raw1.length + raw2.length);
    var ret = new EncryptedChunkRetriever(nonce1, encryptedChunk1.getAuth(), hashes1, chunk2Location);
    var file = FileAccess.create(fileKey, props1, ret);

    // 2nd chunk
    var chunk2 = new Chunk(raw2, fileKey);
    var nonce2 = window.nacl.randomBytes(SymmetricKey.NONCE_BYTES);
    var encryptedChunk2 = new EncryptedChunk(chunk2.encrypt(nonce2));
    var fragments2 = encryptedChunk2.generateFragments();
    var hashes2 = [];
    for (f in fragments2)
        hashes2.push(new ByteBuffer(f.getHash()));
    var ret2 = new EncryptedChunkRetriever(nonce2, encryptedChunk2.getAuth(), hashes2, null);
    var meta2 = FileAccess.create(fileKey, new FileProperties("", raw2.length), ret2);
    
    // now write the root to the core nodes
    receiver.addToStaticData(sharer, new WritableFilePointer(receiver.us, sharer, new ByteBuffer(rootMapKey), rootRKey));
    sender.uploadChunk(root, [], owner, sharer, rootMapKey);
    // now upload the file meta blobs
    console.log("Uploading chunk with %d fragments\n", fragments1.length);
    sender.uploadChunk(file, fragments1, owner, sharer, fileMapKey);
    console.log("Uploading chunk with %d fragments\n", fragments2.length);
    sender.uploadChunk(meta2, fragments2, owner, sharer, chunk2MapKey);
    
    // now check the retrieval from zero knowledge
    var /*Map<WritableFilePointer, FileAccess>*/ roots = receiver.getRoots();
    for (dirPointer in roots) {
        var rootDirKey = dirPointer.rootDirKey;
        var dir = roots.get(dirPointer);
        var /*Map<SymmetricLocationLink, FileAccess>*/ files = receiver.retrieveMetadata(dir.getFiles(), rootDirKey);
        for (fileLoc in files) {
            var baseKey = fileLoc.target(rootDirKey);
            var fileBlob = files.get(fileLoc);
            // download fragments in chunk
            var fileProps = fileBlob.getFileProperties(baseKey);
	    
            var buf = fileBlob.getRetriever().getFile(receiver, baseKey);
            var original = buf.read(fileProps.getSize());
	    
            // checks
            if (!fileProps.name.equals(filename))
		throw new Exception("Correct filename");
            if (! Arrays.equals(original, concat(raw1, raw2)))
		throw new Exception("Correct file contents");
        }
    }
}

function contextTests(dht, core) {
    var ourname = "Bob";
    generateKeyPairs(ourname, "password", function(us) {
	var bob = new UserContext(ourname, us, dht, core);
	
	var alicesName = "Alice";
	generateKeyPairs(alicesName, "password", function(them) {
	    var alice = new UserContext(alicesName, them, dht, core);
	    
	    if (!bob.isRegistered())
		if (!bob.register())
		    throw new Exception("Couldn't register user!");
	    if (!alice.isRegistered())
		if (!alice.register())
		    throw new Exception("Couldn't register user!");
	    
	    var followed = bob.sendFollowRequest(them);
	    
	    var reqs = alice.getFollowRequests();
	    //assert(reqs.size() == 1);
	    var /*WritableFilePointer*/ root = alice.decodeFollowRequest(reqs.get(0));
	    var /*User*/ sharer = root.writer;
	    
	    // store a chunk in alice's space using the permitted sharing key (this could be alice or bob at this point)
	    var frags = 120;
	    var port = 25 + 1024;
	
	    var address = "localhost:"+ port;
	    for (var i = 0; i < frags; i++) {
		var frag = window.nacl.randomBytes(32);
		var message = concat(sharer.getPublicKeys(), frag);
		var signed = sharer.signMessage(message);
		if (!core.registerFragmentStorage(us, address, us, signed)) {
		    console.log("Failed to register fragment storage!");
		}
	    }
	    var quota = core.getQuota(us);
	    console.log("Generated quota: " + quota/1024 + " KiB");
	    var t1 = Date.now();
	    mediumFileTest(us, sharer, bob, alice);
	    var t2 = Date.now();
	    console.log("File test took %d mS\n", (t2 - t1) / 1000000);
	});
    });
}

contextTests(new DHTClient(), new CoreNodeClient());
