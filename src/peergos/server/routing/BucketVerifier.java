package peergos.server.routing;

import peergos.server.util.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.util.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

/** Places servers in address buckets by reaching them (remove-pki §4.5): a server's addresses are its own claim,
 *  so it is put in a bucket only once it has been dialled at a direct address its record lists, authenticated by its
 *  key. Until then, and if that fails, it shares {@link AddressBucket#UNVERIFIED}.
 *
 *  Buckets are asked for while records are being taken in, so {@link #bucket} never dials: it answers from what is
 *  known and verifies in the background for next time.
 */
public class BucketVerifier {
    private static final Logger LOG = Logging.LOG();
    public static final long VERIFIED_MILLIS = 24 * 3600_000L, RETRY_MILLIS = 3600_000L;
    private static final long DIAL_TIMEOUT_SECONDS = 15;

    /** Connects to a peerid at these addresses only, giving the address the connection reached it on. */
    public interface Dialler {
        CompletableFuture<Optional<InetAddress>> reach(Multihash peerId, List<String> multiaddrs);
    }

    private final RoutingLookup lookup;
    private final Dialler dialler;
    private final LongSupplier clock;
    private final ExecutorService background;
    private final LRUCache<Multihash, Checked> checked = new LRUCache<>(100_000);
    private final Set<Multihash> verifying = ConcurrentHashMap.newKeySet();

    private static final class Checked {
        final Optional<AddressBucket> bucket;
        final long at;

        Checked(Optional<AddressBucket> bucket, long at) {
            this.bucket = bucket;
            this.at = at;
        }
    }

    public BucketVerifier(RoutingLookup lookup, Dialler dialler, LongSupplier clock, ExecutorService background) {
        this.lookup = lookup;
        this.dialler = dialler;
        this.clock = clock;
        this.background = background;
    }

    /** The server's verified bucket, or the shared one while it isn't. */
    public AddressBucket bucket(Multihash server) {
        return verified(server).orElse(AddressBucket.UNVERIFIED);
    }

    public Optional<AddressBucket> verified(Multihash server) {
        Multihash id = server.bareMultihash();
        Checked c;
        synchronized (checked) {
            c = checked.get(id);
        }
        long now = clock.getAsLong();
        if (c == null || now - c.at > (c.bucket.isPresent() ? VERIFIED_MILLIS : RETRY_MILLIS))
            verifyLater(id);
        return c == null ? Optional.empty() : c.bucket;
    }

    private void verifyLater(Multihash id) {
        if (verifying.add(id))
            background.submit(() -> {
                try {
                    verify(id);
                } finally {
                    verifying.remove(id);
                }
            });
    }

    /** Dials the server at the direct addresses its record lists, and records where it was reached. */
    public Optional<AddressBucket> verify(Multihash server) {
        Multihash id = server.bareMultihash();
        Optional<AddressBucket> res = Optional.empty();
        try {
            Optional<ServerAddresses> addrs = lookup.replica().server(Subject.server(id)).flatMap(ServerLog::addresses);
            if (addrs.isPresent()) {
                List<String> direct = addrs.get().addrs.stream()
                        .filter(a -> a.transport.equals(Address.LIBP2P))
                        .map(a -> new MultiAddress(a.address).toString())
                        .collect(Collectors.toList());
                Set<InetAddress> listed = direct.stream()
                        .map(BucketVerifier::ip)
                        .flatMap(Optional::stream)
                        .collect(Collectors.toSet());
                if (! listed.isEmpty()) {
                    Optional<InetAddress> reached = dialler.reach(ServerKeys.peerId(addrs.get().endpoint), direct)
                            .get(DIAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    res = reached.filter(listed::contains).map(AddressBucket::of);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Couldn't place " + id + " in a bucket: " + e.getMessage());
        }
        synchronized (checked) {
            checked.put(id, new Checked(res, clock.getAsLong()));
        }
        return res;
    }

    /** The IP literal a multiaddr starts with. */
    public static Optional<InetAddress> ip(String multiaddr) {
        String[] parts = multiaddr.split("/");
        if (parts.length < 3 || ! (parts[1].equals("ip4") || parts[1].equals("ip6")))
            return Optional.empty();
        if (! parts[2].matches("[0-9a-fA-F:.]+"))
            return Optional.empty(); // getByName would look a name up in DNS
        try {
            return Optional.of(InetAddress.getByName(parts[2]));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }
}
