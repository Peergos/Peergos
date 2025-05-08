package peergos.server;

import peergos.shared.util.Futures;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface HostDirEnumerator {

    CompletableFuture<List<String>> getHostDirs(String prefix);

    class Java implements HostDirEnumerator {
        @Override
        public CompletableFuture<List<String>> getHostDirs(String prefix) {
            File dir = new File(prefix);
            List<String> res = new ArrayList<>();
            recurse(dir, res);
            return Futures.of(res);
        }

        private void recurse(File dir, List<String> res) {
            File[] kids = dir.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    if (kid.isDirectory()) {
                        res.add(kid.getAbsolutePath());
                        recurse(kid, res);
                    }
                }
            }
        }
    }
}
