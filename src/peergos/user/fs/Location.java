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

    public static Location deserialize(DataInput din) throws IOException {
        UserPublicKey ownerKey = UserPublicKey.deserialize(din);
        UserPublicKey writerKey = UserPublicKey.deserialize(din);
        byte[] mapKey = new byte[MAP_KEY_LENGTH];
        din.readFully(mapKey);
        return new Location(ownerKey, writerKey, mapKey);
    }

    public static Location decrypt(SymmetricKey fromKey, byte[] nonce, byte[] location) throws IOException {
        byte[] bytes = fromKey.decrypt(location, nonce);
        return Location.deserialize(new DataSource(bytes));
    }
}
