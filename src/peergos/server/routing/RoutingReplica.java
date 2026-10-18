package peergos.server.routing;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.routing.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.function.*;

/** This node's copy of the routing log: records are checked, applied to their subject's log and stored. Whether a
 *  record may be passed on, its proof of work and admission, is a separate question, answered by
 *  {@link #isProven}.
 */
public class RoutingReplica {
    private static final int LOCKS = 64;

    private final JdbcRoutingStore store;
    private final RecordValidator validator;
    private final Work identityWork, serverWork;
    private final Map<Subject, SubjectLog> cache = Collections.synchronizedMap(new LRUCache<>(10_000));
    private final Object[] locks = new Object[LOCKS];
    private final List<BiConsumer<Subject, SubjectLog>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public RoutingReplica(JdbcRoutingStore store, RecordValidator validator, Work identityWork, Work serverWork) {
        this.store = store;
        this.validator = validator;
        this.identityWork = identityWork;
        this.serverWork = serverWork;
        for (int i = 0; i < LOCKS; i++)
            locks[i] = new Object();
    }

    public JdbcRoutingStore store() {
        return store;
    }

    /** Called with a subject's new log whenever a record changes it. */
    public void onChange(BiConsumer<Subject, SubjectLog> listener) {
        listeners.add(listener);
    }

    public SubjectLog log(Subject s) {
        SubjectLog cached = cache.get(s);
        if (cached != null)
            return cached;
        List<Verified<? extends SignedRecord>> records = new ArrayList<>();
        for (SignedRecord r : store.get(s))
            records.add(validator.stored(r).join());
        SubjectLog log = SubjectLog.build(s, records);
        cache.put(s, log);
        return log;
    }

    public Optional<IdentityLog> identity(Subject s) {
        SubjectLog log = log(s);
        return log.inception().isPresent() ? Optional.of((IdentityLog) log) : Optional.empty();
    }

    public Optional<ServerLog> server(Subject s) {
        SubjectLog log = log(s);
        return log.inception().isPresent() ? Optional.of((ServerLog) log) : Optional.empty();
    }

    /** Checks a record and applies it. An inception already held is replaced by a copy whose proof is better: a
     *  proof where there was none, or an admission signed by a later key of the admitting server.
     */
    public Outcome add(Subject s, SignedRecord record) {
        if (! s.accepts(RecordType.of(record)))
            return Outcome.Rejected;
        Verified<? extends SignedRecord> v;
        try {
            v = validator.verify(record).join();
        } catch (RuntimeException e) {
            return Outcome.Rejected;
        }
        synchronized (lock(s)) {
            SubjectLog log = log(s);
            RecordLog.Change<? extends SubjectLog> c = log.add(v);
            SubjectLog updated = c.log;
            Outcome outcome = c.outcome;
            if (outcome == Outcome.Duplicate && isInception(record)
                    && log.inception().map(i -> i.equals(v)).orElse(false)
                    && proofRank(log.inception().get().record) < proofRank(record)) {
                updated = log.withInception(v);
                outcome = Outcome.Added;
            }
            if (outcome == Outcome.Added)
                save(s, updated);
            return outcome;
        }
    }

    private static boolean isInception(SignedRecord r) {
        return r instanceof IdentityInception || r instanceof ServerInception;
    }

    private void save(Subject s, SubjectLog log) {
        List<SignedRecord> records = new ArrayList<>();
        for (Verified<? extends SignedRecord> v : log.held())
            records.add(v.record);
        store.put(s, records, stateHash(records));
        cache.put(s, log);
        for (BiConsumer<Subject, SubjectLog> l : listeners)
            l.accept(s, log);
    }

    /** What reconciliation compares: the hash of every record held, proofs included, in canonical order. */
    public byte[] stateHash(List<? extends SignedRecord> records) {
        return validator.hasher().sha256(JdbcRoutingStore.encode(records)).join();
    }

    public byte[] stateHash(Subject s) {
        List<SignedRecord> records = new ArrayList<>();
        for (Verified<? extends SignedRecord> v : log(s).held())
            records.add(v.record);
        return stateHash(records);
    }

    private Object lock(Subject s) {
        return locks[Math.floorMod(s.hashCode(), LOCKS)];
    }

    /** -1 without a valid proof; for an identity, otherwise the index in the admitting server's chain of the key
     *  that signed the admission, so a re-signed admission by a later key outranks the old one.
     */
    private int proofRank(SignedRecord inception) {
        if (inception instanceof ServerInception) {
            ServerInception si = (ServerInception) inception;
            return si.proof.isPresent() && serverWork.verify(si.server.serialize(), si.proof.get()).join() ? 0 : -1;
        }
        IdentityInception ii = (IdentityInception) inception;
        return admittingKeyIndex(ii);
    }

    private int admittingKeyIndex(IdentityInception ii) {
        if (ii.admission.isEmpty())
            return -1;
        Optional<ServerLog> admitter = server(Subject.server(ii.admittingServer()));
        if (admitter.isEmpty())
            return -1;
        List<PublicSigningKey> keys = admitter.get().records.chain.keys;
        for (int i = keys.size() - 1; i >= 0; i--)
            if (ii.admission.get().verify(ii.identity, keys.get(i), identityWork).join())
                return i;
        return -1;
    }

    /** Whether a subject's inception may be passed on: a server's carries valid proof of work; an identity's an
     *  admission by the admitting server's current key, or by any key in its chain for a record taken in by
     *  catch-up (remove-pki §4.1).
     */
    public boolean isProven(Subject s, boolean catchUp) {
        SubjectLog log = log(s);
        if (log.inception().isEmpty())
            return false;
        SignedRecord inception = log.inception().get().record;
        if (inception instanceof ServerInception)
            return proofRank(inception) >= 0;
        IdentityInception ii = (IdentityInception) inception;
        int i = admittingKeyIndex(ii);
        if (i < 0)
            return false;
        if (catchUp)
            return true;
        ServerLog admitter = server(Subject.server(ii.admittingServer())).get();
        return i == admitter.records.chain.keys.size() - 1;
    }
}
