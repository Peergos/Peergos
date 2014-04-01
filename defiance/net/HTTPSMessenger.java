package defiance.net;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import defiance.crypto.SSL;
import defiance.dht.Message;
import defiance.dht.Messenger;
import defiance.storage.Storage;
import org.bouncycastle.operator.OperatorCreationException;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HTTPSMessenger extends Messenger
{
    public static final String MESSAGE_URL = "/message";

    public static final int THREADS = 5;
    public static final int CONNECTION_BACKLOG = 100;

    private final Logger LOGGER;
    private final int localPort;
    HttpsServer httpsServer;
    private final BlockingQueue<Message> queue = new LinkedBlockingDeque();
    private final Storage keys;
    private final Storage fragments;

    public HTTPSMessenger(int port, Storage fragments, Storage keys, Logger LOGGER) throws IOException
    {
        this.LOGGER = LOGGER;
        this.localPort = port;
        this.keys = keys;
        this.fragments = fragments;
    }

    @Override
    public boolean join(InetAddress addr, int port) throws IOException {
        try
        {
            InetAddress us = IP.getMyPublicAddress();
            InetSocketAddress address = new InetSocketAddress(us, localPort);
            System.out.println("Starting storage server at: " + us.getHostAddress() + ":" + localPort);
            httpsServer = HttpsServer.create(address, CONNECTION_BACKLOG);
            SSLContext sslContext = SSLContext.getInstance("TLS");

            char[] password = "storage".toCharArray();
            KeyStore ks = SSL.getKeyStore(password);

            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, password);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
            tmf.init(ks);

            // setup the HTTPS context and parameters
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            SSLContext.setDefault(sslContext);
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
        }
        catch (NoSuchAlgorithmException|InvalidKeyException|KeyStoreException|CertificateException|
                NoSuchProviderException|SignatureException|OperatorCreationException|
                UnrecoverableKeyException|KeyManagementException ex)
        {
            System.err.println("Failed to create HTTPS port");
            ex.printStackTrace(System.err);
            return false;
        }

        httpsServer.createContext(MESSAGE_URL, new HttpsMessageHandler(this));
        httpsServer.createContext("/key/", new StorageGetHandler(keys, "/key/"));
        httpsServer.createContext("/", new StoragePutHandler(fragments, "/"));
        httpsServer.setExecutor(Executors.newFixedThreadPool(THREADS));
        httpsServer.start();

        // if we are the first node don't contact network
        if (addr == null)
        {

        }
        else // contact network and accept SSL cert from the contact point
        {

        }
        return true;
    }

    protected void queueRequestMessage(Message m)
    {
        queue.add(m);
    }

    @Override
    public void sendMessage(Message m, InetAddress addr, int port) throws IOException
    {
        if (Message.LOG)
            LOGGER.log(Level.ALL, String.format("Sent %s with target %d to %s:%d\n", m.name(), m.getTarget(), addr, port));
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        m.write(new DataOutputStream(bout));
        System.out.println("sending message to "+addr.getHostAddress() + ":"+port);
        URL target = new URL("https", addr.getHostAddress(), port, MESSAGE_URL);
        URLConnection conn = target.openConnection();
        conn.setDoOutput(true);
        OutputStream out = conn.getOutputStream();
        out.write(bout.toByteArray());
        out.flush();
        out.close();
    }

    @Override
    public byte[] getFragment(InetAddress addr, int port, String key) throws IOException
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

    @Override
    public void putFragment(InetAddress addr, int port, String key, byte[] value) throws IOException
    {
        // for now, make a direct connection
        URL target = new URL("https", addr.getHostAddress(), port, key);
        System.out.println("sending fragment to " + target.toString());
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("PUT");
        OutputStream out = conn.getOutputStream();
        out.write(value);
        out.flush();
        out.close();
    }

    @Override
    public Message awaitMessage(int duration) throws IOException, InterruptedException
    {
        return queue.poll(duration, TimeUnit.MILLISECONDS);
    }
}
