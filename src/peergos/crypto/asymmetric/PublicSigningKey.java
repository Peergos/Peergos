package peergos.crypto.asymmetric;

import peergos.crypto.asymmetric.curve25519.Ed25519PublicKey;

import java.io.DataInputStream;
import java.io.IOException;

public interface PublicSigningKey {
    enum Type {Ed25519}

    Type type();

    byte[] serialize();

    byte[] getPublicSigningKey();

    byte[] unsignMessage(byte[] signed);

    static PublicSigningKey deserialize(DataInputStream din) throws IOException {
        Type t = Type.values()[din.read()];
        switch (t) {
            case Ed25519:
                byte[] key = new byte[32];
                din.readFully(key);
                return new Ed25519PublicKey(key);
            default: throw new IllegalStateException("Unknown Public Signing Key type: "+t.name());
        }
    }
}
