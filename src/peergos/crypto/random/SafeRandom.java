package peergos.crypto.random;

import peergos.crypto.*;

public interface SafeRandom {

    void randombytes(byte[] b, int offset, int len);

    class Java implements SafeRandom {

        @Override
        public void randombytes(byte[] b, int offset, int len) {
            TweetNaCl.randomBytes(b, offset, len);
        }
    }
}
