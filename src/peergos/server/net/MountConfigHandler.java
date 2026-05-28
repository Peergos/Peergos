package peergos.server.net;

import com.sun.net.httpserver.*;
import org.eclipse.jetty.server.Server;
import peergos.server.MountProperties;
import peergos.server.webdav.MountConfig;
import peergos.server.webdav.WebdavFileSystem;
import peergos.server.webdav.WebdavMount;
import peergos.server.webdav.WebdavServer;
import peergos.server.util.HttpUtil;
import peergos.server.util.Logging;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.util.Constants;
import peergos.shared.util.Serialize;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.*;

public class MountConfigHandler implements HttpHandler {
    private static final Logger LOG = Logging.LOG();

    private final Path peergosDir;
    private final String peergosUrl;
    private final AtomicReference<Server> webdavServer = new AtomicReference<>(null);
    private final AtomicReference<WebdavMount> activeMount = new AtomicReference<>(null);
    private final AtomicReference<String> mountError = new AtomicReference<>(null);
    private final AtomicReference<String> activePeergosUsername = new AtomicReference<>("");

    public MountConfigHandler(MountProperties props) {
        this.peergosDir = props.peergosDir;
        this.peergosUrl = props.peergosUrl;
    }

    public void start() {
        MountConfig config = readConfig();
        if (config.enabled) {
            mountError.set(null);
            ForkJoinPool.commonPool().execute(() -> {
                try { enableMount(config); }
                catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to restore WebDAV mount on startup", e);
                    mountError.set(e.getMessage());
                }
            });
        }
    }

    private synchronized MountConfig readConfig() {
        Path configFile = peergosDir.resolve(MountConfig.FILENAME);
        if (!configFile.toFile().exists())
            return MountConfig.disabled();
        try {
            String json = Files.readString(configFile);
            return MountConfig.fromJson((Map<String, Object>) JSONParser.parse(json));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void saveConfig(MountConfig config) {
        try {
            Files.write(peergosDir.resolve(MountConfig.FILENAME),
                    JSONParser.toString(config.toJson()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void enableMount(MountConfig config) throws IOException {
        WebdavFileSystem fs = new WebdavFileSystem(config.peergosUsername, config.peergosPassword, peergosUrl);
        Server server = WebdavServer.startNonBlocking(config.webdavPort, config.webdavUsername,
                config.webdavPassword, fs, config.authType);
        webdavServer.set(server);
        activePeergosUsername.set(config.peergosUsername);
        writeAppGroupConfig(config);
        WebdavMount mount = WebdavMount.mount(config.webdavPort, config.webdavUsername, config.webdavPassword);
        activeMount.set(mount);
    }

    private static void writeAppGroupConfig(MountConfig config) {
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("mac")) return;
        try {
            Path appGroupDir = Path.of(System.getProperty("user.home"),
                    "Library", "Group Containers", "group.org.peergos.PeergosMount");
            Files.createDirectories(appGroupDir);
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("port", config.webdavPort);
            json.put("webdavUsername", config.webdavUsername);
            json.put("webdavPassword", config.webdavPassword);
            json.put("peergosUsername", config.peergosUsername);
            Files.write(appGroupDir.resolve("webdav-config.json"),
                    JSONParser.toString(json).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to write App Group config", e);
        }
    }

    private void disableMount() {
        WebdavMount mount = activeMount.getAndSet(null);
        if (mount != null)
            mount.close();
        Server server = webdavServer.getAndSet(null);
        if (server != null) {
            try { server.stop(); } catch (Exception e) {
                LOG.log(Level.WARNING, "Error stopping WebDAV server", e);
            }
        }
        activePeergosUsername.set("");
        if (System.getProperty("os.name", "").toLowerCase().startsWith("mac")) {
            Path.of(System.getProperty("user.home"),
                    "Library", "Group Containers", "group.org.peergos.PeergosMount",
                    "webdav-config.json").toFile().delete();
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static int findFreePort() throws IOException {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    @Override
    public void handle(HttpExchange exchange) {
        long t1 = System.currentTimeMillis();
        String path = exchange.getRequestURI().getPath();
        try {
            if (!HttpUtil.allowedQuery(exchange, false)) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }
            String host = exchange.getRequestHeaders().get("Host").get(0);
            if (!host.startsWith("localhost:")) {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }
            if (path.startsWith("/"))
                path = path.substring(1);
            String action = path.substring(Constants.MOUNT.length());

            if (action.equals("get-config")) {
                MountConfig config = readConfig();
                WebdavMount mount = activeMount.get();
                boolean mountActive = mount != null;
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("enabled", mountActive || config.enabled);
                json.put("peergosUsername", mountActive ? activePeergosUsername.get() : config.peergosUsername);
                json.put("webdavUsername", config.webdavUsername);
                json.put("webdavPort", config.webdavPort);
                json.put("authType", config.authType);
                json.put("mountPoint", mountActive ? mount.getMountPoint() : "");
                String err = mountError.get();
                if (err != null) json.put("error", err);
                byte[] res = JSONParser.toString(json).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, res.length);
                exchange.getResponseBody().write(res);

            } else if (action.equals("enable")) {
                Map<String, Object> body = (Map<String, Object>) JSONParser.parse(
                        new String(Serialize.readFully(exchange.getRequestBody())));
                String peergosUsername = (String) body.get("peergosUsername");
                String peergosPassword = (String) body.get("peergosPassword");
                boolean autoMount = body.get("autoMount") instanceof Boolean ? (Boolean) body.get("autoMount") : true;
                String authType = "digest";
                String webdavUsername = generateToken();
                String webdavPassword = generateToken();
                int webdavPort = findFreePort();

                disableMount();
                MountConfig config = new MountConfig(true, peergosUsername, peergosPassword,
                        webdavUsername, webdavPassword, webdavPort, authType);
                if (autoMount) saveConfig(config);
                // Native mount can block (e.g. gio mount on Linux awaits D-Bus); run in background
                // and let the UI poll get-config for the mount point.
                mountError.set(null);
                ForkJoinPool.commonPool().execute(() -> {
                    try { enableMount(config); }
                    catch (Exception e) {
                        LOG.log(Level.WARNING, "Failed to enable WebDAV mount", e);
                        mountError.set(e.getMessage());
                    }
                });
                exchange.sendResponseHeaders(200, 0);

            } else if (action.equals("disable")) {
                disableMount();
                peergosDir.resolve(MountConfig.FILENAME).toFile().delete();
                exchange.sendResponseHeaders(200, 0);
            } else {
                LOG.info("Unknown mount config action: " + action);
                exchange.sendResponseHeaders(404, 0);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error handling " + exchange.getRequestURI(), e);
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
            LOG.info("Mount Config Handler returned in: " + (System.currentTimeMillis() - t1) + " mS");
        }
    }
}
