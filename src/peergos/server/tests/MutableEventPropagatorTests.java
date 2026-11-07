package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.mutable.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;

import java.util.*;
import java.util.concurrent.*;

public class MutableEventPropagatorTests {

    /** A listener has the pointers a call lands together: the quota filter records what each names before it queues
     *  any of their usage updates, or the queue could judge one update before a child the call moves under another of
     *  its writers is linked there */
    @Test
    public void aListenerHasThePointersACallLandsTogether() {
        MutablePointers accepting = (MutablePointers) java.lang.reflect.Proxy.newProxyInstance(MutablePointers.class.getClassLoader(),
                new Class<?>[] {MutablePointers.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setPointers"))
                        return CompletableFuture.completedFuture(true);
                    throw new UnsupportedOperationException(method.getName());
                });
        MutableEventPropagator propagator = new MutableEventPropagator(accepting);
        List<List<PublicKeyHash>> calls = new ArrayList<>();
        propagator.addListener(events -> calls.add(events.stream().map(e -> e.writer).toList()));

        Crypto crypto = Main.initCrypto();
        PublicKeyHash owner = key(crypto), a = key(crypto), b = key(crypto);
        propagator.setPointers(owner, List.of(new SignedPointerUpdate(a, new byte[] {1}), new SignedPointerUpdate(b, new byte[] {2}))).join();
        Assert.assertEquals(List.of(List.of(a, b)), calls);
    }

    private static PublicKeyHash key(Crypto crypto) {
        return ContentAddressedStorage.hashKey(SigningKeyPair.random(crypto.random, crypto.signer).publicSigningKey);
    }
}
