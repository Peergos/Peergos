package peergos.shared.user;

import jsinterop.annotations.JsType;
import peergos.shared.storage.auth.BatWithId;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@JsType(namespace = "accountCache", isNative = true)
public class NativeJSAccountCache {

    public native void init();

    public native CompletableFuture<Boolean> setLoginData(String key, byte[] entryPoints);

    public native CompletableFuture<Boolean> remove(String key);

    public native CompletableFuture<byte[]> getEntryData(String key);
}
