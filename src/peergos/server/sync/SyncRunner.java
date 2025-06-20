package peergos.server.sync;

import peergos.server.util.Args;
import peergos.server.util.Logging;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.corenode.CoreNode;
import peergos.shared.mutable.MutablePointers;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.MutableTreeImpl;
import peergos.shared.user.WriteSynchronizer;

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

import static peergos.server.net.SyncConfigHandler.SYNC_CONFIG_FILENAME;

public interface SyncRunner {

    void start();

    void runNow();

    StatusHolder getStatusHolder();

    class StatusHolder {
        private String status;
        private LocalDateTime updateTime;
        private Optional<String> error = Optional.empty();

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
            return status + " at " + updateTime.getYear() + "-" + updateTime.getMonthValue()+"-" +
                    updateTime.getDayOfMonth() + " " + updateTime.getHour() + ":" + updateTime.getMinute() + ":" + updateTime.getSecond();
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
                    Path peergosDir = args.getPeergosDir();
                    Args updated = Args.parse(new String[]{"-run-once", "true"}, Optional.of(peergosDir.resolve(SYNC_CONFIG_FILENAME)), false);
                    if (updated.hasArg("links")) {
                        List<String> links = new ArrayList<>(Arrays.asList(updated.getArg("links").split(",")));
                        List<String> localDirs = new ArrayList<>(Arrays.asList(updated.getArg("local-dirs").split(",")));
                        List<Boolean> syncLocalDeletes = updated.hasArg("sync-local-deletes") ?
                                new ArrayList<>(Arrays.stream(updated.getArg("sync-local-deletes").split(","))
                                        .map(Boolean::parseBoolean)
                                        .collect(Collectors.toList())) :
                                IntStream.range(0, links.size())
                                        .mapToObj(x -> true)
                                        .collect(Collectors.toList());
                        List<Boolean> syncRemoteDeletes = updated.hasArg("sync-remote-deletes") ?
                                new ArrayList<>(Arrays.stream(updated.getArg("sync-remote-deletes").split(","))
                                        .map(Boolean::parseBoolean)
                                        .collect(Collectors.toList())) :
                                IntStream.range(0, links.size())
                                        .mapToObj(x -> true)
                                        .collect(Collectors.toList());
                        int maxDownloadParallelism = updated.getInt("max-parallelism", 32);
                        int minFreeSpacePercent = updated.getInt("min-free-space-percent", 5);
                        if (!links.isEmpty()) {
                            try {
                                Consumer<String> statusUpdater = msg -> {
                                    status.setStatus(msg);
                                    DirectorySync.log(msg);
                                };
                                Consumer<String> errorUpdater = msg -> {
                                    status.setError(msg);
                                    DirectorySync.log(msg);
                                };
                                DirectorySync.syncDirs(links, localDirs, syncLocalDeletes, syncRemoteDeletes,
                                        maxDownloadParallelism, minFreeSpacePercent, true,
                                        root -> new LocalFileSystem(Paths.get(root), crypto.hasher),
                                        peergosDir, statusUpdater, errorUpdater, network, crypto);
                            } catch (Exception e) {
                                LOG.log(Level.WARNING, e.getMessage(), e);
                            }
                        }
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
