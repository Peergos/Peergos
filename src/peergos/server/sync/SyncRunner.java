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
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public interface SyncRunner {

    void start();

    class ThreadBased implements SyncRunner {
        private static final Logger LOG = Logging.LOG();
        private final Thread runner;
        private final AtomicBoolean started = new AtomicBoolean(false);

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
                    Args updated = Args.parse(new String[]{"-run-once", "true"}, Optional.of(peergosDir.resolve("config")), false);
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
                                DirectorySync.syncDir(links, localDirs, syncLocalDeletes, syncRemoteDeletes,
                                        maxDownloadParallelism, minFreeSpacePercent, true, peergosDir, DirectorySync::log, network, crypto);
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
    }
}
