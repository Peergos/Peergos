package peergos.shared.storage;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.io.ipfs.bases.Base32;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.util.stream.*;

public class DirectS3BlockStore implements ContentAddressedStorage {

    public static final int MIN_SMALL_BLOCK_SIZE = 100 * 1024;

    private final boolean directWrites, publicReads, authedReads;
    private final Optional<String> basePublicReadUrl;
    private final Optional<String> baseAuthedUrl;
    private final HttpPoster direct;
    private final ContentAddressedStorage fallback;
    private final List<Cid> nodeIds;
    private final LRUCache<PublicKeyHash, Multihash> storageNodeByOwner = new LRUCache<>(100);
    private final CoreNode core;
    private final Hasher hasher;

    public DirectS3BlockStore(BlockStoreProperties blockStoreProperties,
                              HttpPoster direct,
                              ContentAddressedStorage fallback,
                              List<Cid> nodeIds,
                              CoreNode core,
                              Hasher hasher) {
        this.directWrites = blockStoreProperties.directWrites;
        this.publicReads = blockStoreProperties.publicReads;
        this.authedReads = blockStoreProperties.authedReads;
        this.basePublicReadUrl = blockStoreProperties.basePublicReadUrl;
        this.baseAuthedUrl = blockStoreProperties.baseAuthedUrl;
        this.direct = direct;
        this.fallback = fallback;
        this.nodeIds = nodeIds;
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

    public static Cid keyToHash(String keyFileName) {
        // To be compatible with IPFS we use the same scheme here, the cid bytes encoded as uppercase base32
        byte[] decoded = new Base32().decode(keyFileName);
        return Cid.cast(decoded);
    }

    @Override
    public CompletableFuture<Cid> id() {
        return Futures.of(nodeIds.get(nodeIds.size() - 1));
    }

    @Override
    public CompletableFuture<List<Cid>> ids() {
        return Futures.of(nodeIds);
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
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                            PublicKeyHash writer,
                                            List<byte[]> signedHashes,
                                            List<byte[]> blocks,
                                            TransactionId tid) {
        return fallback.put(owner, writer, signedHashes, blocks, tid);
    }

    private CompletableFuture<Boolean> onOwnersNode(PublicKeyHash owner) {
        Multihash cached = storageNodeByOwner.get(owner);
        if (cached != null)
            return Futures.of(nodeIds.stream().map(Cid::bareMultihash).anyMatch(p -> p.equals(cached)));
        return core.getUsername(owner)
                .thenCompose(user -> core.getChain(user)
                        .thenApply(chain -> {
                            if (chain.isEmpty())
                                throw new IllegalStateException("Empty chain returned for " + user);
                            List<Multihash> storageProviders = chain.get(chain.size() - 1).claim.storageProviders;
                            Multihash mainNode = storageProviders.get(0);
                            storageNodeByOwner.put(owner, mainNode.bareMultihash());
                            Logger.getGlobal().info("Are we on owner's node? " + mainNode + " in " + nodeIds);
                            return nodeIds.stream().map(Cid::bareMultihash).anyMatch(p -> p.equals(mainNode.bareMultihash()));
                        }));
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signatures,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressCounter) {
        //  raw blocks smaller than 100 KiB are written directly to server rather than S3 (if S3 blockstore)
        // otherwise we suffer disproportionally from latency to S3 from the client
        if (blocks.stream().allMatch(b -> b.length < MIN_SMALL_BLOCK_SIZE))
            return fallback.putRaw(owner, writer, signatures, blocks, tid, progressCounter);
        return onOwnersNode(owner).thenCompose(ownersNode -> {
            if (ownersNode && directWrites) {
                // we have a trade-off here. The first block upload cannot start until the auth call returns.
                // So we only auth 6 at once, to max out the 6 connections per host in a browser
                int FRAGMENTS_PER_AUTH_QUERY = 6;
                List<List<byte[]>> grouped = ArrayOps.group(blocks, FRAGMENTS_PER_AUTH_QUERY);
                List<List<byte[]>> groupedSignatures = ArrayOps.group(signatures, FRAGMENTS_PER_AUTH_QUERY);
                List<CompletableFuture<List<Cid>>> futures = IntStream.range(0, grouped.size())
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

    private CompletableFuture<List<Cid>> bulkPutRaw(PublicKeyHash owner,
                                                    PublicKeyHash writer,
                                                    List<byte[]> signatures,
                                                    List<byte[]> blocks,
                                                    TransactionId tid,
                                                    ProgressConsumer<Long> progressCounter) {
        CompletableFuture<List<Cid>> res = new CompletableFuture<>();
        List<Integer> sizes = blocks.stream().map(x -> x.length).collect(Collectors.toList());
        List<List<BatId>> batIds = blocks.stream().map(Bat::getRawBlockBats).collect(Collectors.toList());
        fallback.authWrites(owner, writer, signatures, sizes, batIds, true, tid)
                .thenCompose(preAuthed -> {
                    List<CompletableFuture<Cid>> futures = new ArrayList<>();
                    for (int i = 0; i < blocks.size(); i++) {
                        PresignedUrl url = preAuthed.get(i);
                        Cid targetName = keyToHash(url.base.substring(url.base.lastIndexOf("/") + 1));
                        Long size = (long) blocks.get(i).length;
                        int finalI = i;
                        futures.add(RetryStorage.runWithRetry(3, () -> direct.put(url.base, blocks.get(finalI), url.fields))
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
    public CompletableFuture<List<FragmentWithHash>> downloadFragments(PublicKeyHash owner,
                                                                       List<Cid> hashes,
                                                                       List<BatWithId> bats,
                                                                       Hasher h,
                                                                       ProgressConsumer<Long> monitor,
                                                                       double spaceIncreaseFactor) {
        if (publicReads || ! authedReads)
            return NetworkAccess.downloadFragments(owner, hashes, bats, fallback, h, monitor, spaceIncreaseFactor);

        return onOwnersNode(owner).thenCompose(onOwners -> {
            if (! onOwners)
                return NetworkAccess.downloadFragments(owner, hashes, bats, fallback, h, monitor, spaceIncreaseFactor);

            // Do a bulk auth in a single call
            List<Pair<Integer, Cid>> indexAndHash = IntStream.range(0, hashes.size())
                    .mapToObj(i -> new Pair<>(i, hashes.get(i)))
                    .collect(Collectors.toList());
            List<Pair<Integer, Cid>> nonIdentity = indexAndHash.stream()
                    .filter(p -> !p.right.isIdentity())
                    .collect(Collectors.toList());
            CompletableFuture<List<PresignedUrl>> auths = nonIdentity.isEmpty() ?
                    Futures.of(Collections.emptyList()) :
                    fallback.authReads(nonIdentity.stream()
                            .map(p -> new MirrorCap(p.right,
                                    bats.size() > p.left ?
                                            Optional.of(bats.get(p.left)) :
                                            Optional.empty()))
                            .collect(Collectors.toList()));
            CompletableFuture<List<FragmentWithHash>> allResults = new CompletableFuture();
            auths
                    .thenCompose(preAuthedGets ->
                            Futures.combineAllInOrder(IntStream.range(0, preAuthedGets.size())
                                    .parallel()
                                    .mapToObj(i -> direct.get(preAuthedGets.get(i).base, preAuthedGets.get(i).fields)
                                            .thenApply(b -> {
                                                monitor.accept((long) b.length);
                                                Pair<Integer, Cid> hashAndIndex = nonIdentity.get(i);
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
                        for (int i = 0; i < hashes.size(); i++)
                            if (res[i] == null) {
                                Multihash identity = hashes.get(i);
                                if (!identity.isIdentity())
                                    throw new IllegalStateException("Hash should be identity!");
                                res[i] = new FragmentWithHash(new Fragment(identity.getHash()), Optional.empty());
                            }
                        return Arrays.asList(res);
                    }).thenAccept(allResults::complete)
                    .exceptionally(t -> {
                        NetworkAccess.downloadFragments(owner, hashes, bats, this, h, monitor, spaceIncreaseFactor)
                                .thenAccept(allResults::complete)
                                .exceptionally(e -> {
                                    allResults.completeExceptionally(e);
                                    return null;
                                });
                        return null;
                    });
            return allResults;
        });
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return getRaw(owner, hash, bat).thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        if (hash.isIdentity())
                return CompletableFuture.completedFuture(Optional.of(hash.getHash()));
        if (publicReads) {
            CompletableFuture<Optional<byte[]>> res = new CompletableFuture<>();
            direct.get(basePublicReadUrl.get() + hashToKey(hash))
                    .thenApply(Optional::of)
                    .thenAccept(res::complete)
                    .exceptionally(t -> {
                        fallback.authReads(Arrays.asList(new MirrorCap(hash, bat)))
                                .thenCompose(preAuthedGet -> direct.get(preAuthedGet.get(0).base))
                                .thenApply(Optional::of)
                                .thenAccept(res::complete)
                                .exceptionally(e -> {
                                    fallback.getRaw(owner, hash, bat)
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
        if (authedReads && hash.isRaw()) {
            CompletableFuture<Optional<byte[]>> res = new CompletableFuture<>();
            fallback.authReads(Arrays.asList(new MirrorCap(hash, bat)))
                    .thenCompose(preAuthedGet -> direct.get(preAuthedGet.get(0).base, preAuthedGet.get(0).fields))
                    .thenApply(Optional::of)
                    .thenAccept(res::complete)
                    .exceptionally(t -> {
                        fallback.getRaw(owner, hash, bat)
                                .thenAccept(res::complete)
                                .exceptionally(e -> {
                                    res.completeExceptionally(e);
                                    return null;
                                });
                        return null;
                    });
            return res;
        }
        return fallback.getRaw(owner, hash, bat);
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        return fallback.getSize(block);
    }

    @Override
    public CompletableFuture<IpnsEntry> getIpnsEntry(Multihash signer) {
        return fallback.getIpnsEntry(signer);
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat, Optional<Cid> committedRoot) {
        return Futures.asyncExceptionally(
                () -> fallback.getChampLookup(owner, root, champKey, bat, committedRoot),
                t -> {
                    if (!(t instanceof RateLimitException))
                        return Futures.errored(t);
                    return getChampLookup(owner, root, champKey, bat, committedRoot, hasher);
                });
    }

    @Override
    public CompletableFuture<EncryptedCapability> getSecretLink(SecretLink link) {
        return fallback.getSecretLink(link);
    }

    @Override
    public CompletableFuture<LinkCounts> getLinkCounts(String owner, LocalDateTime after, BatWithId mirrorBat) {
        return fallback.getLinkCounts(owner, after, mirrorBat);
    }
}
