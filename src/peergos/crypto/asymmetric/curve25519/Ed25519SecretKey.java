package peergos.crypto.asymmetric.curve25519;

import peergos.crypto.asymmetric.PublicSigningKey;
import peergos.crypto.asymmetric.SecretSigningKey;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

public class Ed25519SecretKey implements SecretSigningKey {

    private final byte[] secretKey;
    private final Ed25519 implementation;

    public Ed25519SecretKey(byte[] secretKey, Ed25519 provider) {
        this.secretKey = secretKey;
        this.implementation = provider;
    }

    public PublicSigningKey.Type type() {
        return PublicSigningKey.Type.Ed25519;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Ed25519SecretKey that = (Ed25519SecretKey) o;

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

    public byte[] getSecretSigningKey() {
        return Arrays.copyOfRange(secretKey, 0, secretKey.length);
    }

    public byte[] signMessage(byte[] message) {
        return implementation.crypto_sign(message, secretKey);
    }

    public static byte[] getPublicSigningKey(byte[] secretSigningKey) {
        return Arrays.copyOfRange(secretSigningKey, 32, 64);
    }
}
