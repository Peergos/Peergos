package peergos.server.storage;

import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.util.LRUCache;
import peergos.shared.util.Pair;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * An in-memory LRU cache over a BlockMetadataStore.
 * Avoids hitting the database for frequently-read block metadata (e.g. during authReads).
 */
public class CachingBlockMetadataStore implements BlockMetadataStore {

    private final BlockMetadataStore target;
    private final Map<Pair<PublicKeyHash, Cid>, BlockMetadata> cache;

    public CachingBlockMetadataStore(BlockMetadataStore target, int cacheSize) {
        this.target = target;
        this.cache = Collections.synchronizedMap(new LRUCache<>(cacheSize));
    }

    @Override
    public Map<Cid, BlockMetadata> getAll(PublicKeyHash owner, List<Cid> blocks) {
        Map<Cid, BlockMetadata> result = new HashMap<>();
        List<Cid> misses = new ArrayList<>();
        for (Cid block : blocks) {
            BlockMetadata cached = owner == null ? null : cache.get(new Pair<>(owner, block));
            if (cached != null)
                result.put(block, cached);
            else
                misses.add(block);
        }
        if (! misses.isEmpty()) {
            Map<Cid, BlockMetadata> fromDb = target.getAll(owner, misses);
            fromDb.forEach((block, meta) -> cache.put(new Pair<>(owner, block), meta));
            result.putAll(fromDb);
        }
        return result;
    }

    @Override
    public void setVersion(PublicKeyHash owner, Cid block, String version) {
        target.setVersion(owner, block, version);
    }

    /** What a caller puts isn't cached: the store keeps an owner's first row for a block and ignores a later one,
     *  and a cached later row - with bats of its writer's choosing - would answer for the block in its place. */
    @Override
    public void put(PublicKeyHash owner, Cid block, String version, BlockMetadata meta) {
        target.put(owner, block, version, meta);
    }

    @Override
    public void put(PublicKeyHash owner, List<Cid> blocks, List<byte[]> data) {
        target.put(owner, blocks, data);
    }

    /** The rows go first, so a lookup that starts after the eviction finds nothing to cache again */
    @Override
    public void remove(PublicKeyHash owner, List<Cid> blocks) {
        target.remove(owner, blocks);
        for (Cid block : blocks)
            cache.remove(new Pair<>(owner, block));
    }

    @Override
    public boolean needsBlockstoreListing(PublicKeyHash owner) {
        return target.needsBlockstoreListing(owner);
    }

    @Override
    public void blockstoreListed(PublicKeyHash owner) {
        target.blockstoreListed(owner);
    }

    @Override
    public long size(PublicKeyHash owner) {
        return target.size(owner);
    }

    @Override
    public boolean isEmpty() {
        return target.isEmpty();
    }

    @Override
    public void applyToAll(Consumer<Cid> action) {
        target.applyToAll(action);
    }

    @Override
    public void applyToAllSizes(BiConsumer<Cid, Long> action) {
        target.applyToAllSizes(action);
    }

    @Override
    public Stream<BlockVersion> list(PublicKeyHash owner) {
        return target.list(owner);
    }

    @Override
    public void listCbor(PublicKeyHash owner, Consumer<List<BlockVersion>> res) {
        target.listCbor(owner, res);
    }

    @Override
    public void compact() {
        target.compact();
    }
}
