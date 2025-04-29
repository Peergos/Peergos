package peergos.server.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import peergos.server.sync.DirectorySync;
import peergos.server.util.Args;
import peergos.server.util.HttpUtil;
import peergos.server.util.Logging;
import peergos.server.util.TimeLimited;
import peergos.shared.corenode.CoreNode;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.util.ArrayOps;
import peergos.shared.util.Constants;
import peergos.shared.util.Serialize;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SyncConfigHandler implements HttpHandler {
	private static final Logger LOG = Logging.LOG();

    private static final boolean LOGGING = true;
    private final SyncRunner syncer;
    private final ContentAddressedStorage storage;
    private final CoreNode core;

    public SyncConfigHandler(Args a, ContentAddressedStorage storage, CoreNode core) {
        this.syncer = new SyncRunner(a);
        this.storage = storage;
        this.core = core;
    }

    static class SyncRunner {
        private Args args;
        private final Thread runner;

        public SyncRunner(Args args) {
            this.args = args;
            this.runner = new Thread(() -> {
                while (true) {
                    if (getArgs().hasArg("links")) {
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
            DirectorySync.syncDir(args.with("run-once", "true"));
        }

        public synchronized void setArgs(Args a) {
            this.args = a;
        }

        public synchronized Args getArgs() {
            return args;
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
            String action = path.substring(Constants.CONFIG.length());
            Map<String, List<String>> params = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
            Function<String, String> last = key -> params.get(key).get(params.get(key).size() - 1);

            if (action.equals("sync/add-pair")) {
                Map<String, Object> json = (Map<String, Object>) JSONParser.parse(new String(Serialize.readFully(exchange.getRequestBody())));
                List<String> links = syncer.getArgs().getOptionalArg("links")
                        .map(a -> a.split(","))
                        .map(Arrays::asList)
                        .orElse(new ArrayList<>());
                List<String> localDirs = syncer.getArgs().getOptionalArg("local-dirs")
                        .map(a -> a.split(","))
                        .map(Arrays::asList)
                        .orElse(new ArrayList<>());
                links.add((String)json.get("link"));
                localDirs.add((String)json.get("dir"));
                syncer.setArgs(syncer.getArgs()
                        .with("links", String.join(",", links))
                        .with("local-dirs", String.join(",", localDirs)));
                syncer.getArgs().saveToFile();
                // restart sync client
                syncer.runNow();
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            } else if (action.equals("sync/remove-pair")) {
                Map<String, Object> json = (Map<String, Object>) JSONParser.parse(new String(Serialize.readFully(exchange.getRequestBody())));
                List<String> links = syncer.getArgs().getOptionalArg("links")
                        .map(a -> a.split(","))
                        .map(Arrays::asList)
                        .orElse(new ArrayList<>());
                List<String> localDirs = syncer.getArgs().getOptionalArg("local-dirs")
                        .map(a -> a.split(","))
                        .map(Arrays::asList)
                        .orElse(new ArrayList<>());
                links.remove((String)json.get("link"));
                localDirs.remove((String)json.get("dir"));
                if (links.size() != localDirs.size())
                    throw new IllegalStateException("Couldn't remove sync directory pair");
                syncer.setArgs(syncer.getArgs()
                        .with("links", String.join(",", links))
                        .with("local-dirs", String.join(",", localDirs)));
                syncer.getArgs().saveToFile();
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            } else if (action.equals("sync/get-pairs")) {
                PublicKeyHash owner = PublicKeyHash.fromString(params.get("owner").get(0));
                TimeLimited.isAllowedTime(ArrayOps.hexToBytes(last.apply("sig")), 30, storage, owner);
                // TODO filter links by owner
                String username = core.getUsername(owner).join();

                Optional<String> links = syncer.getArgs().getOptionalArg("links");
                Optional<String> dirs = syncer.getArgs().getOptionalArg("local-dirs");
                Map<String, Object> json = new LinkedHashMap<>();
                links.ifPresent(val -> json.put("links", val));
                dirs.ifPresent(val -> json.put("local-dirs", val));
                byte[] res = JSONParser.toString(json).getBytes(StandardCharsets.UTF_8);

                exchange.sendResponseHeaders(200, res.length);
                OutputStream resp = exchange.getResponseBody();
                resp.write(res);
                exchange.close();
            } else {
                LOG.info("Unknown sync config handler: " +exchange.getRequestURI());
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
