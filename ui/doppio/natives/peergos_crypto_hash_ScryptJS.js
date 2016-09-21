// This entire object is exported. Feel free to define private helper functions above it.
var ThreadStatus = Doppio.VM.Enums.ThreadStatus;

registerNatives({
    'peergos/crypto/hash/ScryptJS': {
	
	'hashToKeyBytes(Ljava/lang/String;Ljava/lang/String;)[B': function(thread, javaThis, username, password) {
	    console.log("JsScrypt starting..");
	    var t1 = Date.now();
	    var hash = sha256(nacl.util.decodeUTF8(password));
	    var salt = nacl.util.decodeUTF8(username)
	    thread.setStatus(ThreadStatus.ASYNC_WAITING);
	    scrypt(hash, salt, 17, 8, 96, 1000, function(keyBytes) {
		console.log("JS Scrypt complete in: "+ (Date.now()-t1)+"mS");
		var hashedBytes = nacl.util.decodeBase64(keyBytes);
		var i8Array = new Int8Array(hashedBytes.buffer, hashedBytes.byteOffset, hashedBytes.byteLength);
		var javaByteArray = Doppio.VM.Util.newArrayFromDataWithClass(thread, thread.getBsCl().getInitializedClass(thread, '[B'), i8Array);
		thread.asyncReturn(javaByteArray);
	    }, 'base64');
	}
    }
});
