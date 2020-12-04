package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.util.Logging;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

public class GatewayHandler implements HttpHandler {
	private static final Logger LOG = Logging.LOG();
	private static final boolean LOGGING = true;
	private static final int MAX_ASSET_SIZE_CACHE = 200*1024;

    private final String domainSuffix;
    private final NetworkAccess network;
    private final Crypto crypto;
    private final LRUCache<String, WebRootEntry> webRootCache;
    private final LRUCache<String, Asset> assetCache;

    public GatewayHandler(String domainSuffix, Crypto crypto, NetworkAccess network) {
        this.domainSuffix = domainSuffix;
        this.crypto = crypto;
        this.network = network;
        this.webRootCache = new LRUCache<>(1000);
        this.assetCache = new LRUCache<>(1000);
    }

    private final class WebRootEntry {
        public final FileWrapper field;
        public final FileWrapper webRoot;

        public WebRootEntry(FileWrapper field, FileWrapper webRoot) {
            this.field = field;
            this.webRoot = webRoot;
        }
    }

    private final class Asset {
        public final FileWrapper source;
        public final byte[] data;

        public Asset(FileWrapper source, byte[] data) {
            this.source = source;
            this.data = data;
        }
    }

    private synchronized WebRootEntry lookupRoot(String owner) {
        return webRootCache.get(owner);
    }

    private synchronized void cacheRoot(String owner, WebRootEntry webRoot) {
        webRootCache.put(owner, webRoot);
    }

    private synchronized Asset lookupAsset(String owner, String path) {
        return assetCache.get(owner + "/" + path);
    }

    private synchronized void cacheAsset(String owner, String path, Asset asset) {
        assetCache.put(owner + "/" + path, asset);
    }

    private synchronized void invalidateAssets(String owner) {
        assetCache.entrySet().removeIf(entry -> entry.getKey().startsWith(owner + "/"));
    }

    @Override
    public void handle(HttpExchange httpExchange) {
        long t1 = System.currentTimeMillis();
        String path = httpExchange.getRequestURI().getPath();
        try {
            path = path.substring(1).replaceAll("//", "/");
            if (path.length() == 0)
                path = "index.html";

            String domain = httpExchange.getRequestHeaders().getFirst("Host");
            if (! domain.endsWith(domainSuffix))
                throw new IllegalStateException("Incorrect domain! " + domain);
            String owner = domain.substring(0, domain.length() - domainSuffix.length());

            WebRootEntry webRootEntry = lookupRoot(owner);
            if (webRootEntry != null) {
                FileWrapper updatedField = webRootEntry.field.getUpdated(network).join();
                if (!updatedField.version.equals(webRootEntry.field.version)) {
                    webRootEntry = new WebRootEntry(updatedField, null);
                    invalidateAssets(owner);
                }
            } else {
                Path toProfileEntry = Paths.get(owner).resolve(".profile").resolve("webroot");
                AbsoluteCapability capToWebRootField = UserContext.getPublicCapability(toProfileEntry, network).join();
                FileWrapper webRootField = network.getFile(capToWebRootField, owner).join().get();
                webRootEntry = new WebRootEntry(webRootField, null);
            }

            if (webRootEntry.webRoot == null) {
                Path toWebRoot = Paths.get(new String(Serialize.readFully(webRootEntry.field, crypto, network).join()));
                AbsoluteCapability capToWebRoot = UserContext.getPublicCapability(toWebRoot, network).join();
                FileWrapper webRoot = network.getFile(capToWebRoot, owner).join().get();
                webRootEntry = new WebRootEntry(webRootEntry.field, webRoot);
                cacheRoot(owner, webRootEntry);
            }

            Asset cached = lookupAsset(owner, path);
            if (cached != null) {
                FileWrapper updated = cached.source.getUpdated(network).join();
                if (updated.version.equals(cached.source.version)) {
                    serveAsset(cached.data, path, httpExchange);
                    return;
                }
            }

            Optional<FileWrapper> assetOpt = webRootEntry.webRoot.getDescendentByPath(path, crypto.hasher, network).join();
            if (assetOpt.isEmpty()) {
                serve404(httpExchange, webRootEntry.webRoot);
                return;
            }
            if (assetOpt.get().isDirectory()) {
                Optional<FileWrapper> index = assetOpt.get().getChild("index.html", crypto.hasher, network).join();
                if (index.isPresent())
                    assetOpt = index;
                else {
                    serve404(httpExchange, webRootEntry.webRoot);
                    return;
                }
            }
            byte[] body = Serialize.readFully(assetOpt.get(), crypto, network).join();
            serveAsset(body, path, httpExchange);
            if (body.length < MAX_ASSET_SIZE_CACHE) {
                cacheAsset(owner, path, new Asset(assetOpt.get(), body));
            }
        } catch (Exception e) {
            LOG.severe("Error handling " +httpExchange.getRequestURI());
            LOG.log(Level.WARNING, e.getMessage(), e);
            HttpUtil.replyError(httpExchange, e);
        } finally {
            httpExchange.close();
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                LOG.info("Public file Handler returned " + path + " query in: " + (t2 - t1) + " mS");
        }
    }

    private void serveAsset(byte[] body, String path, HttpExchange httpExchange) throws IOException {
//            if (isGzip)
//                httpExchange.getResponseHeaders().set("Content-Encoding", "gzip");
        if (path.endsWith(".js"))
            httpExchange.getResponseHeaders().set("Content-Type", "text/javascript");
        else if (path.endsWith(".html"))
            httpExchange.getResponseHeaders().set("Content-Type", "text/html");
        else if (path.endsWith(".css"))
            httpExchange.getResponseHeaders().set("Content-Type", "text/css");
        else if (path.endsWith(".json"))
            httpExchange.getResponseHeaders().set("Content-Type", "application/json");
        else if (path.endsWith(".png"))
            httpExchange.getResponseHeaders().set("Content-Type", "image/png");
        else if (path.endsWith(".woff"))
            httpExchange.getResponseHeaders().set("Content-Type", "application/font-woff");
        else if (path.endsWith(".svg"))
            httpExchange.getResponseHeaders().set("Content-Type", "image/svg+xml");

        if (httpExchange.getRequestMethod().equals("HEAD")) {
            httpExchange.getResponseHeaders().set("Content-Length", "" + body.length);
            httpExchange.sendResponseHeaders(200, -1);
            return;
        }

        // Only allow assets to be loaded from the original host
//            httpExchange.getResponseHeaders().set("content-security-policy", "default-src https: 'self'");
        // Don't anyone to load Peergos site in an iframe
        httpExchange.getResponseHeaders().set("x-frame-options", "sameorigin");
        // Enable cross site scripting protection
        httpExchange.getResponseHeaders().set("x-xss-protection", "1; mode=block");
        // Don't send Peergos referrer to anyone
        httpExchange.getResponseHeaders().set("referrer-policy", "no-referrer");

        httpExchange.sendResponseHeaders(200, body.length);
        httpExchange.getResponseBody().write(body);
        httpExchange.close();
    }

    private void serve404(HttpExchange httpExchange, FileWrapper webroot) throws IOException {
        if (webroot != null) {
            Optional<FileWrapper> custom404 = webroot.getChild("404.html", crypto.hasher, network).join();
            if (custom404.isPresent()) {
                byte[] data = Serialize.readFully(custom404.get(), crypto, network).join();
                httpExchange.getResponseHeaders().set("Content-Type", "text/html");
                httpExchange.sendResponseHeaders(404, data.length);
                httpExchange.getResponseBody().write(data);
                httpExchange.close();
                return;
            }
        }
        httpExchange.getResponseHeaders().set("Content-Type", "text/plain");
        httpExchange.sendResponseHeaders(404, 0);
    }
}
