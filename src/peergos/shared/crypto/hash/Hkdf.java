package peergos.shared.crypto.hash;

import peergos.shared.util.*;

/** HKDF-SHA256 (RFC 5869). Synchronous everywhere, including in the browser, so key derivation doesn't
 *  force cryptree access to become asynchronous.
 */
public class Hkdf {
    private static final int BLOCK_SIZE = 64;
    private static final int HASH_LEN = 32;

    public static byte[] hmacSha256(byte[] key, byte[] message) {
        byte[] k = key.length > BLOCK_SIZE ? Hash.sha256(key) : key;
        byte[] ipad = new byte[BLOCK_SIZE];
        byte[] opad = new byte[BLOCK_SIZE];
        for (int i = 0; i < BLOCK_SIZE; i++) {
            byte b = i < k.length ? k[i] : 0;
            ipad[i] = (byte) (b ^ 0x36);
            opad[i] = (byte) (b ^ 0x5c);
        }
        byte[] inner = Hash.sha256(ArrayOps.concat(ipad, message));
        return Hash.sha256(ArrayOps.concat(opad, inner));
    }

    public static byte[] sha256(byte[] ikm, byte[] salt, byte[] info, int length) {
        if (length > 255 * HASH_LEN)
            throw new IllegalArgumentException("HKDF output too long: " + length);
        byte[] prk = hmacSha256(salt.length == 0 ? new byte[HASH_LEN] : salt, ikm);
        byte[] okm = new byte[length];
        byte[] t = new byte[0];
        int done = 0;
        for (int i = 1; done < length; i++) {
            t = hmacSha256(prk, ArrayOps.concat(ArrayOps.concat(t, info), new byte[]{(byte) i}));
            int n = Math.min(HASH_LEN, length - done);
            System.arraycopy(t, 0, okm, done, n);
            done += n;
        }
        return okm;
    }
}
