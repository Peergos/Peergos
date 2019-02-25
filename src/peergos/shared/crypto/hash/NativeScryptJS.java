package peergos.shared.crypto.hash;

import jsinterop.annotations.*;
import peergos.shared.user.*;

import java.util.concurrent.*;

@JsType(namespace = "scryptJS", isNative = true)
public class NativeScryptJS {

    public native CompletableFuture<byte[]> hashToKeyBytes(String username, String password, SecretGenerationAlgorithm algorithm);

    public native byte[] sha256(byte[] input);
}
