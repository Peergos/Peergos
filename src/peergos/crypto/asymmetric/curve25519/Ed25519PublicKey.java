package peergos.crypto.asymmetric.curve25519;

import peergos.crypto.asymmetric.PublicSigningKey;

import java.io.*;
import java.util.*;

public class Ed25519PublicKey implements PublicSigningKey {

    private final byte[] publicKey;
    private final Ed25519 implementation;

    public Ed25519PublicKey(byte[] publicKey, Ed25519 provider) {
        this.publicKey = publicKey;
        this.implementation = provider;
    }

    public PublicSigningKey.Type type() {
        return PublicSigningKey.Type.Ed25519;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Ed25519PublicKey that = (Ed25519PublicKey) o;

        return Arrays.equals(publicKey, that.publicKey);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(publicKey);
    }

    public void serialize(DataOutput dout) throws IOException {
        dout.writeByte(type().value);
        dout.write(publicKey);
    }

    public byte[] getPublicSigningKey() {
        return Arrays.copyOfRange(publicKey, 0, publicKey.length);
    }

    public byte[] unsignMessage(byte[] signed) {
        return implementation.crypto_sign_open(signed, publicKey);
    }
}
