package peergos.shared.user;

import peergos.shared.merklebtree.*;

public class CommittedWriterData {

    public final MaybeMultihash hash;
    public final WriterData props;

    public CommittedWriterData(MaybeMultihash hash, WriterData props) {
        this.hash = hash;
        this.props = props;
    }


}
