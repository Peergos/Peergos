package peergos.shared.mutable;

import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;

import java.util.*;
import java.util.concurrent.*;

public class ProxyingMutablePointers implements MutablePointers {

    private final List<Cid> serverIds;
    private final CoreNode core;
    private final MutablePointers local;
    private final MutablePointersProxy p2p;

    public ProxyingMutablePointers(List<Cid> serverIds, CoreNode core, MutablePointers local, MutablePointersProxy p2p) {
        this.serverIds = serverIds;
        this.core = core;
        this.local = local;
        this.p2p = p2p;
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        return Proxy.redirectCall(core,
                serverIds,
                owner,
                () -> local.setPointer(owner, writer, writerSignedBtreeRootHash),
                target -> p2p.setPointer(target, owner, writer, writerSignedBtreeRootHash));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
        return Proxy.redirectCall(core,
                serverIds,
                owner,
                () -> local.getPointer(owner, writer),
                target -> p2p.getPointer(target, owner, writer));
    }

    @Override
    public MutablePointers clearCache() {
        return new ProxyingMutablePointers(serverIds, core, local.clearCache(), p2p);
    }
}
