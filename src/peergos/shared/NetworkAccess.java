package peergos.shared;

import jsinterop.annotations.*;
import peergos.client.*;
import peergos.server.tests.*;
import peergos.shared.corenode.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public class NetworkAccess {

    public final CoreNode coreNode;
    public final ContentAddressedStorage dhtClient;
    public final MutablePointers mutable;
    public final Btree btree;
    @JsProperty
    public final List<String> usernames;
    private final LocalDateTime creationTime;
    private final boolean isJavascript;

    public NetworkAccess(CoreNode coreNode, ContentAddressedStorage dhtClient, MutablePointers mutable, Btree btree, List<String> usernames) {
        this(coreNode, dhtClient, mutable, btree, usernames, false);
    }

    public NetworkAccess(CoreNode coreNode, ContentAddressedStorage dhtClient, MutablePointers mutable, Btree btree, List<String> usernames, boolean isJavascript) {
        this.coreNode = coreNode;
        this.dhtClient = dhtClient;
        this.mutable = mutable;
        this.btree = btree;
        this.usernames = usernames;
        this.creationTime = LocalDateTime.now();
        this.isJavascript = isJavascript;
    }
    
    public boolean isJavascript() {
    	return isJavascript;
    }

    public NetworkAccess withCorenode(CoreNode newCore) {
        return new NetworkAccess(newCore, dhtClient, mutable, btree, usernames, isJavascript);
    }

    @JsMethod
    public CompletableFuture<Boolean> isUsernameRegistered(String username) {
        if (usernames.contains(username))
            return CompletableFuture.completedFuture(true);
        return coreNode.getChain(username).thenApply(chain -> chain.size() > 0);
    }

    public NetworkAccess clear() {
        return new NetworkAccess(coreNode, dhtClient, mutable, new BtreeImpl(mutable, dhtClient), usernames, isJavascript);
    }

    public static CompletableFuture<NetworkAccess> build(HttpPoster poster, boolean isJavascript) {
        int cacheTTL = 7_000;
        System.out.println("Using caching corenode with TTL: " + cacheTTL + " mS");
        CoreNode coreNode = new HTTPCoreNode(poster);
        MutablePointers mutable = new CachingPointers(new HttpMutablePointers(poster), cacheTTL);

        // allow 10MiB of ram for caching btree entries
        ContentAddressedStorage dht = new CachingStorage(new ContentAddressedStorage.HTTP(poster), 10_000, 50 * 1024);
        Btree btree = new BtreeImpl(mutable, dht);
        return coreNode.getUsernames("").thenApply(usernames -> new NetworkAccess(coreNode, dht, mutable, btree, usernames, isJavascript));
    }

    @JsMethod
    public static CompletableFuture<NetworkAccess> buildJS() {
        System.setOut(new ConsolePrintStream());
        System.setErr(new ConsolePrintStream());
        return build(new JavaScriptPoster(), true);
    }

    public static CompletableFuture<NetworkAccess> buildJava(URL target) {
        return build(new JavaPoster(target), false);
    }

    public static CompletableFuture<NetworkAccess> buildJava(int targetPort) {
        try {
            return buildJava(new URL("http://localhost:" + targetPort + "/"));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }
}
