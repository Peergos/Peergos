package peergos.shared.user;

import peergos.shared.*;

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
}
