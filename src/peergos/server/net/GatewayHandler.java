package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.util.Logging;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.io.ipfs.api.*;
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
        public final Optional<String> cspHeader;

        public WebRootEntry(FileWrapper field, FileWrapper webRoot, Optional<String> cspHeader) {
            this.field = field;
            this.webRoot = webRoot;
            this.cspHeader = cspHeader;
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
                    webRootEntry = new WebRootEntry(updatedField, null, Optional.empty());
                    invalidateAssets(owner);
                }
            } else {
                Path toProfileEntry = PathUtil.get(owner).resolve(".profile").resolve("webroot");
                AbsoluteCapability capToWebRootField = UserContext.getPublicCapability(toProfileEntry, network).join();
                FileWrapper webRootField = network.getFile(capToWebRootField, owner).join().get();
                webRootEntry = new WebRootEntry(webRootField, null, Optional.empty());
            }

            if (webRootEntry.webRoot == null) {
                Path toWebRoot = PathUtil.get(new String(Serialize.readFully(webRootEntry.field, crypto, network).join()));
                AbsoluteCapability capToWebRoot = UserContext.getPublicCapability(toWebRoot, network).join();
                Optional<FileWrapper> webRootOpt = network.getFile(capToWebRoot, owner).join();
                if (webRootOpt.isEmpty())
                    throw new IllegalStateException("web root not present");
                FileWrapper webRoot = webRootOpt.get();
                Optional<FileWrapper> headers = webRoot.getChild("headers.json", crypto.hasher, network).join();
                Optional<String> csp = headers.flatMap(f -> getCsp(f));
                webRootEntry = new WebRootEntry(webRootEntry.field, webRoot, csp);
                cacheRoot(owner, webRootEntry);
            }

            Asset cached = lookupAsset(owner, path);
            if (cached != null) {
                FileWrapper updated = cached.source.getUpdated(network).join();
                if (updated.version.equals(cached.source.version)) {
                    serveAsset(AsyncReader.build(cached.data), cached.source.getFileProperties(), cached.data.length,
                            path, webRootEntry.cspHeader, httpExchange);
                    return;
                }
            }

            Optional<FileWrapper> assetOpt = webRootEntry.webRoot.getDescendentByPath(path, crypto.hasher, network).join();
            if (assetOpt.isEmpty()) {
                serve404(httpExchange, webRootEntry.webRoot);
                return;
            }
            FileWrapper asset = assetOpt.get();
            if (asset.isDirectory()) {
                Optional<FileWrapper> index = asset.getChild("index.html", crypto.hasher, network).join();
                if (index.isPresent())
                    asset = index.get();
                else {
                    serve404(httpExchange, webRootEntry.webRoot);
                    return;
                }
            }
            AsyncReader reader = asset.getInputStream(network, crypto, x -> {}).join();
            long size = asset.getSize();
            Optional<byte[]> bodyToCache = serveAsset(reader, asset.getFileProperties(), size, path,
                    webRootEntry.cspHeader, httpExchange);
            if (bodyToCache.isPresent()) {
                cacheAsset(owner, path, new Asset(asset, bodyToCache.get()));
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

    private Optional<String> getCsp(FileWrapper headers) {
        if (headers.getSize() > 1024)
            return Optional.empty();
        try {
            byte[] body = Serialize.readFully(headers.getInputStream(network, crypto, x -> {}).join(), headers.getSize()).join();
            Map<String, String> json = (Map)JSONParser.parse(new String(body));
            return Optional.ofNullable(json.get("content-security-policy"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ThreadLocal<byte[]> buffer = ThreadLocal.withInitial(() -> new byte[MAX_ASSET_SIZE_CACHE]);

    private Optional<byte[]> serveAsset(AsyncReader reader,
                                        FileProperties props,
                                        long size,
                                        String path,
                                        Optional<String> cspHeader,
                                        HttpExchange httpExchange) throws IOException {
//            if (isGzip)
//                httpExchange.getResponseHeaders().set("Content-Encoding", "gzip");

        if (httpExchange.getRequestMethod().equals("HEAD")) {
            httpExchange.getResponseHeaders().set("Content-Length", "" + size);
            httpExchange.getResponseHeaders().set("Content-Type", props.mimeType);
            httpExchange.sendResponseHeaders(200, -1);
            return Optional.empty();
        }

        // Only allow assets to be loaded from the original host
        httpExchange.getResponseHeaders().set("content-security-policy", cspHeader.orElse("default-src 'self'"));
        // Don't anyone to load Peergos site in an iframe
        httpExchange.getResponseHeaders().set("x-frame-options", "sameorigin");
        // Enable cross site scripting protection
        httpExchange.getResponseHeaders().set("x-xss-protection", "1; mode=block");
        // Don't let browser sniff mime types
        httpExchange.getResponseHeaders().set("x-content-type-options", "nosniff");
        // Don't send Peergos referrer to anyone
        httpExchange.getResponseHeaders().set("referrer-policy", "no-referrer");
        // Don't send Peergos referrer to anyone
        httpExchange.getResponseHeaders().set("permissions-policy", "interest-cohort=()");

        if (size < MAX_ASSET_SIZE_CACHE) {
            byte[] body = Serialize.readFully(reader, size).join();
            addContentType(httpExchange, path, props, body);
            httpExchange.sendResponseHeaders(200, size);
            OutputStream resp = httpExchange.getResponseBody();
            resp.write(body);
            httpExchange.close();
            return Optional.of(body);
        }

        addContentType(httpExchange, path, props, null);
        httpExchange.sendResponseHeaders(200, size);
        OutputStream resp = httpExchange.getResponseBody();
        byte[] buf = buffer.get();
        int read;
        long offset = 0;
        while ((read = reader.readIntoArray(buf, 0, (int) Math.min(size - offset, buf.length)).join()) >= 0) {
            resp.write(buf, 0, read);
            offset += read;
        }
        httpExchange.close();
        return Optional.empty();
    }

    private void addContentType(HttpExchange httpExchange, String path, FileProperties props, byte[] start) {
        if (! path.endsWith(props.name))
            path = props.name;
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
        else if (path.endsWith(".wasm"))
            httpExchange.getResponseHeaders().set("Content-Type", "application/wasm");
        else if (path.endsWith(".xml"))
            httpExchange.getResponseHeaders().set("Content-Type", "application/xml");
        else if (start!= null && start.length > 15 && Arrays.equals("<!DOCTYPE html>".getBytes(), Arrays.copyOfRange(start, 0, 15)))
            httpExchange.getResponseHeaders().set("Content-Type", "text/html");
        else
            httpExchange.getResponseHeaders().set("Content-Type", props.mimeType);
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
