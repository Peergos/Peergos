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
    public List<BlockMetadata> bulkGetLinks(List<Multihash> peerIds, PublicKeyHash owner, List<Want> wants) {
        return target.bulkGetLinks(peerIds, owner, wants);
    }

    @Override
    public Stream<Pair<PublicKeyHash, Cid>> getAllBlockHashes(boolean useBlockstore) {
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
    public void bulkDelete(List<BlockVersion> blocks) {
        target.bulkDelete(blocks);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, String auth, boolean persistBlock) {
        return target.get(peerIds, owner, hash, auth, persistBlock);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, String auth, boolean persistBlock) {
        return target.getRaw(peerIds, owner, hash, auth, persistBlock);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds,
                                                      PublicKeyHash owner,
                                                      Cid hash,
                                                      Optional<BatWithId> bat,
                                                      Cid ourId,
                                                      Hasher h,
                                                      boolean doAuth,
                                                      boolean persistBlock) {
        return target.getRaw(peerIds, owner, hash, bat, ourId, h, doAuth, persistBlock);
    }

    @Override
    public CompletableFuture<List<Cid>> mirror(String username, PublicKeyHash owner, PublicKeyHash writer, List<Multihash> peerIds, Optional<Cid> existing,
                                               Optional<Cid> updated, Optional<BatWithId> mirrorBat, Cid ourNodeId,
                                               NewBlocksProcessor newBlockProcessor, TransactionId tid, Hasher hasher) {
        return target.mirror(username, owner, writer, peerIds, existing, updated, mirrorBat, ourNodeId, newBlockProcessor, tid, hasher);
    }

    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return target.blockStoreProperties();
    }

    @Override
    public CompletableFuture<String> linkHost(PublicKeyHash owner) {
        return target.linkHost(owner);
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
    public CompletableFuture<List<PresignedUrl>> authReads(PublicKeyHash owner, List<BlockMirrorCap> blocks) {
        return target.authReads(owner, blocks);
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
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h, boolean persistBlock) {
        return target.get(peerIds, owner, hash, bat, ourId, h, persistBlock);
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, List<ChunkMirrorCap> caps, Optional<Cid> committedRoot) {
        return target.getChampLookup(owner, root, caps, committedRoot);
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
    public CompletableFuture<Optional<Integer>> getSize(PublicKeyHash owner, Multihash block) {
        return target.getSize(owner, block);
    }

    @Override
    public CompletableFuture<IpnsEntry> getIpnsEntry(Multihash signer) {
        return target.getIpnsEntry(signer);
    }

    @Override
    public CompletableFuture<List<Cid>> getLinks(PublicKeyHash owner, Cid root, List<Multihash> peerids) {
        return target.getLinks(owner, root, peerids);
    }

    @Override
    public CompletableFuture<BlockMetadata> getBlockMetadata(PublicKeyHash owner, Cid block) {
        return target.getBlockMetadata(owner, block);
    }

    @Override
    public Optional<BlockCache> getBlockCache() {
        return target.getBlockCache();
    }
}
