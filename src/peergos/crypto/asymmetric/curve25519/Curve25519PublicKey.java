package peergos.crypto.asymmetric.curve25519;

import peergos.crypto.TweetNaCl;
import peergos.crypto.asymmetric.PublicBoxingKey;
import peergos.crypto.asymmetric.SecretBoxingKey;
import peergos.util.ArrayOps;

import java.io.ByteArrayOutputStream;
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

    public byte[] serialize() {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);
            dout.writeByte(type().ordinal());
            dout.write(publicKey);
            return bout.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] getPublicBoxingKey() {
        return Arrays.copyOfRange(publicKey, 0, publicKey.length);
    }

    public byte[] encryptMessageFor(byte[] input, SecretBoxingKey from) {
        byte[] nonce = createNonce();
        return ArrayOps.concat(TweetNaCl.crypto_box(input, nonce, publicKey, from.getSecretBoxingKey()), nonce);
    }

    public byte[] createNonce() {
        byte[] nonce = new byte[TweetNaCl.BOX_NONCE_BYTES];
        rnd.nextBytes(nonce);
        return nonce;
    }
}
