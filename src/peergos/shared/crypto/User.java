package peergos.shared.crypto;

import peergos.shared.crypto.asymmetric.PublicBoxingKey;
import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.crypto.asymmetric.SecretBoxingKey;
import peergos.shared.crypto.asymmetric.SecretSigningKey;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.UserUtil;
import peergos.shared.util.ArrayOps;

import java.io.*;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

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

    public static CompletableFuture<User> generateUserCredentials(String username, String password, LoginHasher hasher, Salsa20Poly1305 provider,
                                               SafeRandom random, Ed25519 signer, Curve25519 boxer) {
        return UserUtil.generateUser(username, password, hasher, provider, random, signer, boxer).thenApply(user -> {
        	return user.getUser();
        });
    }


    public static User random(SafeRandom random, Ed25519 signer, Curve25519 boxer) {

        byte[] secretSignBytes = new byte[64];
        byte[] publicSignBytes = new byte[32];
        byte[] secretBoxBytes = new byte[32];
        byte[] publicBoxBytes = new byte[32];

        random.randombytes(secretBoxBytes, 0, 32);
        random.randombytes(secretSignBytes, 0, 32);

        return random(secretSignBytes, publicSignBytes, secretBoxBytes, publicBoxBytes, signer, boxer, random);
    }

    private static User random(byte[] secretSignBytes, byte[] publicSignBytes, byte[] secretBoxBytes, byte[] publicBoxBytes,
                               Ed25519 signer, Curve25519 boxer, SafeRandom random) {
        signer.crypto_sign_keypair(publicSignBytes, secretSignBytes);
        boxer.crypto_box_keypair(publicBoxBytes, secretBoxBytes);

        return new User(
                new Ed25519SecretKey(secretSignBytes, signer),
                new Curve25519SecretKey(secretBoxBytes, boxer),
                new Ed25519PublicKey(publicSignBytes, signer),
                new Curve25519PublicKey(publicBoxBytes, boxer, random));
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
        return random(secretSignBytes, publicSignBytes, secretBoxBytes, publicBoxBytes, new Ed25519.Java(), new Curve25519.Java(), new SafeRandom.Java());
    }
}
