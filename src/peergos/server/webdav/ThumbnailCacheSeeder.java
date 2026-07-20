package peergos.server.webdav;

import peergos.server.util.Logging;
import peergos.shared.user.fs.FileProperties;
import peergos.shared.user.fs.Thumbnail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pre-seeds the freedesktop thumbnail cache ({@code ~/.cache/thumbnails/normal}) with the
 * thumbnails Peergos already stores inside file metadata, so a file manager browsing the
 * drive mount shows thumbnails <b>without downloading file contents</b>.
 *
 * <p>Only meaningful for the flatpak drive mount:
 * <ul>
 *   <li>a file manager reads thumbnails from the <i>host</i> cache — under flatpak our
 *       {@code $HOME} is the real home (via {@code --filesystem=home}), so we write there
 *       directly;</li>
 *   <li>the cache entry is keyed by {@code md5(uri)} where {@code uri} is the exact
 *       {@code dav://user@localhost:port/path} that gio/GVFS uses for the mounted file;</li>
 *   <li>this is gated to flatpak so it never touches the cache of a machine running the
 *       standalone WebDAV server, and only the drive-mount code path enables it at all.</li>
 * </ul>
 *
 * <p>Entries are written as the stored thumbnail bytes verbatim into a {@code <md5>.png}
 * slot. nemo renders webp bytes in that slot (verified), so no image transcoding is needed.
 * Freshness relies on the mount's port changing each session, so entries are naturally
 * rewritten per mount rather than going stale.
 */
public final class ThumbnailCacheSeeder {
    private static final Logger LOG = Logging.LOG();

    private final String webdavUser;
    private final int port;
    private final Path normalDir;

    private ThumbnailCacheSeeder(String webdavUser, int port, Path normalDir) {
        this.webdavUser = webdavUser;
        this.port = port;
        this.normalDir = normalDir;
    }

    /**
     * Build a seeder for the flatpak drive mount, or {@link Optional#empty()} when it should
     * not run: not under flatpak, no usable home, or the cache dir can't be created.
     */
    public static Optional<ThumbnailCacheSeeder> createForFlatpakMount(MountConfig config) {
        if (System.getenv("FLATPAK_ID") == null)
            return Optional.empty();
        String home = System.getenv("HOME");
        if (home == null || home.isEmpty())
            return Optional.empty();
        // A host XDG_CACHE_HOME override is rare and not visible to us (ours is remapped),
        // so target the file manager's default of $HOME/.cache.
        Path normal = Paths.get(home, ".cache", "thumbnails", "normal");
        try {
            Files.createDirectories(normal);
        } catch (IOException e) {
            LOG.log(Level.WARNING, e, () -> "Thumbnail seeding disabled: cannot use " + normal);
            return Optional.empty();
        }
        // Seeded thumbnails are useless if the file manager won't display thumbnails for a
        // remote (non-local) mount, and the default is 'local-only'. Turn it on for the
        // installed file managers. This is a global, persistent change (it also affects other
        // remote locations the user browses), applied once when the drive mount is enabled.
        enableRemoteThumbnailsOnHost();
        LOG.info("Thumbnail cache seeding enabled at " + normal);
        return Optional.of(new ThumbnailCacheSeeder(config.webdavUsername, config.webdavPort, normal));
    }

    /** File managers whose "show thumbnails" preference gates remote thumbnailing. */
    private static final String[][] FILE_MANAGER_THUMBNAIL_PREFS = {
            {"org.nemo.preferences", "show-image-thumbnails"},
            {"org.gnome.nautilus.preferences", "show-image-thumbnails"},
            {"org.mate.caja.preferences", "show-image-thumbnails"},
    };

    /**
     * Set {@code show-image-thumbnails=always} for each installed file manager, on the host
     * (via {@code flatpak-spawn}). Best-effort: a file manager that isn't installed fails
     * cleanly and is skipped.
     */
    private static void enableRemoteThumbnailsOnHost() {
        for (String[] pref : FILE_MANAGER_THUMBNAIL_PREFS) {
            try {
                Process p = new ProcessBuilder("flatpak-spawn", "--host",
                        "gsettings", "set", pref[0], pref[1], "always")
                        .redirectErrorStream(true).start();
                p.getOutputStream().close();
                byte[] out = p.getInputStream().readAllBytes();
                if (p.waitFor() == 0)
                    LOG.info("Enabled remote thumbnails: " + pref[0]);
                else
                    LOG.fine("Skipped " + pref[0] + " (" + new String(out).trim() + ")");
            } catch (Exception e) {
                LOG.log(Level.FINE, e, () -> "Could not set " + pref[0]);
            }
        }
    }

    /**
     * Best-effort: write a cache entry for one file so the file manager renders it without a
     * download. {@code webdavPath} is the file's path from the mount root, e.g. {@code /demo/x.jpg}.
     * Never throws — a failure here must not disturb the directory listing.
     */
    public void seed(String webdavPath, FileProperties props) {
        try {
            if (props == null || props.isDirectory || props.thumbnail.isEmpty())
                return;
            Thumbnail thumb = props.thumbnail.get();
            String uri = "dav://" + webdavUser + "@localhost:" + port + encodePath(webdavPath);
            String name = md5Hex(uri) + ".png";
            Path out = normalDir.resolve(name);
            Path tmp = normalDir.resolve(name + "." + Thread.currentThread().getId() + ".tmp");
            Files.write(tmp, thumb.data);
            try {
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException noAtomic) {
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, e, () -> "Thumbnail seed failed for " + webdavPath);
        }
    }

    /**
     * Percent-encode a '/'-separated path the way gio/GVFS does for a {@code dav://} URI:
     * RFC 3986 unreserved characters are kept, '/' separators are kept, everything else
     * (UTF-8 bytes) is percent-encoded. Filenames using only these common characters match
     * gio exactly; exotic characters may miss and simply fall back to normal thumbnailing.
     */
    static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (byte raw : path.getBytes(StandardCharsets.UTF_8)) {
            int c = raw & 0xff;
            boolean unreserved = c == '/'
                    || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved)
                sb.append((char) c);
            else
                sb.append('%').append(String.format("%02X", c));
        }
        return sb.toString();
    }

    private static String md5Hex(String s) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
