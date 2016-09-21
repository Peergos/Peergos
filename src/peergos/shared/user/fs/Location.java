package peergos.shared.user.fs;

import peergos.shared.crypto.UserPublicKey;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;

public class Location {
    public static final int MAP_KEY_LENGTH = 32;

    public final UserPublicKey owner, writer;
    private final byte[] mapKey;

    public Location(UserPublicKey owner, UserPublicKey writer, byte[] mapKey) {
        if (mapKey.length != MAP_KEY_LENGTH)
            throw  new IllegalArgumentException("map key length "+ mapKey.length +" is not "+ MAP_KEY_LENGTH);
        this.owner = owner;
        this.writer = writer;
        this.mapKey = mapKey;
    }

    public byte[] serialize() {
        DataSink sink = new DataSink();
        sink.writeArray(owner.getPublicKeys());
        sink.writeArray(writer.getPublicKeys());
        sink.writeArray(mapKey);
        return sink.toByteArray();
    }

    public byte[] encrypt(SymmetricKey key, byte[] nonce) {
        return key.encrypt(serialize(), nonce);
    }

    public byte[] getMapKey() {
        return Arrays.copyOf(mapKey, mapKey.length);
    }

    public String toString() {
        return new ByteArrayWrapper(mapKey).toString();
    }

    public static Location deserialize(DataSource din) {
        try {
            UserPublicKey ownerKey = UserPublicKey.fromByteArray(din.readArray());
            UserPublicKey writerKey = UserPublicKey.fromByteArray(din.readArray());
            byte[] mapKey = din.readArray();
            return new Location(ownerKey, writerKey, mapKey);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Location decrypt(SymmetricKey fromKey, byte[] nonce, byte[] location) {
        byte[] bytes = fromKey.decrypt(location, nonce);
        return Location.deserialize(new DataSource(bytes));
    }

    public Location withWriter(UserPublicKey newWriter) {
        return new Location(owner, newWriter, mapKey);
    }

    public Location withMapKey(byte[] newMapKey) {
        return new Location(owner, writer, newMapKey);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Location location = (Location) o;

        if (owner != null ? !owner.equals(location.owner) : location.owner != null) return false;
        if (writer != null ? !writer.equals(location.writer) : location.writer != null) return false;
        return Arrays.equals(mapKey, location.mapKey);

    }

    @Override
    public int hashCode() {
        int result = owner != null ? owner.hashCode() : 0;
        result = 31 * result + (writer != null ? writer.hashCode() : 0);
        result = 31 * result + Arrays.hashCode(mapKey);
        return result;
    }
}
