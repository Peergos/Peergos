package peergos.shared;

import peergos.shared.io.ipfs.Multihash;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.util.*;

/** A cache of cryptree nodes, grouped by the champ root they were read from.
 *
 *  Committing a chunk gives a new root, but leaves every other mapping unchanged, so an update moves the
 *  existing entries to the new root rather than copying them.
 */
public class CryptreeCache {

    private static final int MAX_ROOTS = 4;

    private final int cacheSize;
    private final LRUCache<Multihash, LRUCache<ByteArrayWrapper, Optional<CryptreeNode>>> byRoot;

    public CryptreeCache() {
        this(1_000);
    }

    public CryptreeCache(int cacheSize) {
        this.cacheSize = cacheSize;
        this.byRoot = new LRUCache<>(MAX_ROOTS);
    }

    public synchronized boolean containsKey(Pair<Multihash, ByteArrayWrapper> cacheKey) {
        LRUCache<ByteArrayWrapper, Optional<CryptreeNode>> forRoot = byRoot.get(cacheKey.left);
        return forRoot != null && forRoot.containsKey(cacheKey.right);
    }

    public synchronized Optional<CryptreeNode> get(Pair<Multihash, ByteArrayWrapper> cacheKey) {
        LRUCache<ByteArrayWrapper, Optional<CryptreeNode>> forRoot = byRoot.get(cacheKey.left);
        return forRoot == null ? null : forRoot.get(cacheKey.right);
    }

    public synchronized void put(Pair<Multihash, ByteArrayWrapper> cacheKey, Optional<CryptreeNode> val) {
        forRoot(cacheKey.left).put(cacheKey.right, val);
    }

    public synchronized void update(Optional<Multihash> priorRoot, Pair<Multihash, ByteArrayWrapper> cacheKey, Optional<CryptreeNode> val) {
        // the other mappings from the prior root are unchanged, so carry them all over to the new root
        LRUCache<ByteArrayWrapper, Optional<CryptreeNode>> carried = priorRoot
                .map(byRoot::remove)
                .orElse(null);
        if (carried != null)
            byRoot.put(cacheKey.left, carried);
        forRoot(cacheKey.left).put(cacheKey.right, val);
    }

    private LRUCache<ByteArrayWrapper, Optional<CryptreeNode>> forRoot(Multihash root) {
        LRUCache<ByteArrayWrapper, Optional<CryptreeNode>> existing = byRoot.get(root);
        if (existing != null)
            return existing;
        LRUCache<ByteArrayWrapper, Optional<CryptreeNode>> fresh = new LRUCache<>(cacheSize);
        byRoot.put(root, fresh);
        return fresh;
    }
}
