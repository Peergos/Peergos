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
    private final Map<Cid, BlockMetadata> cache;
    /** A block's row has one owner, kept beside it here */
    private final Map<Cid, Pair<PublicKeyHash, BlockMetadata>> ownedCache;

    public CachingBlockMetadataStore(BlockMetadataStore target, int cacheSize) {
        this.target = target;
        this.cache = Collections.synchronizedMap(new LRUCache<>(cacheSize));
        this.ownedCache = Collections.synchronizedMap(new LRUCache<>(cacheSize));
    }

    @Override
    public Optional<BlockMetadata> get(Cid block) {
        BlockMetadata cached = cache.get(block);
        if (cached != null)
            return Optional.of(cached);
        Optional<BlockMetadata> result = target.get(block);
        result.ifPresent(m -> cache.put(block, m));
        return result;
    }

    @Override
    public Map<Cid, BlockMetadata> getAll(List<Cid> blocks) {
        Map<Cid, BlockMetadata> result = new HashMap<>();
        List<Cid> misses = new ArrayList<>();
        for (Cid block : blocks) {
            BlockMetadata cached = cache.get(block);
            if (cached != null)
                result.put(block, cached);
            else
                misses.add(block);
        }
        if (!misses.isEmpty()) {
            Map<Cid, BlockMetadata> fromDb = target.getAll(misses);
            cache.putAll(fromDb);
            result.putAll(fromDb);
        }
        return result;
    }

    /** Owner scoped lookups are cached with the owner, and only answer for that owner, because the point of
     *  scoping is that a row written under one owner must not answer for another.
     */
    @Override
    public Map<Cid, BlockMetadata> getAll(PublicKeyHash owner, List<Cid> blocks) {
        Map<Cid, BlockMetadata> result = new HashMap<>();
        List<Cid> misses = new ArrayList<>();
        for (Cid block : blocks) {
            Pair<PublicKeyHash, BlockMetadata> cached = ownedCache.get(block);
            if (cached != null && Objects.equals(cached.left, owner))
                result.put(block, cached.right);
            else
                misses.add(block);
        }
        if (! misses.isEmpty()) {
            Map<Cid, BlockMetadata> fromDb = target.getAll(owner, misses);
            fromDb.forEach((block, meta) -> ownedCache.put(block, new Pair<>(owner, meta)));
            result.putAll(fromDb);
        }
        return result;
    }

    @Override
    public List<Cid> hasBlocks(List<Cid> blocks) {
        return target.hasBlocks(blocks);
    }

    @Override
    public Optional<PublicKeyHash> getOwner(Cid block) {
        return target.getOwner(block);
    }

    @Override
    public void setOwner(PublicKeyHash owner, Cid block) {
        target.setOwner(owner, block);
    }

    @Override
    public void setOwnerAndVersion(PublicKeyHash owner, Cid block, String version) {
        target.setOwnerAndVersion(owner, block, version);
    }

    /** What a caller puts isn't cached: the store keeps the first row for a block and ignores a later one, and a
     *  cached later row - with bats of its writer's choosing - would answer for the block in its place. */
    @Override
    public void put(PublicKeyHash owner, Cid block, String version, BlockMetadata meta) {
        target.put(owner, block, version, meta);
        cache.remove(block);
    }

    @Override
    public void put(PublicKeyHash owner, List<Cid> blocks, List<byte[]> data) {
        target.put(owner, blocks, data);
        blocks.forEach(cache::remove);
    }

    @Override
    public void remove(Cid block) {
        cache.remove(block);
        ownedCache.remove(block);
        target.remove(block);
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
