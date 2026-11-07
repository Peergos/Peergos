package peergos.server.space;

import java.util.concurrent.atomic.*;
import java.util.logging.*;

import peergos.server.storage.*;
import peergos.server.space.WriterUsageStore.ProvisionalWriter;
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
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Provisional writer sweep");
        t.setDaemon(true);
        return t;
    });

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
        // registrations no server resolved, one restarting say, are taken back once they expire
        sweeper.scheduleWithFixedDelay(() -> {
            try {
                takeBackExpired(System.currentTimeMillis());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error taking back expired registrations: " + e.getMessage(), e);
            }
        }, 1, 1, TimeUnit.MINUTES);
        //add shutdown-hook to call close
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "SpaceChecker shutdown"));
    }

    /**
     * Write current view of usages to this.statePath, completing any pending operations
     */
    private synchronized void close() {
        isRunning.set(false);
        sweeper.shutdownNow();
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
        // each user's identity, looked up once; without the PKI, a writer stands in for it as it used to
        Map<String, Optional<PublicKeyHash>> identities = new HashMap<>();
        for (PublicKeyHash writerKey : writers) {
            WriterUsage tmpUsage = null;
            try {
                tmpUsage = store.getUsage(writerKey);
                WriterUsage writerUsage = tmpUsage;
                Logging.LOG().info("Checking for updates from user: " + writerUsage.owner + ", writer key: " + writerKey);

                // the identity key the ownership chains lead to; the pointer and block reads don't need it
                PublicKeyHash owner = identities.computeIfAbsent(writerUsage.owner, u -> {
                    try {
                        return core.getPublicKeyHash(u).join();
                    } catch (Exception e) {
                        return Optional.empty();
                    }
                }).orElse(writerKey);
                MaybeMultihash rootHash = mutable.getPointerTarget(owner, writerKey, dht).join().updated;
                boolean isChanged = ! writerUsage.target().equals(rootHash);
                if (isChanged) {
                    Logging.LOG().info("Root hash changed from " + writerUsage.target() + " to " + rootHash);
                    long updatedSize = dht.getRecursiveBlockSize(owner, (Cid)rootHash.get(), us).get();
                    long deltaUsage = updatedSize - writerUsage.directRetainedStorage();
                    Set<PublicKeyHash> directOwnedKeys = DeletableContentAddressedStorage.getDirectOwnedKeys(owner, writerKey, mutable,
                            (h, s) -> DeletableContentAddressedStorage.getWriterData(us, owner, h, s, false, ourId, hasher, dht), dht, hasher).join();
                    // a key linked under this writer is registered already
                    for (PublicKeyHash key : directOwnedKeys)
                        if (! writerUsage.ownedKeys().contains(key))
                            store.addWriter(writerUsage.owner, key);
                    Set<PublicKeyHash> ownedHere = ownKeys(store, writerUsage.owner, writerKey, directOwnedKeys);
                    List<PublicKeyHash> newOwnedKeys = ownedHere.stream()
                            .filter(key -> !writerUsage.ownedKeys().contains(key))
                            .collect(Collectors.toList());
                    for (PublicKeyHash newOwnedKey : newOwnedKeys) {
                        processMutablePointerEvent(store, owner, newOwnedKey, MaybeMultihash.empty(),
                                mutable.getPointerTarget(owner, newOwnedKey, dht).get().updated, mutable, quotas, dht, hasher);
                    }
                    HashSet<PublicKeyHash> removedOwnedKeys = new HashSet<>(writerUsage.ownedKeys());
                    removedOwnedKeys.removeAll(ownedHere);
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

    /** Records what each pointer a call landed names, the children it links, so their first writes count under its
     *  caps, then queues their usage updates. All are recorded before any is queued, or the queue could judge one
     *  update before a child the call moves under another of its writers is linked there, and forget the child, its
     *  cap with it. The pointers have moved by now, so a full queue mustn't fail the call that moved them: a writer's
     *  usage is behind until its next update. */
    public void accept(List<MutableEvent> landed) {
        for (MutableEvent event : landed) {
            try {
                prepareMutablePointerChange(event, dht, usageStore, hasher);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error registering owned keys for writer " + event.writer + ": " + e.getMessage(), e);
            }
        }
        for (MutableEvent event : landed)
            if (! mutableQueue.offer(event))
                LOG.warning("Mutable event queue full, usage of writer " + event.writer + " waits for its next update");
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
        Set<PublicKeyHash> updatedOwned = directOwnedKeys(event.owner, event.writer, pointerUpdate.updated, us, ourId, dht, hasher);
        if (updatedOwned.isEmpty())
            return;
        // Only the keys not linked under this writer yet. A linked key is a registered writer, so registering and
        // linking it again would be writes that change nothing, on every commit of the writer.
        Set<PublicKeyHash> unlinked = new HashSet<>(updatedOwned);
        unlinked.removeAll(usageStore.getOwnedKeys(event.writer));
        if (unlinked.isEmpty())
            return;
        String owner = usageStore.getOwner(event.writer);
        for (PublicKeyHash owned : unlinked)
            usageStore.addWriter(owner, owned);
        // linked now rather than when the queue gets to it, so a child's first writes already count under its
        // parent's caps
        for (PublicKeyHash owned : ownKeys(usageStore, owner, event.writer, unlinked))
            usageStore.addOwnedKey(event.writer, owned);
    }

    private record RootOf(PublicKeyHash owner, PublicKeyHash writer, Multihash root) {}

    // What a writer's root names never changes, a root being content addressed. Walked as a pointer lands, it is read
    // here again by the queue, for that update and as the previous root of the next one, rather than from S3 each
    // time, where a read can take a second or two. Bounded by its roots and by the keys it holds, as a root can name any
    // number of keys, and most name none.
    private static final int MAX_CACHED_ROOTS = 10_000, MAX_CACHED_OWNED_KEYS = 100_000;
    private static final LinkedHashMap<RootOf, Set<PublicKeyHash>> ownedKeysByRoot = new LinkedHashMap<>(16, 0.75f, true);
    private static long cachedOwnedKeys = 0; // guarded by ownedKeysByRoot, each root counting as one key more

    /** The keys a writer's root names directly, as DeletableContentAddressedStorage.getDirectOwnedKeys */
    private static Set<PublicKeyHash> directOwnedKeys(PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     MaybeMultihash root,
                                                     List<Multihash> us,
                                                     Cid ourId,
                                                     DeletableContentAddressedStorage dht,
                                                     Hasher hasher) {
        if (! root.isPresent())
            return Collections.emptySet();
        RootOf key = new RootOf(owner, writer, root.get());
        synchronized (ownedKeysByRoot) {
            Set<PublicKeyHash> cached = ownedKeysByRoot.get(key);
            if (cached != null)
                return cached;
        }
        Set<PublicKeyHash> named = Set.copyOf(DeletableContentAddressedStorage.getDirectOwnedKeys(owner, writer, root,
                (h, s) -> DeletableContentAddressedStorage.getWriterData(us, owner, h, s, false, ourId, hasher, dht), dht, hasher).join());
        synchronized (ownedKeysByRoot) {
            Set<PublicKeyHash> replaced = ownedKeysByRoot.put(key, named);
            cachedOwnedKeys += named.size() - (replaced == null ? -1 : replaced.size());
            Iterator<Set<PublicKeyHash>> eldest = ownedKeysByRoot.values().iterator();
            while ((ownedKeysByRoot.size() > MAX_CACHED_ROOTS || cachedOwnedKeys > MAX_CACHED_OWNED_KEYS) && eldest.hasNext()) {
                cachedOwnedKeys -= 1 + eldest.next().size();
                eldest.remove();
            }
        }
        return named;
    }

    /** The keys a writer names that are its owner's own, other than itself. A key of another user's, named with an
     *  owner proof it signed, stays theirs: an edge to it would keep it registered after its owner revokes it, and
     *  count their data under this user's caps. A key named under itself owns nothing more for it. */
    private static Set<PublicKeyHash> ownKeys(UsageStore state, String username, PublicKeyHash writer, Set<PublicKeyHash> named) {
        return named.stream()
                .filter(key -> ! key.equals(writer) && username.equals(state.getOwner(key)))
                .collect(Collectors.toSet());
    }

    /** Whether a writer is still owned other than through the edge from this parent: by a chain of edges up to the
     *  owner's identity, through writers of the same user. A key named under itself, or under keys it names in
     *  turn, has no such chain, nor has one named by another user's writer. */
    private static boolean ownedOtherwise(UsageStore state, PublicKeyHash owner, PublicKeyHash writer, PublicKeyHash parent) {
        String username = state.getOwner(writer);
        Set<PublicKeyHash> seen = new HashSet<>(List.of(writer));
        Deque<PublicKeyHash> toVisit = new ArrayDeque<>(state.getParents(writer));
        toVisit.remove(parent);
        while (! toVisit.isEmpty()) {
            PublicKeyHash current = toVisit.poll();
            if (! seen.add(current) || ! username.equals(state.getOwner(current)))
                continue;
            if (current.equals(owner))
                return true;
            toVisit.addAll(state.getParents(current));
        }
        return false;
    }

    /** Whether the owner's identity is reached from the writer by edges between the user's own keys: parents that
     *  only name each other, or the writer itself, own nothing */
    private static boolean owned(UsageStore state, PublicKeyHash owner, PublicKeyHash writer) {
        return ownedOtherwise(state, owner, writer, writer);
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
            // already processed by another thread, unless this empties a writer still holding an edge: its last update,
            // never sized, left its stored target empty and that edge recorded, and with it gone the child goes too
            if (current.target().equals(newRoot) && (newRoot.isPresent() || current.ownedKeys().isEmpty()))
                return;
            if (! newRoot.isPresent()) {
                LOG.info("Removing usage for (" + owner + ", " + writer + ") from " + current.directRetainedStorage());
                // drop its edges too, so if it is re-owned later its children are counted again as newly added
                state.updateWriterUsageAtomically(writer, current.target(), MaybeMultihash.empty(),
                        current.ownedKeys(), Collections.emptySet(), 0,
                        -current.directRetainedStorage(), state.getUsage(current.owner).isErrored());
                // Subtract data size from orphaned child keys (this assumes the keys form a tree without dupes): the
                // ones the old root names, and any whose edge was recorded ahead of an update that was never
                // processed (an event dropped or failed, a restart, an undo that threw). With this writer gone,
                // nothing owns those either.
                Set<PublicKeyHash> orphaned = new HashSet<>(current.ownedKeys());
                if (existingRoot.isPresent()) {
                    try {
                        orphaned.addAll(directOwnedKeys(owner, writer, existingRoot, us, ourId, dht, hasher));
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, e.getMessage(), e);
                    }
                }
                processRemovedOwnedKeys(state, owner, writer, orphaned, mutable, quotaAdmin, dht, hasher);
                return;
            }

            try {
                long t0 = System.nanoTime();
                long changeInStorage = dht.getChangeInContainedSize(owner, current.target().toOptional().map(c -> (Cid) c), (Cid) newRoot.get()).get();
                long t1 = System.nanoTime();
                LOG.info("Calculating change in used space for (" + owner + ", " + writer + ") took " + (t1-t0)/1_000_000 + "mS");
                Set<PublicKeyHash> updatedOwned = directOwnedKeys(owner, writer, newRoot, us, ourId, dht, hasher);
                // a key linked under this writer is registered already
                for (PublicKeyHash owned : updatedOwned) {
                    if (! current.ownedKeys().contains(owned))
                        state.addWriter(current.owner, owned);
                }
                Set<PublicKeyHash> ownedHere = ownKeys(state, current.owner, writer, updatedOwned);
                // What the replaced root named. A child's edge can be recorded ahead of its parent's update, so the
                // store's edges alone don't say which children this update adds or drops.
                Set<PublicKeyHash> namedBefore = directOwnedKeys(owner, writer, current.target(), us, ourId, dht, hasher);
                UserUsage usage = state.getUsage(current.owner);
                boolean initialErrored = usage.isErrored();
                String username = current.owner;
                long quota = getQuota(username, quotaAdmin);
                boolean errored = initialErrored && usage.totalUsage() > quota;

                HashSet<PublicKeyHash> removedChildren = new HashSet<>(current.ownedKeys());
                removedChildren.removeAll(ownedHere);
                // only children the replaced root owned: a new writer's edge is recorded ahead of its parent's pointer
                // update, and an earlier update of the parent still in the queue never named it
                removedChildren.retainAll(namedBefore);
                processRemovedOwnedKeys(state, owner, writer, removedChildren, mutable, quotaAdmin, dht, hasher);
                HashSet<PublicKeyHash> addedOwnedKeys = new HashSet<>(ownedHere);
                addedOwnedKeys.removeAll(current.ownedKeys());
                boolean updated = state.updateWriterUsageAtomically(writer, current.target(), newRoot,
                        removedChildren, addedOwnedKeys,
                        current.directRetainedStorage() + changeInStorage, changeInStorage, errored);
                if (updated) {
                    UserUsage cached = getUsage(username, state);
                    cached.confirmUsage(writer, changeInStorage);
                    cached.setErrored(errored);
                }
                // Each child named anew is counted from its pointer, with its edge recorded ahead or not: one named
                // again after it was dropped has data the store stopped counting when it was dropped.
                HashSet<PublicKeyHash> namedNow = new HashSet<>(ownedHere);
                namedNow.removeAll(namedBefore);
                namedNow.addAll(addedOwnedKeys);
                for (PublicKeyHash added : namedNow) {
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
                // A key of another user's, named under this writer with a proof it signed (or by an edge an older
                // version recorded), is theirs: this user dropping the name forgets nothing of it.
                if (! state.getOwner(parent).equals(state.getOwner(ownedKey)))
                    continue;
                // a key that has been moved to another parent is still in use, not orphaned, as long as that parent
                // is owned in turn: not by another user's writer, nor by the key itself, whatever edges an older
                // version recorded
                if (ownedOtherwise(state, owner, ownedKey, parent))
                    continue;
                MaybeMultihash currentTarget = mutable.getPointerTarget(owner, ownedKey, dht).get().updated;
                processMutablePointerEvent(state, owner, ownedKey, currentTarget, MaybeMultihash.empty(), mutable, quotaAdmin, dht, hasher);
                // Revoking write access leaves the old key with its signature intact but owned by nothing. Forgetting it
                // means any further write with it has to prove it is owned, which it can't.
                state.removeWriter(ownedKey);
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
        return quotaAdmin.getPaymentProperties(usageStore.getOwner(owner), owner, newClientSecret, signedTime);
    }

    @Override
    public CompletableFuture<Long> getQuota(PublicKeyHash owner, byte[] signedTime) {
        TimeLimited.isAllowedTime(signedTime, 24*3600, dht, owner);
        String user = usageStore.getOwner(owner);
        return quotaAdmin.getQuota(user, owner, signedTime);
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

    // How long a commit has to land or be refused before whichever server finds its registration takes it back
    private static final long PROVISIONAL_MILLIS = 10 * 60_000;

    /** Whether a writer may set this pointer: one a commit is still creating only the pointer that commit carries for
     *  it, if any, as another write it let in would be forgotten with the commit if that is refused; otherwise the
     *  owner's identity or a key the owner still owns. */
    public boolean isOwnedWriter(PublicKeyHash owner, SignedPointerUpdate update) {
        // Owned, then its rows, then owned again: a row is in before its writer, so a writer registered by now has its
        // rows there to be read, and a writer taken back is gone before its rows are, so one whose rows went while they
        // were read is found gone. An owner's identity is never provisional.
        if (! isOwnedWriter(owner, update.writer))
            return false;
        if (update.writer.equals(owner))
            return true;
        long now = System.currentTimeMillis();
        List<ProvisionalWriter> creating = new ArrayList<>();
        for (ProvisionalWriter p : usageStore.getProvisional(update.writer)) {
            // one expired, but not taken back yet, still holds its writer back
            if (p.expires() <= now && takeBack(p, now))
                continue;
            if (p.newWriter())
                creating.add(p);
        }
        if (! creating.isEmpty()) {
            if (creating.stream().anyMatch(p -> p.signed().map(s -> Arrays.equals(s, update.signed)).orElse(false)))
                return true;
            // the commit may have landed with its row left behind, its server restarting in between
            if (! creating.stream().allMatch(this::landed))
                return false;
            creating.forEach(p -> usageStore.removeProvisional(p.writer(), p.parent()));
        }
        return isOwnedWriter(owner, update.writer);
    }

    /** Whether the commit a provisional writer is for has landed: the committed parent names the writer. Its own
     *  pointer being the one the commit carries doesn't show that, as that update can be set on its own. */
    private boolean landed(ProvisionalWriter p) {
        return namesWriter(p.owner(), p.parent(), p.writer());
    }

    /** Takes back the registrations that have expired, as their commits' undos would have: for those whose server
     *  restarted, or failed to, before the commit landed or was refused */
    public void takeBackExpired(long now) {
        for (ProvisionalWriter p : usageStore.getExpiredProvisional(now))
            takeBack(p, now);
    }

    /** As an undo: the edge goes unless the committed parent names the writer, then the writer unless something owns
     *  it. The row stays if that fails, to be tried again.
     *
     * @return whether it was taken back
     */
    private boolean takeBack(ProvisionalWriter p, long now) {
        Object lock = healLocks.computeIfAbsent(p.owner(), o -> new Object());
        synchronized (lock) {
            try {
                try {
                    usageStore.getOwner(p.writer());
                } catch (IllegalStateException neverRegistered) {
                    // its server stopped between the row and the writer: only the row is left to take back
                    usageStore.removeProvisional(p.writer(), p.parent());
                    return true;
                }
                // A new writer's own row last: its commit's rows under other parents go first, as its undo takes them
                // back, or it would stay owned through one of their edges, unrestricted, until that row expired too.
                if (p.newWriter())
                    for (ProvisionalWriter other : usageStore.getProvisional(p.writer()))
                        if (! other.newWriter() && Arrays.equals(other.signed().orElse(null), p.signed().orElse(null))
                                && ! takeBack(other, now))
                            return false;
                MaybeMultihash root = mutable.getPointerTarget(p.owner(), p.parent(), dht).join().updated;
                boolean named;
                try {
                    named = rootOwnsWriter(p.owner(), p.parent(), root, p.writer());
                } catch (Exception unreadable) {
                    // A read may fail only for now, and a writer whose commit landed mustn't be forgotten for that, its
                    // cap with it, so the row stays to be tried again. One still failing once the row has been expired
                    // as long again is taken as the root's own doing, its owned key champ pointed at a block this
                    // server won't read for instance, and names nothing.
                    if (now < p.expires() + PROVISIONAL_MILLIS)
                        return false;
                    named = false;
                }
                if (! named)
                    usageStore.removeOwnedKey(p.parent(), p.writer());
                if (! owned(usageStore, p.owner(), p.writer()))
                    forgetWriter(usernameOf(p.owner()), p.owner(), p.writer());
                usageStore.removeProvisional(p.writer(), p.parent());
                return true;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Couldn't take back provisional writer " + p.writer() + " of owner " + p.owner(), e);
                return false;
            }
        }
    }

    /** Whether a writer may update its pointer: the owner's identity, or a key the owner still owns */
    public boolean isOwnedWriter(PublicKeyHash owner, PublicKeyHash writer) {
        if (writer.equals(owner))
            return true;
        String username;
        try {
            username = usageStore.getOwner(writer);
        } catch (IllegalStateException e) {
            return registerMissedWriters(owner, writer);
        }
        return username.equals(usernameOf(owner));
    }

    /** The user an owner's identity belongs to */
    private String usernameOf(PublicKeyHash owner) {
        try {
            return usageStore.getOwner(owner);
        } catch (IllegalStateException e) {
            return core.getUsername(owner).join();
        }
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
        // a writer of one user writes nothing into another's space, whatever it signs
        if (! writer.equals(owner) && ! username.equals(usernameOf(owner)))
            throw new IllegalStateException("Writer " + writer + " is not owned by " + owner);
        long quota = getQuota(username, quotaAdmin);

        UserUsage usage = getUsage(username, usageStore);

        // Each check reserves the bytes as it passes, in one step, so two writes arriving together can't both
        // fit where only one does. Whatever refuses later gives the reservations before it back. For the user's
        // own quota that holds until a write is refused: from then on each check reads the committed total
        // afresh (getUsage), until a processed commit clears the flag.
        long pending = usage.getPending(writer);
        if (! usage.reserve(writer, size, quota, USAGE_TOLERANCE)) {
            usageStore.confirmUsage(username, writer, 0, true);
            LOG.info("Rejecting write for " + username);
            throw new IllegalStateException("Storage quota reached! \nUsed "
                    + usage.totalUsage() + " out of " + quota + " bytes. Rejecting write of size " + (size + pending) + ". \n" +
                    "Please delete some files or request more space.");
        }
        List<UserUsage> reserved = new ArrayList<>();
        reserved.add(usage);
        try {
            for (PublicKeyHash cap : writerQuotas.getCaps(writer)) {
                Optional<Long> capQuota = writerQuotas.getQuota(cap);
                if (capQuota.isEmpty())
                    continue;
                UserUsage capUsage = writerQuotas.getUsage(cap);
                long capPending = capUsage.getPending(writer);
                if (! capUsage.reserve(writer, size, capQuota.get(), USAGE_TOLERANCE)) {
                    LOG.info("Rejecting write to capped writing space " + cap);
                    // Don't reveal the usage of an enclosing capped space to someone who can only write to a nested one
                    throw new IllegalStateException("Storage quota reached for this shared folder! \n"
                            + (cap.equals(writer) ? "Used " + capUsage.totalUsage() + " out of " + capQuota.get() + " bytes. " : "")
                            + "Rejecting write of size " + (size + capPending) + ".");
                }
                reserved.add(capUsage);
            }
            SlidingWindowCounter writeLimit = writeLimiter.get(username);
            if (writeLimit == null) {
                writeLimit = new SlidingWindowCounter(quotaUploadLimitSeconds, quota);
                writeLimiter.put(username, writeLimit);
            }
            if (! writeLimit.allowRequest(size))
                throw new IllegalStateException("Upload bandwidth exceeded please try again tomorrow");
        } catch (RuntimeException e) {
            // whatever refused, or failed, gives back what was reserved before it
            for (UserUsage held : reserved)
                held.releasePending(writer, size);
            throw e;
        }
        return true;
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
    public boolean allowCommit(PublicKeyHash owner, PublicKeyHash writer, int written, Supplier<Map<PublicKeyHash, Long>> deltas) {
        try {
            return allowWrite(owner, writer, written);
        } catch (IllegalStateException e) {
            String message = e.getMessage();
            // a rate limit is not something a delete should be able to step around
            if (message == null || ! message.startsWith("Storage quota reached"))
                throw e;
            String username = usageStore.getOwner(writer);
            Map<PublicKeyHash, Long> changes = deltas.get();
            long change = changes.values().stream().mapToLong(x -> x).sum();
            long quota = getQuota(username, quotaAdmin);
            UserUsage usage = getUsage(username, usageStore);
            // against what is committed and what other writes have reserved. Nothing is reserved here, so two such
            // commits can both pass, each fitting on its own; this is for a commit that frees space.
            if (usage.expectedUsage() + change > quota)
                throw e;
            // a commit that shrinks a capped space is allowed, even if it is still over its cap afterwards. It is judged
            // by everything the commit does under the cap, as a rotation grows one writer by what it frees in another
            for (PublicKeyHash cap : writerQuotas.getCaps(writer)) {
                Optional<Long> capQuota = writerQuotas.getQuota(cap);
                if (capQuota.isEmpty())
                    continue;
                long capChange = changes.entrySet().stream()
                        .filter(c -> c.getKey().equals(cap) || writerQuotas.getCaps(c.getKey()).contains(cap))
                        .mapToLong(Map.Entry::getValue)
                        .sum();
                if (capChange > 0 && writerQuotas.getUsage(cap).expectedUsage() + capChange > capQuota.get())
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
     *  The edge from its parent is recorded too. Otherwise nothing ties the writer to the parent until the
     *  parent's pointer update has been processed, and the commit's own blocks for it would be charged to
     *  the owner alone, past any cap over the parent.
     *
     *  Until the commit lands the writer is provisional: it may set only the pointer the commit carries for it, as
     *  another write it let in would be forgotten with the commit if that is refused.
     *
     * @param ownUpdate the pointer update the commit carries for the writer, if any
     * @return what takes the registration back if the commit is refused, leaving alone what a committed parent
     *         names: a writer it made or linked would otherwise stay registered, and write outside every cap once
     *         its parent is gone
     */
    public BulkCommitStorage.Registration registerNewWriter(PublicKeyHash owner, PublicKeyHash parent, PublicKeyHash writer,
                                                             Optional<SignedPointerUpdate> ownUpdate) {
        BulkCommitStorage.Registration nothing = registration(() -> {}, () -> {});
        Object lock = healLocks.computeIfAbsent(owner, o -> new Object());
        synchronized (lock) {
            boolean writerAdded = false, rowAdded = false;
            try {
                String username = core.getUsername(owner).join();
                if (! quotaAdmin.getLocalUsernames().contains(username))
                    return nothing;
                // The parent first: a writer registered without its edge would write under no cap. The
                // parent has a pointer already, so the self heal finds it if the store is missing it.
                String parentUser;
                try {
                    parentUser = usageStore.getOwner(parent);
                } catch (IllegalStateException absent) {
                    if (! registerMissedWriters(owner, parent))
                        return nothing;
                    parentUser = usageStore.getOwner(parent);
                }
                // only the owner's own writers vouch for a writer in their space: another user's key, sent in a
                // commit for this owner, must not register one here, whatever the rest of that commit does
                if (! username.equals(parentUser) || parent.equals(writer) || writer.equals(owner))
                    return nothing;
                long expires = System.currentTimeMillis() + PROVISIONAL_MILLIS;
                Optional<byte[]> signed = ownUpdate.map(u -> u.signed);
                String writerUser;
                try {
                    writerUser = usageStore.getOwner(writer);
                } catch (IllegalStateException absent) {
                    usageStore.addUserIfAbsent(username);
                    // Provisional before it is registered, so no other pointer of its lands in between, on any server.
                    // A row for it left by an attempt that never registered it, cut short by a restart, gives way.
                    usageStore.removeProvisional(writer, parent);
                    rowAdded = usageStore.addProvisional(new ProvisionalWriter(writer, parent, owner, true, signed, expires));
                    usageStore.addWriter(username, writer);
                    writerUser = username;
                    writerAdded = true;
                }
                // a writer of another user's stays theirs: an edge to it would count their data under this owner's caps
                if (! username.equals(writerUser))
                    return nothing;
                // in before the edge it may add, so a restart before the commit is resolved leaves a row to take it back
                // by; a row another commit still creating the writer has in stays theirs
                if (! writerAdded)
                    rowAdded = usageStore.addProvisional(new ProvisionalWriter(writer, parent, owner, false, signed, expires));
                boolean edgeAdded = usageStore.addOwnedKey(parent, writer);
                boolean newWriter = writerAdded;
                if (rowAdded && ! newWriter && ! edgeAdded) {
                    usageStore.removeProvisional(writer, parent);
                    return nothing;
                }
                boolean ownsRow = rowAdded;
                // A commit made alongside this one may have landed the same writer under this parent meanwhile, and
                // what a committed parent names stays. A writer left with no parent goes, whichever commit
                // registered it and whatever its own pointer says: it would write under no cap. Under the
                // registration lock, so no registration comes in between; an edge recorded as a pointer lands
                // can, and is recorded again when the queue reaches that parent's update.
                return registration(() -> {
                    if (! ownsRow)
                        return;
                    try {
                        usageStore.removeProvisional(writer, parent);
                    } catch (Exception e) {
                        // the commit has landed regardless: its row goes when it is found landed, or once it expires
                        LOG.log(Level.WARNING, "Couldn't remove the row of landed writer " + writer + " of owner " + owner, e);
                    }
                }, () -> {
                    synchronized (lock) {
                        try {
                            if (edgeAdded && ! namesWriter(owner, parent, writer))
                                usageStore.removeOwnedKey(parent, writer);
                            if ((newWriter || edgeAdded) && ! owned(usageStore, owner, writer))
                                forgetWriter(username, owner, writer);
                            // only once it is forgotten or kept, or a pointer of its could land in between
                            if (ownsRow)
                                usageStore.removeProvisional(writer, parent);
                        } catch (Exception e) {
                            // its row stays, so the registration is taken back again once it expires
                            LOG.log(Level.WARNING, "Couldn't take back new writer " + writer + " of owner " + owner, e);
                        }
                    }
                });
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Couldn't register new writer " + writer + " of owner " + owner, e);
                // nothing half done stays: a writer added here without its edge would write under no cap
                try {
                    if (writerAdded)
                        usageStore.removeWriter(writer);
                    else if (rowAdded)
                        usageStore.removeProvisional(writer, parent);
                } catch (Exception f) {
                    LOG.log(Level.WARNING, "Couldn't take back new writer " + writer + " of owner " + owner, f);
                }
                return nothing;
            }
        }
    }

    private static BulkCommitStorage.Registration registration(Runnable landed, Runnable undo) {
        return new BulkCommitStorage.Registration() {
            @Override
            public void landed() {
                landed.run();
            }

            @Override
            public void undo() {
                undo.run();
            }
        };
    }

    /** Forget a writer that a refused commit registered or linked and that nothing owns now, with its bytes in
     *  flight, and any writer registered under it meanwhile: with nothing above them they would write under no
     *  cap. Its usage goes as a dropped child's does, as a write of its own may have landed in the meantime. */
    private void forgetWriter(String username, PublicKeyHash owner, PublicKeyHash writer) {
        getUsage(username, usageStore).clearPending(writer);
        WriterUsage registered;
        try {
            registered = usageStore.getUsage(writer);
        } catch (IllegalStateException gone) {
            return; // forgotten already, with its children, by an undo nested in this one
        }
        if (registered.target().isPresent())
            processMutablePointerEvent(usageStore, owner, writer, registered.target(), MaybeMultihash.empty(),
                    mutable, quotaAdmin, dht, hasher);
        usageStore.removeWriter(writer);
        for (PublicKeyHash child : registered.ownedKeys()) {
            String childUser;
            try {
                childUser = usageStore.getOwner(child);
            } catch (IllegalStateException gone) {
                continue; // forgotten already, with the writer's own update just processed
            }
            if (username.equals(childUser) && ! owned(usageStore, owner, child))
                forgetWriter(username, owner, child);
        }
    }

    /** Whether the parent's committed root names the writer as one it owns. Only the writer's own proof is read: the
     *  parent may be a sharee's, whose root can name a key this server won't read, and that mustn't keep a
     *  registration whose commit was refused. A root whose way to that proof can't be read, its owned key champ
     *  pointed at such a block for instance, names nothing, as it vouches for nothing when a commit registers one. */
    private boolean namesWriter(PublicKeyHash owner, PublicKeyHash parent, PublicKeyHash writer) {
        MaybeMultihash root = mutable.getPointerTarget(owner, parent, dht).join().updated;
        try {
            return rootOwnsWriter(owner, parent, root, writer);
        } catch (Exception unreadable) {
            return false;
        }
    }

    /** Whether a root of the parent directly owns the writer, failing if the way to the writer's proof can't be read */
    private boolean rootOwnsWriter(PublicKeyHash owner, PublicKeyHash parent, MaybeMultihash root, PublicKeyHash writer) {
        List<Multihash> us = List.of(ourId.bareMultihash());
        return DeletableContentAddressedStorage.directlyOwnsKey(owner, parent, root, writer,
                (h, s) -> DeletableContentAddressedStorage.getWriterData(us, owner, h, s, false, ourId, hasher, dht), dht, hasher).join();
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
                if (! known.contains(owner)) {
                    usageStore.addWriter(username, owner);
                    added.add(owner);
                }
                while (! toVisit.isEmpty()) {
                    PublicKeyHash current = toVisit.poll();
                    if (! visited.add(current))
                        continue;
                    Set<PublicKeyHash> named = DeletableContentAddressedStorage.getDirectOwnedKeys(owner, current, mutable,
                            (h, s) -> DeletableContentAddressedStorage.getWriterData(us, owner, h, s, false, ourId, hasher, dht),
                            dht, hasher).join();
                    for (PublicKeyHash child : named) {
                        if (added.contains(child))
                            continue;
                        if (known.contains(child)) {
                            toVisit.add(child);
                            continue;
                        }
                        // a key of another user's named here is theirs, as it is when its name arrives, and so is
                        // what it names: its tree is in their space, and isn't walked for the owner's keys
                        try {
                            if (! username.equals(usageStore.getOwner(child)))
                                continue;
                        } catch (IllegalStateException unregistered) {}
                        usageStore.addWriter(username, child);
                        added.add(child);
                        // under the writer it was found named by, as any key the queue registers is: with no edge
                        // it would write under no cap, and outlive the revocation of what names it
                        usageStore.addOwnedKey(current, child);
                        toVisit.add(child);
                    }
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
