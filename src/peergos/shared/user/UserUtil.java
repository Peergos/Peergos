package peergos.shared.user;

import peergos.shared.Crypto;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.asymmetric.mlkem.Mlkem;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.util.*;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

public class UserUtil {

    public static CompletableFuture<UserWithRoot> generateUser(String username,
                                                               String password,
                                                               Crypto crypto,
                                                               SecretGenerationAlgorithm algorithm) {
        if (password.equals(username))
            return Futures.errored(new IllegalStateException("Your password cannot be the same as your username!"));
        CompletableFuture<byte[]> fut = crypto.hasher.hashToKeyBytes(username + algorithm.getExtraSalt(), password, algorithm);
        return fut.thenCompose(keyBytes -> {
            byte[] signBytesSeed = Arrays.copyOfRange(keyBytes, 0, 32);
            boolean hasBoxer = algorithm.generateBoxerAndIdentity();
            byte[] secretBoxBytes = hasBoxer ? Arrays.copyOfRange(keyBytes, 32, 64) : crypto.random.randomBytes(32);

            byte[] rootKeyBytes = Arrays.copyOfRange(keyBytes, hasBoxer ? 64 : 32, hasBoxer ? 96 : 64);
	
            byte[] secretSignBytes = Arrays.copyOf(signBytesSeed, 64);
            byte[] publicSignBytes = new byte[32];
	
            crypto.signer.crypto_sign_keypair(publicSignBytes, secretSignBytes);
	
            byte[] publicBoxBytes = new byte[32];
            crypto.boxer.crypto_box_keypair(publicBoxBytes, secretBoxBytes);
	
            SigningKeyPair signingKeyPair = new SigningKeyPair(new Ed25519PublicKey(publicSignBytes, crypto.signer), new Ed25519SecretKey(secretSignBytes, crypto.signer));

            return (hasBoxer ?
                    Futures.of(new BoxingKeyPair(new Curve25519PublicKey(publicBoxBytes, crypto.boxer, crypto.random), new Curve25519SecretKey(secretBoxBytes, crypto.boxer))) :
                    BoxingKeyPair.randomHybrid(crypto)).thenApply(boxingKeyPair -> {
                SymmetricKey root = new TweetNaClKey(rootKeyBytes, false, crypto.symmetricProvider, crypto.random);
                return new UserWithRoot(signingKeyPair, boxingKeyPair, root);
            });
        });
    }
}
