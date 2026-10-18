package peergos.shared.routing;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** Cuckoo Cycle proof of work (Tromp): find a cycle of {@link #PROOF_SIZE} edges in a random bipartite graph of
 *  2^edgeBits edges whose endpoints are siphashes of the edge index, keyed by the subject and a nonce. Finding one
 *  needs memory proportional to the graph and many hashes per edge; checking one is 2 * PROOF_SIZE hashes.
 *  A random graph holds such a cycle about once in PROOF_SIZE nonces, and difficultyBits more leading zero bits of
 *  the proof's hash multiply the expected cost by 2^difficultyBits. Everything rests on hash functions, so it is
 *  post-quantum (remove-pki §14).
 */
public final class CuckooCycle implements Work {
    public static final int PROOF_SIZE = 42;
    public static final int MAX_EDGE_BITS = 31;
    private static final String KEYS = "peergos/routing/v1/cuckoo-keys";
    private static final String DIFFICULTY = "peergos/routing/v1/cuckoo-difficulty";

    public final int edgeBits, difficultyBits;

    public CuckooCycle(int edgeBits, int difficultyBits) {
        if (edgeBits < 4 || edgeBits > MAX_EDGE_BITS)
            throw new IllegalArgumentException("edgeBits out of range: " + edgeBits);
        if (difficultyBits < 0 || difficultyBits > 64)
            throw new IllegalArgumentException("difficultyBits out of range: " + difficultyBits);
        this.edgeBits = edgeBits;
        this.difficultyBits = difficultyBits;
    }

    public static final class Proof implements Cborable {
        public final int edgeBits;
        public final long nonce;
        public final int[] edges;

        public Proof(int edgeBits, long nonce, int[] edges) {
            this.edgeBits = edgeBits;
            this.nonce = nonce;
            this.edges = edges;
        }

        @Override
        public CborObject toCbor() {
            byte[] e = new byte[edges.length * 4];
            for (int i = 0; i < edges.length; i++) {
                e[4 * i] = (byte) (edges[i] >>> 24);
                e[4 * i + 1] = (byte) (edges[i] >>> 16);
                e[4 * i + 2] = (byte) (edges[i] >>> 8);
                e[4 * i + 3] = (byte) edges[i];
            }
            Map<String, Cborable> m = new TreeMap<>();
            m.put("b", new CborObject.CborLong(edgeBits));
            m.put("n", new CborObject.CborLong(nonce));
            m.put("e", new CborObject.CborByteArray(e));
            return CborObject.CborMap.build(m);
        }

        public static Proof fromCbor(Cborable cbor) {
            CborObject.CborMap m = RoutingCbor.map(cbor, "Cuckoo proof");
            byte[] e = m.getByteArray("e");
            if (e.length != PROOF_SIZE * 4)
                throw new IllegalStateException("A cuckoo proof has " + PROOF_SIZE + " edges");
            int[] edges = new int[PROOF_SIZE];
            for (int i = 0; i < PROOF_SIZE; i++)
                edges[i] = ((e[4 * i] & 0xff) << 24) | ((e[4 * i + 1] & 0xff) << 16) | ((e[4 * i + 2] & 0xff) << 8) | (e[4 * i + 3] & 0xff);
            return new Proof((int) m.getLong("b"), m.getLong("n"), edges);
        }
    }

    @Override
    public CompletableFuture<Boolean> verify(byte[] subject, byte[] proof) {
        try {
            return Futures.of(check(subject, Proof.fromCbor(CborObject.fromByteArray(proof))));
        } catch (RuntimeException e) {
            return Futures.of(false);
        }
    }

    public boolean check(byte[] subject, Proof proof) {
        if (proof.edgeBits != edgeBits || proof.edges.length != PROOF_SIZE)
            return false;
        long[] keys = keys(subject, proof.nonce, edgeBits);
        return isCycle(keys, edgeBits, proof.edges) && meetsDifficulty(subject, proof);
    }

    public boolean meetsDifficulty(byte[] subject, Proof proof) {
        byte[] hash = Blake3.hash(Signing.message(DIFFICULTY, ArrayOps.concat(subject, proof.serialize())));
        return ProofOfWork.satisfiesDifficulty(difficultyBits, hash);
    }

    /** True iff the edges, in increasing order, form a single cycle that alternates between the two sides. */
    public static boolean isCycle(long[] keys, int edgeBits, int[] edges) {
        int n = edges.length;
        long size = 1L << edgeBits;
        long[] u = new long[n], v = new long[n];
        for (int i = 0; i < n; i++) {
            if (edges[i] < 0 || edges[i] >= size || (i > 0 && edges[i] <= edges[i - 1]))
                return false;
            u[i] = node(keys, edges[i], 0, edgeBits);
            v[i] = node(keys, edges[i], 1, edgeBits);
        }
        boolean[] visited = new boolean[n];
        int current = 0, length = 0, side = 1;
        do {
            if (visited[current])
                return false;
            visited[current] = true;
            long[] nodes = side == 0 ? u : v;
            int next = -1;
            for (int j = 0; j < n; j++) {
                if (j == current || nodes[j] != nodes[current])
                    continue;
                if (next >= 0)
                    return false;
                next = j;
            }
            if (next < 0)
                return false;
            current = next;
            side ^= 1;
            length++;
        } while (current != 0);
        return length == n;
    }

    public static long[] keys(byte[] subject, long nonce, int edgeBits) {
        byte[] input = new byte[subject.length + 9];
        System.arraycopy(subject, 0, input, 0, subject.length);
        for (int i = 0; i < 8; i++)
            input[subject.length + i] = (byte) (nonce >>> (56 - 8 * i));
        input[subject.length + 8] = (byte) edgeBits;
        byte[] seed = Blake3.hash(Signing.message(KEYS, input));
        long[] keys = new long[4];
        for (int k = 0; k < 4; k++)
            for (int i = 7; i >= 0; i--)
                keys[k] = (keys[k] << 8) | (seed[8 * k + i] & 0xff);
        return keys;
    }

    /** The endpoint of an edge on one side of the graph. */
    public static long node(long[] keys, long edge, int side, int edgeBits) {
        return sipHash24(keys, 2 * edge + side) & ((1L << edgeBits) - 1);
    }

    /** SipHash-2-4 of one 64-bit word, with the whole 256-bit state taken from the keys, as Cuckoo Cycle uses it. */
    public static long sipHash24(long[] keys, long nonce) {
        long v0 = keys[0], v1 = keys[1], v2 = keys[2], v3 = keys[3] ^ nonce;
        for (int r = 0; r < 2; r++) {
            v0 += v1; v1 = rotl(v1, 13); v1 ^= v0; v0 = rotl(v0, 32);
            v2 += v3; v3 = rotl(v3, 16); v3 ^= v2;
            v0 += v3; v3 = rotl(v3, 21); v3 ^= v0;
            v2 += v1; v1 = rotl(v1, 17); v1 ^= v2; v2 = rotl(v2, 32);
        }
        v0 ^= nonce;
        v2 ^= 0xff;
        for (int r = 0; r < 4; r++) {
            v0 += v1; v1 = rotl(v1, 13); v1 ^= v0; v0 = rotl(v0, 32);
            v2 += v3; v3 = rotl(v3, 16); v3 ^= v2;
            v0 += v3; v3 = rotl(v3, 21); v3 ^= v0;
            v2 += v1; v1 = rotl(v1, 17); v1 ^= v2; v2 = rotl(v2, 32);
        }
        return v0 ^ v1 ^ v2 ^ v3;
    }

    private static long rotl(long x, int b) {
        return (x << b) | (x >>> (64 - b));
    }
}
