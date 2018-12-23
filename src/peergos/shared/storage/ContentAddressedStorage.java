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

    int MAX_OBJECT_LENGTH  = 1024*256;

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

    CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner);

    CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid);

    CompletableFuture<List<Multihash>> put(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks, TransactionId tid);

    CompletableFuture<Optional<CborObject>> get(Multihash object);

    CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks, TransactionId tid);

    CompletableFuture<Optional<byte[]>> getRaw(Multihash object);

    CompletableFuture<List<MultiAddress>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated);

    CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash h);

    CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash h);

    CompletableFuture<List<Multihash>> getLinks(Multihash root);

    CompletableFuture<Optional<Integer>> getSize(Multihash block);

    default CompletableFuture<PublicKeyHash> putSigningKey(byte[] signature,
                                                           PublicKeyHash authKeyHash,
                                                           PublicSigningKey newKey,
                                                           TransactionId tid) {
        return put(authKeyHash, authKeyHash, signature, newKey.toCbor().toByteArray(), tid)
                .thenApply(PublicKeyHash::new);
    }

    static PublicKeyHash hashKey(PublicSigningKey key) {
        return new PublicKeyHash(new Cid(1, Cid.Codec.DagCbor, new Multihash(
                            Multihash.Type.sha2_256,
                            Hash.sha256(key.serialize()))));
    }

    default CompletableFuture<PublicKeyHash> putBoxingKey(PublicKeyHash controller,
                                                          byte[] signature,
                                                          PublicBoxingKey key,
                                                          TransactionId tid) {
        return put(controller, controller, signature, key.toCbor().toByteArray(), tid)
                .thenApply(PublicKeyHash::new);
    }

    default CompletableFuture<Optional<PublicSigningKey>> getSigningKey(PublicKeyHash hash) {
        return get(hash.multihash)
                .thenApply(opt -> Optional.ofNullable(opt).orElse(Optional.empty()).map(PublicSigningKey::fromCbor));
    }

    default CompletableFuture<Optional<PublicBoxingKey>> getBoxingKey(PublicKeyHash hash) {
        return get(hash.multihash)
                .thenApply(opt -> Optional.ofNullable(opt).orElse(Optional.empty()).map(PublicBoxingKey::fromCbor));
    }

    default CompletableFuture<Long> getRecursiveBlockSize(Multihash block) {
        return getLinks(block).thenCompose(links -> {
            List<CompletableFuture<Long>> subtrees = links.stream().map(this::getRecursiveBlockSize).collect(Collectors.toList());
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
        private final String apiPrefix = "api/v0/";

        public HTTP(HttpPoster poster) {
            this.poster = poster;
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
            return poster.get(apiPrefix + "id")
                    .thenApply(raw -> Multihash.fromBase58((String)((Map)JSONParser.parse(new String(raw))).get("ID")));
        }

        @Override
        public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
            return poster.get(apiPrefix + "transaction/start" + "?owner=" + encode(owner.toString()))
                    .thenApply(raw -> new TransactionId(new String(raw)));
        }

        @Override
        public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
            return poster.get(apiPrefix + "transaction/close?arg=" + tid.toString() + "&owner=" + encode(owner.toString()))
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
            return poster.postMultipart(apiPrefix + "block/put?format=" + format
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
            return poster.get(apiPrefix + "block/get?stream-channels=true&arg=" + hash.toString())
                    .thenApply(raw -> raw.length == 0 ? Optional.empty() : Optional.of(CborObject.fromByteArray(raw)));
        }

        @Override
        public CompletableFuture<Optional<byte[]>> getRaw(Multihash hash) {
            return poster.get(apiPrefix + "block/get?stream-channels=true&arg=" + hash.toString())
                    .thenApply(raw -> raw.length == 0 ? Optional.empty() : Optional.of(raw));
        }

        @Override
        public CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash hash) {
            return poster.get(apiPrefix + "pin/add?stream-channels=true&arg=" + hash.toString()
                    + "&owner=" + encode(owner.toString())).thenApply(this::getPins);
        }

        @Override
        public CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash hash) {
            return poster.get(apiPrefix + "pin/rm?stream-channels=true&r=true&arg=" + hash.toString()
                    + "&owner=" + encode(owner.toString())).thenApply(this::getPins);
        }

        @Override
        public CompletableFuture<List<MultiAddress>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
            return poster.get(apiPrefix + "pin/update?stream-channels=true&arg=" + existing.toString()
                    + "&arg=" + updated + "&unpin=false"
                    + "&owner=" + encode(owner.toString())).thenApply(this::getMultiAddr);
        }

        private List<Multihash> getPins(byte[] raw) {
            Map res = (Map)JSONParser.parse(new String(raw));
            List<String> pins = (List<String>)res.get("Pins");
            return pins.stream().map(Cid::decode).collect(Collectors.toList());
        }

        private List<MultiAddress> getMultiAddr(byte[] raw) {
            Map res = (Map)JSONParser.parse(new String(raw));
            List<String> pins = (List<String>)res.get("Pins");
            return pins.stream().map(MultiAddress::new).collect(Collectors.toList());
        }

        @Override
        public CompletableFuture<List<Multihash>> getLinks(Multihash block) {
            return poster.get(apiPrefix + "refs?arg=" + block.toString())
                    .thenApply(raw -> JSONParser.parseStream(new String(raw))
                            .stream()
                            .map(obj -> (String) (((Map) obj).get("Ref")))
                            .map(Cid::decode)
                            .collect(Collectors.toList()));
        }

        @Override
        public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
            return poster.get(apiPrefix + "block/stat?stream-channels=true&arg=" + block.toString())
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
        public CompletableFuture<List<MultiAddress>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
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
