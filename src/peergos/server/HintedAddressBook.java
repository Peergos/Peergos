package peergos.server;

import io.libp2p.core.*;
import io.libp2p.core.multiformats.*;
import org.jetbrains.annotations.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** The address book nabu dials from, plus the signed addresses the routing log holds for a server and, last, the
 *  addresses links hinted at (remove-pki §5, §11). Hints are held apart, for an hour, so that nabu re-resolving a
 *  peer, which replaces its entry wholesale, never mixes them into verified addresses, and they always rank last.
 */
public class HintedAddressBook implements AddressBook {
    public static final long HINT_MILLIS = 3600_000L;

    private static class Hinted {
        final List<Multiaddr> addrs;
        final long expiry;

        Hinted(List<Multiaddr> addrs, long expiry) {
            this.addrs = addrs;
            this.expiry = expiry;
        }
    }

    private final AddressBook base;
    private final Supplier<Long> time;
    private final LRUCache<PeerId, Hinted> hints = new LRUCache<>(1_000);
    private volatile Function<PeerId, List<Multiaddr>> logged = p -> Collections.emptyList();

    public HintedAddressBook(AddressBook base, Supplier<Long> time) {
        this.base = base;
        this.time = time;
    }

    public void setLogged(Function<PeerId, List<Multiaddr>> logged) {
        this.logged = logged;
    }

    public void addHint(PeerId peer, List<Multiaddr> addrs) {
        synchronized (hints) {
            hints.put(peer, new Hinted(List.copyOf(addrs), time.get() + HINT_MILLIS));
        }
    }

    private List<Multiaddr> hinted(PeerId peer) {
        synchronized (hints) {
            Hinted h = hints.get(peer);
            if (h == null)
                return Collections.emptyList();
            if (h.expiry < time.get()) {
                hints.remove(peer);
                return Collections.emptyList();
            }
            return h.addrs;
        }
    }

    @NotNull
    @Override
    public CompletableFuture<Collection<Multiaddr>> getAddrs(@NotNull PeerId peer) {
        return base.getAddrs(peer).thenApply(known -> {
            LinkedHashSet<Multiaddr> res = new LinkedHashSet<>(known);
            res.addAll(logged.apply(peer));
            res.addAll(hinted(peer));
            return new ArrayList<>(res);
        });
    }

    @NotNull
    @Override
    public CompletableFuture<Void> setAddrs(@NotNull PeerId peer, long ttl, @NotNull Multiaddr... addrs) {
        return base.setAddrs(peer, ttl, addrs);
    }

    @NotNull
    @Override
    public CompletableFuture<Void> addAddrs(@NotNull PeerId peer, long ttl, @NotNull Multiaddr... addrs) {
        return base.addAddrs(peer, ttl, addrs);
    }
}
