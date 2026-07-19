package peergos.server.webdav;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import java.util.stream.*;

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

    private static void ensureWindowsWebDavReady() throws IOException {
        boolean running = false;
        try {
            running = capture(host("sc.exe", "query", "WebClient")).contains("RUNNING");
        } catch (IOException ignored) {}

        boolean fileSizeOk = false;
        try {
            String val = capture(host("reg.exe", "query",
                    "HKLM\\SYSTEM\\CurrentControlSet\\Services\\WebClient\\Parameters",
                    "/v", "FileSizeLimitInBytes")).trim();
            // Default is 0x2FAF080 (50MB); we want 0xFFFFFFFF (4GB)
            fileSizeOk = val.contains("0xffffffff") || val.contains("0xFFFFFFFF");
        } catch (IOException ignored) {}

        if (!running || !fileSizeOk) {
            String startCmd = running ? "" : "net start WebClient & ";
            String fileSizeCmd = fileSizeOk ? "" :
                    "reg add HKLM\\SYSTEM\\CurrentControlSet\\Services\\WebClient\\Parameters" +
                    " /v FileSizeLimitInBytes /t REG_DWORD /d 4294967295 /f & " +
                    "net stop WebClient & net start WebClient";
            String elevatedCmd = (startCmd + fileSizeCmd).replaceAll("& $", "").trim();
            runSilent(host("powershell", "-Command",
                    "Start-Process cmd.exe -ArgumentList '/c " + elevatedCmd + "' -Verb RunAs -Wait"));
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try { running = capture(host("sc.exe", "query", "WebClient")).contains("RUNNING"); }
            catch (IOException ignored) {}
            if (!running)
                throw new IOException("WebClient service could not be started. " +
                        "Please run as Administrator: net start WebClient");
        }
    }

    private static WebdavMount mountWindows(int port, String user, String pass) throws IOException {
        ensureWindowsWebDavReady();
        String unc = "\\\\localhost@" + port + "\\Peergos";
        Set<String> before = driveLetters();
        String letter = null;
        if (!before.contains("P:")) {
            try {
                runChecked(host("net", "use", "P:", unc, pass, "/user:" + user, "/persistent:no"));
                letter = "P:";
            } catch (IOException ignored) {}
        }
        if (letter == null) {
            runChecked(host("net", "use", "*", unc, pass, "/user:" + user, "/persistent:no"));
            Set<String> after = driveLetters();
            after.removeAll(before);
            if (after.isEmpty())
                throw new IOException("net use succeeded but no new drive letter appeared");
            letter = after.iterator().next();
        }
        final String mountedLetter = letter;
        LOG.info("WebDAV mounted at " + mountedLetter);
        // Set a clean label so Explorer shows "Peergos" instead of "Peergos (\\localhost@port\Peergos)".
        // HKCU write requires no UAC elevation.
        String regKey = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\MountPoints2\\##localhost@" + port + "#Peergos";
        runSilent(host("reg.exe", "add", regKey, "/v", "_LabelFromReg", "/t", "REG_SZ", "/d", "Peergos", "/f"));
        return new WebdavMount(mountedLetter, () -> runSilent(host("net", "use", mountedLetter, "/delete", "/yes")));
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
        String gvfsBase = "/run/user/" + uid + "/gvfs";
        String portFragment = "port=" + port + ",";
        // gio mounts on the host, so the mount point only exists in the host's namespace —
        // under flatpak this directory is not visible to us at all. List it on the host for
        // the same reason we run gio there.
        return Stream.of(capture(host("ls", "-1", gvfsBase)).split("\n"))
                .map(String::trim)
                .filter(name -> name.startsWith("dav:") && name.contains(portFragment))
                .findFirst()
                .map(name -> gvfsBase + "/" + name)
                .orElseThrow(() -> new IOException("Could not find GVFS mount point for port " + port + " under " + gvfsBase));
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
