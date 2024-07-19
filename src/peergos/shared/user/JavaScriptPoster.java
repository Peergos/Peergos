package peergos.shared.user;

import jsinterop.annotations.*;

import java.util.*;
import java.util.concurrent.*;

public class JavaScriptPoster implements HttpPoster {

    private final NativeJSHttp http = new NativeJSHttp();
    private final boolean isAbsolute, useGet;

    public JavaScriptPoster(boolean isAbsolute, boolean useGet) {
        this.isAbsolute = isAbsolute;
        this.useGet = useGet;
    }

    private String canonicalise(String url) {
        if (isAbsolute && ! url.startsWith("/") && ! url.startsWith("https://") && ! url.startsWith("http://"))
            return "/" + url;
        return url;
    }

    @Override
    public CompletableFuture<byte[]> post(String url, byte[] payload, boolean unzip, int timeoutMillis) {
        return http.post(canonicalise(url), payload, timeoutMillis);
    }

    @Override
    public CompletableFuture<byte[]> postUnzip(String url, byte[] payload, int timeoutMillis) {
        return post(canonicalise(url), payload, true, timeoutMillis);
    }

    @Override
    public CompletableFuture<byte[]> postMultipart(String url, List<byte[]> files, int timeoutMillis) {
        return http.postMultipart(canonicalise(url), files,  timeoutMillis);
    }

    @Override
    public CompletableFuture<byte[]> put(String url, byte[] payload, Map<String, String> headers) {
        String[] headersArray = new String[headers.size() * 2];
        int index = 0;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            headersArray[index++] = e.getKey();
            headersArray[index++] = e.getValue();
        }
        return http.put(url, payload, headersArray);
    }

    @Override
    public CompletableFuture<byte[]> get(String url) {
        return get(url, Collections.emptyMap());
    }

    @Override
    public CompletableFuture<byte[]> get(String url, Map<String, String> headers) {
        if (useGet) {
            String[] headersArray = new String[headers.size() * 2];
            int index = 0;
            for (Map.Entry<String, String> e : headers.entrySet()) {
                headersArray[index++] = e.getKey();
                headersArray[index++] = e.getValue();
            }
            return http.getWithHeaders(canonicalise(url), headersArray);
        }
        return postUnzip(url, new byte[0]);
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
