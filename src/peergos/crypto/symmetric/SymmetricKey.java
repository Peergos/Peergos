package peergos.crypto.symmetric;

import peergos.crypto.random.*;
import peergos.util.*;

import java.io.*;
import java.util.*;

public interface SymmetricKey
{
    Map<Integer, Type> byValue = new HashMap<>();
    enum Type {
        TweetNaCl(0x1);

        public final int value;

        Type(int value) {
            this.value = value;
            byValue.put(value, this);
        }

        public static Type byValue(int val) {
            return byValue.get(val);
        }
    }

    Map<Type, Salsa20Poly1305> PROVIDERS = new HashMap<>();

    Map<Type, SafeRandom> RNG_PROVIDERS = new HashMap<>();

    static void addProvider(Type t, Salsa20Poly1305 provider) {
        PROVIDERS.put(t, provider);
    }

    static void setRng(Type t, SafeRandom rng) {
        RNG_PROVIDERS.put(t, rng);
    }

    Type type();

    byte[] getKey();

    byte[] encrypt(byte[] data, byte[] nonce);

    byte[] decrypt(byte[] data, byte[] nonce);

    byte[] createNonce();

    static SymmetricKey deserialize(byte[] in) {
        try {
            return deserialize(new DataInputStream(new ByteArrayInputStream(in)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static SymmetricKey deserialize(DataInputStream din) throws IOException {
        Type t = Type.byValue(din.read());
        switch (t) {
            case TweetNaCl:
                byte[] key = new byte[32];
                din.readFully(key);
                return new TweetNaClKey(key, PROVIDERS.get(t), RNG_PROVIDERS.get(t));
            default: throw new IllegalStateException("Unknown Symmetric Key type: "+t.name());
        }

    }

    default byte[] serialize() {
        DataSink sink = new DataSink();
        sink.write(type().value);
        sink.write(getKey());
        return sink.toByteArray();
    }

    static SymmetricKey random() {
        return TweetNaClKey.random(PROVIDERS.get(Type.TweetNaCl), RNG_PROVIDERS.get(Type.TweetNaCl));
    }

    static SymmetricKey createNull() {
        return new TweetNaClKey(new byte[32], PROVIDERS.get(Type.TweetNaCl), RNG_PROVIDERS.get(Type.TweetNaCl));
    }
}
