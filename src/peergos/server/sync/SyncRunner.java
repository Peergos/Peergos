package peergos.server.sync;

import peergos.server.util.Args;
import peergos.server.util.Logging;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.corenode.CoreNode;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.mutable.MutablePointers;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.storage.RetryStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static peergos.server.net.SyncConfigHandler.OLD_SYNC_CONFIG_FILENAME;
import static peergos.server.net.SyncConfigHandler.SYNC_CONFIG_FILENAME;

public interface SyncRunner {

    void start();

    void runNow();

    /** @param localDirs the local dirs known to have changed; empty means sync everything */
    default void runNow(Set<String> localDirs) {
        runNow();
    }

    StatusHolder getStatusHolder();

    class StatusHolder {
        private String status;
        private LocalDateTime updateTime;
        private Optional<String> error = Optional.empty();
        private SyncStatus state = SyncStatus.SYNCED;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        // a pass clears cancelled as it aborts, so holding a pause needs its own flag
        private final AtomicBoolean paused = new AtomicBoolean(false);

        public synchronized void cancel() {
            cancelled.set(true);
        }

        public synchronized void resume() {
            cancelled.set(false);
        }

        public synchronized boolean isCancelled() {
            return cancelled.get();
        }

        /** Stops the pass in flight and the scheduled ones until unpause(). */
        public synchronized void pause() {
            paused.set(true);
            cancel();
        }

        public synchronized void unpause() {
            paused.set(false);
            resume();
        }

        public synchronized boolean isPaused() {
            return paused.get();
        }

        public synchronized void setStatus(String newStatus) {
            status = newStatus;
            updateTime = LocalDateTime.now();
        }

        public synchronized void setError(String error) {
            this.error = error == null || error.isEmpty() ?
                    Optional.empty() :
                    Optional.of(error);
        }

        public synchronized void setStatus(SyncStatus newState) {
            state = newState;
        }

        public synchronized SyncStatus getStatus() {
            return state;
        }

        public synchronized String getStatusAndTime() {
            if (status == null)
                return "";
            return status + " at " + updateTime.toLocalDate() + " " + updateTime.toLocalTime().withNano(0);
        }

        public synchronized Optional<String> getError() {
            return error;
        }
    }

    class ThreadBased implements SyncRunner {
        private static final Logger LOG = Logging.LOG();
        private final Thread runner;
        private final SyncDirWatcher watcher; // null when disabled, or on Android
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean inPass = new AtomicBoolean(false);
        private final AtomicBoolean rerun = new AtomicBoolean(false);
        private final Semaphore wake = new Semaphore(0);
        /** Local dirs with unsynced local changes. Unused while we sync every pair on each
         *  pass, but populated so per-pair syncing is a change to the consumer, not the producers. */
        private final Set<String> dirtyRoots = ConcurrentHashMap.newKeySet();
        private final AtomicLong passes = new AtomicLong(0);
        private final StatusHolder status = new StatusHolder();
        private volatile boolean running = true;

        public ThreadBased(Args args,
                           ContentAddressedStorage storage,
                           MutablePointers mutable,
                           CoreNode core,
                           Crypto crypto) {

            NetworkAccess network = NetworkAccess.buildBuffered(new RetryStorage(storage, 5), null, core, null,
                    mutable, 5_000, null, null, null, null,
                    crypto.hasher, Collections.emptyList(), false);
            // constructing this is inert: no thread and no WatchService until start()
            this.watcher = args.getBoolean("sync-watch-local-dirs", true) && ! isAndroid() ?
                    new SyncDirWatcher(this::runNow,
                            args.getInt("sync-watch-debounce-ms", 2_000),
                            args.getInt("sync-watch-min-interval-ms", 10_000)) :
                    null;
            this.runner = new Thread(() -> {
                while (running) {
                    if (status.isPaused()) {
                        // a paused sync must not keep paying for the watcher, which on macOS
                        // re-lists every registered dir every 2s; the reconcile below re-adds
                        // the roots on the first pass after unpausing
                        if (watcher != null)
                            watcher.watch(Collections.emptyList());
                        awaitWake();
                        continue;
                    }
                    // any trigger from here on is about state this pass hasn't looked at yet
                    wake.drainPermits();
                    dirtyRoots.clear();
                    passes.incrementAndGet();
                    inPass.set(true);
                    try {
                        Path peergosDir = args.getPeergosDir();
                        Path jsonSyncConfig = peergosDir.resolve(SYNC_CONFIG_FILENAME);
                        Path oldSyncConfig = peergosDir.resolve(OLD_SYNC_CONFIG_FILENAME);
                        SyncConfig syncConfig = Files.exists(jsonSyncConfig) ?
                                SyncConfig.fromJson((Map<String, Object>) JSONParser.parse(Files.readString(jsonSyncConfig))) :
                                SyncConfig.fromArgs(Args.parse(new String[]{"-run-once", "true"}, Optional.of(oldSyncConfig), false));
                        if (watcher != null)
                            watcher.watch(syncConfig.links.isEmpty() ?
                                    Collections.emptyList() :
                                    syncConfig.localDirs);
                        if (! syncConfig.links.isEmpty()) {
                            List<String> links = syncConfig.links;
                            List<String> localDirs = syncConfig.localDirs;
                            List<Boolean> syncLocalDeletes = syncConfig.syncLocalDeletes;
                            List<Boolean> syncRemoteDeletes = syncConfig.syncRemoteDeletes;
                            int maxDownloadParallelism = syncConfig.maxDownloadParallelism;
                            int minFreeSpacePercent = syncConfig.minFreeSpacePercent;
                            Consumer<String> statusUpdater = msg -> {
                                status.setStatus(msg);
                                DirectorySync.log(msg);
                            };
                            Consumer<Throwable> errorUpdater = e -> {
                                if (e != null) {
                                    status.setError(e.getMessage());
                                    DirectorySync.log(e.getMessage());
                                }
                            };
                            status.setError(null);
                            DirectorySync.syncDirs(links, localDirs, syncLocalDeletes, syncRemoteDeletes,
                                    maxDownloadParallelism, minFreeSpacePercent, true,
                                    root -> new LocalFileSystem(Paths.get(root), crypto.hasher),
                                    peergosDir, status, statusUpdater, errorUpdater, network.clear(), crypto);
                        } else {
                            // delete stale async state dbs
                            try (Stream<Path> kids = Files.list(peergosDir)) {
                                kids
                                        .filter(p -> p.getFileName().endsWith(".sqlite"))
                                        .filter(p -> p.getFileName().startsWith("dir-sync-state-v3-"))
                                        .forEach(p -> {
                                            try {
                                                Files.delete(p);
                                            } catch (IOException e) {
                                                e.printStackTrace();
                                            }
                                        });
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, e.getMessage(), e);
                    } finally {
                        inPass.set(false);
                    }
                    if (rerun.getAndSet(false))
                        continue;
                    awaitWake();
                }
            });
        }

        static boolean isAndroid() {
            return "The Android Project".equals(System.getProperty("java.vm.vendor"));
        }

        private void awaitWake() {
            try {
                wake.tryAcquire(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {}
        }

        @Override
        public void start() {
            if (started.compareAndSet(false, true)) {
                if (watcher != null) {
                    watcher.start();
                    Runtime.getRuntime().addShutdownHook(new Thread(watcher::close, "peergos sync watcher close"));
                }
                runner.start();
            } else
                wake();
        }

        /** A permit rather than an interrupt: interrupting is only safe while the thread is
         *  asleep, and a watcher fires far too often to rely on catching it there. Asking for
         *  a rerun as well picks up a change made during a pass as soon as it unwinds, rather
         *  than a sleep later. */
        private void wake() {
            if (inPass.get())
                rerun.set(true);
            wake.release();
        }

        @Override
        public void runNow() {
            runNow(Collections.emptySet());
        }

        @Override
        public void runNow(Set<String> localDirs) {
            // the user's sync-now unpauses first, so only the watcher is stopped here
            if (status.isPaused())
                return;
            dirtyRoots.addAll(localDirs);
            wake();
        }

        public void close() {
            running = false;
            wake.release();
            if (watcher != null)
                watcher.close();
        }

        /** Sync passes started since launch, including the one in flight. */
        public long getPassCount() {
            return passes.get();
        }

        @Override
        public StatusHolder getStatusHolder() {
            return status;
        }
    }
}
