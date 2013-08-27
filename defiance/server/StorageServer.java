package defiance.server;

import org.nereus.http.*;
import defiance.util.*;

public class StorageServer implements HTTPRequestHandler
{

    public boolean handleRequest(String urlStem, HTTPInputStream request, HTTPOutputStream response) throws IOException
    {
	
	return true;
    }
    
    public static void main(String[] args) throws Exception
    {
	Args a = new Args(args);
        int port = a.getInt("port", 8080);
        PathMappedHTTPRequestHandler special = new PathMappedHTTPRequestHandler();
        special.registerHandler("/", new StorageServer());

        OrderedHTTPRequestHandler main = new OrderedHTTPRequestHandler(new HTTPRequestHandler[]{special});
        
        HTTPServer server = new HTTPServer(main, true);
        server.listenOn(port, null);

        System.out.println("Defiance Storage Server listening on port "+port);
    }
}