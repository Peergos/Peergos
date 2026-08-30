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
    /** False when another backend owns the drive and this one is only here for the bridge, which
     *  is the Windows CFAPI case. Mounting as well would stack a second drive beside it. */
    private final boolean canMountDrive;
    private final AtomicReference<WebdavMount> activeMount = new AtomicReference<>();
    private final AtomicReference<Server> activeServer = new AtomicReference<>();

    public WebdavBackend(String peergosUrl) {
        this(peergosUrl, true);
    }

    public WebdavBackend(String peergosUrl, boolean canMountDrive) {
        this.peergosUrl = peergosUrl;
        this.canMountDrive = canMountDrive;
    }

    @Override
    public void enable(MountConfig config, UserContext context, Path peergosDir) throws Exception {
        boolean wantsDrive = config.mountDrive && canMountDrive;
        boolean wantsDav = config.syncCalendar || config.syncContacts;
        if (! wantsDrive && ! wantsDav) {
            // bridge only, with nothing asked of it: another backend has the drive
            disable();
            return;
        }
        // The bridge is what serves CalDAV and CardDAV as well as files, so it runs for any of the
        // three; only the drive is gated on mountDrive. Thumbnail-cache seeding is for the drive
        // (and a no-op unless running under flatpak).
        WebdavFileSystem fs = new WebdavFileSystem(config.peergosUsername, config.peergosPassword,
                peergosUrl, config, wantsDrive);
        Server server = WebdavServer.startNonBlocking(config.webdavPort, config.webdavUsername,
                config.webdavPassword, fs, config.authType, config.syncCalendar, config.syncContacts);
        Server prevServer = activeServer.getAndSet(server);
        if (prevServer != null) try { prevServer.stop(); } catch (Exception ignored) {}

        writeAppGroupConfig(config);

        WebdavMount prevMount = activeMount.getAndSet(
                wantsDrive ?
                        WebdavMount.mount(config.webdavPort, config.webdavUsername, config.webdavPassword) :
                        null);
        if (prevMount != null) prevMount.close();
    }

    // Hidden on macOS for now, drive mount or not: nothing there has been through the CalDAV and
    // CardDAV client story yet, so offering it would be offering something untested.
    @Override
    public boolean supportsCalendar() {
        return ! isMac();
    }

    @Override
    public boolean supportsContacts() {
        return ! isMac();
    }

    @Override
    public boolean usesDavClients() {
        return ! isMac();
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
