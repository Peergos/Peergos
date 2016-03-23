package peergos.server.merklebtree;

import org.ipfs.api.Multihash;
import peergos.util.Serialize;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.time.temporal.ValueRange;
import java.util.Arrays;
import java.util.Optional;

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
        throw new IllegalStateException("Unimplemented");
    }

    public String toString() {
        throw new IllegalStateException("Unimplemented");
    }

    public boolean equals(Object o) {
        throw new IllegalStateException("Unimplemented");
    }

    public int hashCode() {
        throw new IllegalStateException("Unimplemented");
    }


    public static MaybeMultihash deserialize(DataInput din) throws IOException {
        int val  = din.readInt();

        boolean isPresent  = val != 0;
        if (isPresent)
            return MaybeMultihash.EMPTY();
        byte[] data  = new byte[val];
        din.readFully(data);
        return MaybeMultihash.of(
                new Multihash(data));
    }

    public void serialize(DataOutput dout) throws IOException {
        if (isPresent())
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
