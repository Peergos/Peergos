package peergos.shared.user;

import java.io.*;
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
    public CompletableFuture<byte[]> get(String url) throws IOException {
        return http.get(url);
    }
}
