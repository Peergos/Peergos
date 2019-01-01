package peergos.shared.social;

import peergos.shared.crypto.hash.*;

import java.util.concurrent.*;

public interface SocialNetwork {

    int MAX_PENDING_FOLLOWERS = 100;

    /** Send a follow request to the target public key
     *
     * @param target
     * @param encryptedPermission
     * @return
     */
    CompletableFuture<Boolean> sendFollowRequest(PublicKeyHash target, byte[] encryptedPermission);

    /**
     *
     * @param owner
     * @return all the pending follow requests for the given public key
     */
    CompletableFuture<byte[]> getFollowRequests(PublicKeyHash owner, byte[] signedTime);

    /** Delete a follow request for a given public key
     *
     * @param owner
     * @param data
     * @return
     */
    CompletableFuture<Boolean> removeFollowRequest(PublicKeyHash owner, byte[] data);
}
