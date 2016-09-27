package peergos.shared.crypto.hash;

import jsinterop.annotations.*;
import java.util.concurrent.*;

@JsType(namespace = "scriptJS", isNative = true)
public class NativeScryptJS {
    public native CompletableFuture<byte[]> hashToKeyBytes(String username, String password) ;
}
