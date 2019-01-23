package peergos.shared.user;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SharedWithCache {

    public enum Access { READ, WRITE }

    private Map<String, Set<String>> sharedWithReadAccessCache = new ConcurrentHashMap<>(); //path to friends
    private Map<String, Set<String>> sharedWithWriteAccessCache = new ConcurrentHashMap<>();

    public SharedWithCache() {

    }

    public Set<String> getSharedWith(Access access, String path) {
        return access == Access.READ ?
            getSharedWith(sharedWithReadAccessCache, path) : getSharedWith(sharedWithWriteAccessCache, path);
    }

    private synchronized Set<String> getSharedWith(Map<String, Set<String>> cache, String path) {
        return new HashSet<>(cache.computeIfAbsent(path, k -> new HashSet<>()));
    }

    public void addSharedWith(Access access, String path, String name) {
        Set<String> names = new HashSet<>();
        names.add(name);
        addSharedWith(access, path, names);
    }

    public void addSharedWith(Access access, String path, Set<String> names) {
        if(access == Access.READ) {
            addCacheEntry(sharedWithReadAccessCache, path, names);
        } else if(access == Access.WRITE){
            addCacheEntry(sharedWithWriteAccessCache, path, names);
        }
    }

    private synchronized void addCacheEntry(Map<String, Set<String>> cache, String path, Set<String> names) {
        cache.computeIfAbsent(path, k -> new HashSet<>()).addAll(names);
    }

    public void clearSharedWith(String path) {
        String fullPath = path.startsWith("/") ? path : "/" + path;
        sharedWithReadAccessCache.computeIfPresent(fullPath, (k, v) -> new HashSet<>());
        sharedWithWriteAccessCache.computeIfPresent(fullPath, (k, v) -> new HashSet<>());
    }

    public void removeSharedWith(Access access, String path, Set<String> names) {
        if(access == Access.READ) {
            removeCacheEntry(sharedWithReadAccessCache, path, names);
        } else if(access == Access.WRITE){
            removeCacheEntry(sharedWithWriteAccessCache, path, names);
        }
    }

    private synchronized void removeCacheEntry(Map<String, Set<String>> cache, String path, Set<String> names) {
        cache.computeIfAbsent(path, k -> new HashSet<>()).removeAll(names);
    }

}
