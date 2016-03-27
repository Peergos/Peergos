package peergos.crypto.asymmetric.curve25519;

import peergos.crypto.TweetNaCl;
import peergos.crypto.asymmetric.PublicBoxingKey;
import peergos.crypto.asymmetric.SecretBoxingKey;
import peergos.util.ArrayOps;

import java.io.ByteArrayOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

public class Curve25519PublicKey implements PublicBoxingKey {
    private final byte[] publicKey;
    private static final Random rnd = new Random(); // TODO make SecureRandom

    public Curve25519PublicKey(byte[] publicKey) {
        this.publicKey = publicKey;
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
        return ArrayOps.concat(TweetNaCl.crypto_box(input, nonce, publicKey, from.getSecretBoxingKey()), nonce);
    }

    public void serialize(DataOutput dout) throws IOException {
        dout.writeByte(type().value);
        dout.write(publicKey);
    }

    public byte[] createNonce() {
        byte[] nonce = new byte[TweetNaCl.BOX_NONCE_BYTES];
        rnd.nextBytes(nonce);
        return nonce;
    }
}
