package peergos.user;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import peergos.util.Args;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class ResourceHandler implements HttpHandler {

    private final String root;

    public ResourceHandler(String root) {
        this.root = root;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {

        String path = httpExchange.getRequestURI().getPath();
        path = path.substring(1);

        InputStream in = getResource(path);
        OutputStream responseBody = httpExchange.getResponseBody();

        try {
            if (in == null)
            {
                httpExchange.sendResponseHeaders(404, 0);
                System.out.println("No resource found for "+ path);
                return;
            }

            in = new BufferedInputStream(in);
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            byte[] buffer = new byte[0x1000];
            int nRead = 0;
            try
            {
                while ((nRead = in.read(buffer)) != -1)
                    bout.write(buffer, 0, nRead);
            } finally {
                in.close();
            }

            byte[] data = bout.toByteArray();

            httpExchange.sendResponseHeaders(200, data.length);
            responseBody.write(data);
        } finally {
            responseBody.close();
        }
    }

    private InputStream getResource(String path) {
        String fullPath = root  + path;
        if (fullPath.equals(root))
            fullPath += "index.html";

        System.out.println("Getting resource for path "+  path + " with full-path " + fullPath);
        return getClass().getClassLoader().getResourceAsStream(fullPath);
    }

    public static void main(String[] args) throws IOException {
        Args.parse(args);
        String listenOn = Args.getArg("address", "localhost");
        int port = Args.getInt("port", 8002);

        InetSocketAddress address = new InetSocketAddress(listenOn, port);

        HttpServer server = HttpServer.create(address, 10);
        server.createContext("/", new ResourceHandler("ui/"));
        server.setExecutor(Executors.newSingleThreadExecutor());

        System.out.println("Starting resource-handler on "+ address);
        server.start();
    }
}
