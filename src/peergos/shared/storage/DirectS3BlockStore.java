package peergos.shared.storage;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multibase.binary.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class DirectS3BlockStore implements ContentAddressedStorage {

    private final boolean directWrites, publicReads, authedReads;
    private final Optional<String> basePublicReadUrl;
    private final Optional<String> baseAuthedUrl;
    private final HttpPoster direct;
    private final ContentAddressedStorage fallback;
    private final Multihash nodeId;
    private final LRUCache<PublicKeyHash, Multihash> storageNodeByOwner = new LRUCache<>(100);
    private final CoreNode core;
    private final Hasher hasher;

    public DirectS3BlockStore(BlockStoreProperties blockStoreProperties,
                              HttpPoster direct,
                              ContentAddressedStorage fallback,
                              Multihash nodeId,
                              CoreNode core,
                              Hasher hasher) {
        this.directWrites = blockStoreProperties.directWrites;
        this.publicReads = blockStoreProperties.publicReads;
        this.authedReads = blockStoreProperties.authedReads;
        this.basePublicReadUrl = blockStoreProperties.basePublicReadUrl;
        this.baseAuthedUrl = blockStoreProperties.baseAuthedUrl;
        this.direct = direct;
        this.fallback = fallback;
        this.nodeId = nodeId;
        this.core = core;
        this.hasher = hasher;
    }

    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return Futures.of(new BlockStoreProperties(directWrites, publicReads, authedReads, basePublicReadUrl, baseAuthedUrl));
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return fallback;
    }

    public static String hashToKey(Multihash hash) {
        // To be compatible with IPFS we use the same scheme here, the cid bytes encoded as uppercase base32
        String padded = new Base32().encodeAsString(hash.toBytes());
        int padStart = padded.indexOf("=");
        return padStart > 0 ? padded.substring(0, padStart) : padded;
    }

    public static Multihash keyToHash(String keyFileName) {
        // To be compatible with IPFS we use the same scheme here, the cid bytes encoded as uppercase base32
        byte[] decoded = new Base32().decode(keyFileName);
        return Cid.cast(decoded);
    }

    @Override
    public CompletableFuture<Multihash> id() {
        return fallback.id();
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        return fallback.startTransaction(owner);
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        return fallback.closeTransaction(owner, tid);
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                  PublicKeyHash writer,
                                                  List<byte[]> signedHashes,
                                                  List<byte[]> blocks,
                                                  TransactionId tid) {
        return onOwnersNode(owner).thenCompose(ownersNode -> {
            if (ownersNode && directWrites) {
                CompletableFuture<List<Multihash>> res = new CompletableFuture<>();
                fallback.authWrites(owner, writer, signedHashes, blocks.stream().map(x -> x.length).collect(Collectors.toList()), false, tid)
                        .thenCompose(preAuthed -> {
                            List<CompletableFuture<Multihash>> futures = new ArrayList<>();
                            for (int i = 0; i < blocks.size(); i++) {
                                PresignedUrl url = preAuthed.get(i);
                                Multihash targetName = keyToHash(url.base.substring(url.base.lastIndexOf("/") + 1));
                                futures.add(direct.put(url.base, blocks.get(i), url.fields)
                                        .thenApply(x -> targetName));
                            }
                            return Futures.combineAllInOrder(futures);
                        }).thenApply(res::complete)
                        .exceptionally(res::completeExceptionally);
                return res;
            }
            return fallback.put(owner, writer, signedHashes, blocks, tid);
        });
    }

    private CompletableFuture<Boolean> onOwnersNode(PublicKeyHash owner) {
        Multihash cached = storageNodeByOwner.get(owner);
        if (cached != null)
            return Futures.of(cached.equals(nodeId));
        return core.getUsername(owner)
                .thenCompose(user -> core.getChain(user)
                        .thenApply(chain -> {
                            if (chain.isEmpty())
                                throw new IllegalStateException("Empty chain returned for " + user);
                            List<Multihash> storageProviders = chain.get(chain.size() - 1).claim.storageProviders;
                            Multihash mainNode = storageProviders.get(0);
                            storageNodeByOwner.put(owner, mainNode);
                            System.out.println("Are we on owner's node? " + mainNode + " == " + nodeId);
                            return mainNode.equals(nodeId);
                        }));
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     List<byte[]> signatures,
                                                     List<byte[]> blocks,
                                                     TransactionId tid,
                                                     ProgressConsumer<Long> progressCounter) {
        return onOwnersNode(owner).thenCompose(ownersNode -> {
            if (ownersNode && directWrites) {
                // we have a trade-off here. The first block upload cannot start until the auth call returns.
                // So we only auth 6 at once, to max out the 6 connections per host in a browser
                int FRAGMENTS_PER_AUTH_QUERY = 6;
                List<List<byte[]>> grouped = ArrayOps.group(blocks, FRAGMENTS_PER_AUTH_QUERY);
                List<List<byte[]>> groupedSignatures = ArrayOps.group(signatures, FRAGMENTS_PER_AUTH_QUERY);
                List<CompletableFuture<List<Multihash>>> futures = IntStream.range(0, grouped.size())
                        .parallel()
                        .mapToObj(i -> bulkPutRaw(
                                owner,
                                writer,
                                groupedSignatures.get(i),
                                grouped.get(i),
                                tid,
                                progressCounter
                        )).collect(Collectors.toList());
                return Futures.combineAllInOrder(futures)
                        .thenApply(groups -> groups.stream()
                                .flatMap(g -> g.stream()).collect(Collectors.toList()));
            }
            return fallback.putRaw(owner, writer, signatures, blocks, tid, progressCounter);
        });
    }

    private CompletableFuture<List<Multihash>> bulkPutRaw(PublicKeyHash owner,
                                                          PublicKeyHash writer,
                                                          List<byte[]> signatures,
                                                          List<byte[]> blocks,
                                                          TransactionId tid,
                                                          ProgressConsumer<Long> progressCounter) {
        CompletableFuture<List<Multihash>> res = new CompletableFuture<>();
        fallback.authWrites(owner, writer, signatures, blocks.stream().map(x -> x.length).collect(Collectors.toList()), true, tid)
                .thenCompose(preAuthed -> {
                    List<CompletableFuture<Multihash>> futures = new ArrayList<>();
                    for (int i = 0; i < blocks.size(); i++) {
                        PresignedUrl url = preAuthed.get(i);
                        Multihash targetName = keyToHash(url.base.substring(url.base.lastIndexOf("/") + 1));
                        Long size = (long) blocks.get(i).length;
                        futures.add(direct.put(url.base, blocks.get(i), url.fields)
                                .thenApply(x -> {
                                    progressCounter.accept(size);
                                    return targetName;
                                }));
                    }
                    return Futures.combineAllInOrder(futures);
                }).thenApply(res::complete)
                .exceptionally(res::completeExceptionally);
        return res;
    }

    @Override
    public CompletableFuture<List<FragmentWithHash>> downloadFragments(List<Multihash> hashes,
                                                                       ProgressConsumer<Long> monitor,
                                                                       double spaceIncreaseFactor) {
        if (publicReads || ! authedReads)
            return NetworkAccess.downloadFragments(hashes, this, monitor, spaceIncreaseFactor);

        // Do a bulk auth in a single call
        List<Pair<Integer, Multihash>> indexAndHash = IntStream.range(0, hashes.size())
                .mapToObj(i -> new Pair<>(i, hashes.get(i)))
                .collect(Collectors.toList());
        List<Pair<Integer, Multihash>> nonIdentity = indexAndHash.stream()
                .filter(p -> ! p.right.isIdentity())
                .collect(Collectors.toList());
        CompletableFuture<List<PresignedUrl>> auths = nonIdentity.isEmpty() ?
                Futures.of(Collections.emptyList()) :
                fallback.authReads(nonIdentity.stream().map(p -> p.right).collect(Collectors.toList()));
        CompletableFuture<List<FragmentWithHash>> allResults = new CompletableFuture();
        auths
                .thenCompose(preAuthedGets ->
                        Futures.combineAllInOrder(IntStream.range(0, preAuthedGets.size())
                                .parallel()
                                .mapToObj(i -> direct.get(preAuthedGets.get(i).base, preAuthedGets.get(i).fields)
                                        .thenApply(b -> {
                                            monitor.accept((long)b.length);
                                            Pair<Integer, Multihash> hashAndIndex = nonIdentity.get(i);
                                            return new Pair<>(hashAndIndex.left,
                                                    new FragmentWithHash(new Fragment(b), Optional.of(hashAndIndex.right)));
                                        }))
                                .collect(Collectors.toList())))
                .thenApply(retrieved -> {
                    FragmentWithHash[] res = new FragmentWithHash[hashes.size()];
                    for (Pair<Integer, FragmentWithHash> p : retrieved) {
                        res[p.left] = p.right;
                    }
                    // This section is only relevant for legacy data that uses identity multihashes to inline fragments
                    for (int i=0; i < hashes.size(); i++)
                        if (res[i] == null) {
                            Multihash identity = hashes.get(i);
                            if (! identity.isIdentity())
                                throw new IllegalStateException("Hash should be identity!");
                            res[i] = new FragmentWithHash(new Fragment(identity.getHash()), Optional.empty());
                        }
                    return Arrays.asList(res);
                }).thenAccept(allResults::complete)
                .exceptionally(t -> {
                    NetworkAccess.downloadFragments(hashes, this, monitor, spaceIncreaseFactor)
                            .thenAccept(allResults::complete)
                            .exceptionally(e -> {
                                allResults.completeExceptionally(e);
                                return null;
                            });
                    return null;
                });
        return allResults;
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash hash) {
        return getRaw(hash).thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash hash) {
        if (hash.isIdentity())
                return CompletableFuture.completedFuture(Optional.of(hash.getHash()));
        if (publicReads) {
            CompletableFuture<Optional<byte[]>> res = new CompletableFuture<>();
            direct.get(basePublicReadUrl.get() + hashToKey(hash))
                    .thenApply(Optional::of)
                    .thenAccept(res::complete)
                    .exceptionally(t -> {
                        fallback.authReads(Arrays.asList(hash))
                                .thenCompose(preAuthedGet -> direct.get(preAuthedGet.get(0).base))
                                .thenApply(Optional::of)
                                .thenAccept(res::complete)
                                .exceptionally(e -> {
                                    fallback.getRaw(hash)
                                            .thenAccept(res::complete)
                                            .exceptionally(f -> {
                                                res.completeExceptionally(f);
                                                return null;
                                            });
                                    return null;
                                });
                        return null;
                    });
            return res;
        }
        if (authedReads) {
            CompletableFuture<Optional<byte[]>> res = new CompletableFuture<>();
            fallback.authReads(Arrays.asList(hash))
                    .thenCompose(preAuthedGet -> direct.get(preAuthedGet.get(0).base, preAuthedGet.get(0).fields))
                    .thenApply(Optional::of)
                    .thenAccept(res::complete)
                    .exceptionally(t -> {
                        fallback.getRaw(hash)
                                .thenAccept(res::complete)
                                .exceptionally(e -> {
                                    res.completeExceptionally(e);
                                    return null;
                                });
                        return null;
                    });
            return res;
        }
        return fallback.getRaw(hash);
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        return fallback.getSize(block);
    }

    @Override
    public CompletableFuture<List<Multihash>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
        return Futures.of(Collections.singletonList(updated));
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash hash) {
        return Futures.of(Collections.singletonList(hash));
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash hash) {
        return Futures.of(Collections.singletonList(hash));
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Multihash root, byte[] champKey) {
        return Futures.asyncExceptionally(
                () -> fallback.getChampLookup(owner, root, champKey),
                t -> {
                    if (!(t instanceof RateLimitException))
                        return Futures.errored(t);
                    return getChampLookup(root, champKey, hasher);
                });
    }

    @Override
    public CompletableFuture<Boolean> gc() {
        return Futures.errored(new IllegalStateException("S3 doesn't implement GC!"));
    }
}
