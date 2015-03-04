package peergos.crypto;

import jdk.nashorn.api.scripting.ScriptObjectMirror;
import jdk.nashorn.api.scripting.ScriptUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import peergos.util.ArrayOps;
import peergos.util.ByteArrayWrapper;
import peergos.util.Serialize;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.*;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class UserPublicKey implements Comparable<UserPublicKey>
{
    public static final int RSA_KEY_BITS = 1024;//4096;
    public static final int HASH_BYTES = 256/8;
    public static final String ECC_CURVE = "secp384r1";
    public static final String KEYS = "ECDSA";
    public static final String AUTH = "ECIES";
    public static final String HASH = "SHA-256";
    public static final String SECURE_RANDOM = "SHA1PRNG"; // TODO: need to figure out an implementation using HMAC-SHA-256

    private final PublicKey publicKey;

    public UserPublicKey(PublicKey pub)
    {
        this.publicKey = pub;
    }

    public UserPublicKey(byte[] encodedPublicKey)
    {
        publicKey = deserializePublic(encodedPublicKey);
    }

    public byte[] getPublicKey()
    {
        return publicKey.getEncoded();
    }

    public byte[] encryptMessageFor(byte[] input)
    {
        try {
            Cipher c = Cipher.getInstance(AUTH, "BC");
            c.init(Cipher.ENCRYPT_MODE, publicKey);
            SymmetricKey sym = SymmetricKey.random();
            byte[] rawSym = sym.getKey().getEncoded();
            byte[] iv = SymmetricKey.randomIV();
            c.update(rawSym);
            byte[] encryptedSym = c.doFinal();
            byte[] content = sym.encrypt(input, iv);
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutput dout = new DataOutputStream(bout);
            Serialize.serialize(encryptedSym, dout);
            Serialize.serialize(iv, dout);
            Serialize.serialize(content, dout);
            return bout.toByteArray();
        } catch (NoSuchAlgorithmException|NoSuchProviderException e)
        {
            e.printStackTrace();
            return null;
        } catch (NoSuchPaddingException e)
        {
            e.printStackTrace();
            return null;
        } catch (InvalidKeyException e)
        {
            e.printStackTrace();
            return null;
        } catch (IllegalBlockSizeException e)
        {
            e.printStackTrace();
            return null;
        } catch (BadPaddingException e)
        {
            e.printStackTrace();
            return null;
        } catch (IOException e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public byte[] unsignMessage(byte[] input)
    {
        try {
            Cipher c = Cipher.getInstance(AUTH, "BC");
            c.init(Cipher.DECRYPT_MODE, publicKey);
            return c.doFinal(input);
        } catch (NoSuchAlgorithmException|NoSuchProviderException e)
        {
            e.printStackTrace();
            return null;
        } catch (NoSuchPaddingException e)
        {
            e.printStackTrace();
            return null;
        } catch (InvalidKeyException e)
        {
            e.printStackTrace();
            return null;
        } catch (IllegalBlockSizeException e)
        {
            e.printStackTrace();
            return null;
        } catch (BadPaddingException e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public boolean equals(Object o)
    {
        if (! (o instanceof UserPublicKey))
            return false;

        return this.publicKey.equals(((UserPublicKey) o).publicKey);
    }

    public int hashCode()
    {
        return publicKey.hashCode();
    }

    public PublicKey getKey()
    {
        return (PublicKey) publicKey;
    }

    public boolean isValidSignature(byte[] signedHash, byte[] raw)
    {
        try
        {
            byte[] a = hash(raw);
            byte[] b = unsignMessage(signedHash);
            return java.util.Arrays.equals(a,b);
        } catch (Exception e) {
            return false;
        }
    }

    static
    {
        Security.addProvider(new BouncyCastleProvider());
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

    public static PublicKey deserializePublic(byte[] pub)
    {
        try {
            return KeyFactory.getInstance(KEYS, "BC").generatePublic(new X509EncodedKeySpec(pub));
        } catch (NoSuchAlgorithmException|NoSuchProviderException|InvalidKeySpecException e)
        {
            throw new IllegalStateException("Couldn't create public key");
        }
    }

    @Override
    public int compareTo(UserPublicKey userPublicKey) {
        return ArrayOps.compare(publicKey.getEncoded(), userPublicKey.publicKey.getEncoded());
    }
}
