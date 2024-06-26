package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class OwnedKeyChamp {

    public final Multihash root;
    private final ChampWrapper<CborObject.CborMerkleLink> champ;
    private final ContentAddressedStorage ipfs;

    private OwnedKeyChamp(Multihash root, ChampWrapper<CborObject.CborMerkleLink> champ, ContentAddressedStorage ipfs) {
        this.root = root;
        this.champ = champ;
        this.ipfs = ipfs;
    }

    public static CompletableFuture<Cid> createEmpty(PublicKeyHash owner,
                                                     SigningPrivateKeyAndPublicHash writer,
                                                     ContentAddressedStorage ipfs,
                                                     Hasher hasher,
                                                     TransactionId tid) {
        Champ<CborObject.CborMerkleLink> newRoot = Champ.empty(c -> (CborObject.CborMerkleLink)c);
        byte[] raw = newRoot.serialize();
        return hasher.sha256(raw)
                .thenCompose(writer.secret::signMessage)
                .thenCompose(signed -> ipfs.put(owner, writer.publicKeyHash, signed, raw, tid));
    }

    public static CompletableFuture<OwnedKeyChamp> build(PublicKeyHash owner, Cid root, ContentAddressedStorage ipfs, Hasher hasher) {
        return ChampWrapper.create(owner, root, Optional.empty(), b -> Futures.of(b.data), ipfs, hasher, c -> (CborObject.CborMerkleLink)c)
                .thenApply(c -> new OwnedKeyChamp(root, c, ipfs));
    }

    private static byte[] reverse(byte[] in) {
        byte[] reversed = new byte[in.length];
        for (int i=0; i < in.length; i++)
            reversed[i] = in[in.length - i - 1];
        return reversed;
    }

    private static byte[] keyToBytes(PublicKeyHash key) {
        return reverse(key.serialize());
    }

    public CompletableFuture<Optional<OwnerProof>> get(PublicKeyHash owner,
                                                       PublicKeyHash ownedKey) {
        return champ.get(keyToBytes(ownedKey))
                .thenCompose(res -> res.isPresent() ?
                        ipfs.get(owner, (Cid)res.get().target, Optional.empty()).thenApply(raw -> raw.map(OwnerProof::fromCbor)) :
                        CompletableFuture.completedFuture(Optional.empty()));
    }

    public CompletableFuture<Multihash> add(PublicKeyHash owner,
                                            SigningPrivateKeyAndPublicHash writer,
                                            OwnerProof proof,
                                            Hasher hasher,
                                            TransactionId tid) {
        return ipfs.put(owner, writer, proof.serialize(), hasher, tid)
                .thenCompose(valueHash ->
                        champ.put(owner, writer, keyToBytes(proof.ownedKey), Optional.empty(), new CborObject.CborMerkleLink(valueHash), Optional.empty(), tid));
    }

    public CompletableFuture<Multihash> remove(PublicKeyHash owner,
                                               SigningPrivateKeyAndPublicHash writer,
                                               PublicKeyHash key,
                                               TransactionId tid) {
        byte[] keyBytes = keyToBytes(key);
        return champ.get(keyBytes)
                .thenCompose(existing -> champ.remove(owner, writer, keyBytes, existing, Optional.empty(), tid));
    }

    public CompletableFuture<Boolean> contains(PublicKeyHash ownedKey) {
        return champ.get(keyToBytes(ownedKey))
                .thenApply(Optional::isPresent);
    }

    public <T> CompletableFuture<T> applyToAllMappings(PublicKeyHash owner,
                                                       T identity,
                                                       BiFunction<T, Pair<PublicKeyHash, OwnerProof>, CompletableFuture<T>> consumer,
                                                       ContentAddressedStorage ipfs) {
        return champ.reduceAllMappings(owner, identity,
                (acc, pair) -> ! pair.right.isPresent() ? CompletableFuture.completedFuture(acc) :
                        ipfs.get(owner, (Cid)pair.right.get().target, Optional.empty())
                                .thenApply(raw -> OwnerProof.fromCbor(raw.get()))
                                .thenCompose(proof -> consumer.apply(acc,
                                        new Pair<>(PublicKeyHash.fromCbor(CborObject.fromByteArray(reverse(pair.left.data))), proof))));
    }
}
