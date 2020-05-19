package peergos.shared.user;

import peergos.shared.*;

import java.util.Objects;

public class CommittedWriterData {

    public final MaybeMultihash hash;
    public final WriterData props;

    public CommittedWriterData(MaybeMultihash hash, WriterData props) {
        this.hash = hash;
        this.props = props;
    }

    @Override
    public String toString() {
        return hash.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CommittedWriterData that = (CommittedWriterData) o;
        return Objects.equals(hash, that.hash);
    }

    @Override
    public int hashCode() {
        return hash.hashCode();
    }

}
