package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.util.*;

public class ProofOfWorkTests {
    private static final Crypto crypto = Main.initCrypto();

    @Test
    public void validity() {
        byte[] data = crypto.random.randomBytes(100);
        // d<=21 takes < 1s, 22-24 take ~12s, 25 takes ~ 85s (all with native sha256, and single threaded)
        // so probably 11 should be the default
        for (int d=0; d < 25; d++) {
            long t0 = System.currentTimeMillis();
            ProofOfWork work = crypto.hasher.generateProofOfWork(d, data).join();
            long t1 = System.currentTimeMillis();
            System.out.println("Difficulty: " + d + " took " + (t1 - t0) + "ms");
            byte[] hash = crypto.hasher.sha256(ArrayOps.concat(work.prefix, data)).join();
            Assert.assertTrue(ProofOfWork.satisfiesDifficulty(d, hash));
        }
    }

    @Test
    public void infiniteDifficulty() {
        DifficultyGenerator rateLimiter = new DifficultyGenerator(System.currentTimeMillis(), 0);
        int diff = rateLimiter.currentDifficulty();
        Assert.assertTrue(diff == ProofOfWork.MAX_DIFFICULTY);
    }
}
