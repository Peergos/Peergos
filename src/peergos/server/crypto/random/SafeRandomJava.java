package peergos.server.crypto.random;

import peergos.server.crypto.*;
import peergos.shared.crypto.random.*;

public class SafeRandomJava implements SafeRandom {

    @Override
    public void randombytes(byte[] b, int offset, int len) {
        TweetNaCl.randomBytes(b, offset, len);
    }
}
