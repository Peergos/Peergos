package peergos.server.storage;

import peergos.shared.io.ipfs.Cid;

import java.util.Objects;

public class UserBlockVersion {
    public final String username;
    public final Cid cid;
    public final String version;
    public final boolean isLatest;

    public UserBlockVersion(String username, Cid cid, String version, boolean isLatest) {
        this.username = username;
        this.cid = cid;
        this.version = version;
        this.isLatest = isLatest;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserBlockVersion that = (UserBlockVersion) o;
        return isLatest == that.isLatest && Objects.equals(username, that.username) && Objects.equals(cid, that.cid) && Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(username, cid, version, isLatest);
    }

    @Override
    public String toString() {
        if (version == null)
            return (username == null ? "" : username + "/") + cid.toString();
        if (username == null)
            return cid.toString() + ":" + version;
        return username + "/" + cid.toString() + ":" + version;
    }
}
