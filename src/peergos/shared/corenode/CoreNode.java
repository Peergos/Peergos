package peergos.shared.corenode;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.merklebtree.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public interface CoreNode {
    int MAX_PENDING_FOLLOWERS = 100;
    int MAX_USERNAME_SIZE = 100;

    /**
     *
     * @param key
     * @return the username claimed by a given public key
     */
    CompletableFuture<String> getUsername(PublicSigningKey key);

    /**
     *
     * @param username
     * @return the key chain proving the claim of the requested username
     */
    CompletableFuture<List<UserPublicKeyLink>> getChain(String username);

    /** Claim a username, or change the public key owning a username
     *
     * @param username
     * @param chain
     * @return
     */
    CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> chain);

    /**
     *
     * @param username
     * @return the public key for a username, if present
     */
    default CompletableFuture<Optional<PublicSigningKey>> getPublicKey(String username) {
        return getChain(username).thenApply(chain -> {
            if (chain.size() == 0)
                return Optional.empty();
            else
                return Optional.of(chain.get(chain.size() - 1).owner);
        });
    }

    /**
     *
     * @param prefix
     * @return All usernames starting with prefix
     */
    CompletableFuture<List<String>> getUsernames(String prefix);

    /** Send a follow request to the target public key
     *
     * @param target
     * @param encryptedPermission
     * @return
     */
    CompletableFuture<Boolean> followRequest(PublicSigningKey target, byte[] encryptedPermission);

    /**
     *
     * @param owner
     * @return all the pending follow requests for the given public key
     */
    CompletableFuture<byte[]> getFollowRequests(PublicSigningKey owner);

    /** Delete a follow request for a given public key
     *
     * @param owner
     * @param data
     * @return
     */
    CompletableFuture<Boolean> removeFollowRequest(PublicSigningKey owner, byte[] data);

    /** Update the hash that a public key maps to (doing a cas with the existing value)
     *
     * @param owner
     * @param writer
     * @param writerSignedBtreeRootHash the signed serialization of the HashCasPair
     * @return
     */
    CompletableFuture<Boolean> setMetadataBlob(PublicSigningKey owner, PublicSigningKey writer, byte[] writerSignedBtreeRootHash);

    /** Get the current hash a public key maps to
     *
     * @param encodedSharingKey
     * @return
     */
    CompletableFuture<MaybeMultihash> getMetadataBlob(PublicSigningKey encodedSharingKey);

    /** Remove the mapping for a public key
     *
     * @param writer
     * @param writerSignedMapKeyPlusBlob
     * @return
     */
    CompletableFuture<Boolean> removeMetadataBlob(PublicSigningKey writer, byte[] writerSignedMapKeyPlusBlob);

    void close() throws IOException;
}
