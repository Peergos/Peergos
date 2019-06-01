package peergos.shared.storage;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multiaddr.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public interface ContentAddressedStorage {

    int MAX_BLOCK_SIZE  = 2*1024*1024;

    default CompletableFuture<Multihash> put(PublicKeyHash owner,
                                             SigningPrivateKeyAndPublicHash writer,
                                             byte[] block,
                                             TransactionId tid) {
        return put(owner, writer.publicKeyHash, writer.secret.signatureOnly(block), block, tid);
    }

    default CompletableFuture<Multihash> put(PublicKeyHash owner,
                                             PublicKeyHash writer,
                                             byte[] signature,
                                             byte[] block,
                                             TransactionId tid) {
        return put(owner, writer, Arrays.asList(signature), Arrays.asList(block), tid)
                .thenApply(hashes -> hashes.get(0));
    }

    default CompletableFuture<Multihash> putRaw(PublicKeyHash owner,
                                                PublicKeyHash writer,
                                                byte[] signature,
                                                byte[] block,
                                                TransactionId tid) {
        return putRaw(owner, writer, Arrays.asList(signature), Arrays.asList(block), tid)
                .thenApply(hashes -> hashes.get(0));
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
     * @param signatures The signatures of each block being written (by the writer)
     * @param blocks The blocks to write
     * @param tid The transaction to group these writes under
     * @return
     */
    CompletableFuture<List<Multihash>> put(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks, TransactionId tid);

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
     * @param signatures
     * @param blocks
     * @param tid
     * @return
     */
    CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks, TransactionId tid);

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

    /**
     * Get all the merkle-links referenced directly from this object
     * @param root The hash of the object whose links we want
     * @return A list of the multihashes referenced with ipld links in this object
     */
    CompletableFuture<List<Multihash>> getLinks(Multihash root);

    /**
     * Get the size in bytes of the object with the requested hash
     * @param block The hash of the object
     * @return The size in bytes, or Optional.empty() if it cannot be found.
     */
    CompletableFuture<Optional<Integer>> getSize(Multihash block);

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
        return put(controller, controller, signature, key.toCbor().toByteArray(), tid)
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
        private static final String apiPrefix = "api/v0/";
        public static final String ID = "id";
        public static final String TRANSACTION_START = "transaction/start";
        public static final String TRANSACTION_CLOSE = "transaction/close";
        public static final String BLOCK_PUT = "block/put";
        public static final String BLOCK_GET = "block/get";
        public static final String BLOCK_STAT = "block/stat";
        public static final String PIN_ADD = "pin/add";
        public static final String PIN_RM = "pin/rm";
        public static final String PIN_UPDATE = "pin/update";
        public static final String REFS = "refs";

        private final boolean isPeergosServer;
        private final Random r = new Random();

        public HTTP(HttpPoster poster, boolean isPeergosServer) {
            this.poster = poster;
            this.isPeergosServer = isPeergosServer;
        }

        private static Multihash getObjectHash(Object rawJson) {
            Map json = (Map)rawJson;
            String hash = (String)json.get("Hash");
            if (hash == null)
                hash = (String)json.get("Key");
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
                    .thenApply(raw -> Multihash.fromBase58((String)((Map)JSONParser.parse(new String(raw))).get("ID")));
        }

        @Override
        public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
            if (! isPeergosServer) // TODO remove once IPFS implements the transaction api
                return CompletableFuture.completedFuture(new TransactionId(Long.toString(r.nextInt(Integer.MAX_VALUE))));
            return poster.get(apiPrefix + TRANSACTION_START + "?owner=" + encode(owner.toString()))
                    .thenApply(raw -> new TransactionId(new String(raw)));
        }

        @Override
        public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
            if (! isPeergosServer) // TODO remove once IPFS implements the transaction api
                return CompletableFuture.completedFuture(true);
            return poster.get(apiPrefix + TRANSACTION_CLOSE + "?arg=" + tid.toString() + "&owner=" + encode(owner.toString()))
                    .thenApply(raw -> new String(raw).equals("1"));
        }

        @Override
        public CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                      PublicKeyHash writer,
                                                      List<byte[]> signatures,
                                                      List<byte[]> blocks,
                                                      TransactionId tid) {
            return put(owner, writer, signatures, blocks, "cbor", tid);
        }

        @Override
        public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                         PublicKeyHash writer,
                                                         List<byte[]> signatures,
                                                         List<byte[]> blocks,
                                                         TransactionId tid) {
            return put(owner, writer, signatures, blocks, "raw", tid);
        }

        private CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                       PublicKeyHash writer,
                                                       List<byte[]> signatures,
                                                       List<byte[]> blocks, String format,
                                                       TransactionId tid) {
            for (byte[] block : blocks) {
                if (block.length > MAX_BLOCK_SIZE)
                    throw new IllegalStateException("Invalid block size: " + block.length
                            + ", blocks must be smaller than 2MiB!");
            }
            return poster.postMultipart(apiPrefix + BLOCK_PUT + "?format=" + format
                    + "&owner=" + encode(owner.toString())
                    + "&transaction=" + encode(tid.toString())
                    + "&writer=" + encode(writer.toString())
                    + "&signatures=" + signatures.stream().map(ArrayOps::bytesToHex).reduce("", (a, b) -> a + "," + b).substring(1), blocks)
                    .thenApply(bytes -> JSONParser.parseStream(new String(bytes))
                            .stream()
                            .map(json -> getObjectHash(json))
                            .collect(Collectors.toList()));
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
            if (hash.type == Multihash.Type.id)
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
        public CompletableFuture<Multihash> id() {
            return local.id();
        }

        @Override
        public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
            return redirectCall(owner,
                    () -> local.startTransaction(owner),
                    target -> p2p.startTransaction(target, owner));
        }

        @Override
        public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
            return redirectCall(owner,
                    () -> local.closeTransaction(owner, tid),
                    target -> p2p.closeTransaction(target, owner, tid));
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
        public CompletableFuture<List<Multihash>> put(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks, TransactionId tid) {
            return redirectCall(owner,
                () -> local.put(owner, writer, signatures, blocks, tid),
                target -> p2p.put(target, owner, writer, signatures, blocks, tid));
        }

        @Override
        public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks, TransactionId tid) {
            return redirectCall(owner,
                    () -> local.putRaw(owner, writer, signatures, blocks, tid),
                    target -> p2p.putRaw(target, owner, writer, signatures, blocks, tid));
        }

        @Override
        public CompletableFuture<List<Multihash>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
            return redirectCall(owner,
                    () -> local.pinUpdate(owner, existing, updated),
                    target -> p2p.pinUpdate(target, owner,  existing, updated));
        }

        @Override
        public CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash h) {
            return redirectCall(owner,
                    () -> local.recursivePin(owner, h),
                    target -> p2p.recursivePin(target, owner,  h));
        }

        @Override
        public CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash h) {
            return redirectCall(owner,
                    () -> local.recursiveUnpin(owner, h),
                    target -> p2p.recursiveUnpin(target, owner,  h));
        }

        public <V> CompletableFuture<V> redirectCall(PublicKeyHash ownerKey, Supplier<CompletableFuture<V>> direct, Function<Multihash, CompletableFuture<V>> proxied) {
        return core.getUsername(ownerKey)
                .thenCompose(owner -> core.getChain(owner)
                        .thenCompose(chain -> {
                            if (chain.isEmpty()) {
                                // This happens during sign-up, before we have a chain yet
                                return direct.get();
                            }
                            List<Multihash> storageIds = chain.get(chain.size() - 1).claim.storageProviders;
                            Multihash target = storageIds.get(0);
                            if (target.equals(ourNodeId)) { // don't proxy
                                return direct.get();
                            } else {
                                return proxied.apply(target);
                            }
                        }));

    }
    }
}
