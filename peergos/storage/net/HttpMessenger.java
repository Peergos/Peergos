package peergos.storage.net;

import com.sun.net.httpserver.HttpServer;
import peergos.storage.Storage;
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
    public static final String USER_URL = "/user/";

    public static final int THREADS = 100;
    public static final int CONNECTION_BACKLOG = 100;

    private final Logger LOGGER;
    private final int localPort;
    HttpServer httpServer;
    private final Storage fragments;

    public HttpMessenger(int port, Storage fragments, Logger LOGGER, Router router) throws IOException
    {
        this.LOGGER = LOGGER;
        this.localPort = port;
        this.fragments = fragments;
        init(router);
    }

    public boolean init(Router router) throws IOException {
        InetAddress us = IP.getMyPublicAddress();
        InetSocketAddress address = new InetSocketAddress(us, localPort);
        System.out.println("Starting storage server messenger at: " + us.getHostAddress() + ":" + localPort);
        httpServer = HttpServer.create(address, CONNECTION_BACKLOG);

        httpServer.createContext(MESSAGE_URL, new HttpMessageHandler(router));
        httpServer.createContext(USER_URL, new HttpUserAPIHandler(router));
        httpServer.createContext("/", new StoragePutHandler(fragments, "/"));
        httpServer.setExecutor(Executors.newFixedThreadPool(THREADS));
        httpServer.start();

        return true;
    }

    public void sendLetter(Letter p) {
        if (p.dest != null)
            try {
                sendMessage(p.m, p.dest, p.destPort + 1);
            } catch (IOException e) {
                LOGGER.log(Level.ALL, "Error sending letter", e);
            }
    }

    // need to think about latency of opening all these SSL connections,
    // maybe we could keep open connections to neighbours, and only open to distant nodes in the DHT?
    public void sendMessage(Message m, InetAddress addr, int port) throws IOException
    {
        if (Message.LOG)
            LOGGER.log(Level.ALL, String.format("Sent %s with target %d to %s:%d\n", m.name(), m.getTarget(), addr, port));
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        m.write(new DataOutputStream(bout));
        URL target = new URL("http", addr.getHostAddress(), port, MESSAGE_URL);
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
        URL target = new URL("http", addr.getHostAddress(), port, key);
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
        URL target = new URL("http", addr.getHostAddress(), port, key+"/");
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
}
