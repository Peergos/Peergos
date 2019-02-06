package peergos.shared.crypto.hash;

import peergos.shared.user.fs.*;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.*;

public class Hash {
    public static final String HASH = "SHA-256";

    public static byte[] sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance(HASH);
            md.update(input);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            // This is only here to work around a bug in Doppio JVM
            Sha256 sha256 = new Sha256();
            sha256.update(input);
            byte[] hash = sha256.digest();
            return hash;
        }
    }

    public static CompletableFuture<byte[]> sha256(AsyncReader input, long length) {
        try {
            MessageDigest md = MessageDigest.getInstance(HASH);
            return sha256(input, length, md, new byte[Chunk.MAX_SIZE]);
        } catch (NoSuchAlgorithmException e) {
            // This is only here to work around a bug in Doppio JVM
            Sha256 sha256 = new Sha256();
            return sha256(input, length, sha256, new byte[Chunk.MAX_SIZE]);
        }
    }

    private static CompletableFuture<byte[]> sha256(AsyncReader input, long length, MessageDigest md, byte[] buf) {
        if (length == 0)
            return CompletableFuture.completedFuture(md.digest());
        return input.readIntoArray(buf, 0, Math.min(buf.length, (int)length & 0xFFFFFFF))
                .thenCompose(read -> {
                    md.update(buf, 0, read);
                    return sha256(input, length - read, md, buf);
                });
    }

    private static CompletableFuture<byte[]> sha256(AsyncReader input, long length, Sha256 md, byte[] buf) {
        if (length == 0)
            return CompletableFuture.completedFuture(md.digest());
        return input.readIntoArray(buf, 0, Math.min(buf.length, (int)length & 0xFFFFFFF))
                .thenCompose(read -> {
                    md.update(buf, 0, read);
                    return sha256(input, length - read, md, buf);
                });
    }

    public static byte[] sha256(String password) {
        try {
            return sha256(password.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("couldn't hash password");
        }
    }


}
