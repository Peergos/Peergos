package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.net.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class IdentityKeysTests {
    private static final Crypto crypto = Main.initCrypto();

    private static SigningKeyPair key() {
        return SigningKeyPair.random(crypto.random, crypto.signer);
    }

    /** A home server whose records answer can be switched to failing, as when it refuses with a 429. */
    private static class Home {
        final AtomicInteger asked = new AtomicInteger();
        volatile Optional<List<SignedRecord>> records = Optional.empty();

        IdentityRouting routing() {
            return (IdentityRouting) java.lang.reflect.Proxy.newProxyInstance(IdentityRouting.class.getClassLoader(), new Class[]{IdentityRouting.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("records")) {
                            asked.incrementAndGet();
                            return records.<CompletableFuture<List<SignedRecord>>>map(Futures::of)
                                    .orElseGet(() -> Futures.errored(new IllegalStateException("Too many requests")));
                        }
                        return method.invoke(IdentityRouting.DISABLED, args);
                    });
        }
    }

    @Test
    public void aRefusingHomeServerNeverMakesTheKeyFromBeforeARotationCount() {
        SigningKeyPair k0 = key(), k1 = key(), k2 = key();
        Multihash server = ServerKeys.peerId(key().publicSigningKey);
        IdentityInception inc = IdentityInception.build(k0, List.of(server), k1.publicSigningKey, crypto.hasher).join();
        IdentityRotation rot = IdentityRotation.build(k0.publicSigningKey, k1, List.of(server), k2.publicSigningKey, crypto.hasher).join();
        PublicKeyHash identity = ContentAddressedStorage.hashKey(k0.publicSigningKey);

        Home home = new Home();
        AtomicLong now = new AtomicLong(1_000_000);
        IdentityKeys.Verifying keys = new IdentityKeys.Verifying(home.routing(), crypto.hasher, now::get);

        try {
            keys.rotated(identity).join();
            Assert.fail("with no key verified yet, a refusal fails the lookup rather than using the key in the hash");
        } catch (CompletionException expected) {}
        keys.rotated(identity).exceptionally(t -> null).join();
        Assert.assertEquals("a refusal is remembered for a while", 1, home.asked.get());

        now.addAndGet(60_000);
        home.records = Optional.of(List.of(inc, rot));
        Assert.assertEquals(Optional.of(k1.publicSigningKey), keys.rotated(identity).join());

        now.addAndGet(60 * 60_000);
        home.records = Optional.empty();
        Assert.assertEquals("a refusal after a rotation was seen keeps the rotated key",
                Optional.of(k1.publicSigningKey), keys.rotated(identity).join());

        home.records = Optional.of(List.of());
        PublicKeyHash legacy = ContentAddressedStorage.hashKey(key().publicSigningKey);
        Assert.assertEquals("a server without the log answers with nothing, which means the key in the hash",
                Optional.empty(), keys.rotated(legacy).join());
    }

    @Test
    public void behindALocalProxyTheAddressIsTheOneTheProxyAdded() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        InetAddress remote = InetAddress.getByName("203.0.113.7");
        Assert.assertEquals("local", IdentityRoutingHandler.client(loopback, null));
        String a = IdentityRoutingHandler.client(loopback, List.of("198.51.100.1, 203.0.113.7"));
        Assert.assertEquals("the last address is the proxy's, the ones before it the client's own",
                IdentityRoutingHandler.client(remote, null), a);
        Assert.assertNotEquals(a, IdentityRoutingHandler.client(loopback, List.of("203.0.113.8")));
        Assert.assertEquals("a forwarded header from elsewhere isn't believed",
                IdentityRoutingHandler.client(remote, null), IdentityRoutingHandler.client(remote, List.of("203.0.113.8")));
        Assert.assertEquals("local", IdentityRoutingHandler.client(loopback, List.of("not an address")));
    }
}
