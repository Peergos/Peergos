package peergos.shared;

import jsinterop.annotations.*;
import peergos.client.*;
import peergos.shared.corenode.*;
import peergos.shared.user.*;

import java.net.*;
import java.util.*;

public class NetworkAccess {

    public final CoreNode coreNode;
    public final DHTClient dhtClient;
    public final Btree btree;

    public NetworkAccess(CoreNode coreNode, DHTClient dhtClient, Btree btree) {
        this.coreNode = coreNode;
        this.dhtClient = dhtClient;
        this.btree = btree;
    }

    public static NetworkAccess build(HttpPoster poster) {
        CoreNode coreNode = new HTTPCoreNode(poster);
        DHTClient dht = new DHTClient.CachingDHTClient(new DHTClient.HTTP(poster), 1000, 50 * 1024);
        Btree btree = new Btree.HTTP(poster);
//        Btree btree = new BtreeImpl(coreNode, dht);
        return new NetworkAccess(coreNode, dht, btree);
    }

    @JsMethod
    public static NetworkAccess buildJS() {
        System.setOut(new ConsolePrintStream());
        System.setErr(new ConsolePrintStream());
        return build(new JavaScriptPoster());
    }

    public static NetworkAccess buildJava(URL target) {
        return build(new JavaPoster(target));
    }

    public static NetworkAccess buildJava(int targetPort) {
        try {
            return buildJava(new URL("http://localhost:" + targetPort + "/"));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }
}
