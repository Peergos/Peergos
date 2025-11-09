package peergos.shared.storage;

import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.auth.BatId;
import peergos.shared.storage.auth.BatWithId;
import peergos.shared.user.fs.EncryptedCapability;
import peergos.shared.user.fs.FragmentWithHash;
import peergos.shared.user.fs.SecretLink;
import peergos.shared.util.EfficientHashMap;
import peergos.shared.util.Futures;
import peergos.shared.util.Pair;
import peergos.shared.util.ProgressConsumer;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class LocalRamStorage implements ContentAddressedStorage {
    private final Hasher h;
    private Map<Cid, byte[]> blocks;

    private LocalRamStorage(Hasher h, EfficientHashMap<Cid, byte[]> blocks) {
        this.h = h;
        this.blocks = blocks;
    }

    public static CompletableFuture<LocalRamStorage> build(Hasher h, List<byte[]> cborBlocks) {
        EfficientHashMap<Cid, byte[]> blocks = new EfficientHashMap<>();
        return Futures.combineAllInOrder(cborBlocks.stream().map(b -> h.sha256(b)
                        .thenApply(hash -> new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash))
                        .thenApply(cid -> new Pair<>(cid, b)))
                .collect(Collectors.toList()))
                .thenApply(mappings -> {
                    mappings.forEach(m -> blocks.put(m.left, m.right));
                    return new LocalRamStorage(h, blocks);
                });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return Futures.of(Optional.ofNullable(blocks.get(hash)));
    }

    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return Futures.of(BlockStoreProperties.empty());
    }

    @Override
    public CompletableFuture<String> linkHost(PublicKeyHash owner) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public void clearBlockCache() {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<Cid> id() {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<List<Cid>> ids() {
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
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return getRaw(owner, hash, bat).thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signatures,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressCounter) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, List<ChunkMirrorCap> caps, Optional<Cid> committedRoot) {
        return getChampLookup(owner, root, caps, committedRoot, h);
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(PublicKeyHash owner, Multihash block) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<EncryptedCapability> getSecretLink(SecretLink link) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<LinkCounts> getLinkCounts(String owner, LocalDateTime after, BatWithId mirrorBat) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<IpnsEntry> getIpnsEntry(Multihash signer) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<List<FragmentWithHash>> downloadFragments(PublicKeyHash owner,
                                                                       List<Cid> hashes,
                                                                       List<BatWithId> bats,
                                                                       Hasher h,
                                                                       ProgressConsumer<Long> monitor,
                                                                       double spaceIncreaseFactor) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authReads(PublicKeyHash owner, List<BlockMirrorCap> blocks) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authWrites(PublicKeyHash owner,
                                                            PublicKeyHash writer,
                                                            List<byte[]> signedHashes,
                                                            List<Integer> blockSizes,
                                                            List<List<BatId>> batIds,
                                                            boolean isRaw,
                                                            TransactionId tid) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public Optional<BlockCache> getBlockCache() {
        return Optional.empty();
    }
}
