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

public class LocalOnlyStorage implements ContentAddressedStorage {
    public static final IllegalStateException ABSENT_BLOCK = new IllegalStateException("Block not present locally!");

    private final BlockCache cache;

    public LocalOnlyStorage(BlockCache cache) {
        this.cache = cache;
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, Optional<BatWithId> bat) {
        return cache.get(hash)
                .thenApply(opt -> Optional.of(opt.orElseThrow(() -> ABSENT_BLOCK)));
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
    public CompletableFuture<Optional<CborObject>> get(Cid hash, Optional<BatWithId> bat) {
        return getRaw(hash, bat).thenApply(opt -> opt.map(CborObject::fromByteArray));
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
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
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
                                                            boolean isRaw,
                                                            TransactionId tid) {
        throw new IllegalStateException("Unimplemented!");
    }
}
