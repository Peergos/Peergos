package peergos.user;

import com.lambdaworks.crypto.SCrypt;
import peergos.crypto.Hash;
import peergos.crypto.TweetNaCl;
import peergos.crypto.User;
import peergos.crypto.asymmetric.curve25519.Curve25519PublicKey;
import peergos.crypto.asymmetric.curve25519.Curve25519SecretKey;
import peergos.crypto.asymmetric.curve25519.Ed25519PublicKey;
import peergos.crypto.asymmetric.curve25519.Ed25519SecretKey;
import peergos.crypto.symmetric.SymmetricKey;
import peergos.crypto.symmetric.TweetNaClKey;
import peergos.user.UserContext;
import peergos.user.UserWithRoot;

import java.security.GeneralSecurityException;
import java.util.Arrays;

public class UserUtil {
    private static final int N = 1 << 17;
    private static byte[] generateKeys(String username, String password) {
        byte[] hash = Hash.sha256(password.getBytes());
        byte[] salt = username.getBytes();
        try {

            return SCrypt.scrypt(hash, salt, N, 8, 96, 1000);
        } catch (GeneralSecurityException gse) {
            throw new IllegalStateException(gse);
        }
    }

    public static UserWithRoot generateUser(String username, String password) {
        byte[] keyBytes = generateKeys(username, password);

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

        SymmetricKey root =  new TweetNaClKey(rootKeyBytes);

        return new UserWithRoot(user, root);
    }
}
