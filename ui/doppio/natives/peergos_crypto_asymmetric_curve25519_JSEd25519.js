// This entire object is exported. Feel free to define private helper functions above it.
var ThreadStatus = Doppio.VM.Enums.ThreadStatus;

registerNatives({
    'peergos/crypto/asymmetric/curve25519/JSEd25519': {
	
	'crypto_sign_open([B[B)[B': function(thread, javaThis, signed, publicKey) {
	    thread.setStatus(ThreadStatus.ASYNC_WAITING);
	    var res = nacl.sign.open(new Uint8Array(signed.array), new Uint8Array(publicKey.array));
	    var i8Array = new Int8Array(res.buffer, res.byteOffset, res.byteLength);
	    var javaByteArray = Doppio.VM.Util.newArrayFromDataWithClass(thread, thread.getBsCl().getInitializedClass(thread, '[B'), i8Array);
	    thread.asyncReturn(javaByteArray);
	},
	
	'crypto_sign([B[B)[B': function(thread, javaThis, message, secretKey) {
	    thread.setStatus(ThreadStatus.ASYNC_WAITING);
	    var res = nacl.sign(new Uint8Array(message.array), new Uint8Array(secretKey.array));
	    var i8Array = new Int8Array(res.buffer, res.byteOffset, res.byteLength);
	    var javaByteArray = Doppio.VM.Util.newArrayFromDataWithClass(thread, thread.getBsCl().getInitializedClass(thread, '[B'), i8Array);
	    thread.asyncReturn(javaByteArray);
	},
	
	'crypto_sign_keypair([B[B)V': function(thread, javaThis, publicKey, secretKey) {
	    var signSeed = new Uint8Array(secretKey.array).slice(0, 32);
	    var signPair = nacl.sign.keyPair.fromSeed(signSeed);
	    for (var i=0; i < signPair.secretKey.length; i++)
		secretKey.array[i] = signPair.secretKey[i];
	    for (var i=0; i < signPair.publicKey.length; i++)
		publicKey.array[i] = signPair.publicKey[i];
	}
    }
});
