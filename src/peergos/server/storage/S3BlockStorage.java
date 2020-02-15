package peergos.server.storage;

import com.amazonaws.*;
import com.amazonaws.auth.*;
import com.amazonaws.client.builder.*;
import com.amazonaws.services.s3.*;
import com.amazonaws.services.s3.model.*;
import peergos.server.corenode.*;
import peergos.server.sql.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multibase.binary.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.io.ipfs.multihash.*;
import io.prometheus.client.Histogram;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.*;

public class S3BlockStorage implements ContentAddressedStorage {

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

    private final Multihash id;
    private final AmazonS3 s3Client;
    private final String bucket, folder;
    private final TransactionStore transactions;
    private final ContentAddressedStorage p2pFallback;

    public S3BlockStorage(S3Config config,
                          Multihash id,
                          TransactionStore transactions,
                          ContentAddressedStorage p2pFallback) {
        this.id = id;
        this.bucket = config.bucket;
        this.folder = config.path.isEmpty() || config.path.endsWith("/") ? config.path : config.path + "/";
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(config.regionEndpoint, config.region))
                .withCredentials(new AWSStaticCredentialsProvider(
                        new BasicAWSCredentials(config.accessKey, config.secretKey)));
        s3Client = builder.build();
        LOG.info("Using S3 Block Storage at " + config.regionEndpoint + ", bucket " + config.bucket + ", path: " + config.path);
        this.transactions = transactions;
        this.p2pFallback = p2pFallback;
    }

    private static String hashToKey(Multihash hash) {
        // To be compatible with IPFS we use the same scheme here, the cid bytes encoded as uppercase base32
        String padded = new Base32().encodeAsString(hash.toBytes());
        int padStart = padded.indexOf("=");
        return padStart > 0 ? padded.substring(0, padStart) : padded;
    }

    private Multihash keyToHash(String key) {
        // To be compatible with IPFS we use the ame scheme here, the cid bytes encoded as uppercase base32
        byte[] decoded = new Base32().decode(key.substring(folder.length()));
        return Cid.cast(decoded);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash hash) {
        if (hash instanceof Cid && ((Cid) hash).codec == Cid.Codec.Raw)
            throw new IllegalStateException("Need to call getRaw if cid is not cbor!");
        return getRaw(hash).thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash hash) {
        Optional<byte[]> fromS3 = map(hash, h -> {
            GetObjectRequest get = new GetObjectRequest(bucket, folder + hashToKey(hash));
            Histogram.Timer readTimer = readTimerLog.labels("read").startTimer();
            try (S3Object res = s3Client.getObject(get); DataInputStream din = new DataInputStream(new BufferedInputStream(res.getObjectContent()))) {
                return Optional.of(Serialize.readFully(din));
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            } finally {
                readTimer.observeDuration();
            }
        }, e -> Optional.empty());
        if (fromS3.isPresent())
            return Futures.of(fromS3);
        return p2pFallback.getRaw(hash);
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

    /** The result of this method is a snapshot of the mutable pointers that is consistent with the blocks store
     * after GC has completed (saved to a file which can be independently backed up).
     *
     * @param pointers
     * @return
     */
    private void collectGarbage(JdbcIpnsAndSocial pointers) throws IOException {
        // TODO: do this more efficiently with a bloom filter, and streaming
        List<Multihash> present = getFiles(Integer.MAX_VALUE);
        List<Multihash> pending = transactions.getOpenTransactionBlocks();
        // This pointers call must happen AFTER the previous two for correctness
        Map<PublicKeyHash, byte[]> allPointers = pointers.getAllEntries();
        BitSet reachable = new BitSet(present.size());
        for (PublicKeyHash writerHash : allPointers.keySet()) {
            byte[] signedRawCas = allPointers.get(writerHash);
            PublicSigningKey writer = getSigningKey(writerHash).join().get();
            byte[] bothHashes = writer.unsignMessage(signedRawCas);
            HashCasPair cas = HashCasPair.fromCbor(CborObject.fromByteArray(bothHashes));
            MaybeMultihash updated = cas.updated;
            if (updated.isPresent())
                markReachable(updated.get(), present, reachable);
        }
        for (Multihash additional : pending) {
            int index = present.indexOf(additional);
            if (index >= 0)
                reachable.set(index);
        }
        // Save pointers snapshot to file
        Path pointerSnapshotFile = Paths.get("pointers-snapshot-" + LocalDateTime.now() + ".txt");
        for (Map.Entry<PublicKeyHash, byte[]> entry : allPointers.entrySet()) {
            Files.write(pointerSnapshotFile, (entry.getKey() + ":" +
                    ArrayOps.bytesToHex(entry.getValue()) + "\n").getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        long deletedBlocks = 0;
        long deletedSize = 0;
        for (int i=0; i < present.size(); i++)
            if (! reachable.get(i)) {
                Multihash hash = present.get(i);
                int size = getSize(hash).join().get();
                deletedBlocks++;
                deletedSize += size;
                delete(hash);
            }
        System.out.println("GC complete. Freed " + deletedBlocks + " blocks totalling " + deletedSize + " bytes");
    }

    private void markReachable(Multihash root, List<Multihash> present, BitSet reachable) {
        int index = present.indexOf(root);
        if (index >= 0)
            reachable.set(index);
        List<Multihash> links = getLinks(root).join();
        for (Multihash link : links) {
            markReachable(link, present, reachable);
        }
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash hash) {
        return Futures.of(map(hash, h -> {
            if (hash.isIdentity()) // Identity hashes are not actually stored explicitly
                return Optional.of(0);
            Histogram.Timer readTimer = readTimerLog.labels("size").startTimer();
            try {
                ObjectMetadata res = s3Client.getObjectMetadata(bucket, folder + hashToKey(hash));
                return Optional.of((int)res.getContentLength());
            } finally {
                readTimer.observeDuration();
            }
        }, e -> Optional.empty()));
    }

    public boolean contains(Multihash key) {
        return map(key, h -> s3Client.doesObjectExist(bucket, folder + hashToKey(h)), e -> false);
    }

    public <T> T map(Multihash hash, Function<Multihash, T> success, Function<Throwable, T> absent) {
        try {
            return success.apply(hash);
        } catch (AmazonServiceException e) {
            /* Caught an AmazonServiceException,
               which means our request made it
               to Amazon S3, but was rejected with an error response
               for some reason.
            */
            if ("NoSuchKey".equals(e.getErrorCode())) {
                Histogram.Timer readTimer = readTimerLog.labels("absent").startTimer();
                readTimer.observeDuration();
                return absent.apply(e);
            }
            LOG.warning("AmazonServiceException: " + e.getMessage());
            LOG.warning("AWS Error Code:   " + e.getErrorCode());
            throw new RuntimeException(e.getMessage(), e);
        } catch (AmazonClientException e) {
            /* Caught an AmazonClientException,
               which means the client encountered
               an internal error while trying to communicate
               with S3, such as not being able to access the network.
            */
            LOG.severe("AmazonClientException: " + e.getMessage());
            LOG.severe("Thrown at:" + e.getCause().toString());
            throw new RuntimeException(e.getMessage(), e);
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
                                                  List<byte[]> signatures,
                                                  List<byte[]> blocks,
                                                  TransactionId tid) {
        return put(owner, writer, signatures, blocks, false, tid);
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     List<byte[]> signatures,
                                                     List<byte[]> blocks,
                                                     TransactionId tid) {
        return put(owner, writer, signatures, blocks, true, tid);
    }

    private CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                   PublicKeyHash writer,
                                                   List<byte[]> signatures,
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
        try {
            Multihash hash = new Multihash(Multihash.Type.sha2_256, Hash.sha256(data));
            Cid cid = new Cid(1, isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, hash.type, hash.getHash());
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(data.length);
            PutObjectRequest put = new PutObjectRequest(bucket, folder + hashToKey(cid), new ByteArrayInputStream(data), metadata);
            transactions.addBlock(cid, tid, owner);
            PutObjectResult putResult = s3Client.putObject(put);
            return cid;
        } catch (AmazonServiceException e) {
            /* Caught an AmazonServiceException,
               which means our request made it
               to Amazon S3, but was rejected with an error response
               for some reason.
            */
            LOG.severe("AmazonServiceException: " + e.getMessage());
            LOG.severe("AWS Error Code:   " + e.getErrorCode());
            throw new RuntimeException(e.getMessage(), e);
        } catch (AmazonClientException e) {
            /* Caught an AmazonClientException,
               which means the client encountered
               an internal error while trying to communicate
               with S3, such as not being able to access the network.
            */
            LOG.severe("AmazonClientException: " + e.getMessage());
            LOG.severe("Thrown at:" + e.getCause().toString());
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            writeTimer.observeDuration();
        }
    }

    private List<Multihash> getFiles(long maxReturned) {
        List<Multihash> results = new ArrayList<>();
        applyToAll(obj -> {
            try {
                results.add(keyToHash(obj.getKey()));
            } catch (Exception e) {
                LOG.warning("Couldn't parse S3 key to Cid: " + obj.getKey());
            }
        }, maxReturned);
        return results;
    }

    private List<String> getFilenames(long maxReturned) {
        List<String> results = new ArrayList<>();
        applyToAll(obj -> results.add(obj.getKey()), maxReturned);
        return results;
    }

    private void applyToAll(Consumer<S3ObjectSummary> processor, long maxObjects) {
        try {
            LOG.log(Level.FINE, "Listing blobs");
            final ListObjectsV2Request req = new ListObjectsV2Request()
                    .withBucketName(bucket)
                    .withPrefix(folder)
                    .withMaxKeys(10_000);
            ListObjectsV2Result result;
            long processedObjects = 0;
            do {
                result = s3Client.listObjectsV2(req);

                for (S3ObjectSummary objectSummary : result.getObjectSummaries()) {
                    if (objectSummary.getKey().endsWith("/")) {
                        LOG.fine(" - " + objectSummary.getKey() + "  " + "(directory)");
                        continue;
                    }
                    LOG.fine(" - " + objectSummary.getKey() + "  " +
                            "(size = " + objectSummary.getSize() +
                            "; modified: " + objectSummary.getLastModified() + ")");
                    processor.accept(objectSummary);
                    processedObjects++;
                    if (processedObjects >= maxObjects)
                        return;
                }
                LOG.log(Level.FINE, "Next Continuation Token : " + result.getNextContinuationToken());
                req.setContinuationToken(result.getNextContinuationToken());
            } while (result.isTruncated());

        } catch (AmazonServiceException ase) {
            /* Caught an AmazonServiceException,
               which means our request made it
               to Amazon S3, but was rejected with an error response
               for some reason.
            */
            LOG.severe("AmazonServiceException: " + ase.getMessage());
            LOG.severe("AWS Error Code:   " + ase.getErrorCode());

        } catch (AmazonClientException ace) {
            /* Caught an AmazonClientException,
               which means the client encountered
               an internal error while trying to communicate
               with S3, such as not being able to access the network.
            */
            LOG.severe("AmazonClientException: " + ace.getMessage());
            LOG.severe("Thrown at:" + ace.getCause().toString());
        }
    }

    public void delete(Multihash hash) {
        DeleteObjectRequest del = new DeleteObjectRequest(bucket, folder + hashToKey(hash));
        s3Client.deleteObject(del);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Performing GC on block store...");
        Args a = Args.parse(args);
        S3Config config = S3Config.build(a);
        boolean usePostgres = a.getBoolean("use-postgres", false);
            SqlSupplier sqlCommands = usePostgres ?
                    new PostgresCommands() :
                    new SqliteCommands();
            Connection database;
            if (usePostgres) {
                String postgresHost = a.getArg("postgres.host");
                int postgresPort = a.getInt("postgres.port", 5432);
                String databaseName = a.getArg("postgres.database", "peergos");
                String postgresUsername = a.getArg("postgres.username");
                String postgresPassword = a.getArg("postgres.password");
                database = Postgres.build(postgresHost, postgresPort, databaseName, postgresUsername, postgresPassword);
            } else {
                database = Sqlite.build(Sqlite.getDbPath(a, "mutable-pointers-file"));
            }
        Connection transactionsDb = usePostgres ?
                    database :
                    Sqlite.build(Sqlite.getDbPath(a, "transactions-sql-file"));
        TransactionStore transactions = JdbcTransactionStore.build(transactionsDb, sqlCommands);
        S3BlockStorage s3 = new S3BlockStorage(config, Cid.decode(a.getArg("ipfs.id")), transactions, new RAMStorage());
        JdbcIpnsAndSocial rawPointers = new JdbcIpnsAndSocial(database, sqlCommands);
        s3.collectGarbage(rawPointers);
    }

    public static void test(String[] args) throws Exception {
        // Use this method to test access to a bucket
        S3Config config = S3Config.build(Args.parse(args));
        System.out.println("Testing S3 bucket: " + config.bucket + " in region " + config.region + " with base dir: " + config.path);
        Multihash id = new Multihash(Multihash.Type.sha2_256, RAMStorage.hash("S3Storage".getBytes()));
        TransactionStore transactions = JdbcTransactionStore.build(Sqlite.build(":memory:"), new SqliteCommands());
        S3BlockStorage s3 = new S3BlockStorage(config, id, transactions, new RAMStorage());

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
