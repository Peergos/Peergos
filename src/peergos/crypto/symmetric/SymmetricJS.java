package peergos.crypto.symmetric;

public class SymmetricJS implements Salsa20Poly1305 {

    @Override
    native public byte[] secretbox(byte[] data, byte[] nonce, byte[] key);

    @Override
    native public byte[] secretbox_open(byte[] cipher, byte[] nonce, byte[] key);
}
