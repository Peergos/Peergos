package peergos.server.storage;

import peergos.shared.storage.auth.*;
import peergos.shared.io.ipfs.Cid;
import java.util.*;
import java.util.stream.Collectors;

public class BlockMetadata {

    public final int size;
    public final List<Cid> links;
    public final List<BatId> batids;

    public BlockMetadata(int size, List<Cid> links, List<BatId> batids) {
        this.size = size;
        this.links = links;
        this.batids = batids;
    }

    public static BlockMetadata fromJSON(Map<String, Object> json) {
        int size = (Integer) json.get("size");
        List<Cid> links = ((List<String>) json.get("links"))
                .stream()
                .map(Cid::decode)
                .collect(Collectors.toList());
        List<BatId> bats = ((List<String>) json.get("links"))
                .stream()
                .map(Cid::decode)
                .map(BatId::new)
                .collect(Collectors.toList());;
        return new BlockMetadata(size, links, bats);
    }
}
