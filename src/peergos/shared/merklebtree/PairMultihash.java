package peergos.shared.merklebtree;

import peergos.shared.util.DataSink;

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

    public byte[] toByteArray() {
        try {
            DataSink sink = new DataSink();
            serialize(sink);
            return sink.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static PairMultihash deserialize(DataInput din) throws IOException {

        MaybeMultihash left = MaybeMultihash.deserialize(din);
        MaybeMultihash right = MaybeMultihash.deserialize(din);

        return new PairMultihash(left, right);
    }
}
