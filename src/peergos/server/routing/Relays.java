package peergos.server.routing;

import io.libp2p.core.Host;
import io.libp2p.core.PeerId;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.protocol.circuit.RelayTransport.CandidateRelay;
import org.peergos.protocol.circuit.RelayBandwidth;
import org.peergos.protocol.circuit.RelayPolicy;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.util.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** Which relays this server reserves on, whom it relays for, and how a server is dialled through its relays
 *  (remove-pki §4.8). Relays are chosen from the log, those the operator configured first, then any, two in
 *  different address buckets. A relay takes only servers in the log whose proof of work is done, and limits how
 *  often each dialler and each target are connected through it. A dialler whose proof of work isn't done, so whose
 *  key cost nothing, shares a small pool with every other such dialler, so a server just created can still reach
 *  its friends while fresh keys can't get round the per-dialler limit.
 *
 *  Both are decided while the relay handles the request, so from the local replica only: a server it doesn't hold
 *  is refused for now, and looked up in the background for next time.
 */
public class Relays {
    public static final int MAX_CLIENTS = 1_000, DIALS_PER_INITIATOR_PER_MINUTE = 60, DIALS_PER_TARGET_PER_MINUTE = 600;
    public static final int UNPROVEN_DIALS_PER_MINUTE = 30, UNPROVEN_DIALS_PER_INITIATOR_PER_MINUTE = 5;
    private static final long PROVEN_CACHE_MILLIS = 10 * 60_000, UNPROVEN_CACHE_MILLIS = 60_000;
    /** The two named and a spare, so one that is briefly unreachable is reserved on again when it returns, as nabu
     *  dials candidates in order only while it holds fewer than this, and a replacement is already held for a while.
     */
    public static final int HELD = ServerAddressPublisher.NAMED_RELAYS + 1;
    /** Enough to set up connections and hole-punch, and to keep a server that can't be punched usable, slowly. */
    public static final long PER_SERVER_BYTES_PER_SECOND = 50_000, PER_SERVER_BURST = 10_000_000;
    public static final long TOTAL_BYTES_PER_SECOND = 4_000_000;
    private static final int MAX_SCANNED = 10_000;

    private final List<Multihash> configured;
    private final boolean holdWhilePublic;
    private final long totalBytesPerSecond;
    private final LongSupplier clock;
    private final Random random;
    private volatile Optional<RoutingLookup> lookup = Optional.empty();
    private volatile Multihash self;
    private final LRUCache<PeerId, long[]> byInitiator = new LRUCache<>(10_000), byTarget = new LRUCache<>(10_000),
            unprovenByInitiator = new LRUCache<>(10_000);
    private final long[] unprovenPool = {0, 0};
    private final LRUCache<Multihash, long[]> proven = new LRUCache<>(10_000);
    private final Set<Multihash> fetching = ConcurrentHashMap.newKeySet();
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Relay lookups");
        t.setDaemon(true);
        return t;
    });

    public Relays(List<Multihash> configured, LongSupplier clock, Random random) {
        this(configured, true, TOTAL_BYTES_PER_SECOND, clock, random);
    }

    /** @param holdWhilePublic whether to hold relays while publicly reachable: unless the operator announced a public
     *                        address, which can be relied on, the address AutoNAT confirmed may change
     */
    public Relays(List<Multihash> configured, boolean holdWhilePublic, long totalBytesPerSecond, LongSupplier clock, Random random) {
        this.configured = configured.stream().map(Multihash::bareMultihash).collect(Collectors.toList());
        this.holdWhilePublic = holdWhilePublic;
        this.totalBytesPerSecond = totalBytesPerSecond;
        this.clock = clock;
        this.random = random;
    }

    /** nabu is built before the log starts, so until then it neither reserves nor relays. */
    public void start(RoutingLookup lookup, Multihash self) {
        this.self = self.bareMultihash();
        this.lookup = Optional.of(lookup);
    }

    public RelayPolicy policy() {
        return new RelayPolicy(this::candidates, (requestor, observed) -> mayReserve(requestor), this::mayConnect, MAX_CLIENTS, HELD,
                holdWhilePublic ? HELD : 0,
                Optional.of(new RelayBandwidth(PER_SERVER_BYTES_PER_SECOND, PER_SERVER_BURST,
                        totalBytesPerSecond, 10 * totalBytesPerSecond)));
    }

    /** A server that relays: it says so, and lists a direct address to reach it on. */
    static Optional<List<Multiaddr>> relayAddresses(ServerLog log) {
        return log.addresses()
                .filter(a -> a.relaying && ! a.addrs.isEmpty())
                .map(a -> a.addrs.stream()
                        .filter(addr -> addr.transport.equals(Address.LIBP2P))
                        .map(addr -> Multiaddr.fromString(new MultiAddress(addr.address).toString()))
                        .collect(Collectors.toList()))
                .filter(addrs -> ! addrs.isEmpty());
    }

    /** Those this server names first, so after a restart or a new IP it reserves on them again and its record
     *  stays the same, then those the operator configured, then any, spread over address buckets. A relay dropped
     *  lately is skipped.
     */
    public List<CandidateRelay> candidates(Host us) {
        if (lookup.isEmpty())
            return Collections.emptyList();
        RoutingReplica replica = lookup.get().replica();
        Set<Multihash> failed = ServerAddressPublisher.recentlyFailed(replica, clock.getAsLong());
        List<Multihash> named = replica.server(Subject.server(self)).flatMap(ServerLog::addresses)
                .map(a -> a.relays.stream().map(Multihash::bareMultihash).collect(Collectors.toList()))
                .orElse(List.of());
        List<CandidateRelay> first = new ArrayList<>(), preferred = new ArrayList<>(), others = new ArrayList<>();
        for (Multihash peer : named)
            lookup.get().server(peer).flatMap(this::candidate).ifPresent(first::add);
        for (Multihash peer : configured)
            if (! named.contains(peer) && ! failed.contains(peer))
                lookup.get().server(peer).flatMap(this::candidate).ifPresent(preferred::add);
        byte[] after = new byte[0];
        int scanned = 0;
        while (scanned < MAX_SCANNED) {
            List<JdbcRoutingStore.State> page = replica.store().states(Subject.SERVER, after, 1_000);
            if (page.isEmpty())
                break;
            for (JdbcRoutingStore.State st : page) {
                Multihash id = st.subject.id.bareMultihash();
                if (configured.contains(id) || named.contains(id) || failed.contains(id))
                    continue;
                replica.server(st.subject).filter(l -> replica.isProven(st.subject, false))
                        .flatMap(this::candidate)
                        .ifPresent(others::add);
            }
            scanned += page.size();
            after = page.get(page.size() - 1).subject.idBytes();
        }
        Collections.shuffle(others, random);
        List<CandidateRelay> rest = new ArrayList<>(preferred);
        rest.addAll(others);
        first.addAll(spreadOverBuckets(rest));
        return first;
    }

    private Optional<CandidateRelay> candidate(ServerLog log) {
        if (log.peerId.bareMultihash().equals(self))
            return Optional.empty();
        return log.addresses().flatMap(a -> relayAddresses(log).map(addrs ->
                new CandidateRelay(PeerId.fromBase58(ServerKeys.peerId(a.endpoint).toBase58()), addrs)));
    }

    /** The two kept are the first two here, so each bucket's first candidate comes before any bucket's second. */
    static List<CandidateRelay> spreadOverBuckets(List<CandidateRelay> ordered) {
        Set<AddressBucket> seen = new HashSet<>();
        List<CandidateRelay> first = new ArrayList<>(), rest = new ArrayList<>();
        for (CandidateRelay c : ordered) {
            AddressBucket b = bucket(c.addrs);
            if (seen.add(b))
                first.add(c);
            else
                rest.add(c);
        }
        first.addAll(rest);
        return first;
    }

    static AddressBucket bucket(List<Multiaddr> addrs) {
        for (Multiaddr a : addrs) {
            String[] parts = a.toString().split("/");
            if (parts.length > 2 && (parts[1].equals("ip4") || parts[1].equals("ip6"))) {
                try {
                    return AddressBucket.of(InetAddress.getByName(parts[2])); // an IP literal, so no DNS lookup
                } catch (UnknownHostException e) {}
            }
        }
        return AddressBucket.UNVERIFIED;
    }

    /** Only a server whose proof of work is done gets a slot, so filling a relay costs a CPU-hour per slot. */
    public boolean mayReserve(PeerId requestor) {
        return isProven(requestor);
    }

    public boolean mayConnect(PeerId target, PeerId initiator) {
        if (isProven(initiator))
            return take(byInitiator, initiator, DIALS_PER_INITIATOR_PER_MINUTE)
                    && take(byTarget, target, DIALS_PER_TARGET_PER_MINUTE);
        return take(unprovenByInitiator, initiator, UNPROVEN_DIALS_PER_INITIATOR_PER_MINUTE)
                && take(unprovenPool, UNPROVEN_DIALS_PER_MINUTE)
                && take(byTarget, target, DIALS_PER_TARGET_PER_MINUTE);
    }

    /** A server in the local replica whose proof of work is done. */
    public boolean isProven(PeerId peer) {
        if (lookup.isEmpty())
            return false;
        Multihash id;
        try {
            id = Multihash.decode(peer.getBytes()).bareMultihash();
        } catch (RuntimeException e) {
            return false;
        }
        long now = clock.getAsLong();
        synchronized (proven) {
            long[] cached = proven.get(id);
            if (cached != null && now - cached[0] < (cached[1] == 1 ? PROVEN_CACHE_MILLIS : UNPROVEN_CACHE_MILLIS))
                return cached[1] == 1;
        }
        RoutingReplica replica = lookup.get().replica();
        Subject s = Subject.server(id);
        boolean res = replica.server(s).isPresent() && replica.isProven(s, false);
        synchronized (proven) {
            proven.put(id, new long[]{now, res ? 1 : 0});
        }
        if (! res && fetching.add(id))
            background.submit(() -> {
                try {
                    if (lookup.get().server(id).isPresent())
                        synchronized (proven) {
                            proven.remove(id);
                        }
                } catch (RuntimeException e) {
                } finally {
                    fetching.remove(id);
                }
            });
        return res;
    }

    private boolean take(long[] w, int perMinute) {
        long now = clock.getAsLong();
        synchronized (w) {
            if (now - w[0] > 60_000) {
                w[0] = now;
                w[1] = 0;
            }
            if (w[1] >= perMinute)
                return false;
            w[1]++;
            return true;
        }
    }

    private boolean take(LRUCache<PeerId, long[]> windows, PeerId peer, int perMinute) {
        long now = clock.getAsLong();
        synchronized (windows) {
            long[] w = windows.get(peer);
            if (w == null || now - w[0] > 60_000) {
                windows.put(peer, new long[]{now, 1});
                return true;
            }
            if (w[1] >= perMinute)
                return false;
            w[1]++;
            return true;
        }
    }

    /** The circuit addresses a server is dialled on through each relay it names, for the address book. */
    public static List<Multiaddr> relayed(RoutingReplica replica, ServerLog target) {
        Optional<ServerAddresses> addrs = target.addresses();
        if (addrs.isEmpty())
            return Collections.emptyList();
        String to = ServerKeys.peerId(addrs.get().endpoint).toBase58();
        List<Multiaddr> res = new ArrayList<>();
        for (Multihash relay : addrs.get().relays) {
            Optional<ServerLog> log = replica.server(Subject.server(relay.bareMultihash()));
            if (log.isEmpty() || log.get().addresses().isEmpty())
                continue;
            String relayId = ServerKeys.peerId(log.get().addresses().get().endpoint).toBase58();
            for (Multiaddr direct : relayAddresses(log.get()).orElse(List.of()))
                res.add(Multiaddr.fromString(direct + "/p2p/" + relayId + "/p2p-circuit/p2p/" + to));
        }
        return res;
    }
}
