package peergos.shared.storage.auth;

import peergos.shared.corenode.*;
import peergos.shared.io.ipfs.*;

import java.util.*;
import java.util.concurrent.*;

public class ProxyingBatCave implements BatCave {

    private final List<Cid> serverIds;
    private final CoreNode core;
    private final BatCave local;
    private final BatCaveProxy p2p;

    public ProxyingBatCave(List<Cid> serverIds, CoreNode core, BatCave local, BatCaveProxy p2p) {
        this.serverIds = serverIds;
        this.core = core;
        this.local = local;
        this.p2p = p2p;
    }

    @Override
    public CompletableFuture<List<BatWithId>> getUserBats(String username, byte[] auth) {
        return Proxy.redirectByName(core, serverIds, username,
                user -> local.getUserBats(user, auth),
                (target, user) -> p2p.getUserBats(target, user, auth));
    }

    @Override
    public CompletableFuture<Boolean> addBat(String username, BatId id, Bat bat, byte[] auth) {
        return Proxy.redirectByName(core, serverIds, username,
                user -> local.addBat(user, id, bat, auth),
                (target, user) -> p2p.addBat(target, user, id, bat, auth));
    }

    @Override
    public Optional<Bat> getBat(BatId id) {
        throw new IllegalStateException("Not supported!");
    }
}
