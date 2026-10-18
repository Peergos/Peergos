package peergos.server.routing;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.routing.*;

import java.math.*;
import java.util.*;

/** Range-based set reconciliation over (subject, state hash) pairs (remove-pki §4.3): two replicas compare a
 *  fingerprint of each range of subject ids, split the ranges that differ, and list the small ones outright.
 */
public class Reconciliation {
    private static final BigInteger MOD = BigInteger.ONE.shiftLeft(256);

    /** Subject ids after one id, and up to another if given; the whole of a kind from the empty id. */
    public static final class Range implements Cborable {
        public static final Range ALL = new Range(new byte[0], Optional.empty());

        public final byte[] after;
        public final Optional<byte[]> upTo;

        public Range(byte[] after, Optional<byte[]> upTo) {
            this.after = after;
            this.upTo = upTo;
        }

        @Override
        public CborObject toCbor() {
            Map<String, Cborable> m = new TreeMap<>();
            m.put("a", new CborObject.CborByteArray(after));
            upTo.ifPresent(u -> m.put("u", new CborObject.CborByteArray(u)));
            return CborObject.CborMap.build(m);
        }

        public static Range fromCbor(Cborable cbor) {
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            return new Range(m.getByteArray("a"), m.getOptionalByteArray("u"));
        }
    }

    public static final class Summary implements Cborable {
        public final long count;
        public final byte[] fingerprint;

        public Summary(long count, byte[] fingerprint) {
            this.count = count;
            this.fingerprint = fingerprint;
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
            return new Summary(m.getLong("c"), m.getByteArray("f"));
        }
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

    /** The sum mod 2^256 of the hash of each pair, so it does not depend on order. */
    public static class Accumulator {
        private final Hasher hasher;
        private BigInteger sum = BigInteger.ZERO;
        private long count = 0;

        public Accumulator(Hasher hasher) {
            this.hasher = hasher;
        }

        public void add(JdbcRoutingStore.State s) {
            byte[] id = s.subject.idBytes();
            byte[] both = new byte[id.length + s.hash.length];
            System.arraycopy(id, 0, both, 0, id.length);
            System.arraycopy(s.hash, 0, both, id.length, s.hash.length);
            sum = sum.add(new BigInteger(1, hasher.sha256(both).join())).mod(MOD);
            count++;
        }

        public Summary summary() {
            byte[] raw = sum.toByteArray();
            byte[] fp = new byte[32];
            int n = Math.min(raw.length, 32);
            System.arraycopy(raw, raw.length - n, fp, 32 - n, n);
            return new Summary(count, fp);
        }
    }
}
