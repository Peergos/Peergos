package peergos.server.storage;

import peergos.server.storage.auth.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class AuthedCachingStorage extends DelegatingStorage {
    private final ContentAddressedStorage target;
    private final Map<Multihash, byte[]> cache;
    private final Map<Multihash, Boolean> legacyBlocks;
    private final Map<Multihash, CompletableFuture<Optional<CborObject>>> pending;
    private final Map<Multihash, CompletableFuture<Optional<byte[]>>> pendingRaw;
    private final BlockRequestAuthoriser authoriser;
    private final Hasher h;
    private final Cid ourNodeId;
    private final int maxValueSize, cacheSize;

    public AuthedCachingStorage(ContentAddressedStorage target,
                                BlockRequestAuthoriser authoriser,
                                Hasher h,
                                int cacheSize,
                                int maxValueSize) {
        super(target);
        this.target = target;
        this.ourNodeId = target.id().join();
        this.authoriser = authoriser;
        this.h = h;
        this.cache = Collections.synchronizedMap(new LRUCache<>(cacheSize));
        this.legacyBlocks = Collections.synchronizedMap(new LRUCache<>(cacheSize));
        this.maxValueSize = maxValueSize;
        this.cacheSize = cacheSize;
        this.pending = Collections.synchronizedMap(new LRUCache<>(100));
        this.pendingRaw = Collections.synchronizedMap(new LRUCache<>(100));
    }

    public Collection<byte[]> getCached() {
        return cache.values();
    }

    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return target.blockStoreProperties();
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return new AuthedCachingStorage(target.directToOrigin(), authoriser, h, cacheSize, maxValueSize);
    }

    @Override
    public void clearBlockCache() {
        cache.clear();
        target.clearBlockCache();
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                            PublicKeyHash writer,
                                            List<byte[]> signedHashes,
                                            List<byte[]> blocks,
                                            TransactionId tid) {
        return target.put(owner, writer, signedHashes, blocks, tid)
                .thenApply(res -> {
                    for (int i=0; i < blocks.size(); i++) {
                        byte[] block = blocks.get(i);
                        if (block.length < maxValueSize)
                            cache.put(res.get(i), block);
                    }
                    return res;
                });
    }

    private CompletableFuture<byte[]> authoriseGet(Cid key, byte[] block, Optional<BatWithId> bat) {
        if (key.isRaw() && bat.isEmpty() && legacyBlocks.containsKey(key))
            return Futures.of(block);
        return bat.map(b -> b.bat.generateAuth(key, ourNodeId, 300, S3Request.currentDatetime(), bat.get().id, h)
                .thenApply(BlockAuth::encode)).orElse(Futures.of(""))
                .thenCompose(auth -> authoriser.allowRead(key, block, ourNodeId, auth))
                .thenCompose(allow -> allow ? Futures.of(block) : Futures.errored(new Throwable("Unauthorised!")));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid key, Optional<BatWithId> bat) {
        if (cache.containsKey(key))
            return authoriseGet(key, cache.get(key), bat)
                    .thenApply(res -> Optional.of(CborObject.fromByteArray(res)));

        if (pending.containsKey(key))
            return pending.get(key)
                    .thenCompose(copt -> copt.isEmpty() ?
                            Futures.of(Optional.empty()) :
                            authoriseGet(key, copt.get().serialize(), bat)
                                    .thenApply(b -> copt));

        CompletableFuture<Optional<CborObject>> pipe = new CompletableFuture<>();
        pending.put(key, pipe);

        CompletableFuture<Optional<CborObject>> result = new CompletableFuture<>();
        target.get(owner, key, bat).thenAccept(cborOpt -> {
            if (cborOpt.isPresent()) {
                byte[] value = cborOpt.get().toByteArray();
                if (value.length > 0 && value.length < maxValueSize)
                    cache.put(key, value);
            }
            pending.remove(key);
            pipe.complete(cborOpt);
            result.complete(cborOpt);
        }).exceptionally(t -> {
            pending.remove(key);
            pipe.completeExceptionally(t);
            result.completeExceptionally(t);
            return null;
        });
        return result;
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signatures,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressConsumer) {
        return target.putRaw(owner, writer, signatures, blocks, tid, progressConsumer)
                .thenApply(res -> {
                    for (int i=0; i < blocks.size(); i++) {
                        byte[] block = blocks.get(i);
                        if (block.length < maxValueSize)
                            cache.put(res.get(i), block);
                    }
                    return res;
                });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid key, Optional<BatWithId> bat) {
        if (cache.containsKey(key))
            return authoriseGet(key, cache.get(key), bat)
                    .thenApply(res -> Optional.of(res));

        if (pendingRaw.containsKey(key))
            return pendingRaw.get(key)
                    .thenCompose(opt -> opt.isEmpty() ?
                            Futures.of(Optional.empty()) :
                            authoriseGet(key, opt.get(), bat)
                                    .thenApply(b -> opt));

        CompletableFuture<Optional<byte[]>> pipe = new CompletableFuture<>();
        pendingRaw.put(key, pipe);
        return target.getRaw(owner, key, bat).thenApply(rawOpt -> {
            if (rawOpt.isPresent()) {
                byte[] value = rawOpt.get();
                if (value.length > 0 && value.length < maxValueSize) {
                    cache.put(key, value);
                    if (bat.isEmpty() && key.isRaw())
                        legacyBlocks.put(key, true);
                }
            }
            pendingRaw.remove(key);
            pipe.complete(rawOpt);
            return rawOpt;
        }).exceptionally(t -> {
            pendingRaw.remove(key);
            pipe.completeExceptionally(t);
            return Optional.empty();
        });
    }
}
