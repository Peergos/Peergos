package peergos.shared.crypto.hash;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Hash {
    public static final String HASH = "SHA-256";

    public static byte[] sha256(byte[] input)
    {
        try {
            MessageDigest md = MessageDigest.getInstance(HASH);
            md.update(input);
            return md.digest();
        } catch (NoSuchAlgorithmException e)
        {
            // This is only here to work around a bug in Doppio JVM
            Sha256 sha256 = new Sha256();
            sha256.update(input);
            byte[] hash = sha256.digest();
            return hash;
        }
    }

    public static byte[] sha256(String password)
    {
        try {
            return sha256(password.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e)
        {
            throw new IllegalStateException("couldn't hash password");
        }
    }


}
