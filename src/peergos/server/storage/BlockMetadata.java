package peergos.server.storage;

import peergos.shared.io.ipfs.cid.*;

import java.util.*;

public class BlockMetadata {

    public final int size;
    public final List<Cid> links;

    public BlockMetadata(int size, List<Cid> links) {
        this.size = size;
        this.links = links;
    }
}
