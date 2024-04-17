package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;

import java.util.*;
import java.util.concurrent.*;

public class GetBlockingStorage extends DelegatingStorage {
    private final ContentAddressedStorage target;

    public GetBlockingStorage(ContentAddressedStorage target) {
        super(target);
        this.target = target;
    }

    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return target.blockStoreProperties();
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return new GetBlockingStorage(target.directToOrigin());
    }

    @Override
    public void clearBlockCache() {
        target.clearBlockCache();
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid key, Optional<BatWithId> bat) {
        throw new IllegalStateException("P2P block gets are not allowed, use bitswap!");
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid key, Optional<BatWithId> bat) {
        throw new IllegalStateException("P2P block gets are not allowed, use bitswap!");
    }

    @Override
    public CompletableFuture<IpnsEntry> getIpnsEntry(Multihash signer) {
        throw new IllegalStateException("P2P IPNS gets are not allowed, use the DHT!");
    }
}
