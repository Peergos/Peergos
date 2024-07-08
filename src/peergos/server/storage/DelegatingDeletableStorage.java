package peergos.server.storage;

import peergos.server.storage.auth.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class DelegatingDeletableStorage implements DeletableContentAddressedStorage {

    private final DeletableContentAddressedStorage target;

    public DelegatingDeletableStorage(DeletableContentAddressedStorage target) {
        this.target = target;
    }

    @Override
    public void setPki(CoreNode pki) {
        target.setPki(pki);
    }

    @Override
    public List<List<Cid>> bulkGetLinks(List<Multihash> peerIds, List<Want> wants) {
        return target.bulkGetLinks(peerIds, wants);
    }

    @Override
    public Stream<Cid> getAllBlockHashes(boolean useBlockstore) {
        return target.getAllBlockHashes(useBlockstore);
    }

    @Override
    public void getAllBlockHashVersions(Consumer<List<BlockVersion>> res) {
        target.getAllBlockHashVersions(res);
    }

    @Override
    public List<Cid> getOpenTransactionBlocks() {
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
    public void delete(Cid hash) {
        target.delete(hash);
    }

    @Override
    public void bloomAdd(Multihash hash) {
        target.bloomAdd(hash);
    }

    @Override
    public void bulkDelete(List<BlockVersion> blocks) {
        target.bulkDelete(blocks);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, Cid hash, String auth) {
        return target.get(peerIds, hash, auth);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, Cid hash, String auth) {
        return target.getRaw(peerIds, hash, auth);
    }

    @Override
    public CompletableFuture<List<Cid>> mirror(PublicKeyHash owner, List<Multihash> peerIds, Optional<Cid> existing,
                                               Optional<Cid> updated, Optional<BatWithId> mirrorBat, Cid ourNodeId,
                                               Consumer<List<Cid>> newBlockProcessor, TransactionId tid, Hasher hasher) {
        return target.mirror(owner, peerIds, existing, updated, mirrorBat, ourNodeId, newBlockProcessor, tid, hasher);
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
    public CompletableFuture<List<PresignedUrl>> authWrites(PublicKeyHash owner,
                                                            PublicKeyHash writer,
                                                            List<byte[]> signedHashes,
                                                            List<Integer> blockSizes,
                                                            List<List<BatId>> batIds,
                                                            boolean isRaw,
                                                            TransactionId tid) {
        return target.authWrites(owner, writer, signedHashes, blockSizes, batIds, isRaw, tid);
    }

    @Override
    public CompletableFuture<Cid> id() {
        return target.id();
    }

    @Override
    public CompletableFuture<List<Cid>> ids() {
        return target.ids();
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
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return target.get(owner, hash, bat);
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
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return target.getRaw(owner, hash, bat);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h) {
        return target.get(peerIds, hash, bat, ourId, h);
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat, Optional<Cid> committedRoot) {
        return target.getChampLookup(owner, root, champKey, bat, committedRoot);
    }

    @Override
    public CompletableFuture<EncryptedCapability> getSecretLink(SecretLink link) {
        return target.getSecretLink(link);
    }

    @Override
    public CompletableFuture<LinkCounts> getLinkCounts(String owner, LocalDateTime after, BatWithId mirrorBat) {
        return target.getLinkCounts(owner, after, mirrorBat);
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
    public CompletableFuture<IpnsEntry> getIpnsEntry(Multihash signer) {
        return target.getIpnsEntry(signer);
    }

    @Override
    public CompletableFuture<List<Cid>> getLinks(Cid root) {
        return target.getLinks(root);
    }

    @Override
    public CompletableFuture<BlockMetadata> getBlockMetadata(Cid block) {
        return target.getBlockMetadata(block);
    }
}
