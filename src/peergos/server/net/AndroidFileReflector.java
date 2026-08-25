package peergos.server.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import peergos.server.util.HttpUtil;
import peergos.server.util.JavaInflate;
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
import peergos.shared.user.fs.archive.ZipReader;
import peergos.shared.util.Constants;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class AndroidFileReflector implements HttpHandler {
	private static final Logger LOG = Logging.LOG();

    private static final boolean LOGGING = true;

    private final Crypto crypto;
    private final CoreNode core;
    private final MutablePointers mutable;
    private final ContentAddressedStorage dht;

    public AndroidFileReflector(Crypto crypto, CoreNode core, MutablePointers mutable, ContentAddressedStorage dht) {
        // reading a zip entry inflates it here rather than in the webview, which has no inflate
        JavaInflate.init();
        this.crypto = crypto;
        this.core = core;
        this.mutable = mutable;
        this.dht = dht;
    }

    @Override
    public void handle(HttpExchange httpExchange) {
        long t1 = System.currentTimeMillis();
        String path = httpExchange.getRequestURI().getPath();
        try {
            if (path.startsWith("/"))
                path = path.substring(1);
            String rest = path.substring(Constants.ANDROID_FILE_REFLECTOR.length());
            String action = rest.split("/")[0];
            if (action.equals("file")) {
                String link = rest.substring(action.length() + 1);

                AbsoluteCapability cap = AbsoluteCapability.fromLink(link);
                NetworkAccess network = NetworkAccess.buildPublicNetworkAccess(crypto.hasher, core, mutable, dht).join();
                Optional<FileWrapper> file = network.retrieveAll(List.of(new EntryPoint(cap, ""))).join().stream().findFirst();
                if (file.isEmpty()) {
                    httpExchange.sendResponseHeaders(404, 0);
                    httpExchange.close();
                    return;
                }
                long fileSize = file.get().getSize();
//                AsyncReader reader = file.get().getBufferedInputStream(network, crypto, (int)(fileSize >> 32), (int)fileSize, 10, x -> {}).join();
                AsyncReader reader = file.get().getInputStream(network, crypto, fileSize, x -> {}).join();
                OutputStream resp = httpExchange.getResponseBody();
                httpExchange.sendResponseHeaders(200, fileSize);
                byte[] buf = new byte[5 * 1024 * 1024];
                for (long offset = 0; offset < fileSize; ) {
                    int read = reader.readIntoArray(buf, 0, (int) Math.min(Chunk.MAX_SIZE, fileSize - offset)).join();
                    offset += read;
                    resp.write(buf, 0, read);
                    resp.flush();
                }
                httpExchange.close();
            } else if (action.equals("zip")) {
                List<String> links = Arrays.asList(rest.substring(action.length() + 1).split("\\$"));
                List<AbsoluteCapability> caps = links.stream().map(AbsoluteCapability::fromLink).collect(Collectors.toList());
                NetworkAccess network = NetworkAccess.buildPublicNetworkAccess(crypto.hasher, core, mutable, dht).join();
                Set<FileWrapper> files = network.retrieveAll(caps.stream().map(cap -> new EntryPoint(cap, "")).collect(Collectors.toList())).join();
                if (files.isEmpty()) {
                    httpExchange.sendResponseHeaders(404, 0);
                    httpExchange.close();
                    return;
                }

                OutputStream resp = httpExchange.getResponseBody();
                ZipOutputStream zout = new ZipOutputStream(resp);
                httpExchange.sendResponseHeaders(200, 0);
                for (FileWrapper file : files) {
                    writeDirToZip(file, zout, network, Paths.get(file.getName()));
                }
                zout.finish();
                zout.flush();
                httpExchange.close();
            } else if (action.equals("entry") || action.equals("entry-zip")) {
                // an entry inside a zip has no capability of its own, so what identifies it is the
                // archive's capability and its path within it
                String link = rest.substring(action.length() + 1);
                // the raw query: a decoded one cannot be split, since an entry name may contain & or =
                Map<String, List<String>> query = HttpUtil.parseQuery(httpExchange.getRequestURI().getRawQuery());
                List<String> entryPaths = decodeAll(query.get("path"));
                String filename = decode(query.getOrDefault("name", List.of("download")).get(0));
                AbsoluteCapability cap = AbsoluteCapability.fromLink(link);
                NetworkAccess network = NetworkAccess.buildPublicNetworkAccess(crypto.hasher, core, mutable, dht).join();
                Optional<FileWrapper> archive = network.retrieveAll(List.of(new EntryPoint(cap, ""))).join()
                        .stream().findFirst();
                if (archive.isEmpty() || entryPaths.isEmpty()) {
                    httpExchange.sendResponseHeaders(404, 0);
                    httpExchange.close();
                    return;
                }
                ZipReader zip = ZipReader.open(archive.get(), network, crypto).join();
                // the download manager fetches this url itself, so say what it is getting
                httpExchange.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=\"" + filename.replaceAll("[\"\\\\]", "_") + "\"");
                if (action.equals("entry")) {
                    Optional<peergos.shared.user.fs.archive.ZipEntry> entry = zip.getIndex().get(entryPaths.get(0));
                    if (entry.isEmpty() || entry.get().isDirectory) {
                        httpExchange.sendResponseHeaders(404, 0);
                        httpExchange.close();
                        return;
                    }
                    long size = entry.get().size;
                    AsyncReader reader = zip.read(entry.get()).join();
                    OutputStream resp = httpExchange.getResponseBody();
                    httpExchange.sendResponseHeaders(200, size);
                    byte[] buf = new byte[(int) Math.max(1, Math.min(size, Chunk.MAX_SIZE))];
                    for (long offset = 0; offset < size; ) {
                        int read = reader.readIntoArray(buf, 0, (int) Math.min(buf.length, size - offset)).join();
                        if (read <= 0)
                            break;
                        offset += read;
                        resp.write(buf, 0, read);
                        resp.flush();
                    }
                    httpExchange.close();
                } else {
                    OutputStream resp = httpExchange.getResponseBody();
                    ZipOutputStream zout = new ZipOutputStream(resp);
                    httpExchange.sendResponseHeaders(200, 0);
                    for (String entryPath : entryPaths) {
                        Optional<peergos.shared.user.fs.archive.ZipEntry> entry = zip.getIndex().get(entryPath);
                        if (entry.isEmpty())
                            continue;
                        writeArchiveEntryToZip(zip, entry.get(), Paths.get(entry.get().getName()), zout);
                    }
                    zout.finish();
                    zout.flush();
                    httpExchange.close();
                }
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

    private static List<String> decodeAll(List<String> values) {
        List<String> res = new ArrayList<>();
        for (String value : values == null ? Collections.<String>emptyList() : values)
            res.add(decode(value));
        return res;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** Write an entry, or a whole directory of them, into a zip being streamed to the download
     *  manager. An empty directory keeps a record of its own, since no file's path implies it.
     */
    private void writeArchiveEntryToZip(ZipReader zip,
                                        peergos.shared.user.fs.archive.ZipEntry entry,
                                        Path ourZipPath,
                                        ZipOutputStream zout) throws IOException {
        if (! entry.isDirectory) {
            long size = entry.size;
            AsyncReader reader = zip.read(entry).join();
            zout.putNextEntry(new ZipEntry(ourZipPath.toString()));
            byte[] buf = new byte[(int) Math.max(1, Math.min(size, Chunk.MAX_SIZE))];
            for (long offset = 0; offset < size; ) {
                int read = reader.readIntoArray(buf, 0, (int) Math.min(buf.length, size - offset)).join();
                if (read <= 0)
                    break;
                offset += read;
                zout.write(buf, 0, read);
            }
            zout.closeEntry();
            return;
        }
        List<peergos.shared.user.fs.archive.ZipEntry> children = zip.listDirectory(entry.path);
        if (children.isEmpty()) {
            zout.putNextEntry(new ZipEntry(ourZipPath + "/"));
            zout.closeEntry();
            return;
        }
        for (peergos.shared.user.fs.archive.ZipEntry child : children)
            writeArchiveEntryToZip(zip, child, ourZipPath.resolve(child.getName()), zout);
    }

    private void writeDirToZip(FileWrapper dir, ZipOutputStream zout, NetworkAccess network, Path ourZipPath) throws IOException {
        if (!dir.isDirectory()) {
            writeFileToZip(dir, ourZipPath, zout, network);
            return;
        }
        Set<FileWrapper> children = dir.getChildren(crypto.hasher, network).join();
        for (FileWrapper child : children) {
            Path childZipPath = ourZipPath.resolve(child.getName());
            if (child.isDirectory()) {
                writeDirToZip(child, zout, network, childZipPath);
            } else {
                writeFileToZip(child, childZipPath, zout, network);
            }
        }
    }

    private void writeFileToZip(FileWrapper f, Path ourZipPath, ZipOutputStream zout, NetworkAccess network) throws IOException {
        long fileSize = f.getSize();
        byte[] buf = new byte[(int)Math.min(fileSize, 5 * 1024 * 1024)];
        AsyncReader reader = f.getInputStream(network, crypto, x -> {}).join();
        zout.putNextEntry(new ZipEntry(ourZipPath.toString()));
        for (long offset = 0; offset < fileSize; ) {
            int read = reader.readIntoArray(buf, 0, (int) Math.min(Chunk.MAX_SIZE, fileSize - offset)).join();
            offset += read;
            zout.write(buf, 0, read);
            zout.flush();
        }
        zout.closeEntry();
    }
}
