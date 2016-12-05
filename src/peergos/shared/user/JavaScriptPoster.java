package peergos.shared.user;

import jsinterop.annotations.*;

import java.util.*;
import java.util.concurrent.*;

public class JavaScriptPoster implements HttpPoster {
    NativeJSHttp http = new NativeJSHttp();

    @Override
    public CompletableFuture<byte[]> post(String url, byte[] payload, boolean unzip) {
        return http.post(url, payload);
    }

    @Override
    public CompletableFuture<byte[]> postUnzip(String url, byte[] payload) {
        return post(url, payload, true);
    }

    @Override
    public CompletableFuture<byte[]> postMultipart(String url, List<byte[]> files) {
        throw new IllegalStateException("Unimplemented JavaScriptPoster.postMulitpart()!");
    }

    @Override
    public CompletableFuture<byte[]> get(String url) {
        return http.get(url);
    }

    // This is an ugly hack to convert Uint8Array to a valid byte[]
    @JsMethod
    public static byte[] convertToBytes(short[] uints) {
        byte[] res = new byte[uints.length];
        for (int i=0; i < res.length; i++)
            res[i] = (byte) uints[i];
        return res;
    }
}
