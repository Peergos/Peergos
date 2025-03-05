package peergos.server.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import peergos.server.util.HttpUtil;
import peergos.server.util.Logging;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.corenode.CoreNode;
import peergos.shared.mutable.MutablePointers;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.EntryPoint;
import peergos.shared.user.fs.AbsoluteCapability;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.Chunk;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.Constants;

import java.io.OutputStream;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AndroidFileReflector implements HttpHandler {
	private static final Logger LOG = Logging.LOG();

    private static final boolean LOGGING = true;

    private final Crypto crypto;
    private final CoreNode core;
    private final MutablePointers mutable;
    private final ContentAddressedStorage dht;

    public AndroidFileReflector(Crypto crypto, CoreNode core, MutablePointers mutable, ContentAddressedStorage dht) {
        this.crypto = crypto;
        this.core = core;
        this.mutable = mutable;
        this.dht = dht;
    }

    @Override
    public void handle(HttpExchange httpExchange) {
        System.out.println("Reflector req: " + httpExchange.getRequestMethod() + ", headers: " + httpExchange.getRequestHeaders().entrySet());
        long t1 = System.currentTimeMillis();
        String path = httpExchange.getRequestURI().getPath();
        try {
            String host = httpExchange.getRequestHeaders().get("Host").get(0);
            if (! host.startsWith("localhost:")) {
                httpExchange.sendResponseHeaders(404, 0);
                httpExchange.close();
                return;
            }
            if (path.startsWith("/"))
                path = path.substring(1);
            String rest = path.substring(Constants.ANDROID_FILE_REFLECTOR.length());
            String action = rest.split("/")[0];
            if (action.equals("file")) {
                String link = rest.substring("file".length() + 1);

                AbsoluteCapability cap = AbsoluteCapability.fromLink(link);
                NetworkAccess network = NetworkAccess.buildPublicNetworkAccess(crypto.hasher, core, mutable, dht).join();
                Optional<FileWrapper> file = network.retrieveAll(List.of(new EntryPoint(cap, ""))).join().stream().findFirst();
                System.out.println("Android reflector got file in " + (System.currentTimeMillis() - t1) + "ms");
                if (file.isEmpty()) {
                    httpExchange.sendResponseHeaders(404, 0);
                    httpExchange.close();
                    return;
                }
                long fileSize = file.get().getSize();
//                AsyncReader reader = file.get().getBufferedInputStream(network, crypto, (int)(fileSize >> 32), (int)fileSize, 10, x -> {}).join();
                AsyncReader reader = file.get().getInputStream(network, crypto, fileSize, x -> {}).join();
                System.out.println("Android reflector got reader");
                OutputStream resp = httpExchange.getResponseBody();
                httpExchange.sendResponseHeaders(200, fileSize);
                byte[] buf = new byte[5 * 1024 * 1024];
                for (long offset = 0; offset < fileSize; ) {
                    int read = reader.readIntoArray(buf, 0, (int) Math.min(Chunk.MAX_SIZE, fileSize - offset)).join();
                    offset += read;
                    resp.write(buf, 0, read);
                    resp.flush();
                    System.out.println("Android reflector wrote " + read);
                }
                httpExchange.close();
            } else {
                LOG.info("Unknown reflector handler: " +httpExchange.getRequestURI());
                httpExchange.sendResponseHeaders(404, 0);
                httpExchange.close();
            }
        } catch (Exception e) {
            LOG.severe("Error handling " +httpExchange.getRequestURI());
            LOG.log(Level.WARNING, e.getMessage(), e);
            HttpUtil.replyError(httpExchange, e);
        } finally {
            httpExchange.close();
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                LOG.info("File reflector Handler returned file in: " + (t2 - t1) + " mS");
        }
    }
}
