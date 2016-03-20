package peergos.user.fs;

import peergos.crypto.*;
import peergos.crypto.symmetric.*;
import peergos.user.fs.erasure.*;
import peergos.util.*;

import java.util.*;
import java.util.stream.*;

public class EncryptedChunk {

    private final byte[] auth, cipher;

    public EncryptedChunk(byte[] encrypted) {
        this.auth = Arrays.copyOfRange(encrypted, 0, TweetNaCl.SECRETBOX_OVERHEAD_BYTES);
        this.cipher = Arrays.copyOfRange(encrypted, TweetNaCl.SECRETBOX_OVERHEAD_BYTES, encrypted.length);
    }

    public List<Fragment> generateFragments(int nOriginalFragments, int nAllowedFailures) {
        byte[][] bfrags = Erasure.split(this.cipher, nOriginalFragments, nAllowedFailures);
        List<Fragment> frags = new ArrayList<>();
        for (int i=0; i < bfrags.length; i++)
            frags.add(new Fragment(new ByteArrayWrapper(bfrags[i])));
	    return frags;
    }

    public byte[] getAuth() {
        return Arrays.copyOf(auth, auth.length);
    }

    public byte[] decrypt(SymmetricKey key, byte[] nonce) {
        return key.decrypt(ArrayOps.concat(this.auth, this.cipher), nonce);
    }

    public static final Set<Integer> ALLOWED_ORIGINAL = Stream.of(5, 10, 20, 40, 80).collect(Collectors.toSet());
    public static final Set<Integer> ALLOWED_FAILURES = Stream.of(5, 10, 20, 40, 80).collect(Collectors.toSet());
    public static final int ERASURE_ORIGINAL = 40; // mean 128 KiB fragments, could also use 80, 20, 10, 5
    public static final int ERASURE_ALLOWED_FAILURES = 10; // generates twice this extra fragments
}
