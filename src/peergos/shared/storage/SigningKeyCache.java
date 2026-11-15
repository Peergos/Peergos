package peergos.shared.storage;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

import java.util.*;

/** Keys addressed by their hash never change, so they can be shared process wide. */
class SigningKeyCache {
    private static final LRUCache<PublicKeyHash, PublicSigningKey> cache = new LRUCache<>(10_000);

    static synchronized Optional<PublicSigningKey> get(PublicKeyHash hash) {
        return Optional.ofNullable(cache.get(hash));
    }

    static synchronized void put(PublicKeyHash hash, PublicSigningKey key) {
        cache.put(hash, key);
    }
}
