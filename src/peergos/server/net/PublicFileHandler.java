package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.util.Logging;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;

public class PublicFileHandler implements HttpHandler {
	private static final Logger LOG = Logging.LOG();

    private static final boolean LOGGING = true;

    private final CoreNode core;
    private final MutablePointers mutable;
    private final ContentAddressedStorage dht;
    private final NetworkAccess network;
    private static final String PATH_PREFIX = "/public/";

    public PublicFileHandler(CoreNode core, MutablePointers mutable, ContentAddressedStorage dht) {
        this.core = core;
        this.mutable = mutable;
        this.dht = dht;
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, dht);
        MutableTree mutableTree = new MutableTreeImpl(mutable, dht, synchronizer);
        this.network = new NetworkAccess(core, null, dht, mutable, mutableTree, synchronizer, null, Collections.emptyList());
    }

    @Override
    public void handle(HttpExchange httpExchange) {
        long t1 = System.currentTimeMillis();
        String path = httpExchange.getRequestURI().getPath();
        try {
            if (! path.startsWith(PATH_PREFIX))
                throw new IllegalStateException("Public file urls must start with /public/");
            path = path.substring(PATH_PREFIX.length());
            String originalPath = path;

            String ownerName = path.substring(0, path.indexOf("/"));

            Optional<PublicKeyHash> ownerOpt = core.getPublicKeyHash(ownerName).get();
            if (! ownerOpt.isPresent())
                throw new IllegalStateException("Owner doesn't exist for path " + path);
            PublicKeyHash owner = ownerOpt.get();
            CommittedWriterData userData = WriterData.getWriterData(owner, owner, mutable, dht).get();
            Optional<Multihash> publicData = userData.props.publicData;
            if (! publicData.isPresent())
                throw new IllegalStateException("User " + ownerName + " has not made any files public.");

            Function<ByteArrayWrapper, byte[]> hasher = x -> Hash.sha256(x.data);
            ChampWrapper champ = ChampWrapper.create(publicData.get(), hasher, dht).get();

            MaybeMultihash capHash = champ.get(("/" + path).getBytes()).get();
            // The user might have published an ancestor directory of the requested path, so drop path elements until we
            // either find a capability, or have none left
            String subPath = "";
            while (! capHash.isPresent() && path.length() > 0) {
                String lastElement = path.substring(path.lastIndexOf("/"));
                subPath = lastElement + subPath;
                path = path.substring(0, path.length() - lastElement.length());
                capHash = champ.get(("/" + path).getBytes()).get();
            }
            if (! capHash.isPresent())
                throw new IllegalStateException("User " + ownerName + " has not published a file at " + originalPath);

            Optional<CborObject> capCbor = dht.get(capHash.get()).get();
            AbsoluteCapability cap = AbsoluteCapability.fromCbor(capCbor.get());

            TrieNodeImpl trieRoot = TrieNodeImpl.empty().put(path, new EntryPoint(cap, ownerName));
            Optional<FileWrapper> fileOpt = trieRoot.getByPath(originalPath, network).get();

            if (! fileOpt.isPresent())
                throw new IllegalStateException("Couldn't retrieve file: " + path);

            FileWrapper file = fileOpt.get();

            if (file.isDirectory()) {
                String fullPath = httpExchange.getRequestURI().getPath();
                String canonicalFullPath = fullPath + (! fullPath.endsWith("/") ? "/" : "");
                Set<FileWrapper> children = file.getChildren(network).get();
                StringBuilder resp = new StringBuilder();
                resp.append("<!DOCTYPE html><html lang=\"en\">");
                resp.append("<body>");
                resp.append("<h1>Contents of directory " + originalPath + "</h1>");
                children.forEach(child -> resp.append("<a href=\""+canonicalFullPath + child.getName()+"\">" + child.getName() + "</a><br/>"));
                resp.append("</body>");
                resp.append("</html>");

                byte[] body = resp.toString().getBytes();
                httpExchange.sendResponseHeaders(200, body.length);
                OutputStream out = httpExchange.getResponseBody();
                out.write(body);
                out.close();
            } else {
                long fileSize = file.getSize();
                httpExchange.sendResponseHeaders(200, fileSize);
                AsyncReader reader = file.getInputStream(network, null, x -> {}).get();
                byte[] buf = new byte[(int) Math.min(fileSize, 5 * 1024 * 1024)];
                long read = 0;
                OutputStream out = httpExchange.getResponseBody();
                while (read < fileSize) {
                    int r = reader.readIntoArray(buf, 0, (int) Math.min(buf.length, fileSize - read)).get();
                    out.write(buf, 0, r);
                    read += r;
                }
                out.close();
            }
        } catch (Exception e) {
            LOG.severe("Error handling " +httpExchange.getRequestURI());
            LOG.log(Level.WARNING, e.getMessage(), e);
            replyError(httpExchange, e);
        } finally {
            httpExchange.close();
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                LOG.info("Public file Handler returned " + path + " query in: " + (t2 - t1) + " mS");
        }
    }

    private static void replyError(HttpExchange exchange, Throwable t) {
        try {
            exchange.getResponseHeaders().set("Trailer", t.getMessage());
            exchange.sendResponseHeaders(400, -1);
        } catch (IOException e)
        {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
    }
}
