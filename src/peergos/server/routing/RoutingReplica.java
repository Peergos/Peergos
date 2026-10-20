package peergos.server.routing;

import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;

/** This node's copy of the routing log: records are checked, applied to their subject's log and stored. Whether a
 *  record may be passed on, its proof of work and admission, is a separate question, answered by
 *  {@link #isProven}.
 *
 *  A compact replica (remove-pki §4.7) verifies each record in full as it arrives, then stores it without its
 *  signature, except for this server and the identities it hosts or admitted. To hand out full records it fetches
 *  the signatures from the provider and matches them to the hashes it kept.
 */
public class RoutingReplica {
    private static final Logger LOG = Logging.LOG();
    private static final int LOCKS = 64;
    private static final long FETCH_TIMEOUT_SECONDS = 15;

    private final JdbcRoutingStore store;
    private final RecordValidator validator;
    private final Work identityWork, serverWork;
    private final Map<Subject, SubjectLog> cache = Collections.synchronizedMap(new LRUCache<>(10_000));
    private final Map<Subject, Subject> aliases = Collections.synchronizedMap(new LRUCache<>(10_000));
    private final Object[] locks = new Object[LOCKS];
    private final List<BiConsumer<Subject, SubjectLog>> listeners = new CopyOnWriteArrayList<>();
    private volatile Optional<Multihash> compactFor = Optional.empty();
    private final Map<ByteArrayWrapper, Integer> ranks = Collections.synchronizedMap(new LRUCache<>(100_000));
    /** Full records fetched for a state, so serving it again doesn't ask its provider again. */
    private final Map<ByteArrayWrapper, List<SignedRecord>> filled = Collections.synchronizedMap(new LRUCache<>(1_000));

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

    /** From now on, subjects that are not this server's own are stored without signatures as they change. */
    public void compact(Multihash self) {
        compactFor = Optional.of(self.bareMultihash());
    }

    public boolean isCompact() {
        return compactFor.isPresent();
    }

    /** This server, and the identities it hosts or admitted: it serves their records and re-signs admissions. */
    static boolean keepsFull(Multihash self, SubjectLog log) {
        if (log instanceof ServerLog)
            return ((ServerLog) log).peerId.bareMultihash().equals(self);
        IdentityLog id = (IdentityLog) log;
        return id.providers().stream().anyMatch(p -> p.bareMultihash().equals(self))
                || id.inception().map(i -> i.record.admittingServer().bareMultihash().equals(self)).orElse(false);
    }

    /** Called with a subject's new log whenever a record changes it. */
    public void onChange(BiConsumer<Subject, SubjectLog> listener) {
        listeners.add(listener);
    }

    /** The subject a key or peerid belongs to: itself, or for a later legacy key or peerid, the first. */
    public Subject canonical(Subject s) {
        Subject cached = aliases.get(s);
        if (cached != null)
            return cached;
        Subject res = store.resolveAlias(s).orElse(s);
        aliases.put(s, res);
        return res;
    }

    public SubjectLog log(Subject requested) {
        Subject s = canonical(requested);
        SubjectLog cached = cache.get(s);
        if (cached != null)
            return cached;
        SubjectLog base = store.getLegacy(s)
                .map(c -> s.isIdentity() ?
                        (SubjectLog) IdentityLog.legacy(LegacyIdentity.fromCbor(c)) :
                        ServerLog.legacy(LegacyServer.fromCbor(c)))
                .orElseGet(() -> SubjectLog.empty(s));
        List<Verified<? extends SignedRecord>> records = new ArrayList<>();
        for (JdbcRoutingStore.Stored r : store.get(s)) {
            Verified<? extends SignedRecord> v = validator.stored(r.record).join();
            records.add(r.hash.isPresent() ? withHash(v, r.hash.get()) : v);
        }
        SubjectLog log = SubjectLog.build(base, records);
        cache.put(s, log);
        return log;
    }

    public Optional<IdentityLog> identity(Subject s) {
        SubjectLog log = log(s);
        return log.exists() ? Optional.of((IdentityLog) log) : Optional.empty();
    }

    public Optional<ServerLog> server(Subject s) {
        SubjectLog log = log(s);
        return log.exists() ? Optional.of((ServerLog) log) : Optional.empty();
    }

    /** Called after a legacy import, which changes subjects without a record arriving. */
    public void clearCache() {
        cache.clear();
        aliases.clear();
    }

    /** Checks a record and applies it. An inception already held is replaced by a copy whose proof is better: a
     *  proof where there was none, or an admission signed by a later key of the admitting server.
     */
    /** Decides, once a record is known to be new, whether this node takes it now. */
    public interface Gate {
        Gate OPEN = (s, record, isNewInception) -> true;

        boolean allow(Subject s, SignedRecord record, boolean isNewInception);
    }

    public Outcome add(Subject requested, SignedRecord record) {
        return add(requested, record, Gate.OPEN);
    }

    public Outcome add(Subject requested, SignedRecord record, Gate gate) {
        Subject s = canonical(requested);
        if (! s.accepts(RecordType.of(record)))
            return Outcome.Rejected;
        if (isHeld(s, record))
            return Outcome.Duplicate;
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
                    && proofRank(s, log.inception().get().record) < proofRank(s, record)) {
                updated = log.withInception(v);
                outcome = Outcome.Added;
            }
            if (outcome == Outcome.Added && ! gate.allow(s, record, isInception(record) && log.inception().isEmpty()))
                return Outcome.Limited;
            if (outcome == Outcome.Added)
                save(s, updated);
            return outcome;
        }
    }

    public boolean holdsAll(Subject s, List<? extends SignedRecord> records) {
        return records.stream().allMatch(r -> isHeld(s, r));
    }

    /** Already held with the same proofs, so a record sent again costs a hash, not a signature check. */
    private boolean isHeld(Subject s, SignedRecord record) {
        byte[] hash = hash(record);
        byte[] extras = RecordType.extras(record).serialize();
        return log(s).held().stream()
                .anyMatch(v -> Arrays.equals(v.hash, hash) && Arrays.equals(RecordType.extras(v.record).serialize(), extras));
    }

    /** The subject's log as it would be with these records, without storing them. */
    public SubjectLog preview(Subject requested, List<SignedRecord> records) {
        Subject s = canonical(requested);
        List<Verified<? extends SignedRecord>> verified = new ArrayList<>();
        for (SignedRecord r : records) {
            if (! s.accepts(RecordType.of(r)))
                continue;
            try {
                verified.add(validator.verify(r).join());
            } catch (RuntimeException e) {}
        }
        return SubjectLog.build(log(s), verified);
    }

    static boolean isInception(SignedRecord r) {
        return r instanceof IdentityInception || r instanceof ServerInception;
    }

    private static <R extends SignedRecord> Verified<R> withHash(Verified<R> v, byte[] hash) {
        return new Verified<>(v.record, hash, v.keyHash);
    }

    private void save(Subject s, SubjectLog log) {
        boolean strip = compactFor.map(self -> ! keepsFull(self, log)).orElse(false);
        List<JdbcRoutingStore.Stored> records = new ArrayList<>();
        for (Verified<? extends SignedRecord> v : log.held())
            records.add(strip || v.record.isStripped() ?
                    new JdbcRoutingStore.Stored(RecordType.stripped(v.record), Optional.of(v.hash)) :
                    new JdbcRoutingStore.Stored(v.record, Optional.empty()));
        store.put(s, records, digest(log.held()));
        if (log instanceof ServerLog)
            index(s, (ServerLog) log);
        if (strip)
            cache.remove(s);
        else
            cache.put(s, log);
        for (BiConsumer<Subject, SubjectLog> l : listeners)
            l.accept(s, log);
    }

    /** What reconciliation compares, and a compact replica can compute without signatures: the hash of each record
     *  held, with its proofs, in canonical order.
     */
    /** Of the records as a set: two nodes that saw an identity's two inceptions in opposite orders keep different
     *  ones first, but hold the same records, and must not refetch them from each other every round.
     */
    private byte[] digest(List<Verified<? extends SignedRecord>> held) {
        List<Cborable> entries = new ArrayList<>();
        List<Verified<? extends SignedRecord>> sorted = new ArrayList<>(held);
        sorted.sort((a, b) -> ArrayOps.compare(a.hash, b.hash));
        for (Verified<? extends SignedRecord> v : sorted)
            entries.add(new CborObject.CborList(Arrays.asList(new CborObject.CborByteArray(v.hash), RecordType.extras(v.record))));
        return validator.hasher().sha256(new CborObject.CborList(entries).toByteArray()).join();
    }

    public byte[] stateHash(List<? extends SignedRecord> records) {
        List<Verified<? extends SignedRecord>> held = new ArrayList<>();
        for (SignedRecord r : records)
            held.add(validator.stored(r).join());
        return digest(held);
    }

    public byte[] stateHash(Subject s) {
        return digest(log(s).held());
    }

    /** The hash a record is known by: of its body and signature. */
    public byte[] hash(SignedRecord r) {
        return validator.stored(r).join().hash;
    }

    /** The subject's records with their signatures, fetching those a compact replica dropped from the identity's
     *  providers, or the server itself, and matching them to the hashes kept. Fails if any can't be found.
     */
    public List<SignedRecord> records(Subject requested, Function<Multihash, RoutingPeer> dial) {
        Subject s = canonical(requested);
        SubjectLog log = log(s);
        List<Verified<? extends SignedRecord>> held = log.held();
        Map<ByteArrayWrapper, byte[]> signatures = new HashMap<>();
        for (Verified<? extends SignedRecord> v : held)
            if (v.record.isStripped())
                signatures.put(new ByteArrayWrapper(v.hash), null);
        ByteArrayWrapper state = new ByteArrayWrapper(digest(held));
        if (! signatures.isEmpty()) {
            List<SignedRecord> cached = filled.get(state);
            if (cached != null)
                return cached;
            for (Multihash source : sources(log)) {
                if (compactFor.map(self -> self.equals(source.bareMultihash())).orElse(false))
                    continue;
                try {
                    for (SignedRecord r : dial.apply(source).records(s).get(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        ByteArrayWrapper h = new ByteArrayWrapper(hash(r));
                        if (! r.isStripped() && signatures.containsKey(h))
                            signatures.put(h, r.signature);
                    }
                } catch (Exception e) {
                    LOG.log(Level.FINE, "Couldn't fetch " + s + " from " + source + ": " + e.getMessage());
                }
                if (! signatures.containsValue(null))
                    break;
            }
            if (signatures.containsValue(null))
                throw new IllegalStateException("Couldn't fetch the full records of " + s);
        }
        List<SignedRecord> res = new ArrayList<>();
        for (Verified<? extends SignedRecord> v : held)
            res.add(v.record.isStripped() ?
                    RecordType.withSignature(v.record, signatures.get(new ByteArrayWrapper(v.hash))) :
                    v.record);
        if (! signatures.isEmpty()) {
            filled.put(state, res);
            if (compactFor.map(self -> keepsFull(self, log)).orElse(false))
                restore(s, held, res);
        }
        return res;
    }

    /** One of ours that was stored without signatures, as when a user moves here: keep it in full from now on. */
    private void restore(Subject s, List<Verified<? extends SignedRecord>> held, List<SignedRecord> full) {
        synchronized (lock(s)) {
            if (! Arrays.equals(stateHash(s), digest(held)))
                return;
            List<JdbcRoutingStore.Stored> records = new ArrayList<>();
            for (SignedRecord r : full)
                records.add(new JdbcRoutingStore.Stored(r, Optional.empty()));
            store.put(s, records, digest(held));
            cache.remove(s);
        }
    }

    private void index(Subject s, ServerLog log) {
        Multihash id = s.id.bareMultihash();
        Optional<Multihash> key = log.currentKey().map(k -> ServerKeys.peerId(k).bareMultihash()).filter(p -> ! p.equals(id));
        Optional<Multihash> endpoint = log.addresses().map(a -> ServerKeys.peerId(a.endpoint).bareMultihash())
                .filter(p -> ! p.equals(id) && ! key.equals(Optional.of(p)));
        store.putPeers(s, key, endpoint);
    }

    /** The server a connection authenticated as this peerid belongs to: the one whose current key or endpoint it
     *  is, whether that is its first peerid or a later one. A key it has rotated away from no longer counts.
     */
    public Optional<ServerLog> serverForPeer(Multihash peer) {
        Multihash p = peer.bareMultihash();
        Optional<ServerLog> direct = server(Subject.server(p));
        if (direct.isPresent())
            return direct.filter(log -> isReachedAs(log, p));
        return store.resolvePeer(p).flatMap(this::server).filter(log -> isReachedAs(log, p));
    }

    private static boolean isReachedAs(ServerLog log, Multihash peer) {
        if (log.currentKey().map(k -> ServerKeys.peerId(k).bareMultihash().equals(peer)).orElse(false))
            return true;
        if (log.addresses().map(a -> ServerKeys.peerId(a.endpoint).bareMultihash().equals(peer)).orElse(false))
            return true;
        return log.currentKey().isEmpty() && log.peerId.bareMultihash().equals(peer);
    }

    private static List<Multihash> sources(SubjectLog log) {
        if (log instanceof IdentityLog)
            return ((IdentityLog) log).providers();
        ServerLog server = (ServerLog) log;
        List<Multihash> res = new ArrayList<>();
        server.currentKey().map(ServerKeys::peerId).ifPresent(res::add);
        if (res.stream().noneMatch(p -> p.bareMultihash().equals(server.peerId.bareMultihash())))
            res.add(server.peerId);
        return res;
    }

    private Object lock(Subject s) {
        return locks[Math.floorMod(s.hashCode(), LOCKS)];
    }

    /** -1 without a valid proof; for an identity, otherwise the index in the admitting server's chain of the key
     *  that signed the admission, so a re-signed admission by a later key outranks the old one.
     */
    private int proofRank(Subject s, SignedRecord inception) {
        if (inception instanceof ServerInception) {
            ServerInception si = (ServerInception) inception;
            return ranked(inception.serialize(), () -> si.proof.isPresent() && serverWork.verify(si.server.serialize(), si.proof.get()).join() ? 0 : -1);
        }
        IdentityInception ii = (IdentityInception) inception;
        byte[] admitter = server(Subject.server(ii.admittingServer()))
                .map(log -> new CborObject.CborList(log.records.chain.keys).serialize())
                .orElse(new byte[0]);
        return ranked(ArrayOps.concat(ArrayOps.concat(s.idBytes(), inception.serialize()), admitter), () -> admittingKeyIndex(s, ii));
    }

    /** Checking an admission is a signature check, and it is asked for on every change and every relay decision, so
     *  the answer is kept for as long as the inception, its proofs and the admitting server's chain are unchanged.
     */
    private int ranked(byte[] inputs, java.util.function.IntSupplier rank) {
        ByteArrayWrapper key = new ByteArrayWrapper(validator.hasher().sha256(inputs).join());
        Integer cached = ranks.get(key);
        if (cached != null)
            return cached;
        int res = rank.getAsInt();
        ranks.put(key, res);
        return res;
    }

    /** Whether an inception's proof of work checks, which costs far less than its signatures, so an inception with
     *  none is turned away before they are checked.
     */
    public boolean isWorkDone(Subject s, SignedRecord inception) {
        if (inception instanceof ServerInception) {
            ServerInception si = (ServerInception) inception;
            return si.proof.isPresent() && serverWork.verify(si.server.serialize(), si.proof.get()).join();
        }
        IdentityInception ii = (IdentityInception) inception;
        return ii.admission.isPresent() && admissionWork(canonical(s)).verify(ii.identity.serialize(), ii.admission.get().proof).join();
    }

    /** A legacy identity is already in the pinned state, so its admission needs no proof of work (remove-pki §4.1). */
    private Work admissionWork(Subject s) {
        boolean legacy = identity(s).map(IdentityLog::isLegacy).orElse(false);
        return legacy ? (subject, proof) -> CompletableFuture.completedFuture(true) : identityWork;
    }

    private int admittingKeyIndex(Subject s, IdentityInception ii) {
        if (ii.admission.isEmpty())
            return -1;
        Work work = admissionWork(s);
        Optional<ServerLog> admitter = server(Subject.server(ii.admittingServer()));
        if (admitter.isEmpty())
            return -1;
        List<PublicSigningKey> keys = admitter.get().records.chain.keys;
        for (int i = keys.size() - 1; i >= 0; i--)
            if (ii.admission.get().verify(ii.identity, keys.get(i), work).join())
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
        return isProven(s, log.inception().get().record, catchUp);
    }

    /** The same, for an inception that may not be held yet. */
    public boolean isProven(Subject s, SignedRecord inception, boolean catchUp) {
        if (inception instanceof ServerInception)
            return proofRank(s, inception) >= 0;
        IdentityInception ii = (IdentityInception) inception;
        int i = admittingKeyIndex(s, ii);
        if (i < 0)
            return false;
        if (catchUp)
            return true;
        ServerLog admitter = server(Subject.server(ii.admittingServer())).get();
        return i == admitter.records.chain.keys.size() - 1;
    }
}
