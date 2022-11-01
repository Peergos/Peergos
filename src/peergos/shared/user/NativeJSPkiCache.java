package peergos.shared.user;

import jsinterop.annotations.JsType;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@JsType(namespace = "pkiCache", isNative = true)
public class NativeJSPkiCache {

    public native void init();

    public native CompletableFuture<List<String>> getChain(String username);

    public native CompletableFuture<Boolean> setChain(String username, String[] serialisedUserPublicKeyLinkChain, String serialisedOwner);

    public native CompletableFuture<String> getUsername(String serialisedPublicKeyHash);
}
