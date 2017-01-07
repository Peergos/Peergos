package peergos.shared.corenode;

import peergos.shared.crypto.*;
import peergos.shared.merklebtree.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public interface CoreNode {
    int MAX_PENDING_FOLLOWERS = 100;
    int MAX_USERNAME_SIZE = 100;

    CompletableFuture<String> getUsername(UserPublicKey key);

    CompletableFuture<List<UserPublicKeyLink>> getChain(String username);

    CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> chain);

    default CompletableFuture<Optional<UserPublicKey>> getPublicKey(String username) {
        return getChain(username).thenApply(chain -> {
            if (chain.size() == 0)
                return Optional.empty();
            else
                return Optional.of(chain.get(chain.size() - 1).owner);
        });
    }

    CompletableFuture<List<String>> getUsernames(String prefix);

    CompletableFuture<Boolean> followRequest(UserPublicKey target, byte[] encryptedPermission);

    CompletableFuture<byte[]> getFollowRequests(UserPublicKey owner);

    CompletableFuture<Boolean> removeFollowRequest(UserPublicKey owner, byte[] data);

    CompletableFuture<Boolean> setMetadataBlob(UserPublicKey ownerPublicKey, UserPublicKey encodedSharingPublicKey, byte[] sharingKeySignedBtreeRootHash);

    CompletableFuture<Boolean> removeMetadataBlob(UserPublicKey encodedSharingPublicKey, byte[] sharingKeySignedMapKeyPlusBlob);

    CompletableFuture<MaybeMultihash> getMetadataBlob(UserPublicKey encodedSharingKey);

    void close() throws IOException;

}
