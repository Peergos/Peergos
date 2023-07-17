package peergos.server.storage;

import peergos.shared.io.ipfs.Cid;

import java.util.*;
import java.util.stream.*;

public class RamBlockMetadataStore implements BlockMetadataStore {

    private final Map<Cid, BlockMetadata> store;

    public RamBlockMetadataStore() {
        this.store = new HashMap<>(50_000);
    }

    @Override
    public Optional<BlockMetadata> get(Cid block) {
        return Optional.ofNullable(store.get(block));
    }

    @Override
    public void put(Cid block, String version, BlockMetadata meta) {
        store.put(block, meta);
    }

    @Override
    public void remove(Cid block) {
        store.remove(block);
    }

    @Override
    public Stream<BlockVersion> list() {
        return store.keySet().stream().map(c -> new BlockVersion(c, null, true));
    }

    @Override
    public Stream<BlockVersion> listCbor() {
        return store.keySet()
                .stream()
                .filter(c -> ! c.isRaw())
                .map(c -> new BlockVersion(c, null, true));
    }

    @Override
    public long size() {
        return store.size();
    }

    @Override
    public void compact() {}
}
