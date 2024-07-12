package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class SecretLinkChamp {

    public final Multihash root;
    private final ChampWrapper<CborObject.CborMerkleLink> champ;
    private final ContentAddressedStorage ipfs;
    private final Hasher hasher;

    private SecretLinkChamp(Multihash root, ChampWrapper<CborObject.CborMerkleLink> champ, ContentAddressedStorage ipfs, Hasher hasher) {
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
        byte[] raw = newRoot.serialize();
        return hasher.sha256(raw)
                .thenCompose(hash -> writer.secret.signMessage(hash)
                        .thenCompose(sig -> ipfs.put(owner, writer.publicKeyHash, sig, raw, tid)));
    }

    public static CompletableFuture<SecretLinkChamp> build(PublicKeyHash owner, Cid root, Optional<BatWithId> mirrorBat, ContentAddressedStorage ipfs, Hasher hasher) {
        return ChampWrapper.create(owner, root, mirrorBat, b -> Futures.of(b.data), ipfs, hasher, c -> (CborObject.CborMerkleLink)c)
                .thenApply(c -> new SecretLinkChamp(root, c, ipfs, hasher));
    }

    private CompletableFuture<byte[]> keyToBytes(long key) {
        byte[] copy = new byte[8];
        for (int i=0; i < 8; i++) {
            copy[i] = (byte) (key >> (8 * i));
        }
        return hasher.sha256(copy);
    }

    public CompletableFuture<Optional<SecretLinkTarget>> get(PublicKeyHash owner,
                                                             long label) {
        return keyToBytes(label)
                .thenCompose(champ::get)
                .thenCompose(res -> res.isPresent() ?
                        ipfs.get(owner, (Cid)res.get().target, Optional.empty()).thenApply(raw -> raw.map(SecretLinkTarget::fromCbor)) :
                        CompletableFuture.completedFuture(Optional.empty()));
    }

    public CompletableFuture<Pair<Multihash, Cid>> add(SigningPrivateKeyAndPublicHash owner,
                                            long label,
                                            SecretLinkTarget target,
                                            Optional<CborObject.CborMerkleLink> existing,
                                            Optional<BatId> mirrorBat,
                                            Hasher hasher,
                                            TransactionId tid) {
        return keyToBytes(label)
                .thenCompose(key -> ipfs.put(owner.publicKeyHash, owner, target.serialize(), hasher, tid)
                        .thenCompose(valueHash ->
                                champ.put(owner.publicKeyHash, owner, key, existing, new CborObject.CborMerkleLink(valueHash), mirrorBat, tid).thenApply(root -> new Pair<>(root, valueHash))));
    }

    public CompletableFuture<Multihash> remove(PublicKeyHash owner,
                                               SigningPrivateKeyAndPublicHash writer,
                                               long label,
                                               Optional<BatId> mirrorBat,
                                               TransactionId tid) {
        return keyToBytes(label)
                .thenCompose(key -> champ.get(key)
                        .thenCompose(existing -> champ.remove(owner, writer, key, existing, mirrorBat, tid)));
    }

    public CompletableFuture<Boolean> contains(long label) {
        return keyToBytes(label)
                .thenCompose(champ::get)
                .thenApply(Optional::isPresent);
    }
}
