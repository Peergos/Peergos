package peergos.server.webdav;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

public class WebdavMount implements Closeable {

    private static final Logger LOG = Logger.getLogger(WebdavMount.class.getName());
    private static final boolean FLATPAK = System.getenv("FLATPAK_ID") != null;

    private static String[] host(String... cmd) {
        if (!FLATPAK) return cmd;
        String[] wrapped = new String[cmd.length + 2];
        wrapped[0] = "flatpak-spawn";
        wrapped[1] = "--host";
        System.arraycopy(cmd, 0, wrapped, 2, cmd.length);
        return wrapped;
    }

    private final String mountPoint;
    private final Runnable unmounter;

    private WebdavMount(String mountPoint, Runnable unmounter) {
        this.mountPoint = mountPoint;
        this.unmounter = unmounter;
    }

    public String getMountPoint() {
        return mountPoint;
    }

    /** Mount the WebDAV server as a native drive/volume.
     *  macOS:   /Volumes/Peergos via mount_webdav
     *  Windows: an available drive letter via net use (requires admin for first-time BasicAuthLevel registry write)
     *  Linux:   GVFS user-space mount via gio mount
     */
    public static WebdavMount mount(int port, String webdavUser, String webdavPassword) throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        WebdavMount m;
        if (os.startsWith("mac"))
            m = mountMacOS(port, webdavUser, webdavPassword);
        else if (os.startsWith("windows"))
            m = mountWindows(port, webdavUser, webdavPassword);
        else
            m = mountLinux(port, webdavUser, webdavPassword);
        Runtime.getRuntime().addShutdownHook(new Thread(m::close, "WebDAV unmount"));
        return m;
    }

    private static WebdavMount mountMacOS(int port, String user, String pass) throws IOException {
        String mountPoint = "/Volumes/Peergos";
        new File(mountPoint).mkdirs();
        String url = "http://" + urlEncode(user) + ":" + urlEncode(pass) + "@localhost:" + port;
        runChecked(host("mount_webdav", "-s", "-v", "Peergos", url, mountPoint));
        LOG.info("WebDAV mounted at " + mountPoint);
        return new WebdavMount(mountPoint, () -> runSilent(host("umount", mountPoint)));
    }

    private static WebdavMount mountWindows(int port, String user, String pass) throws IOException {
        // Digest auth (the WebDAV server default) is supported by WebClient with no registry changes.
        // WebClient on Win 10/11 is trigger-started and auto-launches when net use is called.
        Set<String> before = driveLetters();
        String unc = "\\\\localhost@" + port + "\\DavWWWRoot";
        runChecked(host("net", "use", "*", unc, "/user:" + user, pass, "/persistent:no"));
        Set<String> after = driveLetters();
        after.removeAll(before);
        if (after.isEmpty())
            throw new IOException("net use succeeded but no new drive letter appeared");
        String letter = after.iterator().next();
        LOG.info("WebDAV mounted at " + letter);
        return new WebdavMount(letter, () -> runSilent(host("net", "use", letter, "/delete", "/yes")));
    }

    private static Set<String> driveLetters() {
        Set<String> letters = new HashSet<>();
        for (File root : File.listRoots()) {
            String path = root.getPath();
            if (path.length() >= 2)
                letters.add(path.substring(0, 2).toUpperCase());
        }
        return letters;
    }

    private static WebdavMount mountLinux(int port, String user, String pass) throws IOException {
        // gio mount ignores credentials in the URL and prompts interactively;
        // pipe the password to its stdin instead.
        String url = "dav://" + urlEncode(user) + "@localhost:" + port;
        runCheckedWithStdin(pass, host("gio", "mount", url));
        String mountPoint = findGvfsMountPoint(port);
        LOG.info("WebDAV mounted at " + mountPoint);
        return new WebdavMount(mountPoint, () -> runSilent(host("gio", "mount", "--unmount", url)));
    }

    private static String findGvfsMountPoint(int port) throws IOException {
        String uid = capture(host("id", "-u")).trim();
        Path gvfsBase = Path.of("/run/user/" + uid + "/gvfs");
        String portFragment = "port=" + port + ",";
        try (var stream = Files.list(gvfsBase)) {
            return stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("dav:") && name.contains(portFragment);
                    })
                    .findFirst()
                    .map(Path::toString)
                    .orElseThrow(() -> new IOException("Could not find GVFS mount point for port " + port + " under " + gvfsBase));
        }
    }

    private static void runCheckedWithStdin(String stdin, String... cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        try (OutputStream os = p.getOutputStream()) {
            os.write((stdin + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try {
            boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new IOException("Mount command timed out after 30s: " + String.join(" ", cmd));
            }
            String out = new String(p.getInputStream().readAllBytes());
            if (p.exitValue() != 0)
                throw new IOException("Command failed (exit " + p.exitValue() + "): " + String.join(" ", cmd) + "\n" + out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("Interrupted waiting for: " + String.join(" ", cmd));
        }
    }

    private static void runChecked(String... cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        try {
            boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new IOException("Mount command timed out after 30s: " + String.join(" ", cmd));
            }
            String out = new String(p.getInputStream().readAllBytes());
            if (p.exitValue() != 0)
                throw new IOException("Command failed (exit " + p.exitValue() + "): " + String.join(" ", cmd) + "\n" + out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("Interrupted waiting for: " + String.join(" ", cmd));
        }
    }

    private static void runSilent(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            p.waitFor();
        } catch (Exception e) {
            LOG.warning("Command failed (ignored): " + String.join(" ", cmd) + ": " + e.getMessage());
        }
    }

    private static String capture(String... cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        try {
            int code = p.waitFor();
            if (code != 0)
                throw new IOException("Command failed (exit " + code + "): " + String.join(" ", cmd));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
        return out;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        unmounter.run();
    }
}
