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
 *  Subtree usage is loaded lazily from the usage store, kept up to date from usage changes, and reloaded
 *  periodically or when ownership changes inside the subtree, so any drift corrects itself.
 */
public class WriterQuotas implements WriterUsageStore.UsageListener {
    private static final long RELOAD_MILLIS = 600_000;

    private record CapUsage(UserUsage usage, long loadedAt) {}

    private final WriterQuotaStore store;
    private final Map<PublicKeyHash, Long> quotas;
    private final Map<PublicKeyHash, CapUsage> capUsage = new ConcurrentHashMap<>();
    private final LRUCache<PublicKeyHash, List<PublicKeyHash>> capsCache = new LRUCache<>(10_000);
    private long capsGeneration = 0;

    public WriterQuotas(WriterQuotaStore store) {
        this.store = store;
        this.quotas = new ConcurrentHashMap<>(store.getAllWriterQuotas());
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
        return Optional.ofNullable(quotas.get(writer));
    }

    @Override
    public void writerQuotaChanged(PublicKeyHash writer, Optional<Long> quota) {
        if (quota.isPresent())
            quotas.put(writer, quota.get());
        else
            quotas.remove(writer);
        capUsage.remove(writer);
        invalidateCaps();
    }

    /**
     * @return the capped keys among the writer and the keys that own it
     */
    public List<PublicKeyHash> getCaps(PublicKeyHash writer) {
        if (quotas.isEmpty())
            return Collections.emptyList();
        long generation;
        synchronized (capsCache) {
            List<PublicKeyHash> cached = capsCache.get(writer);
            if (cached != null)
                return cached;
            generation = capsGeneration;
        }
        List<PublicKeyHash> caps = new ArrayList<>();
        if (quotas.containsKey(writer))
            caps.add(writer);
        for (PublicKeyHash ancestor : store.getAncestors(writer)) {
            if (quotas.containsKey(ancestor))
                caps.add(ancestor);
        }
        List<PublicKeyHash> res = Collections.unmodifiableList(caps);
        synchronized (capsCache) {
            if (generation == capsGeneration)
                capsCache.put(writer, res);
        }
        return res;
    }

    public UserUsage getUsage(PublicKeyHash cap) {
        long now = System.currentTimeMillis();
        CapUsage current = capUsage.get(cap);
        if (current != null && now - current.loadedAt < RELOAD_MILLIS)
            return current.usage;
        UserUsage loaded = new UserUsage(store.getSubtreeUsage(cap));
        capUsage.put(cap, new CapUsage(loaded, now));
        return loaded;
    }

    private void invalidateCaps() {
        synchronized (capsCache) {
            capsCache.clear();
            capsGeneration++;
        }
    }

    @Override
    public void usageChanged(PublicKeyHash writer, long delta, boolean ownedKeysChanged) {
        if (quotas.isEmpty())
            return;
        List<PublicKeyHash> caps = getCaps(writer);
        for (PublicKeyHash cap : caps) {
            CapUsage current = capUsage.get(cap);
            if (current != null)
                current.usage.confirmUsage(writer, delta);
        }
        if (ownedKeysChanged) {
            for (PublicKeyHash cap : caps)
                capUsage.remove(cap);
            invalidateCaps();
        }
    }
}
