package peergos.server;

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
    private final Map<PublicKeyHash, String> knownOwners = new ConcurrentHashMap<>();

    /**
     *
     * @param core
     * @param mutable
     * @param dht
     * @param defaultQuota The quota for users who aren't explicitly listed in the whitelist
     */
    public SpaceCheckingKeyFilter(CoreNode core, MutablePointers mutable, ContentAddressedStorage dht, long defaultQuota) {
        this.core = core;
        this.mutable = mutable;
        this.dht = dht;
        this.defaultQuota = defaultQuota;
    }

    private void reloadOwners() {
        try {
            List<String> usernames = core.getUsernames("").get();
            for (String username : usernames) {
                for (PublicKeyHash keyHash : WriterData.getOwnedKeysRecursive(username, core, mutable, dht)) {
                    knownOwners.put(keyHash, username);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long getQuota(String username) {
        return defaultQuota;
    }

    private long getUsage(String username) {
        Set<PublicKeyHash> allOwnedKeys = WriterData.getOwnedKeysRecursive(username, core, mutable, dht);
        return allOwnedKeys.stream()
                .map(keyHash -> {
                    try {
                        return mutable.getPointerKeyHash(keyHash, dht).get();
                    } catch (Exception e) {
                        return MaybeMultihash.empty();
                    }
                })
                .filter(MaybeMultihash::isPresent)
                .mapToLong(pointerTarget -> {
                    try {
                        return dht.getRecursiveBlockSize(pointerTarget.get()).get();
                    } catch (Exception e) {
                        return 0;
                    }
                }).sum();
    }

    private String getOwner(PublicKeyHash writer) {
        String owner = knownOwners.get(writer);
        if (owner != null)
            return owner;
        reloadOwners();
        return knownOwners.get(writer);
    }

    public boolean allowWrite(PublicKeyHash signerHash, int writeSize) {
        String owner = getOwner(signerHash);
        return getUsage(owner) + writeSize < getQuota(owner);
    }
}
