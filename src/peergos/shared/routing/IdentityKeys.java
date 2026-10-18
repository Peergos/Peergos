package peergos.shared.routing;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
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

    /** For a client: from the records its home server holds, verified here, and cached. */
    class Verifying implements IdentityKeys {
        private static final long TTL_MILLIS = 10 * 60_000;

        private final IdentityRouting routing;
        private final Hasher hasher;
        private final Map<PublicKeyHash, Pair<Long, Optional<PublicSigningKey>>> cache = new LRUCache<>(1_000);

        public Verifying(IdentityRouting routing, Hasher hasher) {
            this.routing = routing;
            this.hasher = hasher;
        }

        @Override
        public CompletableFuture<Optional<PublicSigningKey>> rotated(PublicKeyHash identity) {
            Pair<Long, Optional<PublicSigningKey>> cached;
            synchronized (cache) {
                cached = cache.get(identity);
            }
            long now = System.currentTimeMillis();
            if (cached != null && now - cached.left < TTL_MILLIS)
                return Futures.of(cached.right);
            // a home server without the routing log has no records, and one that can't be reached is not taken to
            // mean the identity rotated, so neither failure stops data being read
            return routing.records(identity)
                    .thenCompose(records -> IdentityKeys.rotated(identity, records, hasher))
                    .thenApply(res -> {
                        synchronized (cache) {
                            cache.put(identity, new Pair<>(now, res));
                        }
                        return res;
                    })
                    .exceptionally(t -> {
                        for (Throwable c = t; c != null; c = c.getCause())
                            if (c instanceof Frozen)
                                throw (Frozen) c;
                        return Optional.empty();
                    });
        }

        public void invalidate(PublicKeyHash identity) {
            synchronized (cache) {
                cache.remove(identity);
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
