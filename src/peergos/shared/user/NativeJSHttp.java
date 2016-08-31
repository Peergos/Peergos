package peergos.shared.user;

import jsinterop.annotations.*;

import java.io.*;

@JsType(namespace = "http", isNative = true)
public class NativeJSHttp {

    public native byte[] post(String url, byte[] payload) throws IOException;

    public native byte[] get(String url) throws IOException;
}
