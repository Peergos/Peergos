package peergos.user.fs;

import peergos.util.ByteArrayWrapper;

public class Fragment {
    public static final int MAX_LENGTH = 1024*128;

    public final ByteArrayWrapper data;

    public Fragment(ByteArrayWrapper data) {
        if (MAX_LENGTH < data.data.length)
            throw new IllegalStateException("fragment size "+ data.data.length +" greater than max "+ MAX_LENGTH);
        this.data = data;

    }
}
