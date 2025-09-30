package peergos.server.storage;

import peergos.shared.io.ipfs.Cid;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class BlockProps {

    public final int size;
    public final List<Cid> links;

    public BlockProps(int size, List<Cid> links) {
        this.size = size;
        this.links = links;
    }

    public static BlockProps fromJSON(Map<String, Object> json) {
        int size = (Integer) json.get("size");
        List<Cid> links = ((List<String>) json.get("links"))
                .stream()
                .map(Cid::decode)
                .collect(Collectors.toList());
        return new BlockProps(size, links);
    }
}
