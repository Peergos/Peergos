package peergos.server.tests;

import peergos.server.storage.BlockMetadata;
import peergos.server.storage.BlockMetadataStore;
import peergos.server.storage.BlockVersion;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.util.Pair;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class RequestCountingBlockMetadataStore implements BlockMetadataStore {
    private final BlockMetadataStore target;
    private final AtomicLong count = new AtomicLong(0);

    public RequestCountingBlockMetadataStore(BlockMetadataStore target) {
        this.target = target;
    }

    public long getRequestCount() {
        return count.get();
    }

    public void resetRequestCount() {
        count.set(0);
    }

    @Override
    public boolean isEmpty() {
        return target.isEmpty();
    }

    @Override
    public Optional<BlockMetadata> get(Cid block) {
        count.incrementAndGet();
        return target.get(block);
    }

    @Override
    public List<Cid> hasBlocks(List<Cid> blocks) {
        count.incrementAndGet();
        return blocks.stream()
                .filter(h -> target.get(h).isPresent())
                .collect(Collectors.toList());
    }

    @Override
    public Map<Cid, BlockMetadata> getAll(List<Cid> blocks) {
        return blocks.stream()
                .map(h -> new Pair<>(h, target.get(h)))
                .filter(p -> p.right.isPresent())
                .collect(Collectors.toMap(p -> p.left, p -> p.right.get()));
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

    @Override
    public void put(PublicKeyHash owner, Cid block, String version, BlockMetadata meta) {
        target.put(owner, block, version, meta);
    }

    @Override
    public void remove(Cid block) {
        target.remove(block);
    }

    @Override
    public long size(PublicKeyHash owner) {
        return target.size(owner);
    }

    @Override
    public void applyToAll(Consumer<Cid> consumer) {
        target.applyToAll(consumer);
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
