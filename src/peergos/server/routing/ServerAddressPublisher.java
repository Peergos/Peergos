package peergos.server.routing;

import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.util.*;

import java.net.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

/** Keeps this server's ServerAddresses current (remove-pki §4.1, §4.8, §10.11). It lists the IP literals the
 *  operator announces, and the addresses AutoNAT has confirmed dialable once they have stayed confirmed for a month,
 *  so a static address is listed without configuration and one that changes more often never is. A server with a
 *  direct address relays, if allowed. Unless the operator announced an address, it also names the relays it holds,
 *  so when its confirmed address changes it stays reachable while the old one is still listed, until the new one
 *  qualifies and replaces it in a single update.
 *
 *  Peers take a burst of three updates and then one a month, so a record is signed only when its content changes,
 *  the relays named stay the same unless one is gone for hours, and a change is deferred while this server's own
 *  count of that budget is spent.
 */
public class ServerAddressPublisher {
    private static final Logger LOG = Logging.LOG();
    private static final Set<String> NAMED = Set.of("dns", "dns4", "dns6", "dnsaddr", "p2p-circuit");
    static final String META_KEY = "server-addresses", RELAYS_META_KEY = "server-relays", FAILED_META_KEY = "server-relays-failed",
            BUDGET_META_KEY = "server-addresses-budget";
    public static final int NAMED_RELAYS = 2;
    public static final long RELAY_STABLE_MILLIS = 10 * 60_000, RELAY_FORGET_MILLIS = 6 * 3600_000L,
            RELAY_REPLACE_MILLIS = 6 * 3600_000L, RELAY_FAILED_MILLIS = 7 * 24 * 3600_000L;
    public static final long STABLE_MILLIS = 30 * 24 * 3600_000L;
    /** Confirmed this recently, the address is still this server's. */
    public static final long CURRENT_MILLIS = 24 * 3600_000L;
    /** An address unconfirmed for this long is dropped, and must be confirmed for a month again to return. Until
     *  then it stays listed, unless a current one is, since dropping it alone would cost an update and the new
     *  address another; while a new one is on its way to qualifying it is kept for twice as long.
     */
    public static final long FORGET_MILLIS = 30 * 24 * 3600_000L;

    private final RoutingReplica replica;
    private final SigningKeyPair serverKey;
    private final List<Address> announced;
    private final Supplier<List<String>> confirmed;
    private final ServerAddresses.Mode mode;
    private final Supplier<List<Multihash>> relays;
    private final boolean mayRelay;
    private final LongSupplier clock;

    public ServerAddressPublisher(RoutingReplica replica,
                                  SigningKeyPair serverKey,
                                  List<String> announced,
                                  Supplier<List<String>> confirmed,
                                  ServerAddresses.Mode mode,
                                  LongSupplier clock) {
        this(replica, serverKey, announced, confirmed, mode, Collections::emptyList, false, clock);
    }

    /**
     * @param relays the relays this server holds reservations on now
     * @param mayRelay whether the operator lets this server relay for others once it has a direct address
     */
    public ServerAddressPublisher(RoutingReplica replica,
                                  SigningKeyPair serverKey,
                                  List<String> announced,
                                  Supplier<List<String>> confirmed,
                                  ServerAddresses.Mode mode,
                                  Supplier<List<Multihash>> relays,
                                  boolean mayRelay,
                                  LongSupplier clock) {
        this.replica = replica;
        this.serverKey = serverKey;
        this.announced = directAddresses(announced);
        this.confirmed = confirmed;
        this.mode = mode;
        this.relays = relays;
        this.mayRelay = mayRelay;
        this.clock = clock;
    }

    /** Records which addresses are confirmed now, and publishes a new record if what should be listed changed. */
    public synchronized Optional<ServerAddresses> refresh() {
        long now = clock.getAsLong();
        Map<Address, Seen> seen = loadSeen(META_KEY, Address::fromCbor);
        for (Address a : directAddresses(confirmed.get())) {
            Seen prior = seen.get(a);
            seen.put(a, new Seen(prior == null ? now : prior.first, now));
        }
        Predicate<Seen> current = v -> now - v.last <= CURRENT_MILLIS;
        Predicate<Seen> stable = v -> now - v.first >= STABLE_MILLIS;
        boolean qualifying = seen.values().stream().anyMatch(v -> current.test(v) && ! stable.test(v));
        seen.values().removeIf(v -> now - v.last > (qualifying ? 2 * FORGET_MILLIS : FORGET_MILLIS));
        saveSeen(META_KEY, seen);

        boolean anyCurrent = seen.values().stream().anyMatch(v -> stable.test(v) && current.test(v));
        List<Address> listed = new ArrayList<>(announced);
        seen.entrySet().stream()
                .filter(e -> stable.test(e.getValue()) && (! anyCurrent || current.test(e.getValue())))
                .map(Map.Entry::getKey)
                .filter(a -> ! listed.contains(a))
                .sorted(Comparator.comparing(a -> ArrayOps.bytesToHex(a.address)))
                .forEach(listed::add);

        List<Multihash> named = announced.isEmpty() ? relays(now) : List.of();
        boolean relaying = mayRelay && ! listed.isEmpty();
        Optional<ServerAddresses> held = replica.server(Subject.server(ServerKeys.peerId(serverKey.publicSigningKey)))
                .flatMap(ServerLog::addresses);
        if (held.isPresent() && ! sameContent(held.get(), serverKey.publicSigningKey, listed, named, relaying, mode)) {
            if (! spend(now)) {
                LOG.info("Deferring this server's address update until peers will take it");
                return Optional.empty();
            }
        }
        return publish(replica, serverKey, listed, named, relaying, mode, clock);
    }

    /** Takes one update from this server's own count of the budget peers allow, if one is due. */
    private boolean spend(long now) {
        RateLimits.Rate rate = RateLimits.UPDATES.get(RecordType.SERVER_ADDRESSES);
        long full = rate.burst * rate.refillMillis;
        long credit = full, at = now;
        Optional<byte[]> raw = replica.store().getMeta(BUDGET_META_KEY);
        if (raw.isPresent()) {
            CborObject.CborMap m = (CborObject.CborMap) CborObject.fromByteArray(raw.get());
            credit = m.getLong("c");
            at = m.getLong("a");
        }
        credit = Math.min(full, credit + Math.max(0, now - at));
        if (credit < rate.refillMillis)
            return false;
        SortedMap<String, Cborable> m = new TreeMap<>();
        m.put("c", new CborObject.CborLong(credit - rate.refillMillis));
        m.put("a", new CborObject.CborLong(now));
        replica.store().setMeta(BUDGET_META_KEY, CborObject.CborMap.build(m).serialize());
        return true;
    }

    /** The relays to name: those already named, each kept until it has been gone for hours, so a restart or a
     *  new IP, after which this server reserves on them again, changes nothing. A gone one is replaced by a
     *  relay held for a while, in its slot, one at a time and only while the other is held, and is not used again
     *  for a week.
     */
    private List<Multihash> relays(long now) {
        Set<Multihash> current = new HashSet<>();
        relays.get().forEach(r -> current.add(r.bareMultihash()));
        Map<Multihash, Seen> held = loadSeen(RELAYS_META_KEY, ServerAddressPublisher::multihash);
        for (Multihash relay : current) {
            Seen prior = held.get(relay);
            held.put(relay, new Seen(prior == null ? now : prior.first, now));
        }
        List<Multihash> named = new ArrayList<>(published(replica, serverKey));
        for (Multihash r : named)
            held.putIfAbsent(r, new Seen(now, now)); // a lost store gives the named relays a fresh grace period
        held.entrySet().removeIf(e -> ! named.contains(e.getKey()) && now - e.getValue().last > RELAY_FORGET_MILLIS);
        Map<Multihash, Seen> failed = loadSeen(FAILED_META_KEY, ServerAddressPublisher::multihash);
        failed.values().removeIf(v -> now - v.last > RELAY_FAILED_MILLIS);

        Predicate<Multihash> gone = r -> now - held.get(r).last > RELAY_REPLACE_MILLIS || retired(r);
        for (int i = 0; i < named.size(); i++) {
            Multihash r = named.get(i);
            if (! gone.test(r))
                continue;
            boolean othersHeld = named.stream().filter(o -> ! o.equals(r)).allMatch(current::contains);
            if (! othersHeld && ! named.stream().allMatch(gone))
                continue;
            Optional<Multihash> spare = spare(current, held, named, failed, now);
            if (spare.isEmpty())
                break;
            named.set(i, spare.get());
            failed.put(r, new Seen(now, now));
            break;
        }
        while (named.size() < NAMED_RELAYS) {
            Optional<Multihash> spare = spare(current, held, named, failed, now);
            if (spare.isEmpty())
                break;
            named.add(spare.get());
        }
        saveSeen(RELAYS_META_KEY, held);
        saveSeen(FAILED_META_KEY, failed);
        return named;
    }

    /** The relay held longest, if held long enough, that isn't named and hasn't failed lately. */
    private static Optional<Multihash> spare(Set<Multihash> current, Map<Multihash, Seen> held, List<Multihash> named,
                                             Map<Multihash, Seen> failed, long now) {
        return current.stream()
                .filter(r -> ! named.contains(r) && ! failed.containsKey(r))
                .filter(r -> now - held.get(r).first >= RELAY_STABLE_MILLIS)
                .min(Comparator.<Multihash>comparingLong(r -> held.get(r).first).thenComparing(Multihash::toString));
    }

    /** A relay whose own record says it no longer relays. One we hold no record of isn't retired, since a light
     *  server holds few.
     */
    private boolean retired(Multihash relay) {
        return replica.server(Subject.server(relay)).flatMap(ServerLog::addresses)
                .map(a -> ! a.relaying || a.addrs.isEmpty())
                .orElse(false);
    }

    static List<Multihash> published(RoutingReplica replica, SigningKeyPair serverKey) {
        return replica.server(Subject.server(ServerKeys.peerId(serverKey.publicSigningKey)))
                .flatMap(ServerLog::addresses)
                .map(a -> a.relays.stream().map(Multihash::bareMultihash).collect(Collectors.toList()))
                .orElse(List.of());
    }

    /** Relays dropped from this server's record in the last week, which it doesn't reserve on again. */
    public static Set<Multihash> recentlyFailed(RoutingReplica replica, long now) {
        Set<Multihash> res = new HashSet<>();
        replica.store().getMeta(FAILED_META_KEY).ifPresent(raw -> {
            for (Cborable c : ((CborObject.CborList) CborObject.fromByteArray(raw)).value) {
                CborObject.CborMap m = (CborObject.CborMap) c;
                if (now - m.getLong("l") <= RELAY_FAILED_MILLIS)
                    res.add(multihash(m.get("a")));
            }
        });
        return res;
    }

    private static Multihash multihash(Cborable c) {
        return Multihash.decode(((CborObject.CborByteArray) c).value);
    }

    private static class Seen {
        final long first, last;

        Seen(long first, long last) {
            this.first = first;
            this.last = last;
        }
    }

    private <K> Map<K, Seen> loadSeen(String key, Function<Cborable, K> fromCbor) {
        Map<K, Seen> res = new HashMap<>();
        replica.store().getMeta(key).ifPresent(raw -> {
            for (Cborable c : ((CborObject.CborList) CborObject.fromByteArray(raw)).value) {
                CborObject.CborMap m = (CborObject.CborMap) c;
                res.put(fromCbor.apply(m.get("a")), new Seen(m.getLong("f"), m.getLong("l")));
            }
        });
        return res;
    }

    private <K> void saveSeen(String key, Map<K, Seen> seen) {
        List<Cborable> entries = new ArrayList<>();
        for (Map.Entry<K, Seen> e : seen.entrySet()) {
            SortedMap<String, Cborable> m = new TreeMap<>();
            K k = e.getKey();
            m.put("a", k instanceof Multihash ? new CborObject.CborByteArray(((Multihash) k).toBytes()) : (Cborable) k);
            m.put("f", new CborObject.CborLong(e.getValue().first));
            m.put("l", new CborObject.CborLong(e.getValue().last));
            entries.add(CborObject.CborMap.build(m));
        }
        replica.store().setMeta(key, new CborObject.CborList(entries).serialize());
    }

    /** The announced addresses fit to publish: public IP literals, without DNS names, relay circuits or a peerid. */
    public static List<Address> directAddresses(List<String> announced) {
        List<Address> res = new ArrayList<>();
        for (String raw : announced) {
            Optional<String> direct = direct(raw.trim());
            if (direct.isEmpty())
                continue;
            try {
                Address a = new Address(Address.LIBP2P, new MultiAddress(direct.get()).getBytes());
                if (! res.contains(a))
                    res.add(a);
            } catch (RuntimeException e) {
                LOG.info("Not publishing unparseable address " + raw);
            }
        }
        return res;
    }

    static Optional<String> direct(String multiaddr) {
        String[] parts = multiaddr.split("/");
        if (parts.length < 3 || ! parts[0].isEmpty() || ! (parts[1].equals("ip4") || parts[1].equals("ip6")))
            return Optional.empty();
        for (String part : parts)
            if (NAMED.contains(part))
                return Optional.empty();
        if (! isPublic(parts[1], parts[2]))
            return Optional.empty();
        int peer = multiaddr.indexOf("/p2p/");
        return Optional.of(peer < 0 ? multiaddr : multiaddr.substring(0, peer));
    }

    static boolean isPublic(String protocol, String host) {
        boolean literal = protocol.equals("ip4") ? host.matches("[0-9.]+") : host.matches("[0-9a-fA-F:.]+") && host.contains(":");
        if (! literal)
            return false; // getByName would look a name up in DNS
        InetAddress ip;
        try {
            ip = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return false;
        }
        if ((ip instanceof Inet4Address) != protocol.equals("ip4"))
            return false;
        if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                || ip.isSiteLocalAddress() || ip.isMulticastAddress())
            return false;
        byte[] b = ip.getAddress();
        boolean carrierNat = b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
        boolean uniqueLocal = b.length == 16 && (b[0] & 0xfe) == 0xfc;
        return ! carrierNat && ! uniqueLocal;
    }

    /** Signs and stores a new record if the one held differs from what this server would publish. The counter is
     *  the time, so a server that lost its store still publishes above its old record.
     */
    public static Optional<ServerAddresses> publish(RoutingReplica replica,
                                                    SigningKeyPair serverKey,
                                                    List<Address> addrs,
                                                    ServerAddresses.Mode mode,
                                                    LongSupplier clock) {
        return publish(replica, serverKey, addrs, List.of(), false, mode, clock);
    }

    public static Optional<ServerAddresses> publish(RoutingReplica replica,
                                                    SigningKeyPair serverKey,
                                                    List<Address> addrs,
                                                    List<Multihash> relays,
                                                    boolean relaying,
                                                    ServerAddresses.Mode mode,
                                                    LongSupplier clock) {
        Subject self = Subject.server(ServerKeys.peerId(serverKey.publicSigningKey));
        Optional<ServerAddresses> held = replica.server(self).flatMap(ServerLog::addresses);
        PublicSigningKey endpoint = serverKey.publicSigningKey;
        if (held.isPresent() && sameContent(held.get(), endpoint, addrs, relays, relaying, mode))
            return Optional.empty();
        long counter = Math.max(clock.getAsLong(), held.map(h -> h.counter + 1).orElse(0L));
        ServerAddresses record = ServerAddresses.build(serverKey, endpoint, Optional.empty(), addrs, relays, mode,
                relaying, counter).join();
        Outcome res = replica.add(self, record);
        if (res != Outcome.Added)
            throw new IllegalStateException("Couldn't add our own server addresses: " + res);
        LOG.info("Published this server's addresses to the routing log: " + addrs.size() + " direct, " +
                relays.size() + " relays" + (relaying ? ", relaying" : ""));
        return Optional.of(record);
    }

    private static boolean sameContent(ServerAddresses held, PublicSigningKey endpoint, List<Address> addrs,
                                       List<Multihash> relays, boolean relaying, ServerAddresses.Mode mode) {
        return held.endpoint.equals(endpoint)
                && held.kem.isEmpty()
                && held.addrs.equals(addrs)
                && held.relays.stream().map(Multihash::bareMultihash).collect(Collectors.toList())
                        .equals(relays.stream().map(Multihash::bareMultihash).collect(Collectors.toList()))
                && held.mode == mode
                && held.relaying == relaying;
    }
}
