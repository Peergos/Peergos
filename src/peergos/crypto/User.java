package peergos.crypto;

import peergos.crypto.asymmetric.PublicBoxingKey;
import peergos.crypto.asymmetric.PublicSigningKey;
import peergos.crypto.asymmetric.SecretBoxingKey;
import peergos.crypto.asymmetric.SecretSigningKey;
import peergos.crypto.asymmetric.curve25519.Curve25519PublicKey;
import peergos.crypto.asymmetric.curve25519.Curve25519SecretKey;
import peergos.crypto.asymmetric.curve25519.Ed25519PublicKey;
import peergos.crypto.asymmetric.curve25519.Ed25519SecretKey;
import peergos.crypto.hash.*;
import peergos.crypto.random.*;
import peergos.crypto.symmetric.*;
import peergos.user.UserUtil;
import peergos.util.ArrayOps;

import java.io.*;
import java.util.Arrays;
import java.util.Random;

public class User extends UserPublicKey
{
    public final SecretSigningKey secretSigningKey;
    public final SecretBoxingKey secretBoxingKey;

    public User(SecretSigningKey secretSigningKey, SecretBoxingKey secretBoxingKey, PublicSigningKey publicSigningKey, PublicBoxingKey publicBoxingKey)
    {
        super(publicSigningKey, publicBoxingKey);
        this.secretSigningKey = secretSigningKey;
        this.secretBoxingKey = secretBoxingKey;
    }

    public byte[] getSecretSigningKey() {
        return secretSigningKey.getSecretSigningKey();
    }

    public byte[] getSecretBoxingKey() {
        return secretBoxingKey.getSecretBoxingKey();
    }

    public byte[] signMessage(byte[] message)
    {
        return secretSigningKey.signMessage(message);
    }

    public byte[] decryptMessage(byte[] cipher, PublicBoxingKey theirPublicBoxingKey)
    {
        return secretBoxingKey.decryptMessage(cipher, theirPublicBoxingKey);
    }

    public static UserPublicKey deserialize(DataInput din) {
        try {
            boolean hasPrivateKeys = din.readBoolean();
            if (hasPrivateKeys) {
                SecretSigningKey signingKey = SecretSigningKey.deserialize(din);
                SecretBoxingKey boxingKey = SecretBoxingKey.deserialize(din);
                UserPublicKey pub = UserPublicKey.deserialize(din);
                return new User(signingKey, boxingKey, pub.publicSigningKey, pub.publicBoxingKey);
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
        return ArrayOps.concat(secretSigningKey.serialize(), secretBoxingKey.serialize(), super.serialize());
    }

    public static User generateUserCredentials(String username, String password, LoginHasher hasher, Salsa20Poly1305 provider, SafeRandom random) {
        return UserUtil.generateUser(username, password, hasher, provider, random).getUser();
    }


    public static User random(SafeRandom random) {

        byte[] secretSignBytes = new byte[64];
        byte[] publicSignBytes = new byte[32];
        byte[] secretBoxBytes = new byte[32];
        byte[] publicBoxBytes = new byte[32];

        random.randombytes(secretBoxBytes, 0, 32);
        random.randombytes(secretSignBytes, 0, 32);

        boolean isSeeded = true;
        return random(secretSignBytes, publicSignBytes, secretBoxBytes, publicBoxBytes, isSeeded);
    }

    private static User random(byte[] secretSignBytes, byte[] publicSignBytes, byte[] secretBoxBytes, byte[] publicBoxBytes, boolean isSeeded) {
        TweetNaCl.crypto_sign_keypair(publicSignBytes, secretSignBytes, isSeeded);
        TweetNaCl.crypto_box_keypair(publicBoxBytes, secretBoxBytes, isSeeded);

        return new User(
                new Ed25519SecretKey(secretSignBytes),
                new Curve25519SecretKey(secretBoxBytes),
                new Ed25519PublicKey(publicSignBytes),
                new Curve25519PublicKey(publicBoxBytes));
    }

    public static User insecureRandom() {

        byte[] secretSignBytes = new byte[64];
        byte[] publicSignBytes = new byte[32];
        byte[] secretBoxBytes = new byte[32];
        byte[] publicBoxBytes = new byte[32];

        Random rnd = new Random();
        rnd.nextBytes(secretSignBytes);
        rnd.nextBytes(publicSignBytes);
        rnd.nextBytes(secretBoxBytes);
        rnd.nextBytes(publicBoxBytes);
        boolean isSeeded = true;
        return random(secretSignBytes, publicSignBytes, secretBoxBytes, publicBoxBytes, isSeeded);
    }
}
