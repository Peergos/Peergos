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
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
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
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private final BlockingQueue<MutableEvent> mutableQueue = new ArrayBlockingQueue<>(1000);

    public SpaceCheckingKeyFilter(CoreNode core,
                                  MutablePointers mutable,
                                  DeletableContentAddressedStorage dht,
                                  Hasher hasher,
                                  QuotaAdmin quotaAdmin,
                                  UsageStore usageStore) {
        this.core = core;
        this.mutable = mutable;
        this.dht = dht;
        this.hasher = hasher;
        this.quotaAdmin = quotaAdmin;
        this.usageStore = usageStore;
        new Thread(() -> {
            while (isRunning.get()) {
                try {
                    MutableEvent event = mutableQueue.take();
                    processMutablePointerEvent(event);
                } catch (InterruptedException e) {}
                catch (Exception e) {
                    e.printStackTrace();
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
        List<Multihash> us = List.of(dht.id().join().bareMultihash());
        for (PublicKeyHash writerKey : writers) {
            WriterUsage writerUsage = store.getUsage(writerKey);
            Logging.LOG().info("Checking for updates from user: " + writerUsage.owner + ", writer key: " + writerKey);

            try {
                PublicKeyHash owner = writerKey; //NB: owner is a dummy value
                MaybeMultihash rootHash = mutable.getPointerTarget(owner, writerKey, dht).join().updated;
                boolean isChanged = ! writerUsage.target().equals(rootHash);
                if (isChanged) {
                    Logging.LOG().info("Root hash changed from " + writerUsage.target() + " to " + rootHash);
                    long updatedSize = dht.getRecursiveBlockSize((Cid)rootHash.get()).get();
                    long deltaUsage = updatedSize - writerUsage.directRetainedStorage();
                    store.confirmUsage(writerUsage.owner, writerKey, deltaUsage, false);
                    Set<PublicKeyHash> directOwnedKeys = DeletableContentAddressedStorage.getDirectOwnedKeys(owner, writerKey, mutable,
                            (h, s) -> DeletableContentAddressedStorage.getWriterData(us, h,s, dht), dht, hasher).join();
                    List<PublicKeyHash> newOwnedKeys = directOwnedKeys.stream()
                            .filter(key -> !writerUsage.ownedKeys().contains(key))
                            .collect(Collectors.toList());
                    for (PublicKeyHash newOwnedKey : newOwnedKeys) {
                        store.addWriter(writerUsage.owner, newOwnedKey);
                        processMutablePointerEvent(store, owner, newOwnedKey, MaybeMultihash.empty(),
                                mutable.getPointerTarget(owner, newOwnedKey, dht).get().updated, mutable, dht, hasher);
                    }
                    HashSet<PublicKeyHash> removedOwnedKeys = new HashSet<>(writerUsage.ownedKeys());
                    removedOwnedKeys.removeAll(directOwnedKeys);
                    store.updateWriterUsage(writerKey, rootHash, removedOwnedKeys, new HashSet<>(newOwnedKeys), updatedSize);
                    Logging.LOG().info("Updated space used by " + writerKey + " to " + updatedSize);
                }
            } catch (Throwable t) {
                Logging.LOG().log(Level.WARNING, "Failed calculating usage for " + writerUsage.owner, t);
            }
        }
        long t2 = System.currentTimeMillis();
        Logging.LOG().info(LocalDateTime.now() + " Finished updating space usage for all usernames in " + (t2 - t1)/1000 + " s");
    }

    public CompletableFuture<Boolean> accept(CorenodeEvent event) {
        usageStore.addUserIfAbsent(event.username);
        usageStore.addWriter(event.username, event.keyHash);
        return CompletableFuture.supplyAsync(() -> processCorenodeEvent(event.username, event.keyHash), ForkJoinPool.commonPool());
    }

    /** Update our view of the world because a user has changed their public key (or registered)
     *
     * @param username
     * @param writer
     */
    private boolean processCorenodeEvent(String username, PublicKeyHash writer) {
        try {
            processCorenodeEvent(username, writer, usageStore, dht, mutable, hasher);
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
            e.printStackTrace();
        }
    }

    public static void processCorenodeEvent(String username,
                                            PublicKeyHash owner,
                                            UsageStore usageStore,
                                            DeletableContentAddressedStorage dht,
                                            MutablePointers mutable,
                                            Hasher hasher) {
        // get current set of owned keys from usage db, and traverse filesystem
        // only if a pointer has changed since last usage update
        Set<PublicKeyHash> allUserKeys = usageStore.getAllWriters(owner);
        processCorenodeEvent(username, owner, allUserKeys, usageStore, dht, mutable, hasher);
    }
    public static void processCorenodeEvent(String username,
                                            PublicKeyHash owner,
                                            Set<PublicKeyHash> allUserKeys,
                                            UsageStore usageStore,
                                            DeletableContentAddressedStorage dht,
                                            MutablePointers mutable,
                                            Hasher hasher) {
        usageStore.addUserIfAbsent(username);

        for (PublicKeyHash writerKey : allUserKeys) {
            usageStore.addWriter(username, writerKey);
            WriterUsage current = usageStore.getUsage(writerKey);
            MaybeMultihash updatedRoot = mutable.getPointerTarget(owner, writerKey, dht).join().updated;
            processMutablePointerEvent(usageStore, owner, writerKey, current.target(), updatedRoot, mutable, dht, hasher);
        }
    }

    private static void prepareMutablePointerChange(MutableEvent event,
                                                    DeletableContentAddressedStorage dht,
                                                    UsageStore usageStore,
                                                    Hasher hasher) {
        List<Multihash> us = List.of(dht.id().join().bareMultihash());
        PointerUpdate pointerUpdate = dht.getSigningKey(event.owner, event.writer)
                .thenApply(signer -> PointerUpdate.fromCbor(CborObject.fromByteArray(signer.get()
                        .unsignMessage(event.writerSignedBtreeRootHash).join()))).join();
        Set<PublicKeyHash> updatedOwned =
                DeletableContentAddressedStorage.getDirectOwnedKeys(event.owner, event.writer, pointerUpdate.updated,
                        (h, s) -> DeletableContentAddressedStorage.getWriterData(us, h,s, dht), dht, hasher).join();
        WriterUsage current = usageStore.getUsage(event.writer);
        for (PublicKeyHash owned : updatedOwned) {
            usageStore.addWriter(current.owner, owned);
        }
    }

    private void processMutablePointerEvent(MutableEvent event) {
        try {
            PointerUpdate pointerUpdate = dht.getSigningKey(event.owner, event.writer)
                    .thenApply(signer -> PointerUpdate.fromCbor(CborObject.fromByteArray(signer.get()
                            .unsignMessage(event.writerSignedBtreeRootHash).join()))).join();
            processMutablePointerEvent(usageStore, event.owner, event.writer, pointerUpdate.original, pointerUpdate.updated,
                    mutable, dht, hasher);
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
                                                   DeletableContentAddressedStorage dht,
                                                   Hasher hasher) {
        if (existingRoot.equals(newRoot))
            return;
        WriterUsage current = state.getUsage(writer);
        if (current == null)
            throw new IllegalStateException("Unknown writer key hash: " + writer);
        List<Multihash> us = List.of(dht.id().join().bareMultihash());
        if (! newRoot.isPresent()) {
            LOG.info("Removing usage for (" + owner + ", " + writer + ") from " + current.directRetainedStorage());
            state.confirmUsage(current.owner, writer, -current.directRetainedStorage(), false);
            state.updateWriterUsage(writer, MaybeMultihash.empty(), Collections.emptySet(), Collections.emptySet(), 0);
            if (existingRoot.isPresent()) {
                try {
                    // subtract data size from orphaned child keys (this assumes the keys form a tree without dupes)
                    Set<PublicKeyHash> updatedOwned =
                            DeletableContentAddressedStorage.getDirectOwnedKeys(owner, writer, existingRoot,
                                    (h, s) -> DeletableContentAddressedStorage.getWriterData(us, h,s, dht),  dht, hasher).join();
                    processRemovedOwnedKeys(state, owner, updatedOwned, mutable, dht, hasher);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, e.getMessage(), e);
                }
            }
            return;
        }

        try {
            synchronized (current) {
                long t0 = System.nanoTime();
                long changeInStorage = dht.getChangeInContainedSize(current.target().toOptional().map(c -> (Cid) c), (Cid) newRoot.get()).get();
                long t1 = System.nanoTime();
                LOG.info("Calculating change in used space for (" + owner + ", " + writer + ") took " + (t1-t0)/1_000_000 + "mS");
                Set<PublicKeyHash> updatedOwned =
                        DeletableContentAddressedStorage.getDirectOwnedKeys(owner, writer, newRoot,
                                (h, s) -> DeletableContentAddressedStorage.getWriterData(us, h,s, dht), dht, hasher).join();
                for (PublicKeyHash owned : updatedOwned) {
                    state.addWriter(current.owner, owned);
                }
                state.confirmUsage(current.owner, writer, changeInStorage, false);

                HashSet<PublicKeyHash> removedChildren = new HashSet<>(current.ownedKeys());
                removedChildren.removeAll(updatedOwned);
                processRemovedOwnedKeys(state, owner, removedChildren, mutable, dht, hasher);
                HashSet<PublicKeyHash> addedOwnedKeys = new HashSet<>(updatedOwned);
                addedOwnedKeys.removeAll(current.ownedKeys());
                state.updateWriterUsage(writer, newRoot, removedChildren, addedOwnedKeys, current.directRetainedStorage() + changeInStorage);
                for (PublicKeyHash added : addedOwnedKeys) {
                    state.addWriter(current.owner, added);
                    WriterUsage currentAdded = state.getUsage(added);
                    MaybeMultihash updatedRoot = mutable.getPointerTarget(owner, added, dht).join().updated;
                    processMutablePointerEvent(state, owner, added, currentAdded.target(), updatedRoot, mutable, dht, hasher);
                }
                LOG.info("Updated usage for (" + owner + ", " + writer + ") from " + current.directRetainedStorage() + ", adding " + changeInStorage);
            }
        } catch (Exception e) {
            Exceptions.getRootCause(e).printStackTrace();
        }
    }

    private static void processRemovedOwnedKeys(UsageStore state,
                                                PublicKeyHash owner,
                                                Set<PublicKeyHash> removed,
                                                MutablePointers mutable,
                                                DeletableContentAddressedStorage dht,
                                                Hasher hasher) {
        for (PublicKeyHash ownedKey : removed) {
            try {
                MaybeMultihash currentTarget = mutable.getPointerTarget(owner, ownedKey, dht).get().updated;
                processMutablePointerEvent(state, owner, ownedKey, currentTarget, MaybeMultihash.empty(), mutable, dht, hasher);
            } catch (Exception e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
            }
        }
    }

    @Override
    public CompletableFuture<Long> getUsage(PublicKeyHash owner, byte[] signedTime) {
        TimeLimited.isAllowedTime(signedTime, 300, dht, owner);
        WriterUsage writerUsage = usageStore.getUsage(owner);
        if (writerUsage == null)
            return Futures.errored(new IllegalStateException("Unknown identity key: " + owner));
        UserUsage usage = usageStore.getUsage(writerUsage.owner);
        if (usage == null)
            return Futures.errored(new IllegalStateException("No usage present for user: " + writerUsage.owner));
        return CompletableFuture.completedFuture(usage.totalUsage());
    }

    @Override
    public CompletableFuture<PaymentProperties> getPaymentProperties(PublicKeyHash owner, boolean newClientSecret, byte[] signedTime) {
        return quotaAdmin.getPaymentProperties(owner, newClientSecret, signedTime);
    }

    @Override
    public CompletableFuture<Long> getQuota(PublicKeyHash owner, byte[] signedTime) {
        TimeLimited.isAllowedTime(signedTime, 24*3600, dht, owner);
        WriterUsage writerUsage = usageStore.getUsage(owner);
        if (writerUsage == null)
            return Futures.errored(new IllegalStateException("Unknown identity key: " + owner));
        return quotaAdmin.getQuota(owner, signedTime);
    }

    @Override
    public CompletableFuture<PaymentProperties> requestQuota(PublicKeyHash owner, byte[] signedRequest) {
        return quotaAdmin.requestQuota(owner, signedRequest);
    }

    public boolean allowWrite(PublicKeyHash writer, int size) {
        WriterUsage writerUsage = usageStore.getUsage(writer);
        if (writerUsage == null)
            throw new IllegalStateException("Unknown writing key hash: " + writer);

        UserUsage usage = usageStore.getUsage(writerUsage.owner);
        long quota = quotaAdmin.getQuota(writerUsage.owner);
        long expectedUsage = usage.expectedUsage();
        boolean errored = usage.isErrored();
        if ((! errored && expectedUsage + size > quota) || (errored && expectedUsage + size > quota + USAGE_TOLERANCE)) {
            long pending = usage.getPending(writer);
            usageStore.confirmUsage(writerUsage.owner, writer, 0, true);
            throw new IllegalStateException("Storage quota reached! \nUsed "
                    + usage.totalUsage() + " out of " + quota + " bytes. Rejecting write of size " + (size + pending) + ". \n" +
                    "Please delete some files or request more space.");
        }
        usageStore.addPendingUsage(writerUsage.owner, writer, size);
        return true;
    }
}
