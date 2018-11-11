package peergos.server;

import peergos.shared.*;
import peergos.server.corenode.*;
import peergos.server.fuse.*;
import peergos.server.mutable.*;
import peergos.server.social.*;
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
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main
{
    public static final String PEERGOS_DIR = "PEERGOS_PATH";
    public static final Path DEFAULT_PEERGOS_DIR_PATH =
            Paths.get(System.getProperty("user.home"), ".peergos");

    static {
        PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, new Ed25519.Java());
    }

    public static Command IPFS  = new Command("ipfs",
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

    public static Command SOCIAL = new Command("social",
            "Start a social network node which stores follow requests.",
            Main::startSocialNode,
            Stream.of(
                    new Command.Arg("socialnodeFile", "Name of local social node sql file (created if it doesn't exist)", false, ":memory:"),
                    new Command.Arg("keyfile", "Path to keyfile", false),
                    new Command.Arg("passphrase", "Passphrase for keyfile", false),
                    new Command.Arg("socialnodePort", "Service port", true, "" + HttpSocialNetworkServer.PORT)
            ).collect(Collectors.toList())
    );

    public static final Command PEERGOS = new Command("peergos",
            "The user facing Peergos server",
            Main::startPeergos,
            Stream.of(
                    new Command.Arg("port",  "service port", false, "8000"),
                    new Command.Arg("pki-node-id", "Ipfs node id of the pki node", true),
                    new Command.Arg("socialnodeURL", "Social network node address", false, "http://localhost:" + HttpSocialNetworkServer.PORT),
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
                SOCIAL.main(args);
                args.setArg("port", "443");
                args.setIfAbsent("corenodeURL", "http://localhost:" + args.getArg("corenodePort"));
                args.setIfAbsent("socialnodeURL", "http://localhost:" + args.getArg("socialnodePort"));
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
                    Files.write(Paths.get(args.getArg("pki.secret.key.path")), cipherTextCbor.serialize());
                    Files.write(Paths.get(args.getArg("pki.public.key.path")), pkiKeys.publicSigningKey.toCbor().toByteArray());
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
                                    Files.readAllBytes(Paths.get(args.getArg("pki.public.key.path"))));
                    PublicKeyHash pkiPublicHash = ContentAddressedStorage.hashKey(pkiPublic);
                    int webPort = args.getInt("port");
                    NetworkAccess network = NetworkAccess.buildJava(new URL("http://localhost:" + webPort)).get();

                    // sign up peergos user
                    UserContext context = UserContext.ensureSignedUp(pkiUsername, password, network, crypto).get();
                    Optional<PublicKeyHash> existingPkiKey = context.getNamedKey("pki").get();
                    if (! existingPkiKey.isPresent() || existingPkiKey.get().equals(pkiPublicHash)) {
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
                    // Start an ipfs node for the corenode
                    Path corenodePeergosDir = Files.createTempDirectory("peergos-core");
                    Args coreArgs = args.with(PEERGOS_DIR, corenodePeergosDir.toString());
                    int coreIpfsApiPort = 9000;
                    int corenodePort = args.getInt("corenodePort", 9999);
                    coreArgs.setArg("ipfs-config-api-port", "" + coreIpfsApiPort);
                    coreArgs.setArg("proxy-target", getLocalMultiAddress(corenodePort).toString());
                    IPFS.main(coreArgs);
                    coreArgs.setIfAbsent("peergos.password", "testpassword");
                    coreArgs.setIfAbsent("pki.secret.key.path", "test.pki.secret.key");
                    coreArgs.setIfAbsent("pki.public.key.path", "test.pki.public.key");
                    coreArgs.setIfAbsent("pki.keygen.password", "testPkiPassword");
                    coreArgs.setIfAbsent("pki.keyfile.password", "testPkiFilePassword");
                    coreArgs.setArg("ipfs-api-address", getLocalMultiAddress(coreIpfsApiPort).toString());
                    BOOTSTRAP.main(args);
                    coreArgs.setIfAbsent("domain", "localhost");
                    coreArgs.setIfAbsent("corenodeFile", ":memory:");
                    coreArgs.setIfAbsent("socialnodeFile", ":memory:");
                    coreArgs.setIfAbsent("useIPFS", "false");
                    CORE_NODE.main(coreArgs);
                    POSTSTRAP.main(coreArgs);
                    Multihash pkiIpfsNodeId = new IpfsDHT(getLocalMultiAddress(coreIpfsApiPort)).id().get();

                    // Start a second ipfs instance for the Peergos server
                    int ipfsApiPort = 5001;
                    args.setArg("ipfs-config-api-port", "" + ipfsApiPort);
                    args.setArg("proxy-target", getLocalMultiAddress(args.getInt("port", 8000)).toString());
                    IPFS.main(args);

                    SOCIAL.main(args);
                    args.setIfAbsent("pki-node-id", pkiIpfsNodeId.toBase58());
                    args.setIfAbsent("socialnodeURL", "http://localhost:" + args.getArg("socialnodePort"));
                    PEERGOS.main(args);
                } catch (Exception e) {
                    throw new RuntimeException(e);
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

            int webPort = a.getInt("port");
            URL coreAddress = new URI(a.getArg("corenodeURL")).toURL();
            Multihash pkiServerNodeId = Cid.decode(a.getArg("pkiNodeId"));
            URL socialAddress = new URI(a.getArg("socialnodeURL")).toURL();
            URL ipfsAddress = new URI(a.getArg("ipfsURL", "http://localhost:5001")).toURL();
            String domain = a.getArg("domain");
            InetSocketAddress userAPIAddress = new InetSocketAddress(domain, webPort);

            int dhtCacheEntries = 1000;
            int maxValueSizeToCache = 50 * 1024;
            JavaPoster ipfsPoster = new JavaPoster(ipfsAddress);

            boolean useIPFS = a.getBoolean("useIPFS");
            ContentAddressedStorage dht = useIPFS ?
                    new CachingStorage(new ContentAddressedStorage.HTTP(ipfsPoster), dhtCacheEntries, maxValueSizeToCache) :
                    new FileContentAddressedStorage(blockstorePath(a));

            // start the User Service
            String hostname = a.getArg("domain");

            Multihash nodeId = dht.id().get();
            // build a proxying corenode
            CoreNode core = new HTTPCoreNode(ipfsPoster, pkiServerNodeId);

            SocialNetworkProxy httpSocial = new HttpSocialNetwork(new JavaPoster(socialAddress), ipfsPoster);
            SocialNetwork p2pSocial = new ProxyingSocialNetwork(nodeId, core, httpSocial);

            MutablePointersProxy httpMutable = new HttpMutablePointers(new JavaPoster(coreAddress), ipfsPoster);
            MutablePointers p2mMutable = new ProxyingMutablePointers(nodeId, core, httpMutable);
            Path blacklistPath = a.fromPeergosDir("blacklist_file", "blacklist.txt");
            PublicKeyBlackList blacklist = new UserBasedBlacklist(blacklistPath, core, p2mMutable, dht);
            MutablePointers mutablePointers = new BlockingMutablePointers(new PinningMutablePointers(p2mMutable, dht), blacklist);

            Path userPath = a.fromPeergosDir("whitelist_file", "user_whitelist.txt");
            int delayMs = a.getInt("whitelist_sleep_period", 1000 * 60 * 10);

            new UserFilePinner(userPath, core, mutablePointers, dht, delayMs).start();
            InetSocketAddress httpsMessengerAddress = new InetSocketAddress(hostname, userAPIAddress.getPort());
            new UserService(httpsMessengerAddress, dht, core, p2pSocial, mutablePointers, a);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void startFuse(Args a) {
        String username = a.getArg("username");
        String password = a.getArg("password");

        int webPort  = a.getInt("webport");
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


    public static void startIpfs(Args a) {
        IpfsWrapper ipfs = IpfsWrapper.build(a);
        IpfsWrapper.Config config = IpfsWrapper.buildConfig(a);

        if (a.getBoolean("ipfs-manage-runtime", true))
            IpfsWrapper.launchAndManage(ipfs, config);
        else {
            IpfsWrapper.launchOnce(ipfs, config);
        }
        // set up p2p proxy receiver
        ipfs.startP2pProxy(new MultiAddress(a.getArg("proxy-target")));
    }
    public static void startCoreNode(Args a) {
        String corenodeFile = a.getArg("corenodeFile");
        String path = corenodeFile.equals(":memory:") ? corenodeFile : a.fromPeergosDir("corenodeFile").toString();
        int corenodePort = a.getInt("corenodePort");
        int maxUserCount = a.getInt("maxUserCount", CoreNode.MAX_USERNAME_COUNT);
        System.out.println("Using core node path " + path);
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

    public static void startSocialNode(Args a) {
        String keyfile = a.getArg("keyfile", "social.key");
        char[] passphrase = a.getArg("passphrase", "password").toCharArray();
        String socialNodeFile = a.getArg("socialnodeFile");
        String path = socialNodeFile.equals(":memory:") ? socialNodeFile : a.fromPeergosDir("socialnodeFile").toString();
        int socialnodePort = a.getInt("socialnodePort");
        int maxUserCount = a.getInt("maxUserCount", CoreNode.MAX_USERNAME_COUNT);
        System.out.println("Using social node path " + path);
        boolean useIPFS = a.getBoolean("useIPFS");
        int dhtCacheEntries = 1000;
        int maxValueSizeToCache = 2 * 1024 * 1024;
        ContentAddressedStorage dht = useIPFS ?
                new CachingStorage(new IpfsDHT(), dhtCacheEntries, maxValueSizeToCache) :
                new FileContentAddressedStorage(blockstorePath(a));
        try {
            SocialNetwork social = UserRepository.buildSqlLite(path, dht, maxUserCount);
            HttpSocialNetworkServer.createAndStart(keyfile, passphrase, socialnodePort, social, a);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static final Command MAIN = new Command("Main",
            "Run a Peergos server, corenode server, social network server or combination thereof",
            args -> {
                Optional<String> top = args.head();
                if (! top.isPresent()) {
                    System.out.println("Run with -help to show options");
                    return;
                }
                args.setIfAbsent("domain", "localhost");
                args.setIfAbsent("corenodeFile", ":memory:");
                if (args.getBoolean("useIPFS", true))
                    startIpfs(args);
                startCoreNode(args);
                startPeergos(args);
            },
            Collections.emptyList(),
            Arrays.asList(
                    CORE_NODE,
                    SOCIAL,
                    PEERGOS,
                    LOCAL,
                    DEMO,
                    FUSE
            )
    );

    /**
     * Create path to local blockstore directory from Args.
     * @param args
     * @return
     */
    private static Path blockstorePath(Args args) {
        return args.fromPeergosDir("blockstore_dir", "blockstore");
    }

    private static MultiAddress getLocalMultiAddress(int port) {
        return new MultiAddress("/ip4/127.0.0.1/tcp/" + port);
    }

    public static void main(String[] args) {
        MAIN.main(Args.parse(args));
    }
}
