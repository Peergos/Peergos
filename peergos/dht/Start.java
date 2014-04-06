package peergos.dht;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Inbox;
import peergos.crypto.SSL;
import peergos.directory.DirectoryServer;
import peergos.net.IP;
import peergos.tests.Scripter;
import peergos.util.Args;
import scala.concurrent.duration.Duration;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
        else if (Args.hasOption("rootGen"))
        {
            SSL.generateAndSaveRootCertificate(Args.getParameter("password").toCharArray());
        }
        else if (Args.hasOption("dirGen"))
        {

            SSL.generateCSR(Args.getParameter("password").toCharArray(), Args.getParameter("keyfile"), "dir.csr");
        }
        else if (Args.hasOption("dirSign"))
        {
            SSL.signDirectoryCertificate(Args.getParameter("csr"), Args.getParameter("rootPassword").toCharArray());
        }
        else {
            int port = Args.getInt("port", 8000);
            ActorSystem system = ActorSystem.create("DHT Router");
            ActorRef router = Router.start(system, port);
            final Inbox inbox = Inbox.create(system);
            inbox.send(router, new Messenger.INITIALIZE());
            // wait for INITIALIZED or INITERROR
            Object result = inbox.receive(Duration.create(10, TimeUnit.SECONDS));
            if (result instanceof Messenger.INITERROR)
            {
                throw new IllegalStateException("Couldn't INIT DHT router!");
            }
            if (Args.hasOption("firstNode"))
                inbox.send(router, new Messenger.JOIN(null, 0));
            else
                inbox.send(router, new Messenger.JOIN(InetAddress.getByName(Args.getParameter("contactIP")), Args.getInt("contactPort", 8080)));
            Object joinResult = inbox.receive(Duration.create(10, TimeUnit.SECONDS));
            if (joinResult instanceof Messenger.JOINERROR)
            {
                // maybe try again?
                throw new IllegalStateException("Couldn't join the DHT!");
            }
            // router is ready!

            API api = new API(router);
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
        Start.main(new String[] {"-directoryServer"});
        String[] args = new String[]{"-firstNode", "-port", "8000", "-logMessages"};//, "-script", script};
        Start.main(args);
        args = new String[]{"-port", "", "-logMessages", "-contactIP", IP.getMyPublicAddress().getHostAddress(), "-contactPort", args[2]};
        if (nodes > 1)
            for (int i = 0; i < nodes - 2; i++)
            {
                args[1] = 9000 + 1000 * i + "";
                Start.main(args);
            }
        // execute the script on the last node
        args[1] = 9000 + 1000 * (nodes-2) + "";
        List<String> finalNodeArgs = new LinkedList();
        finalNodeArgs.addAll(java.util.Arrays.asList(args));
        finalNodeArgs.add("-script");
        finalNodeArgs.add(script);
        Start.main(finalNodeArgs.toArray(new String[finalNodeArgs.size()]));
    }
}
