package peergos.shared.storage.auth;

import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;

import java.util.*;
import java.util.concurrent.*;

public class ProxyingBatCave implements BatCave {

    private final Multihash serverId;
    private final CoreNode core;
    private final BatCave local;
    private final BatCaveProxy p2p;

    public ProxyingBatCave(Multihash serverId, CoreNode core, BatCave local, BatCaveProxy p2p) {
        this.serverId = serverId;
        this.core = core;
        this.local = local;
        this.p2p = p2p;
    }

    @Override
    public CompletableFuture<List<BatWithId>> getUserBats(String username, byte[] auth) {
        return core.getPublicKeyHash(username)
                .thenCompose(owner -> Proxy.redirectCall(core,
                        serverId,
                        owner.get(),
                        () -> local.getUserBats(username, auth),
                        target -> p2p.getUserBats(target, username, auth)));
    }

    @Override
    public CompletableFuture<Boolean> addBat(String username, BatId id, Bat bat, byte[] auth) {
        return core.getPublicKeyHash(username)
                .thenCompose(owner -> Proxy.redirectCall(core,
                        serverId,
                        owner.get(),
                        () -> local.addBat(username, id, bat, auth),
                        target -> p2p.addBat(target, username, id, bat, auth)));
    }

    @Override
    public Optional<Bat> getBat(BatId id) {
        throw new IllegalStateException("Not supported!");
    }
}
