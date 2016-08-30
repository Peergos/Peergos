package peergos.shared.user;

import java.io.*;

public class JavaScriptPoster implements HttpPoster {

    @Override
    public byte[] post(String url, byte[] payload, boolean unzip) throws IOException {
        throw new IllegalStateException("Unimplemented JavaScriptPoster!");
    }

    @Override
    public byte[] get(String url) throws IOException {
        throw new IllegalStateException("Unimplemented JavaScriptPoster!");
    }
}
