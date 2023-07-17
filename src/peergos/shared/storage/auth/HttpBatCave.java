package peergos.shared.storage.auth;

import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.util.stream.*;

public class HttpBatCave implements BatCaveProxy, BatCave {
    private static final String P2P_PROXY_PROTOCOL = "/http";
	private static final Logger LOG = Logger.getGlobal();

    private final HttpPoster direct, p2p;

    public HttpBatCave(HttpPoster direct, HttpPoster p2p)
    {
        this.direct = direct;
        this.p2p = p2p;
    }

    private static String getProxyUrlPrefix(Multihash targetId) {
        return "/p2p/" + targetId.toString() + P2P_PROXY_PROTOCOL + "/";
    }

    @Override
    public CompletableFuture<List<BatWithId>> getUserBats(String username, byte[] auth) {
        return getUserBats("", direct, username, auth);
    }

    @Override
    public CompletableFuture<List<BatWithId>> getUserBats(Multihash targetServerId, String username, byte[] auth) {
        return getUserBats(getProxyUrlPrefix(targetServerId), p2p, username, auth);
    }

    private CompletableFuture<List<BatWithId>> getUserBats(String urlPrefix, HttpPoster poster, String username, byte[] auth) {
        return poster.get(urlPrefix + Constants.BATS_URL + "getUserBats?username=" + username + "&auth=" + ArrayOps.bytesToHex(auth))
                .thenApply(res ->
                        ((CborObject.CborList)CborObject.fromByteArray(res)).value
                                .stream()
                                .map(BatWithId::fromCbor)
                                .collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<Boolean> addBat(String username, BatId id, Bat bat, byte[] auth) {
        return addBat("", direct, username, id, bat, auth);
    }

    @Override
    public CompletableFuture<Boolean> addBat(Multihash targetServerId, String username, BatId id, Bat bat, byte[] auth) {
        return addBat(getProxyUrlPrefix(targetServerId), p2p, username, id, bat, auth);
    }

    private CompletableFuture<Boolean> addBat(String urlPrefix, HttpPoster poster, String username, BatId id, Bat bat, byte[] auth)
    {
        return poster.get(urlPrefix + Constants.BATS_URL + "addBat?username=" + username
                + "&batid=" + id.id
                + "&bat=" + bat.encodeSecret()
                + "&auth=" + ArrayOps.bytesToHex(auth))
                .thenApply(res -> ((CborObject.CborBoolean)CborObject.fromByteArray(res)).value);
    }

    @Override
    public Optional<Bat> getBat(BatId id) {
        throw new IllegalStateException("Cannot be called over http!");
    }
}
