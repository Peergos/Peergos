package peergos.shared.user;

import jsinterop.annotations.JsType;

import java.util.concurrent.CompletableFuture;

@JsType(namespace = "rootKeyCache", isNative = true)
public class NativeJSRootKeyCache {

    public native void init();
/*
    public native CompletableFuture<byte[]> getRootKey(String username);
*/
    public native CompletableFuture<Boolean> setRootKey(String username, byte[] rootKeySerialised);
}
