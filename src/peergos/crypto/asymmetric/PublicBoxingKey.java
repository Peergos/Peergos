package peergos.crypto.asymmetric;

import peergos.crypto.asymmetric.curve25519.Curve25519PublicKey;

import java.io.DataInputStream;
import java.io.IOException;

public interface PublicBoxingKey {
    enum Type {Curve25519}

    Type type();

    byte[] serialize();

    byte[] getPublicBoxingKey();

    byte[] encryptMessageFor(byte[] input, SecretBoxingKey from);

    byte[] createNonce();

    static PublicBoxingKey deserialize(DataInputStream din) throws IOException {
        Type t = Type.values()[din.read()];
        switch (t) {
            case Curve25519:
                byte[] key = new byte[32];
                din.readFully(key);
                return new Curve25519PublicKey(key);
            default: throw new IllegalStateException("Unknown Public Boxing Key type: "+t.name());
        }
    }
}
