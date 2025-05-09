package peergos.server;

import peergos.shared.util.Futures;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface HostDirEnumerator {

    CompletableFuture<List<String>> getHostDirs(String prefix);

    class Java implements HostDirEnumerator {
        @Override
        public CompletableFuture<List<String>> getHostDirs(String prefix) {
            Set<String> roots = Set.of(System.getProperty("user.home"));
            List<String> res = new ArrayList<>();
            for (String root : roots) {
                if (root.startsWith(prefix)) {
                    File dir = new File(root);
                    recurse(dir, res);
                } else if (prefix.startsWith(root)) {
                    File dir = new File(prefix);
                    recurse(dir, res);
                }
            }
            return Futures.of(res);
        }

        private void recurse(File dir, List<String> res) {
            File[] kids = dir.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    if (kid.isDirectory() && ! kid.isHidden()) {
                        res.add(kid.getAbsolutePath());
                        recurse(kid, res);
                    }
                }
            }
        }
    }
}
