package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class WriterData implements Cborable {
    /**
     *  Represents the merkle node that a public key maps to
     */

    // the public signing key controlling this subspace
    public final PublicKeyHash controller;

    // publicly readable and present on owner keys
    public final Optional<SecretGenerationAlgorithm> generationAlgorithm;
    // This is the root of a champ containing publicly shared files and folders (a lookup from path to capability)
    public final Optional<Multihash> publicData;
    // The public boxing key to encrypt follow requests to
    public final Optional<PublicKeyHash> followRequestReceiver;
    // Any keys directly owned by the controller, that aren't named, in a champ<key hash, owner proof>
    public final Optional<Multihash> ownedKeys;

    // Any keys directly owned by the controller that have specific labels
    public final Map<String, OwnerProof> namedOwnedKeys;

    // Encrypted entry points to our file systems (present on owner keys)
    public final Optional<UserStaticData> staticData;
    // This is the root of a champ containing the controller's filesystem (present on writer keys)
    public final Optional<Multihash> tree;

    /**
     *
     * @param controller the public signing key that controls writing to this subspace
     * @param generationAlgorithm The algorithm used to create the users key pairs and root key from the username and password
     * @param publicData A readable pointer to a subtree made public by this key
     * @param ownedKeys Any public keys owned by this key
     * @param staticData Any static data owner by this key (list of entry points)
     * @param tree Any file tree owned by this key
     */
    public WriterData(PublicKeyHash controller,
                      Optional<SecretGenerationAlgorithm> generationAlgorithm,
                      Optional<Multihash> publicData,
                      Optional<PublicKeyHash> followRequestReceiver,
                      Optional<Multihash> ownedKeys,
                      Map<String, OwnerProof> namedOwnedKeys,
                      Optional<UserStaticData> staticData,
                      Optional<Multihash> tree) {
        this.controller = controller;
        this.generationAlgorithm = generationAlgorithm;
        this.publicData = publicData;
        this.followRequestReceiver = followRequestReceiver;
        this.ownedKeys = ownedKeys;
        this.namedOwnedKeys = namedOwnedKeys;
        this.staticData = staticData;
        this.tree = tree;
    }

    public WriterData withChamp(Multihash treeRoot) {
        return new WriterData(controller, generationAlgorithm, publicData, followRequestReceiver, ownedKeys, namedOwnedKeys, staticData, Optional.of(treeRoot));
    }

    public WriterData withPublicRoot(Multihash publicChampRoot) {
        return new WriterData(controller, generationAlgorithm, Optional.of(publicChampRoot), followRequestReceiver, ownedKeys, namedOwnedKeys, staticData, tree);
    }

    public CompletableFuture<WriterData> addOwnedKey(PublicKeyHash owner,
                                                     SigningPrivateKeyAndPublicHash signer,
                                                     OwnerProof newOwned,
                                                     ContentAddressedStorage ipfs) {
        return getOwnedKeyChamp(ipfs)
                .thenCompose(champ -> IpfsTransaction.call(owner,
                        tid -> champ.add(owner, signer, newOwned, tid), ipfs)
                .thenApply(newRoot -> new WriterData(controller, generationAlgorithm, publicData, followRequestReceiver,
                        Optional.of(newRoot), namedOwnedKeys, staticData, tree)));
    }

    public CompletableFuture<WriterData> removeOwnedKey(PublicKeyHash owner,
                                                        SigningPrivateKeyAndPublicHash signer,
                                                        PublicKeyHash ownedKey,
                                                        ContentAddressedStorage ipfs) {

        return getOwnedKeyChamp(ipfs)
                .thenCompose(champ -> IpfsTransaction.call(owner,
                        tid -> champ.remove(owner, signer, ownedKey, tid), ipfs)
                        .thenApply(newRoot -> new WriterData(controller, generationAlgorithm, publicData, followRequestReceiver,
                                Optional.of(newRoot), namedOwnedKeys, staticData, tree)));
    }

    public CompletableFuture<Boolean> ownsKey(PublicKeyHash ownedKey,
                                              ContentAddressedStorage ipfs) {
        // TODO do this recursively to handle arbitrary trees of key ownership
        return getOwnedKeyChamp(ipfs)
                .thenCompose(champ -> champ.get(ownedKey))
                .thenApply(Optional::isPresent);
    }

    private CompletableFuture<OwnedKeyChamp> getOwnedKeyChamp(ContentAddressedStorage ipfs) {
        return ownedKeys.map(root -> OwnedKeyChamp.build(root, ipfs))
                .orElseThrow(() -> new IllegalStateException("Owned key champ absent!"));
    }

    private <T> CompletableFuture<Set<T>> applyToOwnedKeys(Function<OwnedKeyChamp, CompletableFuture<Set<T>>> processor,
                                                           ContentAddressedStorage ipfs) {
        return ownedKeys.map(x -> getOwnedKeyChamp(ipfs).thenCompose(processor))
                .orElse(CompletableFuture.completedFuture(Collections.emptySet()));
    }

    public WriterData addNamedKey(String name, OwnerProof newNamedKey) {
        Map<String, OwnerProof> updated = new TreeMap<>(namedOwnedKeys);
        updated.put(name, newNamedKey);
        return new WriterData(controller, generationAlgorithm, publicData, followRequestReceiver, ownedKeys, updated, staticData, tree);
    }

    public WriterData withStaticData(Optional<UserStaticData> entryPoints) {
        return new WriterData(controller, generationAlgorithm, publicData, followRequestReceiver, ownedKeys, namedOwnedKeys, entryPoints, tree);
    }

    public static CompletableFuture<WriterData> createEmpty(PublicKeyHash owner,
                                                            SigningPrivateKeyAndPublicHash writer,
                                                            ContentAddressedStorage ipfs) {
        return OwnedKeyChamp.createEmpty(owner, writer, ipfs)
                .thenApply(ownedRoot -> new WriterData(writer.publicKeyHash,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(ownedRoot),
                        Collections.emptyMap(),
                        Optional.empty(),
                        Optional.empty()));
    }

    public static CompletableFuture<WriterData> createEmptyWithStaticData(PublicKeyHash owner,
                                                                          SigningPrivateKeyAndPublicHash writer,
                                                                          Optional<PublicKeyHash> followRequestReceiver,
                                                                          SymmetricKey rootKey,
                                                                          SecretGenerationAlgorithm algorithm,
                                                                          ContentAddressedStorage ipfs) {
        return OwnedKeyChamp.createEmpty(owner, writer, ipfs)
                .thenApply(ownedRoot -> new WriterData(writer.publicKeyHash,
                        Optional.of(algorithm),
                        Optional.empty(),
                        followRequestReceiver,
                        Optional.of(ownedRoot),
                        Collections.emptyMap(),
                        Optional.of(new UserStaticData(rootKey)),
                        Optional.empty()));
    }

    public CommittedWriterData committed(MaybeMultihash hash) {
        return new CommittedWriterData(hash, this);
    }

    public CompletableFuture<WriterData> changeKeys(SigningPrivateKeyAndPublicHash oldSigner,
                                                    SigningPrivateKeyAndPublicHash signer,
                                                    PublicBoxingKey followRequestReceiver,
                                                    SymmetricKey currentKey,
                                                    SymmetricKey newKey,
                                                    SecretGenerationAlgorithm newAlgorithm,
                                                    NetworkAccess network) {

        network.synchronizer.putEmpty(oldSigner.publicKeyHash, signer.publicKeyHash);
        return network.synchronizer.applyUpdate(oldSigner.publicKeyHash, signer,
                (wd, tid) -> {
                    Optional<UserStaticData> newEntryPoints = staticData
                            .map(sd -> new UserStaticData(sd.getEntryPoints(currentKey), newKey));
                    return network.dhtClient.putBoxingKey(oldSigner.publicKeyHash,
                            oldSigner.secret.signatureOnly(followRequestReceiver.serialize()),
                            followRequestReceiver, tid
                    ).thenApply(boxerHash -> new WriterData(signer.publicKeyHash,
                            Optional.of(newAlgorithm),
                            publicData,
                            Optional.of(new PublicKeyHash(boxerHash)),
                            ownedKeys,
                            namedOwnedKeys,
                            newEntryPoints,
                            tree));
                })
                .thenApply(version -> version.get(signer).props)
                .exceptionally(t -> {
                    if (t.getMessage().contains("cas+failed"))
                        throw new IllegalStateException("You cannot reuse a previous password!");
                    throw new RuntimeException(t.getCause());
                });
    }

    public CompletableFuture<Snapshot> commit(PublicKeyHash owner,
                                              SigningPrivateKeyAndPublicHash signer,
                                              MaybeMultihash currentHash,
                                              NetworkAccess network,
                                              TransactionId tid) {
        return commit(owner, signer, currentHash, network.mutable, network.dhtClient, tid);
    }

    public CompletableFuture<Snapshot> commit(PublicKeyHash owner,
                                              SigningPrivateKeyAndPublicHash signer,
                                              MaybeMultihash currentHash,
                                              MutablePointers mutable,
                                              ContentAddressedStorage immutable,
                                              TransactionId tid) {
        byte[] raw = serialize();
        
        return immutable.put(owner, signer.publicKeyHash, signer.secret.signatureOnly(raw), raw, tid)
                .thenCompose(blobHash -> {
                    MaybeMultihash newHash = MaybeMultihash.of(blobHash);
                    if (newHash.equals(currentHash)) {
                        // nothing has changed
                        CommittedWriterData committed = committed(newHash);
                        return CompletableFuture.completedFuture(new Snapshot(signer.publicKeyHash, committed));
                    }
                    HashCasPair cas = new HashCasPair(currentHash, newHash);
                    byte[] signed = signer.secret.signMessage(cas.serialize());
                    return mutable.setPointer(owner, signer.publicKeyHash, signed)
                            .thenApply(res -> {
                                if (!res)
                                    throw new IllegalStateException("Corenode Crypto CAS failed!");
                                CommittedWriterData committed = committed(newHash);
                                return new Snapshot(signer.publicKeyHash, committed);
                            });
                });
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> result = new TreeMap<>();

        result.put("controller", new CborObject.CborMerkleLink(controller));
        generationAlgorithm.ifPresent(alg -> result.put("algorithm", alg.toCbor()));
        publicData.ifPresent(root -> result.put("public", new CborObject.CborMerkleLink(root)));
        followRequestReceiver.ifPresent(boxer -> result.put("inbound", new CborObject.CborMerkleLink(boxer)));
        ownedKeys.ifPresent(root -> result.put("owned", new CborObject.CborMerkleLink(root)));
        if (! namedOwnedKeys.isEmpty())
            result.put("named", new CborObject.CborMap(new TreeMap<>(namedOwnedKeys.entrySet()
                    .stream()
                    .collect(Collectors.toMap(e -> new CborObject.CborString(e.getKey()), e -> e.getValue())))));
        staticData.ifPresent(sd -> result.put("static", sd.toCbor()));
        tree.ifPresent(tree -> result.put("tree", new CborObject.CborMerkleLink(tree)));
        return CborObject.CborMap.build(result);
    }

    public static WriterData fromCbor(CborObject cbor) {
        if (! ( cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Cbor for WriterData should be a map! " + cbor);

        CborObject.CborMap m = (CborObject.CborMap) cbor;

        PublicKeyHash controller = m.get("controller", PublicKeyHash::fromCbor);
        Optional<SecretGenerationAlgorithm> algo  = m.getOptional("algorithm", SecretGenerationAlgorithm::fromCbor);
        Optional<Multihash> publicData = m.getOptional("public", val -> ((CborObject.CborMerkleLink)val).target);
        Optional<PublicKeyHash> followRequestReceiver = m.getOptional("inbound", PublicKeyHash::fromCbor);
        Optional<Multihash> owned = m.getOptional("owned", val -> ((CborObject.CborMerkleLink)val).target);

        Map<String, OwnerProof> named = m.getOptional("named", c -> (CborObject.CborMap)c)
                .map(map -> map.values.entrySet().stream()
                        .collect(Collectors.toMap(
                                e -> ((CborObject.CborString)e.getKey()).value,
                                e -> OwnerProof.fromCbor(e.getValue()))))
                .orElseGet(() -> Collections.emptyMap());

        Optional<UserStaticData> staticData = m.getOptional("static", UserStaticData::fromCbor);
        Optional<Multihash> tree = m.getOptional("tree", val -> ((CborObject.CborMerkleLink)val).target);
        return new WriterData(controller, algo, publicData, followRequestReceiver, owned, named, staticData, tree);
    }

    public static CompletableFuture<Set<PublicKeyHash>> getOwnedKeysRecursive(String username,
                                                                              CoreNode core,
                                                                              MutablePointers mutable,
                                                                              ContentAddressedStorage dht) {
        return core.getPublicKeyHash(username)
                .thenCompose(publicKeyHash -> publicKeyHash
                        .map(h -> getOwnedKeysRecursive(h, h, mutable, dht))
                        .orElseGet(() -> CompletableFuture.completedFuture(Collections.emptySet())));
    }

    public static CompletableFuture<Set<PublicKeyHash>> getOwnedKeysRecursive(PublicKeyHash owner,
                                                                              PublicKeyHash writer,
                                                                              MutablePointers mutable,
                                                                              ContentAddressedStorage ipfs) {
        return getDirectOwnedKeys(owner, writer, mutable, ipfs)
                .thenCompose(directOwned -> {
                    Set<PublicKeyHash> identity = Collections.singleton(writer);
                    return Futures.reduceAll(directOwned, identity,
                            (a, w) -> getOwnedKeysRecursive(owner, w, mutable, ipfs),
                            (a, b) -> Stream.concat(a.stream(), b.stream())
                                    .collect(Collectors.toSet()));
                });
    }

    public static CompletableFuture<Set<PublicKeyHash>> getDirectOwnedKeys(PublicKeyHash owner,
                                                                           PublicKeyHash writer,
                                                                           MutablePointers mutable,
                                                                           ContentAddressedStorage ipfs) {
        return mutable.getPointerTarget(owner, writer, ipfs)
                .thenCompose(h -> getDirectOwnedKeys(writer, h, ipfs));
    }

    public static CompletableFuture<Set<PublicKeyHash>> getDirectOwnedKeys(PublicKeyHash writer,
                                                                           MaybeMultihash root,
                                                                           ContentAddressedStorage ipfs) {
        if (! root.isPresent())
            return CompletableFuture.completedFuture(Collections.emptySet());

        BiFunction<Set<OwnerProof>, Pair<PublicKeyHash, OwnerProof>, CompletableFuture<Set<OwnerProof>>>
                composer = (acc, pair) -> CompletableFuture.completedFuture(Stream.concat(acc.stream(), Stream.of(pair.right))
                .collect(Collectors.toSet()));

        BiFunction<Set<PublicKeyHash>, OwnerProof, CompletableFuture<Set<PublicKeyHash>>> proofComposer =
                (acc, proof) -> proof.getOwner(ipfs)
                        .thenApply(claimedWriter -> Stream.concat(acc.stream(), claimedWriter.equals(writer) ?
                                Stream.of(proof.ownedKey) :
                                Stream.empty()).collect(Collectors.toSet()));

        return getWriterData(root.get(), ipfs)
                .thenCompose(wd -> wd.props.applyToOwnedKeys(owned ->
                        owned.applyToAllMappings(Collections.emptySet(), composer, ipfs), ipfs)
                        .thenApply(owned -> Stream.concat(owned.stream(),
                                wd.props.namedOwnedKeys.values().stream()).collect(Collectors.toSet())))
                .thenCompose(all -> Futures.reduceAll(all, Collections.emptySet(),
                        proofComposer,
                        (a, b) -> Stream.concat(a.stream(), b.stream())
                                .collect(Collectors.toSet())));
    }

    public static CompletableFuture<CommittedWriterData> getWriterData(PublicKeyHash owner,
                                                                       PublicKeyHash controller,
                                                                       MutablePointers mutable,
                                                                       ContentAddressedStorage dht) {
        return mutable.getPointerTarget(owner, controller, dht)
                .thenCompose(opt -> {
                    if (! opt.isPresent())
                        throw new IllegalStateException("No root pointer present for controller " + controller);
                    return getWriterData(opt.get(), dht);
                });
    }

    public static CompletableFuture<CommittedWriterData> getWriterData(Multihash hash, ContentAddressedStorage dht) {
        return dht.get(hash)
                .thenApply(cborOpt -> {
                    if (! cborOpt.isPresent())
                        throw new IllegalStateException("Couldn't retrieve WriterData from dht! " + hash);
                    return new CommittedWriterData(MaybeMultihash.of(hash), WriterData.fromCbor(cborOpt.get()));
                });
    }
}
