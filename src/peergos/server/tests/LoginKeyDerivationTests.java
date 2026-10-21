package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.shared.*;
import peergos.shared.user.*;

import java.util.concurrent.*;

public class LoginKeyDerivationTests {
    private static final Crypto crypto = Main.initCrypto();

    private static void assertRejected(SecretGenerationAlgorithm alg) {
        try {
            UserUtil.generateUser("alice", "a long enough password", crypto, alg).join();
            Assert.fail("Derived login keys with " + alg.toCbor());
        } catch (CompletionException e) {
            Assert.assertTrue(e.getCause().getMessage(), e.getCause().getMessage().contains("weaker key derivation"));
        }
    }

    @Test
    public void rejectsWeakScrypt() {
        assertRejected(new ScryptGenerator(ScryptGenerator.MIN_MEMORY_COST, 8, 1, 64, ""));
        assertRejected(new ScryptGenerator(ScryptGenerator.LOGIN_MEMORY_COST, 1, 1, 64, ""));
        assertRejected(new ScryptGenerator(ScryptGenerator.LOGIN_MEMORY_COST, 8, 0, 64, ""));
        assertRejected(new RandomSecretType());
    }

    @Test
    public void acceptsDefaults() {
        Assert.assertNotNull(UserUtil.generateUser("alice", "a long enough password", crypto,
                SecretGenerationAlgorithm.getDefaultWithoutExtraSalt()).join());
        Assert.assertNotNull(UserUtil.generateUser("alice", "a long enough password", crypto,
                SecretGenerationAlgorithm.getLegacy(crypto.random)).join());
    }
}
