package peergos.user;

import peergos.crypto.TweetNaCl;
import peergos.crypto.User;
import peergos.crypto.asymmetric.curve25519.Curve25519PublicKey;
import peergos.crypto.asymmetric.curve25519.Curve25519SecretKey;
import peergos.crypto.asymmetric.curve25519.Ed25519PublicKey;
import peergos.crypto.asymmetric.curve25519.Ed25519SecretKey;
import peergos.crypto.hash.*;
import peergos.crypto.symmetric.*;

import java.util.Arrays;

public class UserUtil {

    public static UserWithRoot generateUser(String username, String password, LoginHasher hasher, Salsa20Poly1305 provider) {
        byte[] keyBytes = hasher.hashToKeyBytes(username, password);

        byte[] signBytesSeed = Arrays.copyOfRange(keyBytes, 0, 32);
        byte[] secretBoxBytes = Arrays.copyOfRange(keyBytes, 32, 64);
        byte[] rootKeyBytes = Arrays.copyOfRange(keyBytes, 64, 96);

        byte[] secretSignBytes = Arrays.copyOf(signBytesSeed, 64);
        byte[] publicSignBytes = new byte[32];

        boolean isSeeded = true;
        TweetNaCl.crypto_sign_keypair(publicSignBytes, secretSignBytes, isSeeded);

        byte[] pubilcBoxBytes = new byte[32];
        TweetNaCl.crypto_box_keypair(pubilcBoxBytes, secretBoxBytes, isSeeded);

        User user = new User(
                new Ed25519SecretKey(secretSignBytes),
                new Curve25519SecretKey(secretBoxBytes),
                new Ed25519PublicKey(publicSignBytes),
                new Curve25519PublicKey(pubilcBoxBytes));

        SymmetricKey root =  new TweetNaClKey(rootKeyBytes, provider);

        return new UserWithRoot(user, root);
    }
}
