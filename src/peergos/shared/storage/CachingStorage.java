package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class CachingStorage implements ContentAddressedStorage {
    private final ContentAddressedStorage target;
    private final LRUCache<Multihash, byte[]> cache;
    private final LRUCache<Multihash, CompletableFuture<Optional<CborObject>>> pending;
    private final LRUCache<Multihash, CompletableFuture<Optional<byte[]>>> pendingRaw;
    private final int maxValueSize;

    public CachingStorage(ContentAddressedStorage target, int cacheSize, int maxValueSize) {
        this.target = target;
        this.cache = new LRUCache<>(cacheSize);
        this.maxValueSize = maxValueSize;
        this.pending = new LRUCache<>(100);
        this.pendingRaw = new LRUCache<>(100);
    }

    @Override
    public CompletableFuture<Multihash> id() {
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
    public CompletableFuture<Boolean> gc() {
        return target.gc();
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                  PublicKeyHash writer,
                                                  List<byte[]> signatures,
                                                  List<byte[]> blocks,
                                                  TransactionId tid) {
        return target.put(owner, writer, signatures, blocks, tid)
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
    public CompletableFuture<Optional<CborObject>> get(Multihash key) {
        if (cache.containsKey(key))
            return CompletableFuture.completedFuture(Optional.of(CborObject.fromByteArray(cache.get(key))));

        if (pending.containsKey(key))
            return pending.get(key);

        CompletableFuture<Optional<CborObject>> pipe = new CompletableFuture<>();
        pending.put(key, pipe);

        CompletableFuture<Optional<CborObject>> result = new CompletableFuture<>();
        target.get(key).thenAccept(cborOpt -> {
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
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     List<byte[]> signatures,
                                                     List<byte[]> blocks,
                                                     TransactionId tid) {
        return target.putRaw(owner, writer, signatures, blocks, tid)
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
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash key) {
        if (cache.containsKey(key))
            return CompletableFuture.completedFuture(Optional.of(cache.get(key)));

        if (pendingRaw.containsKey(key))
            return pendingRaw.get(key);

        CompletableFuture<Optional<byte[]>> pipe = new CompletableFuture<>();
        pendingRaw.put(key, pipe);
        return target.getRaw(key).thenApply(rawOpt -> {
            if (rawOpt.isPresent()) {
                byte[] value = rawOpt.get();
                if (value.length > 0 && value.length < maxValueSize)
                    cache.put(key, value);
            }
            pendingRaw.remove(key);
            pipe.complete(rawOpt);
            return rawOpt;
        }).exceptionally(t -> {
            pending.remove(key);
            pipe.completeExceptionally(t);
            return null;
        });
    }

    @Override
    public CompletableFuture<List<FragmentWithHash>> downloadFragments(List<Multihash> hashes,
                                                                       ProgressConsumer<Long> monitor,
                                                                       double spaceIncreaseFactor) {
        return target.downloadFragments(hashes, monitor, spaceIncreaseFactor);
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authReads(List<Multihash> blocks) {
        return target.authReads(blocks);
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authWrites(PublicKeyHash owner,
                                                            PublicKeyHash writer,
                                                            List<byte[]> signedHashes,
                                                            List<Integer> blockSizes,
                                                            boolean isRaw,
                                                            TransactionId tid) {
        return target.authWrites(owner, writer, signedHashes, blockSizes, isRaw, tid);
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash h) {
        return target.recursivePin(owner, h);
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash h) {
        return target.recursiveUnpin(owner, h);
    }

    @Override
    public CompletableFuture<List<Multihash>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
        return target.pinUpdate(owner, existing, updated);
    }

    @Override
    public CompletableFuture<List<Multihash>> getLinks(Multihash root) {
        return target.getLinks(root);
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        return target.getSize(block);
    }
}
