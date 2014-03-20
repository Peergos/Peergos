package defiance.net;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import defiance.dht.Message;
import defiance.dht.Messenger;
import defiance.storage.Storage;
import sun.security.x509.*;

import javax.net.ssl.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.security.cert.Certificate;
import java.util.Date;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HTTPSMessenger extends Messenger
{
    public static final String AUTH = "RSA";
    public static final int RSA_KEY_SIZE = 4096;
    public static final int THREADS = 5;
    public static final int CONNECTION_BACKLOG = 100;

    private final Logger LOGGER;
    private final int localPort;
    HttpsServer httpsServer;
    private final BlockingQueue<Message> queue = new LinkedBlockingDeque();
    private final Storage keyStorage;

    public HTTPSMessenger(int port, Storage keyStorage, Logger LOGGER) throws IOException
    {
        this.LOGGER = LOGGER;
        this.localPort = port;
        this.keyStorage = keyStorage;
    }

    @Override
    public void join(InetAddress addr, int port) throws IOException {
        try
        {
            InetSocketAddress address = new InetSocketAddress(InetAddress.getLocalHost(), localPort);

            httpsServer = HttpsServer.create(address, CONNECTION_BACKLOG);
            SSLContext sslContext = SSLContext.getInstance ("TLS");

            char[] password = "simulator".toCharArray();
            KeyStore ks = getKeyStore(password);

            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, password);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
            tmf.init(ks);

            // setup the HTTPS context and parameters
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext)
            {
                public void configure(HttpsParameters params)
                {
                    try
                    {
                        // initialise the SSL context
                        SSLContext c = SSLContext.getDefault();
                        SSLEngine engine = c.createSSLEngine();
                        params.setNeedClientAuth(false);
                        params.setCipherSuites(engine.getEnabledCipherSuites());
                        params.setProtocols(engine.getEnabledProtocols());

                        // get the default parameters
                        SSLParameters defaultSSLParameters = c.getDefaultSSLParameters();
                        params.setSSLParameters(defaultSSLParameters);
                    }
                    catch (Exception ex)
                    {
                        System.err.println("Failed to create HTTPS port");
                        ex.printStackTrace(System.err);
                    }
                }
            } );
            // now contact network and accept SSL cert from the contact point

        }
        catch (Exception ex)
        {
            System.err.println("Failed to create HTTPS port");
            ex.printStackTrace(System.err);
        }

        httpsServer.createContext("/", new HttpsMessageHandler(this));
        httpsServer.createContext("/key/", new StorageGetHandler(keyStorage, "/key/"));
        httpsServer.setExecutor(Executors.newFixedThreadPool(THREADS));
        httpsServer.start();

        // if we are the first node don't contact network
        if (addr == null)
        {

        }
        else // contact network and accept SSL cert from the contact point
        {

        }
    }

    protected void queueRequestMessage(Message m)
    {
        queue.add(m);
    }

    private KeyStore getKeyStore(char[] password)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, InvalidKeyException, NoSuchProviderException, SignatureException
    {
        // never store certificates to disk, if program is restarted, we generate a new certificate and rejoin network
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, password);
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(AUTH);
        kpg.initialize(RSA_KEY_SIZE);
        KeyPair keypair = kpg.generateKeyPair();
        PrivateKey myPrivateKey = keypair.getPrivate();

        X509CertInfo info = new X509CertInfo();
        Date from = new Date();
        Date to = new Date(from.getTime() + 365 * 86400000l);
        CertificateValidity interval = new CertificateValidity(from, to);
        info.set(X509CertInfo.VALIDITY, interval);
        BigInteger sn = new BigInteger(64, new SecureRandom());
        info.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(sn));

        X500Name owner = new X500Name("EMAIL=hello.NSA.GCHQ.ASIO@goodluck.com");
        info.set(X509CertInfo.SUBJECT, new CertificateSubjectName(owner));
        info.set(X509CertInfo.ISSUER, new CertificateIssuerName(owner));
        info.set(X509CertInfo.KEY, new CertificateX509Key(keypair.getPublic()));
        info.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3));
        AlgorithmId algo = new AlgorithmId(AlgorithmId.SHA512_oid);
        info.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algo));

        // Sign the cert to identify the algorithm that's used.
        X509CertImpl cert = new X509CertImpl(info);
        cert.sign(myPrivateKey, "MD5WithRSA");

        ks.setKeyEntry("private", myPrivateKey, password, new Certificate[]{cert});
        ks.store(new FileOutputStream("sslkeystore"), password);
        return ks;
    }

    @Override
    public void sendMessage(Message m, InetAddress addr, int port) throws IOException
    {
        if (Message.LOG)
            LOGGER.log(Level.ALL, String.format("Sent %s with target %d to %s:%d\n", m.name(), m.getTarget(), addr, port));
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        m.write(new DataOutputStream(bout));
        URL target = new URL("https", addr.getCanonicalHostName(), port, "");
        URLConnection conn = target.openConnection();
        OutputStream out = conn.getOutputStream();
        out.write(bout.toByteArray());
        out.flush();
        out.close();
    }

    @Override
    public Message awaitMessage(int duration) throws IOException, InterruptedException
    {
        return queue.poll(duration, TimeUnit.MILLISECONDS);
    }
}
