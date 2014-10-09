package peergos.directory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;

public class MyIPHandler implements HttpHandler {
    private final DirectoryServer dir;

    public MyIPHandler(DirectoryServer dir) {
        this.dir = dir;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        if (httpExchange.getRequestMethod().equals("GET"))
        {
            handleGet(httpExchange);
        }
    }

    protected void handleGet(HttpExchange exchange) throws IOException
    {
        try {
            InputStream in = exchange.getRequestBody();
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf)) >= 0) {
                bout.write(buf, 0, read);
            }
            in.close();
            InetSocketAddress source = exchange.getRemoteAddress();
            byte[] raw = source.getHostName().getBytes();
            exchange.sendResponseHeaders(200, raw.length);
            exchange.getResponseBody().write(raw);
            exchange.close();
        } catch (Exception e)
        {
            e.printStackTrace();
            exchange.sendResponseHeaders(400, 0);
            exchange.close();
        }
    }
}
