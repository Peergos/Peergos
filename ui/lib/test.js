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

Erase = function() {
    this.f = new Galois();

    // (Uint8Array, int, int)-> Uint8Array
    this.split = function(ints, originalBlobs, allowedFailures)
    {
        const n = originalBlobs + allowedFailures*2;
        const bouts = [];
        for (var i=0; i < n; i++)
            bouts.push(new ByteArrayOutputStream());
        const encodeSize = ((this.f.size/n)|0)*n;
        const inputSize = encodeSize*originalBlobs/n;
        const nec = encodeSize-inputSize;
        const symbolSize = inputSize/originalBlobs;
        if (symbolSize * originalBlobs != inputSize)
            throw "Bad alignment of bytes in chunking. "+inputSize+" != "+symbolSize+" * "+ originalBlobs;

        for (var i=0; i < ints.length; i += inputSize)
        {
            const copy = slice(ints, i, i + inputSize);
            const encoded = GaloisPolynomial.encode(copy, nec, this.f);
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
    this.recombine = function(encoded, truncateTo, originalBlobs, allowedFailures)
    {
        const n = originalBlobs + allowedFailures*2;
        const encodeSize = ((this.f.size/n)|0)*n;
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
            var decodedInts = GaloisPolynomial.decode(bout.toByteArray(), nec, this.f);
            res.write(decodedInts, 0, inputSize);
        }
        return slice(res.toByteArray(), 0, truncateTo);
    }
}

testErasure = function() {
    const raw = new ByteArrayOutputStream();
    const template = nacl.util.decodeUTF8("Hello secure cloud! Goodbye NSA!");
    for (var i = 0; i < Chunk.MAX_SIZE / 32; i++)
        raw.write(template);

    const original = raw.toByteArray();

    var eraser = new Erase();
    const t1 = Date.now();
    var bfrags = eraser.split(original, EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);

    var decoded = eraser.recombine(bfrags, Chunk.MAX_SIZE, EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);
    const t2 = Date.now();
    if (!arraysEqual(original, decoded))
	throw "Decoded contents different from original!";

    console.log("Erasure encode and decode took "+ (t2-t1) + " mS"); 
}
