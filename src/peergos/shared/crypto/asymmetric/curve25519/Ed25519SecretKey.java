package peergos.shared.crypto.asymmetric.curve25519;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.crypto.asymmetric.SecretSigningKey;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;

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
        Map<String, CborObject> cbor = new TreeMap<>();
        cbor.put("t", new CborObject.CborLong(type().value));
        cbor.put("k", new CborObject.CborByteArray(secretKey));
        return CborObject.CborMap.build(cbor);
    }

    public byte[] signMessage(byte[] message) {
        return implementation.crypto_sign(message, secretKey);
    }

    public static SecretSigningKey fromCbor(CborObject cbor, Ed25519 provider) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for Ed25519 secret key! " + cbor);
        CborObject.CborByteArray key = (CborObject.CborByteArray) ((CborObject.CborMap) cbor).values.get(new CborObject.CborString("k"));
        return new Ed25519SecretKey(key.value, provider);
    }
}
