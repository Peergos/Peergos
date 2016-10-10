package peergos.shared;

import jsinterop.annotations.*;
import peergos.client.*;
import peergos.shared.corenode.*;
import peergos.shared.user.*;

import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public class NetworkAccess {

    public final CoreNode coreNode;
    public final DHTClient dhtClient;
    public final Btree btree;
    private final List<String> usernames;
    private final LocalDateTime creationTime;

    public NetworkAccess(CoreNode coreNode, DHTClient dhtClient, Btree btree, List<String> usernames) {
        this.coreNode = coreNode;
        this.dhtClient = dhtClient;
        this.btree = btree;
        this.usernames = usernames;
        this.creationTime = LocalDateTime.now();
    }

    @JsMethod
    public CompletableFuture<Boolean> isUsernameRegistered(String username) {
        if (usernames.contains(username))
            return CompletableFuture.completedFuture(true);
        return coreNode.getChain(username).thenApply(chain -> chain.size() > 0);
    }

    public static CompletableFuture<NetworkAccess> build(HttpPoster poster) {
        CoreNode coreNode = new HTTPCoreNode(poster);
        DHTClient dht = new DHTClient.CachingDHTClient(new DHTClient.HTTP(poster), 1000, 50 * 1024);
        Btree btree = new Btree.HTTP(poster);
//        Btree btree = new BtreeImpl(coreNode, dht);
        return coreNode.getUsernames("").thenApply(usernames -> new NetworkAccess(coreNode, dht, btree, usernames));
    }

    @JsMethod
    public static CompletableFuture<NetworkAccess> buildJS() {
        System.setOut(new ConsolePrintStream());
        System.setErr(new ConsolePrintStream());
        return build(new JavaScriptPoster());
    }

    public static CompletableFuture<NetworkAccess> buildJava(URL target) {
        return build(new JavaPoster(target));
    }

    public static CompletableFuture<NetworkAccess> buildJava(int targetPort) {
        try {
            return buildJava(new URL("http://localhost:" + targetPort + "/"));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }
}
