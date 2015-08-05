package peergos.storage.net;

import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

public class FixedResponseHandler implements HttpHandler
{
    private final byte[] data;
    private final String path;

    public FixedResponseHandler(String pathToRoot, String path, boolean caching) throws IOException {
        if (caching)
            data = readResource(ClassLoader.getSystemClassLoader().getResourceAsStream(pathToRoot + path));
        else
            data = null;
        this.path = path;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        byte[] res = data != null ? data : readResource(new File(HttpsUserService.UI_DIR+path).exists() ?
                new FileInputStream(HttpsUserService.UI_DIR+path)
                : ClassLoader.getSystemClassLoader().getResourceAsStream(HttpsUserService.UI_DIR+path));

        if (path.endsWith(".js"))
            httpExchange.getResponseHeaders().set("Content-Type", "text/javascript");
        else if (path.endsWith(".html"))
            httpExchange.getResponseHeaders().set("Content-Type", "text/html");
        else if (path.endsWith(".css"))
            httpExchange.getResponseHeaders().set("Content-Type", "text/css");
        httpExchange.sendResponseHeaders(200, res.length);
        httpExchange.getResponseBody().write(res);
        httpExchange.getResponseBody().close();
    }

    private static byte[] readResource(InputStream in) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int r;
        while ((r=in.read(tmp)) >= 0)
            bout.write(tmp, 0, r);
        return bout.toByteArray();
    }
}
