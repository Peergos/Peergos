package defiance.storage;

import defiance.dht.FragmentUploadHandler;
import org.nereus.http.server.*;

import java.io.*;

public class StorageServer
{

    public static void createAndStart(int port, File root1, Storage storage1, File root2, Storage storage2) throws IOException
    {
        try {
            HTTPRequestFilter keys = new StaticURLFilter("key", new DirectoryHandler(root2, true));
            HTTPRequestFilter fragments = new StaticURLFilter("", new FragmentUploadHandler(root1, true, storage1));
            HTTPRequestFilter both = new OrderedHTTPRequestFilter(new HTTPRequestFilter[]{keys, fragments});
            Server server = new Server(both);
            server.listenOn(port, false);
        } catch (Exception e)
        {
            e.printStackTrace();
            return;
        }

        System.out.println("Defiance Storage and Key Server listening on TCP port "+port);
    }
}