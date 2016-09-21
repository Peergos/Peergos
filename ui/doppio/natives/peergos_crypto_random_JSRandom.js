// This entire object is exported. Feel free to define private helper functions above it.
registerNatives({
    'peergos/crypto/random/JSRandom': {
	
	'randombytes([BII)V': function(thread, javaThis, array, offset, length) {
	    var rnd = window.nacl.randomBytes(length);
	    for (var i=0; i < length; i++)
		array.array[offset + i] = rnd[i];
	}
	
    }
});
