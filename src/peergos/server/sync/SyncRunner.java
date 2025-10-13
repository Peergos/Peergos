package peergos.server.sync;

import org.peergos.config.Jsonable;
import peergos.server.util.Args;
import peergos.server.util.Logging;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.corenode.CoreNode;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.mutable.MutablePointers;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.MutableTreeImpl;
import peergos.shared.user.WriteSynchronizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static peergos.server.net.SyncConfigHandler.OLD_SYNC_CONFIG_FILENAME;
import static peergos.server.net.SyncConfigHandler.SYNC_CONFIG_FILENAME;

public interface SyncRunner {

    void start();

    void runNow();

    StatusHolder getStatusHolder();

    class StatusHolder {
        private String status;
        private LocalDateTime updateTime;
        private Optional<String> error = Optional.empty();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        public synchronized void cancel() {
            cancelled.set(true);
        }

        public synchronized void resume() {
            cancelled.set(false);
        }

        public synchronized boolean isCancelled() {
            return cancelled.get();
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
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final StatusHolder status = new StatusHolder();

        public ThreadBased(Args args,
                           ContentAddressedStorage storage,
                           MutablePointers mutable,
                           CoreNode core,
                           Crypto crypto) {

            WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, storage, crypto.hasher);
            MutableTreeImpl tree = new MutableTreeImpl(mutable, storage, crypto.hasher, synchronizer);
            NetworkAccess network = new NetworkAccess(core, null, null, storage, null, Optional.empty(),
                    mutable, tree, synchronizer, null, null, null, crypto.hasher,
                    Collections.emptyList(), false);
            this.runner = new Thread(() -> {
                while (true) {
                    try {
                        Path peergosDir = args.getPeergosDir();
                        Path jsonSyncConfig = peergosDir.resolve(SYNC_CONFIG_FILENAME);
                        Path oldSyncConfig = peergosDir.resolve(OLD_SYNC_CONFIG_FILENAME);
                        SyncConfig syncConfig = Files.exists(jsonSyncConfig) ?
                                SyncConfig.fromJson((Map<String, Object>) JSONParser.parse(Files.readString(jsonSyncConfig))) :
                                SyncConfig.fromArgs(Args.parse(new String[]{"-run-once", "true"}, Optional.of(oldSyncConfig), false));
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
                    }
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {}
                }
            });
        }

        @Override
        public void start() {
            if (! started.get()) {
                runner.start();
                started.set(true);
            } else
                runner.interrupt();
        }

        @Override
        public void runNow() {
            runner.interrupt();
        }

        @Override
        public StatusHolder getStatusHolder() {
            return status;
        }
    }
}
