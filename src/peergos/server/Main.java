package peergos.server;

import peergos.shared.*;
import peergos.server.corenode.*;
import peergos.server.fuse.*;
import peergos.server.mutable.*;
import peergos.server.storage.*;
import peergos.server.tests.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.password.*;
import peergos.shared.io.ipfs.multiaddr.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main {
    public static final String PEERGOS_PATH = "PEERGOS_PATH";
    public static final Path DEFAULT_PEERGOS_DIR_PATH =
            Paths.get(System.getProperty("user.home"), ".peergos");

    static {
        PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, new Ed25519.Java());
    }

    public static Command ENSURE_IPFS_INSTALLED = new Command("install-ipfs",
            "Download/update IPFS binary. Does nothing if current IPFS binary is up-to-date.",
            args -> {
                Path ipfsExePath = IpfsWrapper.getIpfsExePath(args);
                File dir = ipfsExePath.getParent().toFile();
                if (!  dir.isDirectory() && ! dir.mkdirs())
                    throw new IllegalStateException("Specified install directory "+ dir +" doesn't exist and can't be created");

                IpfsInstaller.ensureInstalled(ipfsExePath);
            },
            Arrays.asList(
                    new Command.Arg("ipfs-exe-path", "Desired path to IPFS executable. Defaults to $PEERGOS_PATH/ipfs", false)
            )
    );
    public static Command IPFS = new Command("ipfs",
            "Start IPFS daemon and ensure configuration, optionally manage runtime.",
            Main::startIpfs,
            Arrays.asList(
                    new Command.Arg("IPFS_PATH", "Path to IPFS directory. Defaults to $PEERGOS_PATH/.ipfs, or ~/.peergos/.ipfs", false),
                    new Command.Arg("ipfs-exe-path", "Path to IPFS executable. Defaults to $PEERGOS_PATH/ipfs", false),
                    new Command.Arg("ipfs-config-api-port", "IPFS API port", false, "5001"),
                    new Command.Arg("ipfs-config-gateway-port", "IPFS Gateway port", false, "8080"),
                    new Command.Arg("ipfs-config-swarm-port", "IPFS Swarm port", false, "4001"),
                    new Command.Arg("ipfs-config-bootstrap-node-list", "Comma separated list of IPFS bootstrap nodes. Uses existing bootstrap nodes by default.", false),
                    new Command.Arg("ipfs-manage-runtime", "Will manage the IPFS daemon runtime when set (restart on exit)", false, "true")
            )
    );

    public static Command CORE_NODE = new Command("core",
            "Start a Corenode.",
            Main::startCoreNode,
            Arrays.asList(
                    new Command.Arg("corenodeFile", "Name of a local corenode sql file (created if it doesn't exist)", false, ":memory:"),
                    new Command.Arg("keyfile", "Path to keyfile", false),
                    new Command.Arg("passphrase", "Passphrase for keyfile", false),
                    new Command.Arg("corenodePort", "Service port", true, "" + HttpCoreNodeServer.PORT),
                    new Command.Arg("pki.public.key.path", "The path to the pki public key file", true),
                    new Command.Arg("pki.secret.key.path", "The path to the pki secret key file", true),
                    new Command.Arg("peergos.identity.hash", "The hash of the public identity key of the peergos user", true)
            )
    );

    public static final Command PEERGOS = new Command("peergos",
            "The user facing Peergos server",
            Main::startPeergos,
            Stream.of(
                    new Command.Arg("port", "service port", false, "8000"),
                    new Command.Arg("pki-node-id", "Ipfs node id of the pki node", true),
                    new Command.Arg("domain", "Domain name to bind to,", false, "localhost"),
                    new Command.Arg("useIPFS", "Use IPFS for storage or ephemeral RAM store", false, "true"),
                    new Command.Arg("webroot", "the path to the directory to serve as the web root", false),
                    new Command.Arg("publicserver", "listen on all network interfaces, not just localhost", false),
                    new Command.Arg("default-quota", "default maximum storage per user", false, Long.toString(1024L * 1024 * 1024))
            ).collect(Collectors.toList())
    );

    public static final Command DEMO = new Command("demo",
            "Run in demo server mode",
            args -> {
                args.setIfAbsent("domain", "demo.peergos.net");
                args.setIfAbsent("corenodeFile", "core.sql");
                args.setIfAbsent("socialnodeFile", "social.sql");
                args.setIfAbsent("useIPFS", "true");
                args.setIfAbsent("publicserver", "true");
                CORE_NODE.main(args);
                args.setArg("port", "443");
                args.setIfAbsent("corenodeURL", "http://localhost:" + args.getArg("corenodePort"));
                PEERGOS.main(args);
            },
            Collections.emptyList()
    );

    public static final Command BOOTSTRAP = new Command("bootstrap",
            "Bootstrap a new peergos network\n" +
                    "This means creating a pki keypair and publishing the public key",
            args -> {
                try {
                    Crypto crypto = Crypto.initJava();
                    // setup peergos user and pki keys
                    String testpassword = args.getArg("peergos.password");
                    String pkiUsername = "peergos";
                    UserWithRoot peergos = UserUtil.generateUser(pkiUsername, testpassword, crypto.hasher, crypto.symmetricProvider,
                            crypto.random, crypto.signer, crypto.boxer, SecretGenerationAlgorithm.getDefault()).get();

                    boolean useIPFS = args.getBoolean("useIPFS");
                    String ipfsApiAddress = args.getArg("ipfs-api-address", "/ip4/127.0.0.1/tcp/5001");
                    ContentAddressedStorage dht = useIPFS ?
                            new IpfsDHT(new MultiAddress(ipfsApiAddress)) :
                            new FileContentAddressedStorage(blockstorePath(args));

                    SigningKeyPair peergosIdentityKeys = peergos.getUser();
                    PublicKeyHash peergosPublicHash = ContentAddressedStorage.hashKey(peergosIdentityKeys.publicSigningKey);

                    String pkiPassword = args.getArg("pki.keygen.password");
                    SigningKeyPair pkiKeys = UserUtil.generateUser(pkiUsername, pkiPassword, crypto.hasher, crypto.symmetricProvider,
                            crypto.random, crypto.signer, crypto.boxer, SecretGenerationAlgorithm.getDefault()).get().getUser();
                    dht.putSigningKey(peergosIdentityKeys.secretSigningKey.signatureOnly(
                            pkiKeys.publicSigningKey.serialize()),
                            peergosPublicHash,
                            pkiKeys.publicSigningKey).get();

                    String pkiKeyfilePassword = args.getArg("pki.keyfile.password");
                    Cborable cipherTextCbor = PasswordProtected.encryptWithPassword(pkiKeys.secretSigningKey.toCbor().toByteArray(),
                            pkiKeyfilePassword,
                            crypto.hasher,
                            crypto.symmetricProvider,
                            crypto.random);
                    Files.write(args.fromPeergosDir("pki.secret.key.path"), cipherTextCbor.serialize());
                    Files.write(args.fromPeergosDir("pki.public.key.path"), pkiKeys.publicSigningKey.toCbor().toByteArray());
                    args.setIfAbsent("peergos.identity.hash", peergosPublicHash.toString());

                } catch (Exception e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            },
            Arrays.asList(
                    new Command.Arg("useIPFS", "Use IPFS for storage or ephemeral RAM store", false, "true"),
                    new Command.Arg("peergos.password",
                            "The password for the peergos user required to bootstrap the network", true),
                    new Command.Arg("pki.keygen.password", "The password used to generate the pki key pair", true),
                    new Command.Arg("pki.keyfile.password", "The password used to protect the pki private key on disk", true),
                    new Command.Arg("pki.public.key.path", "The path to the pki public key file", true),
                    new Command.Arg("pki.secret.key.path", "The path to the pki secret key file", true)
            )
    );

    public static final Command POSTSTRAP = new Command("poststrap",
            "The final step of bootstrapping a new peergos network, which must be run once after network bootstrap\n" +
                    "This means signing up the peergos user, and adding the pki public key to the peergos user",
            args -> {
                try {
                    Crypto crypto = Crypto.initJava();
                    // recreate peergos user and pki keys
                    String password = args.getArg("peergos.password");
                    String pkiUsername = "peergos";
                    UserWithRoot peergos = UserUtil.generateUser(pkiUsername, password, crypto.hasher, crypto.symmetricProvider,
                            crypto.random, crypto.signer, crypto.boxer, SecretGenerationAlgorithm.getDefault()).get();

                    SigningKeyPair peergosIdentityKeys = peergos.getUser();
                    PublicKeyHash peergosPublicHash = ContentAddressedStorage.hashKey(peergosIdentityKeys.publicSigningKey);
                    PublicSigningKey pkiPublic =
                            PublicSigningKey.fromByteArray(
                                    Files.readAllBytes(args.fromPeergosDir("pki.public.key.path")));
                    PublicKeyHash pkiPublicHash = ContentAddressedStorage.hashKey(pkiPublic);
                    int webPort = args.getInt("port");
                    NetworkAccess network = NetworkAccess.buildJava(new URL("http://localhost:" + webPort)).get();

                    // sign up peergos user
                    UserContext context = UserContext.ensureSignedUp(pkiUsername, password, network, crypto).get();
                    Optional<PublicKeyHash> existingPkiKey = context.getNamedKey("pki").get();
                    if (!existingPkiKey.isPresent() || existingPkiKey.get().equals(pkiPublicHash)) {
                        context.addNamedOwnedKeyAndCommit("pki", pkiPublicHash).get();
                        // write pki public key to ipfs
                        network.dhtClient.putSigningKey(peergosIdentityKeys.secretSigningKey
                                .signatureOnly(pkiPublic.serialize()), peergosPublicHash, pkiPublic).get();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            },
            Arrays.asList(
                    new Command.Arg("peergos.password",
                            "The password for the peergos user required to bootstrap the network", true),
                    new Command.Arg("pki.public.key.path", "The path to the pki public key file", true)
            )
    );

    public static final Command LOCAL = new Command("local",
            "Start an ephemeral Peergos Server and CoreNode server",
            args -> {
                try {
                    int peergosPort = args.getInt("port", 8000);
                    int ipfsApiPort = 10001;
                    int ipfsGatewayPort = 10002;
                    args.setIfAbsent("ipfs-config-api-port", "" + ipfsApiPort);
                    args.setIfAbsent("ipfs-config-gateway-port", "" + ipfsGatewayPort);
                    args.setIfAbsent("proxy-target", getLocalMultiAddress(peergosPort).toString());
                    args.setIfAbsent("useIPFS", "false");

                    IpfsWrapper ipfs = null;
                    boolean useIPFS = args.getBoolean("useIPFS");
                    if (useIPFS) {
                        ENSURE_IPFS_INSTALLED.main(args);
                        ipfs = startIpfs(args);
                    }

                    args.setIfAbsent("peergos.password", "testpassword");
                    args.setIfAbsent("pki.secret.key.path", "test.pki.secret.key");
                    args.setIfAbsent("pki.public.key.path", "test.pki.public.key");
                    args.setIfAbsent("pki.keygen.password", "testPkiPassword");
                    args.setIfAbsent("pki.keyfile.password", "testPkiFilePassword");
                    args.setArg("ipfs-api-address", getLocalMultiAddress(ipfsApiPort).toString());
                    BOOTSTRAP.main(args);
                    args.setIfAbsent("domain", "localhost");
                    args.setIfAbsent("mutable-pointers-file", ":memory:");
                    args.setIfAbsent("social-sql-file", ":memory:");

                    Multihash pkiIpfsNodeId = useIPFS ?
                            new IpfsDHT(getLocalMultiAddress(ipfsApiPort)).id().get() :
                            new FileContentAddressedStorage(blockstorePath(args)).id().get();

                    if (ipfs != null)
                        ipfs.stop();
                    args.setIfAbsent("pki-node-id", pkiIpfsNodeId.toBase58());
                    PEERGOS.main(args);
                    POSTSTRAP.main(args);
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            },
            Collections.emptyList()
    );

    public static final Command FUSE = new Command("fuse",
            "Mount a Peergos user's filesystem natively",
            Main::startFuse,
            Stream.of(
                    new Command.Arg("username", "Peergos username", true),
                    new Command.Arg("password", "Peergos password", true),
                    new Command.Arg("webport", "Peergos service address port", false, "8000"),
                    new Command.Arg("mountPoint", "The directory to mount the Peergos filesystem in", true, "peergos")
            ).collect(Collectors.toList())
    );

    public static void startPeergos(Args a) {
        try {
            PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, new Ed25519.Java());

            ENSURE_IPFS_INSTALLED.main(a);
            IPFS.main(a);

            int webPort = a.getInt("port");
            Multihash pkiServerNodeId = Cid.decode(a.getArg("pki-node-id"));
            URL ipfsApiAddress = AddressUtil.getLocalAddress(a.getInt("ipfs-config-api-port", 5001));
            URL ipfsGatewayAddress = AddressUtil.getLocalAddress(a.getInt("ipfs-config-gateway-port", 8080));
            String domain = a.getArg("domain");
            InetSocketAddress userAPIAddress = new InetSocketAddress(domain, webPort);

            int dhtCacheEntries = 1000;
            int maxValueSizeToCache = 50 * 1024;
            JavaPoster ipfsApi = new JavaPoster(ipfsApiAddress);
            JavaPoster ipfsGateway = new JavaPoster(ipfsGatewayAddress);

            boolean useIPFS = a.getBoolean("useIPFS");
            ContentAddressedStorage localDht = useIPFS ?
                    new CachingStorage(new ContentAddressedStorage.HTTP(ipfsApi), dhtCacheEntries, maxValueSizeToCache) :
                    new FileContentAddressedStorage(blockstorePath(a));

            String hostname = a.getArg("domain");
            int maxUserCount = a.getInt("max-user-count", CoreNode.MAX_USERNAME_COUNT);
            Multihash nodeId = localDht.id().get();

            String mutablePointersSqlFile = a.getArg("mutable-pointers-file");
            String path = mutablePointersSqlFile.equals(":memory:") ?
                    mutablePointersSqlFile :
                    a.fromPeergosDir("mutable-pointers-file").toString();
            MutablePointers sqlMutable = UserRepository.buildSqlLite(path, localDht, maxUserCount);

            // build a proxying corenode, unless we are the pki node
            CoreNode core = nodeId.equals(pkiServerNodeId) ?
                    buildPkiCorenode(sqlMutable, localDht, a) :
                    new HTTPCoreNode(ipfsGateway, pkiServerNodeId);

            long defaultQuota = a.getLong("default-quota");
            Logging.LOG().info("Using default user space quota of " + defaultQuota);
            Path quotaFilePath = a.fromPeergosDir("quotas_file","quotas.txt");
            UserQuotas userQuotas = new UserQuotas(quotaFilePath, defaultQuota);
            SpaceCheckingKeyFilter spaceChecker = new SpaceCheckingKeyFilter(core, sqlMutable, localDht, userQuotas::quota);
            CorenodeEventPropagator corePropagator = new CorenodeEventPropagator(core);
            corePropagator.addListener(spaceChecker::accept);
            MutableEventPropagator localMutable = new MutableEventPropagator(sqlMutable);
            localMutable.addListener(spaceChecker::accept);

            ContentAddressedStorage filteringDht = new WriteFilter(localDht, spaceChecker::allowWrite);
            ContentAddressedStorageProxy proxingDht = new ContentAddressedStorageProxy.HTTP(ipfsGateway);
            ContentAddressedStorage p2pDht = new ContentAddressedStorage.Proxying(filteringDht, proxingDht, nodeId, core);

            MutablePointersProxy proxingMutable = new HttpMutablePointers(ipfsGateway, ipfsGateway);
            Path blacklistPath = a.fromPeergosDir("blacklist_file", "blacklist.txt");
            PublicKeyBlackList blacklist = new UserBasedBlacklist(blacklistPath, core, localMutable, p2pDht);
            MutablePointers blockingMutablePointers = new BlockingMutablePointers(new PinningMutablePointers(localMutable, p2pDht), blacklist);
            MutablePointers p2mMutable = new ProxyingMutablePointers(nodeId, core, blockingMutablePointers, proxingMutable);

            SocialNetworkProxy httpSocial = new HttpSocialNetwork(ipfsGateway, ipfsGateway);
            String socialNodeFile = a.getArg("social-sql-file");
            String socialPath = socialNodeFile.equals(":memory:") ?
                    socialNodeFile :
                    a.fromPeergosDir("social-sql-file").toString();
            SocialNetwork local = UserRepository.buildSqlLite(socialPath, p2pDht, maxUserCount);
            SocialNetwork p2pSocial = new ProxyingSocialNetwork(nodeId, core, local, httpSocial);

            Path userPath = a.fromPeergosDir("whitelist_file", "user_whitelist.txt");
            int delayMs = a.getInt("whitelist_sleep_period", 1000 * 60 * 10);

            new UserFilePinner(userPath, core, p2mMutable, p2pDht, delayMs).start();

            InetSocketAddress httpsMessengerAddress = new InetSocketAddress(hostname, userAPIAddress.getPort());
            new UserService(httpsMessengerAddress, p2pDht, corePropagator, p2pSocial, p2mMutable, a);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void startFuse(Args a) {
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
            NetworkAccess network = NetworkAccess.buildJava(webPort).get();
            Crypto crypto = Crypto.initJava();
            UserContext userContext = UserTests.ensureSignedUp(username, password, network, crypto);
            PeergosFS peergosFS = new PeergosFS(userContext);
            FuseProcess fuseProcess = new FuseProcess(peergosFS, path);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> fuseProcess.close()));

            fuseProcess.start();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }


    public static IpfsWrapper startIpfs(Args a) {
        // test if ipfs is already running
        int ipfsApiPort = IpfsWrapper.getApiPort(a);
        if (IpfsWrapper.isHttpApiListening(ipfsApiPort)) {
            throw new IllegalStateException("IPFS is already running, using existing instance at " + ipfsApiPort);
        }

        IpfsWrapper ipfs = IpfsWrapper.build(a);

        if (a.getBoolean("ipfs-manage-runtime", true))
            IpfsWrapper.launchAndManage(ipfs);
        else {
            IpfsWrapper.launchOnce(ipfs);
        }
        // wait for daemon to finish starting
        ipfs.waitForDaemon(10);
        // set up p2p proxy receiver
        ipfs.startP2pProxy(new MultiAddress(a.getArg("proxy-target")));
        return ipfs;
    }

    public static void startCoreNode(Args a) {
        String mutablePointersSqlFile = a.getArg("mutable-pointers-file");
        String path = mutablePointersSqlFile.equals(":memory:") ?
                mutablePointersSqlFile :
                a.fromPeergosDir("mutable.sql").toString();
        int corenodePort = a.getInt("corenode-port");
        int maxUserCount = a.getInt("max-user-count", CoreNode.MAX_USERNAME_COUNT);
        System.out.println("Using mutable-pointers path " + path);
        boolean useIPFS = a.getBoolean("useIPFS");

        int dhtCacheEntries = 1000;
        int maxValueSizeToCache = 2 * 1024 * 1024;
        ContentAddressedStorage dht = useIPFS ?
                new CachingStorage(new IpfsDHT(), dhtCacheEntries, maxValueSizeToCache) :
                new FileContentAddressedStorage(blockstorePath(a));
        try {
            Crypto crypto = Crypto.initJava();
            MutablePointers mutable = UserRepository.buildSqlLite(path
                    , dht, maxUserCount);
            PublicKeyHash peergosIdentity = PublicKeyHash.fromString(a.getArg("peergos.identity.hash"));

            String pkiSecretKeyfilePassword = a.getArg("pki.keyfile.password");

            PublicSigningKey pkiPublic =
                    PublicSigningKey.fromByteArray(
                            Files.readAllBytes(Paths.get(a.getArg("pki.public.key.path"))));
            SecretSigningKey pkiSecretKey = SecretSigningKey.fromCbor(CborObject.fromByteArray(
                    PasswordProtected.decryptWithPassword(
                            CborObject.fromByteArray(Files.readAllBytes(Paths.get(a.getArg("pki.secret.key.path")))),
                            pkiSecretKeyfilePassword,
                            crypto.hasher,
                            crypto.symmetricProvider,
                            crypto.random
                    )));
            SigningKeyPair pkiKeys = new SigningKeyPair(pkiPublic, pkiSecretKey);
            PublicKeyHash pkiPublicHash = ContentAddressedStorage.hashKey(pkiKeys.publicSigningKey);

            MaybeMultihash currentPkiRoot = mutable.getPointerTarget(peergosIdentity, pkiPublicHash, dht).get();

            IpfsCoreNode core = new IpfsCoreNode(pkiKeys, currentPkiRoot, dht, mutable, peergosIdentity);
            HttpCoreNodeServer.createAndStart(corenodePort, core, mutable, a);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static CoreNode buildPkiCorenode(MutablePointers mutable, ContentAddressedStorage dht, Args a) {
        try {
            Crypto crypto = Crypto.initJava();
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

            return new IpfsCoreNode(pkiKeys, currentPkiRoot, dht, mutable, peergosIdentity);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static final Command MAIN = new Command("Main",
            "Run a Peergos server",
            args -> {
                Optional<String> top = args.head();
                if (!top.isPresent()) {
                    System.out.println("Run with -help to show options");
                    return;
                }
                args.setIfAbsent("domain", "localhost");
                if (args.getBoolean("useIPFS", true))
                    startIpfs(args);
                startCoreNode(args);
                startPeergos(args);
            },
            Collections.emptyList(),
            Arrays.asList(
                    CORE_NODE,
                    PEERGOS,
                    LOCAL,
                    DEMO,
                    FUSE
            )
    );

    /**
     * Create path to local blockstore directory from Args.
     *
     * @param args
     * @return
     */
    private static Path blockstorePath(Args args) {
        return args.fromPeergosDir("blockstore_dir", "blockstore");
    }

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
