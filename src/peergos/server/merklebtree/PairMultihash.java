package peergos.server.merklebtree;

import org.ipfs.api.Multihash;
import peergos.util.DataSink;
import peergos.util.DataSource;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class PairMultihash {
    public final MaybeMultihash  left, right;

    public PairMultihash(MaybeMultihash left, MaybeMultihash right) {
        this.left = left;
        this.right = right;
    }

    public void serialize(DataOutput dout) throws IOException {
        left.serialize(dout);
        right.serialize(dout);

    }

    public static PairMultihash deserialize(DataInput din) throws IOException {

        MaybeMultihash left = MaybeMultihash.deserialize(din);
        MaybeMultihash right = MaybeMultihash.deserialize(din);

        return new PairMultihash(left, right);
    }
}
