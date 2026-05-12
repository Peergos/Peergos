package peergos.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public interface HostDirChooser {
    CompletableFuture<String> chooseDir();

    class Flatpak implements HostDirChooser {
        @Override
        public CompletableFuture<String> chooseDir() {
            CompletableFuture<String> res = new CompletableFuture<>();

            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "zenity",
                        "--file-selection",
                        "--directory",
                        "--title=Select folder to sync with Peergos"
                );
                Process p = pb.start();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream())
                );

                String selectedDir = r.readLine();
                p.waitFor();

                if (selectedDir != null && !selectedDir.isEmpty()) {
                    // Grant permission using the real path so future launches bind-mount
                    // the real directory directly, bypassing the document portal FUSE layer, which is buggy.
                    // Return the portal path for this session since the bind mount for the
                    // real path only takes effect after a restart.
                    String realPath = resolveDocPortalPath(selectedDir);
                    persistFlatpakPermission(realPath);
                    res.complete(selectedDir);
                }
            } catch (Exception e) {
                res.completeExceptionally(e);
            }
            return res;
        }

        // Portal paths look like /run/user/<uid>/doc/<docid>/<name>.
        // Syncing through the portal FUSE daemon causes stat() calls to hang when the
        // daemon gets into a bad state. Resolve to the real host path so that the
        // flatpak override bind-mounts the real directory directly on the next launch.
        public static String resolveDocPortalPath(String path) {
            Matcher m = Pattern.compile("/run/user/\\d+/doc/([^/]+)/.+").matcher(path);
            if (!m.matches())
                return path;
            String docId = m.group(1);
            try {
                Process p = new ProcessBuilder(
                        "flatpak-spawn", "--host",
                        "gdbus", "call", "--session",
                    "--dest", "org.freedesktop.portal.Documents",
                    "--object-path", "/org/freedesktop/portal/documents",
                    "--method", "org.freedesktop.portal.Documents.Info",
                    docId
                ).start();
                p.waitFor();
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                // gdbus output format: ('/home/username/somedir', @a(sas) [])
                // Match single-quoted string, handling gdbus escape sequences (\' and \\)
                Matcher pm = Pattern.compile("\\('((?:[^'\\\\]|\\\\.)*)'").matcher(output);
                if (!pm.find())
                    return path;
                return pm.group(1).replace("\\'", "'").replace("\\\\", "\\");
            } catch (Exception e) {
                return path;
            }
        }

        private static void persistFlatpakPermission(String path) throws Exception {
            // flatpak override must run on the host, not inside the sandbox.
            // Requires --talk-name=org.freedesktop.Flatpak in the Flatpak manifest.
            new ProcessBuilder(
                    "flatpak-spawn", "--host",
                    "flatpak", "override", "--user",
                    "--filesystem=" + path,
                    getAppId()
            ).start().waitFor();
        }

        public static String getAppId() {
            // FLATPAK_ID is set automatically by the Flatpak runtime inside the sandbox
            String envId = System.getenv("FLATPAK_ID");
            if (envId != null && !envId.isEmpty())
                return envId;
            // Fallback: parse /.flatpak-info (INI format, [Application] section, name= key)
            try {
                List<String> lines = Files.readAllLines(Path.of("/.flatpak-info"));
                for (String line : lines) {
                    if (line.startsWith("name="))
                        return line.substring("name=".length()).trim();
                }
            } catch (Exception ignored) {}
            throw new IllegalStateException("Cannot determine Flatpak app ID");
        }
    }
}
