package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.auth.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class LocalOnlyStorage implements ContentAddressedStorage {
    private final BlockCache cache;
    private final Supplier<CompletableFuture<List<byte[]>>> bulkFetcher;
    private final Hasher h;

    public LocalOnlyStorage(BlockCache cache, Supplier<CompletableFuture<List<byte[]>>> bulkFetcher, Hasher h) {
        this.cache = cache;
        this.bulkFetcher = bulkFetcher;
        this.h = h;
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return cache.get(hash)
                .thenCompose(opt -> {
                    if (opt.isPresent())
                        return Futures.of(opt);
                    return bulkFetcher.get()
                            .thenCompose(blocks -> Futures.combineAll(blocks.stream().map(data ->
                                            h.sha256(data).thenApply(hashb -> cache.put(new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, hashb), data)))
                                    .collect(Collectors.toList())))
                            .thenCompose(x ->  cache.get(hash));
                });
    }

    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return Futures.of(BlockStoreProperties.empty());
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public void clearBlockCache() {
        cache.clear();
    }

    @Override
    public CompletableFuture<Cid> id() {
        return Futures.of(new Cid(1, Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, new byte[32]));
    }

    @Override
    public CompletableFuture<List<Cid>> ids() {
        return Futures.of(Arrays.asList(new Cid(1, Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, new byte[32])));
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
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat, Optional<Cid> committedRoot) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
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
    public CompletableFuture<List<PresignedUrl>> authReads(List<MirrorCap> blocks) {
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
}
