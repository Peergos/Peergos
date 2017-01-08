package peergos.shared.crypto.asymmetric.curve25519;

import peergos.shared.cbor.*;
import peergos.shared.crypto.TweetNaCl;
import peergos.shared.crypto.asymmetric.PublicBoxingKey;
import peergos.shared.crypto.asymmetric.SecretBoxingKey;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;

public class Curve25519SecretKey implements SecretBoxingKey {

    private final byte[] secretKey;
    private final Curve25519 implementation;

    public Curve25519SecretKey(byte[] secretKey, Curve25519 provider) {
        this.secretKey = secretKey;
        this.implementation = provider;
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

    @Override
    public byte[] getSecretBoxingKey() {
        return Arrays.copyOfRange(secretKey, 0, secretKey.length);
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> cbor = new TreeMap<>();
        cbor.put("t", new CborObject.CborLong(type().value));
        cbor.put("k", new CborObject.CborByteArray(secretKey));
        return CborObject.CborMap.build(cbor);
    }

    public byte[] decryptMessage(byte[] cipher, PublicBoxingKey from) {
        byte[] nonce = Arrays.copyOfRange(cipher, cipher.length - TweetNaCl.BOX_NONCE_BYTES, cipher.length);
        cipher = Arrays.copyOfRange(cipher, 0, cipher.length - TweetNaCl.BOX_NONCE_BYTES);
        return implementation.crypto_box_open(cipher, nonce, from.getPublicBoxingKey(), secretKey);
    }

    public static SecretBoxingKey fromCbor(CborObject cbor, Curve25519 provider) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for SecretBoxingKey! " + cbor);
        CborObject.CborByteArray key = (CborObject.CborByteArray) ((CborObject.CborMap) cbor).values.get(new CborObject.CborString("k"));
        return new Curve25519SecretKey(key.value, provider);
    }
}
