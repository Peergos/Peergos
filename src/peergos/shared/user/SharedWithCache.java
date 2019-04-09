package peergos.shared.user;

import peergos.shared.user.fs.AbsoluteCapability;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SharedWithCache {

    public enum Access { READ, WRITE }

    //K,V
    // K = ?? of absolute capability
    // V = list of usernames the file/dir is shared with
    private Map<Integer, Set<String>> sharedWithReadAccessCache = new ConcurrentHashMap<>();
    private Map<Integer, Set<String>> sharedWithWriteAccessCache = new ConcurrentHashMap<>();

    public SharedWithCache() {

    }

    private Integer generateKey(AbsoluteCapability cap) {
        int result = 1;
        for(byte b : cap.getMapKey()) { //TODO need to change
            result = 31 * result + b;
        }
        return result;
    }

    public boolean isShared(AbsoluteCapability cap) {
        return ! getSharedWith(SharedWithCache.Access.READ, cap).isEmpty()
                || ! getSharedWith(SharedWithCache.Access.WRITE, cap).isEmpty();
    }

    public void copySharedWith(AbsoluteCapability oldCap, AbsoluteCapability newCap) {
        Set<String> sharedReadAccessWith = getSharedWith(SharedWithCache.Access.READ, oldCap);
        Set<String> sharedWriteAccessWith = getSharedWith(SharedWithCache.Access.WRITE, oldCap);
        addSharedWith(SharedWithCache.Access.READ, newCap, sharedReadAccessWith);
        addSharedWith(SharedWithCache.Access.WRITE, newCap, sharedWriteAccessWith);
    }

    public Set<String> getSharedWith(Access access, AbsoluteCapability cap) {
        return access == Access.READ ?
            getSharedWith(sharedWithReadAccessCache, cap) : getSharedWith(sharedWithWriteAccessCache, cap);
    }

    private synchronized Set<String> getSharedWith(Map<Integer, Set<String>> cache, AbsoluteCapability cap) {
        return new HashSet<>(cache.computeIfAbsent(generateKey(cap), k -> new HashSet<>()));
    }

    public void addSharedWith(Access access, AbsoluteCapability cap, String name) {
        Set<String> names = new HashSet<>();
        names.add(name);
        addSharedWith(access, cap, names);
    }

    public void addSharedWith(Access access, AbsoluteCapability cap, Set<String> names) {
        if(access == Access.READ) {
            addCacheEntry(sharedWithReadAccessCache, cap, names);
        } else if(access == Access.WRITE){
            addCacheEntry(sharedWithWriteAccessCache, cap, names);
        }
    }

    private synchronized void addCacheEntry(Map<Integer, Set<String>> cache, AbsoluteCapability cap, Set<String> names) {
        cache.computeIfAbsent(generateKey(cap), k -> new HashSet<>()).addAll(names);
    }

    public void clearSharedWith(AbsoluteCapability cap) {
        Integer key = generateKey(cap);
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

    private synchronized void removeCacheEntry(Map<Integer, Set<String>> cache, AbsoluteCapability cap, Set<String> names) {
        cache.computeIfAbsent(generateKey(cap), k -> new HashSet<>()).removeAll(names);
    }

}
