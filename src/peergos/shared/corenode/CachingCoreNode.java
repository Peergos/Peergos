package peergos.shared.corenode;

import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
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
    // use strings as key to rule out any JS issues with equals
    private final Map<String, Pair<MaybeMultihash, Long>> cache = new HashMap<>();

    public CachingCoreNode(CoreNode target, int cacheTTL) {
        this.target = target;
        this.cacheTTL = cacheTTL;
    }

    @Override
    public CompletableFuture<MaybeMultihash> getMetadataBlob(PublicSigningKey writer) {
        synchronized (cache) {
            Pair<MaybeMultihash, Long> cached = cache.get(writer.toString());
            if (cached != null && System.currentTimeMillis() - cached.right < cacheTTL) {
                return CompletableFuture.completedFuture(cached.left);
            }
        }
        return target.getMetadataBlob(writer).thenApply(m -> {
            synchronized (cache) {
                cache.put(writer.toString(), new Pair<>(m, System.currentTimeMillis()));
            }
            return m;
        });
    }

    @Override
    public CompletableFuture<Boolean> setMetadataBlob(PublicSigningKey ownerPublicKey, PublicSigningKey writer, byte[] writerSignedBtreeRootHash) {
        synchronized (cache) {
            cache.remove(writer.toString());
        }
        return target.setMetadataBlob(ownerPublicKey, writer, writerSignedBtreeRootHash);
    }

    @Override
    public CompletableFuture<Boolean> removeMetadataBlob(PublicSigningKey writer, byte[] sharingKeySignedMapKeyPlusBlob) {
        synchronized (cache) {
            cache.remove(writer.toString());
        }
        return target.removeMetadataBlob(writer, sharingKeySignedMapKeyPlusBlob);
    }

    @Override
    public CompletableFuture<String> getUsername(PublicSigningKey key) {
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
    public CompletableFuture<Boolean> followRequest(PublicSigningKey target, byte[] encryptedPermission) {
        return this.target.followRequest(target, encryptedPermission);
    }

    @Override
    public CompletableFuture<byte[]> getFollowRequests(PublicSigningKey owner) {
        return target.getFollowRequests(owner);
    }

    @Override
    public CompletableFuture<Boolean> removeFollowRequest(PublicSigningKey owner, byte[] data) {
        return target.removeFollowRequest(owner, data);
    }

    @Override
    public void close() throws IOException {
        target.close();
    }
}
