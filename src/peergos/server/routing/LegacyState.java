package peergos.server.routing;

import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.*;

import java.util.*;
import java.util.stream.*;

/** The PKI's final state, which legacy records are valid only inside (remove-pki §4.1): the PKI's last
 *  WriterData, whose champ every server already mirrors, and the IPNS rotation of every server peerid a storage
 *  provider claim names. Its hash ships with the software.
 */
public class LegacyState implements Cborable {
    public final Cid pki;
    public final List<ServerRotation> servers;

    /** An old peerid's move to a new one, with the IPNS record that said so where it is still known. */
    public static class ServerRotation implements Cborable {
        public final Multihash from, to;
        public final Optional<IpnsEntry> record;

        public ServerRotation(Multihash from, Multihash to, Optional<IpnsEntry> record) {
            this.from = from.bareMultihash();
            this.to = to.bareMultihash();
            this.record = record;
        }

        @Override
        public CborObject toCbor() {
            Map<String, Cborable> m = new TreeMap<>();
            m.put("f", new CborObject.CborByteArray(from.toBytes()));
            m.put("t", new CborObject.CborByteArray(to.toBytes()));
            record.ifPresent(r -> {
                m.put("s", new CborObject.CborByteArray(r.signature));
                m.put("d", new CborObject.CborByteArray(r.data));
            });
            return CborObject.CborMap.build(m);
        }

        public static ServerRotation fromCbor(Cborable cbor) {
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            Optional<byte[]> sig = m.getOptionalByteArray("s");
            return new ServerRotation(Multihash.decode(m.getByteArray("f")),
                    Multihash.decode(m.getByteArray("t")),
                    sig.map(s -> new IpnsEntry(s, m.getByteArray("d"))));
        }
    }

    public LegacyState(Cid pki, List<ServerRotation> servers) {
        this.pki = pki;
        this.servers = servers.stream()
                .sorted(Comparator.comparing(r -> new peergos.shared.util.ByteArrayWrapper(r.from.toBytes())))
                .collect(Collectors.toList());
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> m = new TreeMap<>();
        m.put("p", new CborObject.CborMerkleLink(pki));
        m.put("s", new CborObject.CborList(servers));
        return CborObject.CborMap.build(m);
    }

    public static LegacyState fromCbor(Cborable cbor) {
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new LegacyState((Cid) ((CborObject.CborMerkleLink) m.get("p")).target,
                m.getList("s", ServerRotation::fromCbor));
    }
}
