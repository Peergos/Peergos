package peergos.shared.user;

import java.io.*;

public interface HttpPoster {

    byte[] post(String url, byte[] payload, boolean unzip) throws IOException;

    byte[] postUnzip(String url, byte[] payload) throws IOException;

    byte[] get(String url) throws IOException;

}
