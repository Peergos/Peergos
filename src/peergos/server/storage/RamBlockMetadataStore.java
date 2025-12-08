package peergos.server.storage;

import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class RamBlockMetadataStore implements BlockMetadataStore {

    private final Map<Cid, BlockMetadata> store;

    public RamBlockMetadataStore() {
        this.store = new HashMap<>(50_000);
    }

    @Override
    public boolean isEmpty() {
        return store.isEmpty();
    }

    @Override
    public Optional<BlockMetadata> get(Cid block) {
        return Optional.ofNullable(store.get(block));
    }

    @Override
    public void put(PublicKeyHash owner, Cid block, String version, BlockMetadata meta) {
        store.put(block, meta);
    }

    @Override
    public void remove(Cid block) {
        store.remove(block);
    }

    @Override
    public void applyToAll(Consumer<Cid> action) {
        store.keySet().stream().forEach(action);
    }

    @Override
    public void applyToAllSizes(BiConsumer<Cid, Long> action) {
        store.entrySet().stream().forEach(e -> action.accept(e.getKey(), (long) e.getValue().size));
    }

    @Override
    public Stream<BlockVersion> list(PublicKeyHash owner) {
        return store.keySet().stream().map(c -> new BlockVersion(c, null, true));
    }

    @Override
    public void listCbor(PublicKeyHash owner, Consumer<List<BlockVersion>> res) {
        res.accept(store.keySet()
                .stream()
                .filter(c -> ! c.isRaw())
                .map(c -> new BlockVersion(c, null, true))
                .collect(Collectors.toList()));
    }

    @Override
    public long size(PublicKeyHash owner) {
        return store.size();
    }

    @Override
    public void compact() {}
}
