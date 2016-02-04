package peergos.crypto.asymmetric;

import peergos.crypto.asymmetric.curve25519.Ed25519PublicKey;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public interface PublicSigningKey {
    Map<Integer, Type> byValue = new HashMap<>();
    enum Type {
        Ed25519(0xEC);

        public final int value;

        Type(int value) {
            this.value = value;
            byValue.put(value, this);
        }

        public static Type byValue(int val) {
            if (!byValue.containsKey(val))
                throw new IllegalStateException("Unknown public signing key type: " + String.format("%02x", val));
            return byValue.get(val);
        }
    }

    Type type();

    byte[] getPublicSigningKey();

    byte[] unsignMessage(byte[] signed);

    void serialize(DataOutputStream dout) throws IOException;

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

    static PublicSigningKey fromByteArray(byte[] raw) throws IOException {
        return deserialize(new DataInputStream(new ByteArrayInputStream(raw)));
    }

    static PublicSigningKey deserialize(DataInputStream din) throws IOException {
        Type t = Type.byValue(din.read());
        switch (t) {
            case Ed25519:
                byte[] key = new byte[32];
                din.readFully(key);
                return new Ed25519PublicKey(key);
            default: throw new IllegalStateException("Unknown Public Signing Key type: "+t.name());
        }
    }
}
