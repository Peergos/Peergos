function asyncErasureEncode(original, originalBlobs, allowedFailures) {
    var worker = new Worker("scripts/erasure.js");
    var prom = new Promise(function(resolve, reject){
	worker.onmessage = function(e) {
	    var bfrags = e.data;
	    resolve(bfrags);
	};
	worker.postMessage({original:original, originalBlobs:originalBlobs, allowedFailures:allowedFailures});
    });
    return prom;
}
var ThreadStatus = Doppio.VM.Enums.ThreadStatus;
registerNatives({
    'peergos/user/fs/erasure/Erasure': {
		'split([BII)[[B': function(thread, input, originalBlobs, allowedFailures) {
			thread.setStatus(ThreadStatus.ASYNC_WAITING);
			return asyncErasureEncode(input.array, originalBlobs, allowedFailures).then(function(res) {
				var result = util.newArray(thread, thread.getBsCl(), '[[B', 0), fragment;
				for (fragment in res) {
					var i8Array = new Int8Array(fragment.buffer, fragment.byteOffset, fragment.byteLength);
                	var javaByteArray = Doppio.VM.Util.newArrayFromDataWithClass(thread, thread.getBsCl().getInitializedClass(thread, '[B'), i8Array);
					result.array.push(javaByteArray);
				}
				thread.asyncReturn(result);
			});
		}
    }
    /*,
    //native public static byte[] recombine(byte[][] encoded, int truncateTo, int originalBlobs, int allowedFailures);
    'peergos/user/fs/erasure/Erasure': {
    		'recombine([[BIII)[B': function(thread, encoded, truncateTo, originalBlobs, allowedFailures) {
    		console.log("kev in recombine");
    			thread.setStatus(ThreadStatus.ASYNC_WAITING);
    			var arr = erasure.recombine(encoded.array, truncateTo, originalBlobs, allowedFailures);

				var i8Array = new Int8Array(arr.buffer, arr.byteOffset, arr.byteLength);
				var javaByteArray = Doppio.VM.Util.newArrayFromDataWithClass(thread, thread.getBsCl().getInitializedClass(thread, '[B'), i8Array);

				thread.asyncReturn(javaByteArray);
    		}
	}*/
});
