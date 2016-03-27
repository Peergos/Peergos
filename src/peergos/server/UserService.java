package peergos.server;

import com.sun.net.httpserver.*;
import peergos.corenode.*;
import peergos.crypto.SSL;
import org.bouncycastle.operator.OperatorCreationException;
import peergos.server.net.*;
import peergos.server.storage.ContentAddressedStorage;
import peergos.util.*;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class UserService
{
    public static final String DHT_URL = "/dht/";
    public static final String SIGNUP_URL = "/signup/";
    public static final String ACTIVATION_URL = "/activation/";
    public static final String UI_URL = "/";
    public static final String UI_DIR = "ui/";

    public static final int THREADS = 200;
    public static final int CONNECTION_BACKLOG = 100;

    static {
        // disable weak algorithms
        System.out.println("\nInitial security properties:");
        System.out.println("jdk.tls.disabledAlgorithms: "+Security.getProperty("jdk.tls.disabledAlgorithms"));
        System.out.println("jdk.certpath.disabledAlgorithms: " + Security.getProperty("jdk.certpath.disabledAlgorithms"));
        System.out.println("jdk.tls.rejectClientInitializedRenegotiation: " + Security.getProperty("jdk.tls.rejectClientInitializedRenegotiation"));

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
    private final CoreNode coreNode;
    private HttpServer server;

    public UserService(InetSocketAddress local, Logger LOGGER, ContentAddressedStorage dht, CoreNode coreNode) throws IOException
    {
        this.LOGGER = LOGGER;
        this.local = local;
        this.coreNode = coreNode;
        init(dht);
    }

    public boolean init(ContentAddressedStorage dht) throws IOException {
        boolean isLocal = this.local.getHostName().contains("local");
        if (!isLocal)
            try {
                HttpServer httpServer = HttpServer.create();
                httpServer.createContext("/", new RedirectHandler("https://" + local.getHostName() + ":" + local.getPort() + "/"));
                httpServer.bind(new InetSocketAddress(InetAddress.getLocalHost(), 80), CONNECTION_BACKLOG);
                httpServer.start();
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Couldn't start http redirect to https for user server!");
            }
        System.out.println("Starting user API server at: " + local.getHostName() + ":" + local.getPort());

        if (isLocal) {
            System.out.println("Starting user server on localhost:"+local.getPort()+" only.");
            server = HttpServer.create(local, CONNECTION_BACKLOG);
        } else if (Args.hasArg("publicserver")) {
            System.out.println("Starting user server on all interfaces.");
            server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("::"), local.getPort()), CONNECTION_BACKLOG);
        } else
            server = HttpsServer.create(local, CONNECTION_BACKLOG);

        if (!isLocal) {
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");

                char[] password = "storage".toCharArray();
                KeyStore ks = SSL.getKeyStore(password);

                KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
                kmf.init(ks, password);

//            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
//            tmf.init(SSL.getTrustedKeyStore());

                // setup the HTTPS context and parameters
                sslContext.init(kmf.getKeyManagers(), null, null);
                // set up perfect forward secrecy
                sslContext.getSupportedSSLParameters().setCipherSuites(new String[]{
                        "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
                        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                        "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
                        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"});

                SSLContext.setDefault(sslContext);
                ((HttpsServer)server).setHttpsConfigurator(new HttpsConfigurator(sslContext) {
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
            catch (NoSuchAlgorithmException | InvalidKeyException | KeyStoreException | CertificateException |
                    NoSuchProviderException | SignatureException | OperatorCreationException |
                    UnrecoverableKeyException | KeyManagementException ex)
            {
                System.err.println("Failed to create HTTPS port");
                ex.printStackTrace(System.err);
                return false;
            }
        }

        server.createContext(DHT_URL, new DHTHandler(dht));
        server.createContext(SIGNUP_URL, new InverseProxyHandler("demo.peergos.net"));
        server.createContext(ACTIVATION_URL, new InverseProxyHandler("demo.peergos.net"));
        server.createContext(UI_URL, new StaticHandler(UI_DIR, false));
        server.createContext(HTTPCoreNodeServer.CORE_URL, new HTTPCoreNodeServer.CoreNodeHandler(coreNode));

        BTreeHandlers bTreeHandlers = new BTreeHandlers(coreNode, dht);

        bTreeHandlers.handlerMap()
                .entrySet()
                .stream()
                .forEach(e -> server.createContext(e.getKey(), e.getValue()));


        server.setExecutor(Executors.newFixedThreadPool(THREADS));
        server.start();

        return true;
    }
}
