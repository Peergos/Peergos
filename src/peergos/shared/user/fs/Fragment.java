package peergos.shared.user.fs;

/** A Fragment is a part of an EncryptedChunk which is stored directly in IPFS in a raw format block
 *
 */
public class Fragment {
    public static final int MAX_LENGTH = 512*1024; // max size allowed by bitswap protocol is 1 MiB

    public final byte[] data;

    public Fragment(byte[] data) {
        if (MAX_LENGTH < data.length)
            throw new IllegalStateException("fragment size "+ data.length +" greater than max "+ MAX_LENGTH);
        this.data = data;
    }
}
