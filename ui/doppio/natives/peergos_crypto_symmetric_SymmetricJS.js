// This entire object is exported. Feel free to define private helper functions above it.
var ThreadStatus = Doppio.VM.Enums.ThreadStatus;

registerNatives({
    'peergos/crypto/symmetric/SymmetricJS': {
	
	'secretbox([B[B[B)[B': function(thread, javaThis, data, nonce, key) {
	    thread.setStatus(ThreadStatus.ASYNC_WAITING);
	    const res = nacl.secretbox(new Uint8Array(data.array), new Uint8Array(nonce.array), new Uint8Array(key.array));
	    var i8Array = new Int8Array(res.buffer, res.byteOffset, res.byteLength);
	    var javaByteArray = Doppio.VM.Util.newArrayFromDataWithClass(thread, thread.getBsCl().getInitializedClass(thread, '[B'), i8Array);
	    thread.asyncReturn(javaByteArray);
	},
	
	'secretbox_open([B[B[B)[B': function(thread, javaThis, cipher, nonce, key) {
	    thread.setStatus(ThreadStatus.ASYNC_WAITING);
	    const res = nacl.secretbox.open(new Uint8Array(cipher.array), new Uint8Array(nonce.array), new Uint8Array(key.array));
	    var i8Array = new Int8Array(res.buffer, res.byteOffset, res.byteLength);
	    var javaByteArray = Doppio.VM.Util.newArrayFromDataWithClass(thread, thread.getBsCl().getInitializedClass(thread, '[B'), i8Array);
	    thread.asyncReturn(javaByteArray);
	}
	
    }
});
