package peergos.server.storage;

import io.prometheus.client.*;
import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.sql.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.storage.*;
import peergos.shared.io.ipfs.multihash.*;
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

    private final Multihash id;
    private final String region, bucket, folder, regionEndpoint, host;
    private final String accessKeyId, secretKey;
    private final BlockStoreProperties props;
    private final TransactionStore transactions;
    private final ContentAddressedStorage p2pFallback;

    public S3BlockStorage(S3Config config,
                          Multihash id,
                          BlockStoreProperties props,
                          TransactionStore transactions,
                          ContentAddressedStorage p2pFallback) {
        this.id = id;
        this.region = config.region;
        this.bucket = config.bucket;
        this.folder = config.path.isEmpty() || config.path.endsWith("/") ? config.path : config.path + "/";
        this.regionEndpoint = config.regionEndpoint;
        this.host = config.getHost();
        this.accessKeyId = config.accessKey;
        this.secretKey = config.secretKey;
        LOG.info("Using S3 Block Storage at " + config.regionEndpoint + ", bucket " + config.bucket + ", path: " + config.path);
        this.props = props;
        this.transactions = transactions;
        this.p2pFallback = p2pFallback;
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    private static String hashToKey(Multihash hash) {
        return DirectS3BlockStore.hashToKey(hash);
    }

    private Multihash keyToHash(String key) {
        return DirectS3BlockStore.keyToHash(key.substring(folder.length()));
    }

    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return Futures.of(props);
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authReads(List<Multihash> blocks) {
        if (blocks.size() > 50)
            throw new IllegalStateException("Too many reads to auth!");
        List<PresignedUrl> res = new ArrayList<>();

        for (Multihash block : blocks) {
            String s3Key = hashToKey(block);
            res.add(S3Request.preSignGet(s3Key, Optional.of(600), ZonedDateTime.now(), host, region, accessKeyId, secretKey));
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
                        ZonedDateTime.now(), host, extraHeaders, region, accessKeyId, secretKey));
            }
            return Futures.of(res);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash hash) {
        if (hash instanceof Cid && ((Cid) hash).codec == Cid.Codec.Raw)
            throw new IllegalStateException("Need to call getRaw if cid is not cbor!");
        return getRaw(hash).thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash hash) {
        String path = folder + hashToKey(hash);
        PresignedUrl getUrl = S3Request.preSignGet(path, Optional.of(600),
                ZonedDateTime.now(), host, region, accessKeyId, secretKey);
        Histogram.Timer readTimer = readTimerLog.labels("read").startTimer();
        try {
            return Futures.of(Optional.of(HttpUtil.get(getUrl)));
        } catch (IOException e) {
            String msg = e.getMessage();
            boolean notFound = msg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>NoSuchKey</Code>");
            if (! notFound) {
                LOG.warning("S3 error reading " + path);
                LOG.log(Level.WARNING, msg, e);
            }

            nonLocalGets.inc();
            return p2pFallback.getRaw(hash);
        } finally {
            readTimer.observeDuration();
        }
    }

    @Override
    public CompletableFuture<List<Multihash>> mirror(PublicKeyHash owner,
                                                     Optional<Multihash> existing,
                                                     Optional<Multihash> updated,
                                                     TransactionId tid) {
        if (updated.isEmpty())
            return Futures.of(Collections.emptyList());
        Multihash newRoot = updated.get();
        boolean isRaw = (newRoot instanceof Cid) && ((Cid) newRoot).codec == Cid.Codec.Raw;
        Optional<byte[]> newVal = p2pFallback.getRaw(newRoot).join();
        if (newVal.isEmpty())
            throw new IllegalStateException("Couldn't retrieve block: " + newRoot);

        byte[] newBlock = newVal.get();
        put(newBlock, isRaw, tid, owner);
        if (isRaw)
            return Futures.of(Collections.singletonList(newRoot));

        List<Multihash> newLinks = CborObject.fromByteArray(newBlock).links();
        List<Multihash> existingLinks = existing.map(h -> get(existing.get()).join())
                .flatMap(copt -> copt.map(CborObject::links))
                .orElse(Collections.emptyList());

        for (int i=0; i < newLinks.size(); i++) {
            Optional<Multihash> existingLink = i < existingLinks.size() ?
                    Optional.of(existingLinks.get(i)) :
                    Optional.empty();
            Optional<Multihash> updatedLink = Optional.of(newLinks.get(i));
            mirror(owner, existingLink, updatedLink, tid);
        }
        return Futures.of(Collections.singletonList(newRoot));
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
    public CompletableFuture<Boolean> gc() {
        return Futures.errored(new IllegalStateException("S3 doesn't implement GC!"));
    }

    @Override
    public List<Multihash> getOpenTransactionBlocks() {
        return transactions.getOpenTransactionBlocks();
    }

    private void collectGarbage(JdbcIpnsAndSocial pointers) {
        GarbageCollector.collect(this, pointers, this::savePointerSnapshot);
    }

    private CompletableFuture<Boolean> savePointerSnapshot(Stream<Map.Entry<PublicKeyHash, byte[]>> pointers) {
        CompletableFuture<Boolean> res = new CompletableFuture<>();
        // Save pointers snapshot to file
        Path pointerSnapshotFile = Paths.get("pointers-snapshot-" + LocalDateTime.now() + ".txt");
        pointers.forEach(entry -> {
            try {
                Files.write(pointerSnapshotFile, (entry.getKey() + ":" +
                        ArrayOps.bytesToHex(entry.getValue()) + "\n").getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                res.completeExceptionally(e);
                throw new RuntimeException(e);
            }
        });
        return res;
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash hash) {
        return getSize(hash, 3, 100);
    }

    private CompletableFuture<Optional<Integer>> getSize(Multihash hash, int retries, long sleepMillis) {
        if (hash.isIdentity()) // Identity hashes are not actually stored explicitly
            return Futures.of(Optional.of(0));
        Histogram.Timer readTimer = readTimerLog.labels("size").startTimer();
        try {
            PresignedUrl headUrl = S3Request.preSignHead(folder + hashToKey(hash), Optional.of(60),
                    ZonedDateTime.now(), host, region, accessKeyId, secretKey);
            Map<String, List<String>> headRes = HttpUtil.head(headUrl);
            long size = Long.parseLong(headRes.get("Content-Length").get(0));
            return Futures.of(Optional.of((int)size));
        } catch (Exception e) {
            if (e.getMessage().contains("HTTP 503")) {
                LOG.info("Sleeping for "+sleepMillis+" because of http 503 from S3 (you are being rate limited) getting size of " + hash + " ...");
                try {Thread.sleep(sleepMillis);} catch (InterruptedException f) {}
                if (retries <= 0)
                    throw new RuntimeException(e);
                return getSize(hash, retries - 1, sleepMillis * 2);
            } else {
                LOG.log(Level.SEVERE, e.getMessage(), e);
                return Futures.of(Optional.empty());
            }
        } finally {
            readTimer.observeDuration();
        }
    }

    public boolean contains(Multihash hash) {
        try {
            PresignedUrl headUrl = S3Request.preSignHead(folder + hashToKey(hash), Optional.of(60),
                    ZonedDateTime.now(), host, region, accessKeyId, secretKey);
            Map<String, List<String>> headRes = HttpUtil.head(headUrl);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public CompletableFuture<Multihash> id() {
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
    public CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                  PublicKeyHash writer,
                                                  List<byte[]> signedHashes,
                                                  List<byte[]> blocks,
                                                  TransactionId tid) {
        return put(owner, blocks, false, tid);
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     List<byte[]> signedHashes,
                                                     List<byte[]> blocks,
                                                     TransactionId tid,
                                                     ProgressConsumer<Long> progressConsumer) {
        return put(owner, blocks, true, tid);
    }

    private CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
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
    public Multihash put(byte[] data, boolean isRaw, TransactionId tid, PublicKeyHash owner) {
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
                    ZonedDateTime.now(), host, extraHeaders, region, accessKeyId, secretKey);
            HttpUtil.put(putUrl, data);
            return cid;
        } catch (IOException e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            writeTimer.observeDuration();
        }
    }

    public Stream<Multihash> getAllBlockHashes() {
        // todo make this actually streaming
        return getFiles(Long.MAX_VALUE).stream();
    }

    private List<Multihash> getFiles(long maxReturned) {
        List<Multihash> results = new ArrayList<>();
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

    private void applyToAll(Consumer<S3Request.ObjectMetadata> processor, long maxObjects) {
        try {
            Optional<String> continuationToken = Optional.empty();
            S3Request.ListObjectsReply result;
            long processedObjects = 0;
            do {
                result = S3Request.listObjects(folder, 1_000, continuationToken,
                        ZonedDateTime.now(), host, region, accessKeyId, secretKey, url -> {
                            try {
                                return HttpUtil.get(url);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });

                for (S3Request.ObjectMetadata objectSummary : result.objects) {
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
            PresignedUrl delUrl = S3Request.preSignDelete(folder + hashToKey(hash), ZonedDateTime.now(), host,
                    region, accessKeyId, secretKey);
            HttpUtil.delete(delUrl);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void bulkDelete(List<Multihash> hash) {
        try {
            List<String> keys = hash.stream()
                    .map(h -> folder + hashToKey(h))
                    .collect(Collectors.toList());
            S3Request.bulkDelete(keys, ZonedDateTime.now(), host, region, accessKeyId, secretKey,
                    b -> ArrayOps.bytesToHex(Hash.sha256(b)),
                    (url, body) -> {
                        try {
                            return HttpUtil.post(url, body);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Performing GC on block store...");
        Args a = Args.parse(args);
        S3Config config = S3Config.build(a);
        boolean usePostgres = a.getBoolean("use-postgres", false);
        SqlSupplier sqlCommands = usePostgres ?
                new PostgresCommands() :
                new SqliteCommands();
        Supplier<Connection> database = Main.getDBConnector(a, "mutable-pointers-file");
        Supplier<Connection> transactionsDb = Main.getDBConnector(a, "transactions-sql-file");
        TransactionStore transactions = JdbcTransactionStore.build(transactionsDb, sqlCommands);
        S3BlockStorage s3 = new S3BlockStorage(config, Cid.decode(a.getArg("ipfs.id")), BlockStoreProperties.empty(), transactions, new RAMStorage());
        JdbcIpnsAndSocial rawPointers = new JdbcIpnsAndSocial(database, sqlCommands);
        s3.collectGarbage(rawPointers);
    }

    public static void test(String[] args) throws Exception {
        // Use this method to test access to a bucket
        S3Config config = S3Config.build(Args.parse(args));
        System.out.println("Testing S3 bucket: " + config.bucket + " in region " + config.region + " with base dir: " + config.path);
        Multihash id = new Multihash(Multihash.Type.sha2_256, RAMStorage.hash("S3Storage".getBytes()));
        TransactionStore transactions = JdbcTransactionStore.build(Main.buildEphemeralSqlite(), new SqliteCommands());
        S3BlockStorage s3 = new S3BlockStorage(config, id, BlockStoreProperties.empty(), transactions, new RAMStorage());

        System.out.println("***** Testing ls and read");
        System.out.println("Testing ls...");
        List<Multihash> files = s3.getFiles(1000);
        System.out.println("Success! found " + files.size());

        System.out.println("Testing read...");
        byte[] data = s3.getRaw(files.get(0)).join().get();
        System.out.println("Success: read blob of size " + data.length);

        System.out.println("Testing write...");
        byte[] uploadData = new byte[10 * 1024];
        new Random().nextBytes(uploadData);
        PublicKeyHash owner = PublicKeyHash.NULL;
        TransactionId tid = s3.startTransaction(owner).join();
        Multihash put = s3.put(uploadData, true, tid, owner);
        System.out.println("Success!");

        System.out.println("Testing delete...");
        s3.delete(put);
        System.out.println("Success!");
    }

    @Override
    public String toString() {
        return "S3BlockStore[" + bucket + ":" + folder + "]";
    }
}
