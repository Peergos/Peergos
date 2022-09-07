package peergos.shared.user;

import peergos.shared.*;

import java.util.*;

public class CommittedWriterData {

    public final MaybeMultihash hash;
    public final Optional<Long> sequence;
    public final WriterData props;

    public CommittedWriterData(MaybeMultihash hash, WriterData props, Optional<Long> sequence) {
        this.hash = hash;
        this.props = props;
        this.sequence = sequence;
    }

    @Override
    public String toString() {
        return sequence.map(Object::toString).orElse("") + ":" + hash.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CommittedWriterData that = (CommittedWriterData) o;
        return Objects.equals(hash, that.hash) && sequence.equals(that.sequence);
    }

    @Override
    public int hashCode() {
        return hash.hashCode() ^ sequence.hashCode();
    }
}
