package peergos.server.storage;

import io.ipfs.cid.Cid;
import org.peergos.blockstore.metadatadb.BlockMetadata;
import org.peergos.blockstore.metadatadb.BlockMetadataStore;

import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DelegatingBlockMetadataStore implements BlockMetadataStore {

    private final peergos.server.storage.BlockMetadataStore store;

    public DelegatingBlockMetadataStore(peergos.server.storage.BlockMetadataStore store) {
        this.store = store;
    }

    @Override
    public Optional<org.peergos.blockstore.metadatadb.BlockMetadata> get(Cid block) {
        Optional<peergos.server.storage.BlockMetadata> bmOpt = store.get(
                peergos.shared.io.ipfs.Cid.cast(block.toBytes()));
        if (bmOpt.isEmpty()) {
            return Optional.empty();
        } else {
            return Optional.of(new org.peergos.blockstore.metadatadb.BlockMetadata(bmOpt.get().size,
                    bmOpt.get().links.stream().map(c -> io.ipfs.cid.Cid.cast(c.toBytes())).collect(Collectors.toList()))
            );
        }
    }

    @Override
    public void put(Cid block, BlockMetadata meta) {
    }

    @Override
    public void remove(Cid block) {
    }

    @Override
    public Stream<Cid> list() {
        return store.list().filter(bv -> bv.isLatest).map(bv2 -> io.ipfs.cid.Cid.cast(bv2.cid.toBytes()));
    }

    @Override
    public Stream<Cid> listCbor() {
        List<Cid> res = new ArrayList<>(1000);
        store.listCbor(results -> res.addAll(results.stream()
                .filter(bv -> bv.isLatest)
                .map(bv2 -> io.ipfs.cid.Cid.cast(bv2.cid.toBytes()))
                .collect(Collectors.toList())));
        return res.stream();
    }

    @Override
    public long size() {
        return store.size();
    }

    @Override
    public void compact() {
        store.compact();
    }
}