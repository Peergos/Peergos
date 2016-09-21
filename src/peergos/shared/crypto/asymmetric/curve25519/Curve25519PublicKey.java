package peergos.shared.crypto.asymmetric.curve25519;

import peergos.shared.crypto.TweetNaCl;
import peergos.shared.crypto.asymmetric.PublicBoxingKey;
import peergos.shared.crypto.asymmetric.SecretBoxingKey;
import peergos.shared.crypto.random.*;
import peergos.shared.util.ArrayOps;

import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

public class Curve25519PublicKey implements PublicBoxingKey {
    private final byte[] publicKey;
    private final Curve25519 implementation;
    private final SafeRandom random;

    public Curve25519PublicKey(byte[] publicKey, Curve25519 provider, SafeRandom random) {
        this.publicKey = publicKey;
        this.implementation = provider;
        this.random = random;
    }

    public PublicBoxingKey.Type type() {
        return Type.Curve25519;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Curve25519PublicKey that = (Curve25519PublicKey) o;

        return Arrays.equals(publicKey, that.publicKey);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(publicKey);
    }

    public byte[] getPublicBoxingKey() {
        return Arrays.copyOfRange(publicKey, 0, publicKey.length);
    }

    public byte[] encryptMessageFor(byte[] input, SecretBoxingKey from) {
        byte[] nonce = createNonce();
        return ArrayOps.concat(implementation.crypto_box(input, nonce, publicKey, from.getSecretBoxingKey()), nonce);
    }

    public void serialize(DataOutput dout) throws IOException {
        dout.writeByte(type().value);
        dout.write(publicKey);
    }

    public byte[] createNonce() {
        byte[] nonce = new byte[TweetNaCl.BOX_NONCE_BYTES];
        random.randombytes(nonce, 0, nonce.length);
        return nonce;
    }
}
