package peergos.server;

import peergos.server.storage.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.util.*;
import java.util.concurrent.*;

public class RegisteredUserKeyFilter {

    private static final int TEN_MINUTES = 600_000;
    private final CoreNode core;
    private final MutablePointers mutable;
    private final DeletableContentAddressedStorage dht;
    private final Hasher hasher;
    private final Map<PublicKeyHash, Boolean> allowedKeys = new ConcurrentHashMap<>();

    public RegisteredUserKeyFilter(CoreNode core, MutablePointers mutable, DeletableContentAddressedStorage dht, Hasher hasher) {
        this.core = core;
        this.mutable = mutable;
        this.dht = dht;
        this.hasher = hasher;
        ForkJoinPool.commonPool().submit(() -> {
            while(true) {
                try {
                    reloadKeys();
                    Thread.sleep(TEN_MINUTES);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void reloadKeys() {
        try {
            List<String> usernames = core.getUsernames("").get();
            Set<PublicKeyHash> updated = new HashSet<>();
            for (String username : usernames) {
                updated.addAll(DeletableContentAddressedStorage.getOwnedKeysRecursive(username, core, mutable,
                        (h, s) -> DeletableContentAddressedStorage.getWriterData(Collections.emptyList(), h, s, dht), dht, hasher).join());
            }
            Set<PublicKeyHash> toRemove = new HashSet<>();
            for (PublicKeyHash hash : allowedKeys.keySet())
                if (! updated.contains(hash))
                    toRemove.add(hash);
            for (PublicKeyHash hash : toRemove)
                allowedKeys.put(hash, false);
            for (PublicKeyHash hash : updated)
                allowedKeys.put(hash, true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isAllowed(PublicKeyHash signerHash) {
        Boolean value = allowedKeys.get(signerHash);
        if (value != null)
            return value;
        // slow reload
        reloadKeys();
        return allowedKeys.getOrDefault(signerHash, false);
    }
}
