package peergos.shared.routing;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.stream.*;

/** The records held for one identity or server, and the current state they give (remove-pki §4.2).
 *
 *  Immutable. Apart from the first inception, which is first-seen by design, the records kept and the state are
 *  the same whatever order records arrive in, provided records that come back {@link Outcome#Unconnected} are
 *  offered again once the records they depend on have arrived.
 */
public final class RecordLog<I extends SignedRecord & Establishment, R extends SignedRecord & Rotation, U extends SignedRecord & Update> {
    /** Enough to prove a conflict; a stolen committed key can sign any number. */
    public static final int MAX_ROTATIONS_PER_KEY = 2;

    public final Optional<Verified<I>> inception;
    /** At most one rejected inception, the lowest by hash: proof that the first key signed two. */
    public final Optional<Verified<I>> evidence;
    private final Map<ByteArrayWrapper, List<Verified<R>>> rotations;
    public final Optional<Verified<U>> update;
    public final Chain chain;

    public static final class Chain {
        public final List<PublicSigningKey> keys;
        public final List<Establishment> establishments;
        /** Two different rotations from one key: only possible with a stolen committed key. */
        public final boolean frozen;

        Chain(List<PublicSigningKey> keys, List<Establishment> establishments, boolean frozen) {
            this.keys = Collections.unmodifiableList(keys);
            this.establishments = Collections.unmodifiableList(establishments);
            this.frozen = frozen;
        }

        public Optional<PublicSigningKey> current() {
            return keys.isEmpty() ? Optional.empty() : Optional.of(keys.get(keys.size() - 1));
        }

        public Optional<Establishment> currentEstablishment() {
            return establishments.isEmpty() ? Optional.empty() : Optional.of(establishments.get(establishments.size() - 1));
        }

        int indexOf(PublicSigningKey key) {
            ByteArrayWrapper k = wrap(key);
            for (int i = 0; i < keys.size(); i++)
                if (wrap(keys.get(i)).equals(k))
                    return i;
            return -1;
        }
    }

    public static final class Change<L> {
        public final L log;
        public final Outcome outcome;

        public Change(L log, Outcome outcome) {
            this.log = log;
            this.outcome = outcome;
        }
    }

    private RecordLog(Optional<Verified<I>> inception,
                      Optional<Verified<I>> evidence,
                      Map<ByteArrayWrapper, List<Verified<R>>> rotations,
                      Optional<Verified<U>> update) {
        this.inception = inception;
        this.evidence = evidence;
        this.chain = follow(inception, rotations);
        this.rotations = prune(rotations, chain);
        this.update = update.filter(u -> ! chain.frozen && chain.current().map(c -> sameKey(c, u.record.key())).orElse(false));
    }

    public static <I extends SignedRecord & Establishment, R extends SignedRecord & Rotation, U extends SignedRecord & Update>
    RecordLog<I, R, U> empty() {
        return new RecordLog<>(Optional.empty(), Optional.empty(), Collections.emptyMap(), Optional.empty());
    }

    public List<Verified<R>> rotationsFrom(PublicSigningKey key) {
        return rotations.getOrDefault(wrap(key), Collections.emptyList());
    }

    /** Every record kept, in a canonical order: what a replica stores for this key. */
    public List<Verified<? extends SignedRecord>> records() {
        List<Verified<? extends SignedRecord>> res = new ArrayList<>();
        inception.ifPresent(res::add);
        evidence.ifPresent(res::add);
        for (PublicSigningKey key : chain.keys)
            res.addAll(rotationsFrom(key));
        update.ifPresent(res::add);
        return res;
    }

    public Change<RecordLog<I, R, U>> addInception(Verified<I> v) {
        if (inception.isEmpty())
            return added(new RecordLog<>(Optional.of(v), evidence, rotations, update));
        if (inception.get().equals(v) || evidence.map(e -> e.equals(v)).orElse(false))
            return unchanged(Outcome.Duplicate);
        if (evidence.isPresent() && Verified.byHash(evidence.get(), v) <= 0)
            return unchanged(Outcome.Ignored);
        return added(new RecordLog<>(inception, Optional.of(v), rotations, update));
    }

    public Change<RecordLog<I, R, U>> addRotation(Verified<R> v) {
        if (inception.isEmpty())
            return unchanged(Outcome.Unconnected);
        int i = chain.indexOf(v.record.from());
        if (i < 0)
            return unchanged(Outcome.Unconnected);
        boolean fromCurrent = i == chain.keys.size() - 1;
        if (fromCurrent && chain.frozen)
            return unchanged(Outcome.Ignored);
        if (! Arrays.equals(v.keyHash, chain.establishments.get(i).next()))
            return unchanged(Outcome.Rejected);
        List<Verified<R>> existing = rotationsFrom(v.record.from());
        if (existing.contains(v))
            return unchanged(Outcome.Duplicate);
        List<Verified<R>> kept = Stream.concat(existing.stream(), Stream.of(v))
                .sorted(Verified::byHash)
                .limit(MAX_ROTATIONS_PER_KEY)
                .collect(Collectors.toList());
        if (! kept.contains(v))
            return unchanged(Outcome.Ignored);
        Map<ByteArrayWrapper, List<Verified<R>>> updated = new HashMap<>(rotations);
        updated.put(wrap(v.record.from()), kept);
        return added(new RecordLog<>(inception, evidence, updated, update));
    }

    public Change<RecordLog<I, R, U>> addUpdate(Verified<U> v) {
        if (inception.isEmpty())
            return unchanged(Outcome.Unconnected);
        int i = chain.indexOf(v.record.key());
        if (i < 0)
            return unchanged(Outcome.Unconnected);
        if (chain.frozen || i < chain.keys.size() - 1)
            return unchanged(Outcome.Ignored);
        if (update.isPresent()) {
            Verified<U> current = update.get();
            if (current.equals(v))
                return unchanged(Outcome.Duplicate);
            if (! supersedes(v, current))
                return unchanged(Outcome.Ignored);
        }
        return added(new RecordLog<>(inception, evidence, rotations, Optional.of(v)));
    }

    /** A higher counter wins, and on equal counters the lower hash, so replicas agree whatever arrived first. */
    static boolean supersedes(Verified<? extends Update> a, Verified<? extends Update> b) {
        long ac = ((Update) a.record).counter(), bc = ((Update) b.record).counter();
        if (ac != bc)
            return ac > bc;
        return Verified.byHash(a, b) < 0;
    }

    private Change<RecordLog<I, R, U>> added(RecordLog<I, R, U> log) {
        return new Change<>(log, Outcome.Added);
    }

    private Change<RecordLog<I, R, U>> unchanged(Outcome outcome) {
        return new Change<>(this, outcome);
    }

    private static <I extends SignedRecord & Establishment, R extends SignedRecord & Rotation>
    Chain follow(Optional<Verified<I>> inception, Map<ByteArrayWrapper, List<Verified<R>>> rotations) {
        List<PublicSigningKey> keys = new ArrayList<>();
        List<Establishment> establishments = new ArrayList<>();
        if (inception.isEmpty())
            return new Chain(keys, establishments, false);
        I first = inception.get().record;
        keys.add(first.key());
        establishments.add(first);
        Set<ByteArrayWrapper> seen = new HashSet<>();
        seen.add(wrap(first.key()));
        while (true) {
            List<Verified<R>> from = rotations.getOrDefault(wrap(keys.get(keys.size() - 1)), Collections.emptyList());
            if (from.isEmpty())
                return new Chain(keys, establishments, false);
            R next = from.get(0).record;
            if (from.size() > 1) {
                keys.add(next.key());
                establishments.add(next);
                return new Chain(keys, establishments, true);
            }
            if (! seen.add(wrap(next.key())))
                return new Chain(keys, establishments, false);
            keys.add(next.key());
            establishments.add(next);
        }
    }

    /** Keeps only rotations from keys the chain has rotated away from, so the records kept depend on the chain
     *  alone, not on the order they arrived in.
     */
    private static <R extends SignedRecord & Rotation>
    Map<ByteArrayWrapper, List<Verified<R>>> prune(Map<ByteArrayWrapper, List<Verified<R>>> rotations, Chain chain) {
        Map<ByteArrayWrapper, List<Verified<R>>> res = new HashMap<>();
        for (int i = 0; i < chain.keys.size() - 1; i++) {
            ByteArrayWrapper k = wrap(chain.keys.get(i));
            List<Verified<R>> from = rotations.get(k);
            if (from != null)
                res.put(k, from);
        }
        return Collections.unmodifiableMap(res);
    }

    static ByteArrayWrapper wrap(PublicSigningKey key) {
        return new ByteArrayWrapper(key.serialize());
    }

    static boolean sameKey(PublicSigningKey a, PublicSigningKey b) {
        return wrap(a).equals(wrap(b));
    }
}
