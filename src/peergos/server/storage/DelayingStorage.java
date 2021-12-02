package peergos.server.storage;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
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

    @Override
    public CompletableFuture<Boolean> gc() {
        return source.gc();
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {}
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                  PublicKeyHash writer,
                                                  List<byte[]> signedHashes,
                                                  List<byte[]> blocks,
                                                  TransactionId tid) {
        sleep(writeDelay);
        return source.put(owner, writer, signedHashes, blocks, tid);
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     List<byte[]> signatures,
                                                     List<byte[]> blocks,
                                                     TransactionId tid,
                                                     ProgressConsumer<Long> progressConsumer) {
        sleep(writeDelay);
        return source.putRaw(owner, writer, signatures, blocks, tid, progressConsumer);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash object, String auth) {
        try {
            sleep(readDelay);
            return source.getRaw(object, auth);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash hash, String auth) {
        try {
            sleep(readDelay);
            return source.get(hash, auth);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Multihash root, byte[] champKey) {
        sleep(readDelay);
        return source.getChampLookup(owner, root, champKey);
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash h) {
        return source.recursivePin(owner, h);
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash h) {
        return source.recursiveUnpin(owner, h);
    }

    @Override
    public CompletableFuture<List<Multihash>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
        return source.pinUpdate(owner, existing, updated);
    }

    @Override
    public CompletableFuture<List<Multihash>> getLinks(Multihash root, String auth) {
        try {
            return source.getLinks(root, auth);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
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
        return new NetworkAccess(source.coreNode, source.account, source.social, delayingBlocks, source.mutable, source.tree,
                source.synchronizer, source.instanceAdmin, source.spaceUsage, source.serverMessager,
                source.hasher, source.usernames, false);
    }
}
