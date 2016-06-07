package peergos.user;

import java.io.*;

public class JavaScriptPoster implements HttpPoster {

    @Override
    native public byte[] post(String url, byte[] payload) throws IOException;

    @Override
    native public byte[] get(String url) throws IOException;
}
