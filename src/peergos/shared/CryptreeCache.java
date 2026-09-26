package peergos.shared;

import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.util.*;

/** A cache of cryptree nodes, grouped by the champ root they were read from.
 *
 *  Committing a chunk gives a new root, but leaves every other mapping unchanged, so an update moves the
 *  existing entries to the new root rather than copying them.
 *
 *  A node parsed from a block depends on the key it was parsed with: whether it is a directory is found by trying
 *  that key on it, and a link node, laid out as a directory but holding a file's properties, reads as a file to its
 *  parent key. So a parsed node is only handed back to a lookup with the same key. A node we wrote, or the absence
 *  of one, holds for any key.
 */
public class CryptreeCache {

    private static final int MAX_ROOTS = 4;

    private static final class Entry {
        final Optional<ByteArrayWrapper> parsedWith;
        final Optional<CryptreeNode> node;

        Entry(Optional<ByteArrayWrapper> parsedWith, Optional<CryptreeNode> node) {
            this.parsedWith = parsedWith;
            this.node = node;
        }

        boolean validFor(SymmetricKey key) {
            return parsedWith.isEmpty() || parsedWith.get().equals(new ByteArrayWrapper(key.serialize()));
        }
    }

    private final int cacheSize;
    private final LRUCache<Multihash, LRUCache<ByteArrayWrapper, Entry>> byRoot;

    public CryptreeCache() {
        this(1_000);
    }

    public CryptreeCache(int cacheSize) {
        this.cacheSize = cacheSize;
        this.byRoot = new LRUCache<>(MAX_ROOTS);
    }

    private Entry entry(Pair<Multihash, ByteArrayWrapper> cacheKey, SymmetricKey key) {
        LRUCache<ByteArrayWrapper, Entry> forRoot = byRoot.get(cacheKey.left);
        Entry e = forRoot == null ? null : forRoot.get(cacheKey.right);
        return e != null && e.validFor(key) ? e : null;
    }

    public synchronized boolean containsKey(Pair<Multihash, ByteArrayWrapper> cacheKey, SymmetricKey key) {
        return entry(cacheKey, key) != null;
    }

    /**
     * @return null if there's nothing cached for this key
     */
    public synchronized Optional<CryptreeNode> get(Pair<Multihash, ByteArrayWrapper> cacheKey, SymmetricKey key) {
        Entry e = entry(cacheKey, key);
        return e == null ? null : e.node;
    }

    /** A node parsed from a block with this key */
    public synchronized void putParsed(Pair<Multihash, ByteArrayWrapper> cacheKey, SymmetricKey key, CryptreeNode node) {
        forRoot(cacheKey.left).put(cacheKey.right, new Entry(Optional.of(new ByteArrayWrapper(key.serialize())), Optional.of(node)));
    }

    /** There's no node at this map key */
    public synchronized void putAbsent(Pair<Multihash, ByteArrayWrapper> cacheKey) {
        forRoot(cacheKey.left).put(cacheKey.right, new Entry(Optional.empty(), Optional.empty()));
    }

    public synchronized void update(Optional<Multihash> priorRoot, Pair<Multihash, ByteArrayWrapper> cacheKey, Optional<CryptreeNode> val) {
        // the other mappings from the prior root are unchanged, so carry them all over to the new root
        LRUCache<ByteArrayWrapper, Entry> carried = priorRoot
                .map(byRoot::remove)
                .orElse(null);
        if (carried != null)
            byRoot.put(cacheKey.left, carried);
        forRoot(cacheKey.left).put(cacheKey.right, new Entry(Optional.empty(), val));
    }

    private LRUCache<ByteArrayWrapper, Entry> forRoot(Multihash root) {
        LRUCache<ByteArrayWrapper, Entry> existing = byRoot.get(root);
        if (existing != null)
            return existing;
        LRUCache<ByteArrayWrapper, Entry> fresh = new LRUCache<>(cacheSize);
        byRoot.put(root, fresh);
        return fresh;
    }
}
