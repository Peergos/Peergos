function SharedRootDir(username, owner, mapKey, rootDirKey) {
    this.username = username; //String
    this.owner = owner; //User
    this.mapKey = mapKey; //ByteArrayWrapper
    this.rootDirKey = rootDirKey; //SymmetricKey

    this.serialize = function() {
	var bout = new ByteBuffer(username.length+96 + 32 + 32 + 16);
	bout.writeArray(string2arraybuffer(username));
	bout.writeArray(owner.getSecretKeys());
	bout.writeArray(mapKey);
	bout.writeArray(rootDirKey.key);
	return bout.toArray();
    }
}

SharedRootDir.deserialize = function(buf) {
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

    this.serialize = function() {
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

function Metadata(type, metaNonce, encryptedMetadata) {
    this.type = type; // 0=DIR, 1=FILE, 2=FOLLOWER
    this.metaNonce = metaNonce;
    this.encryptedMetadata = encryptedMetadata;
    this.fragments = [];
    
    this.serialize = function() {
	var bout = new ByteBuffer(1 + encryptedMetadata.length + metaNonce.length + 8);
	bout.writeUnsignedByte(type);
	bout.writeArray(metaNonce);
	bout.writeArray(encryptedMetadata);
	return bout.toArray();
    }
}
Metadata.deserialize = function(buf, ourKey /*SymmetricKey*/) {
    var type = buf.readUnsignedByte();
    var metaNonce = buf.readArray();
    var encryptedMetadata = buf.readArray();
    switch(type) {
    case 0:
	return DirAccess.deserialize(buf, ourKey, concat(metaNonce, encryptedMetadata));
    case 1:
	return FileAccess.deserialize(buf, concat(metaNonce, encryptedMetadata));
    case 2:
	return new Metadata(type, metaNonce, encryptedMetadata);
    default: throw new Error("Unknown Metadata type: "+type);
    }
}

function DirAccess() {
    
}

function string2arraybuffer(str) {
  var buf = new ArrayBuffer(str.length);
  var bufView = new Uint8Array(buf);
  for (var i=0, strLen=str.length; i<strLen; i++) {
    bufView[i] = str.charCodeAt(i);
  }
  return bufView;
}
