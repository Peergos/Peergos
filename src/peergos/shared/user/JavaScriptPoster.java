package peergos.shared.user;

import java.io.*;

public class JavaScriptPoster implements HttpPoster {
    NativeJSHttp http = new NativeJSHttp();

    @Override
    public byte[] post(String url, byte[] payload, boolean unzip) throws IOException {
        return http.post(url, payload);
    }

    @Override
    public byte[] postUnzip(String url, byte[] payload) throws IOException {
        return post(url, payload, true);
    }

    @Override
    public byte[] get(String url) throws IOException {
        return http.get(url);
    }
}
