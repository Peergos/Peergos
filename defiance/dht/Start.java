package defiance.dht;

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
            return;
        }
        if (Args.hasParameter("directoryServer"))
        {
            DirectoryServer.createAndStart();
            return;
        }
        int port = Args.getInt("port", 8080);
        RoutingServer rs = new RoutingServer(port);
        rs.start();
        API api = new API(rs);
        if (Args.hasParameter("script"))
        {
            new Scripter(api, Args.getParameter("script")).start();
        }
    }
}
