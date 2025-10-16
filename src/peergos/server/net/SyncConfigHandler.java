package peergos.server.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import peergos.server.HostDirChooser;
import peergos.server.HostDirEnumerator;
import peergos.server.sync.DirectorySync;
import peergos.server.sync.SyncConfig;
import peergos.server.sync.SyncRunner;
import peergos.server.util.Args;
import peergos.server.util.HttpUtil;
import peergos.server.util.Logging;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.corenode.CoreNode;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.mutable.MutablePointers;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.MutableTreeImpl;
import peergos.shared.user.UserContext;
import peergos.shared.user.WriteSynchronizer;
import peergos.shared.util.Constants;
import peergos.shared.util.Either;
import peergos.shared.util.Futures;
import peergos.shared.util.Serialize;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SyncConfigHandler implements HttpHandler {
	private static final Logger LOG = Logging.LOG();
    public static final String OLD_SYNC_CONFIG_FILENAME = "sync-config";
    public static final String SYNC_CONFIG_FILENAME = "sync-config.json";

    private static final boolean LOGGING = true;
    private final SyncConfig args;
    private final Path peergosDir;
    private final SyncRunner syncer;
    private final NetworkAccess network;
    private final Crypto crypto;
    private final Either<HostDirEnumerator, HostDirChooser> hostPaths;

    public SyncConfigHandler(SyncConfig a,
                             Path peergosDir,
                             SyncRunner syncer,
                             ContentAddressedStorage storage,
                             MutablePointers mutable,
                             Either<HostDirEnumerator, HostDirChooser> hostPaths,
                             CoreNode core,
                             Crypto crypto) {
        this.args = a;
        this.peergosDir = peergosDir;
        this.syncer = syncer;
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, storage, crypto.hasher);
        MutableTreeImpl tree = new MutableTreeImpl(mutable, storage, crypto.hasher, synchronizer);
        this.network = new NetworkAccess(core, null, null, storage, null, Optional.empty(),
                mutable, tree, synchronizer, null, null, null, crypto.hasher,
                Collections.emptyList(), false);
        this.crypto = crypto;
        this.hostPaths = hostPaths;
        saveConfigToFile(a);
    }

    private synchronized void saveConfigToFile(SyncConfig config) {
        byte[] bytes = org.peergos.util.JSONParser.toString(config.toJson()).getBytes(StandardCharsets.UTF_8);
        try {
            Files.write(peergosDir.resolve(SYNC_CONFIG_FILENAME), bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized SyncConfig getUpdatedArgs() {
        try {
            String json = new String(Files.readAllBytes(peergosDir.resolve(SYNC_CONFIG_FILENAME)));
            return SyncConfig.fromJson((Map<String, Object>) org.peergos.util.JSONParser.parse(json));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void updateRemotePaths(SyncConfig updated) {
        List<String> links = updated.links;
        List<String> remotePaths = links.stream()
                .map(this::getRemotePath)
                .collect(Collectors.toList());
        saveConfigToFile(new SyncConfig(updated.localDirs, remotePaths, links, updated.syncLocalDeletes, updated.syncRemoteDeletes,
                updated.maxDownloadParallelism, updated.minFreeSpacePercent));
    }

    public void start() {
        if (! getUpdatedArgs().links.isEmpty())
            syncer.start();
    }

    private String getRemotePath(String link) {
        return UserContext.fromSecretLinksV2(Arrays.asList(link),
                        Arrays.asList(() -> Futures.of("")), network, crypto)
                .join()
                .getEntryPath()
                .join();
    }

    @Override
    public void handle(HttpExchange exchange) {
        long t1 = System.currentTimeMillis();
        String path = exchange.getRequestURI().getPath();
        try {
            if (! HttpUtil.allowedQuery(exchange, false)) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }
            String host = exchange.getRequestHeaders().get("Host").get(0);
            if (! host.startsWith("localhost:")) {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }
            if (path.startsWith("/"))
                path = path.substring(1);
            String action = path.substring(Constants.SYNC.length());
            Map<String, List<String>> params = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
            Function<String, String> last = key -> params.get(key).get(params.get(key).size() - 1);

            if (action.equals("add-pair")) {
                Map<String, Object> json = (Map<String, Object>) JSONParser.parse(new String(Serialize.readFully(exchange.getRequestBody())));
                String link = (String) json.get("link");
                String rawLocalDir = (String) json.get("dir");
                String localDir = isWindows() ? rawLocalDir.replaceAll("\\\\\\\\", "\\\\") : rawLocalDir;
                Boolean newSyncLocalDeletes = (Boolean) json.get("syncLocalDeletes");
                Boolean newSyncRemoteDeletes = (Boolean) json.get("syncRemoteDeletes");
                SyncConfig updated = getUpdatedArgs();
                List<String> links = updated.links;
                List<String> localDirs = updated.localDirs;
                List<String> remotePaths = updated.remotePaths;
                List<Boolean> syncLocalDeletes = updated.syncLocalDeletes;
                List<Boolean> syncRemoteDeletes = updated.syncRemoteDeletes;
                int existing = links.indexOf(link);
                if (existing != -1 && existing == localDirs.indexOf(localDir)) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.close();
                } else {
                    links.add(link);
                    localDirs.add(localDir);
                    remotePaths.add(getRemotePath(link));
                    syncLocalDeletes.add(newSyncLocalDeletes);
                    syncRemoteDeletes.add(newSyncRemoteDeletes);
                    saveConfigToFile(new SyncConfig(localDirs, remotePaths, links, syncLocalDeletes, syncRemoteDeletes,
                            updated.maxDownloadParallelism, updated.minFreeSpacePercent));
                    // run sync client now
                    syncer.start();
                    System.out.println("Syncing " + localDir + " syncLocalDeletes: " + newSyncLocalDeletes + ", syncRemoteDeletes: " + newSyncRemoteDeletes);
                    exchange.sendResponseHeaders(200, 0);
                    exchange.close();
                }
            } else if (action.equals("remove-pair")) {
                long label = Long.parseLong(last.apply("label"));
                int toRemove = 0;
                SyncConfig updated = getUpdatedArgs();
                List<String> links = updated.links;
                for (;toRemove < links.size(); toRemove++) {
                    String link = links.get(toRemove);
                    if (link.substring(link.lastIndexOf("/", link.indexOf("#")) + 1, link.indexOf("#")).equals(Long.toString(label)))
                        break;
                }
                if (toRemove == links.size())
                    throw new IllegalArgumentException("Unknown label");
                String link = links.remove(toRemove);
                List<String> localDirs = updated.localDirs;
                String removedLocal = localDirs.remove(toRemove);
                List<String> remotePaths = updated.remotePaths;
                remotePaths.remove(toRemove);
                List<Boolean> syncLocalDeletes = updated.syncLocalDeletes;
                syncLocalDeletes.remove(toRemove);
                List<Boolean> syncRemoteDeletes = updated.syncRemoteDeletes;
                syncRemoteDeletes.remove(toRemove);

                saveConfigToFile(new SyncConfig(localDirs, remotePaths, links, syncLocalDeletes, syncRemoteDeletes,
                        updated.maxDownloadParallelism, updated.minFreeSpacePercent));
                // clear sync state db as well
                String linkPath = UserContext.fromSecretLinksV2(Arrays.asList(link), Arrays.asList(() -> Futures.of("")), network, crypto).join().getEntryPath().join();
                Path syncDb = DirectorySync.getSyncStateDbPath(peergosDir, linkPath, removedLocal);
                LOG.info("Deleting " + syncDb);
                if (Files.exists(syncDb)) {
                    try {
                        Files.delete(syncDb);
                    } catch (FileSystemException e) {
                        LOG.info("Error deleting " + syncDb);
                    }
                }
                SyncRunner.StatusHolder status = syncer.getStatusHolder();
                status.setStatus("Removed sync of " + removedLocal);
                status.cancel();
                // clear sync state db again if it was recreated by an in progress sync
                if (Files.exists(syncDb)) {
                    Files.delete(syncDb);
                    LOG.info("Deleted " + syncDb);
                }
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            } else if (action.equals("get-pairs")) {
//                PublicKeyHash owner = PublicKeyHash.fromString(params.get("owner").get(0));
//                TimeLimited.isAllowedTime(ArrayOps.hexToBytes(last.apply("sig")), 30, storage, owner);
                // TODO filter links by owner
//                String username = core.getUsername(owner).join();

                SyncConfig updated = getUpdatedArgs();
                Map<String, Object> json = updated.toJsonWithoutCaps();
                byte[] res = JSONParser.toString(json).getBytes(StandardCharsets.UTF_8);

                exchange.sendResponseHeaders(200, res.length);
                OutputStream resp = exchange.getResponseBody();
                resp.write(res);
                exchange.close();
                ForkJoinPool.commonPool().execute(() -> updateRemotePaths(updated));
            } else if (action.equals("get-host-paths")) {
                if (hostPaths.isB())
                    throw new IllegalStateException("Use direct dir chooser");
                String prefix = last.apply("prefix");
                List<String> json = hostPaths.a().getHostDirs(prefix, 2).join();
                Collections.sort(json);
                byte[] res = JSONParser.toString(json).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, res.length);
                OutputStream resp = exchange.getResponseBody();
                resp.write(res);
                exchange.close();
            } else if (action.equals("get-host-dir")) {
                if (hostPaths.isA())
                    throw new IllegalStateException("Use dir lister");
                String rootUri = hostPaths.b().chooseDir().join();
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("root", rootUri);
                byte[] res = JSONParser.toString(json).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, res.length);
                OutputStream resp = exchange.getResponseBody();
                resp.write(res);
                exchange.close();
            } else if (action.equals("sync-now")) {
                syncer.runNow();
                byte[] res = JSONParser.toString(new LinkedHashMap<>()).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, res.length);
                OutputStream resp = exchange.getResponseBody();
                resp.write(res);
                exchange.close();
            } else if (action.equals("status")) {
                LinkedHashMap<Object, Object> reply = new LinkedHashMap<>();
                reply.put("msg", syncer.getStatusHolder().getStatusAndTime());
                Optional<String> error = syncer.getStatusHolder().getError();
                error.ifPresent(err -> reply.put("error", err));
                byte[] res = JSONParser.toString(reply).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, res.length);
                OutputStream resp = exchange.getResponseBody();
                resp.write(res);
                exchange.close();
            } else {
                LOG.info("Unknown sync config handler: " + action);
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
            }
        } catch (Exception e) {
            LOG.severe("Error handling " +exchange.getRequestURI());
            LOG.log(Level.WARNING, e.getMessage(), e);
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                LOG.info("Sync Config Handler returned in: " + (t2 - t1) + " mS");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().startsWith("windows");
    }
}
