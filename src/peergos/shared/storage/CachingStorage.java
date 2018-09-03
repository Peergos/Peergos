package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multiaddr.*;
import peergos.shared.io.ipfs.multihash.*;
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
    public CompletableFuture<List<Multihash>> put(PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks) {
        return target.put(writer, signatures, blocks);
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
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks) {
        return target.putRaw(writer, signatures, blocks);
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
    public CompletableFuture<List<Multihash>> recursivePin(Multihash h) {
        return target.recursivePin(h);
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(Multihash h) {
        return target.recursiveUnpin(h);
    }

    @Override
    public CompletableFuture<List<MultiAddress>> pinUpdate(Multihash existing, Multihash updated) {
        return target.pinUpdate(existing, updated);
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
