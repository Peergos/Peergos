package peergos.shared.user;

import jsinterop.annotations.*;

import java.util.*;
import java.util.concurrent.*;

public class JavaScriptPoster implements HttpPoster {

    private final NativeJSHttp http = new NativeJSHttp();
    private final boolean isAbsolute;

    public JavaScriptPoster(boolean isAbsolute) {
        this.isAbsolute = isAbsolute;
    }

    private String canonicalise(String url) {
        if (isAbsolute && ! url.startsWith("/"))
            return "/" + url;
        return url;
    }

    @Override
    public CompletableFuture<byte[]> post(String url, byte[] payload, boolean unzip) {
        return http.post(canonicalise(url), payload);
    }

    @Override
    public CompletableFuture<byte[]> postUnzip(String url, byte[] payload) {
        return post(canonicalise(url), payload, true);
    }

    @Override
    public CompletableFuture<byte[]> postMultipart(String url, List<byte[]> files) {
        return http.postMultipart(canonicalise(url), files);
    }

    @Override
    public CompletableFuture<byte[]> get(String url) {
        return http.get(canonicalise(url));
    }

    @JsMethod
    public static byte[] emptyArray() {
        return new byte[0];
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
