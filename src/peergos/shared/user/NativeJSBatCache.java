package peergos.shared.user;

import jsinterop.annotations.JsType;
import peergos.shared.storage.auth.BatWithId;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@JsType(namespace = "batCache", isNative = true)
public class NativeJSBatCache {

    public native void init();

    public native CompletableFuture<byte[]> getUserBats(String username);

    public native CompletableFuture<Boolean> setUserBats(String username, byte[] serialisedBats);
}
