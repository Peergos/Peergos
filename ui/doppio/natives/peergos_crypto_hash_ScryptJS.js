// This entire object is exported. Feel free to define private helper functions above it.
registerNatives({
    'peergos/crypto/hash/ScryptJS': {
	
	'hashToKeyBytes(Ljava/lang/String;Ljava/lang/String;)[B': function(thread, javaThis, username, password) {
	    console.log("JsScrypt!");
	    var hash = sha256(nacl.util.decodeUTF8(password));
	    var salt = nacl.util.decodeUTF8(username)
	    thread.setStatus(ThreadStatus.ASYNC_WAITING);
	    scrypt(hash, salt, 17, 8, 96, 1000, function(keyBytes) {
		var hashedBytes = nacl.util.decodeBase64(keyBytes);
		thread.asyncReturn(hashedBytes);
	    }, 'base64');
	}
    }
});
