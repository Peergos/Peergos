package peergos.shared.crypto.hash;

import java.security.*;
import java.util.*;

public class ScryptJava implements LoginHasher {
    private static final int N = 1 << 17;

    @Override
    public byte[] hashToKeyBytes(String username, String password) {
        byte[] hash = Arrays.copyOfRange(Hash.sha256(password.getBytes()), 2, 34);
        byte[] salt = username.getBytes();
        long t1 = System.currentTimeMillis();
//            byte[] scryptHash = SCrypt.scrypt(hash, salt, N, 8, 1, 96);
        byte[] scryptHash = new byte[96];
        long t2 = System.currentTimeMillis();
        System.out.println("Scrypt hashing took: "+(t2-t1)+" mS");
        return scryptHash;
    }
}
