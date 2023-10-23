package peergos.server.storage;

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

    Stream<Cid> getAllBlockHashes();

    Stream<BlockVersion> getAllBlockHashVersions();

    default Stream<BlockVersion> getAllRawBlockVersions() {
        return getAllBlockHashVersions().filter(v -> v.cid.isRaw());
    }

    List<Multihash> getOpenTransactionBlocks();

    void clearOldTransactions(long cutoffMillis);

    boolean hasBlock(Cid hash);

    void delete(Cid block);

    default void delete(BlockVersion blockVersion) {
        delete(blockVersion.cid);
    }

    default void bloomAdd(Multihash hash) {}

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
    CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, Cid hash, String auth, boolean persistBlock);

    default CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds,
                                                        Cid hash,
                                                        Optional<BatWithId> bat,
                                                        Cid ourId,
                                                        Hasher h,
                                                        boolean persistBlock) {
        if (bat.isEmpty())
            return get(peerIds, hash, "", persistBlock);
        return bat.get().bat.generateAuth(hash, ourId, 300, S3Request.currentDatetime(), bat.get().id, h)
                .thenApply(BlockAuth::encode)
                .thenCompose(auth -> get(peerIds, hash, auth, persistBlock));
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
                                               Cid hash,
                                               String auth,
                                               boolean persistBlock);

    default CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds,
                                                       Cid hash,
                                                       String auth,
                                                       boolean doAuth,
                                                       boolean persistBlock) {
        return getRaw(peerIds, hash, auth, persistBlock);
    }

    default CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds,
                                                       Cid hash,
                                                       Optional<BatWithId> bat,
                                                       Cid ourId,
                                                       Hasher h,
                                                       boolean persistBlock) {
        return getRaw(peerIds, hash, bat, ourId, h, true, persistBlock);
    }

    default CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds,
                                                       Cid hash,
                                                       Optional<BatWithId> bat,
                                                       Cid ourId,
                                                       Hasher h,
                                                       boolean doAuth,
                                                       boolean persistBlock) {
        if (bat.isEmpty())
            return getRaw(peerIds, hash, "", persistBlock);
        return bat.get().bat.generateAuth(hash, ourId, 300, S3Request.currentDatetime(), bat.get().id, h)
                .thenApply(BlockAuth::encode)
                .thenCompose(auth -> getRaw(peerIds, hash, auth, doAuth, persistBlock));
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
    default CompletableFuture<List<Cid>> mirror(PublicKeyHash owner,
                                                List<Multihash> peerIds,
                                                Optional<Cid> existing,
                                                Optional<Cid> updated,
                                                Optional<BatWithId> mirrorBat,
                                                Cid ourNodeId,
                                                TransactionId tid,
                                                Hasher hasher) {
        if (updated.isEmpty())
            return Futures.of(Collections.emptyList());
        Cid newRoot = updated.get();
        if (existing.equals(updated))
            return Futures.of(Collections.singletonList(newRoot));
        boolean isRaw = newRoot.isRaw();

        Optional<byte[]> newVal = RetryStorage.runWithRetry(3, () -> getRaw(peerIds, newRoot, mirrorBat, ourNodeId, hasher, false, true)).join();
        if (newVal.isEmpty())
            throw new IllegalStateException("Couldn't retrieve block: " + newRoot);
        if (isRaw)
            return Futures.of(Collections.singletonList(newRoot));

        CborObject newBlock = CborObject.fromByteArray(newVal.get());
        List<Multihash> newLinks = newBlock.links().stream()
                .filter(h -> !h.isIdentity())
                .collect(Collectors.toList());
        List<Multihash> existingLinks = existing.map(h -> getLinks(h).join()
                        .stream()
                        .filter(c -> ! c.isIdentity())
                        .map(c -> (Multihash) c)
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());

        for (int i=0; i < newLinks.size(); i++) {
            Optional<Cid> existingLink = i < existingLinks.size() ?
                    Optional.of((Cid)existingLinks.get(i)) :
                    Optional.empty();
            Optional<Cid> updatedLink = Optional.of((Cid)newLinks.get(i));
            mirror(owner, peerIds, existingLink, updatedLink, mirrorBat, ourNodeId, tid, hasher).join();
        }
        return Futures.of(Collections.singletonList(newRoot));
    }

    /**
     * Get all the merkle-links referenced directly from this object
     * @param root The hash of the object whose links we want
     * @return A list of the multihashes referenced with ipld links in this object
     */
    default CompletableFuture<List<Cid>> getLinks(Cid root) {
        if (root.isRaw())
            return CompletableFuture.completedFuture(Collections.emptyList());
        return get(Collections.emptyList(), root, "", true).thenApply(opt -> opt
                .map(cbor -> cbor.links().stream().map(c -> (Cid) c).collect(Collectors.toList()))
                .orElse(Collections.emptyList())
        );
    }

    default CompletableFuture<Long> getRecursiveBlockSize(Cid block) {
        return getLinks(block).thenCompose(links -> {
            List<CompletableFuture<Long>> subtrees = links.stream()
                    .filter(m -> ! m.isIdentity())
                    .map(c -> Futures.runAsync(() -> getRecursiveBlockSize(c)))
                    .collect(Collectors.toList());
            return getSize(block)
                    .thenCompose(sizeOpt -> {
                        CompletableFuture<Long> reduced = Futures.reduceAll(subtrees,
                                0L, (t, fut) -> fut.thenApply(x -> x + t), (a, b) -> a + b);
                        return reduced.thenApply(sum -> sum + sizeOpt.orElse(0));
                    });
        });
    }

    default CompletableFuture<Long> getChangeInContainedSize(Optional<Cid> original, Cid updated) {
        if (! original.isPresent())
            return getRecursiveBlockSize(updated);
        return getChangeInContainedSize(original.get(), updated);
    }

    default CompletableFuture<BlockMetadata> getBlockMetadata(Cid block) {
        return getRaw(Collections.emptyList(), block, "", true)
                .thenApply(rawOpt -> BlockMetadataStore.extractMetadata(block, rawOpt.get()));
    }

    default CompletableFuture<Long> getChangeInContainedSize(Cid original, Cid updated) {
        return getBlockMetadata(original)
                .thenCompose(before -> getBlockMetadata(updated).thenCompose(after -> {
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

                    CompletableFuture<Long> beforeRes = Futures.runAsync(() -> getAllRecursiveSizes(extraBefore), usagePool);
                    CompletableFuture<Long> afterRes = Futures.runAsync(() -> getAllRecursiveSizes(extraAfter), usagePool);
                    CompletableFuture<Long> pairsRes = Futures.runAsync(() -> getSizeDiff(pairs), usagePool);
                    return beforeRes.thenCompose(priorSize -> afterRes.thenApply(postSize -> postSize - priorSize + objectDelta))
                            .thenCompose(total -> pairsRes.thenApply(res -> res + total));
                }));
    }

    private CompletableFuture<Long> getAllRecursiveSizes(List<Cid> roots) {
        List<CompletableFuture<Long>> allSizes = roots.stream()
                .map(c -> Futures.runAsync(() -> getRecursiveBlockSize(c), usagePool))
                .collect(Collectors.toList());
        return Futures.reduceAll(allSizes,
                0L,
                (s, f) -> f.thenApply(size -> size + s),
                (a, b) -> a + b);
    }

    private CompletableFuture<Long> getSizeDiff(List<Pair<Cid, Cid>> pairs) {
        List<CompletableFuture<Long>> pairDiffs = pairs.stream()
                .map(p -> Futures.runAsync(() -> getChangeInContainedSize(p.left, p.right), usagePool))
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
        public Stream<Cid> getAllBlockHashes() {
            String jsonStream = new String(poster.get(apiPrefix + REFS_LOCAL).join());
            return JSONParser.parseStream(jsonStream).stream()
                    .map(m -> (String) (((Map) m).get("Ref")))
                    .map(Cid::decode);
        }

        @Override
        public Stream<BlockVersion> getAllBlockHashVersions() {
            return getAllBlockHashes().map(c -> new BlockVersion(c, null, true));
        }

        @Override
        public void delete(Cid hash) {
            poster.get(apiPrefix + BLOCK_RM + "?stream-channels=true&arg=" + hash.toString()).join();
        }

        @Override
        public void bloomAdd(Multihash hash) {
            poster.get(apiPrefix + BLOOM_ADD + "?stream-channels=true&arg=" + hash.toString()).join();
        }

        @Override
        public boolean hasBlock(Cid hash) {
            return poster.get(apiPrefix + BLOCK_PRESENT + "?stream-channels=true&arg=" + hash.toString())
                    .thenApply(raw -> new String(raw).equals("true")).join();
        }

        @Override
        public List<Multihash> getOpenTransactionBlocks() {
            throw new IllegalStateException("Unimplemented!");
        }

        @Override
        public void clearOldTransactions(long cutoffMillis) {
            throw new IllegalStateException("Unimplemented!");
        }

        @Override
        public void setPki(CoreNode pki) {}

        @Override
        public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, Cid hash, String auth, boolean persistBlock) {
            if (hash.isIdentity())
                return CompletableFuture.completedFuture(Optional.of(CborObject.fromByteArray(hash.getHash())));
            return poster.get(apiPrefix + BLOCK_GET + "?stream-channels=true&arg=" + hash
                            + (peerIds.isEmpty() ? "" : "&peers=" + peerIds.stream().map(p -> p.bareMultihash().toBase58()).collect(Collectors.joining(",")))
                            + "&auth=" + auth)
                    .thenApply(raw -> raw.length == 0 ? Optional.empty() : Optional.of(CborObject.fromByteArray(raw)));
        }

        @Override
        public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, Cid hash, String auth, boolean persistBlock) {
            if (hash.isIdentity())
                return CompletableFuture.completedFuture(Optional.of(hash.getHash()));
            return poster.get(apiPrefix + BLOCK_GET + "?stream-channels=true&arg=" + hash
                            + (peerIds.isEmpty() ? "" : "&peers=" + peerIds.stream().map(p -> p.bareMultihash().toBase58()).collect(Collectors.joining(",")))
                            + "&auth=" + auth
                            + "&=persist" + persistBlock)
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
                .thenCompose(wd -> wd.props.applyToOwnedKeys(owner, owned ->
                                owned.applyToAllMappings(owner, Collections.emptySet(), composer, ipfs), ipfs, hasher)
                        .thenApply(owned -> Stream.concat(owned.stream(),
                                wd.props.namedOwnedKeys.values().stream()).collect(Collectors.toSet())))
                .thenCompose(all -> Futures.reduceAll(all, Collections.emptySet(),
                        proofComposer,
                        (a, b) -> Stream.concat(a.stream(), b.stream())
                                .collect(Collectors.toSet())));
    }

    static CompletableFuture<CommittedWriterData> getWriterData(List<Multihash> peerIds,
                                                                Cid hash,
                                                                Optional<Long> sequence,
                                                                DeletableContentAddressedStorage dht) {
        return dht.get(peerIds, hash, "", false)
                .thenApply(cborOpt -> {
                    if (! cborOpt.isPresent())
                        throw new IllegalStateException("Couldn't retrieve WriterData from dht! " + hash);
                    return new CommittedWriterData(MaybeMultihash.of(hash), WriterData.fromCbor(cborOpt.get()), sequence);
                });
    }
}
