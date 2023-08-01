package peergos.shared.user;

import jsinterop.annotations.*;

import java.util.*;
import java.util.concurrent.*;

@JsType(namespace = "http", isNative = true)
public class NativeJSHttp {

//    public static <T> CompletableFuture<T> incomplete() {
//        return new CompletableFuture<>();
//    }

    public native CompletableFuture<byte[]> post(String url, byte[] payload, int timeoutMillis) ;/*-{
        console.log("postProm");
        var future = this.incomplete();
        new Promise(function(resolve, reject) {
	        console.log("making http post request");
	        var req = new XMLHttpRequest();
	        req.open('POST', window.location.origin + "/" + url);
	        req.responseType = 'arraybuffer';

	        req.onload = function() {
    	        console.log("http post returned retrieving " + url);
                // This is called even on 404 etc
                // so check the status
                if (req.status == 200) {
	        	    resolve(new Uint8Array(req.response));
                } else {
		            reject(Error(req.statusText));
                }
    	    };

    	    req.onerror = function() {
                reject(Error("Network Error"));
	        };

	        req.send(new Uint8Array(data));
        }).then(function(result, err) {
            if (err != null)
                future.completeExceptionally(err);
            else
                future.complete(peergos.shared.user.JavaScriptPoster.convertToBytes(result));
        });
        return future;
    }-*/;

    public native CompletableFuture<byte[]> get(String url) ;/*-{
        console.log("getProm");
        var future = this.incomplete();
        new Promise(function(resolve, reject) {
	        var req = new XMLHttpRequest();
	        req.open('GET', url);
	        req.responseType = 'arraybuffer';

	        req.onload = function() {
                // This is called even on 404 etc
                // so check the status
                if (req.status == 200) {
		            resolve(new Uint8Array(req.response));
                } else {
		            reject(Error(req.statusText));
                }
	        };

	        req.onerror = function() {
                reject(Error("Network Error"));
	        };

	        req.send();
        }).then(function(result, err) {
            if (err != null)
                future.completeExceptionally(err);
            else
                future.complete(result);
        });
        return future;
    }-*/;

    public native CompletableFuture<byte[]> getWithHeaders(String url, String[] headers);

    public native CompletableFuture<byte[]> postMultipart(String url, List<byte[]> payload, int timeoutMillis);

    public native CompletableFuture<byte[]> put(String url, byte[] payload, String[] headers);
}
