package peergos.server.routing;

import io.libp2p.core.PeerId;
import io.libp2p.core.multiformats.Multiaddr;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.util.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

/** This server's own hint, for links to carry, and the hints a hosted user's client hands in when it opens a link
 *  to a server the log doesn't hold yet (remove-pki §5 step 4, §10.10, §11).
 */
public class Hints {
    private static final Logger LOG = Logging.LOG();
    public static final int PER_USER_PER_HOUR = 30;
    private static final long PULL_TIMEOUT_SECONDS = 15;

    private final HintedAddressBook book;
    private final RoutingReplica replica;
    private final RoutingNode node;
    private final Multihash self;
    private final List<String> swarm;
    private final Supplier<List<InetAddress>> interfaces;
    private final LRUCache<PublicKeyHash, long[]> used = new LRUCache<>(10_000);

    public Hints(HintedAddressBook book, RoutingNode node, Multihash self, List<String> swarm, Supplier<List<InetAddress>> interfaces) {
        this.book = book;
        this.replica = node.replica();
        this.node = node;
        this.self = self.bareMultihash();
        this.swarm = swarm;
        this.interfaces = interfaces;
        book.setLogged(peer -> logged(replica, peer));
    }

    /** The direct addresses a server signed in the log, then the circuits through the relays it names, when the
     *  peer dialled is that server's endpoint.
     */
    public static List<Multiaddr> logged(RoutingReplica replica, PeerId peer) {
        Multihash id = Multihash.decode(peer.getBytes());
        Optional<ServerLog> log = replica.server(Subject.server(id))
                .filter(l -> l.addresses().map(a -> ServerKeys.peerId(a.endpoint).equals(id)).orElse(false));
        if (log.isEmpty())
            return Collections.emptyList();
        List<Multiaddr> res = log.get().addresses().get().addrs.stream()
                .filter(addr -> addr.transport.equals(Address.LIBP2P))
                .map(addr -> Multiaddr.fromString(new MultiAddress(addr.address).toString()))
                .collect(Collectors.toList());
        res.addAll(Relays.relayed(replica, log.get()));
        return res;
    }

    /** The public addresses this server signed in the log, or failing those its relays', or its LAN addresses, so
     *  two servers on a LAN with no internet can still be introduced.
     */
    public ServerHint own() {
        Optional<ServerLog> log = replica.server(Subject.server(self));
        List<String> published = log.flatMap(ServerLog::addresses)
                .map(a -> a.addrs.stream()
                        .filter(addr -> addr.transport.equals(Address.LIBP2P))
                        .map(addr -> new MultiAddress(addr.address).toString())
                        .filter(ServerHint::isAllowed)
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());
        List<String> relayed = log.map(l -> Relays.relayed(replica, l).stream()
                        .map(Multiaddr::toString)
                        .filter(ServerHint::isAllowed)
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());
        List<String> addrs = ! published.isEmpty() ? published :
                ! relayed.isEmpty() ? relayed :
                lanAddresses(swarm, interfaces.get());
        return new ServerHint(self, addrs.stream().limit(ServerHint.MAX_ADDRESSES).collect(Collectors.toList()));
    }

    public static List<String> lanAddresses(List<String> swarm, List<InetAddress> interfaces) {
        List<InetAddress> lan = interfaces.stream()
                .filter(a -> privateBlock(a).isPresent())
                .collect(Collectors.toList());
        List<String> res = new ArrayList<>();
        for (String listen : swarm) {
            String[] parts = listen.split("/");
            if (parts.length < 3 || ! (parts[1].equals("ip4") || parts[1].equals("ip6")))
                continue;
            String rest = listen.substring(("/" + parts[1] + "/" + parts[2]).length());
            boolean any = parts[2].equals("0.0.0.0") || parts[2].equals("::");
            for (InetAddress a : lan) {
                boolean v4 = a instanceof Inet4Address;
                // a socket bound to :: takes IPv4 too
                boolean matches = any ? (v4 ? true : parts[1].equals("ip6")) : a.getHostAddress().equals(parts[2]);
                if (! matches)
                    continue;
                String addr = "/" + (v4 ? "ip4" : "ip6") + "/" + a.getHostAddress() + rest;
                if (ServerHint.isAllowed(addr) && ! res.contains(addr))
                    res.add(addr);
            }
        }
        return res;
    }

    /** Seeds the address book with the hint's addresses this server may dial, then pulls the hinted server's
     *  records, and the identity's if given, from it, which the address book may already know how to reach.
     *
     * @return the addresses kept
     */
    public List<String> add(PublicKeyHash user, ServerHint hint, Optional<PublicKeyHash> identity) {
        take(user);
        List<InetAddress> local = interfaces.get();
        List<String> kept = hint.addrs.stream()
                .filter(a -> mayDial(a, local))
                .collect(Collectors.toList());
        if (hint.peerId.equals(self))
            return kept;
        if (! kept.isEmpty())
            book.addHint(PeerId.fromBase58(hint.peerId.toBase58()), kept.stream().map(Multiaddr::fromString).collect(Collectors.toList()));
        RoutingPeer from = node.dial(hint.peerId);
        List<Subject> subjects = new ArrayList<>();
        subjects.add(Subject.server(hint.peerId));
        identity.ifPresent(i -> subjects.add(Subject.identity(i)));
        for (Subject s : subjects) {
            try {
                CompletableFuture.supplyAsync(() -> node.pull(from, s))
                        .orTimeout(PULL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .join();
            } catch (Exception e) {
                LOG.log(Level.INFO, "Couldn't pull " + s + " from hinted " + hint.peerId + ": " + e.getMessage());
            }
        }
        return kept;
    }

    private void take(PublicKeyHash user) {
        long now = System.currentTimeMillis();
        synchronized (used) {
            long[] window = used.get(user);
            if (window == null || now - window[0] > 3600_000L) {
                used.put(user, new long[]{now, 1});
                return;
            }
            if (window[1] >= PER_USER_PER_HOUR)
                throw new IllegalStateException("Too many hints, try again later");
            window[1]++;
        }
    }

    /** A hint could aim this server at hosts on its own network, so a private address is dialled only when this
     *  server itself has an address in that private range, as on a LAN with no internet.
     */
    public static boolean mayDial(String addr, List<InetAddress> local) {
        String[] parts = addr.split("/");
        if (parts.length < 3)
            return false;
        if (! (parts[1].equals("ip4") || parts[1].equals("ip6")))
            return true;
        InetAddress ip;
        try {
            ip = InetAddress.getByName(parts[2]); // a literal, which ServerHint checked, so no DNS lookup
        } catch (UnknownHostException e) {
            return false;
        }
        if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress() || ip.isMulticastAddress())
            return false;
        Optional<String> block = privateBlock(ip);
        if (block.isEmpty())
            return true;
        return local.stream().anyMatch(l -> privateBlock(l).equals(block));
    }

    public static Optional<String> privateBlock(InetAddress ip) {
        byte[] b = ip.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if (b0 == 10)
                return Optional.of("10/8");
            if (b0 == 172 && (b1 & 0xf0) == 16)
                return Optional.of("172.16/12");
            if (b0 == 192 && b1 == 168)
                return Optional.of("192.168/16");
            if (b0 == 100 && (b1 & 0xc0) == 64)
                return Optional.of("100.64/10");
            return Optional.empty();
        }
        return (b[0] & 0xfe) == 0xfc ? Optional.of("fc00/7") : Optional.empty();
    }

    public static List<InetAddress> interfaceAddresses() {
        try {
            return Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                    .flatMap(i -> Collections.list(i.getInetAddresses()).stream())
                    .collect(Collectors.toList());
        } catch (SocketException e) {
            return Collections.emptyList();
        }
    }
}
