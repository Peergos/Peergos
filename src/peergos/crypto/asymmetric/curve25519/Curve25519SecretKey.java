package peergos.crypto.asymmetric.curve25519;

import peergos.crypto.TweetNaCl;
import peergos.crypto.asymmetric.PublicBoxingKey;
import peergos.crypto.asymmetric.SecretBoxingKey;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

public class Curve25519SecretKey implements SecretBoxingKey {

    private final byte[] secretKey;

    public Curve25519SecretKey(byte[] secretKey) {
        this.secretKey = secretKey;
    }

    public PublicBoxingKey.Type type() {
        return PublicBoxingKey.Type.Curve25519;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Curve25519SecretKey that = (Curve25519SecretKey) o;

        return Arrays.equals(secretKey, that.secretKey);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(secretKey);
    }

    public byte[] serialize() {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);
            dout.writeByte(type().value);
            dout.write(secretKey);
            return bout.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] getSecretBoxingKey() {
        return Arrays.copyOfRange(secretKey, 0, secretKey.length);
    }

    public byte[] decryptMessage(byte[] cipher, PublicBoxingKey from) {
        byte[] nonce = Arrays.copyOfRange(cipher, cipher.length - TweetNaCl.BOX_NONCE_BYTES, cipher.length);
        cipher = Arrays.copyOfRange(cipher, 0, cipher.length - TweetNaCl.BOX_NONCE_BYTES);
        return TweetNaCl.crypto_box_open(cipher, nonce, from.getPublicBoxingKey(), secretKey);
    }

    public static byte[] getPublicBoxingKey(byte[] secretBoxingKey) {
        byte[] pub = new byte[32];
        TweetNaCl.crypto_scalarmult_base(pub, secretBoxingKey);
        return pub;
    }
}
