// This entire object is exported. Feel free to define private helper functions above it.
var ThreadStatus = Doppio.VM.Enums.ThreadStatus;

registerNatives({
    'peergos/user/JavaScriptPoster': {
	
	'post(Ljava/lang/String;[BZ)[B': function(thread, javaThis, url, bodyData, unzip) {
	    thread.setStatus(ThreadStatus.ASYNC_WAITING);
	    return new Promise(function(resolve, reject) {
		console.log("HTTP Post to "+url);
		var req = new XMLHttpRequest();
		req.open('POST', window.location.origin + "/" + url);
		req.responseType = 'arraybuffer';
		
		req.onload = function() {
		    // This is called even on 404 etc
		    // so check the status
		    if (req.status == 200) {
			resolve(new Uint8Array(req.response));
		    }
		    else {
			reject(Error(req.statusText));
		    }
		};
		
		req.onerror = function() {
		    reject(Error("Network Error"));
		};
		
		req.send(bodyData.array);
	    }).then(function(res) {
		var i8Array = new Int8Array(res.buffer, res.byteOffset, res.byteLength);
		var javaByteArray = Doppio.VM.Util.newArrayFromDataWithClass(thread, thread.getBsCl().getInitializedClass(thread, '[B'), i8Array);
		thread.asyncReturn(javaByteArray);
	    });
	},
                
	'get(Ljava/lang/String;)[B': function(thread, javaThis, url) {
	    thread.setStatus(ThreadStatus.ASYNC_WAITING);
	    return new Promise(function(resolve, reject) {
		console.log("HTTP Get to "+url);
		var req = new XMLHttpRequest();
		req.open('GET', window.location.origin + "/" + url);
		req.responseType = 'arraybuffer';
		
		req.onload = function() {
		    // This is called even on 404 etc
		    // so check the status
		    if (req.status == 200) {
			resolve(new Uint8Array(req.response));
		    }
		    else {
			reject(Error(req.statusText));
		    }
		};
		
		req.onerror = function() {
		    reject(Error("Network Error"));
		};
		
		req.send();
	    }).then(function(res) {
		var i8Array = new Int8Array(res.buffer, res.byteOffset, res.byteLength);
		var javaByteArray = Doppio.VM.Util.newArrayFromDataWithClass(thread, thread.getBsCl().getInitializedClass(thread, '[B'), i8Array);
		thread.asyncReturn(javaByteArray);
	    });
	}
    }
});
