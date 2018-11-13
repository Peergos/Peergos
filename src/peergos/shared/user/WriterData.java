package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.fs.*;
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
    // accessible under IPFS address $hash/public
    public final Optional<FilePointer> publicData;
    // The public boxing key to encrypt follow requests to, accessible under IPFS address $hash/inbound
    public final Optional<PublicKeyHash> followRequestReceiver;
    // accessible under IPFS address $hash/owned
    public final Set<PublicKeyHash> ownedKeys;

    // accessible under IPFS address $hash/named
    public final Map<String, PublicKeyHash> namedOwnedKeys;

    // Encrypted
    // accessible under IPFS address $hash/static (present on owner keys)
    public final Optional<UserStaticData> staticData;
    // accessible under IPFS address $hash/tree (present on writer keys)
    public final Optional<Multihash> tree;
    public final Optional<Multihash> btree; // legacy only

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
                      Optional<FilePointer> publicData,
                      Optional<PublicKeyHash> followRequestReceiver,
                      Set<PublicKeyHash> ownedKeys,
                      Map<String, PublicKeyHash> namedOwnedKeys,
                      Optional<UserStaticData> staticData,
                      Optional<Multihash> tree,
                      Optional<Multihash> btree) {
        this.controller = controller;
        this.generationAlgorithm = generationAlgorithm;
        this.publicData = publicData;
        this.followRequestReceiver = followRequestReceiver;
        this.ownedKeys = ownedKeys;
        this.namedOwnedKeys = namedOwnedKeys;
        this.staticData = staticData;
        this.tree = tree;
        this.btree = btree;
        if (tree.isPresent() && btree.isPresent())
            throw new IllegalStateException("A writer cannot have both a legacy btree and a champ!");
    }

    public WriterData withBtree(Multihash treeRoot) {
        return new WriterData(controller, generationAlgorithm, publicData, followRequestReceiver, ownedKeys, namedOwnedKeys, staticData, Optional.empty(), Optional.of(treeRoot));
    }

    public WriterData withChamp(Multihash treeRoot) {
        return new WriterData(controller, generationAlgorithm, publicData, followRequestReceiver, ownedKeys, namedOwnedKeys, staticData, Optional.of(treeRoot), Optional.empty());
    }

    public WriterData withOwnedKeys(Set<PublicKeyHash> owned) {
        return new WriterData(controller, generationAlgorithm, publicData, followRequestReceiver, owned, namedOwnedKeys, staticData, tree, btree);
    }

    public WriterData addOwnedKey(PublicKeyHash newOwned) {
        Set<PublicKeyHash> updated = new HashSet<>(ownedKeys);
        updated.add(newOwned);
        return new WriterData(controller, generationAlgorithm, publicData, followRequestReceiver, updated, namedOwnedKeys, staticData, tree, btree);
    }

    public WriterData addNamedKey(String name, PublicKeyHash newNamedKey) {
        Map<String, PublicKeyHash> updated = new TreeMap<>(namedOwnedKeys);
        updated.put(name, newNamedKey);
        return new WriterData(controller, generationAlgorithm, publicData, followRequestReceiver, ownedKeys, updated, staticData, tree, btree);
    }

    public static WriterData createEmpty(PublicKeyHash controller) {
        return new WriterData(controller,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Collections.emptySet(),
                Collections.emptyMap(),
                Optional.empty(),
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
                Optional.empty(),
                Optional.empty());
    }

    public CommittedWriterData committed(MaybeMultihash hash) {
        return new CommittedWriterData(hash, this);
    }

    public CompletableFuture<CommittedWriterData> removeFromStaticData(FileTreeNode fileTreeNode,
                                                                       SigningPrivateKeyAndPublicHash signer,
                                                                       MaybeMultihash currentHash,
                                                                       NetworkAccess network,
                                                                       Consumer<CommittedWriterData> updater) {
        FilePointer pointer = fileTreeNode.getPointer().filePointer;

        return staticData.map(sd -> {
            boolean isRemoved = sd.remove(pointer);

            if (isRemoved)
                return commit(pointer.location.owner, signer, currentHash, network, updater);
            CommittedWriterData committed = committed(currentHash);
            updater.accept(committed);
            return CompletableFuture.completedFuture(committed);
        }).orElse(CompletableFuture.completedFuture(committed(currentHash)));
    }

    public CompletableFuture<CommittedWriterData> changeKeys(SigningPrivateKeyAndPublicHash oldSigner,
                                                             SigningPrivateKeyAndPublicHash signer,
                                                             MaybeMultihash currentHash,
                                                             PublicBoxingKey followRequestReceiver,
                                                             SymmetricKey newKey,
                                                             SecretGenerationAlgorithm newAlgorithm,
                                                             NetworkAccess network,
                                                             Consumer<CommittedWriterData> updater) {
        // auth new key by adding to existing writer data first
        WriterData tmp = addOwnedKey(signer.publicKeyHash);
        return tmp.commit(oldSigner.publicKeyHash, oldSigner, currentHash, network, x -> {}).thenCompose(tmpCommited -> {
            Optional<UserStaticData> newEntryPoints = staticData.map(sd -> sd.withKey(newKey));
            return network.dhtClient.putBoxingKey(signer.publicKeyHash, signer.secret.signatureOnly(followRequestReceiver.serialize()), followRequestReceiver)
                    .thenCompose(boxerHash -> {
                        WriterData updated = new WriterData(signer.publicKeyHash,
                                Optional.of(newAlgorithm),
                                publicData,
                                Optional.of(new PublicKeyHash(boxerHash)),
                                ownedKeys,
                                namedOwnedKeys,
                                newEntryPoints,
                                tree,
                                btree);
                        return updated.commit(oldSigner.publicKeyHash, signer, MaybeMultihash.empty(), network, updater);
                    });
        });
    }

    public CompletableFuture<CommittedWriterData> migrateToChamp(PublicKeyHash owner,
                                                                 SigningPrivateKeyAndPublicHash writer,
                                                                 MaybeMultihash currentHash,
                                                                 NetworkAccess network,
                                                                 Consumer<CommittedWriterData> updater) {
        CommittedWriterData original = this.committed(currentHash);
        if (tree.isPresent())
            return CompletableFuture.completedFuture(original);
        if (! btree.isPresent())
            throw new IllegalStateException("btree not present!");

        Function<ByteArrayWrapper, byte[]> hasher = b -> b.data;
        BiFunction<Pair<Champ, Multihash>,
                Pair<ByteArrayWrapper, MaybeMultihash>,
                CompletableFuture<Pair<Champ, Multihash>>> inserter =
                (root, pair) -> root.left.put(writer, pair.left, pair.left.data, 0, MaybeMultihash.empty(), pair.right,
                            ChampWrapper.BIT_WIDTH, ChampWrapper.MAX_HASH_COLLISIONS_PER_LEVEL, hasher, network.dhtClient, root.right);

        Champ newRoot = Champ.empty();
        byte[] raw = newRoot.serialize();
        return network.dhtClient.put(writer.publicKeyHash, writer.secret.signatureOnly(raw), raw)
                .thenApply(rootHash -> new Pair<>(newRoot, rootHash))
                .thenCompose(empty -> MerkleBTree.create(writer.publicKeyHash, btree.get(), network.dhtClient)
                        .thenCompose(mbtree -> mbtree.applyToAllMappings(empty, inserter))
                        .thenCompose(champPair -> {
                            WriterData updated = new WriterData(writer.publicKeyHash,
                                    generationAlgorithm,
                                    publicData,
                                    followRequestReceiver,
                                    ownedKeys,
                                    namedOwnedKeys,
                                    staticData,
                                    Optional.of(champPair.right),
                                    Optional.empty());
                            return updated.commit(owner, writer, currentHash, network, updater);
                        })
                );
    }

    public CompletableFuture<CommittedWriterData> commit(PublicKeyHash owner, SigningPrivateKeyAndPublicHash signer, MaybeMultihash currentHash,
                                                         NetworkAccess network, Consumer<CommittedWriterData> updater) {
        return commit(owner, signer, currentHash, network.mutable, network.dhtClient, updater);
    }

    public CompletableFuture<CommittedWriterData> commit(PublicKeyHash owner, SigningPrivateKeyAndPublicHash signer, MaybeMultihash currentHash,
                                                         MutablePointers mutable, ContentAddressedStorage immutable,
                                                         Consumer<CommittedWriterData> updater) {
        byte[] raw = serialize();

        return immutable.put(signer.publicKeyHash, signer.secret.signatureOnly(raw), raw)
                .thenCompose(blobHash -> {
                    MaybeMultihash newHash = MaybeMultihash.of(blobHash);
                    if (newHash.equals(currentHash)) {
                        // nothing has changed
                        CommittedWriterData committed = committed(newHash);
                        updater.accept(committed);
                        return CompletableFuture.completedFuture(committed);
                    }
                    HashCasPair cas = new HashCasPair(currentHash, newHash);
                    byte[] signed = signer.secret.signMessage(cas.serialize());
                    return mutable.setPointer(owner, signer.publicKeyHash, signed)
                            .thenApply(res -> {
                                if (!res)
                                    throw new IllegalStateException("Corenode Crypto CAS failed!");
                                CommittedWriterData committed = committed(newHash);
                                updater.accept(committed);
                                return committed;
                            });
                });
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> result = new TreeMap<>();

        result.put("controller", new CborObject.CborMerkleLink(controller));
        generationAlgorithm.ifPresent(alg -> result.put("algorithm", alg.toCbor()));
        publicData.ifPresent(rfp -> result.put("public", rfp.toCbor()));
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
        btree.ifPresent(btree -> result.put("btree", new CborObject.CborMerkleLink(btree)));
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

    public static WriterData fromCbor(CborObject cbor, SymmetricKey rootKey) {
        if (! ( cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Cbor for WriterData should be a map! " + cbor);

        CborObject.CborMap map = (CborObject.CborMap) cbor;
        Function<String, Optional<Cborable>> extract = key -> {
            CborObject.CborString cborKey = new CborObject.CborString(key);
            return map.values.containsKey(cborKey) ? Optional.of(map.values.get(cborKey)) : Optional.empty();
        };

        PublicKeyHash controller = extract.apply("controller").map(PublicKeyHash::fromCbor).get();
        Optional<SecretGenerationAlgorithm> algo  = extractUserGenerationAlgorithm(cbor);
        Optional<FilePointer> publicData = extract.apply("public").map(FilePointer::fromCbor);
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

        // rootKey is null for other people parsing our WriterData who don't have our root key
        Optional<UserStaticData> staticData = rootKey == null ? Optional.empty() : extract.apply("static").map(raw -> UserStaticData.fromCbor(raw, rootKey));
        Optional<Multihash> tree = extract.apply("tree").map(val -> ((CborObject.CborMerkleLink)val).target);
        Optional<Multihash> btree = extract.apply("btree").map(val -> ((CborObject.CborMerkleLink)val).target);
        return new WriterData(controller, algo, publicData, followRequestReceiver, owned, named, staticData, tree, btree);
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
                    return new CommittedWriterData(MaybeMultihash.of(hash), WriterData.fromCbor(cborOpt.get(), null));
                });
    }
}
