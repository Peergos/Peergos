package peergos.server;

import peergos.corenode.*;
import peergos.crypto.*;
import peergos.server.storage.ContentAddressedStorage;
import peergos.server.storage.IpfsDHT;
import peergos.server.net.*;
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
        System.out.println("\nDefiance RoutingServer help.");
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
            System.out.println("Using core node path "+ path);
            try {
                SQLiteCoreNode coreNode = SQLiteCoreNode.build(path);
                HTTPCoreNodeServer.createAndStart(keyfile, passphrase, HTTPCoreNodeServer.PORT, coreNode);
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
            int port = Args.getInt("port", 8000);
            String domain = Args.getArg("domain", "localhost");
            InetSocketAddress userAPIAddress = new InetSocketAddress(domain, port);

            ContentAddressedStorage dht = new IpfsDHT();

            // start the User Service
            String hostname = Args.getArg("domain", "localhost");

            InetSocketAddress httpsMessengerAddress = new InetSocketAddress(hostname, userAPIAddress.getPort());
            CoreNode core = HTTPCoreNode.getInstance();

            new UserService(httpsMessengerAddress, Logger.getLogger("IPFS"), dht, core);
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

        Start.main(new String[] {"-coreNode", "-domain", domain, "-coreNodePath", coreNodePath});

        Start.main(new String[]{"-port", "8000", "-logMessages", "-domain", domain, "-publicserver"});
    }
}
