package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.sync.SyncDirWatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The watcher is independent of the sync machinery, so these need no network and no peergos dir.
 *
 *  Timeouts are generous on purpose: macOS still polls on a 2s schedule (so "exactly one trigger"
 *  needs a window comfortably over 2s), and windows CI is several times slower than a dev machine.
 */
public class SyncWatcherTests {

    private static final long DEBOUNCE = 1_000;
    private static final long MIN_INTERVAL = 100;
    private static final long TRIGGER_TIMEOUT = 30_000;
    /** long enough to catch a second trigger arriving after the first, on all three platforms */
    private static final long SETTLE = 6_000;

    private static class Triggers implements java.util.function.Consumer<Set<String>> {
        private final List<Set<String>> received = new ArrayList<>();

        @Override
        public synchronized void accept(Set<String> roots) {
            Assert.assertFalse("watcher triggered with no roots", roots.isEmpty());
            received.add(roots);
        }

        public synchronized int count() {
            return received.size();
        }

        public synchronized Set<String> allRoots() {
            Set<String> res = new HashSet<>();
            received.forEach(res::addAll);
            return res;
        }

        public void awaitTrigger() throws InterruptedException {
            long end = System.currentTimeMillis() + TRIGGER_TIMEOUT;
            while (System.currentTimeMillis() < end) {
                if (count() > 0)
                    return;
                Thread.sleep(100);
            }
            throw new AssertionError("No sync trigger within " + TRIGGER_TIMEOUT + "ms");
        }
    }

    private static SyncDirWatcher start(Triggers triggers, List<Path> roots) {
        SyncDirWatcher watcher = new SyncDirWatcher(triggers, DEBOUNCE, MIN_INTERVAL);
        watcher.start();
        watcher.watch(roots.stream().map(Path::toString).collect(java.util.stream.Collectors.toList()));
        return watcher;
    }

    private static void write(Path file) throws IOException {
        Files.write(file, "data".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void singleChangeTriggersOnce() throws Exception {
        Path root = Files.createTempDirectory("peergos-sync-watch");
        Triggers triggers = new Triggers();
        SyncDirWatcher watcher = start(triggers, List.of(root));
        try {
            write(root.resolve("file.bin"));
            triggers.awaitTrigger();
            Thread.sleep(SETTLE);
            Assert.assertEquals(1, triggers.count());
            Assert.assertEquals(Set.of(root.toString()), triggers.allRoots());
        } finally {
            watcher.close();
        }
    }

    @Test
    public void burstIsCoalesced() throws Exception {
        Path root = Files.createTempDirectory("peergos-sync-watch");
        Triggers triggers = new Triggers();
        SyncDirWatcher watcher = start(triggers, List.of(root));
        try {
            for (int i = 0; i < 50; i++)
                write(root.resolve("file-" + i + ".bin"));
            triggers.awaitTrigger();
            Thread.sleep(SETTLE);
            Assert.assertEquals(1, triggers.count());
        } finally {
            watcher.close();
        }
    }

    @Test
    public void newSubdirIsWatched() throws Exception {
        Path root = Files.createTempDirectory("peergos-sync-watch");
        Triggers triggers = new Triggers();
        SyncDirWatcher watcher = start(triggers, List.of(root));
        try {
            Path subdir = root.resolve("subdir");
            Files.createDirectory(subdir);
            triggers.awaitTrigger();
            Thread.sleep(SETTLE);
            int afterDir = triggers.count();

            write(subdir.resolve("nested.bin"));
            long end = System.currentTimeMillis() + TRIGGER_TIMEOUT;
            while (System.currentTimeMillis() < end && triggers.count() == afterDir)
                Thread.sleep(100);
            Assert.assertTrue("no trigger for a file in a newly created subdir",
                    triggers.count() > afterDir);
        } finally {
            watcher.close();
        }
    }

    @Test
    public void deleteTriggers() throws Exception {
        Path root = Files.createTempDirectory("peergos-sync-watch");
        Path file = root.resolve("file.bin");
        write(file);
        Triggers triggers = new Triggers();
        SyncDirWatcher watcher = start(triggers, List.of(root));
        try {
            Files.delete(file);
            triggers.awaitTrigger();
        } finally {
            watcher.close();
        }
    }

    @Test
    public void unwatchableRootIsIgnored() throws Exception {
        Path root = Files.createTempDirectory("peergos-sync-watch");
        Path absent = root.resolveSibling("peergos-sync-watch-does-not-exist");
        Triggers triggers = new Triggers();
        SyncDirWatcher watcher = new SyncDirWatcher(triggers, DEBOUNCE, MIN_INTERVAL);
        watcher.start();
        try {
            // a SAF content URI and a dir the user unplugged both land here
            watcher.watch(List.of("content://com.android.externalstorage.documents/tree/primary",
                    absent.toString(), root.toString()));
            write(root.resolve("file.bin"));
            triggers.awaitTrigger();
            Assert.assertEquals(Set.of(root.toString()), triggers.allRoots());
        } finally {
            watcher.close();
        }
    }

    @Test
    public void removedRootStopsTriggering() throws Exception {
        Path kept = Files.createTempDirectory("peergos-sync-watch");
        Path removed = Files.createTempDirectory("peergos-sync-watch");
        Triggers triggers = new Triggers();
        SyncDirWatcher watcher = start(triggers, List.of(kept, removed));
        try {
            watcher.watch(List.of(kept.toString()));
            write(removed.resolve("file.bin"));
            Thread.sleep(SETTLE);
            Assert.assertEquals("changes in a removed root still trigger", 0, triggers.count());

            write(kept.resolve("file.bin"));
            triggers.awaitTrigger();
            Assert.assertEquals(Set.of(kept.toString()), triggers.allRoots());
        } finally {
            watcher.close();
        }
    }

    @Test
    public void triggerNamesOnlyTheChangedRoot() throws Exception {
        Path changed = Files.createTempDirectory("peergos-sync-watch");
        Path quiet = Files.createTempDirectory("peergos-sync-watch");
        Triggers triggers = new Triggers();
        SyncDirWatcher watcher = start(triggers, List.of(changed, quiet));
        try {
            write(changed.resolve("file.bin"));
            triggers.awaitTrigger();
            Thread.sleep(SETTLE);
            Assert.assertEquals(Set.of(changed.toString()), triggers.allRoots());
        } finally {
            watcher.close();
        }
    }
}
