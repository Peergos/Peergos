package peergos.server.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.peergos.config.Jsonable;
import peergos.server.HostDirChooser;
import peergos.server.HostDirEnumerator;
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

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
    private final Args args;
    private final SyncRunner syncer;
    private final NetworkAccess network;
    private final Crypto crypto;
    private final Either<HostDirEnumerator, HostDirChooser> hostPaths;

    public SyncConfigHandler(Args a,
                             SyncRunner syncer,
                             ContentAddressedStorage storage,
                             MutablePointers mutable,
                             Either<HostDirEnumerator, HostDirChooser> hostPaths,
                             CoreNode core,
                             Crypto crypto) {
        this.args = a;
        this.syncer = syncer;
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, storage, crypto.hasher);
        MutableTreeImpl tree = new MutableTreeImpl(mutable, storage, crypto.hasher, synchronizer);
        this.network = new NetworkAccess(core, null, null, storage, null, Optional.empty(),
                mutable, tree, synchronizer, null, null, null, crypto.hasher,
                Collections.emptyList(), false);
        this.crypto = crypto;
        this.hostPaths = hostPaths;
    }

    private synchronized void saveConfigToFile(List<String> links,
                                  List<String> localDirs,
                                  List<String> remotePaths,
                                  List<Boolean> syncLocalDeletes,
                                  List<Boolean> syncRemoteDeletes) {
        if (links.isEmpty())
            args.removeArg("links")
                    .removeArg("local-dirs")
                    .removeArg("remote-paths")
                    .removeArg("sync-local-deletes")
                    .removeArg("sync-remote-deletes")
                    .with("config", SYNC_CONFIG_FILENAME)
                    .saveToJSONFile();
        else
            args.with("links", String.join(",", links))
                    .with("local-dirs", String.join(",", localDirs))
                    .with("remote-paths", String.join("//", remotePaths))
                    .with("sync-local-deletes", String.join(",", syncLocalDeletes.stream().map(Object::toString).collect(Collectors.toList())))
                    .with("sync-remote-deletes", String.join(",", syncRemoteDeletes.stream().map(Object::toString).collect(Collectors.toList())))
                    .with("config", SYNC_CONFIG_FILENAME)
                    .saveToJSONFile();
    }

    private synchronized Args getUpdatedArgs() {
        return Args.parse(new String[]{}, Optional.of(args.getPeergosDir().resolve(SYNC_CONFIG_FILENAME)), false);
    }

    public List<String> getLinks(Args updated) {
        if (! updated.hasArg("links"))
            return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(updated.getArg("links").split(",")));
    }

    public List<String> getRemotePaths(Args updated) {
        if (updated.hasArg("remote-paths")) {
            return new ArrayList<>(Arrays.asList(updated.getArg("remote-paths").split("//")));
        }
        return getLinks(updated).stream()
                .map(this::getRemotePath)
                .collect(Collectors.toList());
    }

    public void updateRemotePaths(Args updated) {
        List<String> links = getLinks(updated);
        List<String> remotePaths = links.stream()
                .map(this::getRemotePath)
                .collect(Collectors.toList());
        saveConfigToFile(links, getLocalDirs(updated), remotePaths, getSyncLocalDeletes(updated), getSyncRemoteDeletes(updated));
    }

    public List<String> getLocalDirs(Args updated) {
        if (! updated.hasArg("local-dirs"))
            return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(updated.getArg("local-dirs").split(",")));
    }

    public List<Boolean> getSyncLocalDeletes(Args updated) {
        if (! updated.hasArg("sync-local-deletes"))
            return new ArrayList<>();
        return new ArrayList<>(Stream.of(updated.getArg("sync-local-deletes").split(","))
                .map(Boolean::parseBoolean)
                .collect(Collectors.toList()));
    }

    public List<Boolean> getSyncRemoteDeletes(Args updated) {
        if (! updated.hasArg("sync-remote-deletes"))
            return new ArrayList<>();
        return new ArrayList<>(Stream.of(updated.getArg("sync-remote-deletes").split(","))
                .map(Boolean::parseBoolean)
                .collect(Collectors.toList()));
    }

    public void start() {
        if (! getLinks(getUpdatedArgs()).isEmpty())
            syncer.start();
    }

    private static class SyncConfig implements Jsonable {
        public final List<String> localDirs, remotePaths, linkLabels;
        public final List<Boolean> syncLocalDeletes, syncRemoteDeletes;

        public SyncConfig(List<String> localDirs,
                          List<String> remotePaths,
                          List<String> linkLabels,
                          List<Boolean> syncLocalDeletes,
                          List<Boolean> syncRemoteDeletes) {
            if (localDirs.size() != remotePaths.size())
                throw new IllegalStateException("Invalid SyncConfig!");
            if (localDirs.size() != linkLabels.size())
                throw new IllegalStateException("Invalid SyncConfig!");
            if (localDirs.size() != syncLocalDeletes.size())
                throw new IllegalStateException("Invalid SyncConfig!");
            if (localDirs.size() != syncRemoteDeletes.size())
                throw new IllegalStateException("Invalid SyncConfig!");
            this.localDirs = localDirs;
            this.remotePaths = remotePaths;
            this.linkLabels = linkLabels;
            this.syncLocalDeletes = syncLocalDeletes;
            this.syncRemoteDeletes = syncRemoteDeletes;
        }

        @Override
        public Map<String, Object> toJson() {
            LinkedHashMap<String, Object> res = new LinkedHashMap<>();
            List<Object> pairs = new ArrayList<>();
            for (int i=0; i < localDirs.size(); i++) {
                LinkedHashMap<String, Object> pair = new LinkedHashMap<>();
                pair.put("localpath", localDirs.get(i));
                pair.put("remotepath", remotePaths.get(i));
                pair.put("label", linkLabels.get(i));
                pair.put("syncLocalDeletes", syncLocalDeletes.get(i));
                pair.put("syncRemoteDeletes", syncRemoteDeletes.get(i));
                pairs.add(pair);
            }
            res.put("pairs", pairs);
            return res;
        }
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
                Args updated = getUpdatedArgs();
                List<String> links = getLinks(updated);
                List<String> localDirs = getLocalDirs(updated);
                List<String> remotePaths = getRemotePaths(updated);
                List<Boolean> syncLocalDeletes = getSyncLocalDeletes(updated);
                List<Boolean> syncRemoteDeletes = getSyncRemoteDeletes(updated);
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
                    saveConfigToFile(links, localDirs, remotePaths, syncLocalDeletes, syncRemoteDeletes);
                    // run sync client now
                    syncer.start();
                    System.out.println("Syncing " + localDir + " syncLocalDeletes: " + newSyncLocalDeletes + ", syncRemoteDeletes: " + newSyncRemoteDeletes);
                    exchange.sendResponseHeaders(200, 0);
                    exchange.close();
                }
            } else if (action.equals("remove-pair")) {
                long label = Long.parseLong(last.apply("label"));
                int toRemove = 0;
                Args updated = getUpdatedArgs();
                List<String> links = getLinks(updated);
                for (;toRemove < links.size(); toRemove++) {
                    String link = links.get(toRemove);
                    if (link.substring(link.lastIndexOf("/", link.indexOf("#")) + 1, link.indexOf("#")).equals(Long.toString(label)))
                        break;
                }
                if (toRemove == links.size())
                    throw new IllegalArgumentException("Unknown label");
                links.remove(toRemove);
                List<String> localDirs = getLocalDirs(updated);
                String removedLocal = localDirs.remove(toRemove);
                List<String> remotePaths = getRemotePaths(updated);
                remotePaths.remove(toRemove);
                List<Boolean> syncLocalDeletes = getSyncLocalDeletes(updated);
                syncLocalDeletes.remove(toRemove);
                List<Boolean> syncRemoteDeletes = getSyncRemoteDeletes(updated);
                syncRemoteDeletes.remove(toRemove);

                saveConfigToFile(links, localDirs, remotePaths, syncLocalDeletes, syncRemoteDeletes);
                SyncRunner.StatusHolder status = syncer.getStatusHolder();
                status.setStatus("Removed sync of " + removedLocal);
                status.cancel();
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            } else if (action.equals("get-pairs")) {
//                PublicKeyHash owner = PublicKeyHash.fromString(params.get("owner").get(0));
//                TimeLimited.isAllowedTime(ArrayOps.hexToBytes(last.apply("sig")), 30, storage, owner);
                // TODO filter links by owner
//                String username = core.getUsername(owner).join();

                Args updated = getUpdatedArgs();
                List<String> links = getLinks(updated);
                List<String> remotePaths = getRemotePaths(updated);
                List<String> localDirs = getLocalDirs(updated);
                Map<String, Object> json = new SyncConfig(localDirs,
                        remotePaths,
                        links.stream()
                                // only return the link champ label, which is not sensitive, but enough for the owner to delete it
                                .map(link -> link.substring(link.lastIndexOf("/", link.indexOf("#")) + 1, link.indexOf("#")))
                                .collect(Collectors.toList()),
                        getSyncLocalDeletes(updated),
                        getSyncRemoteDeletes(updated)
                ).toJson();
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
                List<String> json = hostPaths.a().getHostDirs(prefix, 5).join();
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
