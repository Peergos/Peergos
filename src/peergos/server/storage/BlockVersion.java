package peergos.server.storage;

import peergos.shared.io.ipfs.cid.*;

public class BlockVersion {
    public final Cid cid;
    public final String version;
    public final boolean isLatest;

    public BlockVersion(Cid cid, String version, boolean isLatest) {
        this.cid = cid;
        this.version = version;
        this.isLatest = isLatest;
    }
}
