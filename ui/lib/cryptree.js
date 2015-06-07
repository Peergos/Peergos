function FilePointer(owner, writer, mapKey, baseKey) {
    this.owner = owner; //Public Key
    this.writer = writer; //Public Key
    this.mapKey = mapKey; //ByteArrayWrapper
    this.baseKey = baseKey; //SymmetricKey

    this.serialize = function() {
	var bout = new ByteBuffer();
	bout.writeArray(string2arraybuffer(username));
	bout.writeArray(owner.getSecretKeys());
	bout.writeArray(mapKey);
	bout.writeArray(rootDirKey.key);
	return bout.toArray();
    }
}

FilePointer.deserialize = function(buf) {
    var bin = new ByteBuffer(buf);
    var name = "";
    var ua = bin.readArray();
    for (var i = 0; i < ua.length; i++)
        name += String.fromCharCode(ua.readUnsignedByte());
    var secKeys = bin.readArray();
    var mapKey = bin.readArray();
    var rootDirKeySecret = bin.readArray();
    return new SharedRootDir(name, User.fromSecretKeys(secKeys), mapKey, new SymmetricKey(rootDirKeySecret));
}

function SymmetricLink(link) {
    this.link = slice(link, SymmetricKey.NONCE_BYTES, link.length);
    this.nonce = slice(link, 0, SymmetricKey.NONCE_BYTES);

    this.serialize = function() {
	return concat(nonce, link);
    }

    this.target = function(from) {
	var encoded = from.decrypt(link, nonce);
	return new SymmetricKey(encoded);
    }
}
SymmetricLink.fromPair = function(from, to, nonce) {
    return new SymmetricLink(concat(nonce, from.encrypt(to.key, nonce)));
}

// String, UserPublicKey, Uint8Array
function Location(owner, subKey, mapKey) {
    this.owner = owner;
    this.subKey = subKey;
    this.mapKey = mapKey;

    this.serialize = function(bout) {
	var bout = new ByteBuffer(username.length + 64 + 32 + 12);
	bout.writeArray(string2arraybuffer(username));
	bout.writeArray(subKey.getPublicKeys());
	bout.writeArray(mapKey);
	return bout.toArray();
    }

    this.encrypt = function(key, nonce) {
	return key.encrypt(serialize(), nonce);
    }
}
Location.decrypt  = function(from, nonce, loc) {
    
}

function SymmetricLocationLink(buf) {
    this.link = buf.readArray();
    this.loc = buf.readArray();

    // SymmetricKey -> Location
    this.targetLocation = function(from) {
	var nonce = slice(link, 0, SymmetricKey.NONCE_BYTES);
	var rest = slice(link, SymmetricKey.NONCE_BYTES, link.length);
	return Location.decrypt(from, nonce, loc);
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
}
FileAccess.deserialize = function(buf, ourKey /*SymmetricKey*/) {
    var p2m = buf.readArray();
    var properties = buf.readArray();
    var hasRetreiver = buf.readUnsignedByte();
    var retriever =  (hasRetreiver == 1) ? FileRetriever.deserialize(buf) : null;
    var type = buf.readUnsignedByte();
    switch(type) {
    case 0:
	return FileAccess.deserialize(buf, concat(metaNonce, encryptedMetadata));
    case 1:
	return DirAccess.deserialize(buf, ourKey, concat(metaNonce, encryptedMetadata));
    default: throw new Error("Unknown Metadata type: "+type);
    }
}

function DirAccess(subfolders2files, subfolders2parent, subfolders, files, parent2meta, properties, retriever) {
    FileAccess.call(this, parent2meta, properties, retriever);
    this.subfolders2files = subfolders2files;
    this.subfolders2parent = subfolders2parent;
    this.subfolders = subfolders;
    this.files = files;

    this.superSerialize = this.serialize;
    this.serialize = function(bout) {
	superSerialize(bout);
	bout.writeArray(subfolders2parent);
	bout.writeArray(subfolders2files);
	bout.writeUnsignedInt(0);
	bout.writeUnsignedInt(subfolders.length)
	for (var i=0; i < subfolders.length; i++)
	    bout.writeArray(subfolders[i].serialize());
	bout.writeUnsignedInt(files.length)
	for (var i=0; i < files.length; i++)
	    bout.writeArray(files[i].serialize());
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
	nsubfolders[i] = new SymmetricLocationLink(bin.readArray());
    var nfiles = bin.readUnsignedInt();
    for (var i=0; i < nfiles; i++)
	nfiles[i] = new SymmetricLocationLink(bin.readArray());
    return new DirAccess(s2f, s2p, subfolders, files, base.parent2meta, base.properties, base.retriever);
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
    
}
EncryptedChunkRetriever.deserialize = function(bin) {
    //TODO
}

function string2arraybuffer(str) {
  var buf = new ArrayBuffer(str.length);
  var bufView = new Uint8Array(buf);
  for (var i=0, strLen=str.length; i<strLen; i++) {
    bufView[i] = str.charCodeAt(i);
  }
  return bufView;
}
