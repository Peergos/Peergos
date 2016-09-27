package peergos.shared.crypto.hash;

import java.security.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import peergos.shared.scrypt.com.lambdaworks.crypto.SCrypt;

public class ScryptJava implements LoginHasher {
    private static final int N = 1 << 17;

    @Override
    public CompletableFuture<byte[]> hashToKeyBytes(String username, String password) {
        CompletableFuture<byte[]> res = new CompletableFuture<>();
        byte[] hash = Arrays.copyOfRange(Hash.sha256(password.getBytes()), 2, 34);
        byte[] salt = username.getBytes();
        try {
            long t1 = System.currentTimeMillis();
            byte[] scryptHash = SCrypt.scrypt(hash, salt, N, 8, 1, 96);
            long t2 = System.currentTimeMillis();
            System.out.println("Scrypt hashing took: "+(t2-t1)+" mS");
            res.complete(scryptHash);
            return res;
        } catch (GeneralSecurityException gse) {
            res.completeExceptionally(gse);
        }
        return res;
    }
}
