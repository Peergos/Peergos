package peergos.server.corenode;
import java.util.logging.*;

import peergos.server.AggregatedMetrics;
import peergos.server.net.*;
import peergos.server.util.*;

import peergos.shared.cbor.*;
import peergos.shared.corenode.CoreNode;
import peergos.shared.corenode.CoreNodeUtils;
import peergos.shared.corenode.UserPublicKeyLink;

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

import com.sun.net.httpserver.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.mutable.*;
import peergos.shared.util.*;

public class HttpCoreNodeServer {
	private static final Logger LOG = Logging.LOG();

    private static final boolean LOGGING = true;
    private static final int CONNECTION_BACKLOG = 100;
    private static final int HANDLER_THREAD_COUNT = 100;

    public static class CoreNodeHandler implements HttpHandler
    {
        private final CoreNode coreNode;

        public CoreNodeHandler(CoreNode coreNode) {
            this.coreNode = coreNode;
        }

        public void handle(HttpExchange exchange) throws IOException 
        {
            long t1 = System.currentTimeMillis();
            DataInputStream din = new DataInputStream(exchange.getRequestBody());
            
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);
            
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/"))
                path = path.substring(1);
            String[] subComponents = path.substring(Constants.CORE_URL.length()).split("/");
            String method = subComponents[0];
//            LOG.info("core method "+ method +" from path "+ path);

            try {
                // only allow http POST requests
                if (! exchange.getRequestMethod().equals("POST")) {
                    exchange.sendResponseHeaders(405, 0);
                    return;
                }

                switch (method)
                {
                    case "getChain":
                        AggregatedMetrics.GET_PUBLIC_KEY_CHAIN.inc();
                        getChain(din, dout);
                        break;
                    case "updateChain":
                        AggregatedMetrics.UPDATE_PUBLIC_KEY_CHAIN.inc();
                        updateChain(din, dout);
                        break;
                    case "getPublicKey":
                        AggregatedMetrics.GET_PUBLIC_KEY.inc();
                        getPublicKey(din, dout);
                        break;
                    case "getUsername":
                        AggregatedMetrics.GET_USERNAME.inc();
                        getUsername(din, dout);
                        break;
                    case "getUsernamesGzip":
                        AggregatedMetrics.GET_ALL_USERNAMES.inc();
                        exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        getAllUsernamesGzip(subComponents.length > 1 ? subComponents[1] : "", din, dout);
                        break;
                    default:
                        throw new IOException("Unknown pkinode method!");
                }

                dout.flush();
                dout.close();
                byte[] b = bout.toByteArray();
                exchange.sendResponseHeaders(200, b.length);
                exchange.getResponseBody().write(b);
            } catch (Exception e) {
                HttpUtil.replyError(exchange, e);
            } finally {
                exchange.close();
                long t2 = System.currentTimeMillis();
                if (LOGGING)
                    LOG.info("Corenode server handled " + method + " request in: " + (t2 - t1) + " mS");
            }
        }

        void getChain(DataInputStream din, DataOutputStream dout) throws Exception
        {
            String username = CoreNodeUtils.deserializeString(din);

            List<UserPublicKeyLink> chain = coreNode.getChain(username).get();
            dout.write(new CborObject.CborList(chain).serialize());
        }

        void updateChain(DataInputStream din, DataOutputStream dout) throws Exception
        {
            String username = CoreNodeUtils.deserializeString(din);
            int count = din.readInt();
            List<UserPublicKeyLink> res = new ArrayList<>();
            for (int i=0; i < count; i++) {
                res.add(UserPublicKeyLink.fromCbor(CborObject.fromByteArray(Serialize.deserializeByteArray(din, UserPublicKeyLink.MAX_SIZE))));
            }
            boolean isAdded = coreNode.updateChain(username, res).get();

            dout.writeBoolean(isAdded);
        }

        void getPublicKey(DataInputStream din, DataOutputStream dout) throws Exception
        {
            String username = CoreNodeUtils.deserializeString(din);
            Optional<PublicKeyHash> k = coreNode.getPublicKeyHash(username).get();
            dout.writeBoolean(k.isPresent());
            if (!k.isPresent())
                return;
            byte[] b = k.get().serialize();
            dout.writeInt(b.length);
            dout.write(b);
        }

        void getUsername(DataInputStream din, DataOutputStream dout) throws Exception
        {
            byte[] publicKey = CoreNodeUtils.deserializeByteArray(din);
            String k = coreNode.getUsername(PublicKeyHash.fromCbor(CborObject.fromByteArray(publicKey))).get();
            if (k == null)
                k="";
            Serialize.serialize(k, dout);
        }

        void getAllUsernamesGzip(String prefix, DataInputStream din, DataOutputStream dout) throws Exception
        {
            List<String> res = coreNode.getUsernames(prefix).get();
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            GZIPOutputStream gout = new GZIPOutputStream(bout);
            gout.write(JSONParser.toString(res).getBytes());
            gout.flush();
            gout.close();
            dout.write(bout.toByteArray());
        }

        public void close() throws IOException{
            coreNode.close();
        }
    }

    private final HttpServer server;
    private final InetSocketAddress address; 
    private final CoreNodeHandler ch;

    public HttpCoreNodeServer(CoreNode coreNode, MutablePointers mutable, InetSocketAddress address) throws IOException
    {

        this.address = address;
        if (address.getHostName().toLowerCase().contains("local"))
            server = HttpServer.create(address, CONNECTION_BACKLOG);
        else
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLocalHost(), address.getPort()), CONNECTION_BACKLOG);
        ch = new CoreNodeHandler(coreNode);
        server.createContext("/" + Constants.CORE_URL, ch);
        server.createContext("/" + Constants.MUTABLE_POINTERS_URL, new MutationHandler(mutable));
        server.setExecutor(Executors.newFixedThreadPool(HANDLER_THREAD_COUNT));
    }

    public void start() throws IOException
    {
        server.start();
    }
    
    public InetSocketAddress getAddress(){return address;}

    public void close() throws IOException
    {   
        server.stop(5);
        ch.close();
    }


    public static void createAndStart(int port, CoreNode coreNode, MutablePointers mutable, Args args)
    {
        try {
            String hostname = args.getArg("domain", "localhost");
            LOG.info("Starting core node server listening on: " + hostname+":"+port +" proxying to "+coreNode);
            InetSocketAddress address = new InetSocketAddress(hostname, port);
            HttpCoreNodeServer server = new HttpCoreNodeServer(coreNode, mutable, address);
            server.start();
        } catch (Exception e)
        {
            LOG.log(Level.WARNING, e.getMessage(), e);
            LOG.info("Couldn't start Corenode server!");
        }
    }
}
