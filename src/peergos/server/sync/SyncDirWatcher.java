package peergos.server.sync;

import peergos.server.util.Logging;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Watches the local side of every sync pair so a local change starts a sync in seconds
 *  rather than at the next 30s tick.
 *
 *  Purely a latency optimisation: the mechanism and its resolution differ wildly between
 *  platforms (inotify on linux, ReadDirectoryChangesW on windows, 2s polling on macOS) and
 *  events are missed entirely for changes made by another machine on a network mount, so
 *  nothing about correctness may depend on a trigger arriving.
 */
public class SyncDirWatcher implements AutoCloseable {
    private static final Logger LOG = Logging.LOG();

    private static final WatchEvent.Kind<?>[] KINDS = {
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE};

    /** Each registered dir on macOS is re-listed every 2s by PollingWatchService, so a whole
     *  tree there costs far more than the sync it is accelerating. Everywhere else a watch is
     *  idle, and the cap is only to avoid being the process that exhausts inotify watches. */
    private static final int MAX_DIRS_PER_ROOT = isMac() ? 1_000 : 10_000;

    /** Windows watches an entire subtree from one registration, so there is nothing to recurse. */
    private static final WatchEvent.Modifier FILE_TREE = fileTreeModifier();

    private final Consumer<Set<String>> trigger;
    private final long debounceMs, minIntervalMs;

    private final Map<WatchKey, String> keyToRoot = new HashMap<>();
    private final Map<String, List<WatchKey>> rootKeys = new LinkedHashMap<>();
    private final Set<String> unwatchableRoots = new HashSet<>();
    private final Set<String> cappedRoots = new HashSet<>();
    private final Set<String> pendingRoots = new LinkedHashSet<>();
    private WatchService watchService;
    private Thread thread;
    private volatile boolean running;
    private long lastEventMillis, lastTriggerMillis;

    /** @param trigger called with the local dirs that have pending changes, never empty */
    public SyncDirWatcher(Consumer<Set<String>> trigger, long debounceMs, long minIntervalMs) {
        this.trigger = trigger;
        this.debounceMs = debounceMs;
        this.minIntervalMs = minIntervalMs;
    }

    public synchronized void start() {
        if (thread != null)
            return;
        running = true;
        thread = new Thread(this::run, "peergos sync dir watcher");
        thread.setDaemon(true);
        thread.start();
    }

    /** Reconcile the registrations against the currently configured local dirs: register any
     *  new root, drop any that has gone. Idempotent, and never throws: a root we cannot watch
     *  (a SAF URI on android, an unplugged drive) just keeps syncing on the 30s tick. */
    public synchronized void watch(List<String> localDirs) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String localDir : localDirs) {
            if (toDir(localDir) != null) {
                wanted.add(localDir);
                unwatchableRoots.remove(localDir);
            } else if (unwatchableRoots.add(localDir))
                LOG.info("Not watching local sync dir for changes: " + localDir);
        }

        for (Iterator<Map.Entry<String, List<WatchKey>>> it = rootKeys.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, List<WatchKey>> e = it.next();
            if (wanted.contains(e.getKey()))
                continue;
            for (WatchKey key : e.getValue()) {
                key.cancel();
                keyToRoot.remove(key);
            }
            pendingRoots.remove(e.getKey());
            cappedRoots.remove(e.getKey());
            it.remove();
        }

        for (String root : wanted) {
            if (rootKeys.containsKey(root))
                continue;
            if (! ensureWatchService())
                return;
            rootKeys.put(root, new ArrayList<>());
            registerRoot(root, toDir(root));
            if (rootKeys.get(root).isEmpty())
                rootKeys.remove(root);
        }
    }

    @Override
    public void close() {
        running = false;
        Thread t;
        synchronized (this) {
            t = thread;
            thread = null;
            keyToRoot.clear();
            rootKeys.clear();
            pendingRoots.clear();
            if (watchService != null) {
                try {
                    watchService.close();
                } catch (IOException e) {
                    LOG.log(Level.WARNING, e.getMessage(), e);
                }
                watchService = null;
            }
        }
        if (t != null)
            t.interrupt();
    }

    private void run() {
        while (running) {
            WatchService ws;
            synchronized (this) {
                ws = watchService;
            }
            try {
                if (ws == null)
                    Thread.sleep(200);
                else {
                    WatchKey key = ws.poll(500, TimeUnit.MILLISECONDS);
                    if (key != null) {
                        handle(key);
                        key.reset();
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (ClosedWatchServiceException e) {
                if (! running)
                    return;
            } catch (Exception e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
            }
            maybeTrigger();
        }
    }

    private void handle(WatchKey key) {
        String root;
        synchronized (this) {
            root = keyToRoot.get(key);
        }
        Path dir = key.watchable() instanceof Path ? (Path) key.watchable() : null;
        boolean fire = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                fire = true;
                continue;
            }
            if (! (event.context() instanceof Path)) {
                fire = true;
                continue;
            }
            // with FILE_TREE this is relative to the root, not just a filename
            Path name = (Path) event.context();
            if (DirectorySync.IGNORED_FILENAMES.contains(name.getFileName().toString()))
                continue;
            Path changed = dir == null ? null : dir.resolve(name);
            // a mv of a populated tree into the sync dir gives one CREATE, for its top dir only
            if (root != null && changed != null && FILE_TREE == null
                    && event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                    && Files.isDirectory(changed, LinkOption.NOFOLLOW_LINKS))
                synchronized (this) {
                    if (rootKeys.containsKey(root))
                        registerRecursive(root, changed);
                }
            fire = true;
        }
        if (! fire)
            return;
        synchronized (this) {
            // a key cancelled concurrently with a reconcile has no root left: sync everything
            // rather than drop the change
            if (root != null)
                pendingRoots.add(root);
            else
                pendingRoots.addAll(rootKeys.keySet());
            lastEventMillis = System.currentTimeMillis();
        }
    }

    private void maybeTrigger() {
        Set<String> roots;
        synchronized (this) {
            if (pendingRoots.isEmpty())
                return;
            long now = System.currentTimeMillis();
            // let a big copy settle rather than syncing once per file, and never trigger
            // more often than minIntervalMs however busy the filesystem is
            if (now - lastEventMillis < debounceMs || now - lastTriggerMillis < minIntervalMs)
                return;
            roots = new HashSet<>(pendingRoots);
            pendingRoots.clear();
            lastTriggerMillis = now;
        }
        try {
            trigger.accept(roots);
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
    }

    private synchronized boolean ensureWatchService() {
        // lazy, so a runner with no sync pairs costs no WatchService at all
        if (watchService != null)
            return true;
        try {
            watchService = FileSystems.getDefault().newWatchService();
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not watch local sync dirs for changes: " + e.getMessage(), e);
            return false;
        }
    }

    private void registerRoot(String root, Path dir) {
        if (FILE_TREE != null) {
            try {
                record(root, dir.register(watchService, KINDS, FILE_TREE));
                return;
            } catch (Exception e) {
                LOG.info("FILE_TREE watch of " + dir + " failed, falling back to recursion: " + e.getMessage());
            }
        }
        registerRecursive(root, dir);
    }

    private void registerRecursive(String root, Path start) {
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // walkFileTree doesn't follow links, so a symlinked dir arrives as a file:
                    // matching applyToSubtree, which never leaves the sync root either
                    if (rootKeys.get(root).size() >= MAX_DIRS_PER_ROOT) {
                        if (cappedRoots.add(root))
                            LOG.info("Only watching the first " + MAX_DIRS_PER_ROOT + " dirs of " + root
                                    + " for changes, the rest sync on the 30s timer");
                        return FileVisitResult.TERMINATE;
                    }
                    try {
                        record(root, dir.register(watchService, KINDS));
                    } catch (Exception e) {
                        LOG.info("Could not watch " + dir + " for changes: " + e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            LOG.info("Could not watch " + start + " for changes: " + e.getMessage());
        }
    }

    private void record(String root, WatchKey key) {
        keyToRoot.put(key, root);
        rootKeys.get(root).add(key);
    }

    private static Path toDir(String localDir) {
        try {
            Path p = Paths.get(localDir);
            return Files.isDirectory(p) ? p : null;
        } catch (Exception e) { // e.g. a content:// URI on android
            return null;
        }
    }

    private static WatchEvent.Modifier fileTreeModifier() {
        if (! System.getProperty("os.name").toLowerCase().contains("win"))
            return null;
        try { // jdk.unsupported, so reflectively: falling back to recursion is fine
            Class<?> c = Class.forName("com.sun.nio.file.ExtendedWatchEventModifier");
            for (Object constant : c.getEnumConstants())
                if ("FILE_TREE".equals(((Enum<?>) constant).name()))
                    return (WatchEvent.Modifier) constant;
        } catch (Throwable t) {}
        return null;
    }

    private static boolean isMac() {
        return System.getProperty("os.name").toLowerCase().contains("mac");
    }
}
