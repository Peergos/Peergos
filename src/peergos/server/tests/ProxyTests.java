package peergos.server.tests;

import org.junit.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** The rules for when a call for a user goes to their home server, and when it is answered from our
 *  own mirror of their data instead.
 */
public class ProxyTests {

    private static final Cid OURS = id(1);
    private static final Cid THEIRS = id(2);
    private static final PublicKeyHash OWNER = new PublicKeyHash(id(3));

    private static Cid id(int seed) {
        byte[] hash = new byte[32];
        hash[0] = (byte) seed;
        return new Cid(1, Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, hash);
    }

    /** A pki which says the user lives on one server, and which has no record of it ever rotating its
     *  identity, so a failed call to it stays failed.
     */
    private static class Pki extends RamPki {
        private final Multihash home;

        public Pki(Multihash home) {
            this.home = home;
        }

        @Override
        public List<Multihash> getStorageProviders(PublicKeyHash owner) {
            return List.of(home);
        }

        @Override
        public CompletableFuture<Optional<Multihash>> getNextServerId(Multihash serverId) {
            return Futures.of(Optional.empty());
        }
    }

    private static final Function<Multihash, CompletableFuture<Optional<String>>> UNREACHABLE =
            target -> Futures.errored(new RuntimeException("Connection refused"));

    private static final Function<PublicKeyHash, Boolean> MIRRORED = owner -> true;
    private static final Function<PublicKeyHash, Boolean> NOT_MIRRORED = owner -> false;

    @Test
    public void ourOwnUserIsNeverProxied() {
        String res = Proxy.redirectCall(new Pki(OURS), List.of(OURS), OWNER,
                () -> Futures.of("local"),
                target -> Futures.errored(new RuntimeException("Shouldn't proxy to ourselves!"))).join();
        Assert.assertEquals("local", res);
    }

    @Test
    public void readOfAMirroredUserIsServedLocally() {
        // their home server is never asked, which is what keeps them readable while it is down
        Optional<String> res = Proxy.redirectRead(new Pki(THEIRS), List.of(OURS), OWNER,
                () -> Futures.of(Optional.of("local")),
                UNREACHABLE, MIRRORED, Optional::isPresent).join();
        Assert.assertEquals(Optional.of("local"), res);
    }

    @Test
    public void readWeDontHaveLocallyStillGoesToTheHomeServer() {
        Optional<String> res = Proxy.redirectRead(new Pki(THEIRS), List.of(OURS), OWNER,
                () -> Futures.of(Optional.<String>empty()),
                target -> Futures.of(Optional.of("remote")), MIRRORED, Optional::isPresent).join();
        Assert.assertEquals(Optional.of("remote"), res);
    }

    @Test
    public void readOfAUserWeDontMirrorNeverTouchesOurStore() {
        Optional<String> res = Proxy.redirectRead(new Pki(THEIRS), List.of(OURS), OWNER,
                () -> Futures.errored(new RuntimeException("Shouldn't read our own store!")),
                target -> Futures.of(Optional.of("remote")), NOT_MIRRORED, Optional::isPresent).join();
        Assert.assertEquals(Optional.of("remote"), res);
    }

    @Test
    public void mutableStateComesFromTheHomeServerWhenItIsUp() {
        Optional<String> res = Proxy.redirectCallWithMirrorFallback(new Pki(THEIRS), List.of(OURS), OWNER,
                () -> Futures.of(Optional.of("stale")),
                target -> Futures.of(Optional.of("current")), MIRRORED).join();
        Assert.assertEquals(Optional.of("current"), res);
    }

    @Test
    public void mutableStateFallsBackToOurMirrorWhenTheHomeServerIsUnreachable() {
        Optional<String> res = Proxy.redirectCallWithMirrorFallback(new Pki(THEIRS), List.of(OURS), OWNER,
                () -> Futures.of(Optional.of("mirrored")),
                UNREACHABLE, MIRRORED).join();
        Assert.assertEquals(Optional.of("mirrored"), res);
    }

    @Test
    public void anUnreachableHomeServerIsAnErrorForAUserWeDontMirror() {
        CompletableFuture<Optional<String>> res = Proxy.redirectCallWithMirrorFallback(new Pki(THEIRS),
                List.of(OURS), OWNER,
                () -> Futures.of(Optional.of("not ours to serve")),
                UNREACHABLE, NOT_MIRRORED);
        try {
            res.join();
            Assert.fail("Should have propagated the failure to reach their home server");
        } catch (CompletionException e) {
            Assert.assertEquals("Connection refused", e.getCause().getMessage());
        }
    }
}
