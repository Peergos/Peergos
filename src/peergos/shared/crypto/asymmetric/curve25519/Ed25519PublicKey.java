package peergos.shared.crypto.asymmetric.curve25519;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.PublicSigningKey;

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

    @Override
    public String toString() {
        return Base64.getEncoder().encodeToString(serialize());
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(Arrays.asList(new CborObject.CborLong(type().value), new CborObject.CborByteArray(publicKey)));
    }

    public byte[] unsignMessage(byte[] signed) {
        if (implementation == null)
            throw new IllegalStateException("Uninitialized crypto-implementation: call peergos.shared.Crypto::init");
        return implementation.crypto_sign_open(signed, publicKey);
    }

    public static Ed25519PublicKey fromCbor(Cborable cbor, Ed25519 provider) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for Ed25519 public key! " + cbor);
        CborObject.CborByteArray key = (CborObject.CborByteArray) ((CborObject.CborList) cbor).value.get(1);
        return new Ed25519PublicKey(key.value, provider);
    }
}
