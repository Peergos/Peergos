package peergos.crypto.asymmetric;

import peergos.crypto.asymmetric.curve25519.Curve25519PublicKey;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public interface PublicBoxingKey {
    Map<Integer, Type> byValue = new HashMap<>();
    enum Type {
        Curve25519(0xEC);

        public final int value;
        Type(int value)
        {
            this.value = value;
            byValue.put(value, this);
        }

        public static Type byValue(int val) {
            if (!byValue.containsKey(val))
                throw new IllegalStateException("Unknown public boxing key type: " + String.format("%02x", val));
            return byValue.get(val);
        }
    }

    Type type();

    byte[] getPublicBoxingKey();

    byte[] encryptMessageFor(byte[] input, SecretBoxingKey from);

    byte[] createNonce();

    void serialize(DataOutput dout) throws IOException;

    default byte[] toByteArray() {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);
            serialize(dout);
            return bout.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static PublicBoxingKey fromByteArray(byte[] raw) throws IOException {
        return deserialize(new DataInputStream(new ByteArrayInputStream(raw)));
    }

    static PublicBoxingKey deserialize(DataInput din) throws IOException {
        Type t = Type.byValue(din.readUnsignedByte());
        switch (t) {
            case Curve25519:
                byte[] key = new byte[32];
                din.readFully(key);
                return new Curve25519PublicKey(key);
            default: throw new IllegalStateException("Unknown Public Boxing Key type: "+t.name());
        }
    }
}
