package peergos.server.storage;

import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class RamBlockMetadataStore implements BlockMetadataStore {

    private final Map<Cid, Map<PublicKeyHash, BlockMetadata>> store;

    public RamBlockMetadataStore() {
        this.store = new HashMap<>(50_000);
    }

    @Override
    public boolean isEmpty() {
        return store.isEmpty();
    }

    @Override
    public Map<Cid, BlockMetadata> getAll(PublicKeyHash owner, List<Cid> blocks) {
        Map<Cid, BlockMetadata> res = new HashMap<>();
        if (owner == null)
            return res;
        for (Cid block : blocks)
            Optional.ofNullable(store.get(block))
                    .map(owners -> owners.get(owner))
                    .ifPresent(m -> res.put(block, m));
        return res;
    }

    @Override
    public void setVersion(PublicKeyHash owner, Cid block, String version) {}

    @Override
    public void put(PublicKeyHash owner, Cid block, String version, BlockMetadata meta) {
        if (owner == null)
            return;
        store.computeIfAbsent(block, b -> new HashMap<>()).putIfAbsent(owner, meta);
    }

    @Override
    public void remove(PublicKeyHash owner, List<Cid> blocks) {
        for (Cid block : blocks) {
            Map<PublicKeyHash, BlockMetadata> owners = store.get(block);
            if (owners == null)
                continue;
            owners.remove(owner);
            if (owners.isEmpty())
                store.remove(block);
        }
    }

    @Override
    public void applyToAll(Consumer<Cid> action) {
        store.keySet().stream().forEach(action);
    }

    @Override
    public void applyToAllSizes(BiConsumer<Cid, Long> action) {
        store.forEach((c, owners) -> action.accept(c, (long) owners.values().iterator().next().size));
    }

    private Stream<Cid> owned(PublicKeyHash owner) {
        return store.entrySet().stream()
                .filter(e -> e.getValue().containsKey(owner))
                .map(Map.Entry::getKey);
    }

    @Override
    public Stream<BlockVersion> list(PublicKeyHash owner) {
        return owned(owner).map(c -> new BlockVersion(c, null, true));
    }

    @Override
    public void listCbor(PublicKeyHash owner, Consumer<List<BlockVersion>> res) {
        res.accept(owned(owner)
                .filter(c -> ! c.isRaw())
                .map(c -> new BlockVersion(c, null, true))
                .collect(Collectors.toList()));
    }

    @Override
    public long size(PublicKeyHash owner) {
        return owned(owner).count();
    }

    @Override
    public void compact() {}
}
