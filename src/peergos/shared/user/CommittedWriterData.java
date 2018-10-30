package peergos.shared.user;

import peergos.shared.merklebtree.*;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CommittedWriterData {

    public final MaybeMultihash hash;
    public final WriterData props;

    public CommittedWriterData(MaybeMultihash hash, WriterData props) {
        this.hash = hash;
        this.props = props;
    }
}
