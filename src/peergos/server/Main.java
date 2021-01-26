package peergos.server;

import peergos.server.cli.CLI;
import peergos.server.messages.*;
import peergos.server.space.*;
import peergos.server.sql.*;
import peergos.server.storage.admin.*;
import peergos.shared.*;
import peergos.server.corenode.*;
import peergos.server.fuse.*;
import peergos.server.mutable.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.password.*;
import peergos.shared.io.ipfs.multiaddr.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.storage.controller.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main extends Builder {
    public static final String PEERGOS_PATH = "PEERGOS_PATH";
    public static final Path DEFAULT_PEERGOS_DIR_PATH =
            Paths.get(System.getProperty("user.home"), ".peergos");

    static {
        PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, initCrypto().signer);
    }

    public static Command<Boolean> ENSURE_IPFS_INSTALLED = new Command<>("install-ipfs",
            "Download/update IPFS binary. Does nothing if current IPFS binary is up-to-date.",
            args -> {
                Path ipfsExePath = IpfsWrapper.getIpfsExePath(args);
                File dir = ipfsExePath.getParent().toFile();
                if (!  dir.isDirectory() && ! dir.mkdirs())
                    throw new IllegalStateException("Specified install directory "+ dir +" doesn't exist and can't be created");

                IpfsInstaller.ensureInstalled(ipfsExePath);

                List<IpfsInstaller.Plugin> plugins = IpfsInstaller.Plugin.parseAll(args);
                Path ipfsDir = IpfsWrapper.getIpfsDir(args);
                if (! plugins.isEmpty())
                    if (! ipfsDir.toFile().exists() && ! ipfsDir.toFile().mkdirs())
                        throw new IllegalStateException("Couldn't create ipfs dir: " + ipfsDir);

                for (IpfsInstaller.Plugin plugin : plugins) {
                    plugin.ensureInstalled(ipfsDir);
                }
                return true;
            },
            Arrays.asList(
                    new Command.Arg("ipfs-exe-path", "Desired path to IPFS executable. Defaults to $PEERGOS_PATH/ipfs", false),
                    new Command.Arg("ipfs-plugins", "comma separated list of ipfs plugins to install, currently only go-ds-s3 is supported", false),
                    new Command.Arg("s3.path", "Path of data store in S3", false),
                    new Command.Arg("s3.bucket", "S3 bucket name", false),
                    new Command.Arg("s3.region", "S3 region", false),
                    new Command.Arg("s3.accessKey", "S3 access key", false),
                    new Command.Arg("s3.secretKey", "S3 secret key", false),
                    new Command.Arg("s3.region.endpoint", "Base url for S3 service", false)
            )
    );
    public static Command<IpfsWrapper> IPFS = new Command<>("ipfs",
            "Configure and start IPFS daemon",
            Main::startIpfs,
            Arrays.asList(
                    new Command.Arg("IPFS_PATH", "Path to IPFS directory. Defaults to $PEERGOS_PATH/.ipfs, or ~/.peergos/.ipfs", false),
                    new Command.Arg("ipfs-exe-path", "Path to IPFS executable. Defaults to $PEERGOS_PATH/ipfs", false),
                    new Command.Arg("ipfs-api-address", "IPFS API port", false, "/ip4/127.0.0.1/tcp/5001"),
                    new Command.Arg("ipfs-gateway-address", "IPFS Gateway port", false, "/ip4/127.0.0.1/tcp/8080"),
                    new Command.Arg("ipfs-swarm-port", "IPFS Swarm port", false, "4001"),
                    new Command.Arg("proxy-target", "Proxy target for p2p http requests", false, "/ip4/127.0.0.1/tcp/8000"),
                    new Command.Arg("ipfs-config-bootstrap-node-list", "Comma separated list of IPFS bootstrap nodes. Uses existing bootstrap nodes by default.", false),
                    new Command.Arg("ipfs-manage-runtime", "Will manage the IPFS daemon runtime when set (restart on exit)", false, "true")
            )
    );

    public static Command<IpfsWrapper> INSTALL_AND_RUN_IPFS = new Command<>("ipfs",
            "Install, configure and start IPFS daemon",
            a -> {
                ENSURE_IPFS_INSTALLED.main(a);
                return IPFS.main(a);
            },
            Stream.concat(
                    ENSURE_IPFS_INSTALLED.params.stream(),
                    IPFS.params.stream())
                    .collect(Collectors.toList())
    );

    public static final Command<UserService> PEERGOS = new Command<>("daemon",
            "The user facing Peergos server",
            Main::startPeergos,
            Stream.of(
                    new Command.Arg("port", "service port", false, "8000"),
                    new Command.Arg("peergos.identity.hash", "The hash of peergos user's public key, this is used to bootstrap the pki", true, "z59vuwzfFDp3ZA8ZpnnmHEuMtyA1q34m3Th49DYXQVJntWpxdGrRqXi"),
                    new Command.Arg("pki-node-id", "Ipfs node id of the pki node", true, "QmVdFZgHnEgcedCS2G2ZNiEN59LuVrnRm7z3yXtEBv2XiF"),
                    new Command.Arg("pki.node.ipaddress", "IP address of the pki node", true, "172.104.157.121"),
                    new Command.Arg("ipfs-api-address", "IPFS API port", false, "/ip4/127.0.0.1/tcp/5001"),
                    new Command.Arg("ipfs-gateway-address", "IPFS Gateway port", false, "/ip4/127.0.0.1/tcp/8080"),
                    new Command.Arg("pki.node.swarm.port", "Swarm port of the pki node", true, "5001"),
                    new Command.Arg("domain", "Domain name to bind to,", false, "localhost"),
                    new Command.Arg("max-users", "The maximum number of local users", false, "1"),
                    new Command.Arg("useIPFS", "Use IPFS for storage or a local disk store", false, "true"),
                    new Command.Arg("mutable-pointers-file", "The filename for the mutable pointers datastore", true, "mutable.sql"),
                    new Command.Arg("social-sql-file", "The filename for the follow requests datastore", true, "social.sql"),
                    new Command.Arg("space-requests-sql-file", "The filename for the space requests datastore", true, "space-requests.sql"),
                    new Command.Arg("quotas-sql-file", "The filename for the quotas datastore", true, "quotas.sql"),
                    new Command.Arg("space-usage-sql-file", "The filename for the space usage datastore", true, "space-usage.sql"),
                    new Command.Arg("server-messages-sql-file", "The filename for the server messages datastore", true, "server-messages.sql"),
                    new Command.Arg("transactions-sql-file", "The filename for the transactions datastore", false, "transactions.sql"),
                    new Command.Arg("webroot", "the path to the directory to serve as the web root", false),
                    new Command.Arg("default-quota", "default maximum storage per user", false, Long.toString(1024L * 1024 * 1024)),
                    new Command.Arg("mirror.node.id", "Mirror a server's data locally", false),
                    new Command.Arg("mirror.username", "Mirror a user's data locally", false),
                    new Command.Arg("public-server", "Are we a public server? (allow http GETs to API)", false, "false"),
                    new Command.Arg("run-gateway", "Run a local Peergos gateway", false, "true"),
                    new Command.Arg("gateway-port", "Port to run a local gateway on", false, "9000"),
                    new Command.Arg("collect-metrics", "Export aggregated metrics", false, "false"),
                    new Command.Arg("metrics.address", "Listen address for serving aggregated metrics", false, "localhost"),
                    new Command.Arg("metrics.port", "Port for serving aggregated metrics", false, "8001")
            ).collect(Collectors.toList())
    );

    private static Args bootstrap(Args args) {
        try {
            // This means creating a pki keypair and publishing the public key
            Crypto crypto = initCrypto();
            // setup peergos user and pki keys
            String peergosPassword = args.getArg("peergos.password");
            String pkiUsername = "peergos";
            UserWithRoot peergos = UserUtil.generateUser(pkiUsername, peergosPassword, crypto.hasher, crypto.symmetricProvider,
                    crypto.random, crypto.signer, crypto.boxer, SecretGenerationAlgorithm.getDefaultWithoutExtraSalt()).get();

            boolean useIPFS = args.getBoolean("useIPFS");
            String ipfsApiAddress = args.getArg("ipfs-api-address", "/ip4/127.0.0.1/tcp/5001");
            ContentAddressedStorage dht = useIPFS ?
                    new IpfsDHT(new MultiAddress(ipfsApiAddress)) :
                    new FileContentAddressedStorage(blockstorePath(args),
                            JdbcTransactionStore.build(getDBConnector(args, "transactions-sql-file"), new SqliteCommands()));

            SigningKeyPair peergosIdentityKeys = peergos.getUser();
            PublicKeyHash peergosPublicHash = ContentAddressedStorage.hashKey(peergosIdentityKeys.publicSigningKey);

            String pkiPassword = args.getArg("pki.keygen.password");

            if (peergosPassword.equals(pkiPassword))
                throw new IllegalStateException("Pki password and peergos password must be different!!");
            SigningKeyPair pkiKeys = UserUtil.generateUser(pkiUsername, pkiPassword, crypto.hasher, crypto.symmetricProvider,
                    crypto.random, crypto.signer, crypto.boxer, SecretGenerationAlgorithm.getDefaultWithoutExtraSalt()).get().getUser();
            IpfsTransaction.call(peergosPublicHash,
                    tid -> dht.putSigningKey(peergosIdentityKeys.secretSigningKey.signMessage(
                            pkiKeys.publicSigningKey.serialize()),
                            peergosPublicHash,
                            pkiKeys.publicSigningKey, tid), dht).get();

            String pkiKeyfilePassword = args.getArg("pki.keyfile.password");
            Cborable cipherTextCbor = PasswordProtected.encryptWithPassword(pkiKeys.secretSigningKey.toCbor().toByteArray(),
                    pkiKeyfilePassword,
                    crypto.hasher,
                    crypto.symmetricProvider,
                    crypto.random);
            Files.write(args.fromPeergosDir("pki.secret.key.path"), cipherTextCbor.serialize());
            Files.write(args.fromPeergosDir("pki.public.key.path"), pkiKeys.publicSigningKey.toCbor().toByteArray());
            System.out.println("Peergos user identity hash: " + peergosPublicHash);
            return args.setIfAbsent("peergos.identity.hash", peergosPublicHash.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static final void poststrap(Args args) {
        try {
            // The final step of bootstrapping a new peergos network, which must be run once after network bootstrap
            // This means signing up the peergos user, and adding the pki public key to the peergos user
            Crypto crypto = initCrypto();
            // recreate peergos user and pki keys
            String password = args.getArg("peergos.password");
            String pkiUsername = "peergos";
            UserWithRoot peergos = UserUtil.generateUser(pkiUsername, password, crypto.hasher, crypto.symmetricProvider,
                    crypto.random, crypto.signer, crypto.boxer, SecretGenerationAlgorithm.getDefaultWithoutExtraSalt()).get();

            SigningKeyPair peergosIdentityKeys = peergos.getUser();
            PublicKeyHash peergosPublicHash = ContentAddressedStorage.hashKey(peergosIdentityKeys.publicSigningKey);
            PublicSigningKey pkiPublic =
                    PublicSigningKey.fromByteArray(
                            Files.readAllBytes(args.fromPeergosDir("pki.public.key.path")));
            PublicKeyHash pkiPublicHash = ContentAddressedStorage.hashKey(pkiPublic);
            int webPort = args.getInt("port");
            Optional<String> basicAuth = args.getOptionalArg("basic-auth")
                    .map(a -> "Basic " + Base64.getEncoder().encodeToString(a.getBytes()));
            NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("http://localhost:" + webPort),
                    false, basicAuth).get();
            String pkiFilePassword = args.getArg("pki.keyfile.password");
            SecretSigningKey pkiSecret =
                    SecretSigningKey.fromCbor(CborObject.fromByteArray(PasswordProtected.decryptWithPassword(
                            CborObject.fromByteArray(Files.readAllBytes(args.fromPeergosDir("pki.secret.key.path"))),
                            pkiFilePassword, crypto.hasher, crypto.symmetricProvider, crypto.random)));

            // sign up peergos user
            SecretGenerationAlgorithm algorithm = SecretGenerationAlgorithm.getDefaultWithoutExtraSalt();
            UserContext context = UserContext.signUpGeneral(pkiUsername, password, "", network, crypto, algorithm, x -> {}).get();
            Optional<PublicKeyHash> existingPkiKey = context.getNamedKey("pki").get();
            if (!existingPkiKey.isPresent() || existingPkiKey.get().equals(pkiPublicHash)) {
                SigningPrivateKeyAndPublicHash pkiKeyPair = new SigningPrivateKeyAndPublicHash(pkiPublicHash, pkiSecret);

                // write pki public key to ipfs
                IpfsTransaction.call(peergosPublicHash,
                        tid -> network.dhtClient.putSigningKey(peergosIdentityKeys.secretSigningKey
                                .signMessage(pkiPublic.serialize()), peergosPublicHash, pkiPublic, tid),
                        network.dhtClient).get();
                context.addNamedOwnedKeyAndCommit("pki", pkiKeyPair).join();
            }
            // Create /peergos/releases and make it public
            Optional<FileWrapper> releaseDir = context.getByPath(Paths.get(pkiUsername, "releases")).join();
            if (! releaseDir.isPresent()) {
                context.getUserRoot().join().mkdir("releases", network, false,
                        crypto).join();
                FileWrapper releases = context.getByPath(Paths.get(pkiUsername, "releases")).join().get();
                context.makePublic(releases).join();
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static final Command<UserService> PKI_INIT = new Command<>("pki-init",
            "Bootstrap and start the Peergos PKI Server",
            args -> {
                try {
                    int peergosPort = args.getInt("port");
                    args = args.setIfAbsent("proxy-target", getLocalMultiAddress(peergosPort).toString());
                    MultiAddress ipfsApi = new MultiAddress(args.getArg("ipfs-api-address"));

                    IpfsWrapper ipfs = null;
                    boolean useIPFS = args.getBoolean("useIPFS");
                    if (useIPFS) {
                        ENSURE_IPFS_INSTALLED.main(args);
                        ipfs = startIpfs(args);
                    }

                    args = bootstrap(args);

                    Multihash pkiIpfsNodeId = useIPFS ?
                            new IpfsDHT(ipfsApi).id().get() :
                            new FileContentAddressedStorage(blockstorePath(args),
                                    JdbcTransactionStore.build(getDBConnector(args, "transactions-sql-file"), new SqliteCommands())).id().get();

                    if (ipfs != null)
                        ipfs.stop();
                    args = args.setIfAbsent("pki-node-id", pkiIpfsNodeId.toString());
                    UserService daemon = PEERGOS.main(args);
                    poststrap(args);
                    return daemon;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            },
            Arrays.asList(
                    new Command.Arg("domain", "The hostname to listen on", true, "localhost"),
                    new Command.Arg("port", "The port for the local non tls server to listen on", true, "8000"),
                    new Command.Arg("useIPFS", "Whether to use IPFS or a local datastore", true, "false"),
                    new Command.Arg("mutable-pointers-file", "The filename for the mutable pointers (or :memory: for ram based)", true, "mutable.sql"),
                    new Command.Arg("social-sql-file", "The filename for the follow requests (or :memory: for ram based)", true, "social.sql"),
                    new Command.Arg("transactions-sql-file", "The filename for the open transactions datastore", true, "transactions.sql"),
                    new Command.Arg("space-requests-sql-file", "The filename for the space requests datastore", true, "space-requests.sql"),
                    new Command.Arg("space-usage-sql-file", "The filename for the space usage datastore", true, "space-usage.sql"),
                    new Command.Arg("ipfs-api-address", "ipfs api port", true, "/ip4/127.0.0.1/tcp/5001"),
                    new Command.Arg("ipfs-gateway-address", "ipfs gateway port", true, "/ip4/127.0.0.1/tcp/8080"),
                    new Command.Arg("pki.secret.key.path", "The path to the pki secret key file", true, "test.pki.secret.key"),
                    new Command.Arg("pki.public.key.path", "The path to the pki public key file", true, "test.pki.public.key"),
                    // Secret parameters
                    new Command.Arg("peergos.password", "The password for the 'peergos' user", true),
                    new Command.Arg("pki.keygen.password", "The password to generate the pki key from", true),
                    new Command.Arg("pki.keyfile.password", "The password protecting the pki keyfile", true)
            )
    );

    public static final Command<UserService> PKI = new Command<>("pki",
            "Start the Peergos PKI Server that has already been bootstrapped",
            args -> {
                try {
                    int peergosPort = args.getInt("port");
                    args = args.setIfAbsent("proxy-target", getLocalMultiAddress(peergosPort).toString());

                    IpfsWrapper ipfs = null;
                    boolean useIPFS = args.getBoolean("useIPFS");
                    if (useIPFS) {
                        ENSURE_IPFS_INSTALLED.main(args);
                        ipfs = startIpfs(args);
                    }

                    MultiAddress ipfsApi = new MultiAddress(args.getArg("ipfs-api-address"));

                    Supplier<Connection> transactionDb = getDBConnector(args, "transactions-sql-file");
                    JdbcTransactionStore transactions = JdbcTransactionStore.build(transactionDb, new SqliteCommands());
                    ContentAddressedStorage storage = useIPFS ?
                            new IpfsDHT(ipfsApi) :
                            S3Config.useS3(args) ?
                                    new S3BlockStorage(S3Config.build(args), Cid.decode(args.getArg("ipfs.id")),
                                            BlockStoreProperties.empty(), transactions, new IpfsDHT(ipfsApi)) :
                                    new FileContentAddressedStorage(blockstorePath(args),
                                            transactions);
                    Multihash pkiIpfsNodeId = storage.id().get();

                    if (ipfs != null)
                        ipfs.stop();
                    args = args.setIfAbsent("pki-node-id", pkiIpfsNodeId.toString());
                    return PEERGOS.main(args);
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            },
            Arrays.asList(
                    new Command.Arg("peergos.identity.hash", "The hostname to listen on", true),
                    new Command.Arg("domain", "The hostname to listen on", true, "localhost"),
                    new Command.Arg("port", "The port for the local non tls server to listen on", true, "8000"),
                    new Command.Arg("useIPFS", "Whether to use IPFS or a local datastore", true, "false"),
                    new Command.Arg("mutable-pointers-file", "The filename for the mutable pointers (or :memory: for ram based)", true, "mutable.sql"),
                    new Command.Arg("social-sql-file", "The filename for the follow requests (or :memory: for ram based)", true, "social.sql"),
                    new Command.Arg("transactions-sql-file", "The filename for the open transactions datastore", true, "transactions.sql"),
                    new Command.Arg("space-requests-sql-file", "The filename for the space requests datastore", true, "space-requests.sql"),
                    new Command.Arg("space-usage-sql-file", "The filename for the space usage datastore", true, "space-usage.sql"),
                    new Command.Arg("ipfs-api-address", "ipfs api port", true, "/ip4/127.0.0.1/tcp/5001"),
                    new Command.Arg("ipfs-gateway-address", "ipfs gateway port", true, "/ip4/127.0.0.1/tcp/8080"),
                    new Command.Arg("pki.secret.key.path", "The path to the pki secret key file", true, "test.pki.secret.key"),
                    new Command.Arg("pki.public.key.path", "The path to the pki public key file", true, "test.pki.public.key"),
                    // Secret parameters
                    new Command.Arg("pki.keyfile.password", "The password protecting the pki keyfile", true)
            )
    );

    public static final Command<FuseProcess> FUSE = new Command<>("fuse",
            "Mount a Peergos user's filesystem natively",
            Main::startFuse,
            Stream.of(
                    new Command.Arg("username", "Peergos username", true),
                    new Command.Arg("password", "Peergos password", true),
                    new Command.Arg("webport", "Peergos service address port", false, "8000"),
                    new Command.Arg("mountPoint", "The directory to mount the Peergos filesystem in", true, "peergos")
            ).collect(Collectors.toList())
    );

    public static final Command<PublicGateway> GATEWAY = new Command<>("gateway",
            "Serve websites directly from Peergos",
            Main::startGateway,
            Stream.of(
                    new Command.Arg("port", "service port", false, "9000"),
                    new Command.Arg("peergos-url", "Address of the Peergos server to connect to", false, "http://localhost:8000"),
                    new Command.Arg("domain-suffix", "Domain suffix to accept", false, ".peergos.localhost:9000"),
                    new Command.Arg("domain", "Domain name to bind to,", false, "localhost"),
                    new Command.Arg("public-server", "Are we a public server? (allow http GETs to API)", false, "false"),
                    new Command.Arg("collect-metrics", "Export aggregated metrics", false, "false"),
                    new Command.Arg("metrics.address", "Listen address for serving aggregated metrics", false, "localhost"),
                    new Command.Arg("metrics.port", "Port for serving aggregated metrics", false, "8001")
            ).collect(Collectors.toList())
    );

    public static final Command<Boolean> SHELL = new Command<>("shell",
            "An interactive command-line-interface to a Peergos server.",
            Main::startShell,
            Collections.emptyList()
    );

    public static UserService startPeergos(Args a) {
        try {
            Crypto crypto = initCrypto();
            PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, crypto.signer);
            int webPort = a.getInt("port");
            MultiAddress localPeergosApi = getLocalMultiAddress(webPort);
            a.setIfAbsent("proxy-target", localPeergosApi.toString());

            boolean useIPFS = a.getBoolean("useIPFS");
            IpfsWrapper ipfsWrapper = null;
            if (useIPFS) {
                ENSURE_IPFS_INSTALLED.main(a);
                ipfsWrapper = IPFS.main(a);
            }
            boolean doExportAggregatedMetrics = a.getBoolean("collect-metrics");
            if (doExportAggregatedMetrics) {
                int exporterPort = a.getInt("metrics.port");
                String exporterAddress = a.getArg("metrics.address");
                AggregatedMetrics.startExporter(exporterAddress, exporterPort);
            }

            Multihash pkiServerNodeId = getPkiServerId(a);
            String domain = a.getArg("domain");
            InetSocketAddress userAPIAddress = new InetSocketAddress(domain, webPort);

            JavaPoster p2pHttpProxy = buildP2pHttpProxy(a);

            SqlSupplier sqlCommands = getSqlCommands(a);

            Supplier<Connection> dbConnectionPool = getDBConnector(a, "transactions-sql-file");
            TransactionStore transactions = buildTransactionStore(a, dbConnectionPool);

            DeletableContentAddressedStorage localStorage = buildLocalStorage(a, transactions);
            JdbcIpnsAndSocial rawPointers = buildRawPointers(a,
                    getDBConnector(a, "mutable-pointers-file", dbConnectionPool));
            boolean enableGC = a.getBoolean("enable-gc", false);
            GarbageCollector gc = null;
            if (enableGC) {
                if (S3Config.useS3(a))
                    throw new IllegalStateException("GC should be run separately when using S3!");
                gc = new GarbageCollector(localStorage, rawPointers);
                gc.start(a.getInt("gc.period.millis", 60 * 60 * 1000), s -> Futures.of(true));
            }

            String hostname = a.getArg("domain");
            Multihash nodeId = localStorage.id().get();

            MutablePointers localPointers = UserRepository.build(localStorage, rawPointers);
            MutablePointersProxy proxingMutable = new HttpMutablePointers(p2pHttpProxy, pkiServerNodeId);

            CoreNode core = buildCorenode(a, localStorage, transactions, rawPointers, localPointers, proxingMutable);

            QuotaAdmin userQuotas = buildSpaceQuotas(a, localStorage, core,
                    getDBConnector(a, "space-requests-sql-file", dbConnectionPool),
                    getDBConnector(a, "quotas-sql-file", dbConnectionPool));
            CoreNode signupFilter = new SignUpFilter(core, userQuotas, nodeId);

            Supplier<Connection> usageDb = getDBConnector(a, "space-usage-sql-file", dbConnectionPool);
            UsageStore usageStore = new JdbcUsageStore(usageDb, sqlCommands);
            Hasher hasher = crypto.hasher;
            SpaceCheckingKeyFilter.update(usageStore, userQuotas, core, localPointers, localStorage, hasher);
            SpaceCheckingKeyFilter spaceChecker = new SpaceCheckingKeyFilter(core, localPointers, localStorage,
                    hasher, userQuotas, usageStore);
            CorenodeEventPropagator corePropagator = new CorenodeEventPropagator(signupFilter);
            corePropagator.addListener(spaceChecker::accept);
            MutableEventPropagator localMutable = new MutableEventPropagator(localPointers);
            localMutable.addListener(spaceChecker::accept);

            ContentAddressedStorage filteringDht = new WriteFilter(localStorage, spaceChecker::allowWrite);
            ContentAddressedStorageProxy proxingDht = new ContentAddressedStorageProxy.HTTP(p2pHttpProxy);
            ContentAddressedStorage p2pDht = new ContentAddressedStorage.Proxying(filteringDht, proxingDht, nodeId, core);

            Path blacklistPath = a.fromPeergosDir("blacklist_file", "blacklist.txt");
            PublicKeyBlackList blacklist = new UserBasedBlacklist(blacklistPath, core, localMutable, p2pDht, hasher);
            MutablePointers blockingMutablePointers = new BlockingMutablePointers(new PinningMutablePointers(localMutable, p2pDht), blacklist);
            MutablePointers p2mMutable = new ProxyingMutablePointers(nodeId, core, blockingMutablePointers, proxingMutable);

            SocialNetworkProxy httpSocial = new HttpSocialNetwork(p2pHttpProxy, p2pHttpProxy);

            Supplier<Connection> socialDatabase = getDBConnector(a, "social-sql-file", dbConnectionPool);

            JdbcIpnsAndSocial rawSocial = new JdbcIpnsAndSocial(socialDatabase, sqlCommands);
            SocialNetwork local = UserRepository.build(p2pDht, rawSocial);
            SocialNetwork p2pSocial = new ProxyingSocialNetwork(nodeId, core, local, httpSocial);

            Set<String> adminUsernames = Arrays.asList(a.getArg("admin-usernames").split(","))
                    .stream()
                    .collect(Collectors.toSet());
            boolean enableWaitlist = a.getBoolean("enable-wait-list", false);
            Admin storageAdmin = new Admin(adminUsernames, userQuotas, core, localStorage, enableWaitlist);
            HttpSpaceUsage httpSpaceUsage = new HttpSpaceUsage(p2pHttpProxy, p2pHttpProxy);
            ProxyingSpaceUsage p2pSpaceUsage = new ProxyingSpaceUsage(nodeId, corePropagator, spaceChecker, httpSpaceUsage);
            UserService peergos = new UserService(p2pDht, crypto, corePropagator, p2pSocial, p2mMutable, storageAdmin,
                    p2pSpaceUsage, new ServerMessageStore(getDBConnector(a, "server-messages-sql-file", dbConnectionPool),
                    sqlCommands, core, p2pDht), gc);
            InetSocketAddress localAddress = new InetSocketAddress("localhost", userAPIAddress.getPort());
            Optional<Path> webroot = a.hasArg("webroot") ?
                    Optional.of(Paths.get(a.getArg("webroot"))) :
                    Optional.empty();
            boolean useWebAssetCache = a.getBoolean("webcache", true);
            Optional<String> tlsHostname = hostname.equals("localhost") ? Optional.empty() : Optional.of(hostname);
            Optional<UserService.TlsProperties> tlsProps =
                    tlsHostname.map(host -> new UserService.TlsProperties(host, a.getArg("tls.keyfile.password")));
            int maxConnectionQueue = a.getInt("max-connection-queue", 500);
            int handlerThreads = a.getInt("handler-threads", 50);
            boolean isPublicServer = a.getBoolean("public-server", false);
            Optional<String> basicAuth = a.getOptionalArg("basic-auth");
            boolean enableCors = a.getBoolean("enable-cors", false);
            peergos.initAndStart(localAddress, tlsProps, basicAuth, webroot, useWebAssetCache, isPublicServer,
                    maxConnectionQueue, handlerThreads, enableCors);
            boolean isPkiNode = nodeId.equals(pkiServerNodeId);
            if (! isPkiNode && useIPFS) {
                int pkiNodeSwarmPort = a.getInt("pki.node.swarm.port");
                InetAddress pkiNodeIpAddress = InetAddress.getByName(a.getArg("pki.node.ipaddress"));
                ipfsWrapper.connectToNode(new InetSocketAddress(pkiNodeIpAddress, pkiNodeSwarmPort), pkiServerNodeId);
                ((MirrorCoreNode) core).start();
            }
            spaceChecker.calculateUsage();

            if (a.hasArg("mirror.node.id")) {
                Multihash nodeToMirrorId = Cid.decode(a.getArg("mirror.node.id"));
                NetworkAccess localApi = Builder.buildLocalJavaNetworkAccess(webPort).join();
                new Thread(() -> {
                    while (true) {
                        try {
                            Mirror.mirrorNode(nodeToMirrorId, localApi, rawPointers, localStorage);
                            try {
                                Thread.sleep(60_000);
                            } catch (InterruptedException f) {}
                        } catch (Exception e) {
                            e.printStackTrace();
                            try {
                                Thread.sleep(5_000);
                            } catch (InterruptedException f) {}
                        }
                    }
                }).start();
            }
            if (a.hasArg("mirror.username")) {
                NetworkAccess localApi = Builder.buildLocalJavaNetworkAccess(webPort).join();
                new Thread(() -> {
                    while (true) {
                        try {
                            Mirror.mirrorUser(a.getArg("mirror.username"), localApi, rawPointers, localStorage);
                            try {
                                Thread.sleep(60_000);
                            } catch (InterruptedException f) {}
                        } catch (Exception e) {
                            e.printStackTrace();
                            try {
                                Thread.sleep(5_000);
                            } catch (InterruptedException f) {}
                        }
                    }
                }).start();
            }
            if (a.getBoolean("run-gateway")) {
                Args gatewayArgs = a.with("port", a.getArg("gateway-port"))
                        .with("peergos-url", "http://localhost:" + a.getArg("port"));
                GATEWAY.main(gatewayArgs);
            }
            a.saveToFileIfAbsent();
            System.out.println("\n" +
                    "█╗█╗█╗█╗   ██████╗ ███████╗███████╗██████╗  ██████╗  ██████╗ ███████╗   █╗█╗█╗█╗\n" +
                    " █████╔╝   ██╔══██╗██╔════╝██╔════╝██╔══██╗██╔════╝ ██╔═══██╗██╔════╝    █████╔╝\n" +
                    " ██ ██║    ██████╔╝█████╗  █████╗  ██████╔╝██║  ███╗██║   ██║███████╗    ██ ██║\n" +
                    " █████║    ██╔═══╝ ██╔══╝  ██╔══╝  ██╔══██╗██║   ██║██║   ██║╚════██║    █████║\n" +
                    "███████╗   ██║     ███████╗███████╗██║  ██║╚██████╔╝╚██████╔╝███████║   ███████╗\n" +
                    "╚══════╝   ╚═╝     ╚══════╝╚══════╝╚═╝  ╚═╝ ╚═════╝  ╚═════╝ ╚══════╝   ╚══════╝");
            boolean generateToken = a.getBoolean("generate-token", false);
            if (generateToken) {
                System.out.println("Generating signup token...");
                String token = userQuotas.generateToken(crypto.random);
                System.out.println("Peergos daemon started. Browse to http://localhost:" + webPort + "/?signup=true&token="
                        + token + " to sign up.");
            } else
                System.out.println("Peergos daemon started. Browse to http://localhost:" + webPort + "/ to sign up or login. ");
            InstanceAdmin.VersionInfo version = storageAdmin.getVersionInfo().join();
            System.out.println("Running version " + version);
            return peergos;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static PublicGateway startGateway(Args a) {
        Crypto crypto = initCrypto();
        String peergosUrl = a.getArg("peergos-url");
        String domainSuffix = a.getArg("domain-suffix");
        try {
            URL api = new URL(peergosUrl);
            NetworkAccess network = Builder.buildJavaNetworkAccess(api,
                    ! peergosUrl.startsWith("http://localhost"), Optional.empty()).join();
            PublicGateway gateway = new PublicGateway(domainSuffix, crypto, network);

            String domain = a.getArg("domain");
            int webPort = a.getInt("port");
            InetSocketAddress userAPIAddress = new InetSocketAddress(domain, webPort);
            InetSocketAddress localAddress = new InetSocketAddress("localhost", userAPIAddress.getPort());
            boolean isPublicServer = a.getBoolean("public-server", false);
            int maxConnectionQueue = a.getInt("max-connection-queue", 500);
            int handlerThreads = a.getInt("handler-threads", 50);
            gateway.initAndStart(localAddress, isPublicServer, maxConnectionQueue, handlerThreads);
            return gateway;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static FuseProcess startFuse(Args a) {
        String username = a.getArg("username");
        String password = a.getArg("password");

        int webPort = a.getInt("webport");
        try {
            Files.createTempDirectory("peergos").toString();
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
        String mountPath = a.getArg("mountPoint");
        Path path = Paths.get(mountPath);

        path.toFile().mkdirs();

        System.out.println("\n\nPeergos mounted at " + path + "\n\n");
        try {
            NetworkAccess network = Builder.buildLocalJavaNetworkAccess(webPort).get();
            Crypto crypto = initCrypto();
            UserContext userContext = UserContext.signIn(username, password, network, crypto).join();
            PeergosFS peergosFS = new PeergosFS(userContext);
            FuseProcess fuseProcess = new FuseProcess(peergosFS, path);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> fuseProcess.close(), "Fuse shutdown"));

            fuseProcess.start();
            return fuseProcess;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }


    public static IpfsWrapper startIpfs(Args a) {
        // test if ipfs is already running
        String ipfsApiAddress = a.getArg("ipfs-api-address");
        if (IpfsWrapper.isHttpApiListening(ipfsApiAddress)) {
            throw new IllegalStateException("IPFS is already running on api " + ipfsApiAddress);
        }

        IpfsWrapper ipfs = IpfsWrapper.build(a);

        if (a.getBoolean("ipfs-manage-runtime", true))
            IpfsWrapper.launchAndManage(ipfs);
        else {
            IpfsWrapper.launchOnce(ipfs);
        }
        // wait for daemon to finish starting
        ipfs.waitForDaemon(10);
        return ipfs;
    }

    public static Boolean startShell(Args args) {
        CLI.main(new String[]{});
        return true;
    }

    public static final Command<Void> MAIN = new Command<>("Main",
            "Run a Peergos command",
            args -> {
                System.out.println("Run with -help to show options");
                return null;
            },
            Collections.emptyList(),
            Arrays.asList(
                    PEERGOS,
                    SHELL,
                    FUSE,
                    QuotaCLI.QUOTA,
                    ServerMessages.SERVER_MESSAGES,
                    GATEWAY,
                    INSTALL_AND_RUN_IPFS,
                    PKI,
                    PKI_INIT
            )
    );

    public static MultiAddress getLocalMultiAddress(int port) {
        return new MultiAddress("/ip4/127.0.0.1/tcp/" + port);
    }

    public static MultiAddress getLocalBootstrapAddress(int port, Multihash nodeId) {
        return new MultiAddress("/ip4/127.0.0.1/tcp/" + port + "/ipfs/"+ nodeId);
    }

    public static void main(String[] args) {
        MAIN.main(Args.parse(args));
    }
}
