package peergos.shared.user;

import jsinterop.annotations.*;

import java.io.*;
import java.util.concurrent.*;

@JsType(namespace = "http", isNative = true)
public class NativeJSHttp {

    public native CompletableFuture<byte[]> post(String url, byte[] payload);

    public native CompletableFuture<byte[]> get(String url) throws IOException;
}
