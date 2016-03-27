package peergos.crypto.asymmetric;

import peergos.crypto.asymmetric.curve25519.Ed25519SecretKey;

import java.io.*;

public interface SecretSigningKey {

    PublicSigningKey.Type type();

    byte[] serialize();

    byte[] getSecretSigningKey();

    byte[] signMessage(byte[] message);

    static SecretSigningKey deserialize(DataInput din) throws IOException {
        PublicSigningKey.Type t = PublicSigningKey.Type.byValue(din.readUnsignedByte());
        switch (t) {
            case Ed25519:
                byte[] key = new byte[64];
                din.readFully(key);
                return new Ed25519SecretKey(key);
            default: throw new IllegalStateException("Unknown Secret Signing Key type: "+t.name());
        }
    }
}
