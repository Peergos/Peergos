package peergos.server.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import peergos.server.HostDirChooser;
import peergos.server.HostDirEnumerator;
import peergos.server.sync.DirectorySync;
import peergos.server.sync.PairLogger;
import peergos.server.sync.PairStatus;
import peergos.server.sync.SyncConfig;
import peergos.server.sync.SyncRunner;
import peergos.server.sync.SyncStatus;
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

    public void updateRemotePaths(SyncConfig snapshot) {
        // resolving the links hits the network, so do it before taking the lock
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String link : snapshot.links)
            resolved.put(link, getRemotePath(link));
        synchronized (this) {
            // pairs can be added or removed while the paths are being resolved, so merge
            // into the config as it is now: writing the old one back would bring a
            // removed pair straight back
            SyncConfig current = getUpdatedArgs();
            List<String> remotePaths = new ArrayList<>();
            boolean changed = false;
            for (int i = 0; i < current.links.size(); i++) {
                String path = resolved.getOrDefault(current.links.get(i), current.remotePaths.get(i));
                changed |= ! path.equals(current.remotePaths.get(i));
                remotePaths.add(path);
            }
            if (changed)
                saveConfigToFile(new SyncConfig(current.localDirs, remotePaths, current.links,
                        current.syncLocalDeletes, current.syncRemoteDeletes, current.allowOnMobile,
                        current.maxDownloadParallelism, current.minFreeSpacePercent, current.paused));
        }
    }

    public void start() {
        SyncConfig config = getUpdatedArgs();
        if (config.paused)
            syncer.getStatusHolder().pause();
        if (! config.links.isEmpty())
            syncer.start();
    }

    // under the same lock as the other whole config rewrites, so a pause cannot be
    // lost against the path refresh that every get-pairs schedules
    private synchronized void setPaused(boolean paused) {
        SyncConfig c = getUpdatedArgs();
        saveConfigToFile(new SyncConfig(c.localDirs, c.remotePaths, c.links, c.syncLocalDeletes,
                c.syncRemoteDeletes, c.allowOnMobile, c.maxDownloadParallelism, c.minFreeSpacePercent, paused));
    }

    /** Windows and macOS treat local paths as case insensitive, so comparing them
     *  literally would let an overlapping pair through. Drive paths always match exactly. */
    private static String localCompare(String path) {
        return isWindows() || isMac() ? path.toLowerCase() : path;
    }

    /** True when either path is the other, or lies inside it. */
    private static boolean nested(String a, String b) {
        // local paths use the platform separator, remote ones always "/"
        String x = a.replace('\\', '/');
        String y = b.replace('\\', '/');
        if (! x.endsWith("/"))
            x = x + "/";
        if (! y.endsWith("/"))
            y = y + "/";
        return x.startsWith(y) || y.startsWith(x);
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
            // A page in the user's browser can POST here cross site without a preflight,
            // so anything that admits to another origin is refused: otherwise any site
            // the user visits could pause their sync or remove a folder. No
            // Access-Control-Allow-Origin is ever sent, so such a caller cannot read a
            // reply either.
            List<String> origins = exchange.getRequestHeaders().get("Origin");
            if (origins != null && ! origins.isEmpty() && ! origins.get(0).equals("http://" + host)) {
                LOG.info("Refusing sync request from origin " + origins.get(0));
                exchange.sendResponseHeaders(403, 0);
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
                List<Boolean> allowOnMobile = updated.allowOnMobile;
                int existing = links.indexOf(link);
                if (existing != -1 && existing == localDirs.indexOf(localDir)) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.close();
                } else {
                    // one pair's folder must not sit inside another's, on either side:
                    // the outer pair would mirror the inner one's files and propagate
                    // its deletions, copying data back and forth and losing it
                    String newRemote = getRemotePath(link);
                    for (int i = 0; i < links.size(); i++) {
                        if (nested(newRemote, remotePaths.get(i)))
                            throw new IllegalStateException("That Drive folder overlaps the one already synced with "
                                    + localDirs.get(i));
                        if (nested(localCompare(localDir), localCompare(localDirs.get(i))))
                            throw new IllegalStateException("That folder overlaps the one already synced with "
                                    + remotePaths.get(i));
                    }
                    // a folder added back after being removed must not report the old status
                    String pairHash = PairLogger.hash(newRemote, localDir);
                    try {
                        PairLogger.deleteFor(peergosDir, pairHash);
                        PairStatus.deleteFor(peergosDir, pairHash);
                    } catch (IOException e) {
                        LOG.info("Error clearing sync log/status for " + pairHash + ": " + e.getMessage());
                    }
                    links.add(link);
                    localDirs.add(localDir);
                    remotePaths.add(newRemote);
                    syncLocalDeletes.add(newSyncLocalDeletes);
                    syncRemoteDeletes.add(newSyncRemoteDeletes);
                    // New pairs default to Wi-Fi only; flip via set-allow-mobile.
                    allowOnMobile.add(false);
                    saveConfigToFile(new SyncConfig(localDirs, remotePaths, links, syncLocalDeletes, syncRemoteDeletes,
                            allowOnMobile, updated.maxDownloadParallelism, updated.minFreeSpacePercent, updated.paused));
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
                List<Boolean> allowOnMobile = updated.allowOnMobile;
                allowOnMobile.remove(toRemove);

                saveConfigToFile(new SyncConfig(localDirs, remotePaths, links, syncLocalDeletes, syncRemoteDeletes,
                        allowOnMobile, updated.maxDownloadParallelism, updated.minFreeSpacePercent, updated.paused));
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
                // cancelling stops the whole pass, so ask for another one straight away:
                // the folders it had not reached should not wait for the next schedule
                status.cancel();
                syncer.runNow();
                // clear sync state db again if it was recreated by an in progress sync
                if (Files.exists(syncDb)) {
                    Files.delete(syncDb);
                    LOG.info("Deleted " + syncDb);
                }
                String pairHash = PairLogger.hash(linkPath, removedLocal);
                try {
                    PairLogger.deleteFor(peergosDir, pairHash);
                    PairStatus.deleteFor(peergosDir, pairHash);
                } catch (IOException e) {
                    LOG.info("Error deleting sync log/status for " + pairHash + ": " + e.getMessage());
                }
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            } else if (action.equals("set-allow-mobile")) {
                long label = Long.parseLong(last.apply("label"));
                boolean allow = Boolean.parseBoolean(last.apply("allow"));
                SyncConfig updated = getUpdatedArgs();
                List<String> links = updated.links;
                int idx = 0;
                for (; idx < links.size(); idx++) {
                    String link = links.get(idx);
                    if (link.substring(link.lastIndexOf("/", link.indexOf("#")) + 1, link.indexOf("#")).equals(Long.toString(label)))
                        break;
                }
                if (idx == links.size())
                    throw new IllegalArgumentException("Unknown label");
                updated.allowOnMobile.set(idx, allow);
                saveConfigToFile(updated);
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
            } else if (action.equals("use-host-dir-chooser")) {
                boolean useHostDirChooser = hostPaths.isB();
                byte[] res = JSONParser.toString(useHostDirChooser).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, res.length);
                OutputStream resp = exchange.getResponseBody();
                resp.write(res);
                exchange.close();
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
                // also clears the cancellation, else the triggered run aborts immediately
                syncer.getStatusHolder().unpause();
                setPaused(false);
                syncer.runNow();
                byte[] res = JSONParser.toString(new LinkedHashMap<>()).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, res.length);
                OutputStream resp = exchange.getResponseBody();
                resp.write(res);
                exchange.close();
            } else if (action.equals("pause")) {
                SyncRunner.StatusHolder holder = syncer.getStatusHolder();
                holder.pause();
                setPaused(true);
                // no further pass is coming, so settle the state rather than leave it SYNCING
                holder.setStatus(SyncStatus.SYNCED);
                byte[] res = JSONParser.toString(new LinkedHashMap<>()).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, res.length);
                OutputStream resp = exchange.getResponseBody();
                resp.write(res);
                exchange.close();
            } else if (action.equals("status")) {
                SyncRunner.StatusHolder global = syncer.getStatusHolder();
                Optional<String> error = global.getError();
                SyncConfig cfg = getUpdatedArgs();
                List<Object> pairs = new ArrayList<>();
                List<SyncStatus> pairStates = new ArrayList<>();
                for (int i = 0; i < cfg.links.size(); i++) {
                    String link = cfg.links.get(i);
                    String label = link.substring(link.lastIndexOf("/", link.indexOf("#")) + 1, link.indexOf("#"));
                    String hash = PairLogger.hash(cfg.remotePaths.get(i), cfg.localDirs.get(i));
                    PairStatus ps = new PairStatus(peergosDir, hash);
                    LinkedHashMap<String, Object> p = new LinkedHashMap<>();
                    p.put("label", label);
                    p.put("msg", ps.getStatusAndTime());
                    p.put("state", ps.getStatus().name());
                    ps.getError().ifPresent(err -> p.put("error", err));
                    pairs.add(p);
                    pairStates.add(ps.getStatus());
                }
                LinkedHashMap<Object, Object> reply = new LinkedHashMap<>();
                reply.put("msg", global.getStatusAndTime());
                boolean globalError = error.isPresent() || global.getStatus() == SyncStatus.ERROR;
                reply.put("state", SyncStatus.aggregate(pairStates, globalError).name());
                reply.put("paused", global.isPaused());
                error.ifPresent(err -> reply.put("error", err));
                reply.put("pairs", pairs);
                byte[] res = JSONParser.toString(reply).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, res.length);
                OutputStream resp = exchange.getResponseBody();
                resp.write(res);
                exchange.close();
            } else if (action.equals("get-log")) {
                long label = Long.parseLong(last.apply("label"));
                SyncConfig cfg = getUpdatedArgs();
                int idx = 0;
                for (; idx < cfg.links.size(); idx++) {
                    String link = cfg.links.get(idx);
                    if (link.substring(link.lastIndexOf("/", link.indexOf("#")) + 1, link.indexOf("#")).equals(Long.toString(label)))
                        break;
                }
                if (idx == cfg.links.size())
                    throw new IllegalArgumentException("Unknown label");
                String hash = PairLogger.hash(cfg.remotePaths.get(idx), cfg.localDirs.get(idx));
                byte[] res = PairLogger.readCombined(peergosDir, hash);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"sync-" + label + ".log\"");
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

    private static boolean isMac() {
        return System.getProperty("os.name").toLowerCase().startsWith("mac");
    }
}
