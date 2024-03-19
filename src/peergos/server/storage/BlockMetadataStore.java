package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.storage.auth.*;
import peergos.shared.io.ipfs.Cid;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public interface BlockMetadataStore {

    Optional<BlockMetadata> get(Cid block);

    void put(Cid block, String version, BlockMetadata meta);

    void remove(Cid block);

    long size();

    Stream<BlockVersion> list();

    void listCbor(Consumer<List<BlockVersion>> res);

    default BlockMetadata put(Cid block, String version, byte[] data) {
        BlockMetadata meta = extractMetadata(block, data);
        put(block, version, meta);
        return meta;
    }

    static BlockMetadata extractMetadata(Cid block, byte[] data) {
        if (block.isRaw()) {
            BlockMetadata meta = new BlockMetadata(data.length, Collections.emptyList(), Bat.getRawBlockBats(data));
            return meta;
        } else {
            CborObject cbor = CborObject.fromByteArray(data);
            List<Cid> links = cbor
                    .links().stream()
                    .map(h -> (Cid) h)
                    .collect(Collectors.toList());
            List<BatId> batIds = cbor instanceof CborObject.CborMap ?
                    ((CborObject.CborMap) cbor).getList("bats", BatId::fromCbor) :
                    Collections.emptyList();
            BlockMetadata meta = new BlockMetadata(data.length, links, batIds);
            return meta;
        }
    }

    void compact();
}
