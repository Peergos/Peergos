package peergos.shared.user;

import peergos.shared.user.fs.AbsoluteCapability;
import peergos.shared.util.ByteArrayWrapper;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SharedWithCache {

    public enum Access { READ, WRITE }

    //K,V
    // K = mapKey of absolute capability
    // V = list of usernames the file/dir is shared with
    private final Map<ByteArrayWrapper, Set<String>> sharedWithReadAccessCache = new ConcurrentHashMap<>();
    private final Map<ByteArrayWrapper, Set<String>> sharedWithWriteAccessCache = new ConcurrentHashMap<>();
    // These would be more space efficient in a trie
    private final Map<Path, Set<String>> writeShares = new ConcurrentHashMap<>();
    private final Map<Path, Set<String>> readShares = new ConcurrentHashMap<>();

    public SharedWithCache() {}

    private static Path canonicalise(Path p) {
        return p.isAbsolute() ? p : Paths.get("/").resolve(p);
    }

    public Map<Path, Set<String>> getAllReadShares(Path start) {
        Path startPath = canonicalise(start);
        Map<Path, Set<String>> res = new HashMap<>();
        for (Path path: readShares.keySet())
        if (path.startsWith(startPath)) {
            Set<String> names = readShares.get(path);
            if (! names.isEmpty())
                res.put(path, names);
        }
        return res;
    }

    public Map<Path, Set<String>> getAllWriteShares(Path start) {
        Path startPath = canonicalise(start);
        Map<Path, Set<String>> res = new HashMap<>();
        for (Path path: writeShares.keySet())
            if (path.startsWith(startPath)) {
                Set<String> names = writeShares.get(path);
                if (! names.isEmpty())
                    res.put(path, names);
            }
        return res;
    }

    private ByteArrayWrapper generateKey(AbsoluteCapability cap) {
        return new ByteArrayWrapper(cap.getMapKey());
    }

    public boolean isShared(AbsoluteCapability cap) {
        return ! getSharedWith(SharedWithCache.Access.READ, cap).isEmpty()
                || ! getSharedWith(SharedWithCache.Access.WRITE, cap).isEmpty();
    }

    public Set<String> getSharedWith(Access access, AbsoluteCapability cap) {
        return access == Access.READ ?
            getSharedWith(sharedWithReadAccessCache, cap) : getSharedWith(sharedWithWriteAccessCache, cap);
    }

    private synchronized Set<String> getSharedWith(Map<ByteArrayWrapper, Set<String>> cache, AbsoluteCapability cap) {
        return new HashSet<>(cache.getOrDefault(generateKey(cap), new HashSet<>()));
    }

    public void addSharedWith(Access access, String path, AbsoluteCapability cap, String name) {
        Path p = Paths.get(path);
        addSharedWith(access, p, cap, Collections.singleton(name));
    }

    public void addSharedWith(Access access, Path p, AbsoluteCapability cap, Set<String> names) {
        Path filePath = canonicalise(p);
        if (access == Access.READ) {
            readShares.putIfAbsent(filePath, new HashSet<>());
            readShares.get(filePath).addAll(names);
            addCacheEntry(sharedWithReadAccessCache, cap, names);
        } else if (access == Access.WRITE) {
            writeShares.putIfAbsent(filePath, new HashSet<>());
            writeShares.get(filePath).addAll(names);
            addCacheEntry(sharedWithWriteAccessCache, cap, names);
        }
    }

    private synchronized void addCacheEntry(Map<ByteArrayWrapper, Set<String>> cache, AbsoluteCapability cap, Set<String> names) {
        cache.computeIfAbsent(generateKey(cap), k -> new HashSet<>()).addAll(names);
    }

    public void clearSharedWith(AbsoluteCapability cap) {
        ByteArrayWrapper key = generateKey(cap);
        sharedWithReadAccessCache.computeIfPresent(key, (k, v) -> new HashSet<>());
        sharedWithWriteAccessCache.computeIfPresent(key, (k, v) -> new HashSet<>());
    }

    public void removeSharedWith(Access access, Path p, AbsoluteCapability cap, Set<String> names) {
        Path filePath = canonicalise(p);
        if (access == Access.READ) {
            if (readShares.containsKey(filePath))
                readShares.get(filePath).removeAll(names);
            removeCacheEntry(sharedWithReadAccessCache, cap, names);
        } else if (access == Access.WRITE) {
            if (writeShares.containsKey(filePath))
                writeShares.get(filePath).removeAll(names);
            removeCacheEntry(sharedWithWriteAccessCache, cap, names);
        }
    }

    private synchronized void removeCacheEntry(Map<ByteArrayWrapper, Set<String>> cache, AbsoluteCapability cap, Set<String> names) {
        cache.computeIfAbsent(generateKey(cap), k -> new HashSet<>()).removeAll(names);
    }
}
