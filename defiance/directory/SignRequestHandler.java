package defiance.directory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import defiance.crypto.SSL;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;

public class SignRequestHandler implements HttpHandler {
    private final DirectoryServer dir;

    public SignRequestHandler(DirectoryServer dir) {
        this.dir = dir;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        if (httpExchange.getRequestMethod().equals("PUT"))
        {
            handlePut(httpExchange);
        }
    }

    protected void handlePut(HttpExchange exchange) throws IOException
    {
        try {
            InputStream in = exchange.getRequestBody();
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read = 0;
            while ((read = in.read(buf)) >= 0) {
                bout.write(buf, 0, read);
            }
            in.close();
            PKCS10CertificationRequest csr = new PKCS10CertificationRequest(bout.toByteArray());
            Certificate signed = dir.signCertificate(csr);
            byte[] raw = signed.getEncoded();
            exchange.sendResponseHeaders(200, raw.length);
            exchange.getResponseBody().write(raw);
            exchange.close();
        } catch (CertificateEncodingException e)
        {
            e.printStackTrace();
            exchange.sendResponseHeaders(400, 0);
            exchange.close();
        }
    }
}
