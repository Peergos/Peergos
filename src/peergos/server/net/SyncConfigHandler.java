package peergos.server.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.peergos.config.Jsonable;
import peergos.server.sync.DirectorySync;
import peergos.server.user.JavaImageThumbnailer;
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
import peergos.shared.user.fs.ThumbnailGenerator;
import peergos.shared.util.Constants;
import peergos.shared.util.Futures;
import peergos.shared.util.Serialize;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class SyncConfigHandler implements HttpHandler {
	private static final Logger LOG = Logging.LOG();

    private static final boolean LOGGING = true;
    private final Args args;
    private final SyncRunner syncer;
    private final ContentAddressedStorage storage;
    private final CoreNode core;
    private final NetworkAccess network;
    private final Crypto crypto;

    public SyncConfigHandler(Args a,
                             ContentAddressedStorage storage,
                             MutablePointers mutable,
                             CoreNode core,
                             Crypto crypto) {
        this.args = a;
        this.storage = storage;
        this.core = core;
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, storage, crypto.hasher);
        MutableTreeImpl tree = new MutableTreeImpl(mutable, storage, null, synchronizer);
        this.network = new NetworkAccess(core, null, null, storage, null, Optional.empty(),
                mutable, tree, synchronizer, null, null, null, crypto.hasher,
                Collections.emptyList(), false);
        this.crypto = crypto;
        List<String> links = args.getOptionalArg("links")
                .map(arg -> Arrays.asList(arg.split(",")))
                .orElse(new ArrayList<>());
        List<String> localDirs = args.getOptionalArg("local-dirs")
                .map(arg -> Arrays.asList(arg.split(",")))
                .orElse(new ArrayList<>());

        this.syncer = new SyncRunner(links, localDirs, 32, 5, a.getPeergosDir(), network, crypto);
        ThumbnailGenerator.setInstance(new JavaImageThumbnailer());
    }

    private void saveConfigToFile(List<String> links, List<String> localDirs) {
        args.with("links", String.join(",", links)).with("local-dirs", String.join(",", localDirs)).saveToFile();
    }

    public void start() {
        syncer.start();
    }

    static class SyncRunner {
        public final List<String> links, localDirs;
        private final int maxDownloadParallelism, minFreeSpacePercent;
        private final Path peergosDir;
        private final NetworkAccess network;
        private final Crypto crypto;
        private final Thread runner;

        public SyncRunner(List<String> links,
                          List<String> localDirs,
                          int maxDownloadParallelism,
                          int minFreeSpacePercent,
                          Path peergosDir,
                          NetworkAccess network,
                          Crypto crypto) {
            this.links = Collections.synchronizedList(links);
            this.localDirs = Collections.synchronizedList(localDirs);
            this.maxDownloadParallelism = maxDownloadParallelism;
            this.minFreeSpacePercent = minFreeSpacePercent;
            this.peergosDir = peergosDir;
            this.network = network;
            this.crypto = crypto;
            this.runner = new Thread(() -> {
                while (true) {
                    if (! links.isEmpty()) {
                        try {
                            runIteration();
                        } catch (Exception e) {
                            LOG.log(Level.WARNING, e.getMessage(), e);
                        }
                    }
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {}
                }
            });
        }

        public void start() {
            runner.start();
        }

        public void runNow() {
            runner.interrupt();
        }

        public void runIteration() {
            DirectorySync.syncDir(links, localDirs, maxDownloadParallelism, minFreeSpacePercent, true, peergosDir, network, crypto);
        }
    }

    private static class SyncConfig implements Jsonable {
        public final List<String> localDirs, remotePaths, linkLabels;

        public SyncConfig(List<String> localDirs, List<String> remotePaths, List<String> linkLabels) {
            if (localDirs.size() != remotePaths.size())
                throw new IllegalStateException("Invalid SyncConfig!");
            if (localDirs.size() != linkLabels.size())
                throw new IllegalStateException("Invalid SyncConfig!");
            this.localDirs = localDirs;
            this.remotePaths = remotePaths;
            this.linkLabels = linkLabels;
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
                pairs.add(pair);
            }
            res.put("pairs", pairs);
            return res;
        }
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
                String localDir = (String) json.get("dir");
                syncer.links.add(link);
                syncer.localDirs.add(localDir);;
                saveConfigToFile(syncer.links, syncer.localDirs);
                // restart sync client
                syncer.runNow();
                System.out.println("Syncing " + localDir);
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            } else if (action.equals("remove-pair")) {
                long label = Long.parseLong(last.apply("label"));
                int toRemove = 0;
                for (;toRemove < syncer.links.size(); toRemove++) {
                    String link = syncer.links.get(toRemove);
                    if (link.substring(link.lastIndexOf("/", link.indexOf("#")) + 1, link.indexOf("#")).equals(Long.toString(label)))
                        break;
                }
                if (toRemove == syncer.links.size())
                    throw new IllegalArgumentException("Unknown label");
                syncer.links.remove(toRemove);
                syncer.localDirs.remove(toRemove);

                saveConfigToFile(syncer.links, syncer.localDirs);
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            } else if (action.equals("get-pairs")) {
//                PublicKeyHash owner = PublicKeyHash.fromString(params.get("owner").get(0));
//                TimeLimited.isAllowedTime(ArrayOps.hexToBytes(last.apply("sig")), 30, storage, owner);
                // TODO filter links by owner
//                String username = core.getUsername(owner).join();

                List<String> remotePaths = syncer.links.stream()
                                .map(cap -> UserContext.fromSecretLinksV2(Arrays.asList(cap),
                                                Arrays.asList(() -> Futures.of("")), network, crypto)
                                        .join()
                                        .getEntryPath()
                                        .join())
                                .collect(Collectors.toList());
                Map<String, Object> json = new SyncConfig(syncer.localDirs,
                        remotePaths,
                        syncer.links.stream()
                                // only return the link champ label, which is not sensitive, but enough for the owner to delete it
                                .map(link -> link.substring(link.lastIndexOf("/", link.indexOf("#")) + 1, link.indexOf("#")))
                                .collect(Collectors.toList())
                ).toJson();
                byte[] res = JSONParser.toString(json).getBytes(StandardCharsets.UTF_8);

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
}
