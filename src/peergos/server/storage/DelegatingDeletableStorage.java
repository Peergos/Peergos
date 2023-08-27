package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class DelegatingDeletableStorage implements DeletableContentAddressedStorage {

    private final DeletableContentAddressedStorage target;

    public DelegatingDeletableStorage(DeletableContentAddressedStorage target) {
        this.target = target;
    }

    @Override
    public Stream<Cid> getAllBlockHashes() {
        return target.getAllBlockHashes();
    }

    @Override
    public List<Multihash> getOpenTransactionBlocks() {
        return target.getOpenTransactionBlocks();
    }

    @Override
    public void clearOldTransactions(long cutoffMillis) {
        target.clearOldTransactions(cutoffMillis);
    }

    @Override
    public boolean hasBlock(Cid hash) {
        return target.hasBlock(hash);
    }

    @Override
    public void delete(Multihash hash) {
        target.delete(hash);
    }

    @Override
    public void bloomAdd(Multihash hash) {
        target.bloomAdd(hash);
    }

    @Override
    public void bulkDelete(List<Multihash> blocks) {
        target.bulkDelete(blocks);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid hash, String auth) {
        return target.get(hash, auth);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, String auth) {
        return target.getRaw(hash, auth);
    }

    @Override
    public CompletableFuture<List<Cid>> mirror(PublicKeyHash owner, Optional<Cid> existing, Optional<Cid> updated, Optional<BatWithId> mirrorBat, Cid ourNodeId, TransactionId tid, Hasher hasher) {
        return target.mirror(owner, existing, updated, mirrorBat, ourNodeId, tid, hasher);
    }

    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return target.blockStoreProperties();
    }

    @Override
    public void clearBlockCache() {
        target.clearBlockCache();
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return target.directToOrigin();
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authReads(List<MirrorCap> blocks) {
        return target.authReads(blocks);
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authWrites(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signedHashes, List<Integer> blockSizes, boolean isRaw, TransactionId tid) {
        return target.authWrites(owner, writer, signedHashes, blockSizes, isRaw, tid);
    }

    @Override
    public CompletableFuture<Cid> id() {
        return target.id();
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        return target.startTransaction(owner);
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        return target.closeTransaction(owner, tid);
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                            PublicKeyHash writer,
                                            List<byte[]> signedHashes,
                                            List<byte[]> blocks,
                                            TransactionId tid) {
        return target.put(owner, writer, signedHashes, blocks, tid);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid hash, Optional<BatWithId> bat) {
        return target.get(hash, bat);
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signedHashes,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressCounter) {
        return target.putRaw(owner, writer, signedHashes, blocks, tid, progressCounter);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, Optional<BatWithId> bat) {
        return target.getRaw(hash, bat);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h) {
        return target.get(hash, bat, ourId, h);
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat, Optional<Cid> committedRoot) {
        return target.getChampLookup(owner, root, champKey, bat, committedRoot);
    }

    @Override
    public CompletableFuture<List<FragmentWithHash>> downloadFragments(PublicKeyHash owner,
                                                                       List<Cid> hashes,
                                                                       List<BatWithId> bats,
                                                                       Hasher h,
                                                                       ProgressConsumer<Long> monitor,
                                                                       double spaceIncreaseFactor) {
        return target.downloadFragments(owner, hashes, bats, h, monitor, spaceIncreaseFactor);
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        return target.getSize(block);
    }

    @Override
    public CompletableFuture<List<Cid>> getLinks(Cid root, String auth) {
        return target.getLinks(root, auth);
    }

    @Override
    public CompletableFuture<Pair<Integer, List<Cid>>> getLinksAndSize(Cid block, String auth) {
        return target.getLinksAndSize(block, auth);
    }
}
