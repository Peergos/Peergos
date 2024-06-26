package peergos.shared.crypto.asymmetric.curve25519;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.crypto.asymmetric.SecretSigningKey;

import java.util.*;
import java.util.concurrent.*;

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

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(Arrays.asList(new CborObject.CborLong(type().value), new CborObject.CborByteArray(secretKey)));
    }

    @Override
    public CompletableFuture<byte[]> signMessage(byte[] message) {
        return implementation.crypto_sign(message, secretKey);
    }

    @Override
    public CompletableFuture<byte[]> signatureOnly(byte[] message) {
        return implementation.crypto_sign(message, secretKey)
                .thenApply(res -> Arrays.copyOf(res, Ed25519PublicKey.SIGNATURE_SIZE_BYTES));
    }

    public static SecretSigningKey fromCbor(Cborable cbor, Ed25519 provider) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for Ed25519 secret key! " + cbor);
        CborObject.CborByteArray key = (CborObject.CborByteArray) ((CborObject.CborList) cbor).value.get(1);
        return new Ed25519SecretKey(key.value, provider);
    }
}
