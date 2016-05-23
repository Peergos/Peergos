package peergos.crypto.asymmetric;

import peergos.crypto.asymmetric.curve25519.Curve25519SecretKey;

import java.io.*;

public interface SecretBoxingKey {

    PublicBoxingKey.Type type();

    byte[] serialize();

    byte[] getSecretBoxingKey();

    byte[] decryptMessage(byte[] cipher, PublicBoxingKey from);

    static SecretBoxingKey deserialize(DataInput din) throws IOException {
        PublicBoxingKey.Type t = PublicBoxingKey.Type.byValue(din.readUnsignedByte());
        switch (t) {
            case Curve25519:
                byte[] key = new byte[32];
                din.readFully(key);
                return new Curve25519SecretKey(key, PublicBoxingKey.PROVIDERS.get(PublicBoxingKey.Type.Curve25519));
            default: throw new IllegalStateException("Unknown Secret Boxing Key type: "+t.name());
        }
    }
}
