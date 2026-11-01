package peergos.server.social;

import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/** Serves the bundles of this server's users' friend links, and accepts a follow request sent to a friend link only
 *  while the link is live, so revoking a link stops both at once (remove-pki §8). A request without a link id goes to
 *  the legacy followRequestReceiver, which stays until the PKI is retired.
 */
public class FriendLinkSocialNetwork implements SocialNetwork {
    private static final Logger LOG = Logger.getGlobal();
    public static final String NO_LINK = "No friend link";

    private final SocialNetwork target;
    private final MutablePointers pointers;
    private final ContentAddressedStorage storage;
    private final BatCave batStore;
    private final Hasher hasher;
    private CoreNode pki;

    public FriendLinkSocialNetwork(SocialNetwork target,
                                   MutablePointers pointers,
                                   ContentAddressedStorage storage,
                                   BatCave batStore,
                                   Hasher hasher) {
        this.target = target;
        this.pointers = pointers;
        this.storage = storage;
        this.batStore = batStore;
        this.hasher = hasher;
    }

    public void setPki(CoreNode pki) {
        this.pki = pki;
    }

    @Override
    public CompletableFuture<Boolean> sendFollowRequest(PublicKeyHash owner, byte[] encryptedPermission) {
        BlindFollowRequest request = BlindFollowRequest.fromCbor(CborObject.fromByteArray(encryptedPermission));
        if (request.linkId.isEmpty())
            return target.sendFollowRequest(owner, encryptedPermission);
        return getFriendLinkBundle(owner, request.linkId.get())
                .thenCompose(bundle -> {
                    if (bundle.isEmpty())
                        throw new IllegalStateException(NO_LINK);
                    return target.sendFollowRequest(owner, encryptedPermission);
                });
    }

    @Override
    public CompletableFuture<byte[]> getFollowRequests(PublicKeyHash owner, byte[] signedTime) {
        return target.getFollowRequests(owner, signedTime);
    }

    @Override
    public CompletableFuture<Boolean> removeFollowRequest(PublicKeyHash owner, byte[] data) {
        return target.removeFollowRequest(owner, data);
    }

    @Override
    public CompletableFuture<Optional<FriendLinkBundle.Signed>> getFriendLinkBundle(PublicKeyHash owner, byte[] linkId) {
        if (linkId.length != FriendLinkBundle.LINK_ID_BYTES)
            return Futures.of(Optional.empty());
        return WriterData.getWriterData(owner, owner, pointers, storage)
                .thenCompose(cwd -> {
                    WriterData wd = cwd.props.get();
                    if (wd.friendLinks.isEmpty())
                        return Futures.of(Optional.<FriendLinkBundle.Signed>empty());
                    return pki.getUsername(owner)
                            .thenCompose(username -> batStore.getUserBats(username, new byte[0]))
                            .thenCompose(bats -> lookup(owner, (Cid) wd.friendLinks.get(), bats, bats.size() - 1, linkId));
                })
                .thenApply(res -> res.filter(b -> Arrays.equals(b.bundle.linkId, linkId)
                        && b.bundle.identity.equals(owner)
                        && ! b.bundle.isExpired(LocalDateTime.now(ZoneOffset.UTC))))
                .exceptionally(t -> {
                    LOG.log(Level.FINE, "Couldn't look up a friend link of " + owner, t);
                    return Optional.empty();
                });
    }

    // Nothing records which bat the champ was written under, so try the user's most recent first, as secret links do
    private CompletableFuture<Optional<FriendLinkBundle.Signed>> lookup(PublicKeyHash owner,
                                                                       Cid root,
                                                                       List<BatWithId> bats,
                                                                       int index,
                                                                       byte[] linkId) {
        Optional<BatWithId> bat = index < 0 ? Optional.empty() : Optional.of(bats.get(index));
        CompletableFuture<Optional<FriendLinkBundle.Signed>> attempt = FriendLinkChamp.build(owner, root, bat, storage, hasher)
                .thenCompose(champ -> champ.get(owner, linkId));
        if (index <= 0)
            return attempt;
        return Futures.asyncExceptionally(() -> attempt, t -> lookup(owner, root, bats, index - 1, linkId));
    }
}
