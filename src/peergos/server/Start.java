package peergos.server;

import peergos.corenode.*;
import peergos.crypto.*;
import peergos.server.storage.*;
import peergos.util.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.*;

public class Start
{

    public static final Map<String, String> OPTIONS = new LinkedHashMap();
    static
    {
        OPTIONS.put("help", "Show this help.");
    }
    public static final Map<String, String> PARAMS = new LinkedHashMap();
    static
    {
        PARAMS.put("port", " the I/O port to listen on.");
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
    public static void main(String[] args) throws IOException
    {
        Args.parse(args);
        if (Args.hasArg("help"))
        {
            printOptions();
            System.exit(0);
        }
        if (Args.hasArg("localJS"))
        {
            Args.parse(new String[]{"-domain", "localhost", "-coreNodePath", Args.getArg("coreNodePath", "core.sql")});
            local();
        }
        else if (Args.hasArg("demo"))
        {
            Args.parse(new String[]{"-domain", "demo.peergos.net", "-coreNodePath", Args.getArg("coreNodePath", "core.sql")});
            demo();
        }
        else if (Args.hasArg("coreNode"))
        {
            String keyfile = Args.getArg("keyfile", "core.key");
            char[] passphrase = Args.getArg("passphrase", "password").toCharArray();
            String path = Args.getArg("coreNodePath", ":memory:");
            int corenodePort = Args.getInt("corenodePort", HTTPCoreNodeServer.PORT);
            System.out.println("Using core node path "+ path);
            try {
                SQLiteCoreNode coreNode = SQLiteCoreNode.build(path);
                HTTPCoreNodeServer.createAndStart(keyfile, passphrase, corenodePort, coreNode);
            } catch (SQLException sqle) {
                throw new IllegalStateException(sqle);
            }
        }
        else if (Args.hasArg("rootGen"))
        {
            SSL.generateAndSaveRootCertificate(Args.getArg("password").toCharArray());
        }
        else if (Args.hasArg("coreGen"))
        {
            String domain = Args.getArg("domain");
            SSL.generateCSR(Args.getArg("password").toCharArray(), domain, Args.getArg("keyfile"), "core.csr");
        }
        else if (Args.hasArg("coreSign"))
        {
            SSL.signCertificate(Args.getArg("csr", "core.csr"), Args.getArg("rootPassword").toCharArray(), "Core");
        }
        else {
            int webPort = Args.getInt("port", 8000);
            int corenodePort = Args.getInt("port", HTTPCoreNodeServer.PORT);
            String domain = Args.getArg("domain", "localhost");
            InetSocketAddress userAPIAddress = new InetSocketAddress(domain, webPort);

            boolean useIPFS = Args.getBoolean("useIPFS", true);
            ContentAddressedStorage dht = useIPFS ? new IpfsDHT() : new RAMStorage();

            // start the User Service
            String hostname = Args.getArg("domain", "localhost");

            InetSocketAddress httpsMessengerAddress = new InetSocketAddress(hostname, userAPIAddress.getPort());
            CoreNode core = HTTPCoreNode.getInstance(corenodePort);
            CoreNode pinner = new PinningCoreNode(core, dht);

            new UserService(httpsMessengerAddress, Logger.getLogger("IPFS"), dht, pinner);
        }
    }

    public static void demo() throws IOException{
        String domain = Args.getArg("domain", "localhost");
        String coreNodePath = Args.getArg("coreNodePath", ":memory:");

        Start.main(new String[] {"-coreNode", "-domain", domain, "-coreNodePath", Args.getArg("coreNodePath", coreNodePath)});

        Start.main(new String[]{"-port", "443", "-logMessages", "-domain", domain, "-publicserver"});
    }

    public static void local() throws IOException{
        String domain = Args.getArg("domain", "localhost");
        String coreNodePath = Args.getArg("coreNodePath", ":memory:");
        boolean useIPFS = Args.getBoolean("useIPFS", true);
        int webPort = Args.getInt("port", 8000);
        int corenodePort = Args.getInt("corenodePort", HTTPCoreNodeServer.PORT);

        Start.main(new String[] {"-coreNode", "-domain", domain, "-coreNodePath", coreNodePath, "-corenodePort", Integer.toString(corenodePort)});

        Start.main(new String[]{"-port", Integer.toString(webPort), "-logMessages", "-domain", domain, "-publicserver",
                "-useIPFS", Boolean.toString(useIPFS), "-corenodePort", Integer.toString(corenodePort)});
    }
}
