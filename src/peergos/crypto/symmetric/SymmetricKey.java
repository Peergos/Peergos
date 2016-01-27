package peergos.crypto.symmetric;

import org.ibex.nestedvm.Runtime;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
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
                return new TweetNaClKey(key);
            default: throw new IllegalStateException("Unknown Symmetric Key type: "+t.name());
        }

    }

    static SymmetricKey random() {
        return TweetNaClKey.random();
    }
}
