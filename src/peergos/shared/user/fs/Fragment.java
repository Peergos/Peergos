package peergos.shared.user.fs;

import peergos.shared.storage.auth.*;

/** A Fragment is a part of an EncryptedChunk which is stored directly in IPFS in a raw format block
 *
 */
public class Fragment {
    // max message size allowed by bitswap protocol is 2 MiB, and the block must fit within that
    public static final int MAX_LENGTH = 1024*1024;
    public static final int MAX_LENGTH_WITH_BAT_PREFIX = MAX_LENGTH + Bat.MAX_RAW_BLOCK_PREFIX_SIZE;

    public final byte[] data;

    public Fragment(byte[] data) {
        if (MAX_LENGTH_WITH_BAT_PREFIX < data.length)
            throw new IllegalStateException("fragment size "+ data.length +" greater than max "+ MAX_LENGTH);
        this.data = data;
    }
}
