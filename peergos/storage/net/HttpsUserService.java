package peergos.storage.net;

import com.sun.net.httpserver.*;
import peergos.corenode.AbstractCoreNode;
import peergos.corenode.HTTPCoreNodeServer;
import peergos.crypto.SSL;
import peergos.storage.dht.Message;
import org.bouncycastle.operator.OperatorCreationException;
import peergos.storage.dht.Router;
import peergos.user.fs.erasure.ErasureHandler;
import peergos.util.*;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HttpsUserService
{
    public static final String MESSAGE_URL = "/message/";
    public static final String DHT_URL = "/dht/";
    public static final String ERASURE_URL = "/erasure/";
    public static final String PUBLIC_LINK_URL = "/public/";
    public static final String UI_URL = "/";
    public static final String UI_DIR = "ui/";

    public static final int THREADS = 200;
    public static final int CONNECTION_BACKLOG = 100;

    static {
        // disable weak algorithms
        System.out.println("\nInitial security properties:");
        System.out.println("jdk.tls.disabledAlgorithms: "+Security.getProperty("jdk.tls.disabledAlgorithms"));
        System.out.println("jdk.certpath.disabledAlgorithms: "+Security.getProperty("jdk.certpath.disabledAlgorithms"));
        System.out.println("jdk.tls.rejectClientInitializedRenegotiation: "+Security.getProperty("jdk.tls.rejectClientInitializedRenegotiation"));

        Security.setProperty("jdk.tls.disabledAlgorithms", "SSLv3, RC4, MD2, MD4, MD5, SHA1, DSA, DH, RSA keySize < 2048, EC keySize < 160");
        Security.setProperty("jdk.certpath.disabledAlgorithms", "RC4, MD2, MD4, MD5, SHA1, DSA, RSA keySize < 2048, EC keySize < 160");
        Security.setProperty("jdk.tls.rejectClientInitializedRenegotiation", "true");

        System.out.println("\nUpdated security properties:");
        System.out.println("jdk.tls.disabledAlgorithms: " + Security.getProperty("jdk.tls.disabledAlgorithms"));
        System.out.println("jdk.certpath.disabledAlgorithms: " + Security.getProperty("jdk.certpath.disabledAlgorithms"));
        System.out.println("jdk.tls.rejectClientInitializedRenegotiation: "+Security.getProperty("jdk.tls.rejectClientInitializedRenegotiation"));

        Security.setProperty("jdk.tls.ephemeralDHKeySize", "2048");
    }

    private final Logger LOGGER;
    private final InetSocketAddress local;
    private final AbstractCoreNode coreNode;
    HttpsServer httpsServer;

    public HttpsUserService(InetSocketAddress local, Logger LOGGER, Router router, AbstractCoreNode coreNode) throws IOException
    {
        this.LOGGER = LOGGER;
        this.local = local;
        this.coreNode = coreNode;
        init(router);
    }

    public boolean init(Router router) throws IOException {
        try
        {
            try {
                HttpServer httpServer = HttpServer.create();
                httpServer.createContext("/", new RedirectHandler("https://"+local.getHostName()+":"+local.getPort()+"/"));
                httpServer.bind(new InetSocketAddress(InetAddress.getLocalHost(), 80), CONNECTION_BACKLOG);
                httpServer.start();
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Couldn't start http redirect to https for user server!");
            }
            System.out.println("Starting user API server at: " + local.getHostName() + ":" + local.getPort());
            if (Args.hasArg("publicserver")) {
                System.out.println("Starting user server on all interfaces.");
                httpsServer = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("::"), local.getPort()), CONNECTION_BACKLOG);
            } else if (!local.getHostName().contains("local"))
                httpsServer = HttpsServer.create(local, CONNECTION_BACKLOG);
            else {
                System.out.println("Starting user server on localhost only.");
                httpsServer = HttpsServer.create(new InetSocketAddress(InetAddress.getLocalHost(), local.getPort()), CONNECTION_BACKLOG);
            }
            SSLContext sslContext = SSLContext.getInstance("TLS");

            char[] password = "storage".toCharArray();
            KeyStore ks = SSL.getKeyStore(password);

            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, password);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
            tmf.init(SSL.getTrustedKeyStore());

            // setup the HTTPS context and parameters
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            // set up perfect forward secrecy
            sslContext.getSupportedSSLParameters().setCipherSuites(new String[]{
                    "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
                    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                    "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
                    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"});

            SSLContext.setDefault(sslContext);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                public void configure(HttpsParameters params) {
                    try {
                        // initialise the SSL context
                        SSLContext c = SSLContext.getDefault();
                        SSLEngine engine = c.createSSLEngine();
                        params.setNeedClientAuth(false);
                        params.setCipherSuites(engine.getEnabledCipherSuites());
                        params.setProtocols(engine.getEnabledProtocols());

                        // get the default parameters
                        SSLParameters defaultSSLParameters = c.getDefaultSSLParameters();
                        params.setSSLParameters(defaultSSLParameters);
                    } catch (Exception ex) {
                        System.err.println("Failed to create HTTPS port");
                        ex.printStackTrace(System.err);
                    }
                }
            });
        }
        catch (NoSuchAlgorithmException|InvalidKeyException|KeyStoreException|CertificateException|
                NoSuchProviderException|SignatureException|OperatorCreationException|
                UnrecoverableKeyException|KeyManagementException ex)
        {
            System.err.println("Failed to create HTTPS port");
            ex.printStackTrace(System.err);
            return false;
        }

        httpsServer.createContext(DHT_URL, new DHTUserAPIHandler(router));
        httpsServer.createContext(ERASURE_URL, ErasureHandler.getInstance());
        httpsServer.createContext(PUBLIC_LINK_URL, new FixedResponseHandler(UI_DIR, "publiclink.html", false));
        httpsServer.createContext(UI_URL, new StaticHandler(UI_DIR, true));
        httpsServer.createContext(HTTPCoreNodeServer.CORE_URL, new HTTPCoreNodeServer.CoreNodeHandler(coreNode));
        httpsServer.setExecutor(Executors.newFixedThreadPool(THREADS));
        httpsServer.start();

        return true;
    }

    // need to think about latency of opening all these SSL connections,
    // maybe we could keep open connections to neighbours, and only open to distant nodes in the DHT?
    public void sendMessage(Message m, InetAddress addr, int port) throws IOException
    {
        if (Message.LOG)
            LOGGER.log(Level.ALL, String.format("Sent %s with target %d to %s:%d\n", m.name(), m.getTarget(), addr, port));
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        m.write(new DataOutputStream(bout));
        URL target = new URL("https", addr.getHostAddress(), port, MESSAGE_URL);
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("PUT");
        LOGGER.log(Level.ALL, String.format("prior to get output stream"));
        OutputStream out = conn.getOutputStream();
        LOGGER.log(Level.ALL, String.format("post to get output stream"));
        out.write(bout.toByteArray());
        out.flush();
        out.close();
        conn.getResponseCode();
        LOGGER.log(Level.ALL, String.format("Finished sending %s with target %d to %s:%d\n", m.name(), m.getTarget(), addr, port));
    }

    public static byte[] getFragment(InetAddress addr, int port, String key) throws IOException
    {
        // for now, make a direct connection
        URL target = new URL("https", addr.getHostAddress(), port, key);
        System.out.println("getting fragment from " + addr.getHostAddress());
        URLConnection conn = target.openConnection();
        InputStream in = conn.getInputStream();
        byte[] buf = new byte[2*1024*1024];
        ByteArrayOutputStream bout = new ByteArrayOutputStream();

        while (true)
        {
            int r = in.read(buf);
            if (r < 0)
                break;
            bout.write(buf, 0, r);
        }
        return bout.toByteArray();
    }

    public static void putFragment(InetAddress addr, int port, String key, byte[] value) throws IOException
    {
        // for now, make a direct connection
        URL target = new URL("https", addr.getHostAddress(), port, key+"/");
        System.out.println("sending fragment to " + target.toString());
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("PUT");
        OutputStream out = conn.getOutputStream();
        out.write(value);
        out.flush();
        out.close();
        conn.getResponseCode();
    }

    public static class JOIN
    {
        public final InetAddress addr;
        public final int port;

        public JOIN(InetAddress addr, int port)
        {
            this.addr = addr;
            this.port = port;
        }
    }
}
