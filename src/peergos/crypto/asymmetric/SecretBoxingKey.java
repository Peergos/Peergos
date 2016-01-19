package peergos.crypto.asymmetric;

import peergos.crypto.asymmetric.curve25519.Curve25519SecretKey;

import java.io.DataInputStream;
import java.io.IOException;

public interface SecretBoxingKey {

    PublicBoxingKey.Type type();

    byte[] serialize();

    byte[] getSecretBoxingKey();

    byte[] decryptMessage(byte[] cipher, PublicBoxingKey from);

    static SecretBoxingKey deserialize(DataInputStream din) throws IOException {
        PublicBoxingKey.Type t = PublicBoxingKey.Type.values()[din.read()];
        switch (t) {
            case Curve25519:
                byte[] key = new byte[32];
                din.readFully(key);
                return new Curve25519SecretKey(key);
            default: throw new IllegalStateException("Unknown Secret Boxing Key type: "+t.name());
        }
    }
}
