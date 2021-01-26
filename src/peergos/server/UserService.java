package peergos.server;
import java.util.*;
import java.util.function.*;
import java.util.logging.Logger;

import peergos.server.storage.*;
import peergos.server.messages.*;
import peergos.server.storage.admin.*;
import peergos.server.util.Logging;
import java.util.logging.Level;

import com.sun.net.httpserver.*;
import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;

import peergos.server.net.*;
import peergos.shared.storage.controller.*;
import peergos.shared.util.*;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.concurrent.*;

public class UserService {
	private static final Logger LOG = Logging.LOG();

    public static final Version CURRENT_VERSION = Version.parse("0.4.0");
    public static final String UI_URL = "/";

    private static void initTLS() {
        // disable weak algorithms
        LOG.info("\nInitial security properties:");
        printSecurityProperties();

        // The ECDH and RSA key exchange algorithms are disabled because they don't provide forward secrecy
        Security.setProperty("jdk.tls.disabledAlgorithms",
                "SSLv3, TLSv1.3, RC4, MD2, MD4, MD5, SHA1, DES, DSA, MD5withRSA, DH, RSA keySize < 2048, EC keySize < 224, 3DES_EDE_CBC, " +
                "TLS_RSA_WITH_NULL_SHA256," +
                "TLS_RSA_WITH_AES_128_GCM_SHA256," +
                "TLS_RSA_WITH_AES_128_CBC_SHA256, " +
                "TLS_RSA_WITH_AES_256_GCM_SHA384, " +
                "TLS_RSA_WITH_AES_256_CBC_SHA256, " +
                "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256, " +
                "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256, " +
                "TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256, " +
                "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384," +
                "TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384," +
                "TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256, " +
                "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384," +
                "TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384," +
                "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256," +
                "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384," +
                "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256," +
                "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384,"
        );
        Security.setProperty("jdk.certpath.disabledAlgorithms",
                "RC4, MD2, MD4, MD5, SHA1, DSA, RSA keySize < 2048, EC keySize < 224");
        Security.setProperty("jdk.tls.rejectClientInitializedRenegotiation", "true");

        LOG.info("\nUpdated security properties:");
        printSecurityProperties();

        Security.setProperty("jdk.tls.ephemeralDHKeySize", "2048");
    }

    static void printSecurityProperties() {
        LOG.info("jdk.tls.disabledAlgorithms: " + Security.getProperty("jdk.tls.disabledAlgorithms"));
        LOG.info("jdk.certpath.disabledAlgorithms: " + Security.getProperty("jdk.certpath.disabledAlgorithms"));
        LOG.info("jdk.tls.rejectClientInitializedRenegotiation: "+Security.getProperty("jdk.tls.rejectClientInitializedRenegotiation"));
    }

    public final ContentAddressedStorage storage;
    public final Crypto crypto;
    public final CoreNode coreNode;
    public final SocialNetwork social;
    public final MutablePointers mutable;
    public final InstanceAdmin controller;
    public final SpaceUsage usage;
    public final ServerMessageStore serverMessages;
    public final GarbageCollector gc; // not exposed

    public UserService(ContentAddressedStorage storage,
                       Crypto crypto,
                       CoreNode coreNode,
                       SocialNetwork social,
                       MutablePointers mutable,
                       InstanceAdmin controller,
                       SpaceUsage usage,
                       ServerMessageStore serverMessages,
                       GarbageCollector gc) {
        this.storage = new CachingStorage(storage, 1000, 50 * 1024);
        this.crypto = crypto;
        this.coreNode = coreNode;
        this.social = social;
        this.mutable = mutable;
        this.controller = controller;
        this.usage = usage;
        this.serverMessages = serverMessages;
        this.gc = gc;
    }

    public static class TlsProperties {
        public final String hostname, keyfilePassword;

        public TlsProperties(String hostname, String keyfilePassword) {
            this.hostname = hostname;
            this.keyfilePassword = keyfilePassword;
        }
    }

    public boolean initAndStart(InetSocketAddress local,
                                Optional<TlsProperties> tlsProps,
                                Optional<String> basicAuth,
                                Optional<Path> webroot,
                                boolean useWebCache,
                                boolean isPublicServer,
                                int connectionBacklog,
                                int handlerPoolSize,
                                boolean enableCors) throws IOException {
        InetAddress allInterfaces = InetAddress.getByName("::");
        if (tlsProps.isPresent())
            try {
                HttpServer httpServer = HttpServer.create();
                httpServer.createContext("/", new RedirectHandler("https://" + tlsProps.get().hostname + ":443/"));
                httpServer.bind(new InetSocketAddress(allInterfaces, 80), connectionBacklog);
                httpServer.start();
                initTLS();
            } catch (Exception e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
                LOG.info("Couldn't start http redirect to https for user server!");
            }

        LOG.info("Starting local Peergos server at: localhost:"+local.getPort());
        if (tlsProps.isPresent())
            LOG.info("Starting Peergos TLS server on all interfaces.");
        HttpServer localhostServer = HttpServer.create(local, connectionBacklog);
        HttpsServer tlsServer = ! tlsProps.isPresent() ? null :
                HttpsServer.create(new InetSocketAddress(allInterfaces, 443), connectionBacklog);

        if (tlsProps.isPresent()) {
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");

                char[] password = tlsProps.get().keyfilePassword.toCharArray();
                KeyStore ks = getKeyStore("storage.p12", password);

                KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
                kmf.init(ks, password);

//            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
//            tmf.init(SSL.getTrustedKeyStore());

                // setup the HTTPS context and parameters
                sslContext.init(kmf.getKeyManagers(), null, null);
                sslContext.getSupportedSSLParameters().setUseCipherSuitesOrder(true);
                // set up perfect forward secrecy
                sslContext.getSupportedSSLParameters().setCipherSuites(new String[]{
                        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                        "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
                        "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384"
                });

                SSLContext.setDefault(sslContext);
                tlsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
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
                            LOG.severe("Failed to create HTTPS port");
                            ex.printStackTrace(System.err);
                        }
                    }
                });
            }
            catch (NoSuchAlgorithmException | InvalidKeyException | KeyStoreException | CertificateException |
                    NoSuchProviderException | SignatureException |
                    UnrecoverableKeyException | KeyManagementException ex)
            {
                LOG.severe("Failed to load TLS settings");
                throw new RuntimeException(ex);
            }
        }

        //define web-root static-handler
        if (webroot.isPresent())
            LOG.info("Using webroot from local file system: " + webroot);
        else
            LOG.info("Using webroot from jar");
        StaticHandler handler = webroot.map(p -> (StaticHandler) new FileHandler(p, true))
                .orElseGet(() -> new JarHandler(true, Paths.get("/webroot")));

        if (useWebCache) {
            LOG.info("Caching web-resources");
            handler = handler.withCache();
        }

        BiConsumer<String, HttpHandler> addHandler = (path, handlerFunc) -> {
            if (basicAuth.isPresent())
                localhostServer.createContext(path, new BasicAuthHandler(basicAuth.get(), handlerFunc));
            else
                localhostServer.createContext(path, handlerFunc);
            if (tlsServer != null) {
                if (basicAuth.isPresent())
                    tlsServer.createContext(path, new HSTSHandler(new BasicAuthHandler(basicAuth.get(), handlerFunc)));
                else
                    tlsServer.createContext(path, new HSTSHandler(handlerFunc));
            }
        };

        addHandler.accept(Constants.DHT_URL, new DHTHandler(storage, crypto.hasher, (h, i) -> true, isPublicServer));
        addHandler.accept("/" + Constants.CORE_URL,
                new CoreNodeHandler(this.coreNode, isPublicServer, enableCors));
        addHandler.accept("/" + Constants.SOCIAL_URL,
                new SocialHandler(this.social, isPublicServer, enableCors));
        addHandler.accept("/" + Constants.MUTABLE_POINTERS_URL,
                new MutationHandler(this.mutable, isPublicServer, enableCors));
        addHandler.accept("/" + Constants.ADMIN_URL,
                new AdminHandler(this.controller, isPublicServer, enableCors));
        addHandler.accept("/" + Constants.SPACE_USAGE_URL,
                new SpaceHandler(this.usage, isPublicServer, enableCors));
        addHandler.accept("/" + Constants.SERVER_MESSAGE_URL,
                new ServerMessageHandler(this.serverMessages, coreNode, storage, isPublicServer, enableCors));
        addHandler.accept("/" + Constants.PUBLIC_FILES_URL, new PublicFileHandler(crypto.hasher, coreNode, mutable, storage));
        addHandler.accept(UI_URL, handler);

        localhostServer.setExecutor(Executors.newFixedThreadPool(handlerPoolSize));
        localhostServer.start();

        if (tlsServer != null) {
            tlsServer.setExecutor(Executors.newFixedThreadPool(handlerPoolSize));
            tlsServer.start();
        }

        return true;
    }

    public static KeyStore getKeyStore(String filename, char[] password)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, InvalidKeyException,
            NoSuchProviderException, SignatureException
    {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        if (new File(filename).exists())
        {
            ks.load(new FileInputStream(filename), password);
            return ks;
        }

        throw new IllegalStateException("SSL keystore file doesn't exist: "+filename);
    }
}
