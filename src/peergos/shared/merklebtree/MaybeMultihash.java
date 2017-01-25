package peergos.shared.merklebtree;

import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.util.*;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class MaybeMultihash {
    private final Multihash hash;

    public MaybeMultihash(Multihash hash) {
        this.hash = hash;
    }

    public boolean isPresent() {
        return hash != null;
    }

    public Multihash get() {
        if (! isPresent())
            throw new IllegalStateException("hash not present");
        return hash;
    }

    public byte[] toBytes() {
        try {
            DataSink sink = new DataSink();
            serialize(sink);
            return sink.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String toString() {
        return hash != null ? hash.toString() : "EMPTY";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MaybeMultihash that = (MaybeMultihash) o;

        return hash != null ? hash.equals(that.hash) : that.hash == null;

    }

    @Override
    public int hashCode() {
        return hash != null ? hash.hashCode() : 0;
    }

    public static MaybeMultihash deserialize(DataInput din) throws IOException {
        int val  = din.readInt();

        boolean isPresent  = val != 0;
        if (! isPresent)
            return MaybeMultihash.EMPTY();
        byte[] data  = new byte[val];
        din.readFully(data);
        return MaybeMultihash.of(Cid.cast(data));
    }

    public void serialize(DataOutput dout) throws IOException {
        if (! isPresent())
            dout.writeInt(0);
        else {
            byte[] bytes = hash.toBytes();
            dout.writeInt(bytes.length);
            dout.write(bytes);
        }
    }

    private static MaybeMultihash EMPTY = new MaybeMultihash(null);

    public static MaybeMultihash EMPTY() {
        return EMPTY;
    }

    public static MaybeMultihash of(Multihash hash) {
        return new MaybeMultihash(hash);
    }
}
