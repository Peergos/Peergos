package peergos.shared.crypto.hash;

import java.security.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import peergos.shared.scrypt.com.lambdaworks.crypto.SCrypt;
import peergos.shared.user.*;

public class ScryptJava implements LoginHasher {
    private static final int LOG_2_MIN_RAM = 17;

    @Override
    public CompletableFuture<byte[]> hashToKeyBytes(String username, String password, UserGenerationAlgorithm algorithm) {
        CompletableFuture<byte[]> res = new CompletableFuture<>();
        if (algorithm.getType() == UserGenerationAlgorithm.Type.ScryptEd25519Curve25519) {
            byte[] hash = Hash.sha256(password.getBytes());
            byte[] salt = username.getBytes();
            try {
                ScryptEd25519Curve25519 params = (ScryptEd25519Curve25519) algorithm;
                long t1 = System.currentTimeMillis();
                int parallelism = params.parallelism;
                int nOutputBytes = params.outputBytes;
                int cpuCost = params.cpuCost;
                int memoryCost = 1 << params.memoryCost; // Amount of ram required to run algorithm in bytes
                byte[] scryptHash = SCrypt.scrypt(hash, salt, memoryCost, cpuCost, parallelism, nOutputBytes);
                long t2 = System.currentTimeMillis();
                System.out.println("Scrypt hashing took: " + (t2 - t1) + " mS");
                res.complete(scryptHash);
                return res;
            } catch (GeneralSecurityException gse) {
                res.completeExceptionally(gse);
            }
            return res;
        }
        throw new IllegalStateException("Unknown user generation algorithm: " + algorithm);
    }
}
