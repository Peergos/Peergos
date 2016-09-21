package peergos.shared.user.fs;

public class Fragment {
    public static final int MAX_LENGTH = 1024*128;

    public final byte[] data;

    public Fragment(byte[] data) {
        if (MAX_LENGTH < data.length)
            throw new IllegalStateException("fragment size "+ data.length +" greater than max "+ MAX_LENGTH);
        this.data = data;

    }
}
