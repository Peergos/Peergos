package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.storage.auth.*;

import java.util.*;
import java.util.stream.*;

public interface BlockMetadataStore {

    Optional<BlockMetadata> get(Cid block);

    void put(Cid block, BlockMetadata meta);

    void remove(Cid block);

    default BlockMetadata put(Cid block, byte[] data) {
        BlockMetadata meta = extractMetadata(block, data);
        put(block, meta);
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
