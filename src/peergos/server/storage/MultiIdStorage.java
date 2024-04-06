package peergos.server.storage;

import io.libp2p.core.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class MultiIdStorage extends DelegatingDeletableStorage {

    private final List<Cid> ourIds;

    public MultiIdStorage(DeletableContentAddressedStorage target, List<PeerId> ourIds) {
        super(target);
        this.ourIds = ourIds.stream()
                .map(PeerId::getBytes)
                .map(Multihash::decode)
                .map(m -> new Cid(1, Cid.Codec.LibP2pKey, m.type, m.getHash()))
                .collect(Collectors.toList());
    }

    @Override
    public CompletableFuture<Cid> id() {
        return Futures.of(ourIds.get(ourIds.size() - 1));
    }

    @Override
    public CompletableFuture<List<Cid>> ids() {
        return Futures.of(ourIds);
    }
}
