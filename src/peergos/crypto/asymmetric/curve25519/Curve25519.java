package peergos.crypto.asymmetric.curve25519;

public interface Curve25519 {

    byte[] crypto_box_open(byte[] cipher, byte[] nonce, byte[] theirPublicBoxingKey, byte[] secretBoxingKey);

    byte[] crypto_box(byte[] message, byte[] nonce, byte[] theirPublicBoxingKey, byte[] ourSecretBoxingKey);

    void crypto_box_keypair(byte[] pk, byte[] sk);
}
