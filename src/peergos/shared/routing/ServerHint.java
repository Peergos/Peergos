package peergos.shared.routing;

import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.*;

import java.util.*;

/** Where to reach a server the routing log may not hold yet, as a link carries it (remove-pki §11): its peerid and
 *  IP-literal, relay, onion or garlic addresses. Never a DNS name, whose lookup would reveal who is about to be
 *  contacted.
 */
public class ServerHint implements Cborable {
    public static final int MAX_ADDRESSES = 8, MAX_ADDRESS_LENGTH = 256;
    private static final Set<String> FIRST = new HashSet<>(Arrays.asList("ip4", "ip6", "onion3", "garlic32", "garlic64"));
    private static final Set<String> WITH_VALUE = new HashSet<>(Arrays.asList("ip4", "ip6", "tcp", "udp", "p2p", "onion3", "garlic32", "garlic64"));
    private static final Set<String> FLAGS = new HashSet<>(Arrays.asList("quic", "quic-v1", "p2p-circuit"));

    public final Multihash peerId;
    public final List<String> addrs;

    public ServerHint(Multihash peerId, List<String> addrs) {
        if (addrs.size() > MAX_ADDRESSES)
            throw new IllegalStateException("Too many addresses in a hint");
        for (String addr : addrs)
            if (! isAllowed(addr))
                throw new IllegalStateException("Not an address a hint may carry: " + addr);
        this.peerId = peerId.bareMultihash();
        this.addrs = Collections.unmodifiableList(new ArrayList<>(addrs));
    }

    public static boolean isAllowed(String multiaddr) {
        if (multiaddr.length() > MAX_ADDRESS_LENGTH || ! multiaddr.startsWith("/"))
            return false;
        String[] parts = multiaddr.substring(1).split("/");
        if (parts.length < 2 || ! FIRST.contains(parts[0]))
            return false;
        for (int i = 0; i < parts.length; i++) {
            String protocol = parts[i];
            if (FLAGS.contains(protocol))
                continue;
            if (! WITH_VALUE.contains(protocol) || i + 1 >= parts.length || ! isValue(protocol, parts[i + 1]))
                return false;
            i++;
        }
        return true;
    }

    private static boolean isValue(String protocol, String value) {
        switch (protocol) {
            case "ip4": {
                String[] octets = value.split("\\.");
                if (octets.length != 4)
                    return false;
                for (String o : octets)
                    if (! o.matches("[0-9]{1,3}") || Integer.parseInt(o) > 255)
                        return false;
                return true;
            }
            case "ip6":
                return value.contains(":") && value.matches("[0-9a-fA-F:.]+");
            case "tcp":
            case "udp":
                return value.matches("[0-9]{1,5}") && Integer.parseInt(value) > 0 && Integer.parseInt(value) < 65536;
            case "onion3":
                return value.matches("[a-z2-7]{56}:[0-9]{1,5}");
            default:
                return value.matches("[A-Za-z0-9]+");
        }
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> m = new TreeMap<>();
        m.put("p", new CborObject.CborByteArray(peerId.toBytes()));
        m.put("a", new CborObject.CborList(addrs.stream().map(CborObject.CborString::new).collect(java.util.stream.Collectors.toList())));
        return CborObject.CborMap.build(m);
    }

    public static ServerHint fromCbor(Cborable cbor) {
        CborObject.CborMap m = RoutingCbor.map(cbor, "ServerHint");
        return new ServerHint(Multihash.decode(m.getByteArray("p")),
                m.getList("a", c -> ((CborObject.CborString) c).value));
    }
}
