package peergos.shared.routing;

import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.*;

import java.util.*;

/** A server that rotated by IPNS before the routing log (remove-pki §4.1): its peerids in order, pinned in the
 *  PKI's final state. It is named by its first peerid; its inception is for the last.
 */
public final class LegacyServer implements Cborable {
    public final List<Multihash> peerIds;

    public LegacyServer(List<Multihash> peerIds) {
        if (peerIds.size() < 2)
            throw new IllegalStateException("A legacy server rotation has at least two peerids");
        this.peerIds = Collections.unmodifiableList(new ArrayList<>(peerIds));
    }

    public Multihash first() {
        return peerIds.get(0);
    }

    public Multihash current() {
        return peerIds.get(peerIds.size() - 1);
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> m = new TreeMap<>();
        m.put("i", RoutingCbor.peerIds(peerIds));
        return CborObject.CborMap.build(m);
    }

    public static LegacyServer fromCbor(Cborable cbor) {
        return new LegacyServer(RoutingCbor.peerIds(RoutingCbor.map(cbor, "LegacyServer").get("i")));
    }
}
