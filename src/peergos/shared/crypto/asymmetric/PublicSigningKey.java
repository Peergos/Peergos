package peergos.shared.crypto.asymmetric;

import jsinterop.annotations.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.util.StringUtils;

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
                throw new IllegalStateException("Unknown public signing key type: " + StringUtils.format("%02x", val));
            return byValue.get(val);
        }
    }

    Map<Type, Ed25519> PROVIDERS = new HashMap<>();

    static void addProvider(Type t, Ed25519 provider) {
        PROVIDERS.put(t, provider);
    }

    Type type();

    @JsMethod
    byte[] getPublicSigningKey();

    @JsMethod
    byte[] unsignMessage(byte[] signed);

    void serialize(DataOutput dout) throws IOException;

    @JsMethod
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

    @JsMethod
    static PublicSigningKey fromByteArray(byte[] raw) throws IOException {
        return deserialize(new DataInputStream(new ByteArrayInputStream(raw)));
    }

    static PublicSigningKey deserialize(DataInput din) throws IOException {
        Type t = Type.byValue(din.readUnsignedByte());
        switch (t) {
            case Ed25519:
                byte[] key = new byte[32];
                din.readFully(key);
                return new Ed25519PublicKey(key, PROVIDERS.get(t));
            default: throw new IllegalStateException("Unknown Public Signing Key type: "+t.name());
        }
    }
}
