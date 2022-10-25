package peergos.shared.user;

import jsinterop.annotations.JsType;
import peergos.shared.crypto.hash.PublicKeyHash;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@JsType(namespace = "pointerCache", isNative = true)
public class NativeJSPointerCache {

    public native void init(int size);

    public native CompletableFuture<Boolean> put(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash);

    public native CompletableFuture<Optional<byte[]>> get(PublicKeyHash owner, PublicKeyHash writer);
}
