package peergos.crypto.asymmetric.curve25519;

import peergos.crypto.TweetNaCl;
import peergos.crypto.asymmetric.PublicSigningKey;
import peergos.crypto.asymmetric.SecretSigningKey;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

public class Ed25519SecretKey implements SecretSigningKey {

    private final byte[] secretKey;

    public Ed25519SecretKey(byte[] secretKey) {
        this.secretKey = secretKey;
    }

    public PublicSigningKey.Type type() {
        return PublicSigningKey.Type.Ed25519;
    }

    public byte[] serialize() {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);
            dout.writeByte(type().ordinal());
            dout.write(secretKey);
            return bout.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] getSecretSigningKey() {
        return Arrays.copyOfRange(secretKey, 0, secretKey.length);
    }

    public byte[] signMessage(byte[] message) {
        return TweetNaCl.crypto_sign(message, secretKey);
    }

    public static byte[] getPublicSigningKey(byte[] secretSigningKey) {
        return Arrays.copyOfRange(secretSigningKey, 32, 64);
    }
}
