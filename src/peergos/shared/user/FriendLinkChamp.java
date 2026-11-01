package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** The signed bundles of an identity's live friend links, keyed by the hash of each link id. Its root has the mirror
 *  bat, like the secret link champ, so only the identity's server can read it: a link that isn't here is revoked or
 *  never existed, and the two look the same (remove-pki §8).
 */
public class FriendLinkChamp {

    public final Multihash root;
    private final ChampWrapper<CborObject.CborMerkleLink> champ;
    private final ContentAddressedStorage ipfs;
    private final Hasher hasher;

    private FriendLinkChamp(Multihash root, ChampWrapper<CborObject.CborMerkleLink> champ, ContentAddressedStorage ipfs, Hasher hasher) {
        this.root = root;
        this.champ = champ;
        this.ipfs = ipfs;
        this.hasher = hasher;
    }

    public static CompletableFuture<Cid> createEmpty(PublicKeyHash owner,
                                                     SigningPrivateKeyAndPublicHash writer,
                                                     Optional<BatId> mirrorBat,
                                                     ContentAddressedStorage ipfs,
                                                     Hasher hasher,
                                                     TransactionId tid) {
        Champ<CborObject.CborMerkleLink> newRoot = Champ.empty(c -> (CborObject.CborMerkleLink)c).withBat(mirrorBat);
        return ipfs.put(owner, writer, newRoot.serialize(), hasher, tid);
    }

    public static CompletableFuture<FriendLinkChamp> build(PublicKeyHash owner, Cid root, Optional<BatWithId> mirrorBat, ContentAddressedStorage ipfs, Hasher hasher) {
        return ChampWrapper.create(owner, root, mirrorBat, b -> Futures.of(b.data), ipfs, hasher, c -> (CborObject.CborMerkleLink)c)
                .thenApply(c -> new FriendLinkChamp(root, c, ipfs, hasher));
    }

    public CompletableFuture<Optional<FriendLinkBundle.Signed>> get(PublicKeyHash owner, byte[] linkId) {
        return hasher.sha256(linkId)
                .thenCompose(champ::get)
                .thenCompose(res -> res.isPresent() ?
                        ipfs.get(owner, (Cid) res.get().target, Optional.empty()).thenApply(raw -> raw.map(FriendLinkBundle.Signed::fromCbor)) :
                        Futures.of(Optional.empty()));
    }

    public CompletableFuture<Multihash> put(SigningPrivateKeyAndPublicHash owner,
                                            FriendLinkBundle.Signed bundle,
                                            Optional<BatId> mirrorBat,
                                            TransactionId tid) {
        return hasher.sha256(bundle.bundle.linkId)
                .thenCompose(key -> champ.get(key)
                        .thenCompose(existing -> ipfs.put(owner.publicKeyHash, owner, bundle.serialize(), hasher, tid)
                                .thenCompose(valueHash -> champ.put(owner.publicKeyHash, owner, key, existing,
                                        new CborObject.CborMerkleLink(valueHash), mirrorBat, tid))));
    }

    public CompletableFuture<Multihash> remove(SigningPrivateKeyAndPublicHash owner,
                                               byte[] linkId,
                                               Optional<BatId> mirrorBat,
                                               TransactionId tid) {
        return hasher.sha256(linkId)
                .thenCompose(key -> champ.get(key)
                        .thenCompose(existing -> existing.isPresent() ?
                                champ.remove(owner.publicKeyHash, owner, key, existing, mirrorBat, tid) :
                                Futures.of(champ.getRoot())));
    }
}
