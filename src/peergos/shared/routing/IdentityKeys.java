package peergos.shared.routing;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** The key an identity signs with now. An identity's hash contains the key it began with, which is the key to verify
 *  against until it rotates; after that the hash is only a name, and the old key, possibly stolen, must not verify
 *  anything (remove-pki §3.1).
 */
public interface IdentityKeys {

    /** The current key if this identity has rotated away from the key inside its hash. */
    CompletableFuture<Optional<PublicSigningKey>> rotated(PublicKeyHash identity);

    IdentityKeys INLINE = identity -> Futures.of(Optional.empty());

    /** Only a rotation in the log from the key the hash names counts, so a legacy identity whose PKI key changed
     *  still verifies its old data with the key in each hash. A frozen identity verifies nothing.
     */
    static Optional<PublicSigningKey> rotated(PublicKeyHash identity, IdentityLog log) {
        if (log.inception().isEmpty())
            return Optional.empty();
        List<PublicSigningKey> keys = log.records.chain.keys;
        if (! ContentAddressedStorage.hashKey(keys.get(0)).equals(identity))
            return Optional.empty();
        if (log.isFrozen())
            throw new Frozen(identity);
        if (keys.size() < 2)
            return Optional.empty();
        return log.records.chain.current();
    }

    class Frozen extends IllegalStateException {
        public Frozen(PublicKeyHash identity) {
            super("Identity " + identity + " signed two rotations and is frozen");
        }
    }

    /** The same from an identity's records, checked here rather than trusted from whoever supplied them. */
    static CompletableFuture<Optional<PublicSigningKey>> rotated(PublicKeyHash identity, List<SignedRecord> records, Hasher hasher) {
        Work unchecked = (subject, proof) -> Futures.of(true);
        RecordValidator validator = new RecordValidator(hasher, unchecked, unchecked);
        List<CompletableFuture<Verified<SignedRecord>>> verified = records.stream()
                .filter(r -> r instanceof IdentityInception || r instanceof IdentityRotation)
                .sorted(Comparator.comparing(r -> r instanceof IdentityInception ? 0 : 1))
                .map(validator::verify)
                .collect(Collectors.toList());
        return Futures.combineAllInOrder(verified).thenApply(vs -> {
            IdentityLog log = IdentityLog.empty(identity);
            for (Verified<SignedRecord> v : vs)
                log = log.add(v).log;
            return rotated(identity, log);
        });
    }

    /** For a client: from the records its home server holds, verified here, and cached. When the server can't answer,
     *  the last key verified is used. With none, the lookup fails: falling back to the key in the hash would let anyone
     *  who can make the server refuse, by using up its limits, have a stolen key from before a rotation accepted.
     */
    class Verifying implements IdentityKeys {
        private static final long TTL_MILLIS = 10 * 60_000;
        /** A failure is remembered this long, so callers don't each ask again a server that is refusing. */
        private static final long FAILURE_MILLIS = 10_000;

        private final IdentityRouting routing;
        private final Hasher hasher;
        private final LongSupplier clock;
        private final Map<PublicKeyHash, Pair<Long, Optional<PublicSigningKey>>> cache = new LRUCache<>(1_000);
        private final Map<PublicKeyHash, Pair<Long, Throwable>> failures = new LRUCache<>(1_000);

        public Verifying(IdentityRouting routing, Hasher hasher) {
            this(routing, hasher, System::currentTimeMillis);
        }

        public Verifying(IdentityRouting routing, Hasher hasher, LongSupplier clock) {
            this.routing = routing;
            this.hasher = hasher;
            this.clock = clock;
        }

        @Override
        public CompletableFuture<Optional<PublicSigningKey>> rotated(PublicKeyHash identity) {
            Pair<Long, Optional<PublicSigningKey>> cached;
            Pair<Long, Throwable> failed;
            synchronized (cache) {
                cached = cache.get(identity);
                failed = failures.get(identity);
            }
            long now = clock.getAsLong();
            if (cached != null && now - cached.left < TTL_MILLIS)
                return Futures.of(cached.right);
            if (failed != null && now - failed.left < FAILURE_MILLIS)
                return cached != null ? Futures.of(cached.right) : Futures.errored(failed.right);
            // a home server without the routing log answers with no records, so the key in the hash is used
            return routing.records(identity)
                    .thenCompose(records -> IdentityKeys.rotated(identity, records, hasher))
                    .thenApply(res -> {
                        synchronized (cache) {
                            cache.put(identity, new Pair<>(now, res));
                            failures.remove(identity);
                        }
                        return res;
                    })
                    .exceptionally(t -> {
                        for (Throwable c = t; c != null; c = c.getCause())
                            if (c instanceof Frozen)
                                throw (Frozen) c;
                        synchronized (cache) {
                            failures.put(identity, new Pair<>(now, t));
                        }
                        if (cached != null)
                            return cached.right;
                        throw new IllegalStateException("Couldn't get the current key of " + identity, t);
                    });
        }

        public void invalidate(PublicKeyHash identity) {
            synchronized (cache) {
                cache.remove(identity);
                failures.remove(identity);
            }
        }
    }

    /** For a server, whose routing log starts after its storage. */
    class Deferred implements IdentityKeys {
        private volatile IdentityKeys target = INLINE;

        public void set(IdentityKeys target) {
            this.target = target;
        }

        @Override
        public CompletableFuture<Optional<PublicSigningKey>> rotated(PublicKeyHash identity) {
            return target.rotated(identity);
        }
    }
}
