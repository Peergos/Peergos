package peergos.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
                    persistFlatpakPermission(selectedDir);
                    res.complete(selectedDir);
                }
            } catch (Exception e) {
                res.completeExceptionally(e);
            }
            return res;
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

        private static String getAppId() {
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
