package peergos.server.storage;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class DelayingStorage implements ContentAddressedStorage {
    private final ContentAddressedStorage source;
    private final int readDelay, writeDelay;

    public DelayingStorage(ContentAddressedStorage source, int readDelay, int writeDelay) {
        this.source = source;
        this.readDelay = readDelay;
        this.writeDelay = writeDelay;
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    @Override
    public CompletableFuture<Cid> id() {
        return source.id();
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        return source.startTransaction(owner);
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        return source.closeTransaction(owner, tid);
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {}
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                            PublicKeyHash writer,
                                            List<byte[]> signedHashes,
                                            List<byte[]> blocks,
                                            TransactionId tid) {
        sleep(writeDelay);
        return source.put(owner, writer, signedHashes, blocks, tid);
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signatures,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressConsumer) {
        sleep(writeDelay);
        return source.putRaw(owner, writer, signatures, blocks, tid, progressConsumer);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid object, Optional<BatWithId> bat) {
        try {
            sleep(readDelay);
            return source.getRaw(object, bat);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid hash, Optional<BatWithId> bat) {
        try {
            sleep(readDelay);
            return source.get(hash, bat);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat) {
        sleep(readDelay);
        return source.getChampLookup(owner, root, champKey, bat);
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        try {
            return source.getSize(block);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public static NetworkAccess buildNetwork(NetworkAccess source, int readDelay, int writeDelay) {
        ContentAddressedStorage delayingBlocks = new DelayingStorage(source.dhtClient, readDelay, writeDelay);
        return new NetworkAccess(source.coreNode, source.account, source.social, delayingBlocks, source.batCave,
                source.mutable, source.tree, source.synchronizer, source.instanceAdmin, source.spaceUsage,
                source.serverMessager, source.hasher, source.usernames, false);
    }
}
