package peergos.user.fs;

import peergos.crypto.UserPublicKey;
import peergos.crypto.symmetric.SymmetricKey;
import peergos.util.ByteArrayWrapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Stream;

public class Location {
    public static final int MAP_KEY_LENGTH = 32;

    public final UserPublicKey ownerKey, writerKey;
    public final ByteArrayWrapper mapKey;

    public Location(UserPublicKey ownerKey, UserPublicKey writerKey, ByteArrayWrapper mapKey) {
        if (mapKey.data.length != MAP_KEY_LENGTH)
            throw  new IllegalArgumentException("map key length "+ mapKey.data.length +" is not "+ MAP_KEY_LENGTH);
        this.ownerKey = ownerKey;
        this.writerKey = writerKey;
        this.mapKey = mapKey;
    }

    public byte[] serialize() {

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        for (byte[] bytes: Arrays.asList(
                ownerKey.serialize(),
                writerKey.serialize(),
                mapKey.data)) {
                    try {
                        bout.write(bytes);
                    } catch (IOException ioe) {
                        throw new IllegalStateException(ioe);
                    }
                }

        return bout.toByteArray();
    }

    public ByteArrayWrapper encrypt(SymmetricKey key, ByteArrayWrapper nonce) {
        byte[] bytes = key.encrypt(serialize(), nonce.data);
        return new ByteArrayWrapper(bytes);
    }

    public static Location deserialize(DataInputStream din) throws IOException {
        UserPublicKey ownerKey = UserPublicKey.deserialize(din);
        UserPublicKey writerKey = UserPublicKey.deserialize(din);
        byte[] mapKey = new byte[MAP_KEY_LENGTH];
        din.readFully(mapKey);
        return new Location(ownerKey, writerKey,
                new ByteArrayWrapper(mapKey));
    }

    public static Location decrypt(SymmetricKey fromKey, ByteArrayWrapper nonce, ByteArrayWrapper location) throws IOException {
        byte[] bytes = fromKey.decrypt(location.data, nonce.data);
        return Location.deserialize(
                new DataInputStream(
                        new ByteArrayInputStream(bytes)));
    }
}
