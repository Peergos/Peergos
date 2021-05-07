package peergos.server;

import com.zaxxer.hikari.*;
import peergos.server.corenode.*;
import peergos.server.crypto.*;
import peergos.server.crypto.asymmetric.curve25519.*;
import peergos.server.crypto.hash.*;
import peergos.server.crypto.random.*;
import peergos.server.crypto.symmetric.*;
import peergos.server.mutable.*;
import peergos.server.space.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.storage.admin.*;
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
import peergos.shared.user.*;

import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class Builder {

    public static Crypto initJavaCrypto() {
        SafeRandomJava random = new SafeRandomJava();
        Salsa20Poly1305Java symmetricProvider = new Salsa20Poly1305Java();
        Ed25519Java signer = new Ed25519Java();
        Curve25519 boxer = new Curve25519Java();
        return Crypto.init(() -> new Crypto(random, new ScryptJava(), symmetricProvider, signer, boxer));
    }

    public static Crypto initNativeCrypto(Salsa20Poly1305 symmetric, Ed25519 signer, Curve25519 boxer) {
        SafeRandomJava random = new SafeRandomJava();
        return Crypto.init(() -> new Crypto(random, new ScryptJava(), symmetric, signer, boxer));
    }

    public static Crypto initCrypto() {
        try {
            JniTweetNacl nativeNacl = JniTweetNacl.build();
            Salsa20Poly1305 symmetricProvider = new JniTweetNacl.Symmetric(nativeNacl);
            Ed25519 signer = new JniTweetNacl.Signer(nativeNacl);
            Curve25519 boxer = new Curve25519Java();
            return initNativeCrypto(symmetricProvider, signer, boxer);
        } catch (Throwable t) {
            return initJavaCrypto();
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
        URL ipfsApiAddress = AddressUtil.getAddress(new MultiAddress(a.getArg("ipfs-api-address")));
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

    /**
     * Create path to local blockstore directory from Args.
     *
     * @param args
     * @return
     */
    public static Path blockstorePath(Args args) {
        return args.fromPeergosDir("blockstore_dir", "blockstore");
    }

    public static DeletableContentAddressedStorage buildLocalStorage(Args a,
                                                                     TransactionStore transactions,
                                                                     Hasher hasher) {
        boolean useIPFS = a.getBoolean("useIPFS");
        boolean enableGC = a.getBoolean("enable-gc", false);
        JavaPoster ipfsApi = buildIpfsApi(a);
        if (useIPFS) {
            DeletableContentAddressedStorage.HTTP ipfs = new DeletableContentAddressedStorage.HTTP(ipfsApi, false, hasher);
            if (enableGC) {
                return new TransactionalIpfs(ipfs, transactions, hasher);
            } else
                return ipfs;
        } else {
            // In S3 mode of operation we require the ipfs id to be supplied as we don't have a local ipfs running
            if (S3Config.useS3(a)) {
                if (enableGC)
                    throw new IllegalStateException("GC should be run separately when using S3!");
                ContentAddressedStorage.HTTP ipfs = new ContentAddressedStorage.HTTP(ipfsApi, false, hasher);
                Optional<String> publicReadUrl = S3Config.getPublicReadUrl(a);
                boolean directWrites = a.getBoolean("direct-s3-writes", false);
                boolean publicReads = a.getBoolean("public-s3-reads", false);
                boolean authedReads = a.getBoolean("authed-s3-reads", false);
                S3Config config = S3Config.build(a);
                Optional<String> authedUrl = Optional.of("https://" + config.getHost() + "/");
                BlockStoreProperties props = new BlockStoreProperties(directWrites, publicReads, authedReads, publicReadUrl, authedUrl);
                return new S3BlockStorage(config, Cid.decode(a.getArg("ipfs.id")), props, transactions, hasher, ipfs);
            } else {
                return new FileContentAddressedStorage(blockstorePath(a), transactions, hasher);
            }
        }
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
            Map<String, Long> quotaInit = UserQuotas.readUsernamesFromFile(Paths.get(quotaFile));
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

    public static CoreNode buildPkiCorenode(MutablePointers mutable, ContentAddressedStorage dht, Args a) {
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

            MaybeMultihash currentPkiRoot = mutable.getPointerTarget(peergosIdentity, pkiPublicHash, dht).get();
            SigningPrivateKeyAndPublicHash pkiSigner = new SigningPrivateKeyAndPublicHash(pkiPublicHash, pkiSecretKey);
            if (! currentPkiRoot.isPresent())
                currentPkiRoot = IpfsTransaction.call(peergosIdentity,
                        tid -> WriterData.createEmpty(peergosIdentity, pkiSigner, dht, crypto.hasher, tid).join()
                                .commit(peergosIdentity, pkiSigner, MaybeMultihash.empty(), mutable, dht, crypto.hasher, tid)
                                .thenApply(version -> version.get(pkiSigner).hash), dht).join();

            return new IpfsCoreNode(pkiSigner, a.getInt("max-daily-signups"), currentPkiRoot, dht, crypto.hasher, mutable, peergosIdentity);
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
                                         Hasher hasher) {
        Multihash nodeId = localStorage.id().join();
        PublicKeyHash peergosId = PublicKeyHash.fromString(a.getArg("peergos.identity.hash"));
        Multihash pkiServerId = getPkiServerId(a);
        // build a mirroring proxying corenode, unless we are the pki node
        boolean isPkiNode = nodeId.equals(pkiServerId);
        return isPkiNode ?
                buildPkiCorenode(new PinningMutablePointers(localPointers, localStorage), localStorage, a) :
                new MirrorCoreNode(new HTTPCoreNode(buildP2pHttpProxy(a), pkiServerId), proxingMutable, localStorage,
                        rawPointers, transactions, localSocial, usageStore, peergosId,
                        a.fromPeergosDir("pki-mirror-state-path","pki-state.cbor"), hasher);
    }

    public static JdbcIpnsAndSocial buildRawPointers(Args a, Supplier<Connection> dbConnectionPool) {
        return new JdbcIpnsAndSocial(dbConnectionPool, getSqlCommands(a));
    }


    public static CompletableFuture<NetworkAccess> buildJavaNetworkAccess(URL apiAddress, URL proxyAddress, String pkiNodeId) {
        Multihash pkiServerNodeId = Cid.decode(pkiNodeId);
        JavaPoster p2pPoster = new JavaPoster(proxyAddress, false);
        JavaPoster apiPoster = new JavaPoster(apiAddress, false);
        ScryptJava hasher = new ScryptJava();
        return NetworkAccess.build(apiPoster, p2pPoster, pkiServerNodeId, NetworkAccess.buildLocalDht(apiPoster, true, hasher), hasher, false);
    }

    public static CompletableFuture<NetworkAccess> buildJavaNetworkAccess(URL target,
                                                                          boolean isPublicServer) {
        return buildJavaNetworkAccess(target, isPublicServer, Optional.empty());
    }

    public static CompletableFuture<NetworkAccess> buildJavaNetworkAccess(URL target,
                                                                          boolean isPublicServer,
                                                                          Optional<String> basicAuth) {
        return buildNonCachingJavaNetworkAccess(target, isPublicServer, basicAuth)
                .thenApply(e -> e.withMutablePointerCache(7_000));
    }

    public static CompletableFuture<NetworkAccess> buildNonCachingJavaNetworkAccess(URL target,
                                                                                    boolean isPublicServer,
                                                                                    Optional<String> basicAuth) {
        JavaPoster poster = new JavaPoster(target, isPublicServer, basicAuth);
        Multihash pkiNodeId = null; // This is not required when talking to a Peergos server
        ScryptJava hasher = new ScryptJava();
        ContentAddressedStorage localDht = NetworkAccess.buildLocalDht(poster, true, hasher);
        return NetworkAccess.build(poster, poster, pkiNodeId, localDht, hasher, false);
    }

    public static CompletableFuture<NetworkAccess> buildLocalJavaNetworkAccess(int targetPort) {
        try {
            return buildJavaNetworkAccess(new URL("http://localhost:" + targetPort + "/"), false, Optional.empty());
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }
}
