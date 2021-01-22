package peergos.shared.user;

import jsinterop.annotations.*;

import java.util.*;
import java.util.concurrent.*;

public class JavaScriptPoster implements HttpPoster {

    private final NativeJSHttp http = new NativeJSHttp();
    private final boolean isAbsolute, useGet;
    private final String prefix;

    public JavaScriptPoster(boolean isAbsolute, boolean useGet, String prefix) {
        this.isAbsolute = isAbsolute;
        this.useGet = useGet;
        this.prefix = prefix;
    }

    private String canonicalise(String url) {
        boolean prefixNotEmpty = this.prefix != null && !this.prefix.equals("");
        if (prefixNotEmpty && url.startsWith(this.prefix)) {
            return url;
        } else if (prefixNotEmpty) {
            return this.prefix + url;
        }

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
    public CompletableFuture<byte[]> put(String url, byte[] payload, Map<String, String> headers) {
        String[] headersArray = new String[headers.size() * 2];
        int index = 0;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            headersArray[index++] = e.getKey();
            headersArray[index++] = e.getValue();
        }
        return http.put(canonicalise(url), payload, headersArray);
    }

    @Override
    public CompletableFuture<byte[]> get(String url) {
        return get(canonicalise(url), Collections.emptyMap());
    }

    @Override
    public CompletableFuture<byte[]> get(String url, Map<String, String> headers) {
        if (isAbsolute || useGet) {// Still do a get if we are served from an IPFS gateway
            String[] headersArray = new String[headers.size() * 2];
            int index = 0;
            for (Map.Entry<String, String> e : headers.entrySet()) {
                headersArray[index++] = e.getKey();
                headersArray[index++] = e.getValue();
            }
            return http.getWithHeaders(canonicalise(url), headersArray);
        }
        return postUnzip(canonicalise(url), new byte[0]);
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
