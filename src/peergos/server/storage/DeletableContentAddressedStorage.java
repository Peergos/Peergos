package peergos.server.storage;

import peergos.server.storage.auth.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** This interface is only used locally on a server and never exposed.
 *  These methods allow garbage collection and local mirroring to be implemented.
 *
 */
public interface DeletableContentAddressedStorage extends ContentAddressedStorage {

    ForkJoinPool usagePool = Threads.newPool(100, "Usage-updater-");

    Stream<Pair<PublicKeyHash, Cid>> getAllBlockHashes(boolean useBlockstore);

    void getAllBlockHashVersions(Consumer<List<BlockVersion>> res);

    default void getAllRawBlockVersions(Consumer<List<BlockVersion>> res) {
        getAllBlockHashVersions(all -> res.accept(all.stream().filter(v -> v.cid.isRaw()).collect(Collectors.toList())));
    }

    List<Cid> getOpenTransactionBlocks();

    void clearOldTransactions(long cutoffMillis);

    boolean hasBlock(Cid hash);

    void delete(Cid block);

    default void delete(BlockVersion blockVersion) {
        delete(blockVersion.cid);
    }

    default Optional<BlockMetadataStore> getBlockMetadataStore() {
        return Optional.empty();
    }

    default void bulkDelete(List<BlockVersion> blockVersions) {
        for (BlockVersion version : blockVersions) {
            delete(version);
        }
    }

    void setPki(CoreNode pki);

    /**
     * @param peerIds
     * @param hash
     * @param persistBlock
     * @return The data with the requested hash, deserialized into cbor, or Optional.empty() if no object can be found
     */
    CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, String auth, boolean persistBlock);

    default CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds,
                                                        PublicKeyHash owner,
                                                        Cid hash,
                                                        Optional<BatWithId> bat,
                                                        Cid ourId,
                                                        Hasher h,
                                                        boolean persistBlock) {
        if (bat.isEmpty())
            return get(peerIds, owner, hash, "", persistBlock);
        return bat.get().bat.generateAuth(hash, ourId, 300, S3Request.currentDatetime(), bat.get().id, h)
                .thenApply(BlockAuth::encode)
                .thenCompose(auth -> get(peerIds, owner, hash, auth, persistBlock));
    }

    /**
     * Get a block of data that is not in ipld cbor format, just raw bytes
     *
     * @param peerIds
     * @param hash
     * @param persistBlock
     * @return
     */
    CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds,
                                               PublicKeyHash owner,
                                               Cid hash,
                                               String auth,
                                               boolean persistBlock);

    default CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds,
                                                       PublicKeyHash owner,
                                                       Cid hash,
                                                       String auth,
                                                       boolean doAuth,
                                                       boolean persistBlock) {
        return getRaw(peerIds, owner, hash, auth, persistBlock);
    }

    default CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds,
                                                       PublicKeyHash owner,
                                                       Cid hash,
                                                       Optional<BatWithId> bat,
                                                       Cid ourId,
                                                       Hasher h,
                                                       boolean persistBlock) {
        return getRaw(peerIds, owner, hash, bat, ourId, h, true, persistBlock);
    }

    default String generateAuth(Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h) {
        return bat.isEmpty() ? "" : bat.get().bat.generateAuth(hash, ourId, 300, S3Request.currentDatetime(), bat.get().id, h)
                .thenApply(BlockAuth::encode).join();
    }

    CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds,
                                               PublicKeyHash owner,
                                               Cid hash,
                                               Optional<BatWithId> bat,
                                               Cid ourId,
                                               Hasher h,
                                               boolean doAuth,
                                               boolean persistBlock);

    default CompletableFuture<List<Cid>> mirror(String username,
                                                PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<Multihash> peerIds,
                                               Optional<Cid> existing,
                                               Optional<Cid> updated,
                                               Optional<BatWithId> mirrorBat,
                                               Cid ourNodeId,
                                               NewBlocksProcessor newBlockProcessor,
                                               TransactionId tid,
                                               Hasher hasher) {
        return mirror(username, owner, writer, peerIds, existing, updated, mirrorBat, ourNodeId, newBlockProcessor, tid, hasher, this);
    }

    /**
     * Ensure that local copies of all blocks in merkle tree referenced are present locally
     *
     * @param owner
     * @param peerIds
     * @param existing
     * @param updated
     * @return
     */
    static CompletableFuture<List<Cid>> mirror(String username,
                                               PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<Multihash> peerIds,
                                               Optional<Cid> existing,
                                               Optional<Cid> updated,
                                               Optional<BatWithId> mirrorBat,
                                               Cid ourNodeId,
                                               NewBlocksProcessor newBlockProcessor,
                                               TransactionId tid,
                                               Hasher hasher,
                                               DeletableContentAddressedStorage storage) {
        if (updated.isEmpty())
            return Futures.of(Collections.emptyList());
        Cid newRoot = updated.get();
        if (existing.equals(updated))
            return Futures.of(Collections.singletonList(newRoot));
        boolean isRaw = newRoot.isRaw();

        Optional<byte[]> newVal = RetryStorage.runWithRetry(3, () -> storage.getRaw(peerIds, owner, newRoot, mirrorBat, ourNodeId, hasher, false, true)).join();
        if (newVal.isEmpty())
            throw new IllegalStateException("Couldn't retrieve block: " + newRoot);
        newBlockProcessor.process(writer, List.of(newRoot), newVal.get().length);
        if (isRaw)
            return Futures.of(Collections.singletonList(newRoot));

        CborObject newBlock = CborObject.fromByteArray(newVal.get());
        List<Cid> newLinks = newBlock.links().stream()
                .filter(h -> !h.isIdentity())
                .map(m -> (Cid) m)
                .collect(Collectors.toList());
        List<Cid> existingLinks = existing.map(h -> storage.getLinks(owner, h, Arrays.asList(ourNodeId)).join()
                        .stream()
                        .filter(c -> ! c.isIdentity())
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());

        return storage.bulkMirror(owner, writer, peerIds, existingLinks, newLinks, mirrorBat, ourNodeId,
                (peers, o, cs, b) -> storage.bulkGetLinks(peerIds, owner, ourNodeId, cs, mirrorBat, hasher),
                newBlockProcessor, tid, hasher);
    }

    default CompletableFuture<List<Cid>> bulkMirror(PublicKeyHash owner,
                                                    PublicKeyHash writer,
                                                    List<Multihash> peerIds,
                                                    List<Cid> existing,
                                                    List<Cid> updated,
                                                    Optional<BatWithId> mirrorBat,
                                                    Cid ourNodeId,
                                                    P2pBlockGet retriever,
                                                    NewBlocksProcessor newBlockProcessor,
                                                    TransactionId tid,
                                                    Hasher hasher) {
        if (updated.isEmpty())
            return Futures.of(updated);
        Set<Cid> common = new HashSet<>(existing);
        common.retainAll(updated);

        List<Cid> removed = existing.stream()
                .filter(x -> ! common.contains(x))
                .filter(c -> ! c.isIdentity())
                .collect(Collectors.toList());
        List<Cid> added = updated.stream()
                .filter(x -> ! common.contains(x))
                .filter(c -> ! c.isIdentity())
                .collect(Collectors.toList());

        List<BlockMetadata> addedLinks = retriever.bulkGet(peerIds, owner, added, mirrorBat);
        newBlockProcessor.process(writer, added, addedLinks.stream().mapToInt(p -> p.size).sum());
        if (removed.isEmpty()) {
            List<Cid> allCbor = addedLinks.stream()
                    .map(p -> p.links)
                    .flatMap(Collection::stream)
                    .filter(c -> !c.isIdentity() && !c.isRaw())
                    .collect(Collectors.toList());
            for (int i=0; i < allCbor.size();) {
                int end = Math.min(allCbor.size(), i + 100);
                bulkMirror(owner, writer, peerIds, Collections.emptyList(), allCbor.subList(i, end), mirrorBat, ourNodeId, retriever, newBlockProcessor, tid, hasher);
                i = end;
            }
            List<Cid> allRaw = addedLinks.stream()
                    .map(p -> p.links)
                    .flatMap(Collection::stream)
                    .filter(c -> !c.isIdentity() && c.isRaw())
                    .collect(Collectors.toList());
            for (int i=0; i < allRaw.size();i++) {
                bulkMirror(owner, writer, peerIds, Collections.emptyList(), allRaw.subList(i, i+1), mirrorBat, ourNodeId, retriever, newBlockProcessor, tid, hasher);
            }
        } else {
            for (int i = 0; i < added.size(); i++) {
                List<Cid> newLinks = addedLinks.get(i).links;
                List<Cid> existingLinks = i >= removed.size() ?
                        Collections.emptyList() :
                        getLinks(owner, removed.get(i), Arrays.asList(ourNodeId)).join().stream()
                                .filter(c -> !c.isIdentity())
                                .collect(Collectors.toList());
                bulkMirror(owner, writer, peerIds, existingLinks, newLinks, mirrorBat, ourNodeId, retriever, newBlockProcessor, tid, hasher);
            }
        }
        return Futures.of(updated);
    }

    List<BlockMetadata> bulkGetLinks(List<Multihash> peerIds, PublicKeyHash owner, List<Want> wants);

    default List<BlockMetadata> bulkGetLinks(List<Multihash> peerIds,
                                             PublicKeyHash owner,
                                             Cid ourId,
                                             List<Cid> blocks,
                                             Optional<BatWithId> mirrorBat,
                                             Hasher h) {
        List<Want> wants = blocks.stream()
                .map(c -> new Want(c, mirrorBat.map(b -> b.bat.generateAuth(c, ourId, 300, S3Request.currentDatetime(), b.id, h)
                        .thenApply(BlockAuth::encode).join())))
                .collect(Collectors.toList());
        return bulkGetLinks(peerIds, owner, wants);
    }

    /**
     * Get all the merkle-links referenced directly from this object
     * @param root The hash of the object whose links we want
     * @return A list of the multihashes referenced with ipld links in this object
     */
    CompletableFuture<List<Cid>> getLinks(PublicKeyHash owner, Cid root, List<Multihash> peerids);

    default CompletableFuture<Long> getRecursiveBlockSize(PublicKeyHash owner, Cid block, List<Multihash> peerids) {
        return getLinks(owner, block, peerids).thenCompose(links -> {
            List<CompletableFuture<Long>> subtrees = links.stream()
                    .filter(m -> ! m.isIdentity())
                    .map(c -> Futures.runAsync(() -> getRecursiveBlockSize(owner, c, peerids)))
                    .collect(Collectors.toList());
            return getSize(owner, block)
                    .thenCompose(sizeOpt -> {
                        CompletableFuture<Long> reduced = Futures.reduceAll(subtrees,
                                0L, (t, fut) -> fut.thenApply(x -> x + t), (a, b) -> a + b);
                        return reduced.thenApply(sum -> sum + sizeOpt.orElse(0));
                    });
        });
    }

    default CompletableFuture<Long> getChangeInContainedSize(PublicKeyHash owner, Optional<Cid> original, Cid updated) {
        if (! original.isPresent())
            return getRecursiveBlockSize(owner, updated, Arrays.asList(id().join()));
        return getChangeInContainedSize(owner, original.get(), updated);
    }

    CompletableFuture<BlockMetadata> getBlockMetadata(PublicKeyHash owner, Cid block);

    default CompletableFuture<Long> getChangeInContainedSize(PublicKeyHash owner, Cid original, Cid updated) {
        return getBlockMetadata(owner, original)
                .thenCompose(before -> getBlockMetadata(owner, updated).thenCompose(after -> {
                    int objectDelta = after.size - before.size;
                    List<Cid> beforeLinks = before.links.stream().filter(c -> !c.isIdentity()).collect(Collectors.toList());
                    List<Cid> onlyBefore = new ArrayList<>(beforeLinks);
                    onlyBefore.removeAll(after.links);
                    List<Cid> afterLinks = after.links.stream().filter(c -> !c.isIdentity()).collect(Collectors.toList());
                    List<Cid> onlyAfter = new ArrayList<>(afterLinks);
                    onlyAfter.removeAll(before.links);

                    int nPairs = Math.min(onlyBefore.size(), onlyAfter.size());
                    List<Pair<Cid, Cid>> pairs = IntStream.range(0, nPairs)
                            .mapToObj(i -> new Pair<>(onlyBefore.get(i), onlyAfter.get(i)))
                            .collect(Collectors.toList());

                    List<Cid> extraBefore = onlyBefore.subList(nPairs, onlyBefore.size());
                    List<Cid> extraAfter = onlyAfter.subList(nPairs, onlyAfter.size());

                    CompletableFuture<Long> beforeRes = Futures.runAsync(() -> getAllRecursiveSizes(owner, extraBefore), usagePool);
                    CompletableFuture<Long> afterRes = Futures.runAsync(() -> getAllRecursiveSizes(owner, extraAfter), usagePool);
                    CompletableFuture<Long> pairsRes = Futures.runAsync(() -> getSizeDiff(owner, pairs), usagePool);
                    return beforeRes.thenCompose(priorSize -> afterRes.thenApply(postSize -> postSize - priorSize + objectDelta))
                            .thenCompose(total -> pairsRes.thenApply(res -> res + total));
                }));
    }

    private CompletableFuture<Long> getAllRecursiveSizes(PublicKeyHash owner, List<Cid> roots) {
        List<CompletableFuture<Long>> allSizes = roots.stream()
                .map(c -> Futures.runAsync(() -> getRecursiveBlockSize(owner, c, Arrays.asList(id().join())), usagePool))
                .collect(Collectors.toList());
        return Futures.reduceAll(allSizes,
                0L,
                (s, f) -> f.thenApply(size -> size + s),
                (a, b) -> a + b);
    }

    private CompletableFuture<Long> getSizeDiff(PublicKeyHash owner, List<Pair<Cid, Cid>> pairs) {
        List<CompletableFuture<Long>> pairDiffs = pairs.stream()
                .map(p -> Futures.runAsync(() -> getChangeInContainedSize(owner, p.left, p.right), usagePool))
                .collect(Collectors.toList());
        return Futures.reduceAll(pairDiffs,
                0L,
                (s, f) -> f.thenApply(size -> size + s),
                (a, b) -> a + b);
    }

    class HTTP extends ContentAddressedStorage.HTTP implements DeletableContentAddressedStorage {

        private final HttpPoster poster;

        public HTTP(HttpPoster poster, boolean isPeergosServer, Hasher hasher) {
            super(poster, isPeergosServer, hasher);
            this.poster = poster;
        }

        @Override
        public Stream<Pair<PublicKeyHash, Cid>> getAllBlockHashes(boolean useBlockstore) {
            String jsonStream = new String(poster.postUnzip(apiPrefix + REFS_LOCAL + "?use-block-store=" + useBlockstore, new byte[0], -1).join());
            return JSONParser.parseStream(jsonStream).stream()
                    .map(m -> (String) (((Map) m).get("Ref")))
                    .map(Cid::decode).map(c -> new Pair<>(PublicKeyHash.NULL, c));
        }

        @Override
        public void getAllBlockHashVersions(Consumer<List<BlockVersion>> res) {
            res.accept(getAllBlockHashes(false)
                    .map(p -> new BlockVersion(p.right, null, true))
                    .collect(Collectors.toList()));
        }

        @Override
        public void delete(Cid hash) {
            poster.get(apiPrefix + BLOCK_RM + "?arg=" + hash.toString()).join();
        }

        @Override
        public void bulkDelete(List<BlockVersion> blocks) {
            Map<String, Object> json = new HashMap<>();
            json.put("cids", blocks.stream()
                    .map(v -> v.cid.toString())
                    .collect(Collectors.toList()));
            poster.postUnzip(apiPrefix + BLOCK_RM_BULK, JSONParser.toString(json).getBytes(), -1);
        }

        @Override
        public CompletableFuture<BlockMetadata> getBlockMetadata(PublicKeyHash owner, Cid block) {
            throw new IllegalStateException("Unimplemented!");
        }

        @Override
        public CompletableFuture<List<Cid>> getLinks(PublicKeyHash owner, Cid root, List<Multihash> peerids) {
            throw new IllegalStateException("Unimplemented!");
        }

        @Override
        public List<BlockMetadata> bulkGetLinks(List<Multihash> peerIds, PublicKeyHash owner, List<Want> wants) {
            if (wants.isEmpty())
                return Collections.emptyList();
            Map<String, Object> json = new HashMap<>();
            json.put("wants", wants.stream()
                    .map(Want::toJson)
                    .collect(Collectors.toList()));
            String peers = peerIds.stream()
                    .map(Multihash::bareMultihash)
                    .map(Multihash::toBase58)
                    .collect(Collectors.joining(","));
            return poster.post(apiPrefix + BLOCK_STAT_BULK + "?peers=" + peers, JSONParser.toString(json).getBytes(), true, -1)
                    .thenApply(raw -> ((List<Map<String, Object>>) JSONParser.parse(new String(raw)))
                            .stream()
                            .map(BlockMetadata::fromJSON)
                            .collect(Collectors.toList()))
                    .join();
        }

        @Override
        public boolean hasBlock(Cid hash) {
            return poster.get(apiPrefix + BLOCK_PRESENT + "?arg=" + hash.toString())
                    .thenApply(raw -> new String(raw).equals("true")).join();
        }

        @Override
        public List<Cid> getOpenTransactionBlocks() {
            throw new IllegalStateException("Unimplemented!");
        }

        @Override
        public void clearOldTransactions(long cutoffMillis) {
            throw new IllegalStateException("Unimplemented!");
        }

        @Override
        public void setPki(CoreNode pki) {}

        @Override
        public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, String auth, boolean persistBlock) {
            if (hash.isIdentity())
                return CompletableFuture.completedFuture(Optional.of(CborObject.fromByteArray(hash.getHash())));
            if (peerIds.isEmpty())
                throw new IllegalStateException("Empty peer list for block "+hash+"!");
            return poster.get(apiPrefix + BLOCK_GET + "?stream-channels=true&arg=" + hash
                            + (peerIds.isEmpty() ? "" : "&peers=" + peerIds.stream().map(p -> p.bareMultihash().toBase58()).collect(Collectors.joining(",")))
                            + "&owner=" + owner
                            + "&auth=" + auth
                            + "&persist=" + persistBlock)
                    .thenApply(raw -> raw.length == 0 ? Optional.empty() : Optional.of(CborObject.fromByteArray(raw)));
        }

        @Override
        public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds,
                                                          PublicKeyHash owner,
                                                          Cid hash,
                                                          Optional<BatWithId> bat,
                                                          Cid ourId,
                                                          Hasher h,
                                                          boolean doAuth,
                                                          boolean persistBlock) {
            if (hash.isIdentity())
                return CompletableFuture.completedFuture(Optional.of(hash.getHash()));
            if (peerIds.isEmpty())
                throw new IllegalStateException("Empty peer list for block "+hash+"!");
            return poster.get(apiPrefix + BLOCK_GET + "?stream-channels=true&arg=" + hash
                            + (peerIds.isEmpty() ? "" : "&peers=" + peerIds.stream().map(p -> p.bareMultihash().toBase58()).collect(Collectors.joining(",")))
                            + "&owner=" + owner
                            + bat.map(b -> "&bat=" + b.encode()).orElse("")
                            + "&persist=" + persistBlock)
                    .thenApply(raw -> raw.length == 0 ? Optional.empty() : Optional.of(raw));
        }

        @Override
        public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, String auth, boolean persistBlock) {
            if (hash.isIdentity())
                return CompletableFuture.completedFuture(Optional.of(hash.getHash()));
            if (peerIds.isEmpty())
                throw new IllegalStateException("Empty peer list for block "+hash+"!");
            return poster.get(apiPrefix + BLOCK_GET + "?stream-channels=true&arg=" + hash
                            + (peerIds.isEmpty() ? "" : "&peers=" + peerIds.stream().map(p -> p.bareMultihash().toBase58()).collect(Collectors.joining(",")))
                            + "&owner=" + owner
                            + "&auth=" + auth
                            + "&persist=" + persistBlock)
                    .thenApply(raw -> raw.length == 0 ? Optional.empty() : Optional.of(raw));
        }
    }

    public static CompletableFuture<Set<PublicKeyHash>> getOwnedKeysRecursive(String username,
                                                                              CoreNode core,
                                                                              MutablePointers mutable,
                                                                              CommittedWriterData.Retriever retriever,
                                                                              ContentAddressedStorage dht,
                                                                              Hasher hasher) {
        List<UserPublicKeyLink> chain = core.getChain(username).join();
        if (chain.isEmpty())
            return Futures.of(Collections.emptySet());
        UserPublicKeyLink last = chain.get(chain.size() - 1);
        PublicKeyHash owner = last.owner;
        return getOwnedKeysRecursive(owner, owner, mutable, retriever, dht, hasher);
    }

    public static CompletableFuture<Set<PublicKeyHash>> getOwnedKeysRecursive(PublicKeyHash owner,
                                                                              PublicKeyHash writer,
                                                                              MutablePointers mutable,
                                                                              CommittedWriterData.Retriever retriever,
                                                                              ContentAddressedStorage ipfs,
                                                                              Hasher hasher) {
        return getOwnedKeysRecursive(owner, writer, Collections.emptySet(), mutable, retriever, ipfs, hasher);
    }

    private static CompletableFuture<Set<PublicKeyHash>> getOwnedKeysRecursive(PublicKeyHash owner,
                                                                               PublicKeyHash writer,
                                                                               Set<PublicKeyHash> alreadyDone,
                                                                               MutablePointers mutable,
                                                                               CommittedWriterData.Retriever retriever,
                                                                               ContentAddressedStorage ipfs,
                                                                               Hasher hasher) {
        return getDirectOwnedKeys(owner, writer, mutable, retriever, ipfs, hasher)
                .thenCompose(directOwned -> {
                    Set<PublicKeyHash> newKeys = directOwned.stream().
                            filter(h -> ! alreadyDone.contains(h))
                            .collect(Collectors.toSet());
                    Set<PublicKeyHash> done = new HashSet<>(alreadyDone);
                    done.add(writer);
                    BiFunction<Set<PublicKeyHash>, PublicKeyHash, CompletableFuture<Set<PublicKeyHash>>> composer =
                            (a, w) -> getOwnedKeysRecursive(owner, w, a, mutable, retriever, ipfs, hasher)
                                    .thenApply(ws ->
                                            Stream.concat(ws.stream(), a.stream())
                                                    .collect(Collectors.toSet()));
                    return Futures.reduceAll(newKeys, done,
                            composer,
                            (a, b) -> Stream.concat(a.stream(), b.stream())
                                    .collect(Collectors.toSet()));
                });
    }

    public static CompletableFuture<Set<PublicKeyHash>> getDirectOwnedKeys(PublicKeyHash owner,
                                                                           PublicKeyHash writer,
                                                                           MutablePointers mutable,
                                                                           CommittedWriterData.Retriever retriever,
                                                                           ContentAddressedStorage ipfs,
                                                                           Hasher hasher) {
        return mutable.getPointerTarget(owner, writer, ipfs)
                .thenCompose(h -> getDirectOwnedKeys(owner, writer, h.updated, retriever, ipfs, hasher));
    }

    public static CompletableFuture<Set<PublicKeyHash>> getDirectOwnedKeys(PublicKeyHash owner,
                                                                           PublicKeyHash writer,
                                                                           MaybeMultihash root,
                                                                           CommittedWriterData.Retriever retriever,
                                                                           ContentAddressedStorage ipfs,
                                                                           Hasher hasher) {
        if (! root.isPresent())
            return CompletableFuture.completedFuture(Collections.emptySet());

        BiFunction<Set<OwnerProof>, Pair<PublicKeyHash, OwnerProof>, CompletableFuture<Set<OwnerProof>>>
                composer = (acc, pair) -> CompletableFuture.completedFuture(Stream.concat(acc.stream(), Stream.of(pair.right))
                .collect(Collectors.toSet()));

        BiFunction<Set<PublicKeyHash>, OwnerProof, CompletableFuture<Set<PublicKeyHash>>> proofComposer =
                (acc, proof) -> proof.getAndVerifyOwner(owner, ipfs)
                        .thenApply(claimedWriter -> Stream.concat(acc.stream(), claimedWriter.equals(writer) ?
                                Stream.of(proof.ownedKey) :
                                Stream.empty()).collect(Collectors.toSet()));

        return retriever.getWriterData((Cid)root.get(), Optional.empty())
                .thenCompose(wd -> wd.props.get().applyToOwnedKeys(owner, owned ->
                                owned.applyToAllMappings(owner, Collections.emptySet(), composer, ipfs), ipfs, hasher)
                        .thenApply(owned -> Stream.concat(owned.stream(),
                                wd.props.get().namedOwnedKeys.values().stream()).collect(Collectors.toSet())))
                .thenCompose(all -> Futures.reduceAll(all, Collections.emptySet(),
                        proofComposer,
                        (a, b) -> Stream.concat(a.stream(), b.stream())
                                .collect(Collectors.toSet())));
    }

    static CompletableFuture<CommittedWriterData> getWriterData(List<Multihash> peerIds,
                                                                PublicKeyHash owner,
                                                                Cid hash,
                                                                Optional<Long> sequence,
                                                                boolean persistBlock,
                                                                Cid ourId,
                                                                Hasher h,
                                                                DeletableContentAddressedStorage dht) {
        return dht.get(peerIds, owner, hash, Optional.empty(), ourId, h, persistBlock)
                .thenApply(cborOpt -> {
                    if (! cborOpt.isPresent())
                        throw new IllegalStateException("Couldn't retrieve WriterData from dht! " + hash);
                    return new CommittedWriterData(MaybeMultihash.of(hash), WriterData.fromCbor(cborOpt.get()), sequence);
                });
    }
}
