package peergos.server.space;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** In memory view of the space caps on writing spaces, and of the usage of each capped subtree.
 *
 *  Caps and ancestors are loaded lazily and expire, so changes made by another server sharing the database are
 *  picked up. Subtree usage is loaded lazily from the usage store, kept up to date from usage changes, and reloaded
 *  periodically or when ownership changes inside the subtree, so any drift corrects itself.
 */
public class WriterQuotas implements WriterUsageStore.UsageListener {
    private static final long RELOAD_MILLIS = 600_000;
    private static final long RELOAD_QUOTA_MILLIS = 60_000;

    private record Timed<V>(V value, long loadedAt) {
        boolean isFresh(long now, long maxAge) {
            return now - loadedAt < maxAge;
        }
    }

    private final WriterQuotaStore store;
    private final LRUCache<PublicKeyHash, Timed<Optional<Long>>> quotas = new LRUCache<>(10_000);
    private final LRUCache<PublicKeyHash, Timed<List<PublicKeyHash>>> ancestors = new LRUCache<>(10_000);
    private final Map<PublicKeyHash, Timed<UserUsage>> capUsage = new ConcurrentHashMap<>();
    private long ancestorsGeneration = 0;

    public WriterQuotas(WriterQuotaStore store) {
        this.store = store;
    }

    /** Check that a writer quota request was signed by the owner, for a writing space of theirs other than the identity.
     *  The request's time is not checked here, so that caps can be carried over in a migration.
     */
    public static WriterQuotaRequest verify(byte[] signedRequest,
                                            PublicKeyHash owner,
                                            String username,
                                            WriterUsageStore store,
                                            ContentAddressedStorage dht) {
        PublicSigningKey ownerKey = dht.getSigningKey(owner, owner).join()
                .orElseThrow(() -> new IllegalStateException("Couldn't retrieve owner key!"));
        WriterQuotaRequest req = WriterQuotaRequest.fromCbor(CborObject.fromByteArray(ownerKey.unsignMessage(signedRequest).join()));
        if (! req.owner.equals(owner))
            throw new IllegalStateException("Writer quota request is for a different owner!");
        if (req.writer.equals(owner))
            throw new IllegalStateException("The identity can't have a writer quota, it is covered by the user's quota");
        if (! username.equals(store.getOwner(req.writer)))
            throw new IllegalStateException("Writer is not owned by " + username);
        return req;
    }

    public Optional<Long> getQuota(PublicKeyHash writer) {
        long now = System.currentTimeMillis();
        synchronized (quotas) {
            Timed<Optional<Long>> cached = quotas.get(writer);
            if (cached != null && cached.isFresh(now, RELOAD_QUOTA_MILLIS))
                return cached.value;
        }
        Optional<Long> loaded = store.getWriterQuota(writer);
        synchronized (quotas) {
            Timed<Optional<Long>> cached = quotas.get(writer);
            // a local change made while we were loading wins
            if (cached == null || cached.loadedAt <= now)
                quotas.put(writer, new Timed<>(loaded, now));
        }
        return loaded;
    }

    @Override
    public void writerQuotaChanged(PublicKeyHash writer, Optional<Long> quota) {
        synchronized (quotas) {
            quotas.put(writer, new Timed<>(quota, System.currentTimeMillis()));
        }
        capUsage.remove(writer);
    }

    private List<PublicKeyHash> getAncestors(PublicKeyHash writer) {
        long now = System.currentTimeMillis();
        long generation;
        synchronized (ancestors) {
            Timed<List<PublicKeyHash>> cached = ancestors.get(writer);
            if (cached != null && cached.isFresh(now, RELOAD_QUOTA_MILLIS))
                return cached.value;
            generation = ancestorsGeneration;
        }
        List<PublicKeyHash> loaded = Collections.unmodifiableList(store.getAncestors(writer));
        synchronized (ancestors) {
            if (generation == ancestorsGeneration)
                ancestors.put(writer, new Timed<>(loaded, now));
        }
        return loaded;
    }

    /**
     * @return the capped keys among the writer and the keys that own it
     */
    public List<PublicKeyHash> getCaps(PublicKeyHash writer) {
        List<PublicKeyHash> caps = new ArrayList<>();
        if (getQuota(writer).isPresent())
            caps.add(writer);
        for (PublicKeyHash ancestor : getAncestors(writer)) {
            if (getQuota(ancestor).isPresent())
                caps.add(ancestor);
        }
        return caps;
    }

    public UserUsage getUsage(PublicKeyHash cap) {
        long now = System.currentTimeMillis();
        Timed<UserUsage> current = capUsage.get(cap);
        if (current != null && current.isFresh(now, RELOAD_MILLIS))
            return current.value;
        UserUsage loaded = new UserUsage(store.getSubtreeUsage(cap));
        capUsage.put(cap, new Timed<>(loaded, now));
        return loaded;
    }

    @Override
    public void usageChanged(PublicKeyHash writer, long delta, boolean ownedKeysChanged) {
        if (ownedKeysChanged) {
            // the writer may have left one capped subtree and joined another
            synchronized (ancestors) {
                ancestors.clear();
                ancestorsGeneration++;
            }
            capUsage.clear();
            return;
        }
        if (capUsage.isEmpty())
            return;
        for (PublicKeyHash cap : getCaps(writer)) {
            Timed<UserUsage> current = capUsage.get(cap);
            if (current != null)
                current.value.confirmUsage(writer, delta);
        }
    }
}
