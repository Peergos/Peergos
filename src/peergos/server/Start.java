package peergos.server;

import peergos.corenode.*;
import peergos.crypto.*;
import peergos.crypto.asymmetric.*;
import peergos.crypto.asymmetric.curve25519.*;
import peergos.fuse.*;
import peergos.server.storage.*;
import peergos.tests.*;
import peergos.user.*;
import peergos.util.*;

import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.*;

public class Start
{

    public static final Map<String, String> OPTIONS = new LinkedHashMap();
    static
    {
        OPTIONS.put("help", "Show this help.");
        OPTIONS.put("local", "Run an ephemeral localhost Peergos");
        OPTIONS.put("fuse", "Mount a Peergos user's filesystem natively");
        OPTIONS.put("corenode", "start a corenode");
        OPTIONS.put("demo", "run in demo mode");
        OPTIONS.put("publicserver", "listen on all network interfaces, not just localhost");
    }
    public static final Map<String, String> PARAMS = new LinkedHashMap();
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

    public static void printOptions()
    {
        System.out.println("\nPeergos Server help.");
        System.out.println("\nOptions:");
        for (String k: OPTIONS.keySet())
            System.out.println("-"+ k + "\t " + OPTIONS.get(k));
        System.out.println("\nParameters:");
        for (String k: PARAMS.keySet())
            System.out.println("-"+ k + "\t " + PARAMS.get(k));
    }

    public static void main(String[] args) {
        run(Args.parse(args));
    }

    public static void run(Args a) {
        try {
            PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, new JavaEd25519());

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
                int corenodePort = a.getInt("corenodePort", HTTPCoreNodeServer.PORT);
                System.out.println("Using core node path " + path);
                SQLiteCoreNode coreNode = SQLiteCoreNode.build(path);
                HTTPCoreNodeServer.createAndStart(keyfile, passphrase, corenodePort, coreNode, a);
            } else {
                int webPort = a.getInt("port", 8000);
                URL coreAddress = new URI(a.getArg("corenodeURL", "http://localhost:" + HTTPCoreNodeServer.PORT)).toURL();
                String domain = a.getArg("domain", "localhost");
                InetSocketAddress userAPIAddress = new InetSocketAddress(domain, webPort);

                boolean useIPFS = a.getBoolean("useIPFS", true);
                int dhtCacheEntries = 1000;
                int maxValueSizeToCache = 50 * 1024;
                ContentAddressedStorage dht = useIPFS ? new CachingStorage(new IpfsDHT(), dhtCacheEntries, maxValueSizeToCache) : new RAMStorage();

                // start the User Service
                String hostname = a.getArg("domain", "localhost");

                CoreNode core = HTTPCoreNode.getInstance(coreAddress);
                CoreNode pinner = new PinningCoreNode(core, dht);

                InetSocketAddress httpsMessengerAddress = new InetSocketAddress(hostname, userAPIAddress.getPort());
                new UserService(httpsMessengerAddress, Logger.getLogger("IPFS"), dht, pinner, a);

                if (a.hasArg("fuse")) {
                    String username = a.getArg("username", "test01");
                    String password = a.getArg("password", "test01");
                    Path mount = Files.createTempDirectory("peergos");
                    String mountPath = a.getArg("mountPoint", mount.toString());

                    Path path = Paths.get(mountPath);
                    path.toFile().mkdirs();

                    System.out.println("\n\nPeergos mounted at " + path + "\n\n");

                    UserContext userContext = UserTests.ensureSignedUp(username, password, webPort);
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

    public static void demo(Args a) throws Exception {
        String domain = a.getArg("domain", "demo.peergos.net");
        String corenodePath = a.getArg("corenodePath", "core.sql");
        int corenodePort = a.getInt("corenodePort", HTTPCoreNodeServer.PORT);

        Start.main(new String[] {"-corenode", "-domain", domain, "-corenodePath", a.getArg("corenodePath", corenodePath)});

        a.setArg("port", "443");
        a.setArg("corenodeURL", "http://" + domain + ":"+corenodePort);
        a.setParameter("publicserver");
        a.removeArg("demo");
        run(a);
    }

    public static void local(Args a) throws Exception {
        String domain = a.getArg("domain", "localhost");
        String corenodePath = a.getArg("corenodePath", ":memory:");
        int corenodePort = a.getInt("corenodePort", HTTPCoreNodeServer.PORT);

        run(Args.parse(new String[] {"-corenode", "-domain", domain, "-corenodePath", corenodePath, "-corenodePort", Integer.toString(corenodePort)}));

        a.setArg("corenodeURL", "http://localhost:"+corenodePort);
        a.removeArg("local");
        run(a);
    }
}
