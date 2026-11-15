package peergos.server.tests;

import peergos.shared.crypto.asymmetric.SigningDomain;

import com.sun.net.httpserver.*;
import org.junit.*;
import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.crypto.asymmetric.curve25519.*;
import peergos.server.crypto.random.*;
import peergos.server.net.*;
import peergos.server.storage.admin.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.lang.reflect.InvocationHandler;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Completing a paid signup or mirror can wait seconds for a card, which mustn't hold an api handler. */
public class PaidCompletionQueueTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Hasher hasher = crypto.hasher;

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Map<String, Object> results) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (proxy, method, args) -> {
            if (results.containsKey(method.getName()))
                return results.get(method.getName());
            if (method.getName().equals("close"))
                return null;
            if (method.isDefault())
                return InvocationHandler.invokeDefault(proxy, method, args);
            throw new UnsupportedOperationException(method.getName());
        });
    }

    private static BatWithId mirrorBat() {
        Bat bat = Bat.random(crypto.random);
        return new BatWithId(bat, BatId.sha256(bat, hasher).join().id);
    }

    @Test
    public void completionsAreQueuedAndBounded() throws Exception {
        SigningKeyPair keys = SigningKeyPair.random(new SafeRandomJava(), new Ed25519Java());
        PublicKeyHash owner = ContentAddressedStorage.hashKey(keys.publicSigningKey);
        CoreNode pki = stub(CoreNode.class, Map.of("getPublicKeyHash", Futures.of(Optional.of(owner))));
        // no card ever shows up, so each completion retries for 9s
        QuotaAdmin quotas = stub(QuotaAdmin.class, Map.of(
                "getQuota", 0L,
                "requestQuota", Futures.of(new PaymentProperties(0))));
        ContentAddressedStorage dht = stub(ContentAddressedStorage.class, Map.of());
        SignUpFilter filter = new SignUpFilter(pki, quotas, null, null, dht, hasher, 10, false);
        BatWithId bat = mirrorBat();
        java.util.function.Function<String, CompletableFuture<PaymentProperties>> complete = username -> {
            QuotaControl.SpaceRequest req = new QuotaControl.SpaceRequest(username, 1_000_000_000L, false,
                    System.currentTimeMillis(), Optional.empty());
            byte[] signed = keys.secretSigningKey.signMessage(req.serialize(), SigningDomain.SPACE_REQUEST).join();
            return filter.completePaidMirror(username, bat, signed, ProofOfWork.empty());
        };

        long t0 = System.currentTimeMillis();
        List<CompletableFuture<PaymentProperties>> pending = new ArrayList<>();
        pending.add(complete.apply("user0"));
        Assert.assertTrue("queueing returns straight away", System.currentTimeMillis() - t0 < 2_000);
        for (int i = 1; i < 100; i++)
            pending.add(complete.apply("user" + i));
        Assert.assertTrue("queueing returns straight away", System.currentTimeMillis() - t0 < 2_000);
        Assert.assertTrue(pending.stream().noneMatch(CompletableFuture::isDone));

        assertRefused(complete.apply("user0"), "already being completed");
        assertRefused(complete.apply("user100"), "busy");

        PaymentProperties first = pending.get(0).get(30, TimeUnit.SECONDS);
        Assert.assertFalse(first.hasError());
        long waited = System.currentTimeMillis() - t0;
        Assert.assertTrue("retried for a card: " + waited, waited >= 9_000);

        for (CompletableFuture<PaymentProperties> f : pending)
            f.get(30, TimeUnit.SECONDS);
        Assert.assertFalse("slots are freed once done", complete.apply("user100").isDone());
        filter.close();
    }

    private static void assertRefused(CompletableFuture<PaymentProperties> res, String reason) {
        try {
            res.get(5, TimeUnit.SECONDS);
            Assert.fail("not refused: " + reason);
        } catch (ExecutionException e) {
            Assert.assertTrue(e.getCause().getMessage(), e.getCause().getMessage().contains(reason));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void slowCompletionsDontHoldTheHandler() throws Exception {
        ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor();
        CoreNode slow = stub(CoreNode.class, Map.of());
        CoreNode core = (CoreNode) java.lang.reflect.Proxy.newProxyInstance(CoreNode.class.getClassLoader(), new Class[]{CoreNode.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("completePaidMirror")) {
                        CompletableFuture<PaymentProperties> res = new CompletableFuture<>();
                        later.schedule(() -> res.complete(new PaymentProperties(42)), 2, TimeUnit.SECONDS);
                        return res;
                    }
                    return method.invoke(slow, args);
                });
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 10);
        server.createContext("/" + Constants.CORE_URL, new CoreNodeHandler(core, false));
        // one handler thread: a second request can only be served while the first waits if the handler is free
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        try {
            HTTPCoreNode client = new HTTPCoreNode(new JavaPoster(new URL("http://localhost:" + server.getAddress().getPort()), false));
            long t0 = System.currentTimeMillis();
            CompletableFuture<PaymentProperties> a = client.completePaidMirror("alice", mirrorBat(), new byte[0], ProofOfWork.empty());
            CompletableFuture<PaymentProperties> b = client.completePaidMirror("bob", mirrorBat(), new byte[0], ProofOfWork.empty());
            Assert.assertEquals(42, a.get(10, TimeUnit.SECONDS).freeQuota);
            Assert.assertEquals(42, b.get(10, TimeUnit.SECONDS).freeQuota);
            long took = System.currentTimeMillis() - t0;
            Assert.assertTrue("both waited at once, not one after the other: " + took, took < 3_500);
        } finally {
            server.stop(0);
            later.shutdownNow();
        }
    }
}
