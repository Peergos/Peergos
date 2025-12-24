package peergos.server.storage;

import io.prometheus.client.Histogram;
import io.prometheus.client.Counter;
import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.space.*;
import peergos.server.sql.*;
import peergos.server.storage.auth.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.io.ipfs.bases.Base64;
import peergos.shared.mutable.PointerUpdate;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.*;

public class S3BlockStorage implements DeletableContentAddressedStorage {

    private static final Logger LOG = Logger.getGlobal();
    private static final List<String> RETRY_S3_CODES = List.of("RequestError","RequestTimeout","Throttling"
            ,"ThrottlingException","RequestLimitExceeded","RequestThrottled","InternalError","ExpiredToken","ExpiredTokenException","SlowDown");
    
    private static final Histogram CborReadTimerLog = Histogram.build()
            .labelNames("filesize")
            .name("cbor_block_read_seconds")
            .help("Time to read a cbor block from immutable storage")
            .exponentialBuckets(0.01, 2, 16)
            .register();
    private static final Histogram RawReadTimerLog = Histogram.build()
            .labelNames("filesize")
            .name("raw_block_read_seconds")
            .help("Time to read a raw block from immutable storage")
            .exponentialBuckets(0.01, 2, 16)
            .register();
    private static final Histogram HeadTimerLog = Histogram.build()
            .labelNames("filesize")
            .name("block_head_seconds")
            .help("Time to get a blocks size from immutable storage")
            .exponentialBuckets(0.01, 2, 16)
            .register();
    private static final Histogram writeTimerLog = Histogram.build()
            .labelNames("filesize")
            .name("s3_block_write_seconds")
            .help("Time to write a block to immutable storage")
            .exponentialBuckets(0.01, 2, 16)
            .register();
    private static final Counter blockHeads = Counter.build()
            .name("s3_block_heads")
            .help("Number of block heads to S3")
            .register();
    private static final Counter blockSize = Counter.build()
            .name("s3_block_size_heads")
            .help("Number of block size head requests to S3")
            .register();
    private static final Counter blockGets = Counter.build()
            .name("s3_block_gets")
            .help("Number of block gets to S3")
            .register();
    private static final Counter blockGetAuths = Counter.build()
            .name("s3_block_get_auths")
            .help("Number of authed block gets to S3")
            .register();
    private static final Counter failedBlockGets = Counter.build()
            .name("s3_block_get_failures")
            .help("Number of failed block gets to S3")
            .register();
    private static final Counter blockPuts = Counter.build()
            .name("s3_block_puts")
            .help("Number of block puts to S3")
            .register();
    private static final Counter blockPutAuths = Counter.build()
            .name("s3_block_put_auths")
            .help("Number of authed block puts to S3")
            .register();
    private static final Histogram blockPutBytes = Histogram.build()
            .labelNames("size")
            .name("s3_block_put_bytes")
            .help("Number of bytes written to S3")
            .exponentialBuckets(0.01, 2, 16)
            .register();
    private static final Counter nonLocalGets = Counter.build()
            .name("p2p_block_gets")
            .help("Number of block gets which fell back to p2p retrieval")
            .register();

    private static final Counter getRateLimited = Counter.build()
            .name("s3_get_rate_limited")
            .help("Number of times we get a http 429 rate limit response during a block get")
            .register();

    private static final Counter rateLimited = Counter.build()
            .name("s3_rate_limited")
            .help("Number of times we get a http 429 rate limit response")
            .register();

    private final Cid id;
    private final List<Cid> ids;
    private final List<Multihash> peerIds;
    private final String region, bucket, folder, host;
    private final boolean useHttps;
    private final String accessKeyId, secretKey;
    private final Optional<String> storageClass;
    private final boolean noReads;
    private final BlockStoreProperties props;
    private final String linkHost;
    private final TransactionStore transactions;
    private final BlockRequestAuthoriser authoriser;
    private final BlockMetadataStore blockMetadata;
    private final UsageStore usage;
    private final BlockCache cborCache;
    private final BlockBuffer blockBuffer;
    private final Hasher hasher;
    private final DeletableContentAddressedStorage ipnsHandler;
    private final ContentAddressedStorageProxy p2pHttpFallback;

    private final LinkedBlockingQueue<Pair<PublicKeyHash, Cid>> blocksToFlush = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<PublicKeyHash, SlidingWindowCounter> userReadReqRateLimits = new ConcurrentHashMap();
    private final ConcurrentHashMap<PublicKeyHash, SlidingWindowCounter> userReadSizeRateLimits = new ConcurrentHashMap();
    private final SlidingWindowCounter globalReadReqCount;
    private final SlidingWindowCounter globalReadBandwidth;
    private final long maxUserBandwidthPerMinute, maxUserReadRequestsPerMinute;
    private final PartitionStatus partitionStatus;
    private final boolean partitionComplete;
    private final JdbcBatCave bats;
    private CoreNode pki;

    public S3BlockStorage(S3Config config,
                          List<Cid> ids,
                          BlockStoreProperties props,
                          String linkHost,
                          TransactionStore transactions,
                          BlockRequestAuthoriser authoriser,
                          JdbcBatCave bats,
                          BlockMetadataStore blockMetadata,
                          UsageStore usage,
                          BlockCache cborCache,
                          BlockBuffer blockBuffer,
                          long maxReadBandwidthPerSecond,
                          long maxReadReqsPerSecond,
                          long maxUserBandwidthPerSecond,
                          long maxUserReadRequestsPerSecond,
                          PartitionStatus partitioned,
                          Hasher hasher,
                          DeletableContentAddressedStorage ipnsHandler,
                          ContentAddressedStorageProxy p2pHttpFallback) {
        this.ids = ids;
        this.peerIds = ids.stream()
                .map(Cid::bareMultihash)
                .collect(Collectors.toList());
        this.id = ids.get(ids.size() - 1);
        this.region = config.region;
        this.bucket = config.bucket;
        this.host = config.getHost();
        this.useHttps = ! host.endsWith("localhost") && ! host.contains("localhost:");
        this.folder = (useHttps ? "" : bucket + "/") + (config.path.isEmpty() || config.path.endsWith("/") ? config.path : config.path + "/");
        this.storageClass = config.storageClass;
        this.noReads = storageClass.isPresent() && storageClass.get().equals("GLACIER");
        this.accessKeyId = config.accessKey;
        this.secretKey = config.secretKey;
        LOG.info("Using S3 Block Storage at " + config.regionEndpoint + ", bucket " + config.bucket
                + ", path: " + config.path + ", peerids: "+peerIds);
        this.props = props;
        this.linkHost = linkHost;
        this.transactions = transactions;
        this.authoriser = authoriser;
        this.bats = bats;
        this.blockMetadata = blockMetadata;
        this.usage = usage;
        this.cborCache = cborCache;
        this.blockBuffer = blockBuffer;
        this.hasher = hasher;
        this.ipnsHandler = ipnsHandler;
        this.p2pHttpFallback = p2pHttpFallback;
        globalReadReqCount = new SlidingWindowCounter(60*60, 60*60 * maxReadReqsPerSecond);
        globalReadBandwidth = new SlidingWindowCounter(60*60, 60*60 * maxReadBandwidthPerSecond);
        this.maxUserBandwidthPerMinute = 60 * maxUserBandwidthPerSecond;
        this.maxUserReadRequestsPerMinute = 60 * maxUserReadRequestsPerSecond;
        this.partitionStatus = partitioned;
        this.partitionComplete = partitionStatus.isDone();
        startFlusherThread();
        new Thread(() -> blockBuffer.applyToAll((o, c) -> {
            bulkPutPool.submit(() -> getWithBackoff(() -> {
                Optional<byte[]> block = blockBuffer.get(o, c).join();
                if (block.isPresent()) {
                    getWithBackoff(() -> put(o, c, block.get(), true));
                    Optional<BlockMetadata> meta = blockMetadata.get(c);
                    if (meta.isPresent())
                        blockBuffer.delete(o, c);
                }
                return true;
            }));
        })).start();
    }

    private boolean userPartitioningComplete() {
        return partitionStatus.isDone();
    }

    @Override
    public void partitionByUser(UsageStore usage,
                                JdbcIpnsAndSocial mutable,
                                PublicKeyHash pkiKey) {
        partitionByUser(mutable, pkiKey);
    }

    public void partitionByUser(JdbcIpnsAndSocial mutable,
                                PublicKeyHash pkiKey) {
        if (userPartitioningComplete())
            return;
        new Thread(() -> {
            List<Triple<Multihash, String, PublicKeyHash>> allTargets = usage.getAllTargets();
            // randomise list so multiple servers can help without clashing too much
            Collections.shuffle(allTargets);
            for (Triple<Multihash, String, PublicKeyHash> target : allTargets) {
                moveSubtreeToOwner(target.right, (Cid) target.left, List.of(id));
            }
            Map<PublicKeyHash, byte[]> allPointers = mutable.getAllEntries();
            PublicKeyHash pkiOwner = pki.getPublicKeyHash("peergos").join().get();

            allPointers.forEach((writerHash, rawPointer) -> {
                PublicKeyHash owner = writerHash.equals(pkiKey) ? pkiOwner : usage.getOwnerKey(writerHash);
                PublicSigningKey writer = getSigningKey(null, writerHash).join().get();
                byte[] bothHashes = writer.unsignMessage(rawPointer).join();
                PointerUpdate cas = PointerUpdate.fromCbor(CborObject.fromByteArray(bothHashes));
                MaybeMultihash updated = cas.updated;

                if (updated.isPresent())
                    moveSubtreeToOwner(owner, (Cid) updated.get(), List.of(id));
            });
            partitionStatus.complete();
        }).start();
    }

    private void moveSubtreeToOwner(PublicKeyHash owner, Cid root, List<Multihash> ourIds) {
        moveLegacyBlockToOwner(owner, root);
        List<Cid> links = getLinks(owner, root, ourIds).join();
        for (Cid link : links) {
            moveSubtreeToOwner(owner, link, ourIds);
        }
    }

    private void moveLegacyBlockToOwner(PublicKeyHash owner, Cid block) {
        if (block.isIdentity())
            return;
        // use new owner metadata column to determine if block has been moved already
        Optional<PublicKeyHash> metaOwner = blockMetadata.getOwner(block);
        if (metaOwner.isEmpty()) {
            // Unfortunately we have to copy, then delete, then update metadata
            copyObject(legacyHashToKey(block), hashToKey(owner, block), hasher);
            delete(null, block);
            blockMetadata.setOwner(owner, block);
        }
    }

    private void copyObject(String sourceKey,
                                   String destKey,
                                   Hasher h) {
        PresignedUrl copyUrl = S3Request.preSignCopy(bucket, sourceKey, destKey, S3AdminRequests.asAwsDate(ZonedDateTime.now()), host,
                storageClass, Collections.emptyMap(), region, accessKeyId, secretKey, true, h).join();
        try {
            System.out.println("Copying s3://" + bucket + "/" + sourceKey + " to s3://" + bucket + "/" + destKey);
            String res = new String(HttpUtil.putWithVersion(copyUrl, new byte[0]).left);
            if (! res.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><CopyObjectResult") || !res.contains("</LastModified><ETag>"))
                throw new IllegalStateException(res);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setPki(CoreNode pki) {
        this.pki = pki;
        updateMetadataStoreIfEmpty();
    }

    private void startFlusherThread() {
        Thread flusher = new Thread(() -> {
            while (true) {
                try {
                    Pair<PublicKeyHash, Cid> p = blocksToFlush.peek();
                    if (p == null) {
                        Thread.sleep(1_000);
                        continue;
                    }
                    PublicKeyHash owner = p.left;
                    Cid h = p.right;
                    bulkPutPool.submit(() -> getWithBackoff(() -> {
                        try {
                            Optional<byte[]> block = blockBuffer.get(owner, h).join();
                            if (block.isPresent()) {
                                getWithBackoff(() -> put(owner, h, block.get(), true));
                                Optional<BlockMetadata> meta = blockMetadata.get(h);
                                if (meta.isPresent())
                                    blockBuffer.delete(owner, h);
                                else {
                                    LOG.info("Error flushing block " + h);
                                    blocksToFlush.add(new Pair<>(owner, h));
                                }
                            } else
                                blocksToFlush.add(new Pair<>(owner, h));
                            return true;
                        } catch (Exception e) {
                            LOG.info("Error flushing block " + h + " " + e.getMessage());
                            blocksToFlush.add(new Pair<>(owner, h));
                            throw new RuntimeException(e);
                        }
                    }));
                    blocksToFlush.poll();
                } catch (Exception e) {
                    LOG.log(Level.INFO, e.getMessage(), e);
                }
            }
        });
        flusher.setDaemon(true);
        flusher.start();
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    private static String legacyHashToKey(Multihash hash) {
        return DirectS3BlockStore.hashToKey(hash);
    }

    private String hashToKey(PublicKeyHash owner, Multihash hash) {
        if (owner == null)
            return legacyHashToKey(hash);
        return ownerToPrefix(owner) + DirectS3BlockStore.hashToKey(hash);
    }

    private Cid keyToHash(String key) {
        return DirectS3BlockStore.keyToHash(key.substring(folder.length()));
    }

    private Pair<PublicKeyHash, Cid> keyToOwnerAndHash(Optional<PublicKeyHash> owner, String key) {
        String path = key.substring(folder.length());
        if (path.contains("/")) {
            int slash = path.indexOf("/");
            Cid hash = DirectS3BlockStore.keyToHash(path.substring(slash + 1));
            if (owner.isPresent())
                return new Pair<>(owner.get(), hash);
            String username = path.substring(0, slash);
            PublicKeyHash parsedOwner = pki.getPublicKeyHash(username).join().get();
            return new Pair<>(parsedOwner, hash);
        }
        // legacy path without owner
        return new Pair<>(null, DirectS3BlockStore.keyToHash(path));
    }

    private String ownerToPrefix(PublicKeyHash owner) {
        // legacy data all starts with AFK or AFY, usernames start with a lowercase letter
        // We want to be able to efficiently list all legacy blocks
        // Achieve this by listing from B which is after A and before lowercase
        if (owner == null)
            return "";
        return pki.getUsername(owner).join() + "/";
    }

    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return Futures.of(props);
    }

    @Override
    public CompletableFuture<String> linkHost(PublicKeyHash owner) {
        return Futures.of(linkHost);
    }

    private void enforceGlobalRequestRateLimit() {
        if (! globalReadReqCount.allowRequest(1))
            throw new MajorRateLimitException("Rate Limit: Server S3 request limit exceeded. Please try again later");
    }

    private void enforceGlobalBandwidthLimit(long readSize) {
        if (! globalReadBandwidth.allowRequest(readSize))
            throw new MajorRateLimitException("Rate Limit: Server bandwidth limit exceeded. Please try again later");
    }

    private void enforceUserRequestRateLimits(PublicKeyHash owner, long readRequests) {
        if (owner == null) // GC until we move to user partitioned blockstores
            return;
        if (! userReadReqRateLimits.computeIfAbsent(owner, o -> new SlidingWindowCounter(60, maxUserReadRequestsPerMinute))
                .allowRequest(readRequests))
            throw new MajorRateLimitException("Rate Limit: User request limit exceeded. Please try again later.");
    }

    private void enforceUserBandwidthRateLimits(PublicKeyHash owner, long readSize) {
        if (owner == null) // GC until we move to user partitioned blockstores
            return;
        if (! userReadSizeRateLimits.computeIfAbsent(owner, o -> new SlidingWindowCounter(60, maxUserBandwidthPerMinute))
                .allowRequest(readSize))
            throw new MajorRateLimitException("Rate Limit: User bandwidth limit exceeded. Please try again later.");
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authReads(PublicKeyHash owner, List<BlockMirrorCap> blocks) {
        if (noReads)
            throw new IllegalStateException("Reads from Glacier are disabled!");
        if (blocks.size() > MAX_BLOCK_AUTHS)
            throw new IllegalStateException("Too many reads to auth!");
        List<PresignedUrl> res = new ArrayList<>();

        if (! blocks.stream().allMatch(c -> c.hash.isRaw()))
            return Futures.errored(new IllegalStateException("Can only auth read for raw blocks, not cbor!"));

        // verify all BATs in parallel
        List<CompletableFuture<BlockMetadata>> auths = blocks.stream()
                .parallel()
                .map(b -> getBlockMetadata(owner, b.hash)
                        .thenApply(meta -> {
                            String auth = b.bat.map(bat -> bat.bat.generateAuth(b.hash, id, 300, S3Request.currentDatetime(), bat.id, hasher)
                                    .thenApply(BlockAuth::encode).join()).orElse("");
                            if (!authoriser.allowRead(b.hash, meta.batids, id, auth).join())
                                throw new IllegalStateException("Unauthorised!");
                            return meta;
                        }))
                .collect(Collectors.toList());

        for (BlockMirrorCap block : blocks) {
            String s3Key = hashToKey(partitionComplete ?
                    owner :
                    blockMetadata.getOwner(block.hash).orElse(null), block.hash);
            res.add(S3Request.preSignGet(folder + s3Key, Optional.of(600), Optional.empty(), S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, storageClass, accessKeyId, secretKey, useHttps, hasher).join());
        }
        long byteCount = 0;
        long reqCount = 0;
        for (CompletableFuture<BlockMetadata> fut : auths) {
            BlockMetadata m = fut.join();// Any invalids BATs will cause this to throw
            byteCount += m.size;
            reqCount++;
        }
        Optional<BatId> mirrorBat = auths.stream()
                .flatMap(f -> f.join().batids.stream().filter(b -> !b.isInline()))
                .findFirst();
        if (mirrorBat.isPresent()) {
            String username = bats.getOwner(mirrorBat.get());
            PublicKeyHash verifiedOwner = pki.getPublicKeyHash(username).join().get();
            // check rate limits
            enforceGlobalRequestRateLimit();
            enforceGlobalBandwidthLimit(byteCount);
            enforceUserRequestRateLimits(verifiedOwner, reqCount);
            enforceUserBandwidthRateLimits(verifiedOwner, byteCount);
        }
        for (int i=0; i < blocks.size(); i++)
            blockGetAuths.inc();
        return Futures.of(res);
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authWrites(PublicKeyHash owner,
                                                            PublicKeyHash writerHash,
                                                            List<byte[]> signedHashes,
                                                            List<Integer> blockSizes,
                                                            List<List<BatId>> batIds,
                                                            boolean isRaw,
                                                            TransactionId tid) {
        try {
            if (signedHashes.size() > MAX_BLOCK_AUTHS)
                throw new IllegalStateException("Too many writes to auth!");
            if (blockSizes.size() != signedHashes.size())
                throw new IllegalStateException("Number of sizes doesn't match number of signed hashes!");
            if (blockSizes.size() != batIds.size())
                throw new IllegalStateException("Number of sizes doesn't match number of bats!");
            PublicSigningKey writer = getSigningKey(owner, writerHash).get().get();
            List<Pair<Cid, BlockMetadata>> blockProps = new ArrayList<>();
            for (int i=0; i < signedHashes.size(); i++) {
                Cid.Codec codec = isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor;
                if (! isRaw)
                    throw new IllegalStateException("Only raw blocks can be pre-authed for writes");
                Cid cid = new Cid(1, codec, Multihash.Type.sha2_256, writer.unsignMessage(signedHashes.get(i)).join());
                blockProps.add(new Pair<>(cid, new BlockMetadata(blockSizes.get(i), Collections.emptyList(), batIds.get(i))));
            }
            List<PresignedUrl> res = new ArrayList<>();
            for (Pair<Cid, BlockMetadata> props : blockProps) {
                if (props.left.type != Multihash.Type.sha2_256)
                    throw new IllegalStateException("Can only pre-auth writes of sha256 hashed blocks!");
                transactions.addBlock(props.left, tid, owner);
                String s3Key = hashToKey(owner, props.left);
                String contentSha256 = ArrayOps.bytesToHex(props.left.getHash());
                Map<String, String> extraHeaders = new LinkedHashMap<>();
                extraHeaders.put("Content-Type", "application/octet-stream");
                res.add(S3Request.preSignPut(folder + s3Key, props.right.size, contentSha256, storageClass, false,
                        S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, extraHeaders, region, accessKeyId, secretKey, useHttps, hasher).join());
                blockPutAuths.inc();
                if (isRaw)
                    blockMetadata.put(owner, props.left, null, props.right);
            }
            return Futures.of(res);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid object, Optional<BatWithId> bat) {
        return getRaw(pki.getStorageProviders(owner), owner, object, bat, id, hasher, true)
                .thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, String auth, boolean persistBlock) {
        if (hash.isRaw())
            throw new IllegalStateException("Need to call getRaw if cid is not cbor!");
        return getRaw(peerIds, owner, hash, auth, persistBlock).thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds,
                                                       PublicKeyHash owner,
                                                       Cid hash,
                                                       Optional<BatWithId> bat,
                                                       Cid ourId,
                                                       Hasher h,
                                                       boolean persistBlock) {
        return getRaw(peerIds, owner, hash, bat, ourId, h, persistBlock).thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid object, Optional<BatWithId> bat) {
        return getRaw(pki.getStorageProviders(owner), owner, object, bat, id, hasher, true);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h, boolean persistBlock) {
        return getRaw(peerIds, owner, hash, bat, ourId, h, true, persistBlock);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h, boolean doAuth, boolean persistBlock) {
        if (hash.isIdentity())
            return Futures.of(Optional.of(hash.getHash()));
        if (noReads) {
            if (peerIds.stream().anyMatch(p -> ids.stream().anyMatch(us -> us.bareMultihash().equals(p.bareMultihash()))))
                throw new IllegalStateException("Reads from Glacier are disabled!");
            return p2pHttpFallback.getRaw(peerIds.get(0), owner, hash, bat).thenApply(res -> {
                if (res.isPresent() && persistBlock) {
                    if (hash.isRaw())
                        putRaw(owner, owner, new byte[0], res.get(), new TransactionId(""), x -> {});
                    else
                        put(owner, owner, new byte[0], res.get(), new TransactionId(""));
                }
                return res;
            });
        }
        return getRaw(peerIds, owner, hash, Optional.empty(), doAuth, bat, persistBlock)
                .thenApply(p -> p.map(v -> v.left));
    }

    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, List<Multihash> peerIds, Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h, boolean persistBlock) {
        if (hash.isIdentity())
            return Futures.of(Optional.of(hash.getHash()));
        if (noReads) {
            if (peerIds.stream().anyMatch(p -> ids.stream().anyMatch(us -> us.bareMultihash().equals(p.bareMultihash()))))
                throw new IllegalStateException("Reads from Glacier are disabled!");
            return p2pHttpFallback.getRaw(peerIds.get(0), owner, hash, bat);
        }
        return getRaw(peerIds, owner, hash, Optional.empty(), true, bat, persistBlock)
                .thenApply(p -> p.map(v -> v.left));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds,
                                                      PublicKeyHash owner,
                                                      Cid hash,
                                                      String auth,
                                                      boolean doAuth,
                                                      boolean persistBlock) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, String auth, boolean persistBlock) {
        throw new IllegalStateException("Unimplemented!");
    }

    /** Get raw block data and version
     *
     * @param hash
     * @param range
     * @param enforceAuth
     * @param bat
     * @return
     */
    private CompletableFuture<Optional<Pair<byte[], String>>> getRaw(List<Multihash> peerIds,
                                                                     PublicKeyHash owner,
                                                                     Cid hash,
                                                                     Optional<Pair<Integer, Integer>> range,
                                                                     boolean enforceAuth,
                                                                     Optional<BatWithId> bat,
                                                                     boolean persistP2pBlock) {
        if (hash.isIdentity())
            return Futures.of(Optional.of(new Pair<>(hash.getHash(), null)));
        if (noReads)
            throw new IllegalStateException("Reads from Glacier are disabled!");
        if (! hash.isRaw()) {
            Optional<byte[]> cached = cborCache.get(hash).join();
            if (cached.isPresent()) {
                if (enforceAuth && ! authoriser.allowRead(hash, cached.get(), id, generateAuth(hash, bat, id, hasher)).join())
                    throw new IllegalStateException("Unauthorised!");
                return Futures.of(Optional.of(new Pair<>(cached.get(), null)));
            }
        }
        Optional<byte[]> buffered = blockBuffer.get(owner, hash).join();
        if (buffered.isPresent()) {
            if (enforceAuth && ! authoriser.allowRead(hash, buffered.get(), id, generateAuth(hash, bat, id, hasher)).join())
                    throw new IllegalStateException("Unauthorised!");
                return Futures.of(Optional.of(new Pair<>(buffered.get(), null)));
        }
        return getWithBackoff(() -> getRawWithoutBackoff(peerIds, owner, hash, range, enforceAuth, bat, persistP2pBlock, false))
                .thenApply(res -> {
                    if (hash.isRaw())
                        return res;
                    if (res.isPresent())
                        cborCache.put(hash, res.get().left);
                    return res;
                });
    }

    private CompletableFuture<Optional<Pair<byte[], String>>> getRawWithoutBackoff(List<Multihash> peerIds,
                                                                                   PublicKeyHash owner,
                                                                                   Cid hash,
                                                                                   Optional<Pair<Integer, Integer>> range,
                                                                                   boolean enforceAuth,
                                                                                   Optional<BatWithId> bat,
                                                                                   boolean persistP2pBlock,
                                                                                   boolean useLegacyPath) {
        enforceGlobalRequestRateLimit();
        enforceUserRequestRateLimits(owner, 1);

        String path = folder + (useLegacyPath ? legacyHashToKey(hash) : hashToKey(owner, hash));
        PresignedUrl getUrl = S3Request.preSignGet(path, Optional.of(600), range,
                S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, storageClass, accessKeyId, secretKey, useHttps, hasher).join();
        Histogram.Timer readTimer = hash.isRaw() ?
                RawReadTimerLog.labels("read").startTimer() :
                CborReadTimerLog.labels("read").startTimer();
        try {
            Pair<byte[], String> blockAndVersion = HttpUtil.getWithVersion(getUrl);
            blockGets.inc();
            enforceUserBandwidthRateLimits(owner, blockAndVersion.left.length);
            enforceGlobalBandwidthLimit(blockAndVersion.left.length);
            // validate auth, unless this is an internal query
            if (enforceAuth && ! authoriser.allowRead(hash, blockAndVersion.left, id, generateAuth(hash, bat, id, hasher)).join())
                throw new IllegalStateException("Unauthorised!");
            if (range.isEmpty())
                blockMetadata.put(owner, hash, blockAndVersion.right, blockAndVersion.left);
            return Futures.of(Optional.of(blockAndVersion));
        } catch (SocketTimeoutException | SSLException | SocketException e) {
            // S3 can't handle the load so treat this as a rate limit and slow down
            throw new RateLimitException();
        } catch (IOException e) {
            String msg = e.getMessage();
            boolean rateLimited = isRateLimitedException(e);
            if (rateLimited) {
                getRateLimited.inc();
                S3BlockStorage.rateLimited.inc();
                throw new RateLimitException();
            }
            boolean notFound = msg.contains("<Code>NoSuchKey</Code>");
            if (! notFound) {
                LOG.warning("S3 error reading " + path);
                LOG.log(Level.WARNING, msg, e);
                if (! useLegacyPath && ! userPartitioningComplete())
                    return getRawWithoutBackoff(peerIds, owner, hash, range, enforceAuth, bat, persistP2pBlock, true);
            }
            failedBlockGets.inc();

            if (peerIds.stream().map(Multihash::bareMultihash).anyMatch(this.peerIds::contains)) {
                // This is the owner's home server, we should have the block!
                throw new IllegalStateException("Missing block " + hash);
            }

            nonLocalGets.inc();
            return p2pHttpFallback.getRaw(peerIds.get(0), owner, hash, bat)
                    .thenApply(res -> {
                        if (res.isPresent() && persistP2pBlock) {
                            if (hash.isRaw())
                                putRaw(owner, owner, new byte[0], res.get(), new TransactionId(""), x -> {});
                            else
                                put(owner, owner, new byte[0], res.get(), new TransactionId(""));
                        }
                        return res;
                    })
                    .thenApply(dopt -> dopt.map(b -> new Pair<>(b, null)));
        } finally {
            readTimer.observeDuration();
        }
    }

    @Override
    public boolean hasBlock(PublicKeyHash owner, Cid hash) {
        if (blockBuffer.hasBlock(owner, hash))
            return true;
        if (! hash.isRaw() && cborCache.hasBlock(hash))
            return true;
        Optional<BlockMetadata> meta = blockMetadata.get(hash);
        if (meta.isPresent())
            return true;
        return getWithBackoff(() -> hasBlockWithoutBackoff(owner, hash));
    }

    public boolean hasBlockWithoutBackoff(PublicKeyHash owner, Cid hash) {
        try {
            PresignedUrl headUrl = S3Request.preSignHead(folder + hashToKey(owner, hash), Optional.of(60),
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, storageClass, accessKeyId, secretKey, useHttps, hasher).join();
            Map<String, List<String>> headRes = HttpUtil.head(headUrl);
            blockHeads.inc();
            return true;
        } catch (FileNotFoundException e) {
            return false;
        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg == null) {
                LOG.info("Error checking for " + hash + ": " + e);
                return false;
            }
            boolean rateLimited = isRateLimitedException(e);
            if (rateLimited) {
                S3BlockStorage.rateLimited.inc();
                throw new RateLimitException();
            }
            boolean notFound = msg.contains("<Code>NoSuchKey</Code>");
            if (! notFound) {
                LOG.warning("S3 error reading " + hash);
                LOG.log(Level.WARNING, msg, e);
            }
            return false;
        }
    }

    public static boolean isRateLimitedException(IOException e) {
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        int startIndex = msg.indexOf("<Code>");
        int endIndex = msg.indexOf("</Code>");
        if (startIndex >=0 && endIndex >=0 && startIndex < endIndex) {
            String code = msg.substring(startIndex + 6, endIndex).trim();
            return RETRY_S3_CODES.contains(code);
        } else {
            return e instanceof ConnectException;
        }
    }

    private BlockMetadata checkAndAddBlock(PublicKeyHash owner, Cid expected, byte[] raw) {
        Cid res = hasher.hash(raw, expected.isRaw()).join();
        if (! res.equals(expected))
            throw new IllegalStateException("Received block with incorrect hash!");
        return put(owner, expected, raw, false).right;
    }

    ForkJoinPool mirrorPool = Threads.newPool(10, "S3-Mirror-");

    private List<BlockMetadata> bulkGetBlocks(List<Multihash> peers,
                                              String username,
                                              PublicKeyHash owner,
                                              List<Cid> hashes,
                                              Optional<BatWithId> mirrorBat,
                                              AtomicLong retrievalCount,
                                              AtomicLong retrievalSize) {
        List<ForkJoinTask<Optional<BlockMetadata>>> futs = hashes.stream()
                .map(c -> mirrorPool.submit(() -> {
                    Optional<BlockMetadata> m;
                    try {
                        m = blockMetadata.get(c);
                    } catch (Exception s) {
                        // occasionally get weird spurious concurrent update exceptions from yugabytedb
                        m = blockMetadata.get(c);
                    }
                    if (m.isPresent())
                        return m;
                    long count = retrievalCount.incrementAndGet();
                    if (count % 100 == 0)
                        LOG.info("User " + username + ": retrieved " + count + " blocks, of total size " + retrievalSize.get());
                    return RetryStorage.runWithRetry(5, () -> p2pHttpFallback.getRaw(peers.get(0), owner, c, mirrorBat)
                            .thenApply(bo -> bo.map(b -> {
                                retrievalSize.addAndGet(b.length);
                                return checkAndAddBlock(owner, c, b);
                            }))).join();
                }))
                .toList();
        return futs.stream()
                .map(f -> f.join().get())
                .toList();
    }

    @Override
    public CompletableFuture<List<Cid>> mirror(String username,
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
        if (updated.isEmpty())
            return Futures.of(Collections.emptyList());
        Cid newRoot = updated.get();
        if (existing.equals(updated))
            return Futures.of(Collections.singletonList(newRoot));

        // This call will not verify the auth as we might not have the mirror bat present locally
        Optional<byte[]> newBlock = getRaw(peerIds, owner, newRoot, mirrorBat, ourNodeId, hasher, false, true).join();
        if (newBlock.isEmpty())
            throw new IllegalStateException("Couldn't retrieve block: " + newRoot);
        if (! hasBlock(owner, newRoot)) {
            getWithBackoff(() -> put(newBlock.get(), newRoot.isRaw(), tid, owner, false));
            usage.addPendingUsage(username, writer, newBlock.get().length);
        }
        if (newRoot.isRaw())
            return Futures.of(Collections.singletonList(newRoot));

        List<Cid> newLinks = CborObject.fromByteArray(newBlock.get()).links()
                .stream()
                .filter(h -> !h.isIdentity())
                .map(c -> (Cid)c)
                .collect(Collectors.toList());
        List<Cid> existingLinks = existing.map(c -> getLinks(owner, c, peerIds).join().stream()
                        .filter(h -> !h.isIdentity())
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());

        AtomicLong blockCount = new AtomicLong(0);
        AtomicLong totalSize = new AtomicLong(0);
        return bulkMirror(owner, writer, peerIds, existingLinks, newLinks, mirrorBat, ourNodeId,
                (p, o, h, m) -> bulkGetBlocks(p, username, o, h, m, blockCount, totalSize),
                (w, bs, size) -> usage.addPendingUsage(username, writer, size), tid, hasher)
                .thenApply(cs -> {
                    if (blockCount.get() > 0) {
                        LOG.info("Mirrored " + blockCount.get() + " blocks, taking " + totalSize.get() + " bytes");
                    }
                    return cs;
                });
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, List<ChunkMirrorCap> caps, Optional<Cid> committedRoot) {
        if (noReads)
            throw new IllegalStateException("Reads from Glacier are disabled!");
        if (! hasBlock(owner, root))
            return Futures.errored(new IllegalStateException("Champ root not present locally: " + root + " for owner: " + owner));
        return getChampLookup(owner, root, caps, committedRoot, hasher);
    }

    @Override
    public List<Cid> getOpenTransactionBlocks(PublicKeyHash owner) {
        return transactions.getOpenTransactionBlocks(owner);
    }

    @Override
    public void clearOldTransactions(PublicKeyHash owner, long cutoffMillis) {
        transactions.clearOldTransactions(owner, cutoffMillis);
    }

    private void collectGarbage(JdbcIpnsAndSocial pointers, UsageStore usage, BlockMetadataStore metadata, boolean listFromBlockstore) {
        GarbageCollector.collect(this, pointers, usage, Paths.get(""),
                this::savePointerSnapshot, metadata, this::confirmDeleteBlocks, listFromBlockstore);
    }

    private CompletableFuture<Boolean> confirmDeleteBlocks(long cborCount, long rawCount, long total) {
        System.out.println("Delete " + cborCount + " cbor blocks and " + rawCount + " raw blocks out of " + total + ", " + ((cborCount + rawCount) * 100 / total) + "% (Y/N)");
        String confirm = System.console().readLine();
        if (confirm.equals("Y"))
            return Futures.of(true);
        throw new IllegalStateException("Aborting delete!");
    }

    public CompletableFuture<Boolean> savePointerSnapshot(Stream<Map.Entry<PublicKeyHash, byte[]>> pointers) {
        // Save pointers snapshot to file
        Path pointerSnapshotFile = PathUtil.get("pointers-snapshot-" + LocalDateTime.now() + ".txt");
        pointers.forEach(entry -> {
            try {
                Files.write(pointerSnapshotFile, (entry.getKey() + ":" +
                        ArrayOps.bytesToHex(entry.getValue()) + "\n").getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return Futures.of(true);
    }

    private static <V> V getWithBackoff(Supplier<V> req) {
        long sleep = 100;
        for (int i=0; i < 20; i++) {
            try {
                return req.get();
            } catch (RateLimitException e) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException f) {}
                sleep *= 2;
            }
        }
        throw new IllegalStateException("Couldn't process request because of rate limit!");
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(PublicKeyHash owner, Multihash hash) {
        Optional<BlockMetadata> meta = blockMetadata.get((Cid) hash);
        if (meta.isPresent())
            return Futures.of(Optional.of(meta.get().size));
        Optional<byte[]> buffered = blockBuffer.get(owner, (Cid) hash).join();
        if (buffered.isPresent())
            return Futures.of(Optional.of(buffered.get().length));
        return getBlockMetadata(owner, (Cid)hash)
                .thenApply(m -> Optional.of(m.size));
    }

    private CompletableFuture<Optional<Integer>> getSizeOnly(PublicKeyHash owner, Multihash hash) {
        Optional<BlockMetadata> meta = blockMetadata.get((Cid) hash);
        if (meta.isPresent())
            return Futures.of(Optional.of(meta.get().size));
        Optional<byte[]> buffered = blockBuffer.get(owner, (Cid) hash).join();
        if (buffered.isPresent())
            return Futures.of(Optional.of(buffered.get().length));
        return getWithBackoff(() -> getSizeWithoutRetry(owner, hash));
    }

    @Override
    public CompletableFuture<IpnsEntry> getIpnsEntry(Multihash signer) {
        return ipnsHandler.getIpnsEntry(signer);
    }

    private CompletableFuture<Optional<Integer>> getSizeWithoutRetry(PublicKeyHash owner, Multihash hash) {
        if (noReads)
            throw new IllegalStateException("Reads from Glacier are disabled!");
        if (hash.isIdentity()) // Identity hashes are not actually stored explicitly
            return Futures.of(Optional.of(0));
        Histogram.Timer readTimer = HeadTimerLog.labels("size").startTimer();
        try {
            PresignedUrl headUrl = S3Request.preSignHead(folder + hashToKey(owner, hash), Optional.of(60),
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, storageClass, accessKeyId, secretKey, useHttps, hasher).join();
            Map<String, List<String>> headRes = HttpUtil.head(headUrl);
            blockHeads.inc();
            blockSize.inc();
            long size = Long.parseLong(headRes.get("Content-Length").get(0));
            return Futures.of(Optional.of((int)size));
        } catch (FileNotFoundException f) {
            LOG.warning("S3 404 error reading " + hash);
            return Futures.of(Optional.empty());
        } catch (IOException e) {
            String msg = e.getMessage();
            boolean rateLimited = isRateLimitedException(e);
            if (rateLimited) {
                S3BlockStorage.rateLimited.inc();
                throw new RateLimitException();
            }
            boolean notFound = msg.contains("<Code>NoSuchKey</Code>");
            if (! notFound) {
                LOG.warning("S3 error reading " + hash);
                LOG.log(Level.WARNING, msg, e);
            }
            return Futures.of(Optional.empty());
        } finally {
            readTimer.observeDuration();
        }
    }

    public boolean contains(PublicKeyHash owner, Multihash hash) {
        try {
            PresignedUrl headUrl = S3Request.preSignHead(folder + hashToKey(owner, hash), Optional.of(60),
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, storageClass, accessKeyId, secretKey, useHttps, hasher).join();
            Map<String, List<String>> headRes = HttpUtil.head(headUrl);
            blockHeads.inc();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public CompletableFuture<Cid> id() {
        return Futures.of(id);
    }

    @Override
    public CompletableFuture<List<Cid>> ids() {
        return Futures.of(ids);
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        return CompletableFuture.completedFuture(transactions.startTransaction(owner));
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        transactions.closeTransaction(owner, tid);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                            PublicKeyHash writer,
                                            List<byte[]> signedHashes,
                                            List<byte[]> blocks,
                                            TransactionId tid) {
        return put(owner, blocks, false, tid);
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signedHashes,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressConsumer) {
        return put(owner, blocks, true, tid);
    }

    private final ForkJoinPool bulkPutPool = Threads.newPool(1_000, "S3-bulk-put-");

    private CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                             List<byte[]> blocks,
                                             boolean isRaw,
                                             TransactionId tid) {
        List<ForkJoinTask<Cid>> puts = blocks.stream()
                .map(b -> bulkPutPool.submit(() -> getWithBackoff(() -> b.length > DirectS3BlockStore.MAX_SMALL_BLOCK_SIZE ?
                        put(b, isRaw, tid, owner, true) : // This should only happen from p2p requests that can't use DirectS3Blockstore
                        putToBuffer(b, isRaw, tid, owner).join())))
                .collect(Collectors.toList());
        return Futures.of(puts.stream().map(f ->  f.join()).collect(Collectors.toList()));
    }

    private CompletableFuture<Cid> putToBuffer(byte[] data, boolean isRaw, TransactionId tid, PublicKeyHash owner) {
        if (data.length > DirectS3BlockStore.MAX_SMALL_BLOCK_SIZE)
            throw new IllegalStateException("Block too big for block buffer!");
        Multihash hash = new Multihash(Multihash.Type.sha2_256, Hash.sha256(data));
        Cid h = new Cid(1, isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, hash.type, hash.getHash());
        blocksToFlush.add(new Pair<>(owner, h));
        return blockBuffer.put(owner, h, data).thenApply(x -> h);
    }

    /** Must be atomic relative to reads of the same key
     *
     * @param data
     */
    public Cid put(byte[] data, boolean isRaw, TransactionId tid, PublicKeyHash owner, boolean cacheCbor) {
        Multihash hash = new Multihash(Multihash.Type.sha2_256, Hash.sha256(data));
        Cid cid = new Cid(1, isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, hash.type, hash.getHash());
        transactions.addBlock(cid, tid, owner);
        return put(owner, cid, data, cacheCbor).left;
    }

    public Pair<Cid, BlockMetadata> put(PublicKeyHash owner, Cid cid, byte[] data, boolean cacheCbor) {
        Histogram.Timer writeTimer = writeTimerLog.labels("write").startTimer();
        String key = hashToKey(owner, cid);
        try {
            String s3Key = folder + key;
            Map<String, String> extraHeaders = new TreeMap<>();
            extraHeaders.put("Content-Type", "application/octet-stream");
            boolean hashContent = true;
            String contentHash = hashContent ? ArrayOps.bytesToHex(cid.getHash()) : "UNSIGNED-PAYLOAD";
            PresignedUrl putUrl = S3Request.preSignPut(s3Key, data.length, contentHash, storageClass, false,
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, extraHeaders, region, accessKeyId, secretKey, useHttps, hasher).join();
            String version = HttpUtil.putWithVersion(putUrl, data).right;
            BlockMetadata meta = blockMetadata.put(owner, cid, version, data);
            blockPuts.inc();
            blockPutBytes.labels("size").observe(data.length);
            if (cacheCbor && ! cid.isRaw())
                cborCache.put(cid, data);
            return new Pair<>(cid, meta);
        } catch (IOException e) {
            boolean rateLimited = isRateLimitedException(e);
            if (rateLimited) {
                S3BlockStorage.rateLimited.inc();
                throw new RateLimitException();
            }
            LOG.log(Level.SEVERE, e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            writeTimer.observeDuration();
        }
    }

    @Override
    public List<BlockMetadata> bulkGetLinks(List<Multihash> peerIds,
                                            PublicKeyHash owner,
                                            Cid ourId,
                                            List<Cid> blocks,
                                            Optional<BatWithId> mirrorBat,
                                            Hasher h) {
        List<Optional<byte[]>> rawOpts = blocks.stream()
                .parallel()
                .map(b -> RetryStorage.runWithRetry(2, () -> p2pHttpFallback.getRaw(peerIds.get(0), owner, b, mirrorBat)).join())
                .toList();
        if (rawOpts.size() != blocks.size())
            throw new IllegalStateException("Incorrect number of blocks returned!");
        List<byte[]> raw = rawOpts.stream().map(Optional::get).toList();
        List<Pair<Cid, byte[]>> hashed = new ArrayList<>();
        for (int i=0; i < blocks.size(); i++) {
            Cid c = blocks.get(i);
            byte[] bytes = raw.get(i);
            Cid res = h.hash(bytes, c.isRaw()).join();
            if (! res.equals(c))
                throw new IllegalStateException("Received block with incorrect hash!");
            hashed.add(new Pair<>(c, bytes));
        }
        return hashed.stream().map(p -> put(owner, p.left, p.right, false).right).toList();
    }

    @Override
    public CompletableFuture<List<Cid>> getLinks(PublicKeyHash owner, Cid root, List<Multihash> peerids) {
        if (root.isRaw())
            return CompletableFuture.completedFuture(Collections.emptyList());
        Optional<BlockMetadata> meta = blockMetadata.get(root);
        if (meta.isPresent())
            return Futures.of(meta.get().links);
        return getBlockMetadata(owner, root)
                .thenApply(res -> res.links);
    }

    @Override
    public CompletableFuture<BlockMetadata> getBlockMetadata(PublicKeyHash owner, Cid h) {
        if (h.isIdentity())
            return Futures.of(new BlockMetadata(0, CborObject.getLinks(h, h.getHash()), Bat.getBlockBats(h, h.getHash())));
        Optional<BlockMetadata> cached = blockMetadata.get(h);
        if (cached.isPresent())
            return Futures.of(cached.get());
        Optional<Pair<byte[], String>> data = getRaw(peerIds, owner, h, h.isRaw() ?
                Optional.of(new Pair<>(0, Bat.MAX_RAW_BLOCK_PREFIX_SIZE - 1)) :
                Optional.empty(), false, Optional.empty(), false).join();
        if (data.isEmpty())
            throw new IllegalStateException("Block not present locally: " + h);
        byte[] bloc = data.get().left;
        String version = data.get().right;
        if (h.isRaw()) {
            // we should avoid this by populating the metadata store, as it means two S3 calls, a ranged GET and a HEAD
            int size = getSizeOnly(owner, h).join().get();
            BlockMetadata meta = new BlockMetadata(size, Collections.emptyList(), Bat.getRawBlockBats(bloc));
            blockMetadata.put(owner, h, version, meta);
            return Futures.of(meta);
        }
        return Futures.of(blockMetadata.put(owner, h, version, bloc));
    }

    public void updateMetadataStoreIfEmpty() {
        if (! blockMetadata.isEmpty())
            return;
        LOG.info("Updating block metadata store from S3. Listing blocks...");
        List<Pair<PublicKeyHash, Cid>> all = getAllBlockHashes(true).collect(Collectors.toList());
        LOG.info("Updating block metadata store from S3. Updating db with " + all.size() + " blocks...");

        int updateParallelism = 10;
        ForkJoinPool pool = new ForkJoinPool(updateParallelism);
        int batchSize = all.size() / updateParallelism;
        AtomicLong progress = new AtomicLong(0);
        int tenth = batchSize/10;

        List<ForkJoinTask<Optional<BlockMetadata>>> futures = IntStream.range(0, updateParallelism)
                .mapToObj(b -> pool.submit(() -> IntStream.range(b * batchSize, (b + 1) * batchSize)
                        .mapToObj(i -> {
                            BlockMetadata res = getBlockMetadata(all.get(i).left, all.get(i).right).join();
                            if (i % (batchSize / 10) == 0) {
                                long updatedProgress = progress.addAndGet(tenth);
                                if (updatedProgress * 10 / all.size() > (updatedProgress - tenth) * 10 / all.size())
                                    LOG.info("Populating block metadata: " + updatedProgress * 100 / all.size() + "% done");
                            }
                            return res;
                        })
                        .reduce((x, y) -> y)))
                .collect(Collectors.toList());
        futures.stream()
                .map(ForkJoinTask::join)
                .collect(Collectors.toList());
        LOG.info("Finished updating block metadata store from S3.");
    }

    @Override
    public Stream<Pair<PublicKeyHash, Cid>> getAllBlockHashes(boolean useBlockstore) {
        // todo make this actually streaming
        return getFiles(Optional.empty(), Long.MAX_VALUE).stream();
    }

    @Override
    public Stream<Pair<PublicKeyHash, Cid>> getAllBlockHashes(PublicKeyHash owner, boolean useBlockstore) {
        // todo make this actually streaming
        return getFiles(Optional.of(owner), Long.MAX_VALUE).stream();
    }

    @Override
    public void getAllBlockHashVersions(PublicKeyHash owner, Consumer<List<BlockVersion>> res) {
        getFileVersions(owner, res);
    }

    @Override
    public void getAllRawBlockVersions(PublicKeyHash owner, Consumer<List<BlockVersion>> res) {
        applyToAllVersions(ownerToPrefix(owner) + "AFK", res, res);
    }

    private void getFileVersions(PublicKeyHash owner, Consumer<List<BlockVersion>> res) {
        applyToAllVersions(ownerToPrefix(owner) + (owner == null ? "A" : ""), res, res);
    }

    private List<Pair<PublicKeyHash, Cid>> getFiles(Optional<PublicKeyHash> owner, long maxReturned) {
        List<Pair<PublicKeyHash, Cid>> results = new ArrayList<>();
        applyToAll(owner.map(this::ownerToPrefix), obj -> {
            try {
                results.add(keyToOwnerAndHash(owner, obj.key));
            } catch (Exception e) {
                LOG.warning("Couldn't parse S3 key to Cid: " + obj.key);
            }
        }, maxReturned);
        return results;
    }

    private void applyToAll(Optional<String> prefix, Consumer<S3AdminRequests.ObjectMetadata> processor, long maxObjects) {
        try {
            Optional<String> continuationToken = Optional.empty();
            S3AdminRequests.ListObjectsReply result;
            long processedObjects = 0;
            do {
                result = S3AdminRequests.listObjects(folder + prefix.orElse(""), 1_000, continuationToken,
                        ZonedDateTime.now(), host, region, storageClass, accessKeyId, secretKey, url -> {
                            try {
                                return HttpUtil.get(url);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }, S3AdminRequests.builder::get, useHttps, hasher);

                for (S3AdminRequests.ObjectMetadata objectSummary : result.objects) {
                    if (objectSummary.key.endsWith("/")) {
                        LOG.fine(" - " + objectSummary.key + "  " + "(directory)");
                        continue;
                    }
                    processor.accept(objectSummary);
                    processedObjects++;
                    if (processedObjects >= maxObjects)
                        return;
                }
                LOG.log(Level.FINE, "Next Continuation Token : " + result.continuationToken);
                continuationToken = result.continuationToken;
            } while (result.isTruncated);

        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    private void applyToAllVersions(String prefix,
                                    Consumer<List<BlockVersion>> processor,
                                    Consumer<List<BlockVersion>> deleteProcessor) {
        try {
            Optional<String> keyMarker = Optional.empty();
            Optional<String> versionIdMarker = Optional.empty();
            S3AdminRequests.ListObjectVersionsReply result;
            do {
                result = S3AdminRequests.listObjectVersions(folder + prefix, 1_000, keyMarker, versionIdMarker,
                        ZonedDateTime.now(), host, region, storageClass, accessKeyId, secretKey, url -> getWithBackoff(() -> {
                            try {
                                return HttpUtil.get(url);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }), S3AdminRequests.builder::get, useHttps, hasher);

                List<BlockVersion> versions = result.versions.stream()
                        .filter(omv -> !omv.key.endsWith("/"))
                        .map(omv -> new BlockVersion(keyToHash(omv.key), omv.version, omv.isLatest))
                        .collect(Collectors.toList());
                processor.accept(versions);

                List<BlockVersion> deletes = result.deletes.stream()
                        .filter(dm -> !dm.key.endsWith("/"))
                        .map(dm -> new BlockVersion(keyToHash(dm.key), dm.version, dm.isLatest))
                        .collect(Collectors.toList());
                deleteProcessor.accept(deletes);
                LOG.log(Level.FINE, "Next key marker : " + result.nextKeyMarker);
                LOG.log(Level.FINE, "Next version id marker : " + result.nextVersionIdMarker);
                keyMarker = result.nextKeyMarker;
                versionIdMarker = result.nextVersionIdMarker;
            } while (result.isTruncated);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    @Override
    public void delete(PublicKeyHash owner, Cid hash) {
        delete(owner, new BlockVersion(hash, null, true));
    }

    @Override
    public void delete(PublicKeyHash owner, BlockVersion version) {
        try {
            PresignedUrl delUrl = S3AdminRequests.preSignDelete(folder + hashToKey(owner, version.cid), Optional.ofNullable(version.version),
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, storageClass, accessKeyId, secretKey, useHttps, hasher).join();
            HttpUtil.delete(delUrl);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static AtomicBoolean hasBulkDeleteError = new AtomicBoolean(false);

    @Override
    public void bulkDelete(PublicKeyHash owner, List<BlockVersion> versions) {
        List<Pair<String, String>> keyVersions = versions.stream()
                .map(v -> new Pair<>(folder + hashToKey(owner, v.cid), v.version))
                .collect(Collectors.toList());
        try {
            S3AdminRequests.bulkDelete(keyVersions, ZonedDateTime.now(), host, region, storageClass, accessKeyId, secretKey,
                    b -> ArrayOps.bytesToHex(Hash.sha256(b)),
                    b -> Base64.encodeBase64String(Hash.sha256(b)),
                    (url, body) -> {
                        try {
                            return HttpUtil.post(url, body);
                        } catch (IOException e) {
                            boolean rateLimited = isRateLimitedException(e);
                            if (rateLimited) {
                                S3BlockStorage.rateLimited.inc();
                                throw new RateLimitException();
                            }
                            throw new RuntimeException(e);
                        }
                    }, S3AdminRequests.builder::get, useHttps, hasher);
        } catch (Exception e) {
            // fallback to doing deletes with parallel single calls
            // This is necessary because B2 doesn't implement the bulk delete call!!
            if (! hasBulkDeleteError.get())
                System.out.println("Falling back to parallel individual block deletes... (B2 doesn't implement bulk delete)" + e.getMessage());
            hasBulkDeleteError.set(true);
            for (BlockVersion version : versions) {
                new Thread(() -> delete(owner, version)).start();
            }
        }
    }

    @Override
    public Optional<BlockCache> getBlockCache() {
        return Optional.of(cborCache);
    }

    @Override
    public CompletableFuture<EncryptedCapability> getSecretLink(SecretLink link) {
        throw new IllegalStateException("Shouldn't get here.");
    }

    @Override
    public CompletableFuture<LinkCounts> getLinkCounts(String owner, LocalDateTime after, BatWithId mirrorBat) {
        throw new IllegalStateException("Shouldn't get here.");
    }

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        Logging.init(a.with("log-to-console", "true"));
        Crypto crypto = Main.initCrypto();
        Hasher hasher = crypto.hasher;
        S3Config config = S3Config.build(a, Optional.empty());
        boolean usePostgres = a.getBoolean("use-postgres", false);
        SqlSupplier sqlCommands = usePostgres ?
                new PostgresCommands() :
                new SqliteCommands();
        Supplier<Connection> database = Main.getDBConnector(a, "mutable-pointers-file");
        Supplier<Connection> transactionsDb = Main.getDBConnector(a, "transactions-sql-file");
        TransactionStore transactions = JdbcTransactionStore.build(transactionsDb, sqlCommands);
        BlockRequestAuthoriser authoriser = (c, b, s, auth) -> Futures.of(true);
        BlockMetadataStore meta = Builder.buildBlockMetadata(a);
        Supplier<Connection> usageDb = Main.getDBConnector(a, "space-usage-sql-file");
        UsageStore usageStore = new JdbcUsageStore(usageDb, sqlCommands);
        Supplier<Connection> statusDb = Main.getDBConnector(a, "partition-status-file");
        PartitionStatus partitioned = new JdbcPartitionStatus(statusDb, sqlCommands);
        S3BlockStorage s3 = new S3BlockStorage(config, List.of(Cid.decode(a.getArg("ipfs.id"))),
                BlockStoreProperties.empty(), "localhost:8000", transactions, authoriser, null, meta, usageStore,
                new RamBlockCache(1024, 100),
                new FileBlockBuffer(a.fromPeergosDir("s3-block-buffer-dir", "block-buffer")),
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, partitioned, hasher,
                new RAMStorage(hasher), null);
        JdbcIpnsAndSocial rawPointers = new JdbcIpnsAndSocial(database, sqlCommands);
        if (a.hasArg("integrity-check")) {
            if (a.hasArg("username"))
                GarbageCollector.checkUserIntegrity(a.getArg("username"), s3, meta, rawPointers, usageStore, a.getBoolean("fix-metadata", false), hasher);
            else
                GarbageCollector.checkIntegrity(s3, meta, rawPointers, usageStore, a.getBoolean("fix-metadata", false), hasher);
            return;
        }
        System.out.println("Performing GC on S3 block store...");
        s3.collectGarbage(rawPointers, usageStore, meta, a.getBoolean("s3.versioned-bucket"));
    }

    @Override
    public String toString() {
        return "S3BlockStore[" + bucket + ":" + folder + "]";
    }
}
