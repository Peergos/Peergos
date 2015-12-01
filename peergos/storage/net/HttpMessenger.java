package peergos.storage.net;

import com.sun.net.httpserver.HttpServer;
import peergos.storage.*;
import peergos.storage.dht.Letter;
import peergos.storage.dht.Message;
import peergos.storage.dht.Router;

import java.io.*;
import java.net.*;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HttpMessenger
{
    public static final String MESSAGE_URL = "/message/";

    public static final int THREADS = 2;
    public static final int CONNECTION_BACKLOG = 100;

    private final Logger LOGGER;
    private final InetSocketAddress local;
    HttpServer httpServer;
    private final StorageWrapper fragments;

    public HttpMessenger(InetSocketAddress local, StorageWrapper fragments, Logger LOGGER, Router router) throws IOException
    {
        this.LOGGER = LOGGER;
        this.local = local;
        this.fragments = fragments;
        init(router);
    }

    public boolean init(Router router) throws IOException {
        System.out.println("Starting storage server messenger listening at: " + local.getHostName() + ":" + local.getPort());
        if (local.getHostName().contains("local"))
            httpServer = HttpServer.create(local, CONNECTION_BACKLOG);
        else
            httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLocalHost(), local.getPort()), CONNECTION_BACKLOG);
        httpServer.createContext(MESSAGE_URL, new HttpMessageHandler(router));
        httpServer.createContext(HttpsUserService.DHT_URL, new DHTAPIHandler(router));
        httpServer.createContext("/", new StoragePutHandler(fragments, "/"));
        httpServer.setExecutor(Executors.newFixedThreadPool(THREADS));
        httpServer.start();

        return true;
    }

    public void sendLetter(Letter p) {
        if (p.dest != null)
            try {
                sendMessage(p.m, p.dest);
            } catch (IOException e) {
                LOGGER.log(Level.ALL, "Error sending letter", e);
            }
    }

    // need to think about latency of opening all these SSL connections,
    // maybe we could keep open connections to neighbours, and only open to distant nodes in the DHT?
    public void sendMessage(Message m, InetSocketAddress addr) throws IOException
    {
        if (Message.LOG)
            LOGGER.log(Level.ALL, String.format("Sent %s with target %d to %s:%d\n", m.name(), m.getTarget(), addr.getHostName(), addr.getPort()));
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        m.write(new DataOutputStream(bout));
        URL target = new URL("http", addr.getAddress().getHostName(), addr.getPort(), MESSAGE_URL);
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
        LOGGER.log(Level.ALL, String.format("Finished sending %s with target %d to %s:%d\n", m.name(), m.getTarget(), addr.getHostName(), addr.getPort()));
    }

    public static byte[] getFragment(InetSocketAddress addr, String key) throws IOException
    {
        // for now, make a direct connection
        URL target = new URL("http", addr.getHostName(), addr.getPort(), key);
        System.out.println("getting fragment "+key+" from " + addr);
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

    public static void putFragment(InetSocketAddress addr, String key, byte[] value) throws IOException
    {
        // for now, make a direct connection
        URL target = new URL("http", addr.getHostName(), addr.getPort(), key+"/");
        System.out.println("sending fragment "+key+" to " + target.toString());
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("PUT");
        OutputStream out = conn.getOutputStream();
        out.write(value);
        out.flush();
        out.close();
        conn.getResponseCode();
    }
}
