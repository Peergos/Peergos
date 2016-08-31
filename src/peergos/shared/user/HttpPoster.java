package peergos.shared.user;

import java.io.*;

public interface HttpPoster {

    byte[] post(String url, byte[] payload, boolean unzip) throws IOException;

    default byte[] postUnzip(String url, byte[] payload) throws IOException {
        return post(url, payload, true);
    }

    byte[] get(String url) throws IOException;

}
