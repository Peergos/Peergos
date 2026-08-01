package peergos.server;

import java.io.BufferedReader;
import java.io.InputStream;
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
        /** Zenity's exit code when the user closes the dialog without choosing anything. */
        private static final int CANCELLED = 1;
        /** The shell's exit code for a command that isn't installed. */
        private static final int COMMAND_NOT_FOUND = 127;

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
                // drain stderr on another thread so a chatty toolkit can't fill the pipe and block us
                StringBuilder stderr = new StringBuilder();
                Thread errorDrain = drain(p.getErrorStream(), stderr);
                String selectedDir;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    selectedDir = r.readLine();
                }
                int exitCode = p.waitFor();
                errorDrain.join();

                if (selectedDir != null && !selectedDir.isEmpty()) {
                    persistFlatpakPermission(selectedDir);
                    res.complete(selectedDir);
                } else if (exitCode == 0 || exitCode == CANCELLED) {
                    res.complete(""); // the user closed the picker without choosing a folder
                } else {
                    res.completeExceptionally(new IllegalStateException(pickerFailure(exitCode, stderr.toString())));
                }
            } catch (Exception e) {
                res.completeExceptionally(e);
            }
            return res;
        }

        private static String pickerFailure(int exitCode, String stderr) {
            String detail = stderr.isBlank() ? "" : " (" + stderr.trim() + ")";
            if (exitCode == COMMAND_NOT_FOUND)
                return "Couldn't open the folder picker: zenity isn't installed on the host system." +
                        " Install it and try again, e.g. 'sudo apt install zenity' or 'sudo pacman -S zenity'." + detail;
            return "Couldn't open the folder picker: zenity exited with code " + exitCode + "." +
                    " Check that zenity is installed and working on the host system." + detail;
        }

        private static Thread drain(InputStream in, StringBuilder into) {
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
                    String line;
                    while ((line = r.readLine()) != null)
                        into.append(line).append("\n");
                } catch (Exception ignored) {}
            });
            t.setDaemon(true);
            t.start();
            return t;
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
