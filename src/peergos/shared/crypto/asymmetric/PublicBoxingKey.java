package peergos.shared.crypto.asymmetric;

import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.random.*;
import peergos.shared.util.StringUtils;

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
                throw new IllegalStateException("Unknown public boxing key type: " + StringUtils.format("%02x", val));
            return byValue.get(val);
        }
    }

    Map<Type, Curve25519> PROVIDERS = new HashMap<>();

    static void addProvider(Type t, Curve25519 provider) {
        PROVIDERS.put(t, provider);
    }

    Map<Type, SafeRandom> RNG_PROVIDERS = new HashMap<>();

    static void setRng(Type t, SafeRandom rng) {
        RNG_PROVIDERS.put(t, rng);
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
                return new Curve25519PublicKey(key, PROVIDERS.get(t), RNG_PROVIDERS.get(t));
            default: throw new IllegalStateException("Unknown Public Boxing Key type: "+t.name());
        }
    }
}
