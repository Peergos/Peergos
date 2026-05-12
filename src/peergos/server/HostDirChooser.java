package peergos.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public interface HostDirChooser {
    CompletableFuture<String> chooseDir();

    class Flatpak implements HostDirChooser {
        @Override
        public CompletableFuture<String> chooseDir() {
            CompletableFuture<String> res = new CompletableFuture<>();

            try {
                // Run zenity on the host so the file chooser returns the real path.
                // Zenity inside the sandbox goes through the file chooser portal, which
                // maps the result into /run/user/<uid>/doc/ (a FUSE mount) regardless of
                // what the user picked, causing stat() calls to hang if the fuse daemon stalls.
                ProcessBuilder pb = new ProcessBuilder(
                        "flatpak-spawn", "--host",
                        "zenity",
                        "--file-selection",
                        "--directory",
                        "--title=Select folder to sync with Peergos"
                );
                Process p = pb.start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String selectedDir = r.readLine();
                p.waitFor();

                if (selectedDir != null && !selectedDir.isEmpty()) {
                    persistFlatpakPermission(selectedDir);
                    res.complete(selectedDir);
                }
            } catch (Exception e) {
                res.completeExceptionally(e);
            }
            return res;
        }

        /**
         * Removes any /run/user/.../doc/... portal FUSE paths from the Flatpak user override.
         * These stale paths cause flatpak info --file-access to deadlock: the portal daemon
         * spawns flatpak info to check permissions, flatpak info stats the FUSE path, which
         * requires the portal to respond — but the portal is waiting for flatpak info to finish.
         * This was a massive pain to find.
         */
        public static void cleanupPortalOverrides() {
            try {
                String appId = getAppId();
                Path overridePath = Path.of(System.getProperty("user.home"),
                        ".local/share/flatpak/overrides", appId);
                if (!Files.exists(overridePath))
                    return;
                List<String> lines = Files.readAllLines(overridePath);
                List<String> fixed = lines.stream().map(line -> {
                    if (!line.startsWith("filesystems="))
                        return line;
                    String entries = line.substring("filesystems=".length());
                    String cleaned = Arrays.stream(entries.split(";"))
                            .filter(e -> !e.isEmpty() && !e.startsWith("/run/") && !e.startsWith("!/run/"))
                            .collect(Collectors.joining(";"));
                    return "filesystems=" + (cleaned.isEmpty() ? "" : cleaned + ";");
                }).collect(Collectors.toList());
                if (!fixed.equals(lines))
                    Files.write(overridePath, fixed);
            } catch (Exception e) {
                System.err.println("Warning: failed to clean portal paths from Flatpak overrides: " + e);
            }
        }

        private static void persistFlatpakPermission(String path) throws Exception {
            if (path.startsWith("/run/"))
                throw new IllegalArgumentException("Refusing to persist portal FUSE path as Flatpak override: " + path);
            // flatpak override must run on the host, not inside the sandbox.
            // Requires --talk-name=org.freedesktop.Flatpak in the Flatpak manifest.
            new ProcessBuilder(
                    "flatpak-spawn", "--host",
                    "flatpak", "override", "--user",
                    "--filesystem=" + path,
                    getAppId()
            ).start().waitFor();
        }

        private static String getAppId() {
            String envId = System.getenv("FLATPAK_ID");
            if (envId != null && !envId.isEmpty())
                return envId;
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
