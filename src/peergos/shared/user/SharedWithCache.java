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
    private Map<ByteArrayWrapper, Set<String>> sharedWithReadAccessCache = new ConcurrentHashMap<>();
    private Map<ByteArrayWrapper, Set<String>> sharedWithWriteAccessCache = new ConcurrentHashMap<>();
    // These would be more space efficient in a trie
    private Map<Path, Set<String>> writeShares = new ConcurrentHashMap<>();
    private Map<Path, Set<String>> readShares = new ConcurrentHashMap<>();

    public SharedWithCache() {

    }

    public Map<Path, Set<String>> getAllReadShares(Path start) {
        Map<Path, Set<String>> res = new HashMap<>();
        for (Path path: readShares.keySet())
        if (path.startsWith(start))
            res.put(path, readShares.get(path));
        return res;
    }

    public Map<Path, Set<String>> getAllWriteShares(Path start) {
        Map<Path, Set<String>> res = new HashMap<>();
        for (Path path: writeShares.keySet())
        if (path.startsWith(start))
            res.put(path, writeShares.get(path));
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
        if (access == Access.READ) {
            readShares.putIfAbsent(p, new HashSet<>());
            readShares.get(p).add(name);
        } else {
            writeShares.putIfAbsent(p, new HashSet<>());
            writeShares.get(p).add(name);
        }
        addSharedWith(access, p, cap, Collections.singleton(name));
    }

    public void addSharedWith(Access access, Path path, AbsoluteCapability cap, Set<String> names) {
        if (access == Access.READ) {
            addCacheEntry(sharedWithReadAccessCache, cap, names);
        } else if (access == Access.WRITE){
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

    public void removeSharedWith(Access access, AbsoluteCapability cap, Set<String> names) {
        if(access == Access.READ) {
            removeCacheEntry(sharedWithReadAccessCache, cap, names);
        } else if(access == Access.WRITE){
            removeCacheEntry(sharedWithWriteAccessCache, cap, names);
        }
    }

    private synchronized void removeCacheEntry(Map<ByteArrayWrapper, Set<String>> cache, AbsoluteCapability cap, Set<String> names) {
        cache.computeIfAbsent(generateKey(cap), k -> new HashSet<>()).removeAll(names);
    }

}
