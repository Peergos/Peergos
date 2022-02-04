package peergos.server.storage;

import io.prometheus.client.*;
import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.space.*;
import peergos.server.sql.*;
import peergos.server.storage.auth.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.storage.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
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
    private static final Counter nonLocalGets = Counter.build()
            .name("p2p_block_gets")
            .help("Number of block gets which fell back to p2p retrieval")
            .register();

    private final Cid id, p2pGetId;
    private final String region, bucket, folder, regionEndpoint, host;
    private final String accessKeyId, secretKey;
    private final BlockStoreProperties props;
    private final TransactionStore transactions;
    private final BlockRequestAuthoriser authoriser;
    private final Hasher hasher;
    private final DeletableContentAddressedStorage p2pFallback;

    public S3BlockStorage(S3Config config,
                          Cid id,
                          BlockStoreProperties props,
                          TransactionStore transactions,
                          BlockRequestAuthoriser authoriser,
                          Hasher hasher,
                          DeletableContentAddressedStorage p2pFallback) {
        this.id = id;
        this.p2pGetId = p2pFallback.id().join();
        this.region = config.region;
        this.bucket = config.bucket;
        this.regionEndpoint = config.regionEndpoint;
        this.host = config.getHost();
        this.folder = (host.contains("localhost") ? bucket + "/" : "") + (config.path.isEmpty() || config.path.endsWith("/") ? config.path : config.path + "/");
        this.accessKeyId = config.accessKey;
        this.secretKey = config.secretKey;
        LOG.info("Using S3 Block Storage at " + config.regionEndpoint + ", bucket " + config.bucket + ", path: " + config.path);
        this.props = props;
        this.transactions = transactions;
        this.authoriser = authoriser;
        this.hasher = hasher;
        this.p2pFallback = p2pFallback;
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
        if (blocks.size() > 50)
            throw new IllegalStateException("Too many reads to auth!");
        List<PresignedUrl> res = new ArrayList<>();

        if (! blocks.stream().allMatch(c -> hasBlock(c.hash)))
            return Futures.errored(new IllegalStateException("Blocks not present locally"));

        // retrieve all blocks and verify BATs in parallel
        List<CompletableFuture<Optional<byte[]>>> data = blocks.stream()
                .parallel()
                .map(b -> getRaw(b.hash, b.bat, id, hasher))
                .collect(Collectors.toList());

        for (MirrorCap block : blocks) {
            String s3Key = hashToKey(block.hash);
            res.add(S3Request.preSignGet(s3Key, Optional.of(600), S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, accessKeyId, secretKey, hasher).join());
        }
        for (CompletableFuture<Optional<byte[]>> fut : data) {
            fut.join(); // Any invalids BATs will cause this to throw
        }
        return Futures.of(res);
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authWrites(PublicKeyHash owner,
                                                            PublicKeyHash writerHash,
                                                            List<byte[]> signedHashes,
                                                            List<Integer> blockSizes,
                                                            boolean isRaw,
                                                            TransactionId tid) {
        try {
            if (signedHashes.size() > 50)
                throw new IllegalStateException("Too many writes to auth!");
            if (blockSizes.size() != signedHashes.size())
                throw new IllegalStateException("Number of sizes doesn't match number of signed hashes!");
            PublicSigningKey writer = getSigningKey(writerHash).get().get();
            List<Pair<Multihash, Integer>> blockProps = new ArrayList<>();
            for (int i=0; i < signedHashes.size(); i++) {
                Cid.Codec codec = isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor;
                Cid cid = new Cid(1, codec, Multihash.Type.sha2_256, writer.unsignMessage(signedHashes.get(i)));
                blockProps.add(new Pair<>(cid, blockSizes.get(i)));
            }
            List<PresignedUrl> res = new ArrayList<>();
            for (Pair<Multihash, Integer> props : blockProps) {
                if (props.left.type != Multihash.Type.sha2_256)
                    throw new IllegalStateException("Can only pre-auth writes of sha256 hashed blocks!");
                transactions.addBlock(props.left, tid, owner);
                String s3Key = hashToKey(props.left);
                String contentSha256 = ArrayOps.bytesToHex(props.left.getHash());
                Map<String, String> extraHeaders = new LinkedHashMap<>();
                extraHeaders.put("Content-Type", "application/octet-stream");
                res.add(S3Request.preSignPut(s3Key, props.right, contentSha256, false,
                        S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, extraHeaders, region, accessKeyId, secretKey, hasher).join());
            }
            return Futures.of(res);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid object, Optional<BatWithId> bat) {
        return getRaw(object, bat, id, hasher)
                .thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid hash, String auth) {
        if (hash.isRaw())
            throw new IllegalStateException("Need to call getRaw if cid is not cbor!");
        return getRaw(hash, auth).thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid object, Optional<BatWithId> bat) {
        return getRaw(object, bat, id, hasher);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h) {
        if (bat.isEmpty())
            return getRaw(hash, "");
        return bat.get().bat.generateAuth(hash, ourId, 300, S3Request.currentDatetime(), bat.get().id, h)
                .thenApply(BlockAuth::encode)
                .thenCompose(auth -> getRaw(hash, auth, true, bat));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, String auth) {
        return getRaw(hash, auth, true, Optional.empty());
    }

    private CompletableFuture<Optional<byte[]>> getRaw(Cid hash, String auth, boolean enforceAuth, Optional<BatWithId> bat) {
        String path = folder + hashToKey(hash);
        PresignedUrl getUrl = S3Request.preSignGet(path, Optional.of(600),
                S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, accessKeyId, secretKey, hasher).join();
        Histogram.Timer readTimer = readTimerLog.labels("read").startTimer();
        try {
            byte[] block = HttpUtil.get(getUrl);
            // validate auth, unless this is an internal query
            if (enforceAuth && ! authoriser.allowRead(hash, block, id, auth).join())
                throw new IllegalStateException("Unauthorised!");
            return Futures.of(Optional.of(block));
        } catch (IOException e) {
            String msg = e.getMessage();
            boolean rateLimited = msg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>SlowDown</Code>");
            if (rateLimited) {
                throw new RateLimitException();
            }
            boolean notFound = msg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>NoSuchKey</Code>");
            if (! notFound) {
                LOG.warning("S3 error reading " + path);
                LOG.log(Level.WARNING, msg, e);
            }

            nonLocalGets.inc();
            if (p2pGetId.equals(id))
                return p2pFallback.getRaw(hash, auth);
            return p2pFallback.getRaw(hash, bat); // recalculate auth when the fallback node has a different node id
        } finally {
            readTimer.observeDuration();
        }
    }

    @Override
    public boolean hasBlock(Cid hash) {
        try {
            PresignedUrl headUrl = S3Request.preSignHead(folder + hashToKey(hash), Optional.of(60),
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, accessKeyId, secretKey, hasher).join();
            Map<String, List<String>> headRes = HttpUtil.head(headUrl);
            return true;
        } catch (IOException e) {
            String msg = e.getMessage();
            boolean rateLimited = msg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>SlowDown</Code>");
            if (rateLimited) {
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

        if (contains(newRoot))
            return Futures.of(Collections.singletonList(newRoot));
        Optional<byte[]> newBlock = p2pFallback.getRaw(newRoot, mirrorBat, id, hasher).join();
        if (newBlock.isEmpty())
            throw new IllegalStateException("Couldn't retrieve block: " + newRoot);
        put(newBlock.get(), false, tid, owner);
        if (newRoot.isRaw())
            return Futures.of(Collections.singletonList(newRoot));

        List<Multihash> newLinks = CborObject.fromByteArray(newBlock.get()).links();
        List<Multihash> existingLinks = existing.map(h -> get(h, mirrorBat, id, hasher).join())
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

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat) {
        if (! hasBlock(root))
            return Futures.errored(new IllegalStateException("Champ root not present locally: " + root));
        return getChampLookup(root, champKey, bat, hasher);
    }

    @Override
    public List<Multihash> getOpenTransactionBlocks() {
        return transactions.getOpenTransactionBlocks();
    }

    private void collectGarbage(JdbcIpnsAndSocial pointers, UsageStore usage) {
        GarbageCollector.collect(this, pointers, usage, this::savePointerSnapshot);
    }

    private CompletableFuture<Boolean> savePointerSnapshot(Stream<Map.Entry<PublicKeyHash, byte[]>> pointers) {
        // Save pointers snapshot to file
        Path pointerSnapshotFile = Paths.get("pointers-snapshot-" + LocalDateTime.now() + ".txt");
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
        return getWithBackoff(() -> getSizeWithoutRetry(hash));
    }

    private CompletableFuture<Optional<Integer>> getSizeWithoutRetry(Multihash hash) {
        if (hash.isIdentity()) // Identity hashes are not actually stored explicitly
            return Futures.of(Optional.of(0));
        Histogram.Timer readTimer = readTimerLog.labels("size").startTimer();
        try {
            PresignedUrl headUrl = S3Request.preSignHead(folder + hashToKey(hash), Optional.of(60),
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, accessKeyId, secretKey, hasher).join();
            Map<String, List<String>> headRes = HttpUtil.head(headUrl);
            long size = Long.parseLong(headRes.get("Content-Length").get(0));
            return Futures.of(Optional.of((int)size));
        } catch (IOException e) {
            String msg = e.getMessage();
            boolean rateLimited = msg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>SlowDown</Code>");
            if (rateLimited) {
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
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, accessKeyId, secretKey, hasher).join();
            Map<String, List<String>> headRes = HttpUtil.head(headUrl);
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

    private CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                             List<byte[]> blocks,
                                             boolean isRaw,
                                             TransactionId tid) {
        return CompletableFuture.completedFuture(blocks.stream()
                .map(b -> put(b, isRaw, tid, owner))
                .collect(Collectors.toList()));
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
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, extraHeaders, region, accessKeyId, secretKey, hasher).join();
            HttpUtil.put(putUrl, data);
            return cid;
        } catch (IOException e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            writeTimer.observeDuration();
        }
    }

    @Override
    public CompletableFuture<List<Cid>> getLinks(Cid root, String auth) {
        if (root.isRaw())
            return CompletableFuture.completedFuture(Collections.emptyList());
        return getRaw(root, "", false, Optional.empty()).thenApply(opt -> opt
                .map(CborObject::fromByteArray)
                .map(cbor -> cbor.links().stream().map(c -> (Cid) c).collect(Collectors.toList()))
                .orElse(Collections.emptyList())
        );
    }

    @Override
    public CompletableFuture<Pair<Integer, List<Cid>>> getLinksAndSize(Cid block, String auth) {
        if (block.isRaw()) {
            return getSize(block)
                    .thenApply(s -> new Pair<>(s.orElse(0), Collections.emptyList()));
        }
        Optional<byte[]> data = getRaw(block, "", false, Optional.empty()).join();
        List<Cid> links = data.map(CborObject::fromByteArray)
                .map(cbor -> cbor.links().stream().map(c -> (Cid) c).collect(Collectors.toList()))
                .orElse(Collections.emptyList());
        int size = data.map(a -> a.length).orElse(0);
        return Futures.of(new Pair<>(size, links));
    }

    public Stream<Cid> getAllBlockHashes() {
        // todo make this actually streaming
        return getFiles(Long.MAX_VALUE).stream();
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
                        }, S3AdminRequests.builder::get, hasher);

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

    public void delete(Multihash hash) {
        try {
            PresignedUrl delUrl = S3Request.preSignDelete(folder + hashToKey(hash), S3AdminRequests.asAwsDate(ZonedDateTime.now()), host,
                    region, accessKeyId, secretKey, hasher).join();
            HttpUtil.delete(delUrl);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void bulkDelete(List<Multihash> hash) {
        List<String> keys = hash.stream()
                .map(h -> folder + hashToKey(h))
                .collect(Collectors.toList());
        S3AdminRequests.bulkDelete(keys, ZonedDateTime.now(), host, region, accessKeyId, secretKey,
                b -> ArrayOps.bytesToHex(Hash.sha256(b)),
                (url, body) -> {
                    try {
                        return HttpUtil.post(url, body);
                    } catch (IOException e) {
                        String msg = e.getMessage();
                        boolean rateLimited = msg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>SlowDown</Code>");
                        if (rateLimited) {
                            throw new RateLimitException();
                        }
                        throw new RuntimeException(e);
                    }
                }, S3AdminRequests.builder::get, hasher);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Performing GC on S3 block store...");
        Args a = Args.parse(args);
        Crypto crypto = Main.initCrypto();
        Hasher hasher = crypto.hasher;
        S3Config config = S3Config.build(a);
        boolean usePostgres = a.getBoolean("use-postgres", false);
        SqlSupplier sqlCommands = usePostgres ?
                new PostgresCommands() :
                new SqliteCommands();
        Supplier<Connection> database = Main.getDBConnector(a, "mutable-pointers-file");
        Supplier<Connection> transactionsDb = Main.getDBConnector(a, "transactions-sql-file");
        TransactionStore transactions = JdbcTransactionStore.build(transactionsDb, sqlCommands);
        BlockRequestAuthoriser authoriser = (c, b, s, auth) -> Futures.of(true);
        S3BlockStorage s3 = new S3BlockStorage(config, Cid.decode(a.getArg("ipfs.id")),
                BlockStoreProperties.empty(), transactions, authoriser, hasher, new RAMStorage(hasher));
        JdbcIpnsAndSocial rawPointers = new JdbcIpnsAndSocial(database, sqlCommands);
        Supplier<Connection> usageDb = Main.getDBConnector(a, "space-usage-sql-file");
        UsageStore usageStore = new JdbcUsageStore(usageDb, sqlCommands);
        s3.collectGarbage(rawPointers, usageStore);
    }

    @Override
    public String toString() {
        return "S3BlockStore[" + bucket + ":" + folder + "]";
    }
}
