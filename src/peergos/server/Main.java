package peergos.server;

import com.webauthn4j.data.client.*;
import peergos.server.cli.CLI;
import peergos.server.login.*;
import peergos.server.messages.*;
import peergos.server.space.*;
import peergos.server.sql.*;
import peergos.server.storage.admin.*;
import peergos.server.storage.auth.*;
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
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.MultiAddress;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.login.mfa.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.storage.controller.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main extends Builder {
    public static final String PEERGOS_PATH = "PEERGOS_PATH";
    public static final Path DEFAULT_PEERGOS_DIR_PATH =
            Paths.get(System.getProperty("user.home"), ".peergos");

    static {
        PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, initCrypto().signer);
    }

    public static final Command.Arg ARG_TRANSACTIONS_SQL_FILE =
        new Command.Arg("transactions-sql-file", "The filename for the transactions datastore", false, "transactions.sql");
    public static final Command.Arg ARG_BAT_STORE =
                    new Command.Arg("bat-store", "The filename for the BAT store (or :memory: for ram based)", true, "bats.sql");
    public static final Command.Arg ARG_USE_IPFS =
        new Command.Arg("useIPFS", "Use IPFS for storage or a local disk store if not", false, "true");
    public static final Command.Arg ARG_IPFS_API_ADDRESS =
        new Command.Arg("ipfs-api-address", "IPFS API address", true, "/ip4/127.0.0.1/tcp/5001");
    public static final Command.Arg ARG_IPFS_PROXY_TARGET =
        new Command.Arg("proxy-target", "Proxy target for p2p http requests", false, "/ip4/127.0.0.1/tcp/8003");

    public static final Command.Arg ARG_BOOTSTRAP_NODES = new Command.Arg("ipfs-config-bootstrap-node-list",
            "Comma separated list of IPFS bootstrap nodes.", false, IpfsWrapper.DEFAULT_BOOTSTRAP_LIST);

    public static Command<IpfsWrapper> IPFS = new Command<>("ipfs",
            "Configure and start IPFS daemon",
            Main::startIpfs,
            Arrays.asList(
                    new Command.Arg("IPFS_PATH", "Path to IPFS directory. Defaults to $PEERGOS_PATH/.ipfs, or ~/.peergos/.ipfs", false),
                    ARG_IPFS_API_ADDRESS,
                    new Command.Arg("ipfs-gateway-address", "IPFS Gateway port", false, "/ip4/127.0.0.1/tcp/8080"),
                    new Command.Arg("ipfs-swarm-port", "IPFS Swarm port", false, "4001"),
                    ARG_IPFS_PROXY_TARGET,
                    ARG_BOOTSTRAP_NODES,
                    new Command.Arg("collect-metrics", "Export aggregated metrics", false, "false"),
                    new Command.Arg("metrics.address", "Listen address for serving aggregated metrics", false, "localhost"),
                    new Command.Arg("ipfs.metrics.port", "Port for serving aggregated ipfs metrics", false, "8101"),
                    new Command.Arg("s3.path", "Path of data store in S3", false),
                    new Command.Arg("s3.bucket", "S3 bucket name", false),
                    new Command.Arg("s3.region", "S3 region", false),
                    new Command.Arg("s3.accessKey", "S3 access key", false),
                    new Command.Arg("s3.secretKey", "S3 secret key", false),
                    new Command.Arg("s3.region.endpoint", "Base url for S3 service", false),
                    new Command.Arg("block-store-filter", "Indicate blockstore filter type. Can be 'none', 'bloom', 'infini'", false),
                    new Command.Arg("block-store-filter-false-positive-rate", "The false positive rate to apply to the block-store-filter. ", false),
                    ARG_BAT_STORE
                    )
    );


    public static final Command<UserService> PEERGOS = new Command<>("daemon",
            "The user facing Peergos server",
            Main::startPeergos,
            Stream.of(
                    new Command.Arg("port", "service port", false, "8000"),
                    new Command.Arg("peergos.identity.hash", "The hash of peergos user's public key, this is used to bootstrap the pki", true, "z59vuwzfFDp3ZA8ZpnnmHEuMtyA1q34m3Th49DYXQVJntWpxdGrRqXi"),
                    new Command.Arg("pki-node-id", "Ipfs node id of the pki node", true, "QmVdFZgHnEgcedCS2G2ZNiEN59LuVrnRm7z3yXtEBv2XiF"),
                    new Command.Arg("pki.node.ipaddress", "IP address of the pki node", true, "172.104.157.121"),
                    ARG_IPFS_API_ADDRESS,
                    new Command.Arg("ipfs-gateway-address", "IPFS Gateway address", false, "/ip4/127.0.0.1/tcp/8080"),
                    ARG_IPFS_PROXY_TARGET,
                    ARG_BOOTSTRAP_NODES,
                    new Command.Arg("pki.node.swarm.port", "Swarm port of the pki node", true, "5001"),
                    new Command.Arg("domain", "Domain name to bind to", false, "localhost"),
                    new Command.Arg("public-domain", "The public domain name for this server (required if TLS is managed upstream)", false),
                    ARG_USE_IPFS,
                    ARG_BAT_STORE,
                    new Command.Arg("mutable-pointers-file", "The filename for the mutable pointers datastore", true, "mutable.sql"),
                    new Command.Arg("social-sql-file", "The filename for the follow requests datastore", true, "social.sql"),
                    new Command.Arg("space-requests-sql-file", "The filename for the space requests datastore", true, "space-requests.sql"),
                    new Command.Arg("account-sql-file", "The filename for the login datastore", true, "login.sql"),
                    new Command.Arg("quotas-sql-file", "The filename for the quotas datastore", true, "quotas.sql"),
                    new Command.Arg("space-usage-sql-file", "The filename for the space usage datastore", true, "space-usage.sql"),
                    new Command.Arg("server-messages-sql-file", "The filename for the server messages datastore", true, "server-messages.sql"),
                    ARG_TRANSACTIONS_SQL_FILE,
                    new Command.Arg("webroot", "the path to the directory to serve as the web root", false),
                    new Command.Arg("default-quota", "default maximum storage per user", false, Long.toString(1024L * 1024 * 1024)),
                    new Command.Arg("admin-usernames", "A comma separated list of usernames who can approve local space requests", false),
                    new Command.Arg("mirror.node.id", "Mirror a server's data locally", false),
                    new Command.Arg("mirror.username", "Mirror a user's data locally", false),
                    new Command.Arg("mirror.bat", "BatWithId to enable mirroring a user's private data", false),
                    new Command.Arg("login-keypair", "The keypair used to mirror the login data for a user (use with 'mirror.username' arg)", false),
                    new Command.Arg("public-server", "Are we a public server? (allow http GETs to API)", false, "false"),
                    new Command.Arg("run-gateway", "Run a local Peergos gateway", false),
                    new Command.Arg("gateway-port", "Port to run a local gateway on", false, "9000"),
                    new Command.Arg("app-dev-target", "URL for app assets for localhost app development", false),
                    new Command.Arg("collect-metrics", "Export aggregated metrics", false, "false"),
                    new Command.Arg("metrics.address", "Listen address for serving aggregated metrics", false, "localhost"),
                    new Command.Arg("metrics.port", "Port for serving aggregated metrics", false, "8001"),
                    new Command.Arg("ipfs.metrics.port", "Port for serving aggregated ipfs metrics", false)
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
            ContentAddressedStorage dht = useIPFS ?
                    new ContentAddressedStorage.HTTP(Builder.buildIpfsApi(args), false, crypto.hasher) :
                    new FileContentAddressedStorage(blockstorePath(args),
                            JdbcTransactionStore.build(getDBConnector(args, "transactions-sql-file"), new SqliteCommands()),
                            (a, b, c, d) -> Futures.of(true), crypto.hasher);

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
            LocalDate expiry = LocalDate.now().plusMonths(2);
            UserContext context = UserContext.signUpGeneral(pkiUsername, password, "", Optional.empty(),
                    id -> {}, Optional.empty(), expiry, network, crypto, algorithm, x -> {}).join();
            Optional<PublicKeyHash> existingPkiKey = context.getNamedKey("pki").get();
            if (!existingPkiKey.isPresent() || existingPkiKey.get().equals(pkiPublicHash)) {
                SigningPrivateKeyAndPublicHash pkiKeyPair = new SigningPrivateKeyAndPublicHash(pkiPublicHash, pkiSecret);

                // write pki public key to ipfs
                IpfsTransaction.call(context.signer.publicKeyHash,
                        tid -> network.dhtClient.putSigningKey(context.signer.secret
                                .signMessage(pkiPublic.serialize()), context.signer.publicKeyHash, pkiPublic, tid),
                        network.dhtClient).join();
                context.addNamedOwnedKeyAndCommit("pki", pkiKeyPair).join();
            }
            System.out.println("Peergos user identity hash: " + context.signer.publicKeyHash);
            // Create /peergos/releases and make it public
            Optional<FileWrapper> releaseDir = context.getByPath(PathUtil.get(pkiUsername, "releases")).join();
            if (! releaseDir.isPresent()) {
                context.getUserRoot().join().mkdir("releases", network, false,
                        Optional.empty(), crypto).join();
                FileWrapper releases = context.getByPath(PathUtil.get(pkiUsername, "releases")).join().get();
                context.makePublic(releases).join();
            }

            // Create /peergos/app-gallery and make it public
            String appGalleryFolderName = "recommended-apps";
            Optional<FileWrapper> appGalleryDir = context.getByPath(PathUtil.get(pkiUsername, appGalleryFolderName)).join();
            if (! appGalleryDir.isPresent()) {
                context.getUserRoot().join().mkdir(appGalleryFolderName, network, false,
                        Optional.empty(), crypto).join();
                FileWrapper appGalleryFolder = context.getByPath(PathUtil.get(pkiUsername, appGalleryFolderName)).join().get();
                String contents = "<html></html>";
                byte[] data = contents.getBytes();
                appGalleryFolder = appGalleryFolder.uploadFileJS("index.html", new AsyncReader.ArrayBacked(data), 0,data.length, false,
                        appGalleryFolder.mirrorBatId(), network, crypto, l -> {}, context.getTransactionService(), f -> Futures.of(false)).join();
                context.makePublic(appGalleryFolder).join();
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
                    Crypto crypto = initCrypto();

                    IpfsWrapper ipfs = null;
                    boolean useIPFS = args.getBoolean("useIPFS");
                    if (useIPFS) {
                        ipfs = startIpfs(args);
                    }

                    args = bootstrap(args);

                    BatCave batStore = new JdbcBatCave(getDBConnector(args, "bat-store"), getSqlCommands(args));
                    BlockRequestAuthoriser blockRequestAuthoriser = Builder.blockAuthoriser(args, batStore, crypto.hasher);
                    Multihash pkiIpfsNodeId = useIPFS ?
                            new ContentAddressedStorage.HTTP(Builder.buildIpfsApi(args), false, crypto.hasher).id().join() :
                            new FileContentAddressedStorage(blockstorePath(args),
                                    JdbcTransactionStore.build(getDBConnector(args, "transactions-sql-file"), new SqliteCommands()),
                                    blockRequestAuthoriser, crypto.hasher).id().get();

                    if (ipfs != null)
                        ipfs.stop();
                    args = args.setIfAbsent("pki-node-id", pkiIpfsNodeId.toString());
                    if (useIPFS) {
                        boolean saveConfigFile = !args.hasArg("ipfs.identity.peerid");
                        args = args.setArg("ipfs.identity.peerid", ipfs.ipfsConfigParams.identity.get().peerId.toBase58());
                        args = args.setArg("ipfs.identity.priv-key", Base64.getEncoder().encodeToString(ipfs.ipfsConfigParams.identity.get().privKeyProtobuf));
                        if (saveConfigFile) {
                            args.saveToFile();
                        }
                    }

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
                    new Command.Arg("legacy-raw-blocks-file", "The filename for the list of legacy raw blocks (or :memory: for ram based)", true, "legacyraw.sql"),
                    new Command.Arg("bat-store", "The filename for the BAT store (or :memory: for ram based)", true, "bats.sql"),
                    new Command.Arg("mutable-pointers-file", "The filename for the mutable pointers (or :memory: for ram based)", true, "mutable.sql"),
                    new Command.Arg("social-sql-file", "The filename for the follow requests (or :memory: for ram based)", true, "social.sql"),
                    new Command.Arg("transactions-sql-file", "The filename for the open transactions datastore", true, "transactions.sql"),
                    new Command.Arg("space-requests-sql-file", "The filename for the space requests datastore", true, "space-requests.sql"),
                    new Command.Arg("account-sql-file", "The filename for the login datastore", true, "login.sql"),
                    new Command.Arg("space-usage-sql-file", "The filename for the space usage datastore", true, "space-usage.sql"),
                    new Command.Arg("ipfs-api-address", "ipfs api port", true, "/ip4/127.0.0.1/tcp/5001"),
                    new Command.Arg("ipfs-gateway-address", "ipfs gateway port", true, "/ip4/127.0.0.1/tcp/8080"),
                    ARG_IPFS_PROXY_TARGET,
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
                    Crypto crypto = initCrypto();

                    IpfsWrapper ipfs = null;
                    boolean useIPFS = args.getBoolean("useIPFS");
                    if (useIPFS) {
                        ipfs = startIpfs(args);
                    }

                    Supplier<Connection> transactionDb = getDBConnector(args, "transactions-sql-file");
                    SqliteCommands sqlCommands = new SqliteCommands();
                    JdbcTransactionStore transactions = JdbcTransactionStore.build(transactionDb, sqlCommands);
                    BatCave batStore = new JdbcBatCave(getDBConnector(args, "bat-store", transactionDb), sqlCommands);
                    BlockRequestAuthoriser authoriser = Builder.blockAuthoriser(args, batStore, crypto.hasher);

                    ContentAddressedStorage storage = useIPFS ?
                            new ContentAddressedStorage.HTTP(Builder.buildIpfsApi(args), false, crypto.hasher) :
                            S3Config.useS3(args) ?
                                    new S3BlockStorage(S3Config.build(args, Optional.empty()), Cid.decode(args.getArg("ipfs.id")),
                                            BlockStoreProperties.empty(), transactions, authoriser, new RamBlockMetadataStore(),
                                            new RamBlockCache(1024, 1000), crypto.hasher, new DeletableContentAddressedStorage.HTTP(Builder.buildIpfsApi(args), false, crypto.hasher),
                                            new DeletableContentAddressedStorage.HTTP(Builder.buildIpfsApi(args), false, crypto.hasher)) :
                                    new FileContentAddressedStorage(blockstorePath(args),
                                            transactions, authoriser, crypto.hasher);
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
                    new Command.Arg("legacy-raw-blocks-file", "The filename for the list of legacy raw blocks (or :memory: for ram based)", true, "legacyraw.sql"),
                    new Command.Arg("bat-store", "The filename for the BAT store (or :memory: for ram based)", true, "bats.sql"),
                    new Command.Arg("mutable-pointers-file", "The filename for the mutable pointers (or :memory: for ram based)", true, "mutable.sql"),
                    new Command.Arg("social-sql-file", "The filename for the follow requests (or :memory: for ram based)", true, "social.sql"),
                    new Command.Arg("transactions-sql-file", "The filename for the open transactions datastore", true, "transactions.sql"),
                    new Command.Arg("space-requests-sql-file", "The filename for the space requests datastore", true, "space-requests.sql"),
                    new Command.Arg("space-usage-sql-file", "The filename for the space usage datastore", true, "space-usage.sql"),
                    ARG_IPFS_API_ADDRESS,
                    new Command.Arg("ipfs-gateway-address", "ipfs gateway port", true, "/ip4/127.0.0.1/tcp/8080"),
                    ARG_IPFS_PROXY_TARGET,
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
                    new Command.Arg("peergos-url", "Peergos service address", false, "https://peergos.net"),
                    new Command.Arg("mountPoint", "The directory to mount the Peergos filesystem in", true, "peergos")
            ).collect(Collectors.toList())
    );

    public static final Command<InstanceAdmin.VersionInfo> VERSION = new Command<>("version",
            "Print the Peergos version",
            a -> {
                InstanceAdmin.VersionInfo version = new InstanceAdmin.VersionInfo(UserService.CURRENT_VERSION, Admin.getSourceVersion());
                System.out.println("Version: " + version);
                return version;
            },
            Collections.emptyList()
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

    public static final Command<Boolean> MIGRATE = new Command<>("migrate",
            "Move a Peergos account to this server.",
            Main::migrate,
            Stream.of(
                      new Command.Arg("peergos-url", "Address of the Peergos server to migrate to", false, "http://localhost:8000")
            ).collect(Collectors.toList())
    );

    public static final Command<Boolean> LINK_IDENTITY = new Command<>("link",
            "Link your Peergos identity to an account on another service.",
            a -> {
                try {
                    Crypto crypto = Main.initCrypto();
                    String peergosUrl = a.getArg("peergos-url");
                    URL api = new URL(peergosUrl);
                    NetworkAccess network = Builder.buildJavaNetworkAccess(api, ! peergosUrl.startsWith("http://localhost")).join();
                    LinkIdentity.link(a, network, crypto);
                    return true;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            },
            Stream.of(
                      new Command.Arg("peergos-url", "Address of the Peergos server", false, "http://localhost:8000"),
                      new Command.Arg("username", "Your Peergos username", true),
                      new Command.Arg("service", "The other service, e.g. Twitter", true),
                      new Command.Arg("service-username", "Your username on the other service", true),
                      new Command.Arg("publish", "Whether the identity proof file should be made public", false, "false"),
                      new Command.Arg("encrypted", "Whether the identity proof should be private", false, "false")
            ).collect(Collectors.toList())
    );

    public static final Command<Boolean> VERIFY_IDENTITY = new Command<>("verify",
            "Verify an identity link post from another service.",
            a -> {
                try {
                    Main.initCrypto();
                    String peergosUrl = a.getArg("peergos-url");
                    URL api = new URL(peergosUrl);
                    NetworkAccess network = Builder.buildJavaNetworkAccess(api, ! peergosUrl.startsWith("http://localhost")).join();
                    LinkIdentity.verify(a, network);
                    return true;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            },
            Stream.of(
                      new Command.Arg("peergos-url", "Address of the Peergos server", false, "http://localhost:8000"),
                      new Command.Arg("username", "Your Peergos username", true),
                      new Command.Arg("service", "The other service, e.g. Twitter", true),
                      new Command.Arg("service-username", "Your username on the other service", true),
                      new Command.Arg("signature", "The signature of the link included in the post", true)
                              ).collect(Collectors.toList())
    );

    public static final Command<Void> IDENTITY = new Command<>("identity",
            "Create or verify an identity proof",
            args -> {
                System.out.println("Run with -help to show options");
                return null;
            },
            Collections.emptyList(),
            Arrays.asList(
                    LINK_IDENTITY,
                    VERIFY_IDENTITY
            )
    );

    public static UserService startPeergos(Args a) {
        try {
            Crypto crypto = initCrypto();
            Hasher hasher = crypto.hasher;
            PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, crypto.signer);

            System.out.println("Starting Peergos daemon version: " + new InstanceAdmin.VersionInfo(UserService.CURRENT_VERSION, Admin.getSourceVersion()));

            SqlSupplier sqlCommands = getSqlCommands(a);

            boolean useIPFS = a.getBoolean("useIPFS");
            Supplier<Connection> dbConnectionPool = getDBConnector(a, "transactions-sql-file");
            BatCave batStore = new JdbcBatCave(getDBConnector(a, "bat-store", dbConnectionPool), sqlCommands);
            BlockRequestAuthoriser blockAuth = blockAuthoriser(a, batStore, hasher);
            BlockMetadataStore meta = buildBlockMetadata(a);
            IpfsWrapper ipfsWrapper = useIPFS ? IpfsWrapper.launch(a, blockAuth, meta) : null;

            boolean doExportAggregatedMetrics = a.getBoolean("collect-metrics");
            if (doExportAggregatedMetrics) {
                int exporterPort = a.getInt("metrics.port");
                String exporterAddress = a.getArg("metrics.address");
                AggregatedMetrics.startExporter(exporterAddress, exporterPort);
            }
            MultiAddress localP2PApi = new MultiAddress(a.getArg("proxy-target"));

            Multihash pkiServerNodeId = getPkiServerId(a);
            String listeningHost = a.getArg("domain");
            int webPort = a.getInt("port");
            InetSocketAddress userAPIAddress = new InetSocketAddress(listeningHost, webPort);
            boolean localhostApi = userAPIAddress.getHostName().equals("localhost");
            if (! localhostApi)
                System.out.println("Warning: listening on non localhost address: " + listeningHost);

            JavaPoster p2pHttpProxy = buildP2pHttpProxy(a);

            TransactionStore transactions = buildTransactionStore(a, dbConnectionPool);

            DeletableContentAddressedStorage localStorage = buildLocalStorage(a, meta, transactions, blockAuth, crypto.hasher);
            JdbcIpnsAndSocial rawPointers = buildRawPointers(a,
                    getDBConnector(a, "mutable-pointers-file", dbConnectionPool));

            Multihash nodeId = localStorage.id().get();

            MutablePointers localPointers = UserRepository.build(localStorage, rawPointers);
            MutablePointersProxy proxingMutable = new HttpMutablePointers(p2pHttpProxy, pkiServerNodeId);

            Supplier<Connection> usageDb = getDBConnector(a, "space-usage-sql-file", dbConnectionPool);
            UsageStore usageStore = new JdbcUsageStore(usageDb, sqlCommands);
            boolean enableGC = a.getBoolean("enable-gc", false);
            GarbageCollector gc = null;
            if (enableGC) {
                boolean useS3 = S3Config.useS3(a);
                boolean listRawBlocks = useS3 && a.getBoolean("s3.versioned-bucket");
                gc = new GarbageCollector(localStorage, rawPointers, usageStore, listRawBlocks);
                Function<Stream<Map.Entry<PublicKeyHash, byte[]>>, CompletableFuture<Boolean>> snapshotSaver =
                        useS3 ?
                                ((S3BlockStorage) localStorage)::savePointerSnapshot :
                                s -> Futures.of(true);
                int gcInterval = 12 * 60 * 60 * 1000;
                gc.start(a.getInt("gc.period.millis", gcInterval), snapshotSaver);
            }

            JdbcIpnsAndSocial rawSocial = new JdbcIpnsAndSocial(getDBConnector(a, "social-sql-file", dbConnectionPool), sqlCommands);
            HttpSpaceUsage httpSpaceUsage = new HttpSpaceUsage(p2pHttpProxy, p2pHttpProxy);

            Optional<String> tlsHostname = a.hasArg("tls.keyfile.password") ? Optional.of(listeningHost) : Optional.empty();
            Optional<String> publicHostname = tlsHostname.isPresent() ? tlsHostname : a.getOptionalArg("public-domain");
            Origin origin = new Origin(publicHostname.map(host -> "https://" + host).orElse("http://localhost:" + webPort));
            String rpId = publicHostname.orElse("localhost");
            JdbcAccount rawAccount = new JdbcAccount(getDBConnector(a, "account-sql-file", dbConnectionPool), sqlCommands, origin, rpId);
            Account account = new AccountWithStorage(localStorage, localPointers, rawAccount);
            AccountProxy accountProxy = new HttpAccount(p2pHttpProxy, pkiServerNodeId);

            CoreNode core = buildCorenode(a, localStorage, transactions, rawPointers, localPointers, proxingMutable,
                    rawSocial, usageStore, rawAccount, batStore, account, hasher);
            localStorage.setPki(core);
            core.initialize();

            boolean isPki = Cid.decodePeerId(a.getArg("pki-node-id")).equals(nodeId);
            QuotaAdmin userQuotas = buildSpaceQuotas(a, localStorage, core,
                    getDBConnector(a, "space-requests-sql-file", dbConnectionPool),
                    getDBConnector(a, "quotas-sql-file", dbConnectionPool), isPki, localhostApi);
            CoreNode signupFilter = new SignUpFilter(core, userQuotas, nodeId, httpSpaceUsage, hasher,
                    a.getInt("max-daily-paid-signups", isPaidInstance(a) ? 10 : 0), isPki);

            if (a.getBoolean("update-usage", true))
                SpaceCheckingKeyFilter.update(usageStore, userQuotas, core, localPointers, localStorage, hasher);
            SpaceCheckingKeyFilter spaceChecker = new SpaceCheckingKeyFilter(core, localPointers, localStorage,
                    hasher, userQuotas, usageStore);
            CorenodeEventPropagator corePropagator = new CorenodeEventPropagator(signupFilter);
            corePropagator.addListener(spaceChecker::accept);
            MutableEventPropagator localMutable = new MutableEventPropagator(localPointers);
            localMutable.addListener(spaceChecker::accept);

            int blockCacheSize = a.getInt("max-cached-blocks", 1000);
            int maxCachedBlockSize = a.getInt("max-cached-block-size", 50 * 1024);
            ContentAddressedStorage filteringDht = new WriteFilter(localStorage, spaceChecker::allowWrite);
            ContentAddressedStorageProxy proxingDht = new ContentAddressedStorageProxy.HTTP(p2pHttpProxy);
            ContentAddressedStorage p2pDht = new ContentAddressedStorage.Proxying(filteringDht, proxingDht, nodeId, core);

            Path blacklistPath = a.fromPeergosDir("blacklist_file", "blacklist.txt");
            PublicKeyBlackList blacklist = new UserBasedBlacklist(blacklistPath, core, localMutable, localStorage, hasher);
            MutablePointers blockingMutablePointers = new BlockingMutablePointers(localMutable, blacklist);
            MutablePointers p2mMutable = new ProxyingMutablePointers(nodeId, core, blockingMutablePointers, proxingMutable);

            SocialNetworkProxy httpSocial = new HttpSocialNetwork(p2pHttpProxy, p2pHttpProxy);

            SocialNetwork local = UserRepository.build(localStorage, rawSocial);
            SocialNetwork p2pSocial = new ProxyingSocialNetwork(nodeId, core, local, httpSocial);

            Set<String> adminUsernames = Arrays.asList(a.getArg("admin-usernames", "").split(","))
                    .stream()
                    .filter(n -> ! n.isEmpty())
                    .collect(Collectors.toSet());
            boolean enableWaitlist = a.getBoolean("enable-wait-list", false);
            Admin storageAdmin = new Admin(adminUsernames, userQuotas, core, localStorage, enableWaitlist);
            ProxyingSpaceUsage p2pSpaceUsage = new ProxyingSpaceUsage(nodeId, corePropagator, spaceChecker, httpSpaceUsage);

            Account p2pAccount = new ProxyingAccount(nodeId, core, account, accountProxy);
            VerifyingAccount verifyingAccount = new VerifyingAccount(p2pAccount, core, localStorage);
            ContentAddressedStorage cachingStorage = new AuthedCachingStorage(p2pDht, blockAuth, hasher, blockCacheSize, maxCachedBlockSize);
            ContentAddressedStorage incomingP2PStorage = new GetBlockingStorage(cachingStorage);

            ProxyingBatCave p2pBats = new ProxyingBatCave(nodeId, core, batStore, new HttpBatCave(p2pHttpProxy, p2pHttpProxy));
            ServerMessageStore serverMessages = new ServerMessageStore(getDBConnector(a, "server-messages-sql-file", dbConnectionPool),
                    sqlCommands, core, p2pDht);
            UserService localAPI = new UserService(cachingStorage, p2pBats, crypto, corePropagator, verifyingAccount,
                    p2pSocial, p2mMutable, storageAdmin, p2pSpaceUsage, serverMessages, gc);
            UserService p2pAPI = new UserService(incomingP2PStorage, p2pBats, crypto, corePropagator, verifyingAccount,
                    p2pSocial, p2mMutable, storageAdmin, p2pSpaceUsage, serverMessages, gc);
            InetSocketAddress localAPIAddress = userAPIAddress;
            InetSocketAddress p2pAPIAddress = new InetSocketAddress("localhost", localP2PApi.getTCPPort());

            Optional<Path> webroot = a.hasArg("webroot") ?
                    Optional.of(PathUtil.get(a.getArg("webroot"))) :
                    Optional.empty();
            Optional<HttpPoster> appDevTarget = a.getOptionalArg("app-dev-target")
                    .map(url ->  new JavaPoster(HttpUtil.toURL(url),  true));
            boolean useWebAssetCache = a.getBoolean("webcache", appDevTarget.isEmpty());
            Optional<UserService.TlsProperties> tlsProps =
                    tlsHostname.map(host -> new UserService.TlsProperties(host, a.getArg("tls.keyfile.password")));
            int maxConnectionQueue = a.getInt("max-connection-queue", 500);
            int handlerThreads = a.getInt("handler-threads", 50);
            boolean isPublicServer = a.getBoolean("public-server", false);
            Optional<String> basicAuth = a.getOptionalArg("basic-auth");
            List<String> blockstoreDomains = S3Config.getBlockstoreDomains(a);
            Optional<String> paymentDomain = a.getOptionalArg("payment-domain");
            List<String> appSubdomains = Arrays.asList(a.getArg("apps", "markup-viewer,email,calendar,todo-board,code-editor,pdf").split(","));
            List<String> frameDomains = paymentDomain.map(Arrays::asList).orElse(Collections.emptyList());

            localAPI.initAndStart(localAPIAddress, nodeId, tlsProps, publicHostname, blockstoreDomains, frameDomains, appSubdomains,
                    a.getBoolean("include-csp", true), basicAuth, webroot, appDevTarget, useWebAssetCache, isPublicServer, maxConnectionQueue, handlerThreads);
            p2pAPI.initAndStart(p2pAPIAddress, nodeId, Optional.empty(), publicHostname, blockstoreDomains, frameDomains, appSubdomains,
                    a.getBoolean("include-csp", true), basicAuth, webroot, Optional.empty(), useWebAssetCache, isPublicServer, maxConnectionQueue, handlerThreads);

            boolean isPkiNode = nodeId.equals(pkiServerNodeId);
            if (! isPkiNode && useIPFS) {
                // ipfs-nucleus doesn't implement swarm. We may reinstate these in the bootstrap list in the future
//                int pkiNodeSwarmPort = a.getInt("pki.node.swarm.port");
//                InetAddress pkiNodeIpAddress = InetAddress.getByName(a.getArg("pki.node.ipaddress"));
//                ipfsWrapper.connectToNode(new InetSocketAddress(pkiNodeIpAddress, pkiNodeSwarmPort), pkiServerNodeId);
                ((MirrorCoreNode) core).start();
            }
            if (a.getBoolean("update-usage", true))
                spaceChecker.calculateUsage();

            if (a.hasArg("mirror.node.id")) {
                Multihash nodeToMirrorId = Cid.decode(a.getArg("mirror.node.id"));
                new Thread(() -> {
                    while (true) {
                        try {
                            BatWithId mirrorBat = BatWithId.decode(a.getArg("mirror.bat"));
                            Mirror.mirrorNode(nodeToMirrorId, mirrorBat, core, p2mMutable, localStorage, rawPointers, transactions, hasher);
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
                new Thread(() -> {
                    while (true) {
                        try {
                            Optional<SigningKeyPair> mirrorLoginDataPair = a.getOptionalArg("login-keypair").map(SigningKeyPair::fromString);
                            if (mirrorLoginDataPair.isEmpty())
                                System.out.println("WARNING: Mirroring users data, but not their login, see option 'login-keypair'");
                            String username = a.getArg("mirror.username");

                            Optional<BatWithId> mirrorBat = a.getOptionalArg("mirror.bat").map(BatWithId::decode);
                            if (mirrorBat.isEmpty())
                                System.out.println("WARNING: Mirroring users public blocks only, see option 'mirror.bat'");
                            else {
                                BatId mirrorId = mirrorBat.get().id();
                                Optional<Bat> existingMirrorBat = batStore.getBat(mirrorId);
                                if (existingMirrorBat.isEmpty())
                                    batStore.addBat(username, mirrorId, mirrorBat.get().bat, new byte[0]).join();
                            }
                            Mirror.mirrorUser(username, mirrorLoginDataPair, mirrorBat, core, p2mMutable, p2pAccount, localStorage,
                                    rawPointers, rawAccount, transactions, hasher);
                            try {
                                Thread.sleep(60_000);
                            } catch (InterruptedException f) {}
                        } catch (Exception e) {
                            Logging.LOG().log(Level.SEVERE, e, () -> e.getMessage());
                            try {
                                Thread.sleep(5_000);
                            } catch (InterruptedException f) {}
                        }
                    }
                }).start();
            }
            if (a.getBoolean("run-gateway", false) && ! isPublicServer) {
                Args gatewayArgs = a.with("port", a.getArg("gateway-port"))
                        .with("peergos-url", "http://localhost:" + a.getArg("port"));
                GATEWAY.main(gatewayArgs);
            }

            if (useIPFS && !a.hasArg("ipfs.identity.peerid")) {
                Args args = a.with("ipfs.identity.peerid", ipfsWrapper.ipfsConfigParams.identity.get().peerId.toBase58());
                args = args.with("ipfs.identity.priv-key", Base64.getEncoder().encodeToString(ipfsWrapper.ipfsConfigParams.identity.get().privKeyProtobuf));
                args.saveToFile();
            } else {
                a.saveToFileIfAbsent();
            }
            System.out.println("\n" +
                    "█╗█╗█╗█╗   ██████╗ ███████╗███████╗██████╗  ██████╗  ██████╗ ███████╗   █╗█╗█╗█╗\n" +
                    " █████╔╝   ██╔══██╗██╔════╝██╔════╝██╔══██╗██╔════╝ ██╔═══██╗██╔════╝    █████╔╝\n" +
                    " ██ ██║    ██████╔╝█████╗  █████╗  ██████╔╝██║  ███╗██║   ██║███████╗    ██ ██║\n" +
                    " █████║    ██╔═══╝ ██╔══╝  ██╔══╝  ██╔══██╗██║   ██║██║   ██║╚════██║    █████║\n" +
                    "███████╗   ██║     ███████╗███████╗██║  ██║╚██████╔╝╚██████╔╝███████║   ███████╗\n" +
                    "╚══════╝   ╚═╝     ╚══════╝╚══════╝╚═╝  ╚═╝ ╚═════╝  ╚═════╝ ╚══════╝   ╚══════╝");
            boolean generateToken = a.getBoolean("generate-token", ! localhostApi);
            String host = (publicHostname.isPresent() ? "https://" : "http://") +
                    (publicHostname.orElse(localAPIAddress.getHostString())) +
                    (webPort == 80 ? "" : ":" + webPort);
            if (generateToken) {
                System.out.println("Generating signup token...");
                String token = userQuotas.generateToken(crypto.random);
                System.out.println("Peergos daemon started. Browse to " + host + "/?signup=true&token="
                        + token + " to sign up, or use the shell command with the token " + token);
            } else
                System.out.println("Peergos daemon started. Browse to " + host + "/ to sign up or login. \nRun with -generate-token true to generate a signup token.");
            InstanceAdmin.VersionInfo version = storageAdmin.getVersionInfo().join();
            System.out.println("Running version " + version);
            return localAPI;
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

        try {
            Files.createTempDirectory("peergos").toString();
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
        String mountPath = a.getArg("mountPoint");
        Path path = Paths.get(mountPath);

        path.toFile().mkdirs();

        try {
            String peergosUrl = a.getArg("peergos-url");
            URL api = new URL(peergosUrl);
            NetworkAccess network = buildJavaNetworkAccess(api, ! peergosUrl.startsWith("http://localhost")).join();

            Crypto crypto = initCrypto();
            UserContext userContext = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).join();
            PeergosFS peergosFS = new PeergosFS(userContext);
            FuseProcess fuseProcess = new FuseProcess(peergosFS, path);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> fuseProcess.close(), "Fuse shutdown"));

            fuseProcess.start();
            System.out.println("\n\nPeergos mounted at " + path + "\n\n");
            return fuseProcess;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static CompletableFuture<MultiFactorAuthResponse> getMfaResponseCLI(MultiFactorAuthRequest req) {
        Optional<MultiFactorAuthMethod> anyTotp = req.methods.stream().filter(m -> m.type == MultiFactorAuthMethod.Type.TOTP).findFirst();
        if (anyTotp.isEmpty())
            throw new IllegalStateException("No supported 2 factor auth method! " + req.methods);
        MultiFactorAuthMethod totp = anyTotp.get();
        System.out.println("Enter TOTP code for login");
        Console console = System.console();
        String code = console.readLine().trim();
        return Futures.of(new MultiFactorAuthResponse(totp.credentialId, Either.a(code)));
    }

    public static IpfsWrapper startIpfs(Args a) {
        // test if ipfs is already running
        String ipfsApiAddress = a.getArg("ipfs-api-address");
        if (IpfsWrapper.isHttpApiListening(ipfsApiAddress)) {
            throw new IllegalStateException("IPFS is already running on api " + ipfsApiAddress);
        }
        return IpfsWrapper.launch(a);
    }

    public static Boolean startShell(Args args) {
        CLI.main(new String[]{});
        return true;
    }

    /** This should be run on a Peergos server to which a user will be migrated
     *
     * @param a
     * @return
     */
    public static boolean migrate(Args a) {
        Crypto crypto = initCrypto();
        String peergosUrl = a.getArg("peergos-url");
        try {
            URL api = new URL(peergosUrl);
            NetworkAccess network = buildJavaNetworkAccess(api, ! peergosUrl.startsWith("http://localhost")).join();
            Console console = System.console();
            String username = console.readLine("Enter username to migrate to this server: ");
            String password = new String(console.readPassword("Enter password for " + username + ": "));

            UserContext user = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).join();
            List<UserPublicKeyLink> existing = user.network.coreNode.getChain(username).join();
            Multihash currentStorageNodeId = existing.get(existing.size() - 1).claim.storageProviders.stream().findFirst().get();
            Multihash newStorageNodeId = network.dhtClient.id().join();
            if (currentStorageNodeId.equals(newStorageNodeId)) {
                System.err.println("You are trying to migrate a user to their current server. Please supply the url of a different server.");
                return false;
            }
            System.out.println("Migrating user from node " + currentStorageNodeId + " to " + newStorageNodeId);
            List<UserPublicKeyLink> newChain = Migrate.buildMigrationChain(existing, newStorageNodeId, user.signer.secret);
            user.ensureMirrorId().join().get();
            Optional<BatWithId> current = user.getMirrorBat().join();
            long usage = user.getSpaceUsage().join();
            user.network.coreNode.migrateUser(username, newChain, currentStorageNodeId, current, usage).join();
            List<UserPublicKeyLink> updatedChain = user.network.coreNode.getChain(username).join();
            if (!updatedChain.get(updatedChain.size() - 1).claim.storageProviders.contains(newStorageNodeId))
                throw new IllegalStateException("Migration failed. Please try again later");
            System.out.println("Migration complete.");
            return true;
        } catch (Exception ex) {
            ex.printStackTrace();
            return false;
        }
    }

    public static final Command<Void> MAIN = new Command<>("Main",
            "Run a Peergos command",
            args -> {
                if (args.hasArg("version")) {
                    VERSION.main(args);
                } else
                    System.out.println("Run with -help to show options");
                return null;
            },
            Collections.emptyList(),
            Arrays.asList(
                    PEERGOS,
                    SHELL,
                    FUSE,
                    QuotaCLI.QUOTA,
                    UsageCLI.USAGE,
                    ServerMessages.SERVER_MESSAGES,
                    GATEWAY,
                    Mirror.MIRROR,
                    MIGRATE,
                    VERSION,
                    IDENTITY,
                    PKI,
                    PKI_INIT,
                    IPFS
            )
    );

    public static MultiAddress getLocalMultiAddress(int port) {
        return new MultiAddress("/ip4/127.0.0.1/tcp/" + port);
    }

    public static MultiAddress getLocalBootstrapAddress(int port, Multihash nodeId) {
        return new MultiAddress("/ip4/127.0.0.1/tcp/" + port + "/ipfs/"+ nodeId);
    }

    public static void main(String[] args) {
        // Netty uses thread count twice the number of CPUs, this undoes that
        System.getProperties().setProperty("io.netty.eventLoopThreads", "2");
        try {
            MAIN.main(Args.parse(args));
        } catch (Throwable e) {
            e.printStackTrace();
            Logging.LOG().log(Level.SEVERE, e, () -> e.getMessage());
            System.exit(-1);
        }
    }
}
