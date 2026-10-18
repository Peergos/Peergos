package peergos.server.routing;

import peergos.shared.routing.*;

import java.util.*;

/** Finds Cuckoo Cycle proofs. Edges whose endpoint has degree one cannot be on a cycle, so they are trimmed,
 *  alternating sides, with bitmaps over the whole graph until few edges are left and on an explicit list after
 *  that; the cycles are then found among the survivors by following paths in a forest, as Tromp's solvers do.
 */
public class CuckooSolver {
    /** Switch from bitmaps to an explicit edge list below this fraction of the graph. */
    private static final int LIST_FRACTION = 64;
    private static final int MAX_PATH = 8192;

    private final CuckooCycle params;

    public CuckooSolver(CuckooCycle params) {
        this.params = params;
    }

    /** Tries nonces from start until one gives a proof meeting the difficulty, for at most attempts nonces. */
    public Optional<CuckooCycle.Proof> solve(byte[] subject, long start, long attempts) {
        for (long nonce = start; nonce < start + attempts; nonce++) {
            Optional<CuckooCycle.Proof> res = solveNonce(subject, nonce);
            if (res.isPresent())
                return res;
        }
        return Optional.empty();
    }

    /** Searches disjoint nonces on several threads, each with its own graph, and returns the first proof found. */
    public Optional<CuckooCycle.Proof> solveParallel(byte[] subject, int threads, long attemptsPerThread) {
        java.util.concurrent.atomic.AtomicReference<CuckooCycle.Proof> found = new java.util.concurrent.atomic.AtomicReference<>();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            long first = t;
            Thread worker = new Thread(() -> {
                for (long i = 0; i < attemptsPerThread && found.get() == null; i++) {
                    Optional<CuckooCycle.Proof> res = solveNonce(subject, first + i * threads);
                    if (res.isPresent())
                        found.compareAndSet(null, res.get());
                }
            }, "Cuckoo solver " + t);
            worker.setDaemon(true);
            worker.start();
            workers.add(worker);
        }
        for (Thread worker : workers) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.ofNullable(found.get());
    }

    public Optional<CuckooCycle.Proof> solveNonce(byte[] subject, long nonce) {
        int edgeBits = params.edgeBits;
        long[] keys = CuckooCycle.keys(subject, nonce, edgeBits);
        for (int[] cycle : cycles(keys, edgeBits)) {
            CuckooCycle.Proof proof = new CuckooCycle.Proof(edgeBits, nonce, cycle);
            if (params.meetsDifficulty(subject, proof))
                return Optional.of(proof);
        }
        return Optional.empty();
    }

    /** Every cycle of the proof length in the graph, as sorted edge indices. */
    public static List<int[]> cycles(long[] keys, int edgeBits) {
        int[] survivors = trim(keys, edgeBits);
        int n = survivors.length;
        long[] u = new long[n], v = new long[n];
        for (int i = 0; i < n; i++) {
            u[i] = CuckooCycle.node(keys, survivors[i], 0, edgeBits) << 1;
            v[i] = (CuckooCycle.node(keys, survivors[i], 1, edgeBits) << 1) | 1;
        }
        Map<Long, Long> forest = new HashMap<>();
        List<int[]> res = new ArrayList<>();
        long[] us = new long[MAX_PATH], vs = new long[MAX_PATH];
        for (int i = 0; i < n; i++) {
            int nu = path(forest, u[i], us), nv = path(forest, v[i], vs);
            if (nu < 0 || nv < 0)
                continue;
            if (us[nu] == vs[nv]) {
                int min = Math.min(nu, nv);
                nu -= min;
                nv -= min;
                while (us[nu] != vs[nv]) {
                    nu++;
                    nv++;
                }
                if (nu + nv + 1 == CuckooCycle.PROOF_SIZE)
                    recover(us, nu, vs, nv, u, v, survivors).ifPresent(res::add);
            } else if (nu < nv) {
                while (nu-- > 0)
                    forest.put(us[nu + 1], us[nu]);
                forest.put(u[i], v[i]);
            } else {
                while (nv-- > 0)
                    forest.put(vs[nv + 1], vs[nv]);
                forest.put(v[i], u[i]);
            }
        }
        return res;
    }

    private static int path(Map<Long, Long> forest, long start, long[] path) {
        int len = 0;
        path[0] = start;
        Long next = forest.get(start);
        while (next != null) {
            if (++len >= MAX_PATH)
                return -1;
            path[len] = next;
            next = forest.get(next);
        }
        return len;
    }

    private static Optional<int[]> recover(long[] us, int nu, long[] vs, int nv, long[] u, long[] v, int[] survivors) {
        Set<Long> pairs = new HashSet<>();
        pairs.add(pair(us[0], vs[0]));
        for (int j = 0; j < nu; j++)
            pairs.add(pair(us[j], us[j + 1]));
        for (int j = 0; j < nv; j++)
            pairs.add(pair(vs[j], vs[j + 1]));
        int[] edges = new int[CuckooCycle.PROOF_SIZE];
        int found = 0;
        for (int i = 0; i < survivors.length && found < edges.length; i++)
            if (pairs.remove(pair(u[i], v[i])))
                edges[found++] = survivors[i];
        if (found != edges.length)
            return Optional.empty();
        Arrays.sort(edges);
        return Optional.of(edges);
    }

    /** An edge as the pair of its nodes, which are below 2^32 since edgeBits is at most 31. */
    private static long pair(long a, long b) {
        long even = (a & 1) == 0 ? a : b, odd = (a & 1) == 0 ? b : a;
        return (even << 32) | odd;
    }

    /** The edges left once no endpoint has degree one. */
    static int[] trim(long[] keys, int edgeBits) {
        long size = 1L << edgeBits;
        int words = (int) Math.max(1, size / 64);
        long[] alive = new long[words], once = new long[words], twice = new long[words];
        Arrays.fill(alive, -1L);
        if (size < 64)
            alive[0] = (1L << size) - 1;
        long count = size;
        int side = 0;
        while (count > size / LIST_FRACTION) {
            Arrays.fill(once, 0);
            Arrays.fill(twice, 0);
            for (int w = 0; w < words; w++)
                for (long bits = alive[w]; bits != 0; bits &= bits - 1) {
                    long node = CuckooCycle.node(keys, 64L * w + Long.numberOfTrailingZeros(bits), side, edgeBits);
                    int nw = (int) (node >>> 6);
                    long bit = 1L << (node & 63);
                    if ((once[nw] & bit) != 0)
                        twice[nw] |= bit;
                    else
                        once[nw] |= bit;
                }
            long before = count;
            for (int w = 0; w < words; w++)
                for (long bits = alive[w]; bits != 0; bits &= bits - 1) {
                    int b = Long.numberOfTrailingZeros(bits);
                    long node = CuckooCycle.node(keys, 64L * w + b, side, edgeBits);
                    if ((twice[(int) (node >>> 6)] & (1L << (node & 63))) == 0) {
                        alive[w] &= ~(1L << b);
                        count--;
                    }
                }
            side ^= 1;
            if (count == before && side == 0)
                break;
        }
        int[] edges = new int[(int) count];
        int k = 0;
        for (int w = 0; w < words; w++)
            for (long bits = alive[w]; bits != 0; bits &= bits - 1)
                edges[k++] = 64 * w + Long.numberOfTrailingZeros(bits);
        return trimList(keys, edgeBits, edges);
    }

    private static int[] trimList(long[] keys, int edgeBits, int[] edges) {
        int n = edges.length;
        long[][] nodes = new long[2][n];
        for (int i = 0; i < n; i++) {
            nodes[0][i] = CuckooCycle.node(keys, edges[i], 0, edgeBits);
            nodes[1][i] = CuckooCycle.node(keys, edges[i], 1, edgeBits);
        }
        boolean[] dead = new boolean[n];
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int side = 0; side < 2; side++) {
                Map<Long, Integer> degree = new HashMap<>();
                for (int i = 0; i < n; i++)
                    if (! dead[i])
                        degree.merge(nodes[side][i], 1, Integer::sum);
                for (int i = 0; i < n; i++)
                    if (! dead[i] && degree.get(nodes[side][i]) == 1) {
                        dead[i] = true;
                        changed = true;
                    }
            }
        }
        int alive = 0;
        for (int i = 0; i < n; i++)
            if (! dead[i])
                edges[alive++] = edges[i];
        return Arrays.copyOf(edges, alive);
    }
}
