package peergos.server.storage;

import peergos.shared.storage.auth.*;
import peergos.shared.io.ipfs.Cid;
import java.util.*;

public class BlockMetadata {

    public final int size;
    public final List<Cid> links;
    public final List<BatId> batids;

    public BlockMetadata(int size, List<Cid> links, List<BatId> batids) {
        this.size = size;
        this.links = links;
        this.batids = batids;
    }
}
