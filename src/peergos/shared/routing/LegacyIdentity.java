package peergos.shared.routing;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;

import java.util.*;
import java.util.stream.*;

/** An identity from the PKI's final state (remove-pki §4.1): its chain of owner keys, each signed over by the one
 *  before, with no commitments, and the providers its last claim names. It is named by its first owner, and
 *  every later owner resolves to it. Its last owner writes the first commitment, as an inception for that key.
 */
public final class LegacyIdentity implements Cborable {
    public final String username;
    public final List<PublicKeyHash> owners;
    public final PublicSigningKey currentKey;
    public final List<Multihash> providers;

    public LegacyIdentity(String username, List<PublicKeyHash> owners, PublicSigningKey currentKey, List<Multihash> providers) {
        if (owners.isEmpty())
            throw new IllegalStateException("A legacy identity has at least one owner");
        this.username = username;
        this.owners = Collections.unmodifiableList(new ArrayList<>(owners));
        this.currentKey = currentKey;
        this.providers = Collections.unmodifiableList(providers.stream()
                .map(Multihash::bareMultihash)
                .collect(Collectors.toList()));
    }

    public PublicKeyHash identity() {
        return owners.get(0);
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> m = new TreeMap<>();
        m.put("u", new CborObject.CborString(username));
        m.put("o", new CborObject.CborList(owners));
        m.put("k", currentKey.toCbor());
        m.put("p", RoutingCbor.peerIds(providers));
        return CborObject.CborMap.build(m);
    }

    public static LegacyIdentity fromCbor(Cborable cbor) {
        CborObject.CborMap m = RoutingCbor.map(cbor, "LegacyIdentity");
        return new LegacyIdentity(m.getString("u"),
                m.getList("o", PublicKeyHash::fromCbor),
                PublicSigningKey.fromCbor(m.get("k")),
                RoutingCbor.peerIds(m.get("p")));
    }
}
