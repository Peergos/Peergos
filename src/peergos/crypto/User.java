package peergos.crypto;

import peergos.crypto.asymmetric.PublicBoxingKey;
import peergos.crypto.asymmetric.PublicSigningKey;
import peergos.crypto.asymmetric.SecretBoxingKey;
import peergos.crypto.asymmetric.SecretSigningKey;
import peergos.crypto.asymmetric.curve25519.*;
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
        long t1 = System.currentTimeMillis();
        try {
            return secretSigningKey.signMessage(message);
        } finally {
            System.out.println("Signing took " + (System.currentTimeMillis()-t1)+" mS");
        }
    }

    public byte[] decryptMessage(byte[] cipher, PublicBoxingKey theirPublicBoxingKey)
    {
        long t1 = System.currentTimeMillis();
        try {
            return secretBoxingKey.decryptMessage(cipher, theirPublicBoxingKey);
        } finally {
            System.out.println("Unboxing took " + (System.currentTimeMillis()-t1)+" mS");
        }
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

    public static User generateUserCredentials(String username, String password, LoginHasher hasher, Salsa20Poly1305 provider,
                                               SafeRandom random, Ed25519 signer, Curve25519 boxer) {
        return UserUtil.generateUser(username, password, hasher, provider, random, signer, boxer).getUser();
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
        return random(secretSignBytes, publicSignBytes, secretBoxBytes, publicBoxBytes, new JavaEd25519(), new JavaCurve25519(), new SafeRandom.Java());
    }
}
