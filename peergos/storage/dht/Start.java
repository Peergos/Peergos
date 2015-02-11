package peergos.storage.dht;

import peergos.corenode.AbstractCoreNode;
import peergos.corenode.HTTPCoreNodeServer;
import peergos.crypto.SSL;
import peergos.directory.DirectoryServer;
import peergos.storage.net.IPMappings;
import peergos.tests.Scripter;
import peergos.util.Args;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class Start
{

    public static final Map<String, String> OPTIONS = new LinkedHashMap();
    static
    {
        OPTIONS.put("help", "Show this help.");
        OPTIONS.put("firstNode", " This is the first node in the network (don't attempt to concat the network).");
        OPTIONS.put("logMessages", "Print every received message to the console.");
    }
    public static final Map<String, String> PARAMS = new LinkedHashMap();
    static
    {
        PARAMS.put("port", " the I/O port to listen on.");
        PARAMS.put("contactIP", "name or IP address of contact point to concat the network");
        PARAMS.put("contactPort", "port of contact point to concat the network");
        PARAMS.put("test", "number of local nodes to start in test mode");
        PARAMS.put("script", "script of commands to run during test");
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
        if (Args.hasArg("firstNode"))
        {
            new File("log/").mkdirs();
            for (File f : new File("log/").listFiles())
                f.delete();
        }
        if (Args.hasArg("test"))
        {
            test(Args.getInt("test", 6));
        }
        else if (Args.hasArg("directoryServer"))
        {
            String keyfile = Args.getArg("keyfile", "dir.key");
            char[] passphrase = Args.getArg("passphrase", "password").toCharArray();
            DirectoryServer.createAndStart(keyfile, passphrase, DirectoryServer.PORT);
        }
        else if (Args.hasArg("coreNode"))
        {
            String keyfile = Args.getArg("keyfile", "core.key");
            char[] passphrase = Args.getArg("passphrase", "password").toCharArray();
            HTTPCoreNodeServer.createAndStart(keyfile, passphrase, AbstractCoreNode.PORT);
        }
        else if (Args.hasArg("rootGen"))
        {
            SSL.generateAndSaveRootCertificate(Args.getArg("password").toCharArray());
        }
        else if (Args.hasArg("dirGen"))
        {
            String domain = Args.getArg("domain", IPMappings.getMyPublicAddress(DirectoryServer.PORT).getHostName());
            String ipAddress = SSL.isIPAddress(domain) ? domain : null;
            SSL.generateCSR(Args.getArg("password").toCharArray(), domain, ipAddress, Args.getArg("keyfile"), "dir.csr");
        }
        else if (Args.hasArg("dirSign"))
        {
            SSL.signCertificate(Args.getArg("csr"), Args.getArg("rootPassword").toCharArray(), "Directory");
        }
        else if (Args.hasArg("coreGen"))
        {
            String domain = Args.getArg("domain", IPMappings.getMyPublicAddress(AbstractCoreNode.PORT).getHostName());
            String ipAddress = SSL.isIPAddress(domain) ? domain : null;
            SSL.generateCSR(Args.getArg("password").toCharArray(), domain, ipAddress, Args.getArg("keyfile"), "core.csr");
        }
        else if (Args.hasArg("coreSign"))
        {
            SSL.signCertificate(Args.getArg("csr", "core.csr"), Args.getArg("rootPassword").toCharArray(), "Core");
        }
        else {
            int port = Args.getInt("port", 8000);
            InetSocketAddress userAPIAddr = IPMappings.getMyPublicAddress(port);
            InetSocketAddress messengerAddr = IPMappings.getMyPublicAddress(port + 1);
            String user = Args.getArg("user", "root");
            boolean isFirstNode = Args.hasArg("firstNode");
            InetAddress contactIP = isFirstNode ? null : InetAddress.getByName(Args.getArg("contactIP"));
            int contactPort = Args.getInt("contactPort", 8080);
            Router router = new Router(user, userAPIAddr, messengerAddr);
            router.init(new InetSocketAddress(contactIP, contactPort));
            // router is ready!
            System.out.println(port+" joined dht");
            DHTAPI api = new DHTAPI(router);
            if (Args.hasArg("script")) {
                new Scripter(api, Args.getArg("script")).start();
            }
        }
    }

    public static void test(int nodes) throws IOException
    {
        if (!Args.hasArg("script"))
            throw new IllegalStateException("Need a script argument for test mode");
        String script = Args.getArg("script");
        Start.main(new String[] {"-directoryServer", "-local", "-domain", "localhost"});

        Start.main(new String[] {"-coreNode", "-local"});

        String[] args;
        if (nodes > 1 )
            args = new String[]{"-firstNode", "-port", "8000", "-logMessages", "-local"};//, "-script", script};
        else
            args = new String[]{"-firstNode", "-port", "8000", "-logMessages", "-local", "-script", script};
        Start.main(args);
        if (nodes == 1)
            return;
        args = new String[]{"-port", "", "-logMessages", "-local", "-contactIP", "localhost", "-contactPort", args[2]};
        if (nodes > 1)
            for (int i = 0; i < nodes - 2; i++)
            {
                args[1] = 9000 + 500 * i + "";
                Start.main(args);
            }
        // execute the script on the last node
        args[1] = 9000 + 500 * (nodes-2) + "";
        List<String> finalNodeArgs = new LinkedList();
        finalNodeArgs.addAll(java.util.Arrays.asList(args));
        finalNodeArgs.add("-script");
        finalNodeArgs.add(script);
        Start.main(finalNodeArgs.toArray(new String[finalNodeArgs.size()]));
    }
}
