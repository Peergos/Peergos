package peergos.user.fs;

import peergos.crypto.UserPublicKey;
import peergos.crypto.symmetric.SymmetricKey;
import peergos.util.*;

import java.io.*;

public class Location {
    public static final int MAP_KEY_LENGTH = 32;

    public final UserPublicKey owner, writer;
    public final byte[] mapKey;

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
}
