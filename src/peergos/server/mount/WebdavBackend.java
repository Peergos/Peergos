package peergos.server.mount;

import org.eclipse.jetty.server.Server;
import peergos.server.webdav.MountConfig;
import peergos.server.webdav.WebdavFileSystem;
import peergos.server.webdav.WebdavMount;
import peergos.server.webdav.WebdavServer;
import peergos.server.util.Logging;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.user.UserContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WebdavBackend implements MountBackend {

    private static final Logger LOG = Logging.LOG();

    private final String peergosUrl;
    private final AtomicReference<WebdavMount> activeMount = new AtomicReference<>();
    private final AtomicReference<Server> activeServer = new AtomicReference<>();

    public WebdavBackend(String peergosUrl) {
        this.peergosUrl = peergosUrl;
    }

    @Override
    public void enable(MountConfig config, UserContext context, Path peergosDir) throws Exception {
        WebdavFileSystem fs = new WebdavFileSystem(config.peergosUsername, config.peergosPassword,
                peergosUrl, config);
        Server server = WebdavServer.startNonBlocking(config.webdavPort, config.webdavUsername,
                config.webdavPassword, fs, config.authType);
        Server prevServer = activeServer.getAndSet(server);
        if (prevServer != null) try { prevServer.stop(); } catch (Exception ignored) {}

        writeAppGroupConfig(config);

        WebdavMount mount = WebdavMount.mount(config.webdavPort, config.webdavUsername, config.webdavPassword);
        WebdavMount prevMount = activeMount.getAndSet(mount);
        if (prevMount != null) prevMount.close();
    }

    @Override
    public void disable() {
        WebdavMount mount = activeMount.getAndSet(null);
        if (mount != null) mount.close();
        Server server = activeServer.getAndSet(null);
        if (server != null) {
            try { server.stop(); } catch (Exception e) {
                LOG.log(Level.WARNING, "Error stopping WebDAV server", e);
            }
        }
        deleteAppGroupConfig();
    }

    @Override
    public java.util.Optional<String> activeMountPoint() {
        WebdavMount mount = activeMount.get();
        return mount == null ? java.util.Optional.empty() : java.util.Optional.of(mount.getMountPoint());
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("mac");
    }

    private static Path appGroupConfigPath() {
        return Path.of(System.getProperty("user.home"),
                "Library", "Group Containers", "group.org.peergos.PeergosMount",
                "webdav-config.json");
    }

    private static void writeAppGroupConfig(MountConfig config) {
        if (!isMac()) return;
        try {
            Path file = appGroupConfigPath();
            Files.createDirectories(file.getParent());
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("port", config.webdavPort);
            json.put("webdavUsername", config.webdavUsername);
            json.put("webdavPassword", config.webdavPassword);
            json.put("peergosUsername", config.peergosUsername);
            Files.write(file, JSONParser.toString(json).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to write App Group config", e);
        }
    }

    private static void deleteAppGroupConfig() {
        if (!isMac()) return;
        try { Files.deleteIfExists(appGroupConfigPath()); }
        catch (IOException e) { LOG.log(Level.WARNING, "Failed to delete App Group config", e); }
    }
}
