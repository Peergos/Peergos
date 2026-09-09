package peergos.shared.mutable;

import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class ProxyingMutablePointers implements MutablePointers {

    private final List<Cid> serverIds;
    private final CoreNode core;
    private final MutablePointers local;
    private final MutablePointersProxy p2p;
    private final Function<PublicKeyHash, Boolean> weMirror;

    public ProxyingMutablePointers(List<Cid> serverIds,
                                   CoreNode core,
                                   MutablePointers local,
                                   MutablePointersProxy p2p,
                                   Function<PublicKeyHash, Boolean> weMirror) {
        this.serverIds = serverIds;
        this.core = core;
        this.local = local;
        this.p2p = p2p;
        this.weMirror = weMirror;
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
    public CompletableFuture<Boolean> setPointers(PublicKeyHash owner, List<SignedPointerUpdate> updates) {
        return Proxy.redirectCall(core,
                serverIds,
                owner,
                () -> local.setPointers(owner, updates),
                target -> p2p.setPointers(target, owner, updates));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
        // a pointer can go stale, so our mirror of it is only used when the home server can't be reached
        return Proxy.redirectCallWithMirrorFallback(core,
                serverIds,
                owner,
                () -> local.getPointer(owner, writer),
                target -> p2p.getPointer(target, owner, writer),
                weMirror);
    }

    @Override
    public MutablePointers clearCache() {
        return new ProxyingMutablePointers(serverIds, core, local.clearCache(), p2p, weMirror);
    }
}
