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
import peergos.shared.user.fs.*;

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
    // Any keys directly owned by the controller, that aren't named
    public final Set<PublicKeyHash> ownedKeys;

    // Any keys directly owned by the controller that have specific labels
    public final Map<String, PublicKeyHash> namedOwnedKeys;

    // Encrypted entry points to our and our friends file systems (present on owner keys)
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
                      Set<PublicKeyHash> ownedKeys,
                      Map<String, PublicKeyHash> namedOwnedKeys,
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

    public WriterData withOwnedKeys(Set<PublicKeyHash> owned) {
        return new WriterData(controller, generationAlgorithm, publicData, followRequestReceiver, owned, namedOwnedKeys, staticData, tree);
    }

    public WriterData addOwnedKey(PublicKeyHash newOwned) {
        Set<PublicKeyHash> updated = new HashSet<>(ownedKeys);
        updated.add(newOwned);
        return new WriterData(controller, generationAlgorithm, publicData, followRequestReceiver, updated, namedOwnedKeys, staticData, tree);
    }

    public WriterData addNamedKey(String name, PublicKeyHash newNamedKey) {
        Map<String, PublicKeyHash> updated = new TreeMap<>(namedOwnedKeys);
        updated.put(name, newNamedKey);
        return new WriterData(controller, generationAlgorithm, publicData, followRequestReceiver, ownedKeys, updated, staticData, tree);
    }

    public WriterData withStaticData(Optional<UserStaticData> entryPoints) {
        return new WriterData(controller, generationAlgorithm, publicData, followRequestReceiver, ownedKeys, namedOwnedKeys, entryPoints, tree);
    }

    public static WriterData createEmpty(PublicKeyHash controller) {
        return new WriterData(controller,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Collections.emptySet(),
                Collections.emptyMap(),
                Optional.empty(),
                Optional.empty());
    }

    public static WriterData createEmpty(PublicKeyHash controller,
                                         Optional<PublicKeyHash> followRequestReceiver,
                                         SymmetricKey rootKey) {
        return new WriterData(controller,
                Optional.of(SecretGenerationAlgorithm.getDefault()),
                Optional.empty(),
                followRequestReceiver,
                Collections.emptySet(),
                Collections.emptyMap(),
                Optional.of(new UserStaticData(rootKey)),
                Optional.empty());
    }

    public CommittedWriterData committed(MaybeMultihash hash) {
        return new CommittedWriterData(hash, this);
    }

    public CompletableFuture<CommittedWriterData> removeFromStaticData(FileWrapper fileWrapper,
                                                                       SymmetricKey rootKey,
                                                                       SigningPrivateKeyAndPublicHash signer,
                                                                       MaybeMultihash currentHash,
                                                                       NetworkAccess network) {
        AbsoluteCapability pointer = fileWrapper.getPointer().capability;

        return staticData.map(sd -> {
            List<EntryPoint> original = sd.getEntryPoints(rootKey);
            List<EntryPoint> updated = original.stream()
                    .filter(e -> !e.pointer.equals(pointer))
                    .collect(Collectors.toList());
            boolean isRemoved = updated.size() < original.size();

            if (isRemoved) {
                return Transaction.call(fileWrapper.owner(),
                        tid -> withStaticData(Optional.of(new UserStaticData(updated, rootKey)))
                                .commit(fileWrapper.owner(), signer, currentHash, network, tid),
                        network.dhtClient);
            }
            CommittedWriterData committed = committed(currentHash);
            return CompletableFuture.completedFuture(committed);
        }).orElse(CompletableFuture.completedFuture(committed(currentHash)));
    }

    public CompletableFuture<CommittedWriterData> changeKeys(SigningPrivateKeyAndPublicHash oldSigner,
                                                             SigningPrivateKeyAndPublicHash signer,
                                                             MaybeMultihash currentHash,
                                                             PublicBoxingKey followRequestReceiver,
                                                             SymmetricKey currentKey,
                                                             SymmetricKey newKey,
                                                             SecretGenerationAlgorithm newAlgorithm,
                                                             NetworkAccess network) {
        return Transaction.call(oldSigner.publicKeyHash, tid -> {
            // auth new key by adding to existing writer data first
            WriterData tmp = addOwnedKey(signer.publicKeyHash);
            return tmp.commit(oldSigner.publicKeyHash, oldSigner, currentHash, network, tid)
                    .thenCompose(tmpCommited -> {
                        Optional<UserStaticData> newEntryPoints = staticData
                                .map(sd -> new UserStaticData(sd.getEntryPoints(currentKey), newKey));
                        return network.dhtClient.putBoxingKey(oldSigner.publicKeyHash,
                                oldSigner.secret.signatureOnly(followRequestReceiver.serialize()),
                                followRequestReceiver, tid
                        ).thenCompose(boxerHash -> {
                            WriterData updated = new WriterData(signer.publicKeyHash,
                                    Optional.of(newAlgorithm),
                                    publicData,
                                    Optional.of(new PublicKeyHash(boxerHash)),
                                    ownedKeys,
                                    namedOwnedKeys,
                                    newEntryPoints,
                                    tree);
                            return updated.commit(oldSigner.publicKeyHash, signer, MaybeMultihash.empty(), network, tid);
                        });
                    });
        }, network.dhtClient);
    }

    public CompletableFuture<CommittedWriterData> commit(PublicKeyHash owner,
                                                         SigningPrivateKeyAndPublicHash signer,
                                                         MaybeMultihash currentHash,
                                                         NetworkAccess network,
                                                         TransactionId tid) {
        return commit(owner, signer, currentHash, network.mutable, network.dhtClient, tid);
    }

    public CompletableFuture<CommittedWriterData> commit(PublicKeyHash owner,
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
                        return CompletableFuture.completedFuture(committed);
                    }
                    HashCasPair cas = new HashCasPair(currentHash, newHash);
                    byte[] signed = signer.secret.signMessage(cas.serialize());
                    return mutable.setPointer(owner, signer.publicKeyHash, signed)
                            .thenApply(res -> {
                                if (!res)
                                    throw new IllegalStateException("Corenode Crypto CAS failed!");
                                CommittedWriterData committed = committed(newHash);
                                return committed;
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
        List<CborObject> ownedKeyStrings = ownedKeys.stream().map(CborObject.CborMerkleLink::new).collect(Collectors.toList());
        if (! ownedKeyStrings.isEmpty())
            result.put("owned", new CborObject.CborList(ownedKeyStrings));
        if (! namedOwnedKeys.isEmpty())
            result.put("named", new CborObject.CborMap(new TreeMap<>(namedOwnedKeys.entrySet()
                    .stream()
                    .collect(Collectors.toMap(e -> new CborObject.CborString(e.getKey()), e -> e.getValue())))));
        staticData.ifPresent(sd -> result.put("static", sd.toCbor()));
        tree.ifPresent(tree -> result.put("tree", new CborObject.CborMerkleLink(tree)));
        return CborObject.CborMap.build(result);
    }

    public static Optional<SecretGenerationAlgorithm> extractUserGenerationAlgorithm(CborObject cbor) {
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        Function<String, Optional<Cborable>> extract = key -> {
            CborObject.CborString cborKey = new CborObject.CborString(key);
            return map.values.containsKey(cborKey) ? Optional.of(map.values.get(cborKey)) : Optional.empty();
        };
        return extract.apply("algorithm").map(SecretGenerationAlgorithm::fromCbor);
    }

    public static WriterData fromCbor(CborObject cbor) {
        if (! ( cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Cbor for WriterData should be a map! " + cbor);

        CborObject.CborMap map = (CborObject.CborMap) cbor;
        Function<String, Optional<Cborable>> extract = key -> {
            CborObject.CborString cborKey = new CborObject.CborString(key);
            return map.values.containsKey(cborKey) ? Optional.of(map.values.get(cborKey)) : Optional.empty();
        };

        PublicKeyHash controller = extract.apply("controller").map(PublicKeyHash::fromCbor).get();
        Optional<SecretGenerationAlgorithm> algo  = extractUserGenerationAlgorithm(cbor);
        Optional<Multihash> publicData = extract.apply("public").map(val -> ((CborObject.CborMerkleLink)val).target);
        Optional<PublicKeyHash> followRequestReceiver = extract.apply("inbound").map(PublicKeyHash::fromCbor);
        CborObject.CborList ownedList = (CborObject.CborList) map.values.get(new CborObject.CborString("owned"));
        Set<PublicKeyHash> owned = ownedList == null ?
                Collections.emptySet() :
                ownedList.value.stream().map(PublicKeyHash::fromCbor).collect(Collectors.toSet());

        CborObject.CborMap namedMap = (CborObject.CborMap) map.values.get(new CborObject.CborString("named"));
        Map<String, PublicKeyHash> named = namedMap == null ?
                Collections.emptyMap() :
                namedMap.values.entrySet().stream()
                        .collect(Collectors.toMap(
                                e -> ((CborObject.CborString)e.getKey()).value,
                                e -> new PublicKeyHash(((CborObject.CborMerkleLink) e.getValue()).target)));

        Optional<UserStaticData> staticData = extract.apply("static").map(UserStaticData::fromCbor);
        Optional<Multihash> tree = extract.apply("tree").map(val -> ((CborObject.CborMerkleLink)val).target);
        return new WriterData(controller, algo, publicData, followRequestReceiver, owned, named, staticData, tree);
    }

    public static Set<PublicKeyHash> getOwnedKeysRecursive(String username,
                                                           CoreNode core,
                                                           MutablePointers mutable,
                                                           ContentAddressedStorage dht) {
        try {
            Optional<PublicKeyHash> publicKeyHash = core.getPublicKeyHash(username).get();
            return publicKeyHash
                    .map(h -> getOwnedKeysRecursive(h, h, mutable, dht))
                    .orElseGet(Collections::emptySet);
        } catch (InterruptedException e) {
            return Collections.emptySet();
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    public static Set<PublicKeyHash> getOwnedKeysRecursive(PublicKeyHash owner,
                                                           PublicKeyHash writer,
                                                           MutablePointers mutable,
                                                           ContentAddressedStorage dht) {
        Set<PublicKeyHash> res = new HashSet<>();
        res.add(writer);
        try {
            CommittedWriterData subspaceDescriptor = mutable.getPointer(owner, writer)
                    .thenCompose(dataOpt -> dataOpt.isPresent() ?
                            dht.getSigningKey(writer)
                                    .thenApply(signer -> HashCasPair.fromCbor(CborObject.fromByteArray(signer.get()
                                            .unsignMessage(dataOpt.get()))).updated) :
                            CompletableFuture.completedFuture(MaybeMultihash.empty()))
                    .thenCompose(x -> getWriterData(writer, x, dht)).get();

            for (PublicKeyHash subKey : subspaceDescriptor.props.ownedKeys) {
                res.addAll(getOwnedKeysRecursive(owner, subKey, mutable, dht));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return res;
    }

    public static Set<PublicKeyHash> getDirectOwnedKeys(PublicKeyHash owner,
                                                        PublicKeyHash writer,
                                                        MutablePointers mutable,
                                                        ContentAddressedStorage dht) {
        Set<PublicKeyHash> res = new HashSet<>();
        try {
            CommittedWriterData subspaceDescriptor = mutable.getPointer(owner, writer)
                    .thenCompose(dataOpt -> dataOpt.isPresent() ?
                            dht.getSigningKey(writer)
                                    .thenApply(signer -> HashCasPair.fromCbor(CborObject.fromByteArray(signer.get()
                                            .unsignMessage(dataOpt.get()))).updated) :
                            CompletableFuture.completedFuture(MaybeMultihash.empty()))
                    .thenCompose(x -> getWriterData(writer, x, dht)).get();

            res.addAll(subspaceDescriptor.props.ownedKeys);
            res.addAll(subspaceDescriptor.props.namedOwnedKeys.values());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return res;
    }

    public static CompletableFuture<CommittedWriterData> getWriterData(PublicKeyHash controller,
                                                                       MaybeMultihash hash,
                                                                       ContentAddressedStorage dht) {
        if (!hash.isPresent())
            return CompletableFuture.completedFuture(new CommittedWriterData(MaybeMultihash.empty(), WriterData.createEmpty(controller)));
        return getWriterData(hash.get(), dht);
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
