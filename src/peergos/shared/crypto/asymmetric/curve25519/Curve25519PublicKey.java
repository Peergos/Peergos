package peergos.shared.crypto.asymmetric.curve25519;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.PublicBoxingKey;
import peergos.shared.crypto.asymmetric.SecretBoxingKey;
import peergos.shared.crypto.random.*;
import peergos.shared.util.ArrayOps;

import java.util.*;

public class Curve25519PublicKey implements PublicBoxingKey {
    public static final int BOX_NONCE_BYTES = 24;
    private final byte[] publicKey;
    private final Curve25519 implementation;
    private final SafeRandom random;

    public Curve25519PublicKey(byte[] publicKey, Curve25519 provider, SafeRandom random) {
        if (publicKey.length != 32)
            throw new IllegalArgumentException("Incorrect curve25519 public key length! " + publicKey.length);
        this.publicKey = publicKey;
        this.implementation = provider;
        this.random = random;
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
        return ArrayOps.concat(implementation.crypto_box(input, nonce, publicKey, from.getSecretBoxingKey()), nonce);
    }

    public byte[] createNonce() {
        byte[] nonce = new byte[BOX_NONCE_BYTES];
        random.randombytes(nonce, 0, nonce.length);
        return nonce;
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(Arrays.asList(new CborObject.CborLong(type().value), new CborObject.CborByteArray(publicKey)));
    }

    public static Curve25519PublicKey fromCbor(Cborable cbor, Curve25519 provider, SafeRandom random) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for PublicBoxingKey! " + cbor);
        CborObject.CborByteArray key = (CborObject.CborByteArray) ((CborObject.CborList) cbor).value.get(1);
        return new Curve25519PublicKey(key.value, provider, random);
    }
}
