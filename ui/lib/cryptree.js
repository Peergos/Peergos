ByteBuffer.prototype.writeArray = function(arr) {
    this.writeUnsignedInt(arr.length);
    this.write(arr);
}

ByteBuffer.prototype.readArray = function(arr) {
    var len = this.readUnsignedInt();
    return this.read(len);
}

function SharedRootDir(username, owner, mapKey, rootDirKey) {
    this.username = username; //String
    this.owner = owner; //User
    this.mapKey = mapKey; //ByteArrayWrapper
    this.rootDirKey = rootDirKey; //SymmetricKey

    this.serialize = function() {
	var bout = new ByteBuffer(4+2*username.length+96+32+32);
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


function string2arraybuffer(str) {
  var buf = new ArrayBuffer(str.length);
  var bufView = new Uint8Array(buf);
  for (var i=0, strLen=str.length; i<strLen; i++) {
    bufView[i] = str.charCodeAt(i);
  }
  return bufView;
}
