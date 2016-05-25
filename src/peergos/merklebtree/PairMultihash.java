package peergos.merklebtree;

import peergos.util.DataSink;

import java.io.*;

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

    public byte[] toByteArray() throws IOException {
        DataSink sink = new DataSink();
        serialize(sink);
        return sink.toByteArray();
    }

    public static PairMultihash deserialize(DataInput din) throws IOException {

        MaybeMultihash left = MaybeMultihash.deserialize(din);
        MaybeMultihash right = MaybeMultihash.deserialize(din);

        return new PairMultihash(left, right);
    }
}
