package peergos.crypto;

import peergos.util.ArrayOps;

import java.util.Arrays;
import java.util.Random;

public class User extends UserPublicKey
{
    public final byte[] secretSigningKey, secretBoxingKey;

    public User(byte[] secretSigningKey, byte[] secretBoxingKey, byte[] publicSigningKey, byte[] publicBoxingKey)
    {
        super(publicSigningKey, publicBoxingKey);
        this.secretSigningKey = secretSigningKey;
        this.secretBoxingKey = secretBoxingKey;
    }

    public User(byte[] secretSigningKey, byte[] secretBoxingKey)
    {
        super(getPublicSigningKey(secretSigningKey), getPublicBoxingKey(secretBoxingKey));
        this.secretSigningKey = secretSigningKey;
        this.secretBoxingKey = secretBoxingKey;
    }

    public static byte[] getPublicSigningKey(byte[] secretSigningKey) {
        return Arrays.copyOfRange(secretSigningKey, 32, 64);
    }

    public static byte[] getPublicBoxingKey(byte[] secretBoxingKey) {
        byte[] pub = new byte[32];
        TweetNaCl.crypto_scalarmult_base(pub, secretBoxingKey);
        return pub;
    }

    public byte[] signMessage(byte[] message)
    {
        return TweetNaCl.crypto_sign(message, secretSigningKey);
    }

    public byte[] decryptMessage(byte[] cipher, byte[] theirPublicBoxingKey)
    {
        byte[] nonce = Arrays.copyOfRange(cipher, cipher.length - TweetNaCl.BOX_NONCE_BYTES, cipher.length);
        cipher = Arrays.copyOfRange(cipher, 0, cipher.length - TweetNaCl.BOX_NONCE_BYTES);
        return TweetNaCl.crypto_box_open(cipher, nonce, theirPublicBoxingKey, secretBoxingKey);
    }

    public static User deserialize(byte[] input) {
        return new User(Arrays.copyOfRange(input, 0, 64), Arrays.copyOfRange(input, 64, 96));
    }

    public byte[] getPrivateKeys() {
        return ArrayOps.concat(secretSigningKey, secretBoxingKey);
    }

    public static User generateUserCredentials(String username, String password)
    {
        // need usernames and public keys to be in 1-1 correspondence, and the private key to be derivable from the username+password
        // username is salt against rainbow table attacks
        byte[] hash = hash(username+password);
        byte[] publicSigningKey = new byte[32];
        byte[] secretSigningKey = new byte[64];
        Random r = new Random(Arrays.hashCode(hash));
        r.nextBytes(secretSigningKey); // only 32 are used
        TweetNaCl.crypto_sign_keypair(publicSigningKey, secretSigningKey, true);
        byte[] publicBoxingKey = new byte[32];
        byte[] secretBoxingKey = new byte[32];
        System.arraycopy(secretSigningKey, 32, secretBoxingKey, 0, 32);
        TweetNaCl.crypto_box_keypair(publicBoxingKey, secretBoxingKey, true);
        return new User(secretSigningKey, secretBoxingKey, publicSigningKey, publicBoxingKey);
    }

    public static User random() {
        byte[] tmp = new byte[8];
        Random r = new Random();
        r.nextBytes(tmp);
        return generateUserCredentials("username", ArrayOps.bytesToHex(tmp));
    }

    public static void main(String[] args) {
        User user = User.generateUserCredentials("Username", "password");
        System.out.println("PublicKey: " + ArrayOps.bytesToHex(user.getPublicKeys()));
        byte[] message = "G'day mate!".getBytes();
        byte[] cipher = user.encryptMessageFor(message, user.secretBoxingKey);
        System.out.println("Cipher: "+ArrayOps.bytesToHex(cipher));
        byte[] clear = user.decryptMessage(cipher, user.getPublicBoxingKey());
        assert (Arrays.equals(message, clear));

        byte[] signed = user.signMessage(message);
        assert (Arrays.equals(user.unsignMessage(signed), message));
    }
}
