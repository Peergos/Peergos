package peergos.crypto.random;

public class JSRandom implements SafeRandom {

    @Override
    native public void randombytes(byte[] b, int offset, int len);
}
