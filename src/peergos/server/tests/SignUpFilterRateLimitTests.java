package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.crypto.asymmetric.curve25519.*;
import peergos.server.crypto.random.*;
import peergos.server.storage.admin.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** Accepted paid signup and mirror requests must raise the proof of work needed for the next ones. */
public class SignUpFilterRateLimitTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Hasher hasher = crypto.hasher;

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Map<String, Object> results) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (proxy, method, args) -> {
            if (results.containsKey(method.getName()))
                return results.get(method.getName());
            throw new UnsupportedOperationException(method.getName());
        });
    }

    /** A proof that only meets difficulty 0, so is refused as soon as the required difficulty rises */
    private static ProofOfWork weakestProof(byte[] data) {
        for (int i = 0; i < 256; i++) {
            byte[] prefix = {(byte) i};
            byte[] hash = hasher.sha256(ArrayOps.concat(prefix, data)).join();
            if ((hash[0] & 0x80) != 0)
                return new ProofOfWork(prefix, Multihash.Type.sha2_256);
        }
        throw new IllegalStateException("No weak proof found");
    }

    private static SignUpFilter filter(CoreNode target) {
        QuotaAdmin quotas = stub(QuotaAdmin.class, Map.of("createPaidUser", new PaymentProperties(0)));
        return new SignUpFilter(target, quotas, null, null, null, hasher, 10, false);
    }

    private static int requestsUntilRefused(java.util.function.Supplier<Either<PaymentProperties, RequiredDifficulty>> request) {
        for (int i = 1; i <= 20; i++) {
            Either<PaymentProperties, RequiredDifficulty> res = request.get();
            if (res.isB()) {
                Assert.assertTrue(res.b().requiredDifficulty > 0);
                return i;
            }
        }
        throw new AssertionError("20 requests in a row were accepted without raising the difficulty");
    }

    @Test
    public void paidMirrorsRaiseDifficulty() {
        String username = "mirroruser";
        CoreNode pki = stub(CoreNode.class, Map.of("startPaidMirror", Futures.of(Either.a(new PaymentProperties(0)))));
        SignUpFilter filter = filter(pki);
        ProofOfWork proof = weakestProof(username.getBytes(StandardCharsets.UTF_8));

        int refusedAt = requestsUntilRefused(() -> filter.startPaidMirror(username, new byte[0], proof).join());
        Assert.assertTrue("the first request is accepted", refusedAt > 1);
    }

    @Test
    public void paidSignupsRaiseDifficulty() {
        String username = "paiduser";
        SigningKeyPair keys = SigningKeyPair.random(new SafeRandomJava(), new Ed25519Java());
        SigningPrivateKeyAndPublicHash signer = new SigningPrivateKeyAndPublicHash(
                ContentAddressedStorage.hashKey(keys.publicSigningKey), keys.secretSigningKey);
        Multihash server = new Cid(1, Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, new byte[32]);
        UserPublicKeyLink chain = UserPublicKeyLink.createInitial(signer, username, LocalDate.now().plusMonths(2), List.of(server))
                .join().get(0);
        CoreNode pki = stub(CoreNode.class, Map.of("startPaidSignup", Futures.of(Either.a(new PaymentProperties(0)))));
        SignUpFilter filter = filter(pki);
        ProofOfWork proof = weakestProof(new CborObject.CborList(List.of(chain)).serialize());

        int refusedAt = requestsUntilRefused(() -> filter.startPaidSignup(username, chain, proof).join());
        Assert.assertTrue("the first request is accepted", refusedAt > 1);
    }
}
