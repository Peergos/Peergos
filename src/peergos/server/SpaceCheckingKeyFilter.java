package peergos.server;

import java.util.concurrent.atomic.*;
import java.util.logging.*;

import peergos.server.space.*;
import peergos.server.storage.*;
import peergos.server.util.*;

import peergos.server.corenode.*;
import peergos.server.mutable.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.time.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private final ContentAddressedStorage dht;
    private final UserQuotas quotaSupplier;
    private final JdbcSpaceRequests spaceRequests;
    private final Path statePath;
    private final State state;
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private final BlockingQueue<MutableEvent> mutableQueue = new ArrayBlockingQueue<>(1000);

    /**
     *
     * @param core
     * @param mutable
     * @param dht
     * @param quotaSupplier The quota supplier
     * @param statePath path to local file with user usages
     */
    public SpaceCheckingKeyFilter(CoreNode core,
                                  MutablePointers mutable,
                                  ContentAddressedStorage dht,
                                  UserQuotas quotaSupplier,
                                  JdbcSpaceRequests spaceRequests,
                                  Path statePath) {
        this.core = core;
        this.mutable = mutable;
        this.dht = dht;
        this.quotaSupplier = quotaSupplier;
        this.spaceRequests = spaceRequests;
        this.statePath = statePath;
        this.state = initState(statePath, mutable, dht);
        new Thread(() -> {
            while (isRunning.get()) {
                try {
                    MutableEvent event = mutableQueue.take();
                    processMutablePointerEvent(event);
                } catch (InterruptedException e) {}
            }
        }, "SpaceCheckingKeyFilter").start();
        //add shutdown-hook to call close
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "SpaceChecker shutdown"));
    }

    public static class State implements Cborable {
        final Map<PublicKeyHash, Stat> currentView;
        final Map<String, Usage> usage;

        public State(Map<PublicKeyHash, Stat> currentView, Map<String, Usage> usage) {
            this.currentView = currentView;
            this.usage = usage;
        }

        @Override
        public CborObject toCbor() {
            TreeMap<CborObject, ? extends Cborable> viewsMap = currentView.entrySet()
                .stream()
                .collect(Collectors.toMap(
                    e -> e.getKey().toCbor(),
                    e -> (Cborable) (e.getValue()),
                    (a,b) -> a,
                    TreeMap::new
                ));

            CborObject.CborMap views = new CborObject.CborMap(viewsMap);
            CborObject.CborMap usages = CborObject.CborMap.build(usage);
            Map<String, Cborable> map = new HashMap<>();
            map.put("views", views);
            map.put("usages", usages);
            return CborObject.CborMap.build(map);
        }

        public static State fromCbor(CborObject cbor) {
            CborObject.CborMap map = (CborObject.CborMap) cbor;
            CborObject.CborMap viewsMap = (CborObject.CborMap) map.get("views");
            CborObject.CborMap usagesMap = (CborObject.CborMap) map.get("usages");

            return new State(viewsMap.getMap(PublicKeyHash::fromCbor, Stat::fromCbor),
                    usagesMap.getMap(e -> ((CborObject.CborString) e).value, Usage::fromCbor));
        }

        public Map<String, Usage> getUsage() {
            return new ConcurrentHashMap<>(usage);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            State state = (State) o;

            if (currentView != null ? !currentView.equals(state.currentView) : state.currentView != null) return false;
            return usage != null ? usage.equals(state.usage) : state.usage == null;
        }

        @Override
        public int hashCode() {
            int result = currentView != null ? currentView.hashCode() : 0;
            result = 31 * result + (usage != null ? usage.hashCode() : 0);
            return result;
        }
    }

    public static class Stat implements Cborable {
        public final String owner;
        private MaybeMultihash target;
        private long directRetainedStorage;
        private Set<PublicKeyHash> ownedKeys;

        public Stat(String owner, MaybeMultihash target, long directRetainedStorage, Set<PublicKeyHash> ownedKeys) {
            this.owner = owner;
            this.target = target;
            this.directRetainedStorage = directRetainedStorage;
            this.ownedKeys = ownedKeys;
        }

        public synchronized void update(MaybeMultihash target, Set<PublicKeyHash> ownedKeys, long retainedStorage) {
            this.target = target;
            this.ownedKeys = Collections.unmodifiableSet(ownedKeys);
            this.directRetainedStorage = retainedStorage;
        }

        public synchronized long getDirectRetainedStorage() {
            return directRetainedStorage;
        }

        public synchronized MaybeMultihash getRoot() {
            return target;
        }

        public synchronized Set<PublicKeyHash> getOwnedKeys() {
            return Collections.unmodifiableSet(ownedKeys);
        }

        @Override
        public CborObject toCbor() {
            Map<String, Cborable> map = new HashMap<>();
            map.put("owner", new CborObject.CborString(owner));
            map.put("target", target);
            map.put("storage", new CborObject.CborLong(directRetainedStorage));
            map.put("ownedKey", new CborObject.CborList(ownedKeys.stream().collect(Collectors.toList())));
            return CborObject.CborMap.build(map);
        }

        public static Stat fromCbor(Cborable cbor) {
            CborObject.CborMap map = (CborObject.CborMap) cbor;
            String owner = map.getString("owner");
            MaybeMultihash target = map.get("target", MaybeMultihash::fromCbor);
            long storage  = map.getLong("storage");
            List<PublicKeyHash> ownedKeys = map.getList("ownedKey").map(PublicKeyHash::fromCbor);
            return new Stat(owner, target, storage, new HashSet<>(ownedKeys));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Stat stat = (Stat) o;

            if (directRetainedStorage != stat.directRetainedStorage) return false;
            if (owner != null ? !owner.equals(stat.owner) : stat.owner != null) return false;
            if (target != null ? !target.equals(stat.target) : stat.target != null) return false;
            return ownedKeys != null ? ownedKeys.equals(stat.ownedKeys) : stat.ownedKeys == null;
        }

        @Override
        public int hashCode() {
            int result = owner != null ? owner.hashCode() : 0;
            result = 31 * result + (target != null ? target.hashCode() : 0);
            result = 31 * result + (int) (directRetainedStorage ^ (directRetainedStorage >>> 32));
            result = 31 * result + (ownedKeys != null ? ownedKeys.hashCode() : 0);
            return result;
        }
    }

    public static class Usage implements Cborable {
        private long totalBytes;
        private boolean errored;
        private Map<PublicKeyHash, Long> pending = new HashMap<>();

        public Usage(long totalBytes) {
            this.totalBytes = totalBytes;
        }

        protected synchronized void confirmUsage(PublicKeyHash writer, long usageDelta) {
            pending.remove(writer);
            totalBytes += usageDelta;
            errored = false;
        }

        protected synchronized void addPending(PublicKeyHash writer, long usageDelta) {
            pending.put(writer, pending.getOrDefault(writer, 0L) + usageDelta);
        }

        protected synchronized void clearPending(PublicKeyHash writer) {
            pending.remove(writer);
        }

        protected synchronized long getPending(PublicKeyHash writer) {
            return pending.getOrDefault(writer, 0L);
        }

        protected synchronized long expectedUsage() {
            return totalBytes + pending.values().stream().mapToLong(x -> x).sum();
        }

        protected synchronized void setErrored(boolean errored) {
            this.errored = errored;
        }

        protected synchronized boolean isErrored() {
            return errored;
        }

        @Override
        public CborObject toCbor() {
            return new CborObject.CborLong(totalBytes);
        }

        public static Usage fromCbor(Cborable cborable) {
            long usage = ((CborObject.CborLong) cborable).value;
            return new Usage(usage);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Usage usage1 = (Usage) o;

            if (totalBytes != usage1.totalBytes) return false;
            return pending != null ? pending.equals(usage1.pending) : usage1.pending == null;
        }

        @Override
        public int hashCode() {
            int result = (int) (totalBytes ^ (totalBytes >>> 32));
            result = 31 * result + (pending != null ? pending.hashCode() : 0);
            return result;
        }
    }

    /**
     *
     */
    private static State initState(Path statePath,
                                   MutablePointers mutable,
                                   ContentAddressedStorage dht) {
        State state;
        try {
            // Read stored usages and update current view.
            state = load(statePath);
            Logging.LOG().info("Successfully loaded usage-state from "+ statePath);
        } catch (IOException ioe) {
            Logging.LOG().info("Could not read usage-state from "+ statePath);
            // calculate usage from scratch
            state = new State(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }
        Logging.LOG().info("Checking for updated mutable pointers...");
        long t1 = System.currentTimeMillis();
        for (Map.Entry<PublicKeyHash, Stat> entry : new HashSet<>(state.currentView.entrySet())) {
            PublicKeyHash writerKey = entry.getKey();
            Stat stat = entry.getValue();
            Logging.LOG().info("Checking for updates from user: " + stat.owner + ", writer key: " + writerKey);

            try {
                PublicKeyHash owner = writerKey; //NB: owner is a dummy value
                MaybeMultihash rootHash = mutable.getPointerTarget(owner, writerKey, dht).join();
                boolean isChanged = ! stat.target.equals(rootHash);
                if (isChanged) {
                    Logging.LOG().info("Root hash changed from " + stat.target + " to " + rootHash);
                    long updatedSize = dht.getRecursiveBlockSize(rootHash.get()).get();
                    long deltaUsage = updatedSize - stat.directRetainedStorage;
                    state.usage.get(stat.owner).confirmUsage(writerKey, deltaUsage);
                    Set<PublicKeyHash> directOwnedKeys = WriterData.getDirectOwnedKeys(owner, writerKey, mutable, dht).join();
                    List<PublicKeyHash> newOwnedKeys = directOwnedKeys.stream()
                            .filter(key -> !stat.ownedKeys.contains(key))
                            .collect(Collectors.toList());
                    for (PublicKeyHash newOwnedKey : newOwnedKeys) {
                        state.currentView.putIfAbsent(newOwnedKey, new Stat(stat.owner, MaybeMultihash.empty(), 0, Collections.emptySet()));
                        processMutablePointerEvent(state, owner, newOwnedKey, MaybeMultihash.empty(),
                                mutable.getPointerTarget(owner, newOwnedKey, dht).get(), mutable, dht);
                    }
                    stat.update(rootHash, directOwnedKeys, updatedSize);
                    Logging.LOG().info("Updated space used by " + writerKey + " to " + updatedSize);
                }
            } catch (Throwable t) {
                Logging.LOG().log(Level.WARNING, "Failed calculating usage for " + stat.owner, t);
            }
        }
        long t2 = System.currentTimeMillis();
        Logging.LOG().info(LocalDateTime.now() + " Finished updating space usage for all usernames in " + (t2 - t1)/1000 + " s");

        return state;
    }

    /**
     * Write current view of usages to this.statePath, completing any pending operations
     */
    private synchronized void close() {
        try {
            isRunning.set(false);
            store();
            Logging.LOG().info("Successfully stored usage-state to " + this.statePath);
        } catch (Throwable t) {
            Logging.LOG().info("Failed to  store "+ this);
            t.printStackTrace();
        }
    }
    /**
     * Read local file with cached user usages.
     * @return previous usages
     * @throws IOException
     */
    private static State load(Path statePath) throws IOException {
        Logging.LOG().info("Reading state from "+ statePath +" which exists ? "+ Files.exists(statePath) +" from cwd "+ System.getProperty("cwd"));
        byte[] data = Files.readAllBytes(statePath);
        CborObject object = CborObject.deserialize(new CborDecoder(new ByteArrayInputStream(data)), 1000);
        return State.fromCbor(object);
    }

    /**
     * Store usages
     * @throws IOException
     */
    private synchronized void store() throws IOException {
        byte[] serialized = state.toCbor().serialize();
        Logging.LOG().info("Writing "+ serialized.length +" bytes to "+ statePath);
        Files.write(
            statePath,
            serialized,
            StandardOpenOption.CREATE);
    }

    /**
     * Walk the virtual file-system to calculate space used by each owner not already checked
     */
    public void calculateUsage() {
        try {
            List<String> usernames = quotaSupplier.getLocalUsernames();
            for (String username : usernames) {
                Logging.LOG().info("Calculating space usage of "+username);
                Optional<PublicKeyHash> publicKeyHash = core.getPublicKeyHash(username).get();
                publicKeyHash.ifPresent(keyHash -> processCorenodeEvent(username, keyHash));
                LOG.info("Updated space usage of user: " + username + " to " + state.usage.get(username).totalBytes);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
    }

    public void accept(CorenodeEvent event) {
        state.currentView.computeIfAbsent(event.keyHash, k -> new Stat(event.username, MaybeMultihash.empty(), 0, Collections.emptySet()));
        state.usage.putIfAbsent(event.username, new Usage(0));
        ForkJoinPool.commonPool().submit(() -> processCorenodeEvent(event.username, event.keyHash));
    }

    /** Update our view of the world because a user has changed their public key (or registered)
     *
     * @param username
     * @param writer
     */
    public void processCorenodeEvent(String username, PublicKeyHash writer) {
        try {
            state.usage.putIfAbsent(username, new Usage(0));
            Set<PublicKeyHash> childrenKeys = WriterData.getDirectOwnedKeys(writer, writer, mutable, dht).join();
            state.currentView.computeIfAbsent(writer, k -> new Stat(username, MaybeMultihash.empty(), 0, childrenKeys));
            Stat current = state.currentView.get(writer);
            MaybeMultihash updatedRoot = mutable.getPointerTarget(writer, writer, dht).get();
            processMutablePointerEvent(state, writer, writer, current.target, updatedRoot, mutable, dht);
            for (PublicKeyHash childKey : childrenKeys) {
                processCorenodeEvent(username, childKey);
            }
        } catch (Throwable e) {
            LOG.severe("Error loading storage for user: " + username);
            Exceptions.getRootCause(e).printStackTrace();
        }
    }

    public void accept(MutableEvent event) {
        mutableQueue.add(event);
        try {
            HashCasPair hashCasPair = dht.getSigningKey(event.writer)
                    .thenApply(signer -> HashCasPair.fromCbor(CborObject.fromByteArray(signer.get()
                            .unsignMessage(event.writerSignedBtreeRootHash)))).get();
            Set<PublicKeyHash> updatedOwned =
                    WriterData.getDirectOwnedKeys(event.writer, hashCasPair.updated, dht).join();
            Stat current = state.currentView.get(event.writer);
            for (PublicKeyHash owned : updatedOwned) {
                state.currentView.computeIfAbsent(owned,
                        k -> new Stat(current.owner, MaybeMultihash.empty(), 0, Collections.emptySet()));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processMutablePointerEvent(MutableEvent event) {
        try {
            HashCasPair hashCasPair = dht.getSigningKey(event.writer)
                    .thenApply(signer -> HashCasPair.fromCbor(CborObject.fromByteArray(signer.get()
                            .unsignMessage(event.writerSignedBtreeRootHash)))).get();
            processMutablePointerEvent(state, event.owner, event.writer, hashCasPair.original, hashCasPair.updated, mutable, dht);
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
    }

    private static void processMutablePointerEvent(State state,
                                                   PublicKeyHash owner,
                                                   PublicKeyHash writer,
                                                   MaybeMultihash existingRoot,
                                                   MaybeMultihash newRoot,
                                                   MutablePointers mutable,
                                                   ContentAddressedStorage dht) {
        if (existingRoot.equals(newRoot))
            return;
        Stat current = state.currentView.get(writer);
        if (current == null)
            throw new IllegalStateException("Unknown writer key hash: " + writer);
        if (! newRoot.isPresent()) {
            current.update(MaybeMultihash.empty(), Collections.emptySet(), 0);
            if (existingRoot.isPresent()) {
                try {
                    // subtract data size from orphaned child keys (this assumes the keys form a tree without dupes)
                    Set<PublicKeyHash> updatedOwned =
                            WriterData.getDirectOwnedKeys(writer, newRoot, dht).join();
                    processRemovedOwnedKeys(state, owner, updatedOwned, mutable, dht);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, e.getMessage(), e);
                }
            }
            return;
        }

        try {
            synchronized (current) {
                long changeInStorage = dht.getChangeInContainedSize(current.target, newRoot.get()).get();
                Set<PublicKeyHash> updatedOwned =
                        WriterData.getDirectOwnedKeys(writer, newRoot, dht).join();
                for (PublicKeyHash owned : updatedOwned) {
                    state.currentView.computeIfAbsent(owned,
                            k -> new Stat(current.owner, MaybeMultihash.empty(), 0, Collections.emptySet()));
                }
                Usage currentUsage = state.usage.get(current.owner);
                currentUsage.confirmUsage(writer, changeInStorage);

                HashSet<PublicKeyHash> removedChildren = new HashSet<>(current.getOwnedKeys());
                removedChildren.removeAll(updatedOwned);
                processRemovedOwnedKeys(state, owner, removedChildren, mutable, dht);
                current.update(newRoot, updatedOwned, current.directRetainedStorage + changeInStorage);
            }
        } catch (Exception e) {
            Exceptions.getRootCause(e).printStackTrace();
        }
    }

    private static void processRemovedOwnedKeys(State state,
                                                PublicKeyHash owner,
                                                Set<PublicKeyHash> removed,
                                                MutablePointers mutable,
                                                ContentAddressedStorage dht) {
        for (PublicKeyHash ownedKey : removed) {
            try {
                MaybeMultihash currentTarget = mutable.getPointerTarget(owner, ownedKey, dht).get();
                processMutablePointerEvent(state, owner, ownedKey, currentTarget, MaybeMultihash.empty(), mutable, dht);
            } catch (Exception e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
            }
        }
    }

    @Override
    public CompletableFuture<Long> getUsage(PublicKeyHash owner) {
        Stat stat = state.currentView.get(owner);
        if (stat == null)
            return Futures.errored(new IllegalStateException("Unknown identity key: " + owner));
        Usage usage = state.usage.get(stat.owner);
        if (usage == null)
            return Futures.errored(new IllegalStateException("No usage present for user: " + stat.owner));
        return CompletableFuture.completedFuture(usage.totalBytes);
    }

    @Override
    public CompletableFuture<Long> getQuota(PublicKeyHash owner, byte[] signedTime) {
        TimeLimited.isAllowedTime(signedTime, 120, dht, owner);
        Stat stat = state.currentView.get(owner);
        if (stat == null)
            return Futures.errored(new IllegalStateException("Unknown identity key: " + owner));
        return CompletableFuture.completedFuture(quotaSupplier.getQuota(stat.owner));
    }

    @Override
    public CompletableFuture<Boolean> requestSpace(PublicKeyHash owner, byte[] signedRequest) {
        // check request is valid
        Optional<PublicSigningKey> ownerOpt = dht.getSigningKey(owner).join();
        if (!ownerOpt.isPresent())
            throw new IllegalStateException("Couldn't retrieve owner key!");
        byte[] raw = ownerOpt.get().unsignMessage(signedRequest);
        CborObject cbor = CborObject.fromByteArray(raw);
        SpaceUsage.SpaceRequest req = SpaceUsage.SpaceRequest.fromCbor(cbor);
        if (req.utcMillis < System.currentTimeMillis() - 30_000)
            throw new IllegalStateException("Stale auth time in space request!");
        // TODO check proof of payment (if required)

        // TODO check user is signed up to this server
        return CompletableFuture.completedFuture(spaceRequests.addSpaceRequest(req.username, signedRequest));
    }

    public boolean allowWrite(PublicKeyHash writer, int size) {
        Stat stat = state.currentView.get(writer);
        if (stat == null)
            throw new IllegalStateException("Unknown writing key hash: " + writer);

        Usage usage = state.usage.get(stat.owner);
        long quota = quotaSupplier.getQuota(stat.owner);
        long expectedUsage = usage.expectedUsage();
        boolean errored = usage.isErrored();
        if ((! errored && expectedUsage + size > quota) || (errored && expectedUsage + size > quota + USAGE_TOLERANCE)) {
            long pending = usage.getPending(writer);
            usage.clearPending(writer);
            usage.setErrored(true);
            throw new IllegalStateException("Storage quota reached! \nUsed "
                    + usage.totalBytes + " out of " + quota + " bytes. Rejecting write of size " + (size + pending) + ". \n" +
                    "Please delete some files or request more space.");
        }
        usage.addPending(writer, size);
        return true;
    }
}
