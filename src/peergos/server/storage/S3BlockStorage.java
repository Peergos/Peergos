package peergos.server.storage;

import com.amazonaws.*;
import com.amazonaws.auth.*;
import com.amazonaws.client.builder.*;
import com.amazonaws.services.s3.*;
import com.amazonaws.services.s3.model.*;
import peergos.server.corenode.*;
import peergos.server.sql.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multibase.binary.*;
import peergos.shared.storage.*;
import peergos.shared.io.ipfs.multihash.*;
import io.prometheus.client.Histogram;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
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

    public S3BlockStorage(S3Config config, Multihash id, TransactionStore transactions) {
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
        return Futures.of(map(hash, h -> {
            GetObjectRequest get = new GetObjectRequest(bucket, folder + hashToKey(hash));
            Histogram.Timer readTimer = readTimerLog.labels("read").startTimer();
            try (S3Object res = s3Client.getObject(get); DataInputStream din = new DataInputStream(new BufferedInputStream(res.getObjectContent()))) {
                return Optional.of(Serialize.readFully(din));
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            } finally {
                readTimer.observeDuration();
            }
        }, e -> Optional.empty()));
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

    private void collectGarbage(JdbcIpnsAndSocial pointers) {
        // TODO: do this more efficiently with a bloom filter, and streaming
        List<Multihash> present = getFiles(Integer.MAX_VALUE);
        List<Multihash> pending = transactions.getOpenTransactionBlocks();
        // This pointers call must happen AFTER the previous two for correctness
        List<Multihash> currentRoots = pointers.getAllTargets(this);
        BitSet reachable = new BitSet(present.size());
        for (Multihash root : currentRoots) {
            markReachable(root, present, reachable);
        }
        for (Multihash additional : pending) {
            int index = present.indexOf(additional);
            if (index >= 0)
                reachable.set(index);
        }
        for (int i=0; i < present.size(); i++)
            if (! reachable.get(i))
                delete(present.get(i));
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
        applyToAll(obj -> results.add(keyToHash(obj.getKey())), maxReturned);
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
        String b32 = "AFKQBEBABZVZ5Q7UPEQUXHIR64UR7J3KAL75JCSBC5WPCKAJXLKPSIAKW6REEQAFGC5IDWOYC46X3IXL7HRDPF6VSCEPENWMATFJUCVU57XAO2XD2EKD7OEVXEUM2VP7XHGXD2MKXKHWPCTAUM57NUYWS3FRYWICVFSPWVAYITJLBWL4VXD5SJ2DVISS2AY4ZRW232HZNNNWE4ZYFF6CC7TN27RNZVBAJV2GFZAK3PNOIPK7ENG7J67IMQEKJTTCJORVOEBDYUOFZ6RL3JCEQIRH6NCSQYTITKYQXXFOLJO4FLOD4ZHGTI34JJGQGQYS5VCL5RHSVWFCYNU5ZH54LXE6DYNCKJYSXI3TIZRQEK5ADGGD4WE55S53EJCI5VMDKPRNILMHU2VMEGH67KWH7BUQ5ZR4GZE34RS6I33ZA7ATNRL5SUIJXG6ZT2MXGT2NHFXRULUNC5N7LYHV452FOBU572YNVFW4BBWD4AC6PVZDQNUXKUAVNNW7IJW45XHCVZQP2LODBNMG5YTS627TXBQTHETK32VJM33BYFYJ7JWHHB7GPS24MRDPARVLIVFW7FX5X5RC56U754IKNHBBURGPVM5F4YRG64KNG3T2EWGPII76BQO3KF67S4ZXGI5HMJKLFBQTC7AR5LSIFE2IF33ZZ7NPVXNP2K37VODGFUWDVZXXHXKR5TH75U6IWYT43XM5U4M2L7RXXGJC5RC5C2FXLYEBQXPZZCIKSLP3Y5VHBXDSIO5277LLOHFYSZRWHGYZXWGEHRVRQAL64Y6ARBTBQNRCALASOB6MMVUCB5BMT2STS3MHR6M7RSVFBWVVEUWHXLPSGFOUOJG2F3VET4MGZJIEJSQVQLLQ5PFA272URQ2OO7YYV5XP7OBLXQKB3BN54YQXOGAS52RFI4V55VVCGVK5VOBGLS3HBSU5CCSKDZXRCLVHD5CAEZLITV64BY5KBDQ3WU3IJ4XY3TMVFNSSTC2HABMTOJJZWMKXWWS4WBS3HZYOBZKQ45DC4GGHEE4PD6WKOEWGPXG674RPZPJWJU6DVFZH2XKK5LN5CE2H7HWOW2K342HSVD5A6WEVWZSCOU5VSSQT23VJAB3KY3QE25OTVVE7A7DRBF23N3RHUUVUADDD64BZI775IGMRCPNPWYI2L6EJH2HOWDJ3JRQKHH6G2AZI5JNNSM66EGB6MAPATFOKIQUC2O2UI4SP7XYY2TSX3ZVHO57FCZEG7YONNPJRHGKM7JRUIH3OPMJ34RKO2P5NCXN6MUOKN6P5LGFQLOK2SZXQB46K42JRULEX3MNATBRDM6LC5XQOAWYNBQG2AVSMJMTTICDXW7WDNCRMLU3ULLPUJVK5K7AGUFLA3FST5ZWAYWM7VEKKHLBG27NQOUVN4KGP7JNZMVLRRJU5ZAFIS76I2N3AICGR7JKN75HWTSMED5D6DNE5GGT6MSN466O2HRDCJ4VH6H4QNDRBG6ZKX57ODNYZJ7XEZJQLIOM5R72SGY77PTK6YPXY7GPWMQ2IJB5Y55OIARXQDCOQAKYIKXI44GBXVJMGUFKFWVEZ2ARWFT7ZLY4N6KLZMWO4A5KZHKXQP7J3QWQGMEAB4J5QIZNWCO3BJ7XTR6FLNBQNOSBY4SHAG32HBTRXORPOVV3SVKF7CHHJU4S5WMDTUMC2MT2KN2NKJY4V5QZOBCYMOBTGPL2R5QEMHTPZ46PJHO6OWAYXO3XJXHBCCLXYEXAQVFMPE5UIKBJ7KWCDKTSPPELAZ2IYLRL7HPUYXTDGK6X2M5P42X4GGK4VTQDKWAZ62JIWC7BEZ54FYIA2OOKEVXO2W6A77LN3JXW2TTJOHB73OZO4YLDB3JMODKGBEJZYEEGWDLCOFW5MC3BY7CBBGNHIPWOYOEPUBGGXIVVQTIBZOLRINNDXIYAXHKZ2TADIHQALFWZRIWQLL5OXAIBOQCO4IZXJ3MU3SW5V4E7OU46OJB7LR5FEL3462EHP24GEWLDKVHVTTKT4UJLTACBBBHVUWF4X56JFWAXWQQZUMEHOL4ANO4IRRMMHQSKXCY6BUSVRHUPUCQEY7KNE2WDMILYR6HM5E5UNQUHWMXBDTY5ZS42P32NTSM22VFYX3A6J56C2QKAOHTD27XDDQGDX7QKHJFKOGC55LIPNKV4RQX44AINMCJQYPVVPQL52WYMGEMLQAVTJYOC7AHPVMET3BF7EYPMUW6OHYECBF327C6PYDU5EH2KJCPLCCDRCBHOG5OB7NVE7O7JY4J67U44KBOLWWCQILM2H32J2RRTIAB6UAXRVWXAII2VIHR5HMAIXOXKXXN5WRMWEKQZDNUZRAVK6HMF6COWBXDVBNAKRNRGJHIMD7FPZFPEGYTGE5UBNISC5F37F32XMBVV22ZOHKYKRPSTCN6FVQBIMGK4DZOQPUPJQJ3E3WRR665Y4N3ZPFWFFESUHFJGSSW4LNOIYC7C7ZCD7IUJTZ3D32LVOOKLLFBJQBNOIAYVOWZJS2FPTRB4TLF6WBCFI3FWM42YJN564XBR2BLSQZVOWUM5YM2PYHCPRG5VPUXAPYKTRNQIS4COV5SB65AYPWX3BBVGGICZZUCA3FTLB3J6HDNWYGO6NPTG5AGX5P7SRBMZRHH3JRK6ESPJDUYX5L24UUFIFAED25SGENLSIVA656W3AINQFYDGQCPCKKBXZBLRQTO74ZLDC7ZNZAUADUT2T36LUTKUTEQSZTTP2WVMKT3HLNTYQQMGIAVZWRSJJYTVQCPG7XMDCSSPTHCUGU7XHGN42AHR3D42FHGINNLEY3QJTENOTCVOWVZQOQVPGFKMVRJDVV4PX57WXOM3T2JZ34CFXYP6GHMHJ57LOY77A5W4WFWRU3VGSZ4A6HBKGBR6TCA2HHA3FGSZ33U2IP6UCBGETFNKDQ7FS43SDSJU3K6DP4L2R2OQ6ZIZPUWUO2IIZW2GYM7GAOO5PFB7VFJHLIX2CKCNDL4LUOSI2HZ7AG3IK2DEWY64VJM6OK3PBE2MNYL53E5LMOAZ3R2YCO5T6PEANYIEC2BKK23XHGJGL4Z5F3PDEP4FZFNE2THBRHMBTCQP2NRQRJ4IRDD6HESAF47JZC4EHRIJT6MGCS3MQFLT3TWQCXVCIHSC6VX7FRKCNRT2HEGSRWSLCOGVSRNBKIISSKWXK6ALNRW4YNTFGEN47GPFGUVIXUVCGSAB5BZFFIKHWHRV3RGL4GD5YDLNDBIRYFEUNCLD4CC76VXYOCOXJ6BJ3VW7INAKST7E6GAOTVY6FUD42RQ6RULGNRSUOOF5HB34VSLQ4WNKRUSLFSPNUT5NFP25S2MDG6XGIT3565SVDGXR6DPRCZTHZGH2LSPBYSF3IBYHK3VNY6EPYMXOOVLTE6V3B4JB7A7KTYSB23MZLT3YALOHFEU4T6E2QMBMQQB5BF5PRWEPVPMCHCW2GOI4KCJY5BI2UKV62HIJ5RHZ7WC4YIURV4BS3JZSRPYXH75CAED5BKA7R235LLSFU4LNUPJ7PIG7ZO5GQCOEI52WIVTHA6VT2DS37IGZRHDLMPC5TWDU7CLOSS24LOOFZ7I2JE25V7PXMCEPWOXGF47E5PF4KZATFCXDHQ54K4GMEDIRNVCFNKFZUBVDFTW2LCARRMYDYIETVXLXHSOWRPDGEU333FTYHNQ2WQC2B774MKOD7C776KYZZFZZUJG3P5MJJ4E6FB7JIRZDD6OPNZWUBD53FZKTV6BE3EWO3P7KNESYBHCUUCPKC4336DFOPJFQSEPJPSBXZDZIZHNXOPMBEP4WDUUIDMGTGM2UOHPK64I42Q6ZE6RRFBV6NHUJAQ2UL7IMBAZ362366IRA7R4NRPUCOW2O7F2GN2YB6F6AHKYPBOTCRR6Q6346WUDYKTWYAZKUB57JWHXUBYJSASXXRN5RFTKTI7DHFINDRZAAI4LHSVMY4HA2FGOYXJ2G6T2WGH4HTG2YCIP35YPVOYYOUPEBUBN7CZG7ASUIVHXXN7QG2IFZT5CA3JEHI7CAAYAXRVHSIKCQHWNRKGTSER7KM6ZPGQ3RPTYAEXI3ENYVF5AP5KIEJR7WJQWJ364TDZXWQYY7NYKDCSANWJ7WT2VTAXBH3BYJ64MPXRIDO3LWEAZ2BZ2AXFXS34XOWX4TCMEM2VDGFMZ2RIMT2IUOZNMD3UJE4GY6ARMCP7ISQ3PXASWFLVZJFIRAFJPK5BNP62XIM34EXQTGQJXNHYKBL7KOEREVXXRDYE3YFPUOSO7MA5Q2LOTQV2OGVDP5TQKZVCFG733TOXD3CHHLO55JPQIOLI5HCCUP7X4HW3UOBKYZX43BOPNFNNQ6EY3MPDNPRYGTVS7E4AETRO4624UEU3UTTZXN5ZE4HFMTTLSTXRTKD6VVDN6BNCRNPVHFRKUBJFRQL2HMJVBKGUNUA4TAXBHGB4PFOAOYRQT6JKCTEQMEFTDXMIEZJ6QFEAD4OGTMNWKYSFROSS5NVMBD7MC4HJJO2P32Z6HIHH5UWEJN43V7RI2RIG6CVMUAB52ETRZUWIRUJE37CM6AWGHDTWESFJMOZ7G4PTLMT347OCOAGPAYMQJOEO55V3CCOSO5J5VW3VRAE3NEFBNOFLKOWQK2Y5DZW2RQ7PJHVXDEDKZF4MFRB7RWVRZHYHMAH4A5D66VCW3D2ACIHKT6A5KGWRONNUPW7LS7MNPCIZEBD2IDFOL6OBNM4AWTBUHDPHH5BEEDDUX4VCBVIZYVKRFGZP3R6ZXTJBBZXCKMYWVXBC2LJANN62NU4E4ODE4LDEJRTZZ3ZJ4K5W6FLGZNISAXCUPEKRBS2XKTPKS4RBQTWXSROMNX2NNHQACVLWL77WNQFV4F5JAOCDGLUITQPZHSZX3COFZQ2D32LEQPB2EKEEITJOJO26KPUZ6VJQITOFHKYR4PHMS5XG2F5OFFHYWYJRZXD5AOWPWJWR6CKMEH3VKF6XGOGH55N7ZEWYG73L5MOB7PEQP5GEOSHMHLFMR3YLTASD7CJOUBUYLVJNCJHZAUVEAQLHRKJ6LNWLH24YVHH25CR7G75YLP2X2UUEYSB6NIWSHQDSOUNIKX3FIFCX6PEU63FXV6IPVZVYLFZ2FNQ5GFIPS5XWEE25DUPUDA3VD6AZIVOX2KVMZ5TOS2VMITW53RDVGOOX6BWJGKHPTZTCAWE2MQ2UICOHYG4B5C5P2LBT5ET7PP3YLCWYJWJHPKXQFKVPOVSJIETOUQF5WGIZJRXY5C3OUUZILFEUW3TSLHARNHFQMMETAQJXUKHRQYYGTDTVKKUXD2N54PIKDEP4GOEV6RXCUFCKRTYHM2RJYEEIXU7WGBI2TGJDJX647LTYRU7EVXSYCLK5HWLOZHY2OHE23MZW32NILGN4352UBFYKUGJ7QJEADMRHN2YAFR32UNRXRDYJGVDLWV4EECIP5EVDOOPJOJLUQXRC762AFBSRERT3CJASED5DEVZ2T5MMLB7WVAN33CWQXIDMS5NXFLDW6Q6OE3KMMB65NAV3ERYWQPUPFH4Z5JOVRSO6JJTAG2AB6W7M5BP6AHB4YTDTT4BR7KXOKLVKLQCFQAOJEQC4O63PJQ4FDKD6N5Q3ZBM3EXTRU7X2QMVBEWLAUTCJQQPVWRHUR2MDKS2MDR7UCB6MAFYRTYQJHW54KKITHSOVABJ77CKQH4SKEZ6PMBVPWGHFF5XJHUIJZLFDOFQZB3Z4MLJMYIZF6FDRBRONATKULMGS2QZRUQSGY5PPIJHIPDDCHX5FLT6ZAAYVPCSMV5O7FUHOCB6DSYIDF53QJ4XCS5YJ5FV4UVMYH22X4EW5S6WLGJ5CWDEEZSSHJWFLH4BKIGF7QE3MMPWCEZJH5UO3JBBWXFY47XKHMDQPRC4TGDP5HKHVPNCGWGZ6MPIM3WTKT3NZMEKBJ6JT44O23ZNNER5UUQHOJ5E4NFYHOXF6OMJAGPEJQZU6LG52NZPZID4YNOWOV7LXCURXF5PYQSBSUUWYDQP6W5U3XI6VYHF3BPLISZBLRWPFMFVI5DMMTQT3IMBAVVKBTJB2C65AAO2ZRXREBCPIIQA4VKX7QYWDN3U6EOQ3JFFI3O54EOOFA7K5HBQQ5ZS5ALQG74UKU6AN7NFZPYEKG7OJKI6EW5AUIDGHEW5UNEMKC4XSWNK6TJKCQNBZ4BKMXP66AH7GVB3OLLDFY5JSU4VBD3PVCX6GVT5SB62T4NRCV6IPJOWCB6G6C6KBFQ4R5B42N6N2VHM5ANEYTUKRSYG5EUL2A2EU5LZ2SYGDUHZBTV7ZMLOYLPLC35X4JKNRHNJCQK4PPY4ILIHORYU2OVJG3YFGT3Z2LQ3ATI2FJMAHEIIPYUSH2QOKSMZHWSFH25XLK4XRINN6FRJ2AMTEDZVQHKJY72WXWTTU5CDX65UAJRBBVVB23D7EJYZDX6W5EUT6UID63M7FZJNAMY55FTWLY23UXTB3TWFZTTGCCUHNOGDNGZNI7ZL4VHIRQJGWAVA2KGVDQIF2T35Y7HEH4X6URMORIAGUM57GVS2J3MNBOA33SRA5BFONIBSTDZYYLTHNUVZVW6WNDY45PQKMHO7JCXZIB55A2P2AJBU25NBNKLQ";
        byte[] decoded = new Base32().decode(b32);
        Cid cid = Cid.cast(decoded);
        System.out.println();
        // Use this method to test access to a bucket
        S3Config config = S3Config.build(Args.parse(args));
        System.out.println("Testing S3 bucket: " + config.bucket + " in region " + config.region + " with base dir: " + config.path);
        Multihash id = new Multihash(Multihash.Type.sha2_256, RAMStorage.hash("S3Storage".getBytes()));
        TransactionStore transactions = JdbcTransactionStore.build(Sqlite.build(":memory:"), new SqliteCommands());
        S3BlockStorage s3 = new S3BlockStorage(config, id, transactions);

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
