package peergos.server;

import peergos.server.corenode.*;
import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.server.mutable.PinningMutablePointers;
import peergos.server.fuse.*;
import peergos.server.storage.*;
import peergos.server.tests.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Start
{
    static {
        PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, new Ed25519.Java());
    }


    public static final Map<String, String> OPTIONS = new LinkedHashMap<>();
    static
    {
        OPTIONS.put("help", "Show this help.");
        OPTIONS.put("local", "Run an ephemeral localhost Peergos");
        OPTIONS.put("fuse", "Mount a Peergos user's filesystem natively");
        OPTIONS.put("corenode", "Start a core-node server.");
        OPTIONS.put("demo", "run in demo mode");
        OPTIONS.put("publicserver", "listen on all network interfaces, not just localhost");
    }
    public static final Map<String, String> PARAMS = new LinkedHashMap<>();
    static
    {
        PARAMS.put("port", " the port to listen on.");
        PARAMS.put("useIPFS", "true/false use IPFS or an ephemeral RAM storage");
        PARAMS.put("corenodeURL", "URL of a corenode e.g. https://demo.peergos.net");
        PARAMS.put("mountPoint", "directory to mount Peergos in (used with -fuse)");
        PARAMS.put("username", "user whose filesystem will be mounted (used with -fuse)");
        PARAMS.put("password", "password for user filesystem to be mounted (used with -fuse)");
        PARAMS.put("corenodePath", "path to a local corenode sql file (created if it doesn't exist)");
        PARAMS.put("corenodePort", "port for the local core node to listen on");
        PARAMS.put("webroot", "the path to the directory to serve as the web root");
        PARAMS.put("domain", "the domain name of the machine that this Peergos server is running on");
        PARAMS.put("mountPoint", "the directory to mount the Peergos filesystem in");
        PARAMS.put("username", "only used for fuse");
        PARAMS.put("password", "only used for fuse");
    }


    public static Command CORE_NODE = new Command("startCoreNode",
            "Start a startCoreNode.",
            Start::startCoreNode,
            Stream.of(
                    new Command.Arg("keyfile", "Path to keyfile", false),
                    new Command.Arg("passphrase", "Passphrase for keyfile", false),
                    new Command.Arg("port", "Service port.", true)
            ).collect(Collectors.toList())
    );

    public static Command FUSE = new Command("fuse",
            "Mount a Peergos user's filesystem natively",
            Start::startFuse,
            Stream.of(
                    new Command.Arg("username", "Peergos user username.", false),
                    new Command.Arg("password", "Peergos user password.", false),
                    new Command.Arg("webport", "Peergos service address port.", false)
            ).collect(Collectors.toList())
    );

    public static Command RUN = new Command("run",
            "",
            Start::startPeergos,
            Stream.of(
                    new Command.Arg("port",  "service port", false),
                    new Command.Arg("coreNodeURL", "Core node service address", false),
                    new Command.Arg("domain", "Service name to bind to,", false),
                    new Command.Arg("useIPFS", "Use IPFS for storage?", false)
            ).collect(Collectors.toList())
    );

    public Command DEMO = new Command("startDemo",
            "Run in startDemo mode",
            args -> {
                args.setIfAbsent("domain", "demo.peergos.net");
                args.setIfAbsent("corenodePath", "core.sql");
                startCoreNode(args);
                startPeergos(args);
            },
            Stream.of(
                    CORE_NODE.params,
                    RUN.params)
                    .flatMap(List::stream)
                    .collect(Collectors.toList())
    );

    public static Command LOCAL = new Command("runLocal",
            "Start a Peergos Server and a CoreNode server",
            args -> {
                args.setIfAbsent("domain", "localhost");
                args.setIfAbsent("corenodePath", ":memory:");
                startCoreNode(args);
                startPeergos(args);
            },
            Stream.of(
                    CORE_NODE.params,
                    RUN.params)
                    .flatMap(List::stream)
                    .collect(Collectors.toList())
    );

    public static void startPeergos(Args a) {
        try {
            PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, new Ed25519.Java());

            if (a.hasArg("help")) {
                printOptions();
                System.exit(0);
            }
            if (a.hasArg("local")) {
                local(a);
            } else if (a.hasArg("demo")) {
                demo(a);
            } else if (a.hasArg("corenode")) {
                String keyfile = a.getArg("keyfile", "core.key");
                char[] passphrase = a.getArg("passphrase", "password").toCharArray();
                String path = a.getArg("corenodePath", ":memory:");
                int corenodePort = a.getInt("corenodePort", HttpCoreNodeServer.PORT);
                System.out.println("Using core node path " + path);
                boolean useIPFS = a.getBoolean("useIPFS", true);
                int dhtCacheEntries = 1000;
                int maxValueSizeToCache = 2 * 1024 * 1024;
                ContentAddressedStorage dht = useIPFS ? new CachingStorage(new IpfsDHT(), dhtCacheEntries, maxValueSizeToCache) : RAMStorage.getSingleton();
                UserRepository userRepository = UserRepository.buildSqlLite(path, dht);
                HttpCoreNodeServer.createAndStart(keyfile, passphrase, corenodePort, userRepository, userRepository, a);
            } else {
                int webPort = a.getInt("port", 8000);
                URL coreAddress = new URI(a.getArg("corenodeURL", "http://localhost:" + HttpCoreNodeServer.PORT)).toURL();
                String domain = a.getArg("domain", "localhost");
                InetSocketAddress userAPIAddress = new InetSocketAddress(domain, webPort);

                boolean useIPFS = a.getBoolean("useIPFS", true);
                int dhtCacheEntries = 1000;
                int maxValueSizeToCache = 50 * 1024;
                ContentAddressedStorage dht = useIPFS ? new CachingStorage(new IpfsDHT(), dhtCacheEntries, maxValueSizeToCache) : RAMStorage.getSingleton();

                // start the User Service
                String hostname = a.getArg("domain", "localhost");

                CoreNode core = HTTPCoreNode.getInstance(coreAddress);
                MutablePointers mutable = HttpMutablePointers.getInstance(coreAddress);
                MutablePointers pinner = new PinningMutablePointers(mutable, dht);

                InetSocketAddress httpsMessengerAddress = new InetSocketAddress(hostname, userAPIAddress.getPort());
                new UserService(httpsMessengerAddress, Logger.getLogger("IPFS"), dht, core, pinner, a);

                if (a.hasArg("fuse")) {
                    String username = a.getArg("username", "test01");
                    String password = a.getArg("password", "test01");
                    Path mount = Files.createTempDirectory("peergos");
                    String mountPath = a.getArg("mountPoint", mount.toString());

                    Path path = Paths.get(mountPath);
                    path.toFile().mkdirs();

                    System.out.println("\n\nPeergos mounted at " + path + "\n\n");

                    NetworkAccess network = NetworkAccess.buildJava(webPort).get();
                    Crypto crypto = Crypto.initJava();
                    UserContext userContext = UserTests.ensureSignedUp(username, password, network, crypto);
                    PeergosFS peergosFS = new PeergosFS(userContext);
                    FuseProcess fuseProcess = new FuseProcess(peergosFS, path);

                    Runtime.getRuntime().addShutdownHook(new Thread(() -> fuseProcess.close()));

                    fuseProcess.start();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    public static void startFuse(Args a) {
        String username = a.getArg("username", "test01");
        String password = a.getArg("password", "test01");

        int webPort  = a.getInt("webport", 8000);
        try {
            Files.createTempDirectory("peergos").toString();
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
        String mountPath = a.getArg("mountPoint", "peergos");
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
        String path = a.getArg("corenodePath", ":memory:");
        int corenodePort = a.getInt("corenodePort", HttpCoreNodeServer.PORT);
        boolean useIPFS = a.getBoolean("useIPFS", false);

        run(Args.parse(new String[] {"-corenode", "-useIPFS", "" + useIPFS, "-domain", domain, "-corenodePath", corenodePath, "-corenodePort", Integer.toString(corenodePort)}));
    }

    /*
    public Command MAIN = new Command("peergos", "Peergos",
            (args) -> {},
            Collections.emptyList(),
            //Stream.of()
            );
    */

    public static void main(String[] args) {
        Args.parse(args);
    }
}
