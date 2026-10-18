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

/** Keeps this server's ServerAddresses current (remove-pki §4.1, §10.11). It lists the IP literals the operator
 *  announces, and the addresses AutoNAT has confirmed dialable once they have stayed confirmed for a week, so an
 *  address that changes more often is never listed; until relays exist none are named. A record is signed only
 *  when its content changes, since peers take a burst of three and then one a month.
 */
public class ServerAddressPublisher {
    private static final Logger LOG = Logging.LOG();
    private static final Set<String> NAMED = Set.of("dns", "dns4", "dns6", "dnsaddr", "p2p-circuit");
    static final String META_KEY = "server-addresses";
    public static final long STABLE_MILLIS = 7 * 24 * 3600_000L;
    /** An address unconfirmed for this long is dropped, and must be confirmed for a week again to return; a shorter
     *  gap, a restart or a failed probe, keeps it listed.
     */
    public static final long FORGET_MILLIS = 24 * 3600_000L;

    private final RoutingReplica replica;
    private final SigningKeyPair serverKey;
    private final List<Address> announced;
    private final Supplier<List<String>> confirmed;
    private final ServerAddresses.Mode mode;
    private final LongSupplier clock;

    public ServerAddressPublisher(RoutingReplica replica,
                                  SigningKeyPair serverKey,
                                  List<String> announced,
                                  Supplier<List<String>> confirmed,
                                  ServerAddresses.Mode mode,
                                  LongSupplier clock) {
        this.replica = replica;
        this.serverKey = serverKey;
        this.announced = directAddresses(announced);
        this.confirmed = confirmed;
        this.mode = mode;
        this.clock = clock;
    }

    /** Records which addresses are confirmed now, and publishes a new record if what should be listed changed. */
    public synchronized Optional<ServerAddresses> refresh() {
        long now = clock.getAsLong();
        Map<Address, Seen> seen = loadSeen();
        for (Address a : directAddresses(confirmed.get())) {
            Seen prior = seen.get(a);
            seen.put(a, new Seen(prior == null ? now : prior.first, now));
        }
        seen.values().removeIf(v -> now - v.last > FORGET_MILLIS);
        saveSeen(seen);

        List<Address> listed = new ArrayList<>(announced);
        seen.entrySet().stream()
                .filter(e -> now - e.getValue().first >= STABLE_MILLIS)
                .map(Map.Entry::getKey)
                .filter(a -> ! listed.contains(a))
                .sorted(Comparator.comparing(a -> ArrayOps.bytesToHex(a.address)))
                .forEach(listed::add);
        return publish(replica, serverKey, listed, mode, clock);
    }

    private static class Seen {
        final long first, last;

        Seen(long first, long last) {
            this.first = first;
            this.last = last;
        }
    }

    private Map<Address, Seen> loadSeen() {
        Map<Address, Seen> res = new HashMap<>();
        replica.store().getMeta(META_KEY).ifPresent(raw -> {
            for (Cborable c : ((CborObject.CborList) CborObject.fromByteArray(raw)).value) {
                CborObject.CborMap m = (CborObject.CborMap) c;
                res.put(Address.fromCbor(m.get("a")), new Seen(m.getLong("f"), m.getLong("l")));
            }
        });
        return res;
    }

    private void saveSeen(Map<Address, Seen> seen) {
        List<Cborable> entries = new ArrayList<>();
        for (Map.Entry<Address, Seen> e : seen.entrySet()) {
            SortedMap<String, Cborable> m = new TreeMap<>();
            m.put("a", e.getKey());
            m.put("f", new CborObject.CborLong(e.getValue().first));
            m.put("l", new CborObject.CborLong(e.getValue().last));
            entries.add(CborObject.CborMap.build(m));
        }
        replica.store().setMeta(META_KEY, new CborObject.CborList(entries).serialize());
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
        Subject self = Subject.server(ServerKeys.peerId(serverKey.publicSigningKey));
        Optional<ServerAddresses> held = replica.server(self).flatMap(ServerLog::addresses);
        PublicSigningKey endpoint = serverKey.publicSigningKey;
        if (held.isPresent() && sameContent(held.get(), endpoint, addrs, mode))
            return Optional.empty();
        long counter = Math.max(clock.getAsLong(), held.map(h -> h.counter + 1).orElse(0L));
        ServerAddresses record = ServerAddresses.build(serverKey, endpoint, Optional.empty(), addrs, List.of(), mode,
                false, counter).join();
        Outcome res = replica.add(self, record);
        if (res != Outcome.Added)
            throw new IllegalStateException("Couldn't add our own server addresses: " + res);
        LOG.info("Published this server's addresses to the routing log: " + addrs.size() + " direct");
        return Optional.of(record);
    }

    private static boolean sameContent(ServerAddresses held, PublicSigningKey endpoint, List<Address> addrs, ServerAddresses.Mode mode) {
        return held.endpoint.equals(endpoint)
                && held.kem.isEmpty()
                && held.addrs.equals(addrs)
                && held.relays.isEmpty()
                && held.mode == mode
                && ! held.relaying;
    }
}
