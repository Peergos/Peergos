package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class LocalOnlyStorage implements ContentAddressedStorage {
    private static final IllegalStateException BLOCK_ABSENT = new IllegalStateException("Block not present locally!");

    private final Map<Cid, OpLog.BlockWrite> storage;

    public LocalOnlyStorage(Map<Cid, OpLog.BlockWrite> storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid hash, Optional<BatWithId> bat) {
        return getRaw(hash, bat).thenApply(res -> res.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, Optional<BatWithId> bat) {
        OpLog.BlockWrite res = storage.get(hash);
        if (res != null)
            return Futures.of(Optional.of(res.block));
        return Futures.errored(BLOCK_ABSENT);
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<Cid> id() {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signedHashes, List<byte[]> blocks, TransactionId tid) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signedHashes, List<byte[]> blocks, TransactionId tid, ProgressConsumer<Long> progressCounter) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        throw new IllegalStateException("Unimplemented!");
    }
}
