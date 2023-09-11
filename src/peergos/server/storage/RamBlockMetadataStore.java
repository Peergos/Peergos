package peergos.server.storage;

import peergos.shared.io.ipfs.cid.*;

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
    public void put(Cid block, BlockMetadata meta) {
        store.put(block, meta);
    }

    @Override
    public void remove(Cid block) {
        store.remove(block);
    }

    @Override
    public Stream<Cid> list() {
        return store.keySet().stream();
    }

    @Override
    public void compact() {}
}
