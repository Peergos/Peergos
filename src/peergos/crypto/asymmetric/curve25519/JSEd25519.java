package peergos.crypto.asymmetric.curve25519;

public class JSEd25519 implements Ed25519 {

    @Override
    native public byte[] crypto_sign_open(byte[] signed, byte[] publicSigningKey);

    @Override
    native public byte[] crypto_sign(byte[] message, byte[] secretSigningKey);
}
