package peergos.shared.crypto.symmetric;

import peergos.shared.cbor.*;
import peergos.shared.crypto.random.*;

import java.util.*;

public class TweetNaClKey implements SymmetricKey
{
    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 24;

    private final byte[] secretKey;
    private final boolean isDirty;
    private final Salsa20Poly1305 implementation;
    private final SafeRandom random;

    public TweetNaClKey(byte[] secretKey, boolean isDirty, Salsa20Poly1305 implementation, SafeRandom random)
    {
        if (secretKey.length != KEY_BYTES)
            throw new IllegalStateException("Incorrect key size! ("+secretKey.length+")");
        this.secretKey = secretKey;
        this.isDirty = isDirty;
        this.implementation = implementation;
        this.random = random;
    }

    public Type type() {
        return Type.TweetNaCl;
    }

    public byte[] getKey()
    {
        return secretKey;
    }

    public boolean isDirty() {
        return isDirty;
    }

    public SymmetricKey makeDirty() {
        return new TweetNaClKey(secretKey, true, implementation, random);
    }

    public byte[] encrypt(byte[] data, byte[] nonce)
    {
        return encrypt(secretKey, data, nonce, implementation);
    }

    public byte[] decrypt(byte[] data, byte[] nonce)
    {
        return decrypt(secretKey, data, nonce, implementation);
    }

    private static byte[] encrypt(byte[] key, byte[] data, byte[] nonce, Salsa20Poly1305 implementation)
    {
        return implementation.secretbox(data, nonce, key);
    }

    private static byte[] decrypt(byte[] key, byte[] cipher, byte[] nonce, Salsa20Poly1305 implementation)
    {
        return implementation.secretbox_open(cipher, nonce, key);
    }

    public byte[] createNonce()
    {
        byte[] res = new byte[NONCE_BYTES];
        random.randombytes(res, 0, res.length);
        return res;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TweetNaClKey that = (TweetNaClKey) o;

        if (isDirty != that.isDirty) return false;
        return Arrays.equals(secretKey, that.secretKey);

    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(secretKey);
        result = 31 * result + (isDirty ? 1 : 0);
        return result;
    }

    public CborObject toCbor() {
        return  new CborObject.CborList(Arrays.asList(
                new CborObject.CborLong(type().value),
                new CborObject.CborByteArray(secretKey),
                new CborObject.CborBoolean(isDirty)));
    }

    public static TweetNaClKey fromCbor(Cborable cbor, Salsa20Poly1305 provider, SafeRandom random) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for PublicBoxingKey! " + cbor);
        CborObject.CborByteArray secretKey = (CborObject.CborByteArray) ((CborObject.CborList) cbor).value.get(1);
        CborObject.CborBoolean isDirty = (CborObject.CborBoolean) ((CborObject.CborList) cbor).value.get(2);
        return new TweetNaClKey(secretKey.value, isDirty.value, provider, random);
    }

    public static TweetNaClKey random(Salsa20Poly1305 provider, SafeRandom random)
    {
        byte[] key = new byte[KEY_BYTES];
        random.randombytes(key, 0, KEY_BYTES);
        return new TweetNaClKey(key, false, provider, random);
    }
}