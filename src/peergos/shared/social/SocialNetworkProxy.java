package peergos.shared.social;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;

import java.util.concurrent.*;

public interface SocialNetworkProxy extends SocialNetwork {


    /** Send a follow request to the target public key
     *
     * @param target
     * @param encryptedPermission
     * @return
     */
    CompletableFuture<Boolean> sendFollowRequest(Multihash targetServerId, PublicKeyHash target, byte[] encryptedPermission);

    /**
     *
     * @param owner
     * @return all the pending follow requests for the given public key
     */
    CompletableFuture<byte[]> getFollowRequests(Multihash targetServerId, PublicKeyHash owner);

    /** Delete a follow request for a given public key
     *
     * @param owner
     * @param data
     * @return
     */
    CompletableFuture<Boolean> removeFollowRequest(Multihash targetServerId, PublicKeyHash owner, byte[] data);
}
