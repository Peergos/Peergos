package peergos.server.routing;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.routing.*;

import java.math.*;
import java.util.*;

/** Set reconciliation over (subject, state hash) pairs (remove-pki §4.3): two replicas compare a fingerprint of each
 *  range of subjects, descend into the ranges that differ, and list the small ones outright.
 *
 *  Subjects are ordered by their slot, the first bytes of the hash of their id, and a range is a prefix of the slot.
 *  So every replica splits at the same places, and keeps each range's fingerprint up to date as records arrive
 *  rather than reading the range to answer.
 */
public class Reconciliation {
    private static final BigInteger MOD = BigInteger.ONE.shiftLeft(256);
    /** Bytes of slot, and so levels of ranges below the whole. */
    public static final int MAX_DEPTH = 3;
    public static final int FANOUT = 256;

    /** The hash of the id rather than the id, so that subjects spread evenly whatever their ids' encoding. */
    public static int slot(byte[] id) {
        byte[] h = Hash.sha256(id);
        return ((h[0] & 0xff) << 16) | ((h[1] & 0xff) << 8) | (h[2] & 0xff);
    }

    /** The subjects whose slot starts with a prefix of depth bytes; the whole of a kind at depth 0. */
    public static final class Range implements Cborable {
        public static final Range ALL = new Range(0, 0);

        public final int depth, prefix;

        public Range(int depth, int prefix) {
            if (depth < 0 || depth > MAX_DEPTH || prefix < 0 || prefix >= 1 << (8 * depth))
                throw new IllegalStateException("Invalid range " + depth + "/" + prefix);
            this.depth = depth;
            this.prefix = prefix;
        }

        public int from() {
            return prefix << (8 * (MAX_DEPTH - depth));
        }

        public int to() {
            return from() + (1 << (8 * (MAX_DEPTH - depth))) - 1;
        }

        public boolean contains(int slot) {
            return slot >= from() && slot <= to();
        }

        public boolean contains(byte[] id) {
            return contains(slot(id));
        }

        public Range child(int i) {
            return new Range(depth + 1, (prefix << 8) | i);
        }

        public boolean isParentOf(Range child) {
            return child.depth == depth + 1 && child.prefix >> 8 == prefix;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Range && depth == ((Range) o).depth && prefix == ((Range) o).prefix;
        }

        @Override
        public int hashCode() {
            return 31 * depth + prefix;
        }

        @Override
        public String toString() {
            return depth + "/" + prefix;
        }

        @Override
        public CborObject toCbor() {
            Map<String, Cborable> m = new TreeMap<>();
            m.put("d", new CborObject.CborLong(depth));
            m.put("p", new CborObject.CborLong(prefix));
            return CborObject.CborMap.build(m);
        }

        public static Range fromCbor(Cborable cbor) {
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            return new Range((int) m.getLong("d"), (int) m.getLong("p"));
        }
    }

    /** A place in the order of subjects, by slot and then id: the start of a slot, or just after a subject. */
    public static final class Cursor implements Cborable {
        public static final Cursor START = new Cursor(0, new byte[0]);

        public final int slot;
        public final byte[] id;

        public Cursor(int slot, byte[] id) {
            if (slot < 0 || slot >= 1 << (8 * MAX_DEPTH))
                throw new IllegalStateException("Invalid slot " + slot);
            this.slot = slot;
            this.id = id;
        }

        public static Cursor at(int slot) {
            return new Cursor(slot, new byte[0]);
        }

        public static Cursor after(Subject s) {
            byte[] id = s.idBytes();
            return new Cursor(slot(id), id);
        }

        /** Whether a subject comes after this place. */
        public boolean isBefore(Subject s) {
            byte[] other = s.idBytes();
            int otherSlot = slot(other);
            return otherSlot != slot ? otherSlot > slot : Subject.compareBytes(other, id) > 0;
        }

        @Override
        public CborObject toCbor() {
            Map<String, Cborable> m = new TreeMap<>();
            m.put("s", new CborObject.CborLong(slot));
            m.put("i", new CborObject.CborByteArray(id));
            return CborObject.CborMap.build(m);
        }

        public static Cursor fromCbor(Cborable cbor) {
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            return new Cursor((int) m.getLong("s"), m.getByteArray("i"));
        }
    }

    /** How many subjects a range holds, and the sum mod 2^256 of the hash of each (subject, state hash) pair, which
     *  doesn't depend on order and changes by one subtraction and one addition when a subject's state does.
     */
    public static final class Summary implements Cborable {
        public static final Summary EMPTY = new Summary(0, new byte[32]);

        public final long count;
        public final byte[] fingerprint;

        public Summary(long count, byte[] fingerprint) {
            this.count = count;
            this.fingerprint = fingerprint;
        }

        public Summary plus(JdbcRoutingStore.State s) {
            return new Summary(count + 1, bytes(new BigInteger(1, fingerprint).add(item(s.subject.idBytes(), s.hash)).mod(MOD)));
        }

        public Summary minus(JdbcRoutingStore.State s) {
            return new Summary(count - 1, bytes(new BigInteger(1, fingerprint).subtract(item(s.subject.idBytes(), s.hash)).mod(MOD)));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Summary && count == ((Summary) o).count && Arrays.equals(fingerprint, ((Summary) o).fingerprint);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(fingerprint);
        }

        @Override
        public CborObject toCbor() {
            Map<String, Cborable> m = new TreeMap<>();
            m.put("c", new CborObject.CborLong(count));
            m.put("f", new CborObject.CborByteArray(fingerprint));
            return CborObject.CborMap.build(m);
        }

        public static Summary fromCbor(Cborable cbor) {
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            byte[] fingerprint = m.getByteArray("f");
            if (fingerprint.length != 32)
                throw new IllegalStateException("A fingerprint is 32 bytes");
            return new Summary(m.getLong("c"), fingerprint);
        }
    }

    /** The summaries of a range's children that hold anything, by the last byte of their prefix. */
    public static CborObject.CborList children(Map<Integer, Summary> children) {
        List<Cborable> res = new ArrayList<>();
        for (Map.Entry<Integer, Summary> e : children.entrySet()) {
            Map<String, Cborable> m = new TreeMap<>();
            m.put("i", new CborObject.CborLong(e.getKey()));
            m.put("s", e.getValue());
            res.add(CborObject.CborMap.build(m));
        }
        return new CborObject.CborList(res);
    }

    public static Map<Integer, Summary> children(Cborable cbor) {
        Map<Integer, Summary> res = new TreeMap<>();
        for (Cborable c : ((CborObject.CborList) cbor).value) {
            CborObject.CborMap m = (CborObject.CborMap) c;
            int i = (int) m.getLong("i");
            if (i < 0 || i >= FANOUT)
                throw new IllegalStateException("Not a child: " + i);
            res.put(i, Summary.fromCbor(m.get("s")));
        }
        return res;
    }

    public static CborObject.CborMap state(JdbcRoutingStore.State s) {
        Map<String, Cborable> m = new TreeMap<>();
        m.put("s", s.subject);
        m.put("h", new CborObject.CborByteArray(s.hash));
        return CborObject.CborMap.build(m);
    }

    public static JdbcRoutingStore.State state(Cborable cbor) {
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new JdbcRoutingStore.State(Subject.fromCbor(m.get("s")), m.getByteArray("h"));
    }

    /** What one (subject, state hash) pair adds to its ranges' fingerprints. */
    static BigInteger item(byte[] id, byte[] stateHash) {
        byte[] both = new byte[id.length + stateHash.length];
        System.arraycopy(id, 0, both, 0, id.length);
        System.arraycopy(stateHash, 0, both, id.length, stateHash.length);
        return new BigInteger(1, Hash.sha256(both));
    }

    static byte[] bytes(BigInteger sum) {
        byte[] raw = sum.toByteArray();
        byte[] fp = new byte[32];
        int n = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - n, fp, 32 - n, n);
        return fp;
    }

    static BigInteger add(BigInteger sum, BigInteger delta) {
        return sum.add(delta).mod(MOD);
    }
}
