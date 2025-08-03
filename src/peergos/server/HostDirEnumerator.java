package peergos.server;

import peergos.shared.util.Futures;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface HostDirEnumerator {

    CompletableFuture<List<String>> getHostDirs(String prefix, int maxDepth);

    class Java implements HostDirEnumerator {
        @Override
        public CompletableFuture<List<String>> getHostDirs(String prefix, int mathDepthFromPrefix) {
            Set<String> roots = Stream.concat(
                            Stream.of(System.getProperty("user.home")),
                            Arrays.stream(File.listRoots())
                                    .map(f -> f.toPath().toString()))
                    .collect(Collectors.toSet());
            List<String> res = new ArrayList<>();
            for (String root : roots) {
                if (root.startsWith(prefix)) {
                    File dir = new File(root);
                    recurse(dir, res, mathDepthFromPrefix - Paths.get(root).getNameCount());
                } else if (prefix.startsWith(root)) {
                    File dir = new File(prefix);
                    recurse(dir, res, mathDepthFromPrefix - Paths.get(prefix).getNameCount());
                }
                if (res.isEmpty() && ! root.startsWith(prefix))
                    recurse(new File(root), res, mathDepthFromPrefix - Paths.get(root).getNameCount());
            }
            return Futures.of(res);
        }

        private void recurse(File dir, List<String> res, int maxDepth) {
            if (maxDepth <= 0)
                return;
            File[] kids = dir.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    if (kid.isDirectory() && ! kid.isHidden()) {
                        res.add(kid.getAbsolutePath());
                        recurse(kid, res, maxDepth - 1);
                    }
                }
            }
        }
    }
}
