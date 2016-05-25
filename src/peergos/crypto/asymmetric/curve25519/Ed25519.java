package peergos.crypto.asymmetric.curve25519;

public interface Ed25519 {

    byte[] crypto_sign_open(byte[] signed, byte[] publicSigningKey);

    byte[] crypto_sign(byte[] message, byte[] secretSigningKey);

    void crypto_sign_keypair(byte[] pk, byte[] sk);
}
