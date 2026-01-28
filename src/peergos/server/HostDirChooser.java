package peergos.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
                        "--title=\"Select folder to sync with Peergos\""
                );
                Process p = pb.start();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream())
                );

                String selectedDir = r.readLine();
                p.waitFor();

                if (selectedDir != null && !selectedDir.isEmpty()) {
                    res.complete(selectedDir);
                }
            } catch (Exception e) {
                res.completeExceptionally(e);
            }
            return res;
        }
    }
}
