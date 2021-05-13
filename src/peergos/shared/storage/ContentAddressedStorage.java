package peergos.shared.storage;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.corenode.Proxy;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public interface ContentAddressedStorage {

    boolean DEBUG_GC = false;
    int MAX_BLOCK_SIZE  = 1024*1024;

    default CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return Futures.of(BlockStoreProperties.empty());
    }

    /**
     *
     * @return an instance of the same type that doesn't do any cross domain requests
     */
    ContentAddressedStorage directToOrigin();

    default CompletableFuture<List<PresignedUrl>> authReads(List<Multihash> blocks) {
        return Futures.errored(new IllegalStateException("Unimplemented call!"));
    }

    default CompletableFuture<List<PresignedUrl>> authWrites(PublicKeyHash owner,
                                                             PublicKeyHash writer,
                                                             List<byte[]> signedHashes,
                                                             List<Integer> blockSizes,
                                                             boolean isRaw,
                                                             TransactionId tid) {
        return Futures.errored(new IllegalStateException("Unimplemented call!"));
    }

    default CompletableFuture<Multihash> put(PublicKeyHash owner,
                                             SigningPrivateKeyAndPublicHash writer,
                                             byte[] block,
                                             Hasher hasher,
                                             TransactionId tid) {
        return hasher.sha256(block)
                .thenCompose(hash -> put(owner, writer.publicKeyHash, writer.secret.signMessage(hash), block, tid));
    }

    default CompletableFuture<Multihash> put(PublicKeyHash owner,
                                             PublicKeyHash writer,
                                             byte[] signature,
                                             byte[] block,
                                             TransactionId tid) {
        return put(owner, writer, Collections.singletonList(signature), Collections.singletonList(block), tid)
                .thenApply(hashes -> hashes.get(0));
    }

    default CompletableFuture<Multihash> putRaw(PublicKeyHash owner,
                                                PublicKeyHash writer,
                                                byte[] signature,
                                                byte[] block,
                                                TransactionId tid,
                                                ProgressConsumer<Long> progressConsumer) {
        return putRaw(owner, writer, Collections.singletonList(signature), Collections.singletonList(block), tid, progressConsumer)
                .thenApply(hashes -> hashes.get(0));
    }

    default CompletableFuture<Boolean> flush() {
        return Futures.of(true);
    }

    /**
     *
     * @return The identity (hash of the public key) of the storage node we are talking to
     */
    CompletableFuture<Multihash> id();

    /**
     *
     * @param owner
     * @return A new transaction id that can be used to group writes together and protect them from being garbage
     * collected before they have been pinned.
     */
    CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner);

    /**
     * Release all associated objects from this transaction to allow them to be garbage collected if they haven't been
     * pinned.
     * @param owner
     * @param tid
     * @return
     */
    CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid);

    /**
     *
     * @param owner The owner of these blocks of data
     * @param writer The public signing key authorizing these writes, which must be owned by the owner key
     * @param signedHashes The signatures of the sha256 of each block being written (by the writer)
     * @param blocks The blocks to write
     * @param tid The transaction to group these writes under
     * @return
     */
    CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                           PublicKeyHash writer,
                                           List<byte[]> signedHashes,
                                           List<byte[]> blocks,
                                           TransactionId tid);

    /**
     *
     * @param hash
     * @return The data with the requested hash, deserialized into cbor, or Optional.empty() if no object can be found
     */
    CompletableFuture<Optional<CborObject>> get(Multihash hash);

    /**
     * Write a block of data that is just raw bytes, not ipld structured cbor
     * @param owner
     * @param writer
     * @param signedHashes
     * @param blocks
     * @param tid
     * @param progressCounter
     * @return
     */
    CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                              PublicKeyHash writer,
                                              List<byte[]> signedHashes,
                                              List<byte[]> blocks,
                                              TransactionId tid,
                                              ProgressConsumer<Long> progressCounter);

    /**
     * Get a block of data that is not in ipld cbor format, just raw bytes
     * @param hash
     * @return
     */
    CompletableFuture<Optional<byte[]>> getRaw(Multihash hash);

    /**
     * Update an existing pin with a new root. This is useful when modifying a tree of ipld objects where only a small
     * number of components are changed
     * @param owner The owner of the data
     * @param existing The present root hash
     * @param updated The new root hash
     * @return
     */
    CompletableFuture<List<Multihash>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated);

    /**
     * Recursively pin all the objects referenced via ipld merkle links from a root object
     * @param owner The owner of the data
     * @param hash The root hash of the merkle-tree
     * @return A list of the multihashes pinned
     */
    CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash hash);

    /**
     * Recursively unpin a merkle tree of objects. This releases the objects to be collected by garbage collection
     * @param owner The owner of the data
     * @param hash The root hash of the merkle-tree
     * @return
     */
    CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash hash);

    CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Multihash root, byte[] champKey);

    default CompletableFuture<List<byte[]>> getChampLookup(Multihash root, byte[] champKey, Hasher hasher) {
        CachingStorage cache = new CachingStorage(this, 100, 100 * 1024);
        return ChampWrapper.create(root, x -> Futures.of(x.data), cache, hasher, c -> (CborObject.CborMerkleLink) c)
                .thenCompose(tree -> tree.get(champKey))
                .thenApply(c -> c.map(x -> x.target).map(MaybeMultihash::of).orElse(MaybeMultihash.empty()))
                .thenApply(btreeValue -> {
                    if (btreeValue.isPresent())
                        return cache.get(btreeValue.get());
                    return Optional.empty();
                }).thenApply(x -> new ArrayList<>(cache.getCached()));
    }

    /** Run a garbage collection on the ipfs block store. This is only callable internally to a Peergos server.
     *
     * @return true
     */
    CompletableFuture<Boolean> gc();

    /**
     * Get all the merkle-links referenced directly from this object
     * @param root The hash of the object whose links we want
     * @return A list of the multihashes referenced with ipld links in this object
     */
    default CompletableFuture<List<Multihash>> getLinks(Multihash root) {
        if (root instanceof Cid && ((Cid) root).codec == Cid.Codec.Raw)
            return CompletableFuture.completedFuture(Collections.emptyList());
        return get(root).thenApply(opt -> opt
                .map(cbor -> cbor.links())
                .orElse(Collections.emptyList())
        );
    }

    /**
     * Get the size in bytes of the object with the requested hash
     * @param block The hash of the object
     * @return The size in bytes, or Optional.empty() if it cannot be found.
     */
    CompletableFuture<Optional<Integer>> getSize(Multihash block);

    default CompletableFuture<Cid> hashToCid(byte[] input, boolean isRaw, Hasher hasher) {
        return hasher.sha256(input)
                .thenApply(hash -> buildCid(hash, isRaw));
    }

    default Cid buildCid(byte[] sha256, boolean isRaw) {
        return new Cid(Cid.V1, isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, Multihash.Type.sha2_256, sha256);
    }

    default CompletableFuture<List<FragmentWithHash>> downloadFragments(List<Multihash> hashes,
                                                                        ProgressConsumer<Long> monitor,
                                                                        double spaceIncreaseFactor) {
        return NetworkAccess.downloadFragments(hashes, this, monitor, spaceIncreaseFactor);
    }

    default CompletableFuture<PublicKeyHash> putSigningKey(byte[] signature,
                                                           PublicKeyHash owner,
                                                           PublicSigningKey newKey,
                                                           TransactionId tid) {
        return putSigningKey(signature, owner, owner, newKey, tid);
    }

    default CompletableFuture<PublicKeyHash> putSigningKey(byte[] signature,
                                                           PublicKeyHash owner,
                                                           PublicKeyHash writer,
                                                           PublicSigningKey newKey,
                                                           TransactionId tid) {
        return CompletableFuture.completedFuture(hashKey(newKey));
    }

    static PublicKeyHash hashKey(PublicSigningKey key) {
        return new PublicKeyHash(new Cid(1, Cid.Codec.DagCbor, Multihash.Type.id, key.serialize()));
    }

    default CompletableFuture<PublicKeyHash> putBoxingKey(PublicKeyHash controller,
                                                          byte[] signature,
                                                          PublicBoxingKey key,
                                                          TransactionId tid) {
        byte[] rawKey = key.toCbor().toByteArray();
        if (rawKey.length <= Multihash.MAX_IDENTITY_HASH_SIZE)
            return Futures.of(new PublicKeyHash(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, rawKey)));
        return put(controller, controller, signature, rawKey, tid)
                .thenApply(PublicKeyHash::new);
    }

    default CompletableFuture<Optional<PublicSigningKey>> getSigningKey(PublicKeyHash hash) {
        return (hash.isIdentity() ?
                CompletableFuture.completedFuture(Optional.of(CborObject.fromByteArray(hash.getHash()))) :
                get(hash.multihash))
                .thenApply(opt -> Optional.ofNullable(opt).orElse(Optional.empty()).map(PublicSigningKey::fromCbor));
    }

    default CompletableFuture<Optional<PublicBoxingKey>> getBoxingKey(PublicKeyHash hash) {
        return (hash.isIdentity() ?
                CompletableFuture.completedFuture(Optional.of(CborObject.fromByteArray(hash.getHash()))) :
                get(hash.multihash))
                .thenApply(opt -> Optional.ofNullable(opt).orElse(Optional.empty()).map(PublicBoxingKey::fromCbor));
    }

    default CompletableFuture<Long> getRecursiveBlockSize(Multihash block) {
        return getLinks(block).thenCompose(links -> {
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

    default CompletableFuture<Long> getChangeInContainedSize(MaybeMultihash original, Multihash updated) {
        if (! original.isPresent())
            return getRecursiveBlockSize(updated);
        return getChangeInContainedSize(original.get(), updated);
    }

    default CompletableFuture<Long> getChangeInContainedSize(Multihash original, Multihash updated) {
        return getLinksAndSize(original)
                .thenCompose(before -> getLinksAndSize(updated).thenCompose(after -> {
                    int objectDelta = after.left - before.left;
                    List<Multihash> onlyBefore = new ArrayList<>(before.right);
                    onlyBefore.removeAll(after.right);
                    List<Multihash> onlyAfter = new ArrayList<>(after.right);
                    onlyAfter.removeAll(before.right);

                    int nPairs = Math.min(onlyBefore.size(), onlyAfter.size());
                    List<Pair<Multihash, Multihash>> pairs = IntStream.range(0, nPairs)
                            .mapToObj(i -> new Pair<>(onlyBefore.get(i), onlyAfter.get(i)))
                            .collect(Collectors.toList());

                    List<Multihash> extraBefore = onlyBefore.subList(nPairs, onlyBefore.size());
                    List<Multihash> extraAfter = onlyAfter.subList(nPairs, onlyAfter.size());
                    Function<List<Multihash>, CompletableFuture<Long>> getAllRecursiveSizes =
                            extra -> Futures.reduceAll(extra,
                                    0L,
                                    (s, h) -> getRecursiveBlockSize(h).thenApply(size -> size + s),
                                    (a, b) -> a + b);

                    Function<List<Pair<Multihash, Multihash>>, CompletableFuture<Long>> getSizeDiff =
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

    default CompletableFuture<Pair<Integer, List<Multihash>>> getLinksAndSize(Multihash block) {
        return getLinks(block)
                .thenCompose(links -> getSize(block).thenApply(size -> new Pair<>(size.orElse(0), links)));
    }

    class HTTP implements ContentAddressedStorage {

        private final HttpPoster poster;
        public static final String apiPrefix = "api/v0/";
        public static final String ID = "id";
        public static final String BLOCKSTORE_PROPERTIES = "blockstore/props";
        public static final String AUTH_READS = "blockstore/auth-reads";
        public static final String AUTH_WRITES = "blockstore/auth";
        public static final String TRANSACTION_START = "transaction/start";
        public static final String TRANSACTION_CLOSE = "transaction/close";
        public static final String CHAMP_GET = "champ/get";
        public static final String GC = "repo/gc";
        public static final String BLOCK_PUT = "block/put";
        public static final String BLOCK_GET = "block/get";
        public static final String BLOCK_RM = "block/rm";
        public static final String BLOCK_STAT = "block/stat";
        public static final String PIN_ADD = "pin/add";
        public static final String PIN_RM = "pin/rm";
        public static final String PIN_UPDATE = "pin/update";
        public static final String REFS = "refs";
        public static final String REFS_LOCAL = "refs/local";

        private final boolean isPeergosServer;
        private final Hasher hasher;
        private final Random r = new Random();

        public HTTP(HttpPoster poster, boolean isPeergosServer, Hasher hasher) {
            this.poster = poster;
            this.isPeergosServer = isPeergosServer;
            this.hasher = hasher;
        }

        @Override
        public ContentAddressedStorage directToOrigin() {
            return this;
        }

        private static Multihash getObjectHash(Object rawJson) {
            Map json = (Map)rawJson;
            String hash = (String)json.get("Hash");
            if (hash == null) {
                Object val = json.get("Key");
                if (val instanceof  String)
                    hash = (String) val;
                else if (val instanceof Map)
                    hash = (String) ((Map)val).get("/");
                else
                    throw new IllegalStateException("Couldn't parse hash from response!");
            }
            return Cid.decode(hash);
        }

        private static String encode(String component) {
            try {
                return URLEncoder.encode(component, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public CompletableFuture<Multihash> id() {
            return poster.get(apiPrefix + ID)
                    .thenApply(raw -> Cid.decodePeerId((String)((Map)JSONParser.parse(new String(raw))).get("ID")));
        }

        @Override
        public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
            if (! isPeergosServer)
                return Futures.of(BlockStoreProperties.empty());
            return poster.get(apiPrefix + BLOCKSTORE_PROPERTIES)
                    .thenApply(raw -> BlockStoreProperties.fromCbor(CborObject.fromByteArray(raw)));
        }

        @Override
        public CompletableFuture<List<PresignedUrl>> authReads(List<Multihash> blocks) {
            if (! isPeergosServer)
                return Futures.errored(new IllegalStateException("Cannot auth reads when not talking to a Peergos server!"));
            return poster.get(apiPrefix + AUTH_READS
                    + "?hashes=" + blocks.stream().map(x -> x.toString()).collect(Collectors.joining(",")))
                    .thenApply(raw -> ((CborObject.CborList)CborObject.fromByteArray(raw)).value
                            .stream()
                            .map(PresignedUrl::fromCbor)
                            .collect(Collectors.toList()));
        }

        @Override
        public CompletableFuture<List<PresignedUrl>> authWrites(PublicKeyHash owner,
                                                                PublicKeyHash writer,
                                                                List<byte[]> signedHashes,
                                                                List<Integer> blockSizes,
                                                                boolean isRaw,
                                                                TransactionId tid) {
            if (! isPeergosServer)
                return Futures.errored(new IllegalStateException("Cannot auth writes when not talking to a Peergos server!"));
            List<Long> sizes = blockSizes.stream()
                    .map(Integer::longValue)
                    .collect(Collectors.toList());
            WriteAuthRequest req = new WriteAuthRequest(signedHashes, sizes);
            return poster.postUnzip(apiPrefix + AUTH_WRITES + "?owner=" + encode(owner.toString())
                    + "&writer=" + encode(writer.toString())
                    + "&transaction=" + encode(tid.toString())
                    + "&raw=" + isRaw, req.serialize())
                    .thenApply(raw -> ((CborObject.CborList)CborObject.fromByteArray(raw)).value
                            .stream()
                            .map(PresignedUrl::fromCbor)
                            .collect(Collectors.toList()));
        }

        @Override
        public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
            if (! isPeergosServer)
                return CompletableFuture.completedFuture(new TransactionId(Long.toString(r.nextInt(Integer.MAX_VALUE))));
            return poster.get(apiPrefix + TRANSACTION_START + "?owner=" + encode(owner.toString()))
                    .thenApply(raw -> new TransactionId(new String(raw)));
        }

        @Override
        public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
            if (! isPeergosServer)
                return CompletableFuture.completedFuture(true);
            return poster.get(apiPrefix + TRANSACTION_CLOSE + "?arg=" + tid.toString() + "&owner=" + encode(owner.toString()))
                    .thenApply(raw -> new String(raw).equals("1"));
        }

        @Override
        public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Multihash root, byte[] champKey) {
            if (! isPeergosServer) {
                return getChampLookup(root, champKey, hasher);
            }
            return poster.get(apiPrefix + CHAMP_GET + "?arg=" + root.toString() + "&arg=" + ArrayOps.bytesToHex(champKey) + "&owner=" + encode(owner.toString()))
                    .thenApply(CborObject::fromByteArray)
                    .thenApply(c -> (CborObject.CborList)c)
                    .thenApply(res -> res.map(c -> ((CborObject.CborByteArray)c).value));
        }

        @Override
        public CompletableFuture<Boolean> gc() {
            return poster.get(apiPrefix + GC)
                    .thenApply(raw -> {
                        if (DEBUG_GC) {
                            List<Multihash> removed = JSONParser.parseStream(new String(raw))
                                    .stream()
                                    .map(json -> getObjectHash(json))
                                    .collect(Collectors.toList());
                            System.out.println("GCed:\n" + removed);
                        }
                        return true;
                    });
        }

        @Override
        public CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                      PublicKeyHash writer,
                                                      List<byte[]> signedHashes,
                                                      List<byte[]> blocks,
                                                      TransactionId tid) {
            return bulkPut(owner, writer, signedHashes, blocks, "cbor", tid, x -> {});
        }

        @Override
        public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                         PublicKeyHash writer,
                                                         List<byte[]> signatures,
                                                         List<byte[]> blocks,
                                                         TransactionId tid,
                                                         ProgressConsumer<Long> progressConsumer) {
            return bulkPut(owner, writer, signatures, blocks, "raw", tid, progressConsumer)
                    .thenApply(hashes -> {
                        if (DEBUG_GC)
                            System.out.println("Added blocks: " + hashes);
                        return hashes;
                    });
        }

        private CompletableFuture<List<Multihash>> bulkPut(PublicKeyHash owner,
                                                           PublicKeyHash writer,
                                                           List<byte[]> signatures,
                                                           List<byte[]> blocks,
                                                           String format,
                                                           TransactionId tid,
                                                           ProgressConsumer<Long> progressConsumer) {
            // Do 8 fragments per query to spread the 40 fragments in a chunk over the 5 connections in a browser
            // Unless we are talking to IPFS directly, then upload one per query because IPFS doesn't support more than one
            int FRAGMENTs_PER_QUERY = isPeergosServer ? 1 : 1;
            List<List<byte[]>> grouped = ArrayOps.group(blocks, FRAGMENTs_PER_QUERY);
            List<List<byte[]>> groupedSignatures = ArrayOps.group(signatures, FRAGMENTs_PER_QUERY);
            List<Integer> sizes = grouped.stream()
                    .map(frags -> frags.stream().mapToInt(f -> f.length).sum())
                    .collect(Collectors.toList());
            List<CompletableFuture<List<Multihash>>> futures = IntStream.range(0, grouped.size())
                    .parallel()
                    .mapToObj(i -> put(
                            owner,
                            writer,
                            groupedSignatures.get(i),
                            grouped.get(i),
                            format,
                            tid
                    ).thenApply(hash -> {
                        if (progressConsumer != null)
                            progressConsumer.accept((long) sizes.get(i));
                        return hash;
                    })).collect(Collectors.toList());
            return Futures.combineAllInOrder(futures)
                    .thenApply(groups -> groups.stream()
                            .flatMap(g -> g.stream()).collect(Collectors.toList()));
        }

        private CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                       PublicKeyHash writer,
                                                       List<byte[]> signatures,
                                                       List<byte[]> blocks,
                                                       String format,
                                                       TransactionId tid) {
            for (byte[] block : blocks) {
                if (block.length > MAX_BLOCK_SIZE)
                    throw new IllegalStateException("Invalid block size: " + block.length
                            + ", blocks must be smaller than 1MiB!");
            }
            return poster.postMultipart(apiPrefix + BLOCK_PUT + "?format=" + format
                    + "&owner=" + encode(owner.toString())
                    + "&transaction=" + encode(tid.toString())
                    + "&writer=" + encode(writer.toString())
                    + "&signatures=" + signatures.stream().map(ArrayOps::bytesToHex).reduce("", (a, b) -> a + "," + b).substring(1), blocks)
                    .thenApply(bytes -> JSONParser.parseStream(new String(bytes))
                            .stream()
                            .map(json -> getObjectHash(json))
                            .collect(Collectors.toList()))
                    .thenApply(hashes -> {
                        if (DEBUG_GC)
                            System.out.println("Added blocks: " + hashes);
                        if (hashes.size() != blocks.size())
                            throw new IllegalStateException("Incorrect number of hashes returned from bulk write: " + hashes.size() + " != " + blocks.size());
                        return hashes;
                    });
        }

        @Override
        public CompletableFuture<Optional<CborObject>> get(Multihash hash) {
            if (hash.isIdentity())
                return CompletableFuture.completedFuture(Optional.of(CborObject.fromByteArray(hash.getHash())));
            return poster.get(apiPrefix + BLOCK_GET + "?stream-channels=true&arg=" + hash.toString())
                    .thenApply(raw -> raw.length == 0 ? Optional.empty() : Optional.of(CborObject.fromByteArray(raw)));
        }

        @Override
        public CompletableFuture<Optional<byte[]>> getRaw(Multihash hash) {
            if (hash.isIdentity())
                return CompletableFuture.completedFuture(Optional.of(hash.getHash()));
            return poster.get(apiPrefix + BLOCK_GET + "?stream-channels=true&arg=" + hash.toString())
                    .thenApply(raw -> raw.length == 0 ? Optional.empty() : Optional.of(raw));
        }

        @Override
        public CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash hash) {
            return poster.get(apiPrefix + PIN_ADD + "?stream-channels=true&arg=" + hash.toString()
                    + "&owner=" + encode(owner.toString())).thenApply(this::getPins);
        }

        @Override
        public CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash hash) {
            return poster.get(apiPrefix + PIN_RM + "?stream-channels=true&r=true&arg=" + hash.toString()
                    + "&owner=" + encode(owner.toString())).thenApply(this::getPins);
        }

        @Override
        public CompletableFuture<List<Multihash>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
            return poster.get(apiPrefix + PIN_UPDATE + "?stream-channels=true&arg=" + existing.toString()
                    + "&arg=" + updated + "&unpin=false"
                    + "&owner=" + encode(owner.toString())).thenApply(this::getPins);
        }

        private List<Multihash> getPins(byte[] raw) {
            Map res = (Map)JSONParser.parse(new String(raw));
            List<String> pins = (List<String>)res.get("Pins");
            return pins.stream().map(Cid::decode).collect(Collectors.toList());
        }

        @Override
        public CompletableFuture<List<Multihash>> getLinks(Multihash block) {
            return poster.get(apiPrefix + REFS + "?arg=" + block.toString())
                    .thenApply(raw -> JSONParser.parseStream(new String(raw))
                            .stream()
                            .map(obj -> (String) (((Map) obj).get("Ref")))
                            .map(Cid::decode)
                            .collect(Collectors.toList()));
        }

        @Override
        public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
            return poster.get(apiPrefix + BLOCK_STAT + "?stream-channels=true&arg=" + block.toString())
                    .thenApply(raw -> Optional.of((Integer)((Map)JSONParser.parse(new String(raw))).get("Size")));
        }
    }

    class Proxying implements ContentAddressedStorage {
        private final ContentAddressedStorage local;
        private final ContentAddressedStorageProxy p2p;
        private final Multihash ourNodeId;
        private final CoreNode core;

        public Proxying(ContentAddressedStorage local, ContentAddressedStorageProxy p2p, Multihash ourNodeId, CoreNode core) {
            this.local = local;
            this.p2p = p2p;
            this.ourNodeId = ourNodeId;
            this.core = core;
        }

        @Override
        public ContentAddressedStorage directToOrigin() {
            return new Proxying(local.directToOrigin(), p2p, ourNodeId, core);
        }

        @Override
        public CompletableFuture<Multihash> id() {
            return local.id();
        }

        @Override
        public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
            return local.blockStoreProperties();
        }

        @Override
        public CompletableFuture<List<PresignedUrl>> authReads(List<Multihash> blocks) {
            return local.authReads(blocks);
        }

        @Override
        public CompletableFuture<List<PresignedUrl>> authWrites(PublicKeyHash owner,
                                                                PublicKeyHash writer,
                                                                List<byte[]> signedHashes,
                                                                List<Integer> blockSizes,
                                                                boolean isRaw,
                                                                TransactionId tid) {
            return local.authWrites(owner, writer, signedHashes, blockSizes, isRaw, tid);
        }

        @Override
        public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
            return Proxy.redirectCall(core,
                    ourNodeId,
                    owner,
                    () -> local.startTransaction(owner),
                    target -> p2p.startTransaction(target, owner));
        }

        @Override
        public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
            return Proxy.redirectCall(core,
                    ourNodeId,
                    owner,
                    () -> local.closeTransaction(owner, tid),
                    target -> p2p.closeTransaction(target, owner, tid));
        }

        @Override
        public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Multihash root, byte[] champKey) {
            return Proxy.redirectCall(core,
                    ourNodeId,
                    owner,
                    () -> local.getChampLookup(owner, root, champKey),
                    target -> p2p.getChampLookup(target, owner, root, champKey));
        }

        @Override
        public CompletableFuture<Boolean> gc() {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Optional<CborObject>> get(Multihash object) {
            return local.get(object);
        }

        @Override
        public CompletableFuture<Optional<byte[]>> getRaw(Multihash object) {
            return local.getRaw(object);
        }

        @Override
        public CompletableFuture<List<Multihash>> getLinks(Multihash root) {
            return local.getLinks(root);
        }

        @Override
        public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
            return local.getSize(block);
        }

        @Override
        public CompletableFuture<List<Multihash>> put(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signedHashes, List<byte[]> blocks, TransactionId tid) {
            return Proxy.redirectCall(core,
                    ourNodeId,
                    owner,
                    () -> local.put(owner, writer, signedHashes, blocks, tid),
                    target -> p2p.put(target, owner, writer, signedHashes, blocks, tid));
        }

        @Override
        public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                         PublicKeyHash writer,
                                                         List<byte[]> signatures,
                                                         List<byte[]> blocks,
                                                         TransactionId tid,
                                                         ProgressConsumer<Long> progressConsumer) {
            return Proxy.redirectCall(core,
                    ourNodeId,
                    owner,
                    () -> local.putRaw(owner, writer, signatures, blocks, tid, progressConsumer),
                    target -> p2p.putRaw(target, owner, writer, signatures, blocks, tid, progressConsumer));
        }

        @Override
        public CompletableFuture<List<Multihash>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
            return Proxy.redirectCall(core,
                    ourNodeId,
                    owner,
                    () -> local.pinUpdate(owner, existing, updated),
                    target -> p2p.pinUpdate(target, owner,  existing, updated));
        }

        @Override
        public CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash h) {
            return Proxy.redirectCall(core,
                    ourNodeId,
                    owner,
                    () -> local.recursivePin(owner, h),
                    target -> p2p.recursivePin(target, owner,  h));
        }

        @Override
        public CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash h) {
            return Proxy.redirectCall(core,
                    ourNodeId,
                    owner,
                    () -> local.recursiveUnpin(owner, h),
                    target -> p2p.recursiveUnpin(target, owner,  h));
        }
    }
}
