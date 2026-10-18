package peergos.shared.routing;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.util.*;

import java.util.*;

/** What a record is about: an identity, named by the hash of its first key, or a server, named by its peerid. */
public final class Subject implements Cborable, Comparable<Subject> {
    public static final int IDENTITY = 0, SERVER = 1;

    public final int kind;
    public final Multihash id;

    public Subject(int kind, Multihash id) {
        if (kind != IDENTITY && kind != SERVER)
            throw new IllegalStateException("Unknown subject kind " + kind);
        this.kind = kind;
        this.id = kind == IDENTITY && ! (id instanceof PublicKeyHash) ? new PublicKeyHash((Cid) id) : id;
    }

    public static Subject identity(PublicKeyHash identity) {
        return new Subject(IDENTITY, identity);
    }

    public static Subject server(Multihash peerId) {
        return new Subject(SERVER, peerId.bareMultihash());
    }

    public boolean isIdentity() {
        return kind == IDENTITY;
    }

    public boolean accepts(RecordType type) {
        return type.identity == isIdentity();
    }

    public byte[] idBytes() {
        return id.toBytes();
    }

    @Override
    public int compareTo(Subject o) {
        if (kind != o.kind)
            return Integer.compare(kind, o.kind);
        return ArrayOps.compare(idBytes(), o.idBytes());
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> m = new TreeMap<>();
        m.put("k", new CborObject.CborLong(kind));
        m.put("i", new CborObject.CborByteArray(idBytes()));
        return CborObject.CborMap.build(m);
    }

    public static Subject fromCbor(Cborable cbor) {
        CborObject.CborMap m = RoutingCbor.map(cbor, "Subject");
        int kind = (int) m.getLong("k");
        byte[] id = m.getByteArray("i");
        return new Subject(kind, kind == IDENTITY ? new PublicKeyHash(Cid.cast(id)) : Multihash.decode(id));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (! (o instanceof Subject)) return false;
        Subject s = (Subject) o;
        return kind == s.kind && Arrays.equals(idBytes(), s.idBytes());
    }

    @Override
    public int hashCode() {
        return 31 * kind + Arrays.hashCode(idBytes());
    }

    @Override
    public String toString() {
        return (isIdentity() ? "identity " : "server ") + id;
    }
}
