package peergos.server;

import peergos.server.corenode.*;
import peergos.server.mutable.*;
import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.mutable.*;
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
            Stream.of(
                    new Command.Arg("corenodePath", "Path to a local corenode sql file (created if it doesn't exist)", false, ":memory:"),
                    new Command.Arg("keyfile", "Path to keyfile", false),
                    new Command.Arg("passphrase", "Passphrase for keyfile", false),
                    new Command.Arg("corenodePort", "Service port", true, "" + HttpCoreNodeServer.PORT)
            ).collect(Collectors.toList())
    );

    public static final Command PEERGOS = new Command("peergos",
            "The user facing Peergos server",
            Start::startPeergos,
            Stream.of(
                    new Command.Arg("port",  "service port", false, "8000"),
                    new Command.Arg("corenodeURL", "Core node address", false, "http://localhost:" + HttpCoreNodeServer.PORT),
                    new Command.Arg("domain", "Domain name to bind to,", false, "localhost"),
                    new Command.Arg("useIPFS", "Use IPFS for storage or ephemeral RAM store", false, "true"),
                    new Command.Arg("webroot", "the path to the directory to serve as the web root", false),
                    new Command.Arg("publicserver", "listen on all network interfaces, not just localhost", false)
            ).collect(Collectors.toList())
    );

    public static final Command DEMO = new Command("demo",
            "Run in demo server mode",
            args -> {
                args.setIfAbsent("domain", "demo.peergos.net");
                args.setIfAbsent("corenodePath", "core.sql");
                args.setIfAbsent("useIPFS", "true");
                args.setIfAbsent("publicserver", "true");
                CORE_NODE.main(args);
                args.setIfAbsent("corenodeURL", "http://localhost:" + args.getArg("corenodePort"));
                PEERGOS.main(args);
            },
            Collections.emptyList()
    );

    public static final Command LOCAL = new Command("local",
            "Start an ephemeral Peergos Server and CoreNode server",
            args -> {
                args.setIfAbsent("domain", "localhost");
                args.setIfAbsent("corenodePath", ":memory:");
                args.setIfAbsent("useIPFS", "false");
                CORE_NODE.main(args);
                args.setIfAbsent("corenodeURL", "http://localhost:" + args.getArg("corenodePort"));
                PEERGOS.main(args);
            },
            Collections.emptyList()
    );

    public static final Command FUSE = new Command("fuse",
            "Mount a Peergos user's filesystem natively",
            Start::startFuse,
            Stream.of(
                    new Command.Arg("username", "Peergos username", false),
                    new Command.Arg("password", "Peergos password", false),
                    new Command.Arg("webport", "Peergos service address port", false, "8000"),
                    new Command.Arg("mountPoint", "The directory to mount the Peergos filesystem in", true, "peergos")
            ).collect(Collectors.toList())
    );

    public static void startPeergos(Args a) {
        try {
            PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, new Ed25519.Java());

            int webPort = a.getInt("port");
            URL coreAddress = new URI(a.getArg("corenodeURL")).toURL();
            String domain = a.getArg("domain");
            InetSocketAddress userAPIAddress = new InetSocketAddress(domain, webPort);

            boolean useIPFS = a.getBoolean("useIPFS");
            int dhtCacheEntries = 1000;
            int maxValueSizeToCache = 50 * 1024;
            ContentAddressedStorage dht = useIPFS ? new CachingStorage(new IpfsDHT(), dhtCacheEntries, maxValueSizeToCache) : RAMStorage.getSingleton();

            // start the User Service
            String hostname = a.getArg("domain");

            CoreNode core = HTTPCoreNode.getInstance(coreAddress);
            MutablePointers mutable = HttpMutablePointers.getInstance(coreAddress);
            String blacklistPath = "blacklist.txt";
            PublicKeyBlackList blacklist = new UserBasedBlacklist(Paths.get(blacklistPath), core, mutable, dht);
            MutablePointers pinner = new BlockingMutablePointers(new PinningMutablePointers(mutable, dht), blacklist);

            InetSocketAddress httpsMessengerAddress = new InetSocketAddress(hostname, userAPIAddress.getPort());
            new UserService(httpsMessengerAddress, Logger.getLogger("IPFS"), dht, core, pinner, a);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    public static void startFuse(Args a) {
        String username = a.getArg("username", "test01");
        String password = a.getArg("password", "test01");

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
            UserRepository userRepository = UserRepository.buildSqlLite(path, dht, maxUserCount);
            HttpCoreNodeServer.createAndStart(keyfile, passphrase, corenodePort, userRepository, userRepository, a);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static final Command MAIN = new Command("Main",
            "Run a Peergos server and/or a corenode server",
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
