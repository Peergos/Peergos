package peergos.server;

import peergos.server.corenode.*;
import peergos.server.mutable.*;
import peergos.server.social.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.server.fuse.*;
import peergos.server.storage.*;
import peergos.server.tests.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.logging.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Start
{
    static {
        PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, new Ed25519.Java());
    }

    public static Command CORE_NODE = new Command("core",
            "Start a Corenode.",
            Start::startCoreNode,
            Arrays.asList(
                    new Command.Arg("corenodePath", "Path to a local corenode sql file (created if it doesn't exist)", false, ":memory:"),
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
            Start::startSocialNode,
            Stream.of(
                    new Command.Arg("socialnodePath", "Path to a local social node sql file (created if it doesn't exist)", false, ":memory:"),
                    new Command.Arg("keyfile", "Path to keyfile", false),
                    new Command.Arg("passphrase", "Passphrase for keyfile", false),
                    new Command.Arg("socialnodePort", "Service port", true, "" + HttpSocialNetworkServer.PORT)
            ).collect(Collectors.toList())
    );

    public static final Command PEERGOS = new Command("peergos",
            "The user facing Peergos server",
            Start::startPeergos,
            Stream.of(
                    new Command.Arg("port",  "service port", false, "8000"),
                    new Command.Arg("corenodeURL", "Core node address", false, "http://localhost:" + HttpCoreNodeServer.PORT),
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
                args.setIfAbsent("corenodePath", "core.sql");
                args.setIfAbsent("socialnodePath", "social.sql");
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
                            crypto.random, crypto.signer, crypto.boxer, UserGenerationAlgorithm.getDefault()).get();


                    boolean useIPFS = args.getBoolean("useIPFS");
                    ContentAddressedStorage dht = useIPFS ? new IpfsDHT() : RAMStorage.getSingleton();

                    SigningKeyPair peergosIdentityKeys = peergos.getUser();
                    PublicKeyHash peergosPublicHash = ContentAddressedStorage.hashKey(peergosIdentityKeys.publicSigningKey);

                    String pkiPassword = "testPkiPassword";
                    SigningKeyPair pkiKeys = UserUtil.generateUser(pkiUsername, pkiPassword, crypto.hasher, crypto.symmetricProvider,
                            crypto.random, crypto.signer, crypto.boxer, UserGenerationAlgorithm.getDefault()).get().getUser();
                    dht.putSigningKey(peergosIdentityKeys.secretSigningKey.signatureOnly(
                            pkiKeys.publicSigningKey.serialize()),
                            peergosPublicHash,
                            pkiKeys.publicSigningKey).get();

                    Files.write(Paths.get(args.getArg("pki.secret.key.path")), pkiKeys.secretSigningKey.toCbor().toByteArray());
                    Files.write(Paths.get(args.getArg("pki.public.key.path")), pkiKeys.publicSigningKey.toCbor().toByteArray());
                    args.setIfAbsent("peergos.identity.hash", peergosPublicHash.toString());

                } catch (Exception e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            },
            Arrays.asList(
                    new Command.Arg("peergos.password",
                            "The password for the peergos user required to bootstrap the network", true),
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
                            crypto.random, crypto.signer, crypto.boxer, UserGenerationAlgorithm.getDefault()).get();

                    SigningKeyPair peergosIdentityKeys = peergos.getUser();
                    PublicKeyHash peergosPublicHash = ContentAddressedStorage.hashKey(peergosIdentityKeys.publicSigningKey);
                    PublicSigningKey pkiPublic =
                            PublicSigningKey.fromByteArray(
                                    Files.readAllBytes(Paths.get(args.getArg("pki.public.key.path"))));
                    SecretSigningKey pkiSecretKey = SecretSigningKey.fromCbor(CborObject.fromByteArray(
                            Files.readAllBytes(Paths.get(args.getArg("pki.secret.key.path")))));
                    SigningKeyPair pkiKeys = new SigningKeyPair(pkiPublic, pkiSecretKey);
                    PublicKeyHash pkiPublicHash = ContentAddressedStorage.hashKey(pkiKeys.publicSigningKey);
                    int webPort = args.getInt("port");
                    NetworkAccess network = NetworkAccess.buildJava(new URL("http://localhost:" + webPort)).get();

                    // sign up peergos user
                    UserContext context = UserContext.ensureSignedUp(pkiUsername, password, network, crypto).get();
                    context.addNamedOwnedKeyAndCommit("pki", pkiPublicHash).get();
                    // write pki public key to ipfs
                    network.dhtClient.putSigningKey(peergosIdentityKeys.secretSigningKey
                            .signatureOnly(pkiKeys.publicSigningKey.serialize()), peergosPublicHash, pkiKeys.publicSigningKey).get();
                } catch (Exception e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            },
            Arrays.asList(
                    new Command.Arg("peergos.password",
                    "The password for the peergos user required to bootstrap the network", true),
                    new Command.Arg("pki.public.key.path", "The path to the pki public key file", true),
                    new Command.Arg("pki.secret.key.path", "The path to the pki secret key file", true)
            )
    );

    public static final Command LOCAL = new Command("local",
            "Start an ephemeral Peergos Server and CoreNode server",
            args -> {
                args.setIfAbsent("peergos.password", "testpassword");
                args.setIfAbsent("pki.secret.key.path", "test.pki.secret.key");
                args.setIfAbsent("pki.public.key.path", "test.pki.public.key");
                BOOTSTRAP.main(args);
                args.setIfAbsent("domain", "localhost");
                args.setIfAbsent("corenodePath", ":memory:");
                args.setIfAbsent("socialnodePath", ":memory:");
                args.setIfAbsent("useIPFS", "false");
                CORE_NODE.main(args);
                SOCIAL.main(args);
                args.setIfAbsent("corenodeURL", "http://localhost:" + args.getArg("corenodePort"));
                args.setIfAbsent("socialnodeURL", "http://localhost:" + args.getArg("socialnodePort"));
                PEERGOS.main(args);
                POSTSTRAP.main(args);
            },
            Collections.emptyList()
    );

    public static final Command FUSE = new Command("fuse",
            "Mount a Peergos user's filesystem natively",
            Start::startFuse,
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
            URL socialAddress = new URI(a.getArg("socialnodeURL")).toURL();
            String domain = a.getArg("domain");
            InetSocketAddress userAPIAddress = new InetSocketAddress(domain, webPort);

            boolean useIPFS = a.getBoolean("useIPFS");
            int dhtCacheEntries = 1000;
            int maxValueSizeToCache = 50 * 1024;
            ContentAddressedStorage dht = useIPFS ? new CachingStorage(new IpfsDHT(), dhtCacheEntries, maxValueSizeToCache) : RAMStorage.getSingleton();

            // start the User Service
            String hostname = a.getArg("domain");

            CoreNode core = HTTPCoreNode.getInstance(coreAddress);
            SocialNetwork social = HttpSocialNetwork.getInstance(socialAddress);
            MutablePointers mutable = HttpMutablePointers.getInstance(coreAddress);
            String blacklistPath = "blacklist.txt";
            PublicKeyBlackList blacklist = new UserBasedBlacklist(Paths.get(blacklistPath), core, mutable, dht);
            MutablePointers mutablePointers = new BlockingMutablePointers(new PinningMutablePointers(mutable, dht), blacklist);

            Path userPath = Paths.get(a.getArg("whitelist_path", "user_whitelist.txt"));
            int delayMs = a.getInt("whitelist_sleep_period", 1000 * 60 * 10);

            new UserFilePinner(userPath, core, mutablePointers, dht, delayMs).start();
            InetSocketAddress httpsMessengerAddress = new InetSocketAddress(hostname, userAPIAddress.getPort());
            new UserService(httpsMessengerAddress, Logger.getLogger("IPFS"), dht, core, social, mutablePointers, a);
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

    public static void startCoreNode(Args a) {
        String keyfile = a.getArg("keyfile", "core.key");
        char[] passphrase = a.getArg("passphrase", "password").toCharArray();
        String path = a.getArg("corenodePath");
        int corenodePort = a.getInt("corenodePort");
        int maxUserCount = a.getInt("maxUserCount", CoreNode.MAX_USERNAME_COUNT);
        System.out.println("Using core node path " + path);
        boolean useIPFS = a.getBoolean("useIPFS");
        int dhtCacheEntries = 1000;
        int maxValueSizeToCache = 2 * 1024 * 1024;
        ContentAddressedStorage dht = useIPFS ?
                new CachingStorage(new IpfsDHT(), dhtCacheEntries, maxValueSizeToCache) :
                RAMStorage.getSingleton();
        try {
            UserRepository mutable = UserRepository.buildSqlLite(path, dht, maxUserCount);
            PublicKeyHash peergosIdentity = PublicKeyHash.fromString(a.getArg("peergos.identity.hash"));

            PublicSigningKey pkiPublic =
                    PublicSigningKey.fromByteArray(
                            Files.readAllBytes(Paths.get(a.getArg("pki.public.key.path"))));
            SecretSigningKey pkiSecretKey = SecretSigningKey.fromCbor(CborObject.fromByteArray(
                    Files.readAllBytes(Paths.get(a.getArg("pki.secret.key.path")))));
            SigningKeyPair pkiKeys = new SigningKeyPair(pkiPublic, pkiSecretKey);
            PublicKeyHash pkiPublicHash = ContentAddressedStorage.hashKey(pkiKeys.publicSigningKey);

            MaybeMultihash currentPkiRoot = mutable.getPointerTarget(pkiPublicHash, dht).get();

            IpfsCoreNode core = new IpfsCoreNode(pkiKeys, currentPkiRoot, dht, mutable, peergosIdentity);
            HttpCoreNodeServer.createAndStart(keyfile, passphrase, corenodePort, core, mutable, a);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void startSocialNode(Args a) {
        String keyfile = a.getArg("keyfile", "social.key");
        char[] passphrase = a.getArg("passphrase", "password").toCharArray();
        String path = a.getArg("socialnodePath");
        int socialnodePort = a.getInt("socialnodePort");
        int maxUserCount = a.getInt("maxUserCount", CoreNode.MAX_USERNAME_COUNT);
        System.out.println("Using social node path " + path);
        boolean useIPFS = a.getBoolean("useIPFS");
        int dhtCacheEntries = 1000;
        int maxValueSizeToCache = 2 * 1024 * 1024;
        ContentAddressedStorage dht = useIPFS ?
                new CachingStorage(new IpfsDHT(), dhtCacheEntries, maxValueSizeToCache) :
                RAMStorage.getSingleton();
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
                args.setIfAbsent("corenodePath", ":memory:");
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

    public static void main(String[] args) {
        MAIN.main(Args.parse(args));
    }
}
