// This entire object is exported. Feel free to define private helper functions above it.
var ThreadStatus = Doppio.VM.Enums.ThreadStatus;

registerNatives({
    'peergos/crypto/asymmetric/curve25519/JSCurve25519': {
	
	'crypto_box_open([B[B[B[B)[B': function(thread, javaThis, cipher, nonce, theirPublicKey, ourSecretKey) {
	    thread.setStatus(ThreadStatus.ASYNC_WAITING);
	    var res = nacl.box.open(new Uint8Array(cipher.array), new Uint8Array(nonce.array), new Uint8Array(theirPublicKey.array), new Uint8Array(ourSecretKey.array));
	    var i8Array = new Int8Array(res.buffer, res.byteOffset, res.byteLength);
	    var javaByteArray = Doppio.VM.Util.newArrayFromDataWithClass(thread, thread.getBsCl().getInitializedClass(thread, '[B'), i8Array);
	    thread.asyncReturn(javaByteArray);
	},
	
	'crypto_box([B[B[B[B)[B': function(thread, javaThis, message, nonce, theirPublicKey, ourSecretKey) {
	    thread.setStatus(ThreadStatus.ASYNC_WAITING);
	    var res = nacl.box(new Uint8Array(message.array), new Uint8Array(nonce.array), new Uint8Array(theirPublicKey.array), new Uint8Array(ourSecretKey.array));
	    var i8Array = new Int8Array(res.buffer, res.byteOffset, res.byteLength);
	    var javaByteArray = Doppio.VM.Util.newArrayFromDataWithClass(thread, thread.getBsCl().getInitializedClass(thread, '[B'), i8Array);
	    thread.asyncReturn(javaByteArray);
	},
	
	'crypto_box_keypair([B[B)V': function(thread, javaThis, publicKey, secretKey) {
	    var boxPair = nacl.box.keyPair.fromSecretKey(new Uint8Array(secretKey.array));
	    for (var i=0; i < boxPair.publicKey.length; i++)
		publicKey.array[i] = boxPair.publicKey[i];
	}
    }
});
