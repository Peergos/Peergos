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
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
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

    private static final Histogram readTimerLog = Histogram.build()
            .labelNames("filesize")
            .name("block_read_seconds")
            .help("Time to read a block from immutable storage")
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

    private final Cid id, p2pGetId;
    private final String region, bucket, folder, regionEndpoint, host;
    private final boolean useHttps;
    private final String accessKeyId, secretKey;
    private final BlockStoreProperties props;
    private final TransactionStore transactions;
    private final BlockRequestAuthoriser authoriser;
    private final BlockMetadataStore blockMetadata;
    private final Hasher hasher;
    private final DeletableContentAddressedStorage p2pFallback, bloomTarget;

    private final LinkedBlockingQueue<Cid> bloomAdds = new LinkedBlockingQueue<>();
    private CoreNode pki;

    public S3BlockStorage(S3Config config,
                          Cid id,
                          BlockStoreProperties props,
                          TransactionStore transactions,
                          BlockRequestAuthoriser authoriser,
                          BlockMetadataStore blockMetadata,
                          Hasher hasher,
                          DeletableContentAddressedStorage p2pFallback,
                          DeletableContentAddressedStorage bloomTarget) {
        this.id = id;
        this.p2pGetId = p2pFallback.id().join();
        this.region = config.region;
        this.bucket = config.bucket;
        this.regionEndpoint = config.regionEndpoint;
        this.host = config.getHost();
        this.useHttps = ! host.endsWith("localhost") && ! host.contains("localhost:");
        this.folder = (useHttps ? "" : bucket + "/") + (config.path.isEmpty() || config.path.endsWith("/") ? config.path : config.path + "/");
        this.accessKeyId = config.accessKey;
        this.secretKey = config.secretKey;
        LOG.info("Using S3 Block Storage at " + config.regionEndpoint + ", bucket " + config.bucket + ", path: " + config.path);
        this.props = props;
        this.transactions = transactions;
        this.authoriser = authoriser;
        this.blockMetadata = blockMetadata;
        this.hasher = hasher;
        this.p2pFallback = p2pFallback;
        this.bloomTarget = bloomTarget;
        startBloomThread();
    }

    @Override
    public void setPki(CoreNode pki) {
        this.pki = pki;
    }

    private void startBloomThread() {
        Thread bloomer = new Thread(() -> {
            while (true) {
                try {
                    Cid h = bloomAdds.peek();
                    if (h == null) {
                        Thread.sleep(1_000);
                        continue;
                    }
                    bloomTarget.bloomAdd(h);
                    bloomAdds.poll();
                } catch (Exception e) {
                    LOG.log(Level.INFO, e.getMessage(), e);
                }
            }
        });
        bloomer.setDaemon(true);
        bloomer.start();
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    private static String hashToKey(Multihash hash) {
        return DirectS3BlockStore.hashToKey(hash);
    }

    private Cid keyToHash(String key) {
        return DirectS3BlockStore.keyToHash(key.substring(folder.length()));
    }

    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return Futures.of(props);
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authReads(List<MirrorCap> blocks) {
        if (blocks.size() > MAX_BLOCK_AUTHS)
            throw new IllegalStateException("Too many reads to auth!");
        List<PresignedUrl> res = new ArrayList<>();

        if (! blocks.stream().allMatch(c -> hasBlock(c.hash)))
            return Futures.errored(new IllegalStateException("Blocks not present locally"));
        if (! blocks.stream().allMatch(c -> c.hash.isRaw()))
            return Futures.errored(new IllegalStateException("Can only auth read for raw blocks, not cbor!"));

        // verify all BATs in parallel
        List<CompletableFuture<Boolean>> auths = blocks.stream()
                .parallel()
                .map(b -> getBlockMetadata(b.hash)
                        .thenApply(meta -> {
                            String auth = b.bat.map(bat -> bat.bat.generateAuth(b.hash, id, 300, S3Request.currentDatetime(), bat.id, hasher)
                                    .thenApply(BlockAuth::encode).join()).orElse("");
                            if (!authoriser.allowRead(b.hash, meta.batids, id, auth).join())
                                throw new IllegalStateException("Unauthorised!");
                            return true;
                        }))
                .collect(Collectors.toList());

        for (MirrorCap block : blocks) {
            String s3Key = hashToKey(block.hash);
            res.add(S3Request.preSignGet(folder + s3Key, Optional.of(600), Optional.empty(), S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, accessKeyId, secretKey, useHttps, hasher).join());
        }
        for (CompletableFuture<Boolean> fut : auths) {
            fut.join(); // Any invalids BATs will cause this to throw
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
                Cid cid = new Cid(1, codec, Multihash.Type.sha2_256, writer.unsignMessage(signedHashes.get(i)));
                blockProps.add(new Pair<>(cid, new BlockMetadata(blockSizes.get(i), Collections.emptyList(), batIds.get(i))));
            }
            List<PresignedUrl> res = new ArrayList<>();
            for (Pair<Cid, BlockMetadata> props : blockProps) {
                if (props.left.type != Multihash.Type.sha2_256)
                    throw new IllegalStateException("Can only pre-auth writes of sha256 hashed blocks!");
                transactions.addBlock(props.left, tid, owner);
                String s3Key = hashToKey(props.left);
                String contentSha256 = ArrayOps.bytesToHex(props.left.getHash());
                Map<String, String> extraHeaders = new LinkedHashMap<>();
                extraHeaders.put("Content-Type", "application/octet-stream");
                res.add(S3Request.preSignPut(folder + s3Key, props.right.size, contentSha256, false,
                        S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, extraHeaders, region, accessKeyId, secretKey, useHttps, hasher).join());
                blockPutAuths.inc();
                bloomAdds.add(props.left);
                if (isRaw)
                    blockMetadata.put(props.left, null, props.right);
            }
            return Futures.of(res);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid object, Optional<BatWithId> bat) {
        return getRaw(pki.getStorageProviders(owner), object, bat, id, hasher, false)
                .thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, Cid hash, String auth, boolean persistBlock) {
        if (hash.isRaw())
            throw new IllegalStateException("Need to call getRaw if cid is not cbor!");
        return getRaw(peerIds, hash, auth, persistBlock).thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid object, Optional<BatWithId> bat) {
        return getRaw(pki.getStorageProviders(owner), object, bat, id, hasher, false);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h, boolean persistBlock) {
        if (bat.isEmpty())
            return getRaw(peerIds, hash, "", persistBlock);
        return bat.get().bat.generateAuth(hash, ourId, 300, S3Request.currentDatetime(), bat.get().id, h)
                .thenApply(BlockAuth::encode)
                .thenCompose(auth -> getRaw(peerIds, hash, Optional.empty(), auth, true, bat))
                .thenApply(p -> p.map(v -> v.left));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, Cid hash, String auth, boolean persistBlock) {
        return getRaw(peerIds, hash, Optional.empty(), auth, true, Optional.empty())
                .thenApply(p -> p.map(v -> v.left));
    }

    /** Get raw block data and version
     *
     * @param hash
     * @param range
     * @param auth
     * @param enforceAuth
     * @param bat
     * @return
     */
    private CompletableFuture<Optional<Pair<byte[], String>>> getRaw(List<Multihash> peerIds,
                                                                     Cid hash,
                                                                     Optional<Pair<Integer, Integer>> range,
                                                                     String auth,
                                                                     boolean enforceAuth,
                                                                     Optional<BatWithId> bat) {
        return getWithBackoff(() -> getRawWithoutBackoff(peerIds, hash, range, auth, enforceAuth, bat));
    }

    private CompletableFuture<Optional<Pair<byte[], String>>> getRawWithoutBackoff(List<Multihash> peerIds,
                                                                                   Cid hash,
                                                                                   Optional<Pair<Integer, Integer>> range,
                                                                                   String auth,
                                                                                   boolean enforceAuth,
                                                                                   Optional<BatWithId> bat) {
        String path = folder + hashToKey(hash);
        PresignedUrl getUrl = S3Request.preSignGet(path, Optional.of(600), range,
                S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, accessKeyId, secretKey, useHttps, hasher).join();
        Histogram.Timer readTimer = readTimerLog.labels("read").startTimer();
        try {
            Pair<byte[], String> blockAndVersion = HttpUtil.getWithVersion(getUrl);
            blockGets.inc();
            // validate auth, unless this is an internal query
            if (enforceAuth && ! authoriser.allowRead(hash, blockAndVersion.left, id, auth).join())
                throw new IllegalStateException("Unauthorised!");
            if (range.isEmpty())
                blockMetadata.put(hash, blockAndVersion.right, blockAndVersion.left);
            return Futures.of(Optional.of(blockAndVersion));
        } catch (SocketTimeoutException | SSLException e) {
            // S3 can't handle the load so treat this as a rate limit and slow down
            throw new RateLimitException();
        } catch (IOException e) {
            String msg = e.getMessage();
            boolean rateLimited = msg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>SlowDown</Code>");
            if (rateLimited) {
                getRateLimited.inc();
                S3BlockStorage.rateLimited.inc();
                throw new RateLimitException();
            }
            boolean notFound = msg.contains("<Code>NoSuchKey</Code>");
            if (! notFound) {
                LOG.warning("S3 error reading " + path);
                LOG.log(Level.WARNING, msg, e);
            }
            failedBlockGets.inc();

            nonLocalGets.inc();
            if (p2pGetId.equals(id))
                return p2pFallback.getRaw(peerIds, hash, auth, false)
                        .thenApply(dopt -> dopt.map(b -> new Pair<>(b, null)));
            // recalculate auth when the fallback node has a different node id
            return p2pFallback.getRaw(peerIds, hash, bat, p2pGetId, hasher, enforceAuth, false)
                    .thenApply(dopt -> dopt.map(b -> new Pair<>(b, null)));
        } finally {
            readTimer.observeDuration();
        }
    }

    @Override
    public boolean hasBlock(Cid hash) {
        return getWithBackoff(() -> hasBlockWithoutBackoff(hash));
    }

    public boolean hasBlockWithoutBackoff(Cid hash) {
        try {
            PresignedUrl headUrl = S3Request.preSignHead(folder + hashToKey(hash), Optional.of(60),
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, accessKeyId, secretKey, useHttps, hasher).join();
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
            boolean rateLimited = msg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>SlowDown</Code>");
            if (rateLimited) {
                S3BlockStorage.rateLimited.inc();
                throw new RateLimitException();
            }
            boolean notFound = msg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>NoSuchKey</Code>");
            if (! notFound) {
                LOG.warning("S3 error reading " + hash);
                LOG.log(Level.WARNING, msg, e);
            }
            return false;
        }
    }

    @Override
    public CompletableFuture<List<Cid>> mirror(PublicKeyHash owner,
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

        // This call will not verify the auth as we might not have the mirror bat present locally
        Optional<byte[]> newBlock = p2pFallback.getRaw(peerIds, newRoot, mirrorBat, p2pGetId, hasher, false, true).join();
        if (newBlock.isEmpty())
            throw new IllegalStateException("Couldn't retrieve block: " + newRoot);
        if (! hasBlock(newRoot))
            getWithBackoff(() -> put(newBlock.get(), newRoot.isRaw(), tid, owner));
        if (newRoot.isRaw())
            return Futures.of(Collections.singletonList(newRoot));

        List<Multihash> newLinks = CborObject.fromByteArray(newBlock.get()).links()
                .stream()
                .filter(h -> !h.isIdentity())
                .collect(Collectors.toList());
        List<Cid> existingLinks = existing.map(c -> getLinks(c).join().stream()
                        .filter(h -> !h.isIdentity())
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());

        for (int i=0; i < newLinks.size(); i++) {
            Optional<Cid> existingLink = i < existingLinks.size() ?
                    Optional.of(existingLinks.get(i)) :
                    Optional.empty();
            Optional<Cid> updatedLink = Optional.of((Cid)newLinks.get(i));
            mirror(owner, peerIds, existingLink, updatedLink, mirrorBat, ourNodeId, tid, hasher).join();
        }
        return Futures.of(Collections.singletonList(newRoot));
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat,Optional<Cid> committedRoot) {
        if (! hasBlock(root))
            return Futures.errored(new IllegalStateException("Champ root not present locally: " + root));
        return getChampLookup(owner, root, champKey, bat, committedRoot, hasher);
    }

    @Override
    public List<Multihash> getOpenTransactionBlocks() {
        return transactions.getOpenTransactionBlocks();
    }

    @Override
    public void clearOldTransactions(long cutoffMillis) {
        transactions.clearOldTransactions(cutoffMillis);
    }

    private void collectGarbage(JdbcIpnsAndSocial pointers, UsageStore usage, BlockMetadataStore metadata, boolean listFromBlockstore) {
        GarbageCollector.collect(this, pointers, usage,
                this::savePointerSnapshot, metadata, listFromBlockstore);
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
    public CompletableFuture<Optional<Integer>> getSize(Multihash hash) {
        Optional<BlockMetadata> meta = blockMetadata.get((Cid) hash);
        if (meta.isPresent())
            return Futures.of(Optional.of(meta.get().size));
        return getWithBackoff(() -> getSizeWithoutRetry(hash));
    }

    private CompletableFuture<Optional<Integer>> getSizeWithoutRetry(Multihash hash) {
        if (hash.isIdentity()) // Identity hashes are not actually stored explicitly
            return Futures.of(Optional.of(0));
        Histogram.Timer readTimer = readTimerLog.labels("size").startTimer();
        try {
            PresignedUrl headUrl = S3Request.preSignHead(folder + hashToKey(hash), Optional.of(60),
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, accessKeyId, secretKey, useHttps, hasher).join();
            Map<String, List<String>> headRes = HttpUtil.head(headUrl);
            blockHeads.inc();
            long size = Long.parseLong(headRes.get("Content-Length").get(0));
            return Futures.of(Optional.of((int)size));
        } catch (FileNotFoundException f) {
            LOG.warning("S3 404 error reading " + hash);
            return Futures.of(Optional.empty());
        } catch (IOException e) {
            String msg = e.getMessage();
            boolean rateLimited = msg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>SlowDown</Code>");
            if (rateLimited) {
                S3BlockStorage.rateLimited.inc();
                throw new RateLimitException();
            }
            boolean notFound = msg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>NoSuchKey</Code>");
            if (! notFound) {
                LOG.warning("S3 error reading " + hash);
                LOG.log(Level.WARNING, msg, e);
            }
            return Futures.of(Optional.empty());
        } finally {
            readTimer.observeDuration();
        }
    }

    public boolean contains(Multihash hash) {
        try {
            PresignedUrl headUrl = S3Request.preSignHead(folder + hashToKey(hash), Optional.of(60),
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, accessKeyId, secretKey, useHttps, hasher).join();
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
                .map(b -> bulkPutPool.submit(() -> getWithBackoff(() -> put(b, isRaw, tid, owner))))
                .collect(Collectors.toList());
        return Futures.of(puts.stream().map(f ->  f.join()).collect(Collectors.toList()));
    }

    /** Must be atomic relative to reads of the same key
     *
     * @param data
     */
    public Cid put(byte[] data, boolean isRaw, TransactionId tid, PublicKeyHash owner) {
        Histogram.Timer writeTimer = writeTimerLog.labels("write").startTimer();
        Multihash hash = new Multihash(Multihash.Type.sha2_256, Hash.sha256(data));
        Cid cid = new Cid(1, isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, hash.type, hash.getHash());
        String key = hashToKey(cid);
        try {
            transactions.addBlock(cid, tid, owner);
            String s3Key = folder + key;
            Map<String, String> extraHeaders = new TreeMap<>();
            extraHeaders.put("Content-Type", "application/octet-stream");
            boolean hashContent = true;
            String contentHash = hashContent ? ArrayOps.bytesToHex(hash.getHash()) : "UNSIGNED-PAYLOAD";
            PresignedUrl putUrl = S3Request.preSignPut(s3Key, data.length, contentHash, false,
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, extraHeaders, region, accessKeyId, secretKey, useHttps, hasher).join();
            String version = HttpUtil.putWithVersion(putUrl, data).right;
            blockMetadata.put(cid, version, data);
            bloomAdds.add(cid);
            blockPuts.inc();
            blockPutBytes.labels("size").observe(data.length);
            return cid;
        } catch (IOException e) {
            String msg = e.getMessage();
            boolean rateLimited = msg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>SlowDown</Code>");
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
    public CompletableFuture<List<Cid>> getLinks(Cid root) {
        if (root.isRaw())
            return CompletableFuture.completedFuture(Collections.emptyList());
        Optional<BlockMetadata> meta = blockMetadata.get(root);
        if (meta.isPresent())
            return Futures.of(meta.get().links);
        return getBlockMetadata(root)
                .thenApply(res -> res.links);
    }

    @Override
    public CompletableFuture<BlockMetadata> getBlockMetadata(Cid h) {
        if (h.isIdentity())
            return Futures.of(new BlockMetadata(0, CborObject.getLinks(h, h.getHash()), Bat.getBlockBats(h, h.getHash())));
        Optional<BlockMetadata> cached = blockMetadata.get(h);
        if (cached.isPresent())
            return Futures.of(cached.get());
        Optional<Pair<byte[], String>> data = getRaw(Collections.emptyList(), h, h.isRaw() ?
                Optional.of(new Pair<>(0, Bat.MAX_RAW_BLOCK_PREFIX_SIZE - 1)) :
                Optional.empty(), "", false, Optional.empty()).join();
        if (data.isEmpty())
            throw new IllegalStateException("Block not present locally: " + h);
        byte[] bloc = data.get().left;
        String version = data.get().right;
        if (h.isRaw()) {
            // we should avoid this by populating the metadata store, as it means two S3 calls, a ranged GET and a HEAD
            int size = getSize(h).join().get();
            BlockMetadata meta = new BlockMetadata(size, Collections.emptyList(), Bat.getRawBlockBats(bloc));
            blockMetadata.put(h, version, meta);
            return Futures.of(meta);
        }
        return Futures.of(blockMetadata.put(h, version, bloc));
    }

    public void updateMetadataStoreIfEmpty() {
        if (blockMetadata.size() > 0)
            return;
        LOG.info("Updating block metadata store from S3. Listing blocks...");
        List<Cid> all = getAllBlockHashes().collect(Collectors.toList());
        LOG.info("Updating block metadata store from S3. Updating db with " + all.size() + " blocks...");

        int updateParallelism = 10;
        ForkJoinPool pool = new ForkJoinPool(updateParallelism);
        int batchSize = all.size() / updateParallelism;
        AtomicLong progress = new AtomicLong(0);
        int tenth = batchSize/10;

        List<ForkJoinTask<Optional<BlockMetadata>>> futures = IntStream.range(0, updateParallelism)
                .mapToObj(b -> pool.submit(() -> IntStream.range(b * batchSize, (b + 1) * batchSize)
                        .mapToObj(i -> {
                            BlockMetadata res = getBlockMetadata(all.get(i)).join();
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
    public Stream<Cid> getAllBlockHashes() {
        // todo make this actually streaming
        return getFiles(Long.MAX_VALUE).stream();
    }

    @Override
    public Stream<BlockVersion> getAllBlockHashVersions() {
        // todo make this actually streaming
        return getFileVersions(Long.MAX_VALUE).stream();
    }

    @Override
    public Stream<BlockVersion> getAllRawBlockVersions() {
        // todo make this actually streaming
        List<BlockVersion> results = new ArrayList<>();
        applyToAllVersions("AFK", obj -> {
            try {
                results.add(new BlockVersion(keyToHash(obj.key), obj.version, obj.isLatest));
            } catch (Exception e) {
                LOG.warning("Couldn't parse S3 key to Cid: " + obj.key);
            }
        }, del -> {
            try {
                results.add(new BlockVersion(keyToHash(del.key), del.version, del.isLatest));
            } catch (Exception e) {
                LOG.warning("Couldn't parse S3 key to Cid: " + del.key);
            }
        }, Long.MAX_VALUE);
        return results.stream();
    }

    private List<BlockVersion> getFileVersions(long maxReturned) {
        List<BlockVersion> results = new ArrayList<>();
        applyToAllVersions("", obj -> {
            try {
                results.add(new BlockVersion(keyToHash(obj.key), obj.version, obj.isLatest));
            } catch (Exception e) {
                LOG.warning("Couldn't parse S3 key to Cid: " + obj.key);
            }
        }, del -> {
            try {
                results.add(new BlockVersion(keyToHash(del.key), del.version, del.isLatest));
            } catch (Exception e) {
                LOG.warning("Couldn't parse S3 key to Cid: " + del.key);
            }
        }, maxReturned);
        return results;
    }

    private List<Cid> getFiles(long maxReturned) {
        List<Cid> results = new ArrayList<>();
        applyToAll(obj -> {
            try {
                results.add(keyToHash(obj.key));
            } catch (Exception e) {
                LOG.warning("Couldn't parse S3 key to Cid: " + obj.key);
            }
        }, maxReturned);
        return results;
    }

    private List<String> getFilenames(long maxReturned) {
        List<String> results = new ArrayList<>();
        applyToAll(obj -> results.add(obj.key), maxReturned);
        return results;
    }

    private void applyToAll(Consumer<S3AdminRequests.ObjectMetadata> processor, long maxObjects) {
        try {
            Optional<String> continuationToken = Optional.empty();
            S3AdminRequests.ListObjectsReply result;
            long processedObjects = 0;
            do {
                result = S3AdminRequests.listObjects(folder, 1_000, continuationToken,
                        ZonedDateTime.now(), host, region, accessKeyId, secretKey, url -> {
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
                                    Consumer<S3AdminRequests.ObjectMetadataVersion> processor,
                                    Consumer<S3AdminRequests.DeleteMarker> deleteProcessor,
                                    long maxObjects) {
        try {
            Optional<String> keyMarker = Optional.empty();
            Optional<String> versionIdMarker = Optional.empty();
            S3AdminRequests.ListObjectVersionsReply result;
            long processedObjects = 0;
            do {
                result = S3AdminRequests.listObjectVersions(folder + prefix, 1_000, keyMarker, versionIdMarker,
                        ZonedDateTime.now(), host, region, accessKeyId, secretKey, url -> {
                            try {
                                return HttpUtil.get(url);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }, S3AdminRequests.builder::get, useHttps, hasher);

                for (S3AdminRequests.ObjectMetadataVersion objectSummary : result.versions) {
                    if (objectSummary.key.endsWith("/")) {
                        LOG.fine(" - " + objectSummary.key + "  " + "(directory)");
                        continue;
                    }
                    processor.accept(objectSummary);
                    processedObjects++;
                    if (processedObjects >= maxObjects)
                        return;
                }
                for (S3AdminRequests.DeleteMarker deleteSummary : result.deletes) {
                    if (deleteSummary.key.endsWith("/")) {
                        LOG.fine(" - " + deleteSummary.key + "  " + "(directory)");
                        continue;
                    }
                    deleteProcessor.accept(deleteSummary);
                    processedObjects++;
                    if (processedObjects >= maxObjects)
                        return;
                }
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
    public void delete(Cid hash) {
        delete(new BlockVersion(hash, null, true));
    }

    @Override
    public void delete(BlockVersion version) {
        try {
            PresignedUrl delUrl = S3AdminRequests.preSignDelete(folder + hashToKey(version.cid), Optional.ofNullable(version.version),
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, accessKeyId, secretKey, useHttps, hasher).join();
            HttpUtil.delete(delUrl);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void bulkDelete(List<BlockVersion> versions) {
        List<Pair<String, String>> keyVersions = versions.stream()
                .map(v -> new Pair<>(folder + hashToKey(v.cid), v.version))
                .collect(Collectors.toList());
        try {
            S3AdminRequests.bulkDelete(keyVersions, ZonedDateTime.now(), host, region, accessKeyId, secretKey,
                    b -> ArrayOps.bytesToHex(Hash.sha256(b)),
                    (url, body) -> {
                        try {
                            return HttpUtil.post(url, body);
                        } catch (IOException e) {
                            String msg = e.getMessage();
                            boolean rateLimited = msg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>SlowDown</Code>");
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
            System.out.println("Falling back to parallel individual block deletes...");
            for (BlockVersion version : versions) {
                new Thread(() -> delete(version)).start();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Performing GC on S3 block store...");
        Args a = Args.parse(args);
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
        S3BlockStorage s3 = new S3BlockStorage(config, Cid.decode(a.getArg("ipfs.id")),
                BlockStoreProperties.empty(), transactions, authoriser, meta,
                hasher, new RAMStorage(hasher), new RAMStorage(hasher));
        JdbcIpnsAndSocial rawPointers = new JdbcIpnsAndSocial(database, sqlCommands);
        Supplier<Connection> usageDb = Main.getDBConnector(a, "space-usage-sql-file");
        UsageStore usageStore = new JdbcUsageStore(usageDb, sqlCommands);
        s3.collectGarbage(rawPointers, usageStore, meta, a.getBoolean("s3.versioned-bucket"));
    }

    @Override
    public String toString() {
        return "S3BlockStore[" + bucket + ":" + folder + "]";
    }
}
