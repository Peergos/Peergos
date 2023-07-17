package peergos.shared.user;

import jsinterop.annotations.JsType;
import peergos.shared.io.ipfs.Cid;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@JsType(namespace = "cache", isNative = true)
public class NativeJSCache {

    public native void init(int maxSizeMiB);

    public native CompletableFuture<Boolean> put(Cid hash, byte[] data);

    public native CompletableFuture<Optional<byte[]>> get(Cid hash);

    public native boolean hasBlock(Cid hash);

    public native CompletableFuture<Boolean> clear();

}
