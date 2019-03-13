package peergos.shared.user.fs;

/** A Fragment is a part of an EncryptedChunk which is stored directly in IPFS in raw format
 *
 */
public class Fragment {
    public static final int MAX_LENGTH = 1024*128;

    public final byte[] data;

    public Fragment(byte[] data) {
        if (MAX_LENGTH < data.length)
            throw new IllegalStateException("fragment size "+ data.length +" greater than max "+ MAX_LENGTH);
        this.data = data;
    }
}
