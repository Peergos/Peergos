package peergos.crypto;

import peergos.util.ArrayOps;

import java.io.*;
import java.security.*;
import java.util.Arrays;
import java.util.Random;

public class UserPublicKey implements Comparable<UserPublicKey>
{
    public static final String HASH = "SHA-256";
    public static final int HASH_BYTES = 32;
    public static final int SIZE = 64;
    public static final int PADDING_LENGTH = 32;
    private static final Random rnd = new Random();

    public final byte[] publicSigningKey, publicBoxingKey;

    public UserPublicKey(byte[] publicSigningKey, byte[] publicBoxingKey)
    {
        this.publicSigningKey = publicSigningKey;
        this.publicBoxingKey = publicBoxingKey;
    }

    public UserPublicKey(byte[] keys) {
        this(Arrays.copyOfRange(keys, 0, 32), Arrays.copyOfRange(keys, 32, 64));
    }

    public byte[] getPublicKeys()
    {
        return ArrayOps.concat(publicSigningKey, publicBoxingKey);
    }

    public byte[] encryptMessageFor(byte[] input, byte[] ourSecretBoxingKey)
    {
        byte[] cipherText = new byte[PADDING_LENGTH + input.length];
        byte[] paddedMessage = new byte[PADDING_LENGTH + input.length];
        System.arraycopy(input, 0, paddedMessage, PADDING_LENGTH, input.length);
        byte[] nonce = createNonce();
        TweetNaCl.crypto_box(cipherText, paddedMessage, paddedMessage.length, nonce, publicBoxingKey, ourSecretBoxingKey);
        return ArrayOps.concat(Arrays.copyOfRange(cipherText, 16, cipherText.length), nonce);
    }

    public byte[] createNonce() {
        byte[] nonce = new byte[TweetNaCl.BOX_NONCE_BYTES];
        rnd.nextBytes(nonce);
        return nonce;
    }

    public byte[] unsignMessage(byte[] signed)
    {
        byte[] message = new byte[signed.length];
        TweetNaCl.crypto_sign_open(message, signed, signed.length, publicSigningKey);
        return Arrays.copyOfRange(message, 64, message.length);
    }

    public boolean equals(Object o)
    {
        if (! (o instanceof UserPublicKey))
            return false;

        return Arrays.equals(publicBoxingKey, ((UserPublicKey) o).publicBoxingKey) && Arrays.equals(publicSigningKey, ((UserPublicKey) o).publicSigningKey);
    }

    public int hashCode()
    {
        return Arrays.hashCode(publicBoxingKey) ^ Arrays.hashCode(publicSigningKey);
    }

    public boolean isValidSignature(byte[] signed, byte[] raw)
    {
        return Arrays.equals(unsignMessage(signed), raw);
    }

    public static byte[] hash(String password)
    {
        try {
            return hash(password.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e)
        {
            throw new IllegalStateException("couldn't hash password");
        }
    }

    public static byte[] hash(byte[] input)
    {
        try {
            MessageDigest md = MessageDigest.getInstance(HASH);
            md.update(input);
            return md.digest();
        } catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("couldn't hash password");
        }
    }

    @Override
    public int compareTo(UserPublicKey userPublicKey) {
        int signing = ArrayOps.compare(publicSigningKey, userPublicKey.publicSigningKey);
        if (signing != 0)
            return signing;
        return ArrayOps.compare(publicBoxingKey, userPublicKey.publicBoxingKey);
    }
}
