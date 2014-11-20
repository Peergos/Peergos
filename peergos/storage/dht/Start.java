package peergos.storage.dht;

import peergos.corenode.AbstractCoreNode;
import peergos.corenode.HTTPCoreNodeServer;
import peergos.crypto.SSL;
import peergos.directory.DirectoryServer;
import peergos.storage.net.IP;
import peergos.tests.Scripter;
import peergos.util.Args;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.LinkedList;
import java.util.List;

public class Start
{
    public static void main(String[] args) throws IOException
    {
        Args.parse(args);
        if (Args.hasOption("help"))
        {
            Args.printOptions();
            System.exit(0);
        }
        if (Args.hasOption("firstNode"))
        {
            new File("log/").mkdirs();
            for (File f : new File("log/").listFiles())
                f.delete();
        }
        if (Args.hasParameter("test"))
        {
            test(Args.getInt("test", 6));
        }
        else if (Args.hasOption("directoryServer"))
        {
            String keyfile = Args.getParameter("keyfile", "dir.key");
            char[] passphrase = Args.getParameter("passphrase", "password").toCharArray();
            DirectoryServer.createAndStart(keyfile, passphrase, DirectoryServer.PORT);
        }
        else if (Args.hasOption("coreNode"))
        {
            String keyfile = Args.getParameter("keyfile", "core.key");
            char[] passphrase = Args.getParameter("passphrase", "password").toCharArray();
            HTTPCoreNodeServer.createAndStart(keyfile, passphrase, AbstractCoreNode.PORT);
        }
        else if (Args.hasOption("rootGen"))
        {
            SSL.generateAndSaveRootCertificate(Args.getParameter("password").toCharArray());
        }
        else if (Args.hasOption("dirGen"))
        {
            String domain = Args.getParameter("domain", IP.getMyPublicAddress().getHostAddress());
            String ipAddress = SSL.isIPAddress(domain) ? domain : null;
            SSL.generateCSR(Args.getParameter("password").toCharArray(), domain, ipAddress, Args.getParameter("keyfile"), "dir.csr");
        }
        else if (Args.hasOption("dirSign"))
        {
            SSL.signCertificate(Args.getParameter("csr"), Args.getParameter("rootPassword").toCharArray(), "Directory");
        }
        else if (Args.hasOption("coreGen"))
        {
            String domain = Args.getParameter("domain", IP.getMyPublicAddress().getHostAddress());
            String ipAddress = SSL.isIPAddress(domain) ? domain : null;
            SSL.generateCSR(Args.getParameter("password").toCharArray(), domain, ipAddress, Args.getParameter("keyfile"), "core.csr");
        }
        else if (Args.hasOption("coreSign"))
        {
            SSL.signCertificate(Args.getParameter("csr", "core.csr"), Args.getParameter("rootPassword").toCharArray(), "Core");
        }
        else {
            int port = Args.getInt("port", 8000);
            String user = Args.getParameter("user", "root");
            boolean isFirstNode = Args.hasOption("firstNode");
            InetAddress contactIP = isFirstNode ? null : InetAddress.getByName(Args.getParameter("contactIP"));
            int contactPort = Args.getInt("contactPort", 8080);
            Router router = new Router(user, port);
            router.init(contactIP, contactPort);
            // router is ready!
            System.out.println(port+" joined dht");
            DHTAPI api = new DHTAPI(router);
            if (Args.hasParameter("script")) {
                new Scripter(api, Args.getParameter("script")).start();
            }
        }
    }

    public static void test(int nodes) throws IOException
    {
        if (!Args.hasParameter("script"))
            throw new IllegalStateException("Need a script argument for test mode");
        String script = Args.getParameter("script");
        Start.main(new String[] {"-directoryServer", "-local"});

        Start.main(new String[] {"-coreNode", "-local"});

        String[] args;
        if (nodes > 1 )
            args = new String[]{"-firstNode", "-port", "8000", "-logMessages", "-local"};//, "-script", script};
        else
            args = new String[]{"-firstNode", "-port", "8000", "-logMessages", "-local", "-script", script};
        Start.main(args);
        if (nodes == 1)
            return;
        args = new String[]{"-port", "", "-logMessages", "-local", "-contactIP", IP.getMyPublicAddress().getHostAddress(), "-contactPort", args[2]};
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
