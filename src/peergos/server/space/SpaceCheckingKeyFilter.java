package peergos.server.space;

import java.util.concurrent.atomic.*;
import java.util.logging.*;

import peergos.server.storage.*;
import peergos.server.storage.admin.*;
import peergos.server.util.*;

import peergos.server.corenode.*;
import peergos.server.mutable.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/** This class checks whether a given user is using more storage space than their quota
 *
 */
public class SpaceCheckingKeyFilter implements SpaceUsage {
    private static final Logger LOG = Logging.LOG();
    private static final long USAGE_TOLERANCE = 1024 * 1024;
    private final CoreNode core;
    private final MutablePointers mutable;
    private final DeletableContentAddressedStorage dht;
    private final Hasher hasher;
    private final QuotaAdmin quotaAdmin;
    private final UsageStore usageStore;
    private final WriterQuotas writerQuotas;
    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private final BlockingQueue<MutableEvent> mutableQueue = new ArrayBlockingQueue<>(1000);
    private final long quotaUploadLimitSeconds;
    private final Map<String, SlidingWindowCounter> writeLimiter = new ConcurrentHashMap<>();
    private final Cid ourId;
    private static final long HEAL_RETRY_INTERVAL_MILLIS = 60_000;
    private final ConcurrentHashMap<PublicKeyHash, Object> healLocks = new ConcurrentHashMap<>();
    private final Map<PublicKeyHash, Long> lastFailedHeals = new ConcurrentHashMap<>();
    private final LRUCache<PublicKeyHash, Boolean> nonLocalOwners = new LRUCache<>(1_000);

    public SpaceCheckingKeyFilter(CoreNode core,
                                  MutablePointers mutable,
                                  DeletableContentAddressedStorage dht,
                                  Hasher hasher,
                                  QuotaAdmin quotaAdmin,
                                  UsageStore usageStore,
                                  long quotaUploadLimitSeconds) {
        this.core = core;
        this.mutable = mutable;
        this.dht = dht;
        this.hasher = hasher;
        this.quotaAdmin = quotaAdmin;
        this.usageStore = usageStore;
        this.quotaUploadLimitSeconds = quotaUploadLimitSeconds;
        this.ourId = dht.id().join();
        this.writerQuotas = new WriterQuotas(usageStore);
        usageStore.addUsageListener(writerQuotas);
        new Thread(() -> {
            while (isRunning.get()) {
                try {
                    MutableEvent event = mutableQueue.take();
                    processMutablePointerEvent(event);
                } catch (InterruptedException e) {}
                catch (Exception e) {
                    LOG.log(Level.WARNING, "Error processing mutable pointer event: " + e.getMessage(), e);
                }
            }
        }, "SpaceCheckingKeyFilter").start();
        //add shutdown-hook to call close
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "SpaceChecker shutdown"));
    }

    /**
     * Write current view of usages to this.statePath, completing any pending operations
     */
    private synchronized void close() {
        isRunning.set(false);
        usageStore.close();
    }

    /**
     * Walk the virtual file-system to calculate space used by each owner not already checked
     */
    public void calculateUsage() {
        try {
            List<String> usernames = quotaAdmin.getLocalUsernames();
            long t0 = System.currentTimeMillis();
            Logging.LOG().info("Calculating space usage for " + usernames.size() + " local users...");
            long done = 0;
            for (String username : usernames) {
                Logging.LOG().info("Calculating space usage of " + username + " (" + done++ + "/" + usernames.size() + ")");
                try {
                    Optional<PublicKeyHash> identity = core.getPublicKeyHash(username).get();
                    if (identity.isPresent()) {
                        long prior = usageStore.getUsage(username).totalUsage();
                        processCorenodeEvent(username, identity.get());
                        long after = usageStore.getUsage(username).totalUsage();
                        if (after != prior)
                            LOG.info("Updated space usage of user: " + username + " to " + after);
                    } else
                        LOG.info("Identity key absent in pki for user: " + username);
                } catch (Exception e) {
                    e.printStackTrace();
                    LOG.log(Level.WARNING, "ERROR calculating usage for user: " + username + "\n" + e.getMessage(), e);
                }
            }
            usageStore.initialized();
            long t1 = System.currentTimeMillis();
            Logging.LOG().info("Finished calculating space usage for " + usernames.size() + " local users in " + (t1-t0)/1_000 + "s");
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
    }

    public static void update(UsageStore store,
                              QuotaAdmin quotas,
                              CoreNode core,
                              MutablePointers mutable,
                              DeletableContentAddressedStorage dht,
                              Hasher hasher) {
        Logging.LOG().info("Checking for updated usage for users...");
        Cid ourId = dht.id().join();
        List<String> localUsernames = quotas.getLocalUsernames();
        for (String username : localUsernames) {
            store.addUserIfAbsent(username);
            Optional<PublicKeyHash> identity = core.getPublicKeyHash(username).join();
            if (identity.isPresent())
                store.addWriter(username, identity.get());
        }

        Logging.LOG().info("Checking for updated mutable pointers...");
        long t1 = System.currentTimeMillis();
        Set<PublicKeyHash> writers = store.getAllWriters();
        List<Multihash> us = List.of(ourId.bareMultihash());
        for (PublicKeyHash writerKey : writers) {
            WriterUsage tmpUsage = null;
            try {
                tmpUsage = store.getUsage(writerKey);
                WriterUsage writerUsage = tmpUsage;
                Logging.LOG().info("Checking for updates from user: " + writerUsage.owner + ", writer key: " + writerKey);

                PublicKeyHash owner = writerKey; //NB: owner is a dummy value
                MaybeMultihash rootHash = mutable.getPointerTarget(owner, writerKey, dht).join().updated;
                boolean isChanged = ! writerUsage.target().equals(rootHash);
                if (isChanged) {
                    Logging.LOG().info("Root hash changed from " + writerUsage.target() + " to " + rootHash);
                    long updatedSize = dht.getRecursiveBlockSize(owner, (Cid)rootHash.get(), us).get();
                    long deltaUsage = updatedSize - writerUsage.directRetainedStorage();
                    Set<PublicKeyHash> directOwnedKeys = DeletableContentAddressedStorage.getDirectOwnedKeys(owner, writerKey, mutable,
                            (h, s) -> DeletableContentAddressedStorage.getWriterData(us, owner, h, s, false, ourId, hasher, dht), dht, hasher).join();
                    List<PublicKeyHash> newOwnedKeys = directOwnedKeys.stream()
                            .filter(key -> !writerUsage.ownedKeys().contains(key))
                            .collect(Collectors.toList());
                    for (PublicKeyHash newOwnedKey : newOwnedKeys) {
                        store.addWriter(writerUsage.owner, newOwnedKey);
                        processMutablePointerEvent(store, owner, newOwnedKey, MaybeMultihash.empty(),
                                mutable.getPointerTarget(owner, newOwnedKey, dht).get().updated, mutable, quotas, dht, hasher);
                    }
                    HashSet<PublicKeyHash> removedOwnedKeys = new HashSet<>(writerUsage.ownedKeys());
                    removedOwnedKeys.removeAll(directOwnedKeys);
                    boolean updated = store.updateWriterUsageAtomically(writerKey, writerUsage.target(), rootHash,
                            removedOwnedKeys, new HashSet<>(newOwnedKeys), updatedSize, deltaUsage,
                            store.getUsage(writerUsage.owner).isErrored());
                    if (updated)
                        Logging.LOG().info("Updated space used by " + writerKey + " to " + updatedSize);
                }
            } catch (Throwable t) {
                Logging.LOG().log(Level.WARNING, "Failed calculating usage for " + (tmpUsage == null ? writerKey : tmpUsage.owner), t);
            }
        }
        long t2 = System.currentTimeMillis();
        Logging.LOG().info(LocalDateTime.now() + " Finished updating space usage for all usernames in " + (t2 - t1)/1000 + " s");
    }

    public CompletableFuture<Boolean> accept(CorenodeEvent event) {
        usageStore.addUserIfAbsent(event.username);
        usageStore.addWriter(event.username, event.keyHash);
        return CompletableFuture.supplyAsync(() -> processCorenodeEvent(event.username, event.keyHash), VIRTUAL_EXECUTOR);
    }

    /** Update our view of the world because a user has changed their public key (or registered)
     *
     * @param username
     * @param writer
     */
    private boolean processCorenodeEvent(String username, PublicKeyHash writer) {
        try {
            processCorenodeEvent(username, writer, usageStore, quotaAdmin, dht, mutable, hasher);
            return true;
        } catch (Throwable e) {
            LOG.severe("Error loading storage for user: " + username);
            Exceptions.getRootCause(e).printStackTrace();
            return false;
        }
    }

    public void accept(MutableEvent event) {
        mutableQueue.add(event);
        try {
            prepareMutablePointerChange(event, dht, usageStore, hasher);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error registering owned keys for writer " + event.writer + ": " + e.getMessage(), e);
        }
    }

    public static void processCorenodeEvent(String username,
                                            PublicKeyHash owner,
                                            UsageStore usageStore,
                                            QuotaAdmin quotaAdmin,
                                            DeletableContentAddressedStorage dht,
                                            MutablePointers mutable,
                                            Hasher hasher) {
        // get current set of owned keys from usage db, and traverse filesystem
        // only if a pointer has changed since last usage update
        Set<PublicKeyHash> allUserKeys = usageStore.getAllWriters(owner);
        processCorenodeEvent(username, owner, allUserKeys, usageStore, quotaAdmin, dht, mutable, hasher);
    }
    public static void processCorenodeEvent(String username,
                                            PublicKeyHash owner,
                                            Set<PublicKeyHash> allUserKeys,
                                            UsageStore usageStore,
                                            QuotaAdmin quotaAdmin,
                                            DeletableContentAddressedStorage dht,
                                            MutablePointers mutable,
                                            Hasher hasher) {
        usageStore.addUserIfAbsent(username);

        for (PublicKeyHash writerKey : allUserKeys) {
            usageStore.addWriter(username, writerKey);
            WriterUsage current = usageStore.getUsage(writerKey);
            MaybeMultihash updatedRoot = mutable.getPointerTarget(owner, writerKey, dht).join().updated;
            processMutablePointerEvent(usageStore, owner, writerKey, current.target(), updatedRoot, mutable, quotaAdmin, dht, hasher);
        }
    }

    private static void prepareMutablePointerChange(MutableEvent event,
                                                    DeletableContentAddressedStorage dht,
                                                    UsageStore usageStore,
                                                    Hasher hasher) {
        Cid ourId = dht.id().join();
        List<Multihash> us = List.of(ourId.bareMultihash());
        PointerUpdate pointerUpdate = dht.getSigningKey(event.owner, event.writer)
                .thenApply(signer -> PointerUpdate.fromCbor(CborObject.fromByteArray(signer.get()
                        .unsignMessage(event.writerSignedBtreeRootHash).join()))).join();
        Set<PublicKeyHash> updatedOwned =
                DeletableContentAddressedStorage.getDirectOwnedKeys(event.owner, event.writer, pointerUpdate.updated,
                        (h, s) -> DeletableContentAddressedStorage.getWriterData(us, event.owner, h, s, false, ourId, hasher, dht), dht, hasher).join();
        String owner = usageStore.getOwner(event.writer);
        for (PublicKeyHash owned : updatedOwned) {
            usageStore.addWriter(owner, owned);
        }
    }

    private void processMutablePointerEvent(MutableEvent event) {
        try {
            PointerUpdate pointerUpdate = dht.getSigningKey(event.owner, event.writer)
                    .thenApply(signer -> PointerUpdate.fromCbor(CborObject.fromByteArray(signer.get()
                            .unsignMessage(event.writerSignedBtreeRootHash).join()))).join();
            processMutablePointerEvent(usageStore, event.owner, event.writer, pointerUpdate.original, pointerUpdate.updated,
                    mutable, quotaAdmin, dht, hasher);
            // a writing space that is merely orphaned may have been moved, so only drop its cap once its pointer is empty
            if (! pointerUpdate.updated.isPresent() && writerQuotas.getQuota(event.writer).isPresent())
                usageStore.deleteWriterQuota(event.writer);
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
    }

    private static void processMutablePointerEvent(UsageStore state,
                                                   PublicKeyHash owner,
                                                   PublicKeyHash writer,
                                                   MaybeMultihash existingRoot,
                                                   MaybeMultihash newRoot,
                                                   MutablePointers mutable,
                                                   QuotaAdmin quotaAdmin,
                                                   DeletableContentAddressedStorage dht,
                                                   Hasher hasher) {
        if (existingRoot.equals(newRoot))
            return;
        Cid ourId = dht.id().join();
        List<Multihash> us = List.of(ourId.bareMultihash());
        synchronized (getWriterLock(writer)) {
            // Re-read inside the lock to get a consistent view; another thread may have already processed this change
            WriterUsage current = state.getUsage(writer);
            if (current == null)
                throw new IllegalStateException("Unknown writer key hash: " + writer);
            if (current.target().equals(newRoot))
                return; // already processed by another thread
            if (! newRoot.isPresent()) {
                LOG.info("Removing usage for (" + owner + ", " + writer + ") from " + current.directRetainedStorage());
                // drop its edges too, so if it is re-owned later its children are counted again as newly added
                state.updateWriterUsageAtomically(writer, current.target(), MaybeMultihash.empty(),
                        current.ownedKeys(), Collections.emptySet(), 0,
                        -current.directRetainedStorage(), state.getUsage(current.owner).isErrored());
                if (existingRoot.isPresent()) {
                    try {
                        // subtract data size from orphaned child keys (this assumes the keys form a tree without dupes)
                        Set<PublicKeyHash> updatedOwned =
                                DeletableContentAddressedStorage.getDirectOwnedKeys(owner, writer, existingRoot,
                                        (h, s) -> DeletableContentAddressedStorage.getWriterData(us, owner, h, s, false, ourId, hasher, dht),  dht, hasher).join();
                        processRemovedOwnedKeys(state, owner, writer, updatedOwned, mutable, quotaAdmin, dht, hasher);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, e.getMessage(), e);
                    }
                }
                return;
            }

            try {
                long t0 = System.nanoTime();
                long changeInStorage = dht.getChangeInContainedSize(owner, current.target().toOptional().map(c -> (Cid) c), (Cid) newRoot.get()).get();
                long t1 = System.nanoTime();
                LOG.info("Calculating change in used space for (" + owner + ", " + writer + ") took " + (t1-t0)/1_000_000 + "mS");
                Set<PublicKeyHash> updatedOwned =
                        DeletableContentAddressedStorage.getDirectOwnedKeys(owner, writer, newRoot,
                                (h, s) -> DeletableContentAddressedStorage.getWriterData(us, owner, h, s, false, ourId, hasher, dht), dht, hasher).join();
                for (PublicKeyHash owned : updatedOwned) {
                    state.addWriter(current.owner, owned);
                }
                UserUsage usage = state.getUsage(current.owner);
                boolean initialErrored = usage.isErrored();
                String username = current.owner;
                long quota = getQuota(username, quotaAdmin);
                boolean errored = initialErrored && usage.totalUsage() > quota;

                HashSet<PublicKeyHash> removedChildren = new HashSet<>(current.ownedKeys());
                removedChildren.removeAll(updatedOwned);
                processRemovedOwnedKeys(state, owner, writer, removedChildren, mutable, quotaAdmin, dht, hasher);
                HashSet<PublicKeyHash> addedOwnedKeys = new HashSet<>(updatedOwned);
                addedOwnedKeys.removeAll(current.ownedKeys());
                boolean updated = state.updateWriterUsageAtomically(writer, current.target(), newRoot,
                        removedChildren, addedOwnedKeys,
                        current.directRetainedStorage() + changeInStorage, changeInStorage, errored);
                if (updated) {
                    UserUsage cached = getUsage(username, state);
                    cached.confirmUsage(writer, changeInStorage);
                    cached.setErrored(errored);
                }
                for (PublicKeyHash added : addedOwnedKeys) {
                    state.addWriter(current.owner, added);
                    WriterUsage currentAdded = state.getUsage(added);
                    MaybeMultihash updatedRoot = mutable.getPointerTarget(owner, added, dht).join().updated;
                    processMutablePointerEvent(state, owner, added, currentAdded.target(), updatedRoot, mutable, quotaAdmin, dht, hasher);
                }
                LOG.info("Updated usage for (" + owner + ", " + writer + ") from " + current.directRetainedStorage() + ", adding " + changeInStorage);
            } catch (Exception e) {
                Exceptions.getRootCause(e).printStackTrace();
            }
        }
    }

    private static void processRemovedOwnedKeys(UsageStore state,
                                                PublicKeyHash owner,
                                                PublicKeyHash parent,
                                                Set<PublicKeyHash> removed,
                                                MutablePointers mutable,
                                                QuotaAdmin quotaAdmin,
                                                DeletableContentAddressedStorage dht,
                                                Hasher hasher) {
        for (PublicKeyHash ownedKey : removed) {
            try {
                // a key that has been moved to another parent is still in use, not orphaned
                Set<PublicKeyHash> otherParents = new HashSet<>(state.getParents(ownedKey));
                otherParents.remove(parent);
                if (! otherParents.isEmpty())
                    continue;
                MaybeMultihash currentTarget = mutable.getPointerTarget(owner, ownedKey, dht).get().updated;
                processMutablePointerEvent(state, owner, ownedKey, currentTarget, MaybeMultihash.empty(), mutable, quotaAdmin, dht, hasher);
            } catch (Exception e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
            }
        }
    }

    @Override
    public CompletableFuture<Long> getUsage(PublicKeyHash owner, byte[] signedTime, boolean local) {
        TimeLimited.isAllowedTime(signedTime, 300, dht, owner);
        String user = usageStore.getOwner(owner);
        UserUsage usage = usageStore.getUsage(user);
        if (usage == null)
            return Futures.errored(new IllegalStateException("No usage present for user: " + user));
        return CompletableFuture.completedFuture(local ? usage.expectedUsage() : usage.totalUsage());
    }

    @Override
    public CompletableFuture<PaymentProperties> getPaymentProperties(PublicKeyHash owner, boolean newClientSecret, byte[] signedTime) {
        return quotaAdmin.getPaymentProperties(owner, newClientSecret, signedTime);
    }

    @Override
    public CompletableFuture<Long> getQuota(PublicKeyHash owner, byte[] signedTime) {
        TimeLimited.isAllowedTime(signedTime, 24*3600, dht, owner);
        String user = usageStore.getOwner(owner);
        return quotaAdmin.getQuota(owner, signedTime);
    }

    @Override
    public CompletableFuture<PaymentProperties> requestQuota(PublicKeyHash owner, byte[] signedRequest, long claimedUsage) {
        String username = core.getUsername(owner).join();
        UserUsage usage = usageStore.getUsage(username);
        return quotaAdmin.requestQuota(owner, signedRequest,  usage.totalUsage());
    }

    @Override
    public CompletableFuture<Boolean> setWriterQuota(PublicKeyHash owner, byte[] signedRequest) {
        String username = usageStore.getOwner(owner);
        if (! core.getPublicKeyHash(username).join().equals(Optional.of(owner)))
            throw new IllegalStateException("Only the current identity of " + username + " can set writer quotas");
        WriterQuotaRequest req = WriterQuotas.verify(signedRequest, owner, username, usageStore, dht);
        if (Math.abs(System.currentTimeMillis() - req.utcMillis) > 300_000)
            throw new IllegalStateException("Stale auth time, is your clock accurate?");
        if (! usageStore.setWriterQuota(username, req.writer, req.bytes, req.utcMillis, signedRequest))
            throw new IllegalStateException("A newer writer quota has already been set");
        LOG.info("Set writer quota of " + req.writer + " for " + username + " to " + req.bytes);
        return Futures.of(true);
    }

    @Override
    public CompletableFuture<List<WriterUsageInfo>> getWriterQuotas(PublicKeyHash owner, byte[] signedRequest) {
        TimeLimited.isAllowed(SpaceUsage.writerQuotasPath(), signedRequest, 300, dht, owner);
        String username = usageStore.getOwner(owner);
        List<WriterUsageInfo> res = usageStore.getWriterQuotas(username).keySet().stream()
                .map(this::getWriterUsage)
                .collect(Collectors.toList());
        return Futures.of(res);
    }

    @Override
    public CompletableFuture<WriterUsageInfo> getWriterUsage(PublicKeyHash owner, PublicKeyHash writer, byte[] signedRequest) {
        PublicSigningKey writerKey = dht.getSigningKey(owner, writer).join()
                .orElseThrow(() -> new IllegalStateException("Couldn't retrieve writer key!"));
        String path = SpaceUsage.writerUsagePath(owner, writer);
        try {
            TimeLimited.isAllowed(path, signedRequest, 300, writerKey);
        } catch (Exception e) {
            if (writer.equals(owner))
                throw e;
            TimeLimited.isAllowed(path, signedRequest, 300, dht, owner);
        }
        String username = usageStore.getOwner(owner);
        if (! username.equals(usageStore.getOwner(writer)))
            throw new IllegalStateException("Writer is not owned by " + username);
        return Futures.of(getWriterUsage(writer));
    }

    private WriterUsageInfo getWriterUsage(PublicKeyHash writer) {
        Optional<Long> quota = writerQuotas.getQuota(writer);
        long used = quota.isPresent() ? writerQuotas.getUsage(writer).totalUsage() : 0;
        Optional<Long> available = writerQuotas.getCaps(writer).stream()
                .flatMap(cap -> writerQuotas.getQuota(cap)
                        .map(q -> Math.max(0, q - writerQuotas.getUsage(cap).totalUsage()))
                        .stream())
                .min(Long::compare);
        return new WriterUsageInfo(writer, quota, used, available);
    }

    private static final LRUCache<Long, Map<String, Long>> quotas = new LRUCache<>(2);
    private static final LRUCache<Long, Map<String, UserUsage>> usageCache = new LRUCache<>(2);
    private static final ConcurrentHashMap<PublicKeyHash, Object> writerLocks = new ConcurrentHashMap<>();

    private static Object getWriterLock(PublicKeyHash writer) {
        return writerLocks.computeIfAbsent(writer, k -> new Object());
    }

    private static long getQuota(String owner, QuotaAdmin quotaAdmin) {
        long timeKey = System.currentTimeMillis() / 3_600_000;
        Map<String, Long> cachedQuotas;
        synchronized (quotas) {
            cachedQuotas = quotas.computeIfAbsent(timeKey, k -> new ConcurrentHashMap<>());
        }
        Long cachedQuota = cachedQuotas.get(owner);
        long quota = cachedQuota != null ? cachedQuota : quotaAdmin.getQuota(owner);
        if (cachedQuota == null)
            cachedQuotas.put(owner, quota);
        return quota;
    }

    private static UserUsage getUsage(String username, UsageStore usageStore) {
        UserUsage usage;
        Map<String, UserUsage> usageByHour;
        long tenMinuteKey = System.currentTimeMillis() / 600_000;
        synchronized (usageCache) {
            usageByHour = usageCache.computeIfAbsent(tenMinuteKey, k -> new ConcurrentHashMap<>());
        }
        usage = usageByHour.get(username);
        if (usage == null) {
            usage = usageStore.getUsage(username);
            usageByHour.put(username, usage);
        } else if (usage.isErrored()) {
            usage = usageStore.getUsage(username);
            usageByHour.put(username, usage);
        }
        return usage;
    }

    public boolean allowWrite(PublicKeyHash owner, PublicKeyHash writer, int size) {
        String username;
        try {
            username = usageStore.getOwner(writer);
        } catch (IllegalStateException e) {
            // The writer is absent from the usage store, most likely because a registration event failed or was
            // missed. Self heal: if the writer is provably owned by the owner then register it and allow the write.
            if (! registerMissedWriters(owner, writer))
                throw e;
            username = usageStore.getOwner(writer);
        }
        long quota = getQuota(username, quotaAdmin);

        UserUsage usage = getUsage(username, usageStore);

        long expectedUsage = usage.expectedUsage();
        boolean errored = usage.isErrored();
        if ((! errored && expectedUsage + size > quota) || (errored && expectedUsage + size > quota + USAGE_TOLERANCE)) {
            long pending = usage.getPending(writer);
            usageStore.confirmUsage(username, writer, 0, true);
            usage.confirmUsage(writer, 0);
            usage.setErrored(true);
            LOG.info("Rejecting write for " + username);
            throw new IllegalStateException("Storage quota reached! \nUsed "
                    + usage.totalUsage() + " out of " + quota + " bytes. Rejecting write of size " + (size + pending) + ". \n" +
                    "Please delete some files or request more space.");
        }
        List<PublicKeyHash> caps = writerQuotas.getCaps(writer);
        for (PublicKeyHash cap : caps)
            checkWriterQuota(cap, writer, size);
        SlidingWindowCounter writeLimit = writeLimiter.get(username);
        if (writeLimit == null) {
            writeLimit = new SlidingWindowCounter(quotaUploadLimitSeconds, quota);
            writeLimiter.put(username, writeLimit);
        }
        if (! writeLimit.allowRequest(size))
            throw new IllegalStateException("Upload bandwidth exceeded please try again tomorrow");
        try {
            usage.addPending(writer, size);
            for (PublicKeyHash cap : caps)
                writerQuotas.getUsage(cap).addPending(writer, size);
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't update pending usage for user " + username, e);
        }
        return true;
    }

    private void checkWriterQuota(PublicKeyHash cap, PublicKeyHash writer, int size) {
        Optional<Long> quota = writerQuotas.getQuota(cap);
        if (quota.isEmpty())
            return;
        UserUsage usage = writerQuotas.getUsage(cap);
        long expectedUsage = usage.expectedUsage();
        boolean errored = usage.isErrored();
        if ((! errored && expectedUsage + size > quota.get()) || (errored && expectedUsage + size > quota.get() + USAGE_TOLERANCE)) {
            long pending = usage.getPending(writer);
            usage.confirmUsage(writer, 0);
            usage.setErrored(true);
            LOG.info("Rejecting write to capped writing space " + cap);
            // Don't reveal the usage of an enclosing capped space to someone who can only write to a nested one
            throw new IllegalStateException("Storage quota reached for this shared folder! \n"
                    + (cap.equals(writer) ? "Used " + usage.totalUsage() + " out of " + quota.get() + " bytes. " : "")
                    + "Rejecting write of size " + (size + pending) + ".");
        }
    }

    /** Whether a commit may be applied, given the bytes it writes and the net change in stored bytes
     *  its pointer updates will cause.
     *
     *  A write is otherwise allowed only if it fits in the remaining quota, which leaves a user who is
     *  over quota unable to delete anything: freeing space itself takes a write. A commit carries its
     *  blocks and its pointer updates together, so the net effect is knowable before anything is
     *  applied, and one that brings the user back within quota is let through.
     *
     * @param delta computes the change in stored bytes, only called when the write would be rejected
     */
    public boolean allowCommit(PublicKeyHash owner, PublicKeyHash writer, int written, Supplier<Long> delta) {
        try {
            return allowWrite(owner, writer, written);
        } catch (IllegalStateException e) {
            String message = e.getMessage();
            // a rate limit is not something a delete should be able to step around
            if (message == null || ! message.startsWith("Storage quota reached"))
                throw e;
            String username = usageStore.getOwner(writer);
            long change = delta.get();
            long quota = getQuota(username, quotaAdmin);
            UserUsage usage = getUsage(username, usageStore);
            if (usage.totalUsage() + change > quota)
                throw e;
            // a commit that shrinks a capped space is allowed, even if it is still over its cap afterwards
            for (PublicKeyHash cap : writerQuotas.getCaps(writer)) {
                Optional<Long> capQuota = writerQuotas.getQuota(cap);
                if (capQuota.isPresent() && change > 0 && writerQuotas.getUsage(cap).totalUsage() + change > capQuota.get())
                    throw e;
            }
            LOG.info("Allowing a commit for " + username + " over quota: it frees " + (-change) + " bytes");
            return true;
        }
    }

    /** Register a writer that is being created by the commit we are in the middle of applying.
     *
     *  The proof that the owner owns it is in that commit - the parent's new WriterData names it - and
     *  has already been checked, but it isn't in the committed pointers yet, so the self-heal below
     *  cannot find it and the write would be rejected.
     *
     * @return true if the writer is now registered
     */
    public boolean registerNewWriter(PublicKeyHash owner, PublicKeyHash writer) {
        Object lock = healLocks.computeIfAbsent(owner, o -> new Object());
        synchronized (lock) {
            try {
                usageStore.getOwner(writer);
                return true;
            } catch (IllegalStateException absent) {}
            try {
                String username = core.getUsername(owner).join();
                if (! quotaAdmin.getLocalUsernames().contains(username))
                    return false;
                usageStore.addUserIfAbsent(username);
                usageStore.addWriter(username, writer);
                return true;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Couldn't register new writer " + writer + " of owner " + owner, e);
                return false;
            }
        }
    }

    /** A write was attempted with a writer that is absent from the usage store. If the writer is reachable through
     *  the chain of ownership proofs from the owner's identity key then register it, and any other missing owned
     *  keys, and account their current usage.
     *
     * @return true if the writer was registered
     */
    private boolean registerMissedWriters(PublicKeyHash owner, PublicKeyHash writer) {
        synchronized (nonLocalOwners) {
            if (nonLocalOwners.containsKey(owner))
                return false;
        }
        Object lock = healLocks.computeIfAbsent(owner, o -> new Object());
        synchronized (lock) {
            try {
                usageStore.getOwner(writer);
                return true; // already healed by a concurrent write
            } catch (IllegalStateException absent) {}
            Long lastFailure = lastFailedHeals.get(owner);
            if (lastFailure != null && System.currentTimeMillis() - lastFailure < HEAL_RETRY_INTERVAL_MILLIS)
                return false;
            try {
                String username = core.getUsername(owner).join();
                if (! quotaAdmin.getLocalUsernames().contains(username)) {
                    synchronized (nonLocalOwners) {
                        nonLocalOwners.put(owner, true);
                    }
                    return false;
                }
                LOG.warning("Usage store is missing writer " + writer + " of local user " + username
                        + ", searching their ownership tree for it");
                usageStore.addUserIfAbsent(username);
                Set<PublicKeyHash> known = usageStore.getAllWriters(username);
                List<Multihash> us = List.of(ourId.bareMultihash());
                Set<PublicKeyHash> visited = new HashSet<>();
                Deque<PublicKeyHash> toVisit = new ArrayDeque<>();
                toVisit.add(owner);
                List<PublicKeyHash> added = new ArrayList<>();
                while (! toVisit.isEmpty()) {
                    PublicKeyHash current = toVisit.poll();
                    if (! visited.add(current))
                        continue;
                    if (! known.contains(current)) {
                        usageStore.addWriter(username, current);
                        added.add(current);
                    }
                    toVisit.addAll(DeletableContentAddressedStorage.getDirectOwnedKeys(owner, current, mutable,
                            (h, s) -> DeletableContentAddressedStorage.getWriterData(us, owner, h, s, false, ourId, hasher, dht),
                            dht, hasher).join());
                }
                for (PublicKeyHash newWriter : added) {
                    try {
                        MaybeMultihash target = mutable.getPointerTarget(owner, newWriter, dht).join().updated;
                        processMutablePointerEvent(usageStore, owner, newWriter, MaybeMultihash.empty(), target,
                                mutable, quotaAdmin, dht, hasher);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Couldn't account usage of missed writer " + newWriter, e);
                    }
                }
                if (visited.contains(writer)) {
                    LOG.info("Registered " + added.size() + " missed writers for " + username);
                    return true;
                }
                LOG.warning("Writer " + writer + " is not owned by " + username + ", rejecting write");
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to search for missed writer " + writer + " of owner " + owner, e);
            }
            lastFailedHeals.put(owner, System.currentTimeMillis());
            return false;
        }
    }
}
