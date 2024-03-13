package peergos.server.storage;

import peergos.shared.io.ipfs.Cid;

import java.util.*;

public class BlockVersion {
    public final Cid cid;
    public final String version;
    public final boolean isLatest;

    public BlockVersion(Cid cid, String version, boolean isLatest) {
        this.cid = cid;
        this.version = version;
        this.isLatest = isLatest;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BlockVersion that = (BlockVersion) o;
        return Objects.equals(cid, that.cid) && Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cid, version);
    }
}
