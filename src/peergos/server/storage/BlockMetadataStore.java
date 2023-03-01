package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.cid.*;

import java.util.*;
import java.util.stream.*;

public interface BlockMetadataStore {

    Optional<BlockMetadata> get(Cid block);

    void put(Cid block, BlockMetadata meta);

    void remove(Cid block);

    default void put(Cid block, byte[] data) {
        if (block.isRaw()) {
            put(block, new BlockMetadata(data.length, Collections.emptyList()));
        } else {
            List<Cid> links = CborObject.fromByteArray(data)
                    .links().stream()
                    .map(h -> (Cid) h)
                    .collect(Collectors.toList());
            put(block, new BlockMetadata(data.length, links));
        }
    }

    void compact();
}
