package peergos.shared.corenode;

import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.merklebtree.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public interface CoreNode {
    int MAX_PENDING_FOLLOWERS = 100;
    int MAX_USERNAME_SIZE = 100;

    CompletableFuture<String> getUsername(PublicSigningKey key);

    CompletableFuture<List<UserPublicKeyLink>> getChain(String username);

    CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> chain);

    default CompletableFuture<Optional<PublicSigningKey>> getPublicKey(String username) {
        return getChain(username).thenApply(chain -> {
            if (chain.size() == 0)
                return Optional.empty();
            else
                return Optional.of(chain.get(chain.size() - 1).owner);
        });
    }

    CompletableFuture<List<String>> getUsernames(String prefix);

    CompletableFuture<Boolean> followRequest(PublicSigningKey target, byte[] encryptedPermission);

    CompletableFuture<byte[]> getFollowRequests(PublicSigningKey owner);

    CompletableFuture<Boolean> removeFollowRequest(PublicSigningKey owner, byte[] data);

    CompletableFuture<Boolean> setMetadataBlob(PublicSigningKey owner, PublicSigningKey writer, byte[] writerSignedBtreeRootHash);

    CompletableFuture<Boolean> removeMetadataBlob(PublicSigningKey writer, byte[] writerSignedMapKeyPlusBlob);

    CompletableFuture<MaybeMultihash> getMetadataBlob(PublicSigningKey encodedSharingKey);

    void close() throws IOException;

}
