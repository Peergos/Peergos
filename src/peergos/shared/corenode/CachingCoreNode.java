package peergos.shared.corenode;

import peergos.shared.crypto.*;
import peergos.shared.merklebtree.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/*
 * A CoreNode that caches previous metadata blob reads for a certain time
 */
public class CachingCoreNode implements CoreNode {

    private final CoreNode target;
    private final int cacheTTL;
    private final Map<UserPublicKey, Pair<MaybeMultihash, Long>> cache = new HashMap<>();

    public CachingCoreNode(CoreNode target, int cacheTTL) {
        this.target = target;
        this.cacheTTL = cacheTTL;
    }

    @Override
    public CompletableFuture<MaybeMultihash> getMetadataBlob(UserPublicKey writerKey) {
        synchronized (cache) {
            Pair<MaybeMultihash, Long> cached = cache.get(writerKey);
            if (cached != null && System.currentTimeMillis() - cached.right < cacheTTL)
                return CompletableFuture.completedFuture(cached.left);
        }
        return target.getMetadataBlob(writerKey).thenApply(m -> {
            cache.put(writerKey, new Pair<>(m, System.currentTimeMillis()));
            return m;
        });
    }

    @Override
    public CompletableFuture<Boolean> setMetadataBlob(UserPublicKey ownerPublicKey, UserPublicKey writerKey, byte[] sharingKeySignedBtreeRootHash) {
        synchronized (cache) {
            cache.remove(writerKey);
        }
        return target.setMetadataBlob(ownerPublicKey, writerKey, sharingKeySignedBtreeRootHash);
    }

    @Override
    public CompletableFuture<Boolean> removeMetadataBlob(UserPublicKey writerKey, byte[] sharingKeySignedMapKeyPlusBlob) {
        synchronized (cache) {
            cache.remove(writerKey);
        }
        return target.removeMetadataBlob(writerKey, sharingKeySignedMapKeyPlusBlob);
    }

    @Override
    public CompletableFuture<String> getUsername(UserPublicKey key) {
        return target.getUsername(key);
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        return target.getChain(username);
    }

    @Override
    public CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> chain) {
        return target.updateChain(username, chain);
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        return target.getUsernames(prefix);
    }

    @Override
    public CompletableFuture<Boolean> followRequest(UserPublicKey target, byte[] encryptedPermission) {
        return this.target.followRequest(target, encryptedPermission);
    }

    @Override
    public CompletableFuture<byte[]> getFollowRequests(UserPublicKey owner) {
        return target.getFollowRequests(owner);
    }

    @Override
    public CompletableFuture<Boolean> removeFollowRequest(UserPublicKey owner, byte[] data) {
        return target.removeFollowRequest(owner, data);
    }

    @Override
    public void close() throws IOException {
        target.close();
    }
}
