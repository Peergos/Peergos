package peergos.server;

import com.zaxxer.hikari.*;
import peergos.server.corenode.*;
import peergos.server.crypto.*;
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
import java.util.function.*;

public class Builder {

    public static Crypto initCrypto() {
        try {
            JniTweetNacl nativeNacl = JniTweetNacl.build();
            Salsa20Poly1305 symmetricProvider = new JniTweetNacl.Symmetric(nativeNacl);
            Ed25519 signer = new JniTweetNacl.Signer(nativeNacl);
            Curve25519 boxer = new Curve25519.Java();
            return Crypto.initNative(symmetricProvider, signer, boxer);
        } catch (Throwable t) {
            return Crypto.initJava();
        }
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
     * @return This returns the P@P HTTP proxy, which is in the IPFS gateway
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
                                                                      TransactionStore transactions) {
        boolean useIPFS = a.getBoolean("useIPFS");
        boolean enableGC = a.getBoolean("enable-gc", false);
        JavaPoster ipfsApi = buildIpfsApi(a);
        if (useIPFS) {
            DeletableContentAddressedStorage.HTTP ipfs = new DeletableContentAddressedStorage.HTTP(ipfsApi, false);
            if (enableGC) {
                return new TransactionalIpfs(ipfs, transactions);
            } else
                return ipfs;
        } else {
            // In S3 mode of operation we require the ipfs id to be supplied as we don't have a local ipfs running
            if (S3Config.useS3(a)) {
                if (enableGC)
                    throw new IllegalStateException("GC should be run separately when using S3!");
                ContentAddressedStorage.HTTP ipfs = new ContentAddressedStorage.HTTP(ipfsApi, false);
                Optional<String> publicReadUrl = Optional.ofNullable(a.getArg("blockstore-url", null));
                boolean directWrites = a.getBoolean("direct-s3-writes", false);
                boolean publicReads = a.getBoolean("public-s3-reads", false);
                boolean authedReads = a.getBoolean("authed-s3-reads", false);
                BlockStoreProperties props = new BlockStoreProperties(directWrites, publicReads, authedReads, publicReadUrl);
                return new S3BlockStorage(S3Config.build(a), Cid.decode(a.getArg("ipfs.id")),
                        props, transactions, ipfs);
            } else {
                return new FileContentAddressedStorage(blockstorePath(a), transactions);
            }
        }
    }

    public static SqlSupplier getSqlCommands(Args a) {
        boolean usePostgres = a.getBoolean("use-postgres", false);
        return usePostgres ? new PostgresCommands() : new SqliteCommands();
    }

    public static TransactionStore buildTransactionStore(Args a) {
        Supplier<Connection> transactionsDb = getDBConnector(a, "transactions-sql-file");
        return JdbcTransactionStore.build(transactionsDb, getSqlCommands(a));
    }

    public static QuotaAdmin buildSpaceQuotas(Args a, DeletableContentAddressedStorage localDht, CoreNode core) {
        long defaultQuota = a.getLong("default-quota");
        long maxUsers = a.getLong("max-users");
        Logging.LOG().info("Using default user space quota of " + defaultQuota);
        Path quotaFilePath = a.fromPeergosDir("quotas_file","quotas.txt");

        boolean paidStorage = a.hasArg("quota-admin-address");
        if (! paidStorage) {
            SqlSupplier sqlCommands = getSqlCommands(a);
            Supplier<Connection> spaceDb = getDBConnector(a, "space-requests-sql-file");
            JdbcSpaceRequests spaceRequests = JdbcSpaceRequests.build(spaceDb, sqlCommands);
            return new UserQuotas(quotaFilePath, defaultQuota, maxUsers, spaceRequests, localDht, core);
        } else {
            JavaPoster poster = new JavaPoster(AddressUtil.getAddress(new MultiAddress(a.getArg("quota-admin-address"))), true);
            return new HttpQuotaAdmin(poster);
        }
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
        return Cid.decode(a.getArg("pki-node-id"));
    }

    public static CoreNode buildCorenode(Args a,
                                          DeletableContentAddressedStorage localStorage,
                                          TransactionStore transactions,
                                          JdbcIpnsAndSocial rawPointers,
                                          MutablePointers localPointers,
                                          MutablePointersProxy proxingMutable) {
        Multihash nodeId = localStorage.id().join();
        PublicKeyHash peergosId = PublicKeyHash.fromString(a.getArg("peergos.identity.hash"));
        Multihash pkiServerId = getPkiServerId(a);
        // build a mirroring proxying corenode, unless we are the pki node
        boolean isPkiNode = nodeId.equals(pkiServerId);
        return isPkiNode ?
                buildPkiCorenode(new PinningMutablePointers(localPointers, localStorage), localStorage, a) :
                new MirrorCoreNode(new HTTPCoreNode(buildP2pHttpProxy(a), pkiServerId), proxingMutable, localStorage,
                        rawPointers, transactions, peergosId,
                        a.fromPeergosDir("pki-mirror-state-path","pki-state.cbor"));
    }

    public static JdbcIpnsAndSocial buildRawPointers(Args a) {
        return new JdbcIpnsAndSocial(getDBConnector(a, "mutable-pointers-file"), getSqlCommands(a));
    }


}
