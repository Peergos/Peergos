package peergos.server;

import peergos.server.corenode.*;
import peergos.server.mutable.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.util.*;
import java.util.concurrent.*;

public class SpaceCheckingKeyFilter {

    private final CoreNode core;
    private final MutablePointers mutable;
    private final ContentAddressedStorage dht;
    private final long defaultQuota;

    private final Map<PublicKeyHash, Stat> currentView = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> usage = new ConcurrentHashMap<>();

    private static class Stat {
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
            return ownedKeys;
        }
    }

    /**
     *
     * @param core
     * @param mutable
     * @param dht
     * @param defaultQuota The quota for users who aren't explicitly listed in the whitelist
     */
    public SpaceCheckingKeyFilter(CoreNode core, MutablePointers mutable, ContentAddressedStorage dht, long defaultQuota) {
        System.out.println("Using default user space quota of " + defaultQuota);
        this.core = core;
        this.mutable = mutable;
        this.dht = dht;
        this.defaultQuota = defaultQuota;
        loadAllOwners();
    }

    private void loadAllOwners() {
        try {
            List<String> usernames = core.getUsernames("").get();
            for (String username : usernames) {
                Optional<PublicKeyHash> publicKeyHash = core.getPublicKeyHash(username).get();
                publicKeyHash.ifPresent(keyHash -> processCorenodeEvent(username, keyHash));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void accept(CorenodeEvent event) {
        currentView.computeIfAbsent(event.keyHash, k -> new Stat(event.username, MaybeMultihash.empty(), 0, Collections.emptySet()));
        usage.putIfAbsent(event.username, 0L);
        ForkJoinPool.commonPool().submit(() -> processCorenodeEvent(event.username, event.keyHash));
    }

    public void processCorenodeEvent(String username, PublicKeyHash ownedKeyHash) {
        try {
            Set<PublicKeyHash> childrenKeys = WriterData.getDirectOwnedKeys(ownedKeyHash, mutable, dht);
            currentView.computeIfAbsent(ownedKeyHash, k -> new Stat(username, MaybeMultihash.empty(), 0, childrenKeys));
            Stat current = currentView.get(ownedKeyHash);
            MaybeMultihash updatedRoot = mutable.getPointerKeyHash(ownedKeyHash, dht).get();
            processMutablePointerEvent(ownedKeyHash, current.target, updatedRoot);
            for (PublicKeyHash childKey : childrenKeys) {
                processCorenodeEvent(username, childKey);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void accept(MutableEvent event) {
        ForkJoinPool.commonPool().submit(() -> {
            try {
                HashCasPair hashCasPair = dht.getSigningKey(event.writer)
                        .thenApply(signer -> HashCasPair.fromCbor(CborObject.fromByteArray(signer.get()
                                .unsignMessage(event.writerSignedBtreeRootHash)))).get();
                processMutablePointerEvent(event.writer, hashCasPair.original, hashCasPair.updated);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public void processMutablePointerEvent(PublicKeyHash writer, MaybeMultihash existingRoot, MaybeMultihash newRoot) {
        if (existingRoot.equals(newRoot))
            return;
        Stat current = currentView.get(writer);
        if (current == null)
            throw new IllegalStateException("Unknown writer key hash: " + writer);
        if (! newRoot.isPresent()) {
            current.update(MaybeMultihash.empty(), Collections.emptySet(), 0);
            return;
        }

        try {
            synchronized (current) {
                long updatedDirectSize = dht.getRecursiveBlockSize(newRoot.get()).get();
                Set<PublicKeyHash> updatedOwned = WriterData.getWriterData(writer, newRoot, dht).get().props.ownedKeys;
                for (PublicKeyHash owned : updatedOwned) {
                    currentView.computeIfAbsent(owned, k -> new Stat(current.owner, MaybeMultihash.empty(), 0, Collections.emptySet()));
                }
                while (true) {
                    Long currentUsage = usage.get(current.owner);
                    boolean casSucceeded = usage.replace(current.owner, currentUsage, currentUsage + updatedDirectSize - current.directRetainedStorage);
                    if (casSucceeded)
                        break;
                }
                current.update(newRoot, updatedOwned, updatedDirectSize);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private long getQuota(String username) {
        return defaultQuota;
    }

    private long getUsage(String username) {
        Long cached = usage.get(username);
        if (cached != null) {
            return cached;
        }
        throw new IllegalStateException("Unknown user!");
    }

    private String getOwner(PublicKeyHash writer) {
        Stat state = currentView.get(writer);
        if (state != null)
            return state.owner;
        throw new IllegalStateException("Unknown writing key hash: " + writer);
    }

    public boolean allowWrite(PublicKeyHash signerHash) {
        String owner = getOwner(signerHash);
        return getUsage(owner) < getQuota(owner);
    }
}
