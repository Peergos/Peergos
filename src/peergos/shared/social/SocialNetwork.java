package peergos.shared.social;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;

import java.util.*;
import java.util.concurrent.*;

public interface SocialNetwork {

    int MAX_PENDING_FOLLOWERS = 100;

    /** Send a follow request to the target public key
     *
     * @param target The public identity key hash of the target user
     * @param encryptedPermission The encrypted follow request
     * @return True if successful
     */
    CompletableFuture<Boolean> sendFollowRequest(PublicKeyHash target, byte[] encryptedPermission);

    /**
     *
     * @param owner The public identity key hash of user who's pending follow requests are being retrieved
     * @param signedTime The current time signed by the owner
     * @return all the pending follow requests for the given user
     */
    CompletableFuture<byte[]> getFollowRequests(PublicKeyHash owner, byte[] signedTime);

    /** Delete a follow request for a given public key
     *
     * @param owner The public identity key hash of user who's follow request is being deleted
     * @param data The original follow request data to delete, signed by the owner
     * @return True if successful
     */
    CompletableFuture<Boolean> removeFollowRequest(PublicKeyHash owner, byte[] data);

    /** The signed bundle of a live friend link. A revoked, expired or unknown link id all answer empty.
     *
     * @param owner The identity the link is for
     * @param linkId The link's id
     */
    CompletableFuture<Optional<FriendLinkBundle.Signed>> getFriendLinkBundle(PublicKeyHash owner, byte[] linkId);

    static byte[] serializeBundle(Optional<FriendLinkBundle.Signed> bundle) {
        return new CborObject.CborList(bundle.<List<Cborable>>map(Collections::singletonList).orElse(Collections.emptyList())).serialize();
    }

    static Optional<FriendLinkBundle.Signed> parseBundle(byte[] raw) {
        List<? extends Cborable> list = ((CborObject.CborList) CborObject.fromByteArray(raw)).value;
        return list.isEmpty() ? Optional.empty() : Optional.of(FriendLinkBundle.Signed.fromCbor(list.get(0)));
    }
}
