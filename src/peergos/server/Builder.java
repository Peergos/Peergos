package peergos.server;

import com.zaxxer.hikari.*;
import peergos.server.corenode.*;
import peergos.server.crypto.*;
import peergos.server.crypto.asymmetric.curve25519.*;
import peergos.server.crypto.hash.*;
import peergos.server.crypto.random.*;
import peergos.server.crypto.symmetric.*;
import peergos.server.login.*;
import peergos.server.mutable.*;
import peergos.server.space.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.storage.admin.*;
import peergos.server.storage.auth.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.password.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multiaddr.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class Builder {

    public static Crypto initNativeCrypto(Salsa20Poly1305 symmetric, Ed25519 signer, Curve25519 boxer) {
        SafeRandomJava random = new SafeRandomJava();
        return Crypto.init(() -> new Crypto(random, new ScryptJava(), symmetric, signer, boxer));
    }

    public static Crypto initCrypto() {
        try {
            if (! "linux".equalsIgnoreCase(System.getProperty("os.name")))
                return JavaCrypto.init();
            JniTweetNacl nativeNacl = JniTweetNacl.build();
            Salsa20Poly1305 symmetricProvider = new JniTweetNacl.Symmetric(nativeNacl);
            Ed25519 signer = new JniTweetNacl.Signer(nativeNacl);
            Curve25519 boxer = new Curve25519Java();
            return initNativeCrypto(symmetricProvider, signer, boxer);
        } catch (Throwable t) {
            return JavaCrypto.init();
        }
    }

    public static Supplier<Connection> getDBConnector(Args a, String dbName, Supplier<Connection> existing) {
        boolean usePostgres = a.getBoolean("use-postgres", false);
        if (usePostgres)
            return existing;
        return getDBConnector(a, dbName);
    }

    public static Supplier<Connection> getDBConnector(Args a, String dbName) {
        boolean usePostgres = a.getBoolean("use-postgres", false);
        HikariConfig config;
        if (usePostgres) {
            String postgresHost = a.getArg("postgres.host");
            int postgresPort = a.getInt("postgres.port", 5432);
            String databaseName = a.getArg("postgres.database", "peergos");
            String postgresUsername = a.getArg("postgres.username");
            String postgresPassword = a.getArg("postgres.password");

            Properties props = new Properties();
            props.setProperty("dataSourceClassName", "org.postgresql.ds.PGSimpleDataSource");
            props.setProperty("dataSource.serverName", postgresHost);
            props.setProperty("dataSource.portNumber", "" + postgresPort);
            props.setProperty("dataSource.user", postgresUsername);
            props.setProperty("dataSource.password", postgresPassword);
            props.setProperty("dataSource.databaseName", databaseName);
            config = new HikariConfig(props);
            HikariDataSource ds = new HikariDataSource(config);

            return () -> {
                try {
                    return ds.getConnection();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            };
        } else {
            String sqlFilePath = Sqlite.getDbPath(a, dbName);
            if (":memory:".equals(sqlFilePath))
                return buildEphemeralSqlite();
            try {
                Connection memory = Sqlite.build(sqlFilePath);
                // We need a connection that ignores close
                Connection instance = new Sqlite.UncloseableConnection(memory);
                return () -> instance;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static Supplier<Connection> buildEphemeralSqlite() {
        try {
            Connection memory = Sqlite.build(":memory:");
            // We need a connection that ignores close
            Connection instance = new Sqlite.UncloseableConnection(memory);
            return () -> instance;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static JavaPoster buildIpfsApi(Args a) {
        URL ipfsApiAddress = AddressUtil.getAddress(new MultiAddress(a.getArg("ipfs-api-address", "/ip4/127.0.0.1/tcp/5001")));
        return new JavaPoster(ipfsApiAddress, false);
    }

    /**
     *
     * @param a
     * @return This returns the P2P HTTP proxy, which is in the IPFS gateway
     */
    public static JavaPoster buildP2pHttpProxy(Args a) {
        URL ipfsGatewayAddress = AddressUtil.getAddress(new MultiAddress(a.getArg("ipfs-gateway-address")));
        return new JavaPoster(ipfsGatewayAddress, false);
    }

    /** A number representing the size in bytes of the blockstore's bloom filter. A value of zero represents the feature is disabled.

     This site generates useful graphs for various bloom filter values: https://hur.st/bloomfilter/?n=1e6&p=0.01&m=&k=7
     You may use it to find a preferred optimal value, where m is BloomFilterSize in bits. Remember to convert the value
     m from bits, into bytes for use as BloomFilterSize in the config file. For example, for 1,000,000 blocks, expecting
     a 1% false-positive rate, you'd end up with a filter size of 9592955 bits, so for BloomFilterSize we'd want to use
     1199120 bytes. As of writing, 7 hash functions are used, so the constant k is 7 in the formula.
     *
     * @param falsePositivesProbability
     * @param numberOfBlocks
     * @return
     */
    public static int bloomfilterSizeBytes(double falsePositivesProbability, long numberOfBlocks) {
        int numberOfHashfunctions = 7;
        return (int)Math.ceil((numberOfBlocks * Math.log(falsePositivesProbability)) / Math.log(1 / Math.pow(2, Math.log(2))))/8;

    }

    /**
     *
     * @param a
     * @return This returns the ipfs bloom  filter api target
     */
    public static JavaPoster buildBloomApiTarget(Args a) {
        if (! a.hasArg("ipfs-bloom-api-address"))
            return buildIpfsApi(a);
        URL ipfsGatewayAddress = AddressUtil.getAddress(new MultiAddress(a.getArg("ipfs-bloom-api-address")));
        return new JavaPoster(ipfsGatewayAddress, false);
    }

    /**
     * Create path to local blockstore directory from Args.
     *
     * @param args
     * @return
     */
    public static Path blockstorePath(Args args) {
        return args.fromPeergosDir("blockstore_dir", "blockstore");
    }

    private static BlockStoreProperties buildS3Properties(Args a) {
        S3Config config = S3Config.build(a);
        Optional<String> publicReadUrl = S3Config.getPublicReadUrl(a);
        boolean directWrites = a.getBoolean("direct-s3-writes", false);
        boolean publicReads = a.getBoolean("public-s3-reads", false);
        boolean authedReads = a.getBoolean("authed-s3-reads", false);
        Optional<String> authedUrl = Optional.of("https://" + config.getHost() + "/");
        return new BlockStoreProperties(directWrites, publicReads, authedReads, publicReadUrl, authedUrl);
    }

    public static DeletableContentAddressedStorage buildLocalStorage(Args a,
                                                                     TransactionStore transactions,
                                                                     BlockRequestAuthoriser authoriser,
                                                                     Hasher hasher) {
        boolean useIPFS = a.getBoolean("useIPFS");
        boolean enableGC = a.getBoolean("enable-gc", false);
        boolean useS3 = S3Config.useS3(a);
        JavaPoster ipfsApi = buildIpfsApi(a);
        if (useIPFS) {
            DeletableContentAddressedStorage.HTTP ipfs = new DeletableContentAddressedStorage.HTTP(ipfsApi, false, hasher);
            if (useS3) {
                // IPFS is already running separately, we can still use an S3BlockStorage
                S3Config config = S3Config.build(a);
                BlockStoreProperties props = buildS3Properties(a);
                TransactionalIpfs p2pBlockRetriever = new TransactionalIpfs(ipfs, transactions, authoriser, ipfs.id().join(), hasher);

                return new S3BlockStorage(config, ipfs.id().join(), props, transactions, authoriser, hasher, p2pBlockRetriever, ipfs);
            } else if (enableGC) {
                return new TransactionalIpfs(ipfs, transactions, authoriser, ipfs.id().join(), hasher);
            } else
                return new AuthedStorage(ipfs, authoriser, hasher);
        } else {
            // In S3 mode of operation we require the ipfs id to be supplied as we don't have a local ipfs running
            if (useS3) {
                if (enableGC)
                    throw new IllegalStateException("GC should be run separately when using S3!");
                DeletableContentAddressedStorage.HTTP ipfs = new DeletableContentAddressedStorage.HTTP(ipfsApi, false, hasher);
                Cid ourId = Cid.decode(a.getArg("ipfs.id"));
                TransactionalIpfs p2pBlockRetriever = new TransactionalIpfs(ipfs, transactions, authoriser, ipfs.id().join(), hasher);
                S3Config config = S3Config.build(a);
                BlockStoreProperties props = buildS3Properties(a);

                JavaPoster bloomApiTarget = buildBloomApiTarget(a);
                DeletableContentAddressedStorage.HTTP bloomTarget = new DeletableContentAddressedStorage.HTTP(bloomApiTarget, false, hasher);
                return new S3BlockStorage(config, ourId, props, transactions, authoriser, hasher, p2pBlockRetriever, bloomTarget);
            } else {
                return new FileContentAddressedStorage(blockstorePath(a), transactions, authoriser, hasher);
            }
        }
    }


    private static CompletableFuture<Boolean> ALLOW = Futures.of(true);
    private static CompletableFuture<Boolean> BLOCK = Futures.of(false);
    public static BlockRequestAuthoriser blockAuthoriser(Args a,
                                                         BatCave batStore,
                                                         Hasher hasher) {
        Optional<BatWithId> instanceBat = a.getOptionalArg("instance-bat").map(BatWithId::decode);
        return (b, d, s, auth) -> {
            Logging.LOG().fine("Allow: " + b + ", auth=" + auth + ", from: " + s);
            if (b.isRaw()) {
                List<BatId> batids = Bat.getRawBlockBats(d);
                if (batids.isEmpty()) // legacy raw block
                    return ALLOW;
                if (auth.isEmpty())
                    return BLOCK;
                BlockAuth blockAuth = BlockAuth.fromString(auth);
                for (BatId bid : batids) {
                    Optional<Bat> bat = bid.getInline()
                            .or(() -> bid.id.equals(blockAuth.batId) ?
                                    batStore.getBat(bid) :
                                    Optional.empty());
                    if (bat.isPresent() && BlockRequestAuthoriser.isValidAuth(blockAuth, b, s, bat.get(), hasher))
                        return ALLOW;
                }
                if (instanceBat.isPresent()) {
                    if (BlockRequestAuthoriser.isValidAuth(blockAuth, b, s, instanceBat.get().bat, hasher))
                        return ALLOW;
                }
                return BLOCK;
            } else if (b.codec == Cid.Codec.DagCbor) {
                CborObject block = CborObject.fromByteArray(d);
                if (block instanceof CborObject.CborMap) {
                    if (((CborObject.CborMap) block).containsKey("bats")) {
                        List<BatId> batids = ((CborObject.CborMap) block).getList("bats", BatId::fromCbor);
                        if (auth.isEmpty()) {
                            System.out.println("INVALID AUTH: EMPTY");
                            return BLOCK;
                        }
                        BlockAuth blockAuth = BlockAuth.fromString(auth);
                        for (BatId bid : batids) {
                            Optional<Bat> bat = bid.getInline()
                                    .or(() -> bid.id.equals(blockAuth.batId) ?
                                            batStore.getBat(bid) :
                                            Optional.empty());
                            if (bat.isPresent() && BlockRequestAuthoriser.isValidAuth(blockAuth, b, s, bat.get(), hasher))
                                return ALLOW;
                        }
                        if (instanceBat.isPresent()) {
                            if (BlockRequestAuthoriser.isValidAuth(blockAuth, b, s, instanceBat.get().bat, hasher))
                                return ALLOW;
                        }
                        if (! batids.isEmpty()) {
                            Logging.LOG().info("INVALID AUTH: " + BlockRequestAuthoriser.invalidReason(blockAuth, b, s, batids, hasher));
                        }
                        return BLOCK;
                    } else return ALLOW; // This is a public block
                } else // e.g. inner CHAMP nodes
                    return ALLOW;
            }
            return BLOCK;
        };
    }

    public static SqlSupplier getSqlCommands(Args a) {
        boolean usePostgres = a.getBoolean("use-postgres", false);
        return usePostgres ? new PostgresCommands() : new SqliteCommands();
    }

    public static TransactionStore buildTransactionStore(Args a, Supplier<Connection> transactionsDb) {
        return JdbcTransactionStore.build(transactionsDb, getSqlCommands(a));
    }

    public static QuotaAdmin buildSpaceQuotas(Args a,
                                              DeletableContentAddressedStorage localDht,
                                              CoreNode core,
                                              Supplier<Connection> spaceDb,
                                              Supplier<Connection> quotasDb) {
        boolean paidStorage = a.hasArg("quota-admin-address");
        if (paidStorage)
            return buildPaidQuotas(a);

        SqlSupplier sqlCommands = getSqlCommands(a);
        JdbcSpaceRequests spaceRequests = JdbcSpaceRequests.build(spaceDb, sqlCommands);
        JdbcQuotas quotas = JdbcQuotas.build(quotasDb, sqlCommands);
        if (a.hasArg("quotas-init-file")) {
            String quotaFile = a.getArg("quotas-init-file");
            Map<String, Long> quotaInit = UserQuotas.readUsernamesFromFile(PathUtil.get(quotaFile));
            quotaInit.forEach(quotas::setQuota);
        }
        long defaultQuota = a.getLong("default-quota");
        long maxUsers = a.getLong("max-users");
        Logging.LOG().info("Using default user space quota of " + defaultQuota);
        return new UserQuotas(quotas, defaultQuota, maxUsers, spaceRequests, localDht, core);
    }

    public static QuotaAdmin buildPaidQuotas(Args a) {
        JavaPoster poster = new JavaPoster(AddressUtil.getAddress(new MultiAddress(a.getArg("quota-admin-address"))), true);
        return new HttpQuotaAdmin(poster);
    }

    public static CoreNode buildPkiCorenode(MutablePointers mutable, Account account, BatCave batCave, ContentAddressedStorage dht, Args a) {
        try {
            Crypto crypto = initCrypto();
            PublicKeyHash peergosIdentity = PublicKeyHash.fromString(a.getArg("peergos.identity.hash"));

            String pkiSecretKeyfilePassword = a.getArg("pki.keyfile.password");

            PublicSigningKey pkiPublic =
                    PublicSigningKey.fromByteArray(
                            Files.readAllBytes(a.fromPeergosDir("pki.public.key.path")));
            SecretSigningKey pkiSecretKey = SecretSigningKey.fromCbor(CborObject.fromByteArray(
                    PasswordProtected.decryptWithPassword(
                            CborObject.fromByteArray(Files.readAllBytes(a.fromPeergosDir("pki.secret.key.path"))),
                            pkiSecretKeyfilePassword,
                            crypto.hasher,
                            crypto.symmetricProvider,
                            crypto.random
                    )));
            SigningKeyPair pkiKeys = new SigningKeyPair(pkiPublic, pkiSecretKey);
            PublicKeyHash pkiPublicHash = ContentAddressedStorage.hashKey(pkiKeys.publicSigningKey);

            PointerUpdate currentPkiPointer = mutable.getPointerTarget(peergosIdentity, pkiPublicHash, dht).join();
            Optional<Long> currentPkiSequence = currentPkiPointer.sequence;
            MaybeMultihash currentPkiRoot = currentPkiPointer.updated;
            SigningPrivateKeyAndPublicHash pkiSigner = new SigningPrivateKeyAndPublicHash(pkiPublicHash, pkiSecretKey);
            if (! currentPkiRoot.isPresent()) {
                CommittedWriterData committed = IpfsTransaction.call(peergosIdentity,
                        tid -> WriterData.createEmpty(peergosIdentity, pkiSigner, dht, crypto.hasher, tid).join()
                                .commit(peergosIdentity, pkiSigner, MaybeMultihash.empty(), Optional.empty(), mutable, dht, crypto.hasher, tid)
                                .thenApply(version -> version.get(pkiSigner)), dht).join();
                currentPkiRoot = committed.hash;
                currentPkiSequence = committed.sequence;
            }

            return new IpfsCoreNode(pkiSigner, a.getInt("max-daily-signups"), currentPkiRoot, currentPkiSequence,
                    dht, crypto.hasher, mutable, account, batCave, peergosIdentity);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Multihash getPkiServerId(Args a) {
        return Cid.decodePeerId(a.getArg("pki-node-id"));
    }

    public static CoreNode buildCorenode(Args a,
                                         DeletableContentAddressedStorage localStorage,
                                         TransactionStore transactions,
                                         JdbcIpnsAndSocial rawPointers,
                                         MutablePointers localPointers,
                                         MutablePointersProxy proxingMutable,
                                         JdbcIpnsAndSocial localSocial,
                                         UsageStore usageStore,
                                         JdbcAccount rawAccount,
                                         BatCave bats,
                                         Account account,
                                         Hasher hasher) {
        Multihash nodeId = localStorage.id().join();
        PublicKeyHash peergosId = PublicKeyHash.fromString(a.getArg("peergos.identity.hash"));
        Multihash pkiServerId = getPkiServerId(a);
        // build a mirroring proxying corenode, unless we are the pki node
        boolean isPkiNode = nodeId.equals(pkiServerId);
        return isPkiNode ?
                buildPkiCorenode(localPointers, account, bats, localStorage, a) :
                new MirrorCoreNode(new HTTPCoreNode(buildP2pHttpProxy(a), pkiServerId), rawAccount, bats, account, proxingMutable,
                        localStorage, rawPointers, localPointers, transactions, localSocial, usageStore, peergosId,
                        a.fromPeergosDir("pki-mirror-state-path","pki-state.cbor"), hasher);
    }

    public static JdbcIpnsAndSocial buildRawPointers(Args a, Supplier<Connection> dbConnectionPool) {
        return new JdbcIpnsAndSocial(dbConnectionPool, getSqlCommands(a));
    }


    public static CompletableFuture<NetworkAccess> buildJavaGatewayAccess(URL apiAddress, URL proxyAddress, String pkiNodeId) {
        Multihash pkiServerNodeId = Cid.decode(pkiNodeId);
        JavaPoster p2pPoster = new JavaPoster(proxyAddress, false);
        JavaPoster apiPoster = new JavaPoster(apiAddress, false);
        ScryptJava hasher = new ScryptJava();
        return NetworkAccess.buildViaGateway(apiPoster, p2pPoster, pkiServerNodeId, 0, hasher, false);
    }

    public static CompletableFuture<NetworkAccess> buildJavaNetworkAccess(URL target,
                                                                          boolean isPublicServer) {
        return buildJavaNetworkAccess(target, isPublicServer, Optional.empty());
    }

    public static CompletableFuture<NetworkAccess> buildJavaNetworkAccess(URL target,
                                                                          boolean isPublicServer,
                                                                          Optional<String> basicAuth) {
        return buildNonCachingJavaNetworkAccess(target, isPublicServer, 7_000, basicAuth);
    }

    public static CompletableFuture<NetworkAccess> buildNonCachingJavaNetworkAccess(URL target,
                                                                                    boolean isPublicServer,
                                                                                    int mutableCacheTime,
                                                                                    Optional<String> basicAuth) {
        JavaPoster poster = new JavaPoster(target, isPublicServer, basicAuth);
        ScryptJava hasher = new ScryptJava();
        ContentAddressedStorage localDht = NetworkAccess.buildLocalDht(poster, true, hasher);
        return NetworkAccess.buildViaPeergosInstance(poster, poster, localDht, mutableCacheTime, hasher, false);
    }

    public static CompletableFuture<NetworkAccess> buildLocalJavaNetworkAccess(int targetPort) {
        try {
            return buildJavaNetworkAccess(new URL("http://localhost:" + targetPort + "/"), false, Optional.empty());
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }
}
