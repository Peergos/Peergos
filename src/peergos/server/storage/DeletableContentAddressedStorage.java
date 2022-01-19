package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
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

    Stream<Cid> getAllBlockHashes();

    List<Multihash> getOpenTransactionBlocks();

    boolean hasBlock(Cid hash);

    void delete(Multihash hash);

    default void bulkDelete(List<Multihash> blocks) {
        for (Multihash block : blocks) {
            delete(block);
        }
    }

    /**
     *
     * @param hash
     * @return The data with the requested hash, deserialized into cbor, or Optional.empty() if no object can be found
     */
    CompletableFuture<Optional<CborObject>> get(Cid hash, String auth);

    default CompletableFuture<Optional<CborObject>> get(Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h) {
        if (bat.isEmpty())
            return get(hash, "");
        return bat.get().bat.generateAuth(hash, ourId, 300, S3Request.currentDatetime(), bat.get().id, h)
                .thenApply(BlockAuth::encode)
                .thenCompose(auth -> get(hash, auth));
    }

    /**
     * Get a block of data that is not in ipld cbor format, just raw bytes
     * @param hash
     * @return
     */
    CompletableFuture<Optional<byte[]>> getRaw(Cid hash, String auth);

    default CompletableFuture<Optional<byte[]>> getRaw(Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h) {
        if (bat.isEmpty())
            return getRaw(hash, "");
        return bat.get().bat.generateAuth(hash, ourId, 300, S3Request.currentDatetime(), bat.get().id, h)
                .thenApply(BlockAuth::encode)
                .thenCompose(auth -> getRaw(hash, auth));
    }

    /** Ensure that local copies of all blocks in merkle tree referenced are present locally
     *
     * @param owner
     * @param existing
     * @param updated
     * @return
     */
    default CompletableFuture<List<Cid>> mirror(PublicKeyHash owner,
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

        Optional<CborObject> newVal = get(newRoot, mirrorBat, ourNodeId, hasher).join();
        if (newVal.isEmpty())
            throw new IllegalStateException("Couldn't retrieve block: " + newRoot);
        if (isRaw)
            return Futures.of(Collections.singletonList(newRoot));

        CborObject newBlock = newVal.get();
        List<Multihash> newLinks = newBlock.links();
        List<Multihash> existingLinks = existing.map(h -> get(h, mirrorBat, ourNodeId, hasher).join())
                .flatMap(copt -> copt.map(CborObject::links))
                .orElse(Collections.emptyList());

        for (int i=0; i < newLinks.size(); i++) {
            Optional<Cid> existingLink = i < existingLinks.size() ?
                    Optional.of((Cid)existingLinks.get(i)) :
                    Optional.empty();
            Optional<Cid> updatedLink = Optional.of((Cid)newLinks.get(i));
            mirror(owner, existingLink, updatedLink, mirrorBat, ourNodeId, tid, hasher).join();
        }
        return Futures.of(Collections.singletonList(newRoot));
    }

    /**
     * Get all the merkle-links referenced directly from this object
     * @param root The hash of the object whose links we want
     * @return A list of the multihashes referenced with ipld links in this object
     */
    default CompletableFuture<List<Cid>> getLinks(Cid root, String auth) {
        if (root.codec == Cid.Codec.Raw)
            return CompletableFuture.completedFuture(Collections.emptyList());
        return get(root, auth).thenApply(opt -> opt
                .map(cbor -> cbor.links().stream().map(c -> (Cid) c).collect(Collectors.toList()))
                .orElse(Collections.emptyList())
        );
    }

    default CompletableFuture<Long> getRecursiveBlockSize(Cid block) {
        return getLinks(block, "").thenCompose(links -> {
            List<CompletableFuture<Long>> subtrees = links.stream()
                    .filter(m -> ! m.isIdentity())
                    .map(this::getRecursiveBlockSize)
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

    default CompletableFuture<Pair<Integer, List<Cid>>> getLinksAndSize(Cid block, String auth) {
        return getLinks(block, auth)
                .thenCompose(links -> getSize(block).thenApply(size -> new Pair<>(size.orElse(0), links)));
    }

    default CompletableFuture<Long> getChangeInContainedSize(Cid original, Cid updated) {
        return getLinksAndSize(original, "")
                .thenCompose(before -> getLinksAndSize(updated, "").thenCompose(after -> {
                    int objectDelta = after.left - before.left;
                    List<Cid> onlyBefore = new ArrayList<>(before.right);
                    onlyBefore.removeAll(after.right);
                    List<Cid> onlyAfter = new ArrayList<>(after.right);
                    onlyAfter.removeAll(before.right);

                    int nPairs = Math.min(onlyBefore.size(), onlyAfter.size());
                    List<Pair<Cid, Cid>> pairs = IntStream.range(0, nPairs)
                            .mapToObj(i -> new Pair<>(onlyBefore.get(i), onlyAfter.get(i)))
                            .collect(Collectors.toList());

                    List<Cid> extraBefore = onlyBefore.subList(nPairs, onlyBefore.size());
                    List<Cid> extraAfter = onlyAfter.subList(nPairs, onlyAfter.size());
                    Function<List<Cid>, CompletableFuture<Long>> getAllRecursiveSizes =
                            extra -> Futures.reduceAll(extra,
                                    0L,
                                    (s, h) -> getRecursiveBlockSize(h).thenApply(size -> size + s),
                                    (a, b) -> a + b);

                    Function<List<Pair<Cid, Cid>>, CompletableFuture<Long>> getSizeDiff =
                            ps -> Futures.reduceAll(ps,
                                    0L,
                                    (s, p) -> getChangeInContainedSize(p.left, p.right).thenApply(size -> size + s),
                                    (a, b) -> a + b);
                    return getAllRecursiveSizes.apply(extraBefore)
                            .thenCompose(priorSize -> getAllRecursiveSizes.apply(extraAfter)
                                    .thenApply(postSize -> postSize - priorSize + objectDelta))
                            .thenCompose(total -> getSizeDiff.apply(pairs).thenApply(res -> res + total));
                }));
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
        public void delete(Multihash hash) {
            poster.get(apiPrefix + BLOCK_RM + "?stream-channels=true&arg=" + hash.toString()).join();
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
        public CompletableFuture<Optional<CborObject>> get(Cid hash, String auth) {
            if (hash.isIdentity())
                return CompletableFuture.completedFuture(Optional.of(CborObject.fromByteArray(hash.getHash())));
            return poster.get(apiPrefix + BLOCK_GET + "?stream-channels=true&arg=" + hash + "&auth=" + auth)
                    .thenApply(raw -> raw.length == 0 ? Optional.empty() : Optional.of(CborObject.fromByteArray(raw)));
        }

        @Override
        public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, String auth) {
            if (hash.isIdentity())
                return CompletableFuture.completedFuture(Optional.of(hash.getHash()));
            return poster.get(apiPrefix + BLOCK_GET + "?stream-channels=true&arg=" + hash + "&auth=" + auth)
                    .thenApply(raw -> raw.length == 0 ? Optional.empty() : Optional.of(raw));
        }
    }
}
