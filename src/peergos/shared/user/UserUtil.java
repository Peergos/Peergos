package peergos.shared.user;

import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

public class UserUtil {

    public static CompletableFuture<UserWithRoot> generateUser(String username,
                                                               String password,
                                                               LoginHasher hasher,
                                                               Salsa20Poly1305 provider,
                                                               SafeRandom random,
                                                               Ed25519 signer,
                                                               Curve25519 boxer,
                                                               SecretGenerationAlgorithm algorithm) {
        CompletableFuture<byte[]> fut = hasher.hashToKeyBytes(username, password, algorithm);
        return fut.thenApply(keyBytes -> {
            byte[] signBytesSeed = Arrays.copyOfRange(keyBytes, 0, 32);
            byte[] secretBoxBytes = Arrays.copyOfRange(keyBytes, 32, 64);
            byte[] rootKeyBytes = Arrays.copyOfRange(keyBytes, 64, 96);
	
            byte[] secretSignBytes = Arrays.copyOf(signBytesSeed, 64);
            byte[] publicSignBytes = new byte[32];
	
            signer.crypto_sign_keypair(publicSignBytes, secretSignBytes);
	
            byte[] pubilcBoxBytes = new byte[32];
            boxer.crypto_box_keypair(pubilcBoxBytes, secretBoxBytes);
	
            SigningKeyPair signingKeyPair = new SigningKeyPair(new Ed25519PublicKey(publicSignBytes, signer), new Ed25519SecretKey(secretSignBytes, signer));

            BoxingKeyPair boxingKeyPair = new BoxingKeyPair(new Curve25519PublicKey(pubilcBoxBytes, boxer, random), new Curve25519SecretKey(secretBoxBytes, boxer));

            SymmetricKey root =  new TweetNaClKey(rootKeyBytes, false, provider, random);

            return new UserWithRoot(signingKeyPair, boxingKeyPair, root);
        });
    }
}
