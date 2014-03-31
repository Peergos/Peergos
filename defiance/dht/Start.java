package defiance.dht;

import defiance.crypto.SSL;
import defiance.directory.DirectoryServer;
import defiance.tests.Scripter;
import defiance.util.Args;

import java.io.File;
import java.io.IOException;

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
            RoutingServer.test(Args.getInt("test", 6));
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
            RoutingServer rs = new RoutingServer(port);
            rs.start();
            API api = new API(rs);
            if (Args.hasParameter("script")) {
                new Scripter(api, Args.getParameter("script")).start();
            }
        }
    }
}
