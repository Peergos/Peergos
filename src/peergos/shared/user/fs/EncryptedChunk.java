package peergos.shared.user.fs;

import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** An encrypted chunk is the ciphertext and auth for a Chunk of data up to 5 MiB in size
 *
 */
public class EncryptedChunk {
    private static final int AUTH_SIZE = TweetNaCl.SECRETBOX_OVERHEAD_BYTES;

    private final byte[] authAndCipherText;

    public EncryptedChunk(byte[] authAndCipherText) {
        this.authAndCipherText = authAndCipherText;
    }

    public List<Fragment> generateFragments(peergos.shared.user.fs.Fragmenter  fragmenter) {
        if (authAndCipherText.length == AUTH_SIZE)
            return Collections.emptyList();

        byte[][] bfrags = fragmenter.split(Arrays.copyOfRange(authAndCipherText, AUTH_SIZE, authAndCipherText.length));
        List<Fragment> frags = new ArrayList<>();
        for (int i=0; i < bfrags.length; i++)
            frags.add(new Fragment(bfrags[i]));
	    return frags;
    }

    public byte[] getAuth() {
        return Arrays.copyOf(authAndCipherText, AUTH_SIZE);
    }

    public CompletableFuture<byte[]> decrypt(SymmetricKey key, byte[] nonce) {
        if (authAndCipherText.length == AUTH_SIZE) {
            CompletableFuture<byte[]> res = new CompletableFuture<>();
            res.complete(new byte[0]);
            return res;
        }
        return key.decryptAsync(authAndCipherText, nonce);
    }

    public EncryptedChunk truncateTo(int length) {
        return new EncryptedChunk(Arrays.copyOfRange(authAndCipherText, 0, AUTH_SIZE + length));
    }
}
