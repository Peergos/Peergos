function SharedRootDir(username, owner, mapKey, rootDirKey) {
    this.username = username; //String
    this.owner = owner; //User
    this.mapKey = mapKey; //ByteArrayWrapper
    this.rootDirKey = rootDirKey; //SymmetricKey

    function serialize() {
	return new Blob([username, ], {type:"/application/octet-stream"});
    }
}
