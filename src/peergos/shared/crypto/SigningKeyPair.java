package peergos.shared.crypto;

import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.crypto.asymmetric.SecretSigningKey;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.random.*;
import peergos.shared.util.ArrayOps;

import java.io.*;
import java.util.Random;

public class SigningKeyPair extends UserPublicKey
{
    public final SecretSigningKey secretSigningKey;

    public SigningKeyPair(SecretSigningKey secretSigningKey, PublicSigningKey publicSigningKey)
    {
        super(publicSigningKey);
        this.secretSigningKey = secretSigningKey;
    }

    public byte[] getSecretSigningKey() {
        return secretSigningKey.getSecretSigningKey();
    }

    public byte[] signMessage(byte[] message)
    {
        return secretSigningKey.signMessage(message);
    }

    public static UserPublicKey deserialize(DataInput din) {
        try {
            boolean hasPrivateKeys = din.readBoolean();
            if (hasPrivateKeys) {
                SecretSigningKey signingKey = SecretSigningKey.deserialize(din);
                UserPublicKey pub = UserPublicKey.deserialize(din);
                return new SigningKeyPair(signingKey, pub.publicSigningKey);
            }
            return UserPublicKey.deserialize(din);
        } catch (IOException e) {
            throw new IllegalStateException("Invalid serialized User", e);
        }
    }

    public void serialize(DataOutput dout) throws IOException {
        dout.write(serialize());
    }

    public byte[] serialize() {
        return ArrayOps.concat(secretSigningKey.serialize(), super.serialize());
    }

    public static SigningKeyPair random(SafeRandom random, Ed25519 signer) {

        byte[] secretSignBytes = new byte[64];
        byte[] publicSignBytes = new byte[32];

        random.randombytes(secretSignBytes, 0, 32);

        return random(secretSignBytes, publicSignBytes, signer);
    }

    private static SigningKeyPair random(byte[] secretSignBytes, byte[] publicSignBytes,
                                         Ed25519 signer) {
        signer.crypto_sign_keypair(publicSignBytes, secretSignBytes);

        return new SigningKeyPair(
                new Ed25519SecretKey(secretSignBytes, signer),
                new Ed25519PublicKey(publicSignBytes, signer));
    }

    public static SigningKeyPair insecureRandom() {

        byte[] secretSignBytes = new byte[64];
        byte[] publicSignBytes = new byte[32];

        Random rnd = new Random();
        rnd.nextBytes(secretSignBytes);
        rnd.nextBytes(publicSignBytes);
        return random(secretSignBytes, publicSignBytes, new Ed25519.Java());
    }
}
