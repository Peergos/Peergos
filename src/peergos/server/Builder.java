package peergos.server;

import com.zaxxer.hikari.*;
import io.libp2p.core.*;
import peergos.server.corenode.*;
import peergos.server.crypto.*;
import peergos.server.crypto.asymmetric.curve25519.*;
import peergos.server.crypto.hash.*;
import peergos.server.crypto.random.*;
import peergos.server.login.*;
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
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.MultiAddress;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

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

    public static Supplier<Connection> getPostgresConnector(Args a, String prefix) {
        String postgresHost = a.getArg(prefix + "postgres.host");
        int postgresPort = a.getInt(prefix + "postgres.port", 5432);
        String databaseName = a.getArg(prefix + "postgres.database", "peergos");
        String postgresUsername = a.getArg(prefix + "postgres.username");
        String postgresPassword = a.getArg(prefix + "postgres.password");

        Properties props = new Properties();
        props.setProperty("dataSourceClassName", "org.postgresql.ds.PGSimpleDataSource");
        props.setProperty("dataSource.serverName", postgresHost);
        props.setProperty("dataSource.portNumber", "" + postgresPort);
        props.setProperty("dataSource.user", postgresUsername);
        props.setProperty("dataSource.password", postgresPassword);
        props.setProperty("dataSource.databaseName", databaseName);
        HikariConfig config = new HikariConfig(props);
        HikariDataSource ds = new HikariDataSource(config);

        return () -> {
            try {
                return ds.getConnection();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        };
    }

    public static Supplier<Connection> getDBConnector(Args a, String dbName) {
        boolean usePostgres = a.getBoolean("use-postgres", false);
        if (usePostgres) {
            return getPostgresConnector(a, "");
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
        S3Config config = S3Config.build(a, Optional.empty());
        Optional<String> publicReadUrl = S3Config.getPublicReadUrl(a);
        boolean directWrites = a.getBoolean("direct-s3-writes", false);
        boolean publicReads = a.getBoolean("public-s3-reads", false);
        boolean authedReads = a.getBoolean("authed-s3-reads", false);
        Optional<String> authedUrl = Optional.of("https://" + config.getHost() + "/");
        return new BlockStoreProperties(directWrites, publicReads, authedReads, publicReadUrl, authedUrl);
    }

    public static BlockMetadataStore buildBlockMetadata(Args a) {
        try {
            boolean usePostgres = a.getArg("block-metadata-db-type", "sqlite").equals("postgres");
            if (usePostgres) {
                return new JdbcBlockMetadataStore(getPostgresConnector(a, "metadb."), new PostgresCommands());
            } else {
                File metaFile = a.fromPeergosDir("block-metadata-sql-file", "blockmetadata-v3.sql").toFile();
                Connection instance = new Sqlite.UncloseableConnection(Sqlite.build(metaFile.getPath()));
                return new JdbcBlockMetadataStore(() -> instance, new SqliteCommands());
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static DeletableContentAddressedStorage buildLocalStorage(Args a,
                                                                     BlockMetadataStore meta,
                                                                     TransactionStore transactions,
                                                                     BlockRequestAuthoriser authoriser,
                                                                     ServerIdentityStore ids,
                                                                     Hasher hasher) throws SQLException {
        boolean useIPFS = a.getBoolean("useIPFS");
        boolean enableGC = a.getBoolean("enable-gc", false);
        boolean useS3 = S3Config.useS3(a);
        JavaPoster ipfsApi = buildIpfsApi(a);
        DeletableContentAddressedStorage.HTTP http = new DeletableContentAddressedStorage.HTTP(ipfsApi, false, hasher);
        List<PeerId> ourIds = ids.getIdentities();
        MultiIdStorage ipfs = new MultiIdStorage(http, ourIds);
        if (useIPFS) {
            if (useS3) {
                // IPFS is already running separately, we can still use an S3BlockStorage
                S3Config config = S3Config.build(a, Optional.empty());
                BlockStoreProperties props = buildS3Properties(a);
                TransactionalIpfs p2pBlockRetriever = new TransactionalIpfs(ipfs, transactions, authoriser, ipfs.id().join(), hasher);

                FileBlockCache cborCache = new FileBlockCache(a.fromPeergosDir("block-cache-dir", "block-cache"), 1024 * 1024 * 1024L);
                S3BlockStorage s3 = new S3BlockStorage(config, ipfs.ids().join(), props, transactions, authoriser,
                        meta, cborCache, hasher, p2pBlockRetriever, ipfs);
                s3.updateMetadataStoreIfEmpty();
                return new LocalIpnsStorage(s3, ids);
            } else if (enableGC) {
                TransactionalIpfs txns = new TransactionalIpfs(ipfs, transactions, authoriser, ipfs.id().join(), hasher);
                MetadataCachingStorage metabs = new MetadataCachingStorage(txns, meta, hasher);
                metabs.updateMetadataStoreIfEmpty();
                return new LocalIpnsStorage(metabs, ids);
            } else {
                AuthedStorage target = new AuthedStorage(ipfs, authoriser, hasher);
                MetadataCachingStorage metabs = new MetadataCachingStorage(target, meta, hasher);
                metabs.updateMetadataStoreIfEmpty();
                return new LocalIpnsStorage(metabs, ids);
            }
        } else {
            // In S3 mode of operation we require the ipfs id to be supplied as we don't have a local ipfs running
            if (useS3) {
                if (enableGC)
                    throw new IllegalStateException("GC should be run separately when using S3!");
                TransactionalIpfs p2pBlockRetriever = new TransactionalIpfs(http, transactions, authoriser, http.id().join(), hasher);
                S3Config config = S3Config.build(a, Optional.empty());
                BlockStoreProperties props = buildS3Properties(a);

                JavaPoster bloomApiTarget = buildBloomApiTarget(a);
                DeletableContentAddressedStorage.HTTP bloomTarget = new DeletableContentAddressedStorage.HTTP(bloomApiTarget, false, hasher);

                FileBlockCache cborCache = new FileBlockCache(a.fromPeergosDir("block-cache-dir", "block-cache"), 10 * 1024 * 1024 * 1024L);
                S3BlockStorage s3 = new S3BlockStorage(config, ipfs.ids().join(), props, transactions, authoriser,
                        meta, cborCache, hasher, p2pBlockRetriever, bloomTarget);
                s3.updateMetadataStoreIfEmpty();
                return new LocalIpnsStorage(s3, ids);
            } else {
                FileContentAddressedStorage fileBacked = new FileContentAddressedStorage(blockstorePath(a), transactions, authoriser, hasher);
                MetadataCachingStorage metabs = new MetadataCachingStorage(fileBacked, meta, hasher);
                metabs.updateMetadataStoreIfEmpty();
                return new LocalIpnsStorage(metabs, ids);
            }
        }
    }

    public static BlockRequestAuthoriser blockAuthoriser(Args a,
                                                         BatCave batStore,
                                                         Hasher hasher) {
        Optional<BatWithId> instanceBat = a.getOptionalArg("instance-bat").map(BatWithId::decode);
        return (b, blockBats, s, auth) -> {
            Optional<BlockAuth> blockAuth = auth.isEmpty() ?
                    Optional.empty() :
                    Optional.of(BlockAuth.fromString(auth));
            return Futures.of(BlockRequestAuthoriser.allowRead(b, blockBats, s, blockAuth, batStore, instanceBat, hasher));
        };
    }

    public static SqlSupplier getSqlCommands(Args a) {
        boolean usePostgres = a.getBoolean("use-postgres", false);
        return usePostgres ? new PostgresCommands() : new SqliteCommands();
    }

    public static TransactionStore buildTransactionStore(Args a, Supplier<Connection> transactionsDb) {
        return JdbcTransactionStore.build(transactionsDb, getSqlCommands(a));
    }

    public static boolean isPaidInstance(Args a) {
        return a.hasArg("quota-admin-address");
    }

    public static QuotaAdmin buildSpaceQuotas(Args a,
                                              DeletableContentAddressedStorage localDht,
                                              CoreNode core,
                                              Supplier<Connection> spaceDb,
                                              Supplier<Connection> quotasDb,
                                              boolean isPki,
                                              boolean localhostApi) {
        if (isPaidInstance(a))
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
        long maxUsers = a.getLong("max-users", localhostApi ? 1 : 0);
        if (! localhostApi && maxUsers > 0)
            Logging.LOG().warning("Anyone can signup to this instance because we are listening on non-localhost addresses and max-users > 0. Using signup tokens is more secure.");
        Logging.LOG().info("Using default user space quota of " + defaultQuota);
        return new UserQuotas(quotas, defaultQuota, maxUsers, spaceRequests, localDht, core, isPki);
    }

    public static QuotaAdmin buildPaidQuotas(Args a) {
        JavaPoster poster = new JavaPoster(AddressUtil.getAddress(new MultiAddress(a.getArg("quota-admin-address"))), true);
        return new HttpQuotaAdmin(poster);
    }

    public static CoreNode buildPkiCorenode(MutablePointers mutable, Account account, BatCave batCave, DeletableContentAddressedStorage dht, Args a) {
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

            SigningPrivateKeyAndPublicHash pkiSigner = new SigningPrivateKeyAndPublicHash(pkiPublicHash, pkiSecretKey);

            return new IpfsCoreNode(pkiSigner, a.getInt("max-daily-signups"), dht, crypto, mutable,
                    account, batCave, peergosIdentity);
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
                                         LinkRetrievalCounter linkCounts,
                                         Crypto crypto) {
        Multihash nodeId = localStorage.id().join();
        PublicKeyHash peergosId = PublicKeyHash.fromString(a.getArg("peergos.identity.hash"));
        Multihash pkiServerId = getPkiServerId(a);
        // build a mirroring proxying corenode, unless we are the pki node
        boolean isPkiNode = nodeId.bareMultihash().equals(pkiServerId);
        return isPkiNode ?
                buildPkiCorenode(localPointers, account, bats, localStorage, a) :
                new MirrorCoreNode(new HTTPCoreNode(buildP2pHttpProxy(a), pkiServerId), rawAccount, bats, account, proxingMutable,
                        localStorage, rawPointers, localPointers, transactions, localSocial, usageStore, linkCounts, pkiServerId, peergosId,
                        a.fromPeergosDir("pki-mirror-state-path","pki-state.cbor"), crypto);
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
