function WritableFilePointer(owner, writer, mapKey, baseKey) {
    this.owner = owner; //UserPublicKey
    this.writer = writer; //User
    this.mapKey = mapKey; //ByteArrayWrapper
    this.baseKey = baseKey; //SymmetricKey

    this.serialize = function() {
	var bout = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
	bout.writeArray(owner.getPublicKeys());
	bout.writeArray(writer.getSecretKeys());
	bout.writeArray(mapKey);
	bout.writeArray(baseKey.key);
	return bout.toArray();
    }
}

WritableFilePointer.deserialize = function(buf) {
    var bin = new ByteBuffer(buf);
    var owner = bin.readArray();
    var writerSecretKeys = bin.readArray();
    var mapKey = bin.readArray();
    var rootDirKeySecret = bin.readArray();
    return new WritableFilePointer(UserPublicKey.fromPublicKeys(owner), User.fromSecretKeys(writerSecretKeys), mapKey, new SymmetricKey(rootDirKeySecret));
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
	var bout = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
	bout.writeArray(owner.getPublicKeys());
	bout.writeArray(writer.getPublicKeys());
	bout.writeArray(mapKey);
	return new Uint8Array(bout.toArray());
    }

    this.encrypt = function(key, nonce) {
	return key.encrypt(this.serialize(), nonce);
    }
}
Location.deserialize = function(buf) {
    var owner = buf.readArray();
    var writer = buf.readArray();
    var mapKey = buf.readArray();
    return new Location(UserPublicKey.fromPublicKeys(owner), UserPublicKey.fromPublicKeys(writer), mapKey);
}
Location.decrypt = function(from, nonce, loc) {
    var raw = from.decrypt(loc, nonce);
    return Location.deserialize(new ByteBuffer(new Uint8Array(raw)));
}

function SymmetricLocationLink(buf) {
    this.link = buf.readArray();
    this.loc = buf.readArray();

    // SymmetricKey -> Location
    this.targetLocation = function(from) {
	var nonce = slice(this.link, 0, SymmetricKey.NONCE_BYTES);
	var rest = slice(this.link, SymmetricKey.NONCE_BYTES, this.link.length);
	return Location.decrypt(from, nonce, new Uint8Array(this.loc.toArray()));
    }

    this.target = function(from) {
	var nonce = slice(this.link, 0, SymmetricKey.NONCE_BYTES);
	var rest = slice(this.link, SymmetricKey.NONCE_BYTES, this.link.length);
	var encoded = from.decrypt(rest, nonce);
	return new SymmetricKey(encoded);
    }

    this.serialize = function() {
	var buf = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
	buf.writeArray(this.link);
	buf.writeArray(this.loc);
	return buf.toArray();
    }
}

function FileAccess(parent2meta, properties, retriever) {
    this.parent2meta = parent2meta;
    this.properties = properties;
    this.retriever = retriever;
    
    this.serialize = function(bout) {
	bout.writeArray(parent2meta.serialize());
	bout.writeArray(properties);
	bout.writeByte(retriever != null ? 1 : 0);
	if (retriever != null)
	    retriever.serialize(bout);
	bout.writeByte(this.getType());
    }

    // 0=FILE, 1=DIR
    this.getType = function() {
	return 0;
    }

    this.getMetaKey = function(parentKey) {
	return parent2meta.target(parentKey);
    }

    this.getFileProperties = function(parentKey) {
	var nonce = slice(this.properties, 0, SymmetricKey.NONCE_BYTES);
	var cipher = slice(this.properties, SymmetricKey.NONCE_BYTES, this.properties.length);
	return FileProperties.deserialize(this.getMetaKey(parentKey).decrypt(cipher, nonce));
    }
}
FileAccess.deserialize = function(buf) {
    var p2m = buf.readArray();
    var properties = buf.readArray();
    var hasRetreiver = buf.readUnsignedByte();
    var retriever =  (hasRetreiver == 1) ? FileRetriever.deserialize(buf) : null;
    var type = buf.readUnsignedByte();
    var fileAccess = new FileAccess(new SymmetricLink(p2m), properties, retriever);
    switch(type) {
    case 0:
	return fileAccess;
    case 1:
	return DirAccess.deserialize(fileAccess, buf);
    default: throw new Error("Unknown Metadata type: "+type);
    }
}

FileAccess.create = function(parentKey, props, retriever) {
    var metaKey = SymmetricKey.random();
    var nonce = metaKey.createNonce();
    return new FileAccess(SymmetricLink.fromPair(parentKey, metaKey, parentKey.createNonce()),
                concat(nonce, metaKey.encrypt(props.serialize(), nonce)), retriever);
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
	bout.writeUnsignedInt(0);
	bout.writeUnsignedInt(subfolders.length)
	for (var i=0; i < subfolders.length; i++)
	    bout.writeArray(subfolders[i].serialize());
	bout.writeUnsignedInt(files.length)
	for (var i=0; i < files.length; i++)
	    bout.writeArray(files[i].serialize());
    }

    // Location, SymmetricKey, SymmetricKey
    this.addFile = function(location, ourSubfolders, targetParent) {
	var nonce = ourSubfolders.createNonce();
	var loc = location.encrypt(ourSubfolders, nonce);
        var link = concat(nonce, ourSubfolders.encrypt(targetParent.key, nonce));
	var buf = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
	buf.writeArray(link);
	buf.writeArray(loc);
	this.files.push(new SymmetricLocationLink(new ByteBuffer(buf)));
    }

    // 0=FILE, 1=DIR
    this.getType = function() {
	return 1;
    }
}

DirAccess.deserialize = function(base, bin) {
    var s2p = bin.readArray();
    var s2f = bin.readArray();
    var nSharingKeys = bin.readUnsignedInt();
    var files = [], subfolders = [];
    var nsubfolders = bin.readUnsignedInt();
    for (var i=0; i < nsubfolders; i++)
	subfolders[i] = new SymmetricLocationLink(bin.readArray());
    var nfiles = bin.readUnsignedInt();
    for (var i=0; i < nfiles; i++)
	files[i] = new SymmetricLocationLink(bin.readArray());
    return new DirAccess(s2f, s2p, subfolders, files, base.parent2meta, base.properties, base.retriever);
}

// User, SymmetricKey, FileProperties
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
    var type = bin.readUnsignedByte();
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
	return this.getChunkInputStream(context, dataKey).then(function(chunk) {
	    return Promise.resolve(new LazyInputStreamCombiner(this, context, dataKey, chunk));
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
	    var fullEncryptedChunk = new EncryptedChunk(concat(chunkAuth, cipherText.toArray()));
            var original = fullEncryptedChunk.decrypt(dataKey, chunkNonce);
	    return Promise.resolve(new ByteBuffer(original));
	});
    }

    this.serialize = function(buf) {
	buf.writeUnsignedByte(1); // This class
	buf.writeArray(chunkNonce);
	buf.writeArray(chunkAuth);
	buf.writeArray(concat(fragmentHashes));
	buf.writeUnsignedByte(nextChunk != null ? 1 : 0);
	if (nextChunk != null)
	    buf.write(nextChunk.serialize());
    }
}
EncryptedChunkRetriever.deserialize = function(buf) {
    var chunkNonce = new Uint8Array(buf.readArray().toArray());
    var chunkAuth = new Uint8Array(buf.readArray().toArray());
    var concatFragmentHashes = new Uint8Array(buf.readArray().toArray());
    var fragmentHashes = split(concatFragmentHashes, UserPublicKey.HASH_BYTES);
    var hasNext = buf.readUnsignedByte();
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
    this.context = context;
    this.dataKey = dataKey;
    this.current = chunk;
    this.next = stream.getNext();

    this.getNextStream = function() {
        if (this.next != null) {
            return context.getMetadata(this.next).then(function(meta) {
		var nextRet = meta.getRetriever();
		this.next = nextRet.getNext();
		return nextRet.getChunkInputStream(context, dataKey);
            });
	}
        throw "EOFException";
    }

    this.readByte = function() {
        try {
	    return this.current.readByte();
	} catch (e) {}
        this.current = this.getNextStream();
        return this.current.readByte();
    }

    this.read = function(len) {
	var res = new Uint8Array(len);
	for (var i=0; i < len; i++)
	    res[i] = this.readByte();
	return res;
    }
}

var Erasure = {};
Erasure.recombine = function(fragments, truncateTo, originalBlobs, allowedFailures) {
    var buf = new ByteBuffer(0, ByteBuffer.BIG_ENDIAN, true);
    // assume we have all fragments in original order for now
    for (var i=0; i < originalBlobs; i++)
	buf.write(fragments[i]);
    return buf;
}
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
Erasure.split = function(input, originalBlobs, allowedFailures) {
    //TO DO port erasure code implementation and Galois groups
    var size = (input.length/originalBlobs)|0;
    var bfrags = [];
    for (var i=0; i < input.length/size; i++)
	bfrags.push(slice(input, i*size, Math.min(input.length, (i+1)*size)));
    return bfrags;
}

function string2arraybuffer(str) {
  var buf = new ArrayBuffer(str.length);
  var bufView = new Uint8Array(buf);
  for (var i=0, strLen=str.length; i<strLen; i++) {
    bufView[i] = str.charCodeAt(i);
  }
  return bufView;
}
