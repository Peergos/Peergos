package peergos.crypto.asymmetric.curve25519;

public class JSCurve25519 implements Curve25519 {

    @Override
    native public byte[] crypto_box_open(byte[] cipher, byte[] nonce, byte[] theirPublicBoxingKey, byte[] secretBoxingKey);

    @Override
    native public byte[] crypto_box(byte[] message, byte[] nonce, byte[] theirPublicBoxingKey, byte[] ourSecretBoxingKey);

    @Override
    native public void crypto_box_keypair(byte[] pk, byte[] sk);
}
