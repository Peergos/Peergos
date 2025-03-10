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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
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
