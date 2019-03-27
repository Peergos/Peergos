package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class OwnedKeyChamp {

    public final Multihash root;
    private final ChampWrapper champ;
    private final ContentAddressedStorage ipfs;

    private OwnedKeyChamp(Multihash root, ChampWrapper champ, ContentAddressedStorage ipfs) {
        this.root = root;
        this.champ = champ;
        this.ipfs = ipfs;
    }

    public static CompletableFuture<Multihash> createEmpty(PublicKeyHash owner,
                                                           SigningPrivateKeyAndPublicHash writer,
                                                           ContentAddressedStorage ipfs) {
        Champ newRoot = Champ.empty();
        byte[] raw = newRoot.serialize();
        return IpfsTransaction.call(owner,
                tid -> ipfs.put(owner, writer.publicKeyHash, writer.secret.signatureOnly(raw), raw, tid), ipfs);
    }

    public static CompletableFuture<OwnedKeyChamp> build(Multihash root, ContentAddressedStorage ipfs) {
        return ChampWrapper.create(root, b -> b.data, ipfs)
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

    public CompletableFuture<Optional<OwnerProof>> get(PublicKeyHash ownedKey) {
        return champ.get(keyToBytes(ownedKey))
                .thenCompose(res -> res.isPresent() ?
                        ipfs.get(res.get()).thenApply(raw -> raw.map(OwnerProof::fromCbor)) :
                        CompletableFuture.completedFuture(Optional.empty()));
    }

    public CompletableFuture<Multihash> add(PublicKeyHash owner,
                                            SigningPrivateKeyAndPublicHash writer,
                                            OwnerProof proof,
                                            TransactionId tid) {
        return ipfs.put(owner, writer, proof.serialize(), tid)
                .thenCompose(valueHash ->
                        champ.put(owner, writer, keyToBytes(proof.ownedKey), MaybeMultihash.empty(), valueHash, tid));
    }

    public CompletableFuture<Multihash> remove(PublicKeyHash owner,
                                               SigningPrivateKeyAndPublicHash writer,
                                               PublicKeyHash key,
                                               TransactionId tid) {
        byte[] keyBytes = keyToBytes(key);
        return champ.get(keyBytes)
                .thenCompose(existing -> champ.remove(owner, writer, keyBytes, existing, tid));
    }

    public CompletableFuture<Boolean> contains(PublicKeyHash ownedKey) {
        return champ.get(keyToBytes(ownedKey))
                .thenApply(MaybeMultihash::isPresent);
    }

    public <T> CompletableFuture<T> applyToAllMappings(T identity,
                                                       BiFunction<T, Pair<PublicKeyHash, OwnerProof>, CompletableFuture<T>> consumer,
                                                       ContentAddressedStorage ipfs) {
        return champ.applyToAllMappings(identity,
                (acc, pair) -> ! pair.right.isPresent() ? CompletableFuture.completedFuture(acc) :
                        ipfs.get(pair.right.get())
                                .thenApply(raw -> OwnerProof.fromCbor(raw.get()))
                                .thenCompose(proof -> consumer.apply(acc,
                                        new Pair<>(PublicKeyHash.fromCbor(CborObject.fromByteArray(reverse(pair.left.data))), proof))));
    }
}
