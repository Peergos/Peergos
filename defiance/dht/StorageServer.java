package defiance.dht;

import org.nereus.http.server.*;

import java.io.*;

public class StorageServer
{

    public static void create(int port, File root, Storage storage) throws IOException
    {
        try {
            Server server = new Server(new FragmentUploadHandler(root, true, storage));
            server.listenOn(port, false);
        } catch (Exception e)
        {
            e.printStackTrace();
            return;
        }

        System.out.println("Defiance StorageServer listening on port "+port);
    }
}