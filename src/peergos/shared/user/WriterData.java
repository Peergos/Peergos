package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
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
    public final Optional<UserGenerationAlgorithm> generationAlgorithm;
    // accessible under IPFS address $hash/public
    public final Optional<FilePointer> publicData;
    // The public boxing key to encrypt follow requests to, accessible under IPFS address $hash/inbound
    public final Optional<PublicKeyHash> followRequestReceiver;
    // accessible under IPFS address $hash/owned
    public final Set<PublicKeyHash> ownedKeys;

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
                      Optional<UserGenerationAlgorithm> generationAlgorithm,
                      Optional<FilePointer> publicData,
                      Optional<PublicKeyHash> followRequestReceiver,
                      Set<PublicKeyHash> ownedKeys,
                      Optional<UserStaticData> staticData,
                      Optional<Multihash> tree,
                      Optional<Multihash> btree) {
        this.controller = controller;
        this.generationAlgorithm = generationAlgorithm;
        this.publicData = publicData;
        this.followRequestReceiver = followRequestReceiver;
        this.ownedKeys = ownedKeys;
        this.staticData = staticData;
        this.tree = tree;
        this.btree = btree;
        if (tree.isPresent() && btree.isPresent())
            throw new IllegalStateException("A writer cannot have both a legacy btree and a champ!");
    }

    public WriterData withBtree(Multihash treeRoot) {
        return new WriterData(controller, generationAlgorithm, publicData, followRequestReceiver, ownedKeys, staticData, Optional.empty(), Optional.of(treeRoot));
    }

    public WriterData withChamp(Multihash treeRoot) {
        return new WriterData(controller, generationAlgorithm, publicData, followRequestReceiver, ownedKeys, staticData, Optional.of(treeRoot), Optional.empty());
    }

    public WriterData withOwnedKeys(Set<PublicKeyHash> owned) {
        return new WriterData(controller, generationAlgorithm, publicData, followRequestReceiver, owned, staticData, tree, btree);
    }

    public WriterData addOwnedKey(PublicKeyHash newOwned) {
        Set<PublicKeyHash> updated = new HashSet<>(ownedKeys);
        updated.add(newOwned);
        return new WriterData(controller, generationAlgorithm, publicData, followRequestReceiver, updated, staticData, tree, btree);
    }

    public static WriterData createEmpty(PublicKeyHash controller) {
        return new WriterData(controller,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Collections.emptySet(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public static WriterData createEmpty(PublicKeyHash controller,
                                         Optional<PublicKeyHash> followRequestReceiver,
                                         SymmetricKey rootKey) {
        return new WriterData(controller,
                Optional.of(UserGenerationAlgorithm.getDefault()),
                Optional.empty(),
                followRequestReceiver,
                Collections.emptySet(),
                Optional.of(new UserStaticData(rootKey)),
                Optional.empty(),
                Optional.empty());
    }

    public CommittedWriterData committed(MaybeMultihash hash) {
        return new CommittedWriterData(hash, this);
    }

    public CompletableFuture<CommittedWriterData> removeFromStaticData(FileTreeNode fileTreeNode, SigningPrivateKeyAndPublicHash signer,
                                                                       MaybeMultihash currentHash,
                                                                       NetworkAccess network, Consumer<CommittedWriterData> updater) {
        FilePointer pointer = fileTreeNode.getPointer().filePointer;

        return staticData.map(sd -> {
            boolean isRemoved = sd.remove(pointer);

            if (isRemoved)
                return commit(signer, currentHash, network, updater);
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
                                                             UserGenerationAlgorithm newAlgorithm,
                                                             NetworkAccess network,
                                                             Consumer<CommittedWriterData> updater) {
        // auth new key by adding to existing writer data first
        WriterData tmp = addOwnedKey(signer.publicKeyHash);
        return tmp.commit(oldSigner, currentHash, network, x -> {}).thenCompose(tmpCommited -> {
            Optional<UserStaticData> newEntryPoints = staticData.map(sd -> sd.withKey(newKey));
            return network.dhtClient.putBoxingKey(signer.publicKeyHash, signer.secret.signatureOnly(followRequestReceiver.serialize()), followRequestReceiver)
                    .thenCompose(boxerHash -> {
                        WriterData updated = new WriterData(signer.publicKeyHash,
                                Optional.of(newAlgorithm),
                                publicData,
                                Optional.of(new PublicKeyHash(boxerHash)),
                                ownedKeys,
                                newEntryPoints,
                                tree,
                                btree);
                        return updated.commit(signer, MaybeMultihash.empty(), network, updater);
                    });
        });
    }

    public CompletableFuture<CommittedWriterData> commit(SigningPrivateKeyAndPublicHash signer, MaybeMultihash currentHash,
                                                         NetworkAccess network, Consumer<CommittedWriterData> updater) {
        return commit(signer, currentHash, network.mutable, network.dhtClient, updater);
    }

    public CompletableFuture<CommittedWriterData> commit(SigningPrivateKeyAndPublicHash signer, MaybeMultihash currentHash,
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
                    return mutable.setPointer(signer.publicKeyHash, signer.publicKeyHash, signed)
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
        result.put("owned", new CborObject.CborList(ownedKeyStrings));
        staticData.ifPresent(sd -> result.put("static", sd.toCbor()));
        tree.ifPresent(tree -> result.put("tree", new CborObject.CborMerkleLink(tree)));
        btree.ifPresent(btree -> result.put("btree", new CborObject.CborMerkleLink(btree)));
        return CborObject.CborMap.build(result);
    }

    public static Optional<UserGenerationAlgorithm> extractUserGenerationAlgorithm(CborObject cbor) {
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        Function<String, Optional<Cborable>> extract = key -> {
            CborObject.CborString cborKey = new CborObject.CborString(key);
            return map.values.containsKey(cborKey) ? Optional.of(map.values.get(cborKey)) : Optional.empty();
        };
        return extract.apply("algorithm").map(UserGenerationAlgorithm::fromCbor);
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
        Optional<UserGenerationAlgorithm> algo  = extractUserGenerationAlgorithm(cbor);
        Optional<FilePointer> publicData = extract.apply("public").map(FilePointer::fromCbor);
        Optional<PublicKeyHash> followRequestReceiver = extract.apply("inbound").map(PublicKeyHash::fromCbor);
        CborObject.CborList ownedList = (CborObject.CborList) map.values.get(new CborObject.CborString("owned"));
        Set<PublicKeyHash> owned = ownedList.value.stream().map(PublicKeyHash::fromCbor).collect(Collectors.toSet());
        // rootKey is null for other people parsing our WriterData who don't have our root key
        Optional<UserStaticData> staticData = rootKey == null ? Optional.empty() : extract.apply("static").map(raw -> UserStaticData.fromCbor(raw, rootKey));
        Optional<Multihash> tree = extract.apply("tree").map(val -> ((CborObject.CborMerkleLink)val).target);
        Optional<Multihash> btree = extract.apply("btree").map(val -> ((CborObject.CborMerkleLink)val).target);
        return new WriterData(controller, algo, publicData, followRequestReceiver, owned, staticData, tree, btree);
    }

    public static Set<PublicKeyHash> getOwnedKeysRecursive(String username,
                                                           CoreNode core,
                                                           MutablePointers mutable,
                                                           ContentAddressedStorage dht) {
        try {
            Optional<PublicKeyHash> publicKeyHash = core.getPublicKeyHash(username).get();
            return publicKeyHash
                    .map(h -> getOwnedKeysRecursive(h, mutable, dht))
                    .orElseGet(Collections::emptySet);
        } catch (InterruptedException e) {
            return Collections.emptySet();
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    public static Set<PublicKeyHash> getOwnedKeysRecursive(PublicKeyHash writer,
                                                           MutablePointers mutable,
                                                           ContentAddressedStorage dht) {
        Set<PublicKeyHash> res = new HashSet<>();
        res.add(writer);
        try {
            CommittedWriterData subspaceDescriptor = mutable.getPointer(writer)
                    .thenCompose(dataOpt -> dataOpt.isPresent() ?
                            dht.getSigningKey(writer)
                                    .thenApply(signer -> HashCasPair.fromCbor(CborObject.fromByteArray(signer.get()
                                            .unsignMessage(dataOpt.get()))).updated) :
                            CompletableFuture.completedFuture(MaybeMultihash.empty()))
                    .thenCompose(x -> getWriterData(writer, x, dht)).get();

            for (PublicKeyHash subKey : subspaceDescriptor.props.ownedKeys) {
                res.addAll(getOwnedKeysRecursive(subKey, mutable, dht));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return res;
    }

    public static Set<PublicKeyHash> getDirectOwnedKeys(PublicKeyHash writer,
                                                        MutablePointers mutable,
                                                        ContentAddressedStorage dht) {
        Set<PublicKeyHash> res = new HashSet<>();
        try {
            CommittedWriterData subspaceDescriptor = mutable.getPointer(writer)
                    .thenCompose(dataOpt -> dataOpt.isPresent() ?
                            dht.getSigningKey(writer)
                                    .thenApply(signer -> HashCasPair.fromCbor(CborObject.fromByteArray(signer.get()
                                            .unsignMessage(dataOpt.get()))).updated) :
                            CompletableFuture.completedFuture(MaybeMultihash.empty()))
                    .thenCompose(x -> getWriterData(writer, x, dht)).get();

            res.addAll(subspaceDescriptor.props.ownedKeys);
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
        return dht.get(hash.get())
                .thenApply(cborOpt -> {
                    if (! cborOpt.isPresent())
                        throw new IllegalStateException("Couldn't retrieve WriterData from dht! " + hash);
                    return new CommittedWriterData(hash, WriterData.fromCbor(cborOpt.get(), null));
                });
    }
}
