package peergos.server.tests;

import peergos.server.storage.BlockMetadata;
import peergos.server.storage.BlockMetadataStore;
import peergos.server.storage.BlockVersion;
import peergos.shared.io.ipfs.Cid;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
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
    public Optional<BlockMetadata> get(Cid block) {
        count.incrementAndGet();
        return target.get(block);
    }

    @Override
    public void put(Cid block, String version, BlockMetadata meta) {
        target.put(block, version, meta);
    }

    @Override
    public void remove(Cid block) {
        target.remove(block);
    }

    @Override
    public long size() {
        return target.size();
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
    public Stream<BlockVersion> list() {
        return target.list();
    }

    @Override
    public void listCbor(Consumer<List<BlockVersion>> res) {
        target.listCbor(res);
    }

    @Override
    public void compact() {
        target.compact();
    }
}
