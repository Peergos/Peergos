package peergos.server.sync;

import peergos.server.Builder;
import peergos.server.Main;
import peergos.server.UserService;
import peergos.server.net.ProxyChooser;
import peergos.server.storage.FileBlockCache;
import peergos.server.util.Args;
import peergos.server.util.JvmThumbnailer;
import peergos.server.util.Logging;
import peergos.server.util.Threads;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.corenode.HTTPCoreNode;
import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.crypto.hash.Hash;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.login.mfa.MultiFactorAuthMethod;
import peergos.shared.login.mfa.MultiFactorAuthRequest;
import peergos.shared.login.mfa.MultiFactorAuthResponse;
import peergos.shared.mutable.HttpMutablePointers;
import peergos.shared.social.HttpSocialNetwork;
import peergos.shared.storage.HttpSpaceUsage;
import peergos.shared.storage.MajorRateLimitException;
import peergos.shared.storage.RateLimitException;
import peergos.shared.storage.RetryStorage;
import peergos.shared.storage.UnauthedCachingStorage;
import peergos.shared.user.CommittedWriterData;
import peergos.shared.user.LinkProperties;
import peergos.shared.user.Snapshot;
import peergos.shared.user.TrieNodeImpl;
import peergos.shared.user.UserContext;
import peergos.shared.user.WriteSynchronizer;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.ProxySelector;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class DirectorySync {
    private static final Logger LOG = Logging.LOG();
    static Set<String> IGNORED_FILENAMES = Stream.of(".DS_Store")
            .collect(Collectors.toSet());

    private static void disableLogSpam() {
        // disable log spam
        TrieNodeImpl.disableLog();
        HttpMutablePointers.disableLog();
        NetworkAccess.disableLog();
        HTTPCoreNode.disableLog();
        HttpSocialNetwork.disableLog();
        HttpSpaceUsage.disableLog();
        FileUploader.disableLog();
        LazyInputStreamCombiner.disableLog();
    }

    public static boolean syncDir(Args args) {
        try {
            disableLogSpam();

            String address = args.getArg("peergos-url");
            URL serverURL = new URL(address);
            Crypto crypto = Main.initCrypto();
            PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, crypto.signer);
            String cacheSize = args.getArg("block-cache-size-bytes");
            long blockCacheSizeBytes = cacheSize.endsWith("g") ?
                    Long.parseLong(cacheSize.substring(0, cacheSize.length() - 1)) * 1024L * 1024 * 1024 :
                    Long.parseLong(cacheSize);
            Optional<ProxySelector> proxy = ProxyChooser.build(args);
            NetworkAccess network = Builder.buildJavaNetworkAccess(serverURL, address.startsWith("https"), Optional.of("Peergos-" + UserService.CURRENT_VERSION + "-sync"), proxy).join()
                    .withStorage(s -> new UnauthedCachingStorage(s, new FileBlockCache(args.fromPeergosDir("block-cache-dir", "block-cache"), blockCacheSizeBytes), crypto.hasher));
            JvmThumbnailer.initJava(args);
            List<String> links = new ArrayList<>(Arrays.asList(args.getArg("links").split(",")));
            List<String> localDirs = new ArrayList<>(Arrays.asList(args.getArg("local-dirs").split(",")));
            List<Boolean> syncLocalDeletes = args.hasArg("sync-local-deletes") ?
                    new ArrayList<>(Arrays.stream(args.getArg("sync-local-deletes").split(","))
                            .map(Boolean::parseBoolean)
                            .collect(Collectors.toList())) :
                    IntStream.range(0, links.size())
                            .mapToObj(x -> true)
                            .collect(Collectors.toList());
            List<Boolean> syncRemoteDeletes = args.hasArg("sync-remote-deletes") ?
                    new ArrayList<>(Arrays.stream(args.getArg("sync-remote-deletes").split(","))
                            .map(Boolean::parseBoolean)
                            .collect(Collectors.toList())) :
                    IntStream.range(0, links.size())
                            .mapToObj(x -> true)
                            .collect(Collectors.toList());
            int maxDownloadParallelism = args.getInt("max-parallelism", 32);
            int minFreeSpacePercent = args.getInt("min-free-space-percent", 5);
            boolean oneRun = args.getBoolean("run-once", false);
            Path peergosDir = args.getPeergosDir();
            return syncDirs(links, localDirs, syncLocalDeletes, syncRemoteDeletes, maxDownloadParallelism,
                    minFreeSpacePercent, oneRun, root -> new LocalFileSystem(Paths.get(root), crypto.hasher),
                    peergosDir, new SyncRunner.StatusHolder(), m -> log(m), e -> {if (e != null) log(e.getMessage());}, network, crypto);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, e, e == null ? () -> "" : e::getMessage);
            throw new RuntimeException(e);
        }
    }

    public static PeergosSyncFS buildRemote(String link,
                                            NetworkAccess network,
                                            Crypto crypto) {
        Supplier<CompletableFuture<String>> linkUserPassword = () -> Futures.of("");
        List<Supplier<CompletableFuture<String>>> linkPasswords = List.of(linkUserPassword);
        UserContext context = UserContext.fromSecretLinksV2(List.of(link), linkPasswords, network, crypto).join();
        Path path = PathUtil.get(context.getEntryPath().join());
        return new PeergosSyncFS(context, path);
    }

    public static Path getSyncStateDbPath(Path peergosDir, String linkPath, String localDir) {
        return peergosDir.resolve("dir-sync-state-v3-" + ArrayOps.bytesToHex(Hash.sha256(linkPath + "///" + localDir)) + ".sqlite");
    }

    private static <T> List<T> pick(List<T> all, List<Integer> indices) {
        return indices.stream().map(all::get).collect(Collectors.toList());
    }

    public static String pairHash(String linkPath, String localDir) {
        return PairLogger.hash(linkPath, localDir);
    }

    public static boolean syncDirs(List<String> allLinks,
                                   List<String> allLocalDirs, //could be paths or URIs
                                   List<Boolean> allSyncLocalDeletes,
                                   List<Boolean> allSyncRemoteDeletes,
                                   int maxDownloadParallelism,
                                   int minFreeSpacePercent,
                                   boolean oneRun,
                                   Function<String, SyncFilesystem> localBuilder,
                                   Path peergosDir,
                                   SyncRunner.StatusHolder status,
                                   Consumer<String> LOG,
                                   Consumer<Throwable> ERROR,
                                   NetworkAccess network,
                                   Crypto crypto) {
        return syncDirs(allLinks, allLocalDirs, allSyncLocalDeletes, allSyncRemoteDeletes, maxDownloadParallelism,
                minFreeSpacePercent, oneRun, localBuilder, peergosDir, status, LOG, ERROR, network, crypto, true);
    }

    /** @param everyPair these are all the configured pairs, so state belonging to any other
     *  pair is left over from a removed one. A pass that syncs a subset (only the folders
     *  allowed on mobile data, say) must say so, or it deletes the rest of their state. */
    public static boolean syncDirs(List<String> allLinks,
                                   List<String> allLocalDirs,
                                   List<Boolean> allSyncLocalDeletes,
                                   List<Boolean> allSyncRemoteDeletes,
                                   int maxDownloadParallelism,
                                   int minFreeSpacePercent,
                                   boolean oneRun,
                                   Function<String, SyncFilesystem> localBuilder,
                                   Path peergosDir,
                                   SyncRunner.StatusHolder status,
                                   Consumer<String> LOG,
                                   Consumer<Throwable> ERROR,
                                   NetworkAccess network,
                                   Crypto crypto,
                                   boolean everyPair) {
        if (allSyncLocalDeletes.size() != allLinks.size())
            throw new IllegalStateException("Incorrect number of sync-local-deletes!");
        if (allSyncRemoteDeletes.size() != allLinks.size())
            throw new IllegalStateException("Incorrect number of sync-remote-deletes!");

        List<String> linkPaths = new ArrayList<>();
        List<Integer> opened = new ArrayList<>();
        for (int i = 0; i < allLinks.size(); i++) {
            try {
                linkPaths.add(UserContext.fromSecretLinksV2(Arrays.asList(allLinks.get(i)),
                        Arrays.asList(() -> Futures.of("")), network, crypto).join().getEntryPath().join());
                opened.add(i);
            } catch (Exception e) {
                // one link the server will not open must not stop the other pairs syncing
                LOG.accept("Cannot open the link for " + allLocalDirs.get(i) + ": " + describeError(e));
                ERROR.accept(new PairFailure(i, e));
            }
        }
        // a pair whose link would not open is still configured, so its state is not stale
        boolean everyLinkOpened = opened.size() == allLinks.size();
        boolean completeSet = everyLinkOpened && everyPair;
        if (opened.isEmpty())
            return false;
        List<String> links = pick(allLinks, opened);
        List<String> localDirs = pick(allLocalDirs, opened);
        List<Boolean> syncLocalDeletes = pick(allSyncLocalDeletes, opened);
        List<Boolean> syncRemoteDeletes = pick(allSyncRemoteDeletes, opened);

        List<Path> syncDbPaths = IntStream.range(0, linkPaths.size())
                .mapToObj(i -> getSyncStateDbPath(peergosDir, linkPaths.get(i), localDirs.get(i)))
                .collect(Collectors.toList());

        // only tidy up when the pass has every pair and every link opened: the paths of a
        // pair it is missing are unknown, so its db and logs would look unreferenced
        if (completeSet) {
            try (Stream<Path> kids = Files.list(peergosDir)) {
                kids
                        .filter(p -> p.getFileName().toString().endsWith(".sqlite"))
                        .filter(p -> p.getFileName().toString().startsWith("dir-sync-state-v3-"))
                        .filter(p -> ! syncDbPaths.contains(p))
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

        List<String> pairHashes = IntStream.range(0, linkPaths.size())
                .mapToObj(i -> pairHash(linkPaths.get(i), localDirs.get(i)))
                .collect(Collectors.toList());
        List<PairLogger> pairLoggers = new ArrayList<>();
        List<PairStatus> pairStatuses = new ArrayList<>();
        for (String h : pairHashes) {
            try {
                pairLoggers.add(new PairLogger(peergosDir, h));
            } catch (IOException e) {
                pairLoggers.add(null);
            }
            pairStatuses.add(new PairStatus(peergosDir, h));
        }
        // delete logs/status for pairs that are no longer present
        Path syncLogsDir = PairLogger.logDir(peergosDir);
        if (completeSet && Files.exists(syncLogsDir)) {
            Set<String> keepHashes = new HashSet<>(pairHashes);
            try (Stream<Path> kids = Files.list(syncLogsDir)) {
                kids.filter(p -> {
                            String name = p.getFileName().toString();
                            return name.startsWith("sync-") && (name.endsWith(".log") || name.endsWith(".log.1") || name.endsWith(".status.json"));
                        })
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            String rest = name.substring("sync-".length());
                            int dot = rest.indexOf('.');
                            if (dot < 0) return false;
                            return ! keepHashes.contains(rest.substring(0, dot));
                        })
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

        List<Supplier<SyncState>> syncedStates = syncDbPaths.stream()
                .<Supplier<SyncState>>map(p -> () -> new JdbcTreeState(p.toString()))
                .collect(Collectors.toList());
        if (links.size() != localDirs.size())
            throw new IllegalArgumentException("Mismatched number of local dirs and links");

        boolean errored = false;
        while (true) {
            LOG.accept("Syncing " + links.size() + " pairs of directories: " + IntStream.range(0, links.size()).mapToObj(i -> Arrays.asList(localDirs.get(i), linkPaths.get(i))).collect(Collectors.toList()));
            errored = false;
            // a cancel that stopped the previous pass must not abort this one before it
            // syncs anything; a pause is held by its own flag, so this cannot resume one
            status.resume();
            status.setStatus(SyncStatus.SYNCING);
            for (int i=0; i < links.size(); i++) {
                SyncState syncedState = null;
                PairLogger pairLog = pairLoggers.get(i);
                PairStatus pairStatus = pairStatuses.get(i);
                Consumer<String> perPairLog = msg -> {
                    LOG.accept(msg);
                    if (pairLog != null)
                        pairLog.log(msg);
                    pairStatus.setStatus(msg);
                };
                try {
                    if (status.isCancelled()) {
                        status.resume();
                        return false;
                    }
                    Path localDir = Paths.get(localDirs.get(i));
                    Path remoteDir = PathUtil.get(linkPaths.get(i));
                    syncedState = syncedStates.get(i).get();
                    perPairLog.accept("Syncing " + localDir + " to+from " + remoteDir);
                    // else a stale failure is shown for the whole of a long transfer
                    pairStatus.setError(null);
                    pairStatus.setStatus(SyncStatus.SYNCING);
                    long t0 = System.currentTimeMillis();
                    String username = remoteDir.getName(0).toString();
                    PublicKeyHash owner = network.coreNode.getPublicKeyHash(username).join().get();
                    PeergosSyncFS remote = buildRemote(links.get(i), network, crypto);
                    boolean isAndroid = "The Android Project".equals(System.getProperty("java.vm.vendor"));
                    if (! isAndroid && ! Paths.get(localDirs.get(i)).toFile().exists()) {
                        boolean isFlatpak = System.getenv("FLATPAK_ID") != null;
                        perPairLog.accept(isFlatpak ?
                                "Restart Peergos to start syncing " + localDirs.get(i) :
                                "Local dir does not exist! Please remove and recreate the sync.");
                        pairStatus.setStatus(SyncStatus.ERROR);
                        errored = true;
                    } else {
                        SyncFilesystem local = localBuilder.apply(localDirs.get(i));
                        syncDir(local, remote, syncLocalDeletes.get(i), syncRemoteDeletes.get(i),
                                owner, network, syncedState, maxDownloadParallelism, minFreeSpacePercent, peergosDir,
                                crypto, status::isCancelled, perPairLog);
                        long t1 = System.currentTimeMillis();
                        perPairLog.accept("Dir sync took " + (t1 - t0) / 1000 + "s");
                        pairStatus.setError(null);
                        pairStatus.setStatus(SyncStatus.SYNCED);
                    }
                } catch (Exception e) {
                    if (status.isPaused() || status.isCancelled()) {
                        // stopped on request, so not a failure, and the folder is not synced
                        // either: it stopped part way. A pause keeps the progress it reached,
                        // which is what is worth seeing next to a paused sync; any other stop
                        // clears it, since a message like "Checking files in Drive" left behind
                        // reads as though the sync were still running
                        pairStatus.setError(null);
                        if (! status.isPaused()) {
                            // a stop with a reason is worth showing: a folder that stopped for
                            // the network needs the user to act, not to look idle
                            Optional<String> why = status.getStopReason();
                            if (why.isPresent()) {
                                pairStatus.setError(why.get());
                                pairStatus.setStatus(SyncStatus.ERROR);
                            } else
                                pairStatus.setStatus("");
                        }
                        if (pairLog != null)
                            pairLog.log(status.isPaused() ? "Sync paused" : "Sync stopped");
                        return false;
                    }
                    errored = true;
                    ERROR.accept(e);
                    if (pairLog != null)
                        pairLog.error(describeError(e));
                    pairStatus.setError(describeError(e));
                    pairStatus.setStatus(SyncStatus.ERROR);
                    e.printStackTrace();
                    DirectorySync.LOG.log(Level.WARNING, e, e::getMessage);
                } finally {
                    if (syncedState != null)
                        try {
                            syncedState.close();
                        } catch (IOException e) {
                            DirectorySync.LOG.log(Level.WARNING, e, e::getMessage);
                        }
                }
            }
            status.setStatus(errored ? SyncStatus.ERROR : SyncStatus.SYNCED);
            if (!errored)
                ERROR.accept(null);
            if (oneRun)
                break;
            Threads.sleep(30_000);
        }
        // the caller decides what to do about a folder that did not sync, so say whether
        // this pass left one behind
        return ! errored && everyLinkOpened;
    }

    public static boolean init(Args args) {
        disableLogSpam();
        Console console = System.console();
        String username = new String(console.readLine("Enter username:"));
        String password = new String(console.readPassword("Enter password:"));
        String address = args.getArg("peergos-url");
        try {
            URL serverURL = new URL(address);
            Optional<ProxySelector> proxy = ProxyChooser.build(args);
            NetworkAccess network = Builder.buildJavaNetworkAccess(serverURL, address.startsWith("https"), Optional.of("Peergos-" + UserService.CURRENT_VERSION + "-sync"), proxy).join();
            Crypto crypto = Main.initCrypto();
            UserContext context = UserContext.signIn(username, password, mfar -> mfa(mfar), network, crypto).join();
            String peergosPath = new String(console.readLine("Enter the peergos path you want to sync to (e.g. /$username/media/images):"));
            init(context, peergosPath);
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static LinkProperties init(UserContext context, String peergosPath) {
        try {
            Path toSync = PathUtil.get(peergosPath).normalize();
            if (toSync.getNameCount() < 2)
                throw new IllegalArgumentException("You cannot sync to your Peergos home directory, please make a sub-directory.");
            Optional<FileWrapper> dir = context.getByPath(toSync).join();
            if (dir.isEmpty())
                throw new IllegalArgumentException("Directory "+toSync+" does not exist in Peergos!");
            // ensure directory is in its own writing space
            if (dir.get().owner().equals(context.signer.publicKeyHash)) {
                // our file
                context.shareWriteAccessWith(toSync, Collections.emptySet()).join();
            } else {
                // something we have write access to
                if (! dir.get().isWritable())
                    throw new IllegalArgumentException("You do not have write access to this directory!");
            }
            LinkProperties link = context.createSecretLink(toSync.toString(), true, Optional.empty(), Optional.empty(), "", false).join();

            String cap = link.toLinkString(context.signer.publicKeyHash);

            System.out.println("Run the sync dir command on all devices you want to sync using the following args: -links " + cap + " -local-dirs $LOCAL_DIR");
            return link;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static CompletableFuture<MultiFactorAuthResponse> mfa(MultiFactorAuthRequest req) {
        Optional<MultiFactorAuthMethod> anyTotp = req.methods.stream().filter(m -> m.type == MultiFactorAuthMethod.Type.TOTP).findFirst();
        if (anyTotp.isEmpty())
            throw new IllegalStateException("No supported 2 factor auth method! " + req.methods);
        MultiFactorAuthMethod totp = anyTotp.get();
        Console console = System.console();
        String code = new String(console.readLine("Enter TOTP code for login:"));
        return Futures.of(new MultiFactorAuthResponse(totp.credentialId, Either.a(code)));
    }

    public static void syncDir(SyncFilesystem localFS,
                               SyncFilesystem remoteFS,
                               boolean syncLocalDeletes,
                               boolean syncRemoteDeletes,
                               PublicKeyHash owner,
                               NetworkAccess network,
                               SyncState syncedVersions,
                               int maxParallelism,
                               int minPercentFreeSpace,
                               Path peergosDir,
                               Crypto crypto,
                               Supplier<Boolean> isCancelled,
                               Consumer<String> LOG) throws IOException {
        // first complete any failed in progress copy ops
        List<CopyOp> ops = syncedVersions.getInProgressCopies();
        if (! ops.isEmpty())
            log("Rerunning failed copy operations...");
        Map<Pair<Boolean, Path>, List<CopyOp>> byTarget = ops.stream()
                .collect(Collectors.groupingBy(op -> new Pair<>(op.isLocalTarget, op.target), LinkedHashMap::new, Collectors.toList()));
        for (List<CopyOp> sameTarget : byTarget.values()) {
            CopyOp first = sameTarget.get(0);
            SyncFilesystem srcFs = first.isLocalTarget ? remoteFS : localFS;
            SyncFilesystem targetFs = first.isLocalTarget ? localFS : remoteFS;
            try {
                if (targetFs.completingCopy(first.target)) {
                    // all of the copy is here, and part of it already in the target, which only finishing makes whole
                    targetFs.completeCopy(first.target, ranges(sameTarget), first.sourceState.size);
                    syncedVersions.finishCopies(sameTarget);
                    continue;
                }
                // a source changed since would be copied with the range and hash of what it was, and a target changed
                // since is someone else's edit, so leave both to the sync below
                boolean sourceUnchanged = srcFs.exists(first.source) && srcFs.size(first.source) == first.sourceState.size &&
                        srcFs.getLastModified(first.source) == first.sourceState.modificationTime;
                if (targetFs.showsPartialCopies() || sourceUnchanged && (targetAsScanned(targetFs, first, syncedVersions) ||
                        writtenInPlace(targetFs, first))) {
                    // what has been written into the target is the start of this copy, not the state it expects
                    copy(srcFs, targetFs, sameTarget, false, syncedVersions, isCancelled, LOG);
                    continue;
                }
                if (srcFs.exists(first.source) && writtenInPlace(targetFs, first)) {
                    // what has been written is a mix of two versions that neither side made, so put all of the source
                    // as it is now in its place
                    LOG.accept("Sync: redoing an interrupted " + (first.isLocalTarget ? "download" : "upload") + " of " +
                            first.sourceState.relPath + ", as it has changed since");
                    long size = srcFs.size(first.source);
                    int chunkSize = first.sourceState.chunkSize;
                    HashTree hash = srcFs.hashFile(first.source, Optional.empty(), first.sourceState.relPath, syncedVersions, size, chunkSize);
                    FileState now = new FileState(first.sourceState.relPath, srcFs.getLastModified(first.source), size, hash, chunkSize);
                    FileState written = new FileState(first.targetState.relPath, targetFs.getLastModified(first.target),
                            targetFs.size(first.target), first.targetState.hashTree, first.targetState.chunkSize);
                    syncedVersions.finishCopies(sameTarget);
                    List<CopyOp> redo = List.of(new CopyOp(first.isLocalTarget, first.source, first.target,
                            now, written, 0, size, ResumeUploadProps.random(crypto)));
                    syncedVersions.startCopies(redo);
                    copy(srcFs, targetFs, redo, false, syncedVersions, isCancelled, LOG);
                    continue;
                }
                LOG.accept("Sync: abandoning an interrupted " + (first.isLocalTarget ? "download" : "upload") + " of " +
                        first.sourceState.relPath + ", as it has changed since");
            } catch (Exception e) {
                Throwable cause = Exceptions.getRootCause(e);
                if (! (cause instanceof FileNotFoundException) && ! (e instanceof ConcurrentChangeException))
                    throw new RuntimeException(e);
                // A local file has been added and removed concurrently with us trying to copy it, ignore the copy op now
            }
            for (CopyOp op : sameTarget)
                targetFs.discardCopy(op);
            syncedVersions.finishCopies(sameTarget);
        }
        // anything left was written by a copy that has been forgotten, such as when the sync state is reset
        localFS.discardCopies();
        remoteFS.discardCopies();
        if (! syncedVersions.hasCompletedSync()) {
            // Do an incremental sync of only files to make progress quicker
            // We don't need to prehash entire dirs here,
            // because we are not trying to detect moves/renames
            // Enumerate the remote once here rather than probing it a file at a time. As well as
            // being far cheaper, it lets us record a synced entry for every file that already
            // matches remotely, whatever its size. Only recording the large ones would leave a
            // small file that is deleted locally before any sync completes indistinguishable from
            // a remote addition, so it would be copied back down instead of deleted remotely.
            String initialRemotePath = File.createTempFile("peergos-sync", ".sqlite", peergosDir.toFile()).toString();
            try {
                SyncState initialRemote = new JdbcTreeState(initialRemotePath);
                long r0 = System.currentTimeMillis();
                LOG.accept("Checking files in Drive");
                buildDirState(remoteFS, initialRemote, syncedVersions, isCancelled);
                LOG.accept("Found " + initialRemote.filesCount() + " remote files in " + (System.currentTimeMillis() - r0)/1_000 + "s");

                // every local file is visited, so count each one, including those skipped
                SyncProgress progress = new SyncProgress(localFS.filesCount());
                localFS.applyToSubtree(file -> {
                    if (isCancelled.get())
                        throw new RuntimeException("Sync cancelled during initial scan");
                    FileState synced = syncedVersions.byPath(file.relPath);
                    if (synced != null) {
                        progress.doneFile();
                        return;
                    }
                    Path p = PathUtil.getUntrimmed(file.relPath);
                    if (IGNORED_FILENAMES.contains(p.getFileName().toString())) {
                        progress.doneFile();
                        return;
                    }
                    FileState remote = initialRemote.byPath(file.relPath);
                    if (remote == null) {
                        // avoid doing many small files in non bulk uploads, and leave a file where the other side
                        // has a folder to the full sync, which resolves the clash
                        if (file.size <= 1024*1024 || initialRemote.hasDir(file.relPath) ||
                                ancestors(file.relPath).stream().anyMatch(a -> initialRemote.byPath(a) != null)) {
                            progress.doneFile();
                            return;
                        }
                        try {
                            LOG.accept("REMOTE: Uploading " + file.relPath + " " + progress);
                            int chunkSize = FileProperties.chunkSizeForNewFiles();
                            HashTree hashTree = localFS.hashFile(p, Optional.empty(), file.relPath, syncedVersions, file.size, chunkSize);
                            CopyOp op = new CopyOp(false, localFS.resolve(file.relPath),
                                    remoteFS.resolve(file.relPath), new FileState(file.relPath, file.modifiedTime, file.size, hashTree, chunkSize), null,
                                    0, file.size, ResumeUploadProps.random(crypto));
                            copyFileDiffAndTruncate(localFS, remoteFS, List.of(op), syncedVersions, isCancelled, m -> LOG.accept(m + " " + progress));
                            syncedVersions.add(new FileState(file.relPath, file.modifiedTime, file.size, hashTree, chunkSize));
                            progress.doneFile();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    } else if (remote.size != file.size) {
                        progress.doneFile();
                    } else {
                        // hashing here is not extra work: recording the entry lets buildDirState
                        // below match it on modtime and size and skip hashing the file again
                        HashTree localHash = localFS.hashFile(p, Optional.empty(), file.relPath, syncedVersions, file.size, remote.chunkSize);
                        progress.doneFile();
                        if (localHash.equals(remote.hashTree)) {
                            syncedVersions.add(new FileState(file.relPath, file.modifiedTime, file.size, localHash, remote.chunkSize));
                            LOG.accept("Skipping identical remote file in initial sync: " + file.relPath + " " + progress);
                        }
                    }
                }, dir -> {
                    if (isCancelled.get())
                        throw new RuntimeException("Sync cancelled during initial scan");
                    // and the same for dirs: an unrecorded dir that is deleted locally looks like
                    // a remote addition, so it would be recreated locally rather than deleted
                    if (initialRemote.hasDir(dir.relPath))
                        syncedVersions.addDir(dir.relPath);
                }, false);
            } finally {
                File initialRemoteState = new File(initialRemotePath);
                if (initialRemoteState.exists())
                    initialRemoteState.delete();
            }
        }

        String localStatePath = File.createTempFile("peergos-sync", ".sqlite", peergosDir.toFile()).toString();
        String remoteStatePath = null;
        try {
            syncedVersions.copyTo(localStatePath);
            SyncState localState = new JdbcTreeState(localStatePath);
            long t1 = System.currentTimeMillis();
            // the remote scan reports itself, so the local one has to as well: hashing a
            // large folder takes minutes, and silence there looks like a hang
            LOG.accept("Checking files on this device");
            buildDirState(localFS, localState, syncedVersions, isCancelled);
            long t2 = System.currentTimeMillis();
            LOG.accept("Found " + localState.filesCount() + " local files in " + (t2-t1)/1_000 + "s");

            Snapshot syncedVersion = syncedVersions.getSnapshot(remoteFS.getRoot());
            Snapshot remoteVersion = network == null ?
                    new Snapshot(new HashMap<>()) :
                    Futures.reduceAll(syncedVersion.versions.keySet(),
                            new Snapshot(new HashMap<>()),
                            (v, w) -> v.withWriter(owner, w, network),
                            Snapshot::mergeAndOverwriteWith).join();

            Snapshot initialVersion = remoteVersion;
            CommitTracker ourCommits = new CommitTracker();
            if (network != null)
                network.synchronizer.setCommitObserver(ourCommits);

            boolean remoteChange = ! remoteVersion.equals(syncedVersion) || remoteVersion.versions.isEmpty();
            remoteStatePath = File.createTempFile("peergos-sync", ".sqlite", peergosDir.toFile()).toString();
            // with nothing changed there since, Drive holds what was synced, except for any deletes we haven't synced
            Set<String> remoteDeletes = remoteChange ? Collections.emptySet() : syncedVersions.getRemoteDeletes();
            if (! remoteChange && ! remoteDeletes.isEmpty())
                syncedVersions.copyTo(remoteStatePath);
            SyncState remoteState = remoteChange || ! remoteDeletes.isEmpty() ? new JdbcTreeState(remoteStatePath) : syncedVersions;
            long t3 = System.currentTimeMillis();
            if (! remoteChange && ! remoteDeletes.isEmpty()) {
                if (remoteState.filesCount() == 0) {
                    // a state kept in memory has no file to copy
                    for (String path : syncedVersions.allFilePaths())
                        remoteState.add(syncedVersions.byPath(path));
                    for (String dir : syncedVersions.getDirs())
                        remoteState.addDir(dir);
                }
                for (String path : remoteDeletes)
                    remoteState.remove(path);
            }
            if (remoteChange) {
                // this rehashes anything it cannot match from cache, which for a file of
                // several GiB is minutes with nothing else to report
                LOG.accept("Checking files in Drive");
                remoteVersion = buildDirState(remoteFS, remoteState, syncedVersions, isCancelled);
            }
            long t4 = System.currentTimeMillis();
            LOG.accept("Found " + remoteState.filesCount() + " remote files in " + (t4-t3)/1_000 + "s");

            resolveFileFolderClashes(localFS, localState, remoteFS, remoteState, syncedVersions, syncLocalDeletes, syncRemoteDeletes, LOG);
            resolveFileFolderClashes(remoteFS, remoteState, localFS, localState, syncedVersions, syncRemoteDeletes, syncLocalDeletes, LOG);

            TreeSet<String> allPaths = new TreeSet<>(localState.allFilePaths());
            allPaths.addAll(remoteState.allFilePaths());
            // gone from both sides, by deletes or moves, so nothing will visit them, and they would keep their folders
            for (String path : syncedVersions.allFilePaths())
                if (! allPaths.contains(path))
                    syncedVersions.remove(path);
            TreeSet<String> allChangedPaths = new TreeSet<>();

            // remove identical paths
            for (String path : allPaths) {
                FileState local = localState.byPath(path);
                FileState remote = remoteState.byPath(path);
                FileState synced = syncedVersions.byPath(path);
                if (Objects.equals(local, remote)) {
                    // an interrupted copy that a later sync finished can leave an older version recorded
                    if (! Objects.equals(synced, local))
                        syncedVersions.add(local);
                    // already synced
                    if (! syncLocalDeletes && syncedVersions.hasLocalDelete(path))
                        syncedVersions.removeLocalDelete(path);
                    if (! syncRemoteDeletes && syncedVersions.hasRemoteDelete(path))
                        syncedVersions.removeRemoteDelete(path);
                } else if (synced != null && remote != null && local != null &&
                        remote.hashTree.rootHash.equals(local.hashTree.rootHash) &&
                        (synced.equalsIgnoreModtime(local) || synced.equalsIgnoreModtime(remote))) {
                    // already synced
                    if (! syncLocalDeletes && syncedVersions.hasLocalDelete(path))
                        syncedVersions.removeLocalDelete(path);
                    if (! syncRemoteDeletes && syncedVersions.hasRemoteDelete(path))
                        syncedVersions.removeRemoteDelete(path);
                } else if (! IGNORED_FILENAMES.contains(PathUtil.getUntrimmed(path).getFileName().toString()))
                    allChangedPaths.add(path);
            }
            Set<String> doneFiles = Collections.synchronizedSet(new HashSet<>());
            // what was synced before this pass changed it, as a moved file's new path can't be one of these
            Set<String> syncedBefore = syncedVersions.allFilePaths();
            SyncProgress progress = new SyncProgress(allChangedPaths.size());

            // upload new small files in large bulk operations
            List<Set<String>> smallFileBatches = new ArrayList<>();
            smallFileBatches.add(new HashSet<>());
            long currentSmallBatchCount = 0;
            long currentSmallBatchSize = 0;
            Set<String> localDeletes = new HashSet<>();
            for (String relativePath : allChangedPaths) {
                FileState synced = syncedVersions.byPath(relativePath);
                FileState local = localState.byPath(relativePath);
                FileState remote = remoteState.byPath(relativePath);
                // a file under a path the other side still has a file at waits for that to be moved or deleted
                boolean isSmallRemoteCopy = synced == null && remote == null && local.size < FileProperties.chunkSizeForNewFiles() &&
                        ancestors(relativePath).stream().noneMatch(a -> remoteState.byPath(a) != null);
                if (isSmallRemoteCopy) {
                    List<FileState> remoteByHash = remoteState.byHash(local.hashTree.rootHash);
                    List<FileState> localByHash = localState.byHash(local.hashTree.rootHash);
                    List<FileState> extraRemote = remoteByHash.stream()
                            .filter(f -> ! localByHash.contains(f))
                            .sorted(Comparator.comparing(f -> f.relPath))
                            .collect(Collectors.toList());
                    List<FileState> extraLocal = localByHash.stream()
                            .filter(f -> ! remoteByHash.contains(f))
                            .sorted(Comparator.comparing(f -> f.relPath))
                            .collect(Collectors.toList());
                    // This index is deterministic because of the sorting
                    int index = extraLocal.indexOf(local);

                    Optional<FileState> localAtHashedPath = extraRemote.size() == extraLocal.size() ?
                            Optional.ofNullable(localState.byPath(extraRemote.get(index).relPath)) :
                            Optional.empty();
                    if (extraRemote.size() != extraLocal.size() || localAtHashedPath.isPresent()) {
                        currentSmallBatchCount++;
                        currentSmallBatchSize += local.size;
                        if (currentSmallBatchCount >= 1_000 || currentSmallBatchSize >= 100*1024*1024) {
                            smallFileBatches.add(new HashSet<>());
                            currentSmallBatchCount = 0;
                            currentSmallBatchSize = 0;
                        }
                        smallFileBatches.get(smallFileBatches.size() - 1).add(relativePath);
                    }
                }

                boolean isLocalDelete = local == null &&
                        remote != null &&
                        synced != null &&
                        remote.equalsIgnoreModtime(synced);
                if (isLocalDelete ) {
                    List<FileState> remoteByHash = remoteState.byHash(remote.hashTree.rootHash);
                    List<FileState> localByHash = localState.byHash(remote.hashTree.rootHash);
                    List<FileState> extraLocal = localByHash.stream()
                            .filter(f -> ! remoteByHash.contains(f))
                            .sorted(Comparator.comparing(f -> f.relPath))
                            .collect(Collectors.toList());
                    List<FileState> extraRemote = remoteByHash.stream()
                            .filter(f -> ! localByHash.contains(f))
                            .sorted(Comparator.comparing(f -> f.relPath))
                            .collect(Collectors.toList());
                    int index = extraRemote.indexOf(remote);

                    Optional<FileState> remoteAtHashedPath = extraLocal.size() == extraRemote.size() ?
                            Optional.ofNullable(remoteState.byPath(extraLocal.get(index).relPath)) :
                            Optional.empty();
                    if (extraLocal.size() != extraRemote.size() || remoteAtHashedPath.isPresent() ||
                            syncedBefore.contains(extraLocal.get(index).relPath))
                        localDeletes.add(relativePath);
                }
            }
            if (isCancelled.get())
                return;

            // do deletes in bulk, before uploads, which may need a deleted file's path for a folder
            if (! localDeletes.isEmpty()) {
                Map<String, Map<String, FileState>> byFolder = new HashMap<>();
                for (String relPath : localDeletes) {
                    doneFiles.add(relPath);
                    if (! syncLocalDeletes) {
                        LOG.accept("Sync ignore local delete " + relPath + " " + progress);
                        syncedVersions.addLocalDelete(relPath);
                    } else {
                        String folderPath = relPath.contains("/") ? relPath.substring(0, relPath.lastIndexOf("/")) : "";
                        String filename = relPath.substring(relPath.lastIndexOf("/") + 1);
                        byFolder.computeIfAbsent(folderPath, f -> new HashMap<>()).put(filename, remoteState.byPath(relPath));
                    }
                }
                byFolder.forEach((dir, files) -> {
                    if (isCancelled.get())
                        throw new RuntimeException("Sync cancelled before deleting from " + dir);
                    LOG.accept("REMOTE: bulk deleting " + files.size() + " from " + remoteFS.resolve(dir) + " " + progress);
                    Set<String> deleted = remoteFS.bulkDeleteFiles(remoteFS.resolve(dir), files);
                    for (String file : files.keySet()) {
                        String path = dir + (dir.isEmpty() ? "" : "/") + file;
                        progress.doneFile();
                        if (deleted.contains(file)) {
                            LOG.accept("REMOTE: deleted " + path + " " + progress);
                            syncedVersions.remove(path);
                        } else {
                            // a change since the scan is a change against a delete, which the next sync copies over as new
                            LOG.accept("REMOTE: not deleting " + path + ", as it has changed since " + progress);
                            syncedVersions.remove(path);
                        }
                    }
                });
            }

            for (Set<String> smallFiles : smallFileBatches) {
                if (!smallFiles.isEmpty()) {
                    LOG.accept("Remote: bulk uploading " + smallFiles.size() + " small files" + " " + progress);
                    Map<String, FileWrapper.FolderUploadProperties> folders = new HashMap<>();
                    for (String relPath : smallFiles) {
                        doneFiles.add(relPath);
                        String folderPath = relPath.contains("/") ? relPath.substring(0, relPath.lastIndexOf("/")) : "";
                        FileWrapper.FolderUploadProperties folder = folders.get(folderPath);
                        if (folder == null) {
                            List<String> relativePath = folderPath.isEmpty() ? Collections.emptyList() : Arrays.asList(folderPath.split("/"));
                            folder = new FileWrapper.FolderUploadProperties(relativePath, new ArrayList<>());
                            folders.put(folderPath, folder);
                        }
                        String filename = relPath.substring(relPath.lastIndexOf("/") + 1);
                        FileState local = localState.byPath(relPath);
                        if (relPath.contains("/"))
                            remoteState.addDir(relPath.substring(0, relPath.length() - filename.length() - 1));
                        AtomicBoolean uploadStarted = new AtomicBoolean(false);
                        AtomicBoolean uploadEnded = new AtomicBoolean(false);
                        LocalDateTime modificationTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(local.modificationTime / 1000, 0), ZoneOffset.UTC);
                        folder.files.add(new FileWrapper.FileUploadProperties(filename,
                                () -> {
                                    try {
                                        return localFS.getBytes(localFS.resolve(relPath), 0);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                },
                                (int) (local.size >> 32), (int) local.size, Optional.of(modificationTime), Optional.of(local.hashTree), false, true,
                                x -> {
                                    // the batch is one call, and the check after it only runs once
                                    // the whole batch is done: without this a stopped sync keeps
                                    // uploading, on mobile data too, until every queued file is sent
                                    if (isCancelled.get())
                                        throw new RuntimeException("Sync cancelled while uploading " + relPath);
                                    if (!uploadStarted.get()) {
                                        LOG.accept("REMOTE: Uploading " + relPath + " " + progress);
                                        uploadStarted.set(true);
                                    }
                                    if (!uploadEnded.get()) {
                                        progress.doneFile();
                                        uploadEnded.set(true);
                                    }
                                }));
                    }
                    remoteFS.uploadSubtree(folders.values().stream());
                    for (String relPath : smallFiles) {
                        syncedVersions.add(localState.byPath(relPath));
                    }
                }
                if (isCancelled.get())
                    return;
            }

            List<ForkJoinTask<?>> downloads = new ArrayList<>();
            AtomicInteger maxDownloadConcurrency = new AtomicInteger(maxParallelism);

            // a file under a path either side has a file at waits for that file to be moved or deleted, which can be
            // done from a path that sorts after it
            List<String> ordered = new ArrayList<>(), blocked = new ArrayList<>();
            for (String path : allChangedPaths)
                (ancestors(path).stream().anyMatch(a -> localState.byPath(a) != null || remoteState.byPath(a) != null) ?
                        blocked : ordered).add(path);
            ordered.addAll(blocked);
            // a download left running when this returns would race the next sync, so every one is waited for, and
            // the rest stop at the first failure
            AtomicBoolean failed = new AtomicBoolean(false);
            Supplier<Boolean> stopDownload = () -> failed.get() || isCancelled.get();
            Throwable failure = null;
            try {
                for (String relativePath : ordered) {
                    if (doneFiles.contains(relativePath))
                        continue;
                    if (isCancelled.get())
                        break;
                    FileState synced = syncedVersions.byPath(relativePath);
                    FileState local = localState.byPath(relativePath);
                    FileState remote = remoteState.byPath(relativePath);
                    // one that the same contents here could make a move must not race the files it moves
                    boolean isLocalCopy = synced == null && local == null && localState.byHash(remote.hashTree.rootHash).isEmpty();
                    if (isLocalCopy) {
                        while (maxDownloadConcurrency.get() == 0) {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                            }
                        }
                        maxDownloadConcurrency.decrementAndGet();
                        downloads.add(ForkJoinPool.commonPool().submit(() -> {
                            try {
                                syncFile(synced, local, remote, localFS, remoteFS, syncedVersions, syncedBefore,
                                        localState, remoteState, syncLocalDeletes, syncRemoteDeletes, doneFiles, minPercentFreeSpace, crypto, stopDownload, LOG, progress);
                            } catch (IOException e) {
                                failed.set(true);
                                throw new RuntimeException(e);
                            } catch (RuntimeException | Error e) {
                                failed.set(true);
                                throw e;
                            } finally {
                                maxDownloadConcurrency.incrementAndGet();
                            }
                        }));
                    } else
                        syncFile(synced, local, remote, localFS, remoteFS, syncedVersions, syncedBefore, localState,
                                remoteState, syncLocalDeletes, syncRemoteDeletes, doneFiles, minPercentFreeSpace, crypto, isCancelled, LOG, progress);
                    if (failed.get())
                        break;
                }
            } catch (IOException | RuntimeException | Error e) {
                failure = e;
                failed.set(true);
            }
            for (ForkJoinTask<?> download : downloads) {
                try {
                    download.join();
                } catch (RuntimeException | Error e) {
                    if (failure == null)
                        failure = e;
                }
            }
            if (failure instanceof IOException)
                throw (IOException) failure;
            if (failure instanceof RuntimeException)
                throw (RuntimeException) failure;
            if (failure instanceof Error)
                throw (Error) failure;
            if (isCancelled.get())
                return;
            // every copy is complete, and none is under way to need the folder they were written in
            localFS.discardCopies();
            remoteFS.discardCopies();

            // all files are in sync, now sync dirs
            Comparator<String> longestFirst = (a, b) -> -a.compareTo(b);
            SortedSet<String> allDirs = new TreeSet<>(longestFirst);
            allDirs.addAll(remoteState.getDirs());
            allDirs.addAll(localState.getDirs());
            allDirs.addAll(syncedVersions.getDirs());
            // a folder deleted on one side, but given new files or folders on the other, still has to exist on both
            // now, as the copies made it again. Subfolders come first, so they can mark the folders above them.
            Set<String> kept = new HashSet<>();
            for (String file : syncedVersions.allFilePaths())
                kept.addAll(ancestors(file));
            for (String dirPath : allDirs) {
                boolean hasLocal = localState.hasDir(dirPath);
                boolean hasRemote = remoteState.hasDir(dirPath);
                boolean hasSynced = syncedVersions.hasDir(dirPath);
                if ((hasLocal && hasRemote) || kept.contains(dirPath)) {
                    syncedVersions.addDir(dirPath);
                    kept.addAll(ancestors(dirPath));
                } else if (!hasLocal && !hasRemote) {
                    syncedVersions.removeDir(dirPath);
                } else if (hasLocal) {
                    if (hasSynced) { // delete
                        if (syncRemoteDeletes) {
                            LOG.accept("Sync local: delete dir " + dirPath + " " + progress);
                            deleteDir(localFS, localFS.resolve(dirPath), LOG);
                            syncedVersions.removeDir(dirPath);
                        }
                    } else {
                        LOG.accept("Sync Remote: mkdir " + dirPath + " " + progress);
                        remoteFS.mkdirs(remoteFS.resolve(dirPath));
                        syncedVersions.addDir(dirPath);
                        kept.addAll(ancestors(dirPath));
                    }
                } else {
                    if (hasSynced) { // delete
                        if (syncLocalDeletes) {
                            LOG.accept("Sync Remote: delete dir " + dirPath + " " + progress);
                            deleteDir(remoteFS, remoteFS.resolve(dirPath), LOG);
                            syncedVersions.removeDir(dirPath);
                        }
                    } else {
                        LOG.accept("Sync Local: mkdir " + dirPath + " " + progress);
                        localFS.mkdirs(localFS.resolve(dirPath));
                        syncedVersions.addDir(dirPath);
                        kept.addAll(ancestors(dirPath));
                    }
                }
            }

            syncedVersions.setSnapshot(remoteFS.getRoot(),
                    versionToSave(owner, network, initialVersion, remoteVersion, ourCommits));
            syncedVersions.setCompletedSync(true);
        } finally {
            if (network != null)
                network.synchronizer.setCommitObserver((w, s) -> {});
            File localState = new File(localStatePath);
            if (localState.exists())
                localState.delete();
            if (remoteStatePath != null) {
                File remoteState = new File(remoteStatePath);
                if (remoteState.exists())
                    remoteState.delete();
            }
        }
    }

    /** A pair the pass could not even start, carrying which one it was so the caller can
     *  show that folder as needing attention rather than leaving it looking busy. */
    public static class PairFailure extends RuntimeException {
        public final int pair;

        public PairFailure(int pair, Throwable cause) {
            super(describeError(cause), cause);
            this.pair = pair;
        }
    }

    /** A failed request is thrown with the url as its message, which tells the user nothing
     *  they can act on, and repeats their owner key back at them. */
    public static String describeError(Throwable t) {
        // a transient rate limit has no message of its own, so it would show as its class name
        Throwable cause = Exceptions.getRootCause(t);
        if (cause instanceof MajorRateLimitException)
            return cause.getMessage();
        if (cause instanceof RateLimitException)
            return "The server is busy, sync will retry shortly";
        String msg = t.getMessage();
        // android reports a name lookup failure as a raw resolver error, not as the exception
        // type, so the text is what tells them apart
        if (t instanceof UnknownHostException
                || (msg != null && (msg.contains("getaddrinfo") || msg.contains("Unable to resolve host"))))
            return "Cannot reach the server. Check your connection.";
        if (msg == null || ! msg.startsWith("http"))
            return msg;
        int query = msg.indexOf('?');
        return "No answer from the server for " + (query < 0 ? msg : msg.substring(0, query));
    }

    public static void log(String msg) {
        System.out.println(msg);
        LOG.info(msg);
    }

    public static void syncFile(FileState synced, FileState local, FileState remote,
                                SyncFilesystem localFs,
                                SyncFilesystem remoteFs,
                                SyncState syncedVersions, Set<String> syncedBefore, SyncState localTree, SyncState remoteTree,
                                boolean syncLocalDeletes,
                                boolean syncRemoteDeletes,
                                Set<String> doneFiles,
                                int minPercentFree,
                                Crypto crypto,
                                Supplier<Boolean> isCancelled,
                                Consumer<String> LOG,
                                SyncProgress progress) throws IOException {
        // per MiB transfer lines replace the status, so they carry the pass's count too
        Consumer<String> transferLog = m -> LOG.accept(m + " " + progress);
        long totalSpace = localFs.totalSpace();
        long freeSpace = localFs.freeSpace();

        if (synced == null) {
            if (local == null) { // remotely added or renamed
                List<FileState> remoteByHash = remoteTree.byHash(remote.hashTree.rootHash);
                List<FileState> localByHash = localTree.byHash(remote.hashTree.rootHash);
                List<FileState> extraLocal = localByHash.stream()
                        .filter(f -> ! remoteByHash.contains(f))
                        .sorted(Comparator.comparing(f -> f.relPath))
                        .collect(Collectors.toList());
                List<FileState> extraRemote = remoteByHash.stream()
                        .filter(f -> ! localByHash.contains(f))
                        .sorted(Comparator.comparing(f -> f.relPath))
                        .collect(Collectors.toList());
                int index = extraRemote.indexOf(remote);

                Optional<FileState> remoteAtHashedPath = extraLocal.size() == extraRemote.size() ?
                        Optional.ofNullable(remoteTree.byPath(extraLocal.get(index).relPath)) :
                        Optional.empty();

                // the scan can't know of a copy this pass has made there since
                if (extraLocal.size() == extraRemote.size() && remoteAtHashedPath.isEmpty() &&
                        ! isFile(remoteFs, remoteFs.resolve(extraLocal.get(index).relPath))) {// rename
                    FileState toMove = extraLocal.get(index);
                    LOG.accept("Sync Local: Moving " + toMove.relPath + " ==> " + remote.relPath + " " + progress);
                    move(localFs, localFs.resolve(toMove.relPath), localFs.resolve(remote.relPath));
                    syncedVersions.remove(toMove.relPath);
                    doneFiles.add(toMove.relPath);
                    syncedVersions.add(remote);
                    progress.doneFile();
                    progress.doneFile();
                } else {
                    if ((freeSpace - remote.size) * 100 / totalSpace < minPercentFree && (freeSpace - remote.size < 5L * 1024*1024*1024))
                        throw new IllegalStateException("Not enough local free space to sync and keep " + minPercentFree + "% free or 5 GB free");
                    LOG.accept("Sync Local: Copying " + remote.relPath + " " + progress);
                    List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(true, remoteFs.resolve(remote.relPath), localFs.resolve(remote.relPath), remote, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                            .collect(Collectors.toList());
                    Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions, isCancelled, transferLog);
                    syncedVersions.add(remote.withModtime(actualModTime));
                    progress.doneFile();
                }
            } else if (remote == null) { // locally added or renamed
                List<FileState> remoteByHash = remoteTree.byHash(local.hashTree.rootHash);
                List<FileState> localByHash = localTree.byHash(local.hashTree.rootHash);
                List<FileState> extraRemote = remoteByHash.stream()
                        .filter(f -> ! localByHash.contains(f))
                        .sorted(Comparator.comparing(f -> f.relPath))
                        .collect(Collectors.toList());
                List<FileState> extraLocal = localByHash.stream()
                        .filter(f -> ! remoteByHash.contains(f))
                        .sorted(Comparator.comparing(f -> f.relPath))
                        .collect(Collectors.toList());
                int index = extraLocal.indexOf(local);

                Optional<FileState> localAtHashedPath = extraRemote.size() == extraLocal.size() ?
                        Optional.ofNullable(localTree.byPath(extraRemote.get(index).relPath)) :
                        Optional.empty();

                // the scan can't know of a copy this pass has made there since
                if (extraRemote.size() == extraLocal.size() && localAtHashedPath.isEmpty() &&
                        ! isFile(localFs, localFs.resolve(extraRemote.get(index).relPath))) {// rename
                    FileState toMove = extraRemote.get(index);
                    LOG.accept("Sync Remote: Moving " + toMove.relPath + " ==> " + local.relPath + " " + progress);
                    move(remoteFs, remoteFs.resolve(toMove.relPath), Paths.get(local.relPath));
                    syncedVersions.remove(toMove.relPath);
                    doneFiles.add(toMove.relPath);
                    syncedVersions.add(local);
                    progress.doneFile();
                    progress.doneFile();
                } else {
                    LOG.accept("Sync Remote: Copying " + local.relPath + " " + progress);
                    List<Pair<Long, Long>> diffs = local.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(false, localFs.resolve(local.relPath), remoteFs.resolve(local.relPath), local, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                            .collect(Collectors.toList());
                    Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(localFs, remoteFs, ops, syncedVersions, isCancelled, transferLog);
                    syncedVersions.add(local.withModtime(actualModTime));
                    progress.doneFile();
                }
            } else {
                // concurrent addition, rename 1 if contents are different
                if (remote.hashTree.rootHash.equals(local.hashTree.rootHash)) {
                    if (local.modificationTime > remote.modificationTime) {
                        LOG.accept("Remote: Set mod time " + local.relPath + " " + progress);
                        remoteFs.setModificationTime(remoteFs.resolve(local.relPath), local.modificationTime);
                        syncedVersions.add(local);
                        progress.doneFile();
                        return;
                    } else if (remote.modificationTime > local.modificationTime) {
                        LOG.accept("Sync Local: Set mod time " + local.relPath + " " + progress);
                        localFs.setModificationTime(localFs.resolve(local.relPath), remote.modificationTime);
                        syncedVersions.add(remote);
                        progress.doneFile();
                        return;
                    }
                    syncedVersions.add(local);
                } else {
                    if ((freeSpace - remote.size) * 100 / totalSpace < minPercentFree && (freeSpace - remote.size < 5L * 1024*1024*1024))
                        throw new IllegalStateException("Not enough local free space to sync and keep " + minPercentFree + "% free or 5GB free. Conflict on " + local.relPath);
                    LOG.accept("Sync Remote: Concurrent file addition: " + local.relPath + " renaming local version" + " " + progress);
                    FileState renamed = renameOnConflict(localFs, remoteFs, localFs.resolve(local.relPath), local);
                    List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(true, remoteFs.resolve(remote.relPath), localFs.resolve(remote.relPath), remote, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                            .collect(Collectors.toList());
                    Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions, isCancelled, transferLog);
                    syncedVersions.add(remote.withModtime(actualModTime));

                    List<Pair<Long, Long>> diffs2 = local.diffRanges(null);
                    List<CopyOp> ops2 = diffs2.stream()
                            .map(d -> new CopyOp(false, localFs.resolve(renamed.relPath), remoteFs.resolve(renamed.relPath), renamed, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                            .collect(Collectors.toList());
                    Optional<LocalDateTime> actualModTime2 = copyFileDiffAndTruncate(localFs, remoteFs, ops2, syncedVersions, isCancelled, transferLog);
                    syncedVersions.add(renamed.withModtime(actualModTime2));
                    progress.doneFile();
                }
            }
        } else { // synced != null
            if (synced.equalsIgnoreModtime(local)) { // remote change only
                if (remote == null) { // deletion or rename
                    List<FileState> remoteByHash = remoteTree.byHash(local.hashTree.rootHash);
                    List<FileState> localByHash = localTree.byHash(local.hashTree.rootHash);
                    List<FileState> extraRemote = remoteByHash.stream()
                            .filter(f -> ! localByHash.contains(f))
                            .sorted(Comparator.comparing(f -> f.relPath))
                            .collect(Collectors.toList());
                    List<FileState> extraLocal = localByHash.stream()
                            .filter(f -> ! remoteByHash.contains(f))
                            .sorted(Comparator.comparing(f -> f.relPath))
                            .collect(Collectors.toList());
                    int index = extraLocal.indexOf(local);

                    Optional<FileState> localAtHashedPath = extraRemote.size() == extraLocal.size() ?
                            Optional.ofNullable(localTree.byPath(extraRemote.get(index).relPath)) :
                            Optional.empty();
                    // a synced partner is a copy that was already there, not where this moved to
                    if (extraRemote.size() == extraLocal.size() && localAtHashedPath.isEmpty() &&
                            ! syncedBefore.contains(extraRemote.get(index).relPath)) {// rename
                        // we will do the local rename when we process the new remote entry
                    } else {
                        if (syncRemoteDeletes && ! localFs.exists(localFs.resolve(local.relPath))) {
                            // deleted here too since
                            syncedVersions.remove(local.relPath);
                        } else if (syncRemoteDeletes && ! asScanned(localFs, localFs.resolve(local.relPath), local, syncedVersions)) {
                            // an edit since the scan is a change against a delete, which the next sync copies over as new
                            LOG.accept("Sync Local: not deleting " + local.relPath + ", as it has changed since " + progress);
                            syncedVersions.remove(local.relPath);
                        } else if (syncRemoteDeletes) {
                            LOG.accept("Sync Local: delete " + local.relPath + " " + progress);
                            localFs.delete(localFs.resolve(local.relPath));
                            syncedVersions.remove(local.relPath);
                        } else {
                            LOG.accept("Sync ignore remote delete " + local.relPath + " " + progress);
                            syncedVersions.addRemoteDelete(local.relPath);
                        }
                        progress.doneFile();
                    }
                } else if (remote.hashTree.rootHash.equals(local.hashTree.rootHash)) {
                    // already synced
                    if (! syncLocalDeletes && syncedVersions.hasLocalDelete(remote.relPath))
                        syncedVersions.removeLocalDelete(remote.relPath);
                    if (! syncRemoteDeletes && syncedVersions.hasRemoteDelete(remote.relPath))
                        syncedVersions.removeRemoteDelete(remote.relPath);
                    progress.doneFile();
                } else {
                    if (syncedVersions.hasRemoteDelete(remote.relPath) ||
                            ! syncRemoteDeletes && movedOver(remote, syncedVersions, remoteTree)) {
                        // remote file was deleted, then a different file with same path was added. Rename local and copy remote
                        LOG.accept("Sync Remote: Concurrent change: " + local.relPath + " renaming local version" + " " + progress);
                        FileState renamed = renameOnConflict(localFs, remoteFs, localFs.resolve(local.relPath), local);
                        List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                        List<CopyOp> ops = diffs.stream()
                                .map(d -> new CopyOp(true, remoteFs.resolve(remote.relPath), localFs.resolve(remote.relPath), remote, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                                .collect(Collectors.toList());
                        Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions, isCancelled, transferLog);
                        syncedVersions.add(remote.withModtime(actualModTime));
                        progress.doneFile();

                        List<Pair<Long, Long>> diffs2 = local.diffRanges(null);
                        List<CopyOp> ops2 = diffs2.stream()
                                .map(d -> new CopyOp(false, localFs.resolve(renamed.relPath), remoteFs.resolve(renamed.relPath), renamed, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                                .collect(Collectors.toList());
                        Optional<LocalDateTime> actualModTime2 = copyFileDiffAndTruncate(localFs, remoteFs, ops2, syncedVersions, isCancelled, transferLog);
                        syncedVersions.add(renamed.withModtime(actualModTime2));
                        syncedVersions.removeRemoteDelete(remote.relPath);
                        progress.doneFile();
                    } else {
                        if (remote.size > local.size && (freeSpace + local.size - remote.size) * 100 / totalSpace < minPercentFree &&
                                (freeSpace + local.size - remote.size < 5L * 1024*1024*1024))
                            throw new IllegalStateException("Not enough local free space to sync and keep " + minPercentFree + "% free or 5 GiB free");
                        LOG.accept("Sync Local: Copying changes to " + remote.relPath + " " + progress);
                        List<Pair<Long, Long>> diffs = remote.diffRanges(local);
                        List<CopyOp> ops = diffs.stream()
                                .map(d -> new CopyOp(true, remoteFs.resolve(remote.relPath), localFs.resolve(remote.relPath), remote, local, d.left, d.right, ResumeUploadProps.random(crypto)))
                                .collect(Collectors.toList());
                        Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions, isCancelled, transferLog);
                        syncedVersions.add(remote.withModtime(actualModTime));
                        progress.doneFile();
                    }
                }
            } else if (synced.equalsIgnoreModtime(remote)) { // local only change
                if (local == null) { // deletion or rename
                    List<FileState> remoteByHash = remoteTree.byHash(remote.hashTree.rootHash);
                    List<FileState> localByHash = localTree.byHash(remote.hashTree.rootHash);
                    List<FileState> extraLocal = localByHash.stream()
                            .filter(f -> ! remoteByHash.contains(f))
                            .sorted(Comparator.comparing(f -> f.relPath))
                            .collect(Collectors.toList());
                    List<FileState> extraRemote = remoteByHash.stream()
                            .filter(f -> ! localByHash.contains(f))
                            .sorted(Comparator.comparing(f -> f.relPath))
                            .collect(Collectors.toList());
                    int index = extraRemote.indexOf(remote);

                    Optional<FileState> remoteAtHashedPath = extraLocal.size() == extraRemote.size() ?
                            Optional.ofNullable(remoteTree.byPath(extraLocal.get(index).relPath)) :
                            Optional.empty();
                    // a synced partner is a copy that was already there, not where this moved to
                    if (extraLocal.size() == extraRemote.size() && remoteAtHashedPath.isEmpty() &&
                            ! syncedBefore.contains(extraLocal.get(index).relPath)) {// rename
                        // we will do the remote rename when we process the new local entry
                    } else {
                        if (syncLocalDeletes && ! remoteFs.exists(remoteFs.resolve(remote.relPath))) {
                            // deleted there too since
                            syncedVersions.remove(remote.relPath);
                        } else if (syncLocalDeletes && ! asScanned(remoteFs, remoteFs.resolve(remote.relPath), remote, syncedVersions)) {
                            // an edit since the scan is a change against a delete, which the next sync copies over as new
                            LOG.accept("Sync Remote: not deleting " + remote.relPath + ", as it has changed since " + progress);
                            syncedVersions.remove(remote.relPath);
                        } else if (syncLocalDeletes) {
                            LOG.accept("Sync Remote: delete " + remote.relPath + " " + progress);
                            remoteFs.delete(remoteFs.resolve(remote.relPath));
                            syncedVersions.remove(remote.relPath);
                        } else {
                            LOG.accept("Sync ignore local delete " + remote.relPath + " " + progress);
                            syncedVersions.addLocalDelete(remote.relPath);
                        }
                        progress.doneFile();
                    }
                } else if (remote.hashTree.rootHash.equals(local.hashTree.rootHash)) {
                    // already synced
                    if (! syncLocalDeletes && syncedVersions.hasLocalDelete(remote.relPath))
                        syncedVersions.removeLocalDelete(remote.relPath);
                    if (! syncRemoteDeletes && syncedVersions.hasRemoteDelete(remote.relPath))
                        syncedVersions.removeRemoteDelete(remote.relPath);
                    progress.doneFile();
                } else {
                    if (syncedVersions.hasLocalDelete(local.relPath) ||
                            ! syncLocalDeletes && movedOver(local, syncedVersions, localTree)) {
                        // local file was deleted, then a different file with same path was added. Keep remote, rename local.
                        LOG.accept("Sync Remote: Concurrent change: " + local.relPath + " renaming different local version after local delete" + " " + progress);
                        FileState renamed = renameOnConflict(localFs, remoteFs, localFs.resolve(local.relPath), local);
                        List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                        List<CopyOp> ops = diffs.stream()
                                .map(d -> new CopyOp(true, remoteFs.resolve(remote.relPath), localFs.resolve(remote.relPath), remote, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                                .collect(Collectors.toList());
                        Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions, isCancelled, transferLog);
                        syncedVersions.add(remote.withModtime(actualModTime));
                        progress.doneFile();

                        List<Pair<Long, Long>> diffs2 = local.diffRanges(null);
                        List<CopyOp> ops2 = diffs2.stream()
                                .map(d -> new CopyOp(false, localFs.resolve(renamed.relPath), remoteFs.resolve(renamed.relPath), renamed, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                                .collect(Collectors.toList());
                        Optional<LocalDateTime> actualModTime2 = copyFileDiffAndTruncate(localFs, remoteFs, ops2, syncedVersions, isCancelled, transferLog);
                        syncedVersions.add(renamed.withModtime(actualModTime2));
                        syncedVersions.removeLocalDelete(local.relPath);
                        progress.doneFile();
                    } else {
                        LOG.accept("Sync Remote: Copying changes to " + local.relPath + " " + progress);
                        List<Pair<Long, Long>> diffs = local.diffRanges(remote);
                        List<CopyOp> ops = diffs.stream()
                                .map(d -> new CopyOp(false, localFs.resolve(local.relPath), remoteFs.resolve(local.relPath), local, remote, d.left, d.right, ResumeUploadProps.random(crypto)))
                                .collect(Collectors.toList());
                        Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(localFs, remoteFs, ops, syncedVersions, isCancelled, transferLog);
                        syncedVersions.add(local.withModtime(actualModTime));
                        progress.doneFile();
                    }
                }
            } else { // concurrent change/deletion
                if (local == null && remote == null) {// concurrent deletes
                    LOG.accept("Sync Concurrent delete on " + synced.relPath + " " + progress);
                    syncedVersions.remove(synced.relPath);
                    progress.doneFile();
                    return;
                }
                if (local == null) { // local delete, copy changed remote
                    if ((freeSpace - remote.size) * 100 / totalSpace < minPercentFree && (freeSpace - remote.size < 5L * 1024*1024*1024))
                        throw new IllegalStateException("Not enough local free space to sync and keep " + minPercentFree + "% free");
                    LOG.accept("Sync Local: deleted, copying changed remote " + remote.relPath + ", Synced: " + synced.prettyPrint() + ", remote: " + remote.prettyPrint() + " " + progress);
                    List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(true, remoteFs.resolve(remote.relPath), localFs.resolve(remote.relPath), remote, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                            .collect(Collectors.toList());
                    Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions, isCancelled, transferLog);
                    syncedVersions.add(remote.withModtime(actualModTime));
                    progress.doneFile();
                    if (! syncLocalDeletes && syncedVersions.hasLocalDelete(remote.relPath))
                        syncedVersions.removeLocalDelete(remote.relPath);
                    if (! syncRemoteDeletes && syncedVersions.hasRemoteDelete(remote.relPath))
                        syncedVersions.removeRemoteDelete(remote.relPath);
                    return;
                }
                if (remote == null) { // remote delete, copy changed local
                    LOG.accept("Sync Remote: deleted, copying changed local " + local.relPath + " " + progress);
                    List<Pair<Long, Long>> diffs = local.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(false, localFs.resolve(local.relPath), remoteFs.resolve(local.relPath), local, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                            .collect(Collectors.toList());
                    Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(localFs, remoteFs, ops, syncedVersions, isCancelled, transferLog);
                    syncedVersions.add(local.withModtime(actualModTime));
                    progress.doneFile();
                    if (! syncRemoteDeletes && syncedVersions.hasRemoteDelete(local.relPath))
                        syncedVersions.removeRemoteDelete(local.relPath);
                    return;
                }
                // concurrent change, rename one sync the other
                if ((freeSpace - remote.size) * 100 / totalSpace < minPercentFree && (freeSpace - remote.size < 5L * 1024*1024*1024))
                    throw new IllegalStateException("Not enough local free space to sync and keep " + minPercentFree + "% free or 5 GiB free");
                // if local and remote are the same, update sync and return
                if (local.size == remote.size && local.hashTree.rootHash.equals(remote.hashTree.rootHash)) {
                    syncedVersions.add(local);
                } else if (synced.hashTree.rootHash.equals(remote.hashTree.rootHash)) {
                    // synced content is same as remote, so just a local change
                    LOG.accept("Sync Remote: Copying changes to " + local.relPath + " " + progress);
                    List<Pair<Long, Long>> diffs = local.diffRanges(remote);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(false, localFs.resolve(local.relPath), remoteFs.resolve(local.relPath), local, remote, d.left, d.right, ResumeUploadProps.random(crypto)))
                            .collect(Collectors.toList());
                    Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(localFs, remoteFs, ops, syncedVersions, isCancelled, transferLog);
                    syncedVersions.add(local.withModtime(actualModTime));
                    progress.doneFile();
                } else {
                    LOG.accept("Sync Remote: Concurrent change: " + local.relPath + " renaming local version" + " " + progress);
                    FileState renamed = renameOnConflict(localFs, remoteFs, localFs.resolve(local.relPath), local);
                    List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(true, remoteFs.resolve(remote.relPath), localFs.resolve(remote.relPath), remote, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                            .collect(Collectors.toList());
                    Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions, isCancelled, transferLog);
                    syncedVersions.add(remote.withModtime(actualModTime));
                    progress.doneFile();

                    List<Pair<Long, Long>> diffs2 = local.diffRanges(null);
                    List<CopyOp> ops2 = diffs2.stream()
                            .map(d -> new CopyOp(false, localFs.resolve(renamed.relPath), remoteFs.resolve(renamed.relPath), renamed, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                            .collect(Collectors.toList());
                    Optional<LocalDateTime> actualModTime2 = copyFileDiffAndTruncate(localFs, remoteFs, ops2, syncedVersions, isCancelled, transferLog);
                    syncedVersions.add(renamed.withModtime(actualModTime2));
                    progress.doneFile();
                }
            }
        }
    }

    /** A file moved into a folder that took its name has to make way for the folder first */
    private static void move(SyncFilesystem fs, Path from, Path to) {
        if (! to.startsWith(from)) {
            fs.moveTo(from, to);
            return;
        }
        Path aside = getParent(from).resolve(from.getFileName() + ".sync-moving");
        for (int i = 1; fs.exists(aside); i++)
            aside = getParent(from).resolve(from.getFileName() + ".sync-moving" + i);
        fs.moveTo(from, aside);
        fs.moveTo(aside, to);
    }

    /** Deletes a folder the scan found, unless it has since been replaced by a file, which the next sync will see */
    private static void deleteDir(SyncFilesystem fs, Path dir, Consumer<String> LOG) {
        if (isFile(fs, dir)) {
            LOG.accept("Sync: not deleting " + dir + ", as it has been replaced by a file since");
            return;
        }
        fs.delete(dir);
    }

    private static boolean isFile(SyncFilesystem fs, Path p) {
        // only a folder can have something put in it
        return fs.exists(p) && ! fs.canCreate(p.resolve("f"));
    }

    private static List<String> ancestors(String path) {
        List<String> res = new ArrayList<>();
        for (int slash = path.lastIndexOf("/"); slash > 0; slash = path.lastIndexOf("/", slash - 1))
            res.add(path.substring(0, slash));
        return res;
    }

    /** A file on one side where the other side has a folder can't be copied over, and nothing else would resolve
     *  it, so every sync would fail on it. An empty folder holds nothing, so remove it when deletes are synced in
     *  that direction, otherwise move the file aside the way a conflicting edit is.
     */
    private static void resolveFileFolderClashes(SyncFilesystem fileFS,
                                                 SyncState fileState,
                                                 SyncFilesystem folderFS,
                                                 SyncState folderState,
                                                 SyncState syncedVersions,
                                                 boolean deleteEmptyFolder,
                                                 boolean folderSideDeletesSync,
                                                 Consumer<String> LOG) {
        List<String> clashes = fileState.allFilePaths().stream()
                .filter(folderState::hasDir)
                // an unchanged file that was synced is deleted as usual, the folder having replaced it, unless that
                // side's deletes aren't synced
                .filter(p -> {
                    FileState synced = syncedVersions.byPath(p);
                    return ! folderSideDeletesSync || synced == null ||
                            ! synced.hashTree.rootHash.equals(fileState.byPath(p).hashTree.rootHash);
                })
                .sorted()
                .collect(Collectors.toList());
        if (clashes.isEmpty())
            return;
        Set<String> folderFiles = folderState.allFilePaths();
        Set<String> folderDirs = folderState.getDirs();
        for (String path : clashes) {
            String prefix = path + "/";
            boolean emptyFolder = folderFiles.stream().noneMatch(f -> f.startsWith(prefix)) &&
                    folderDirs.stream().noneMatch(d -> d.startsWith(prefix));
            if (emptyFolder && deleteEmptyFolder) {
                LOG.accept("Sync: deleting empty folder " + path + " where the other side has a file");
                deleteDir(folderFS, folderFS.resolve(path), LOG);
                folderState.removeDir(path);
                syncedVersions.removeDir(path);
            } else {
                FileState file = fileState.byPath(path);
                FileState renamed = renameOnConflict(fileFS, folderFS, fileFS.resolve(path), file);
                LOG.accept("Sync: renamed file " + path + " to " + renamed.relPath + " as the other side has a folder there");
                fileState.remove(path);
                fileState.add(renamed);
            }
        }
    }

    public static FileState renameOnConflict(SyncFilesystem fs, Path f, FileState s) {
        return renameOnConflict(fs, null, f, s);
    }

    /** Rename to a conflict name that is free on this side and, if given, on the other one, where a file may yet
     *  be copied across under the same name
     */
    public static FileState renameOnConflict(SyncFilesystem fs, SyncFilesystem other, Path f, FileState s) {
        String name = f.getFileName().toString();
        String dirRel = s.relPath.substring(0, s.relPath.length() - name.length());
        Predicate<String> taken = n -> fs.exists(getParent(f).resolve(n)) ||
                (other != null && other.exists(other.resolve(dirRel + n)));
        String newName;
        if (name.contains("[conflict-")) {
            int start = name.lastIndexOf("[conflict-");
            int end = name.indexOf("]", start);
            int version = Integer.parseInt(name.substring(start + "[conflict-".length(), end));
            while (true) {
                newName = name.substring(0, start) + "[conflict-" + (version + 1) + "]" + name.substring(end + 1);
                if (! taken.test(newName))
                    break;
                version++;
            }
        } else {
            int version = 0;
            while (true) {
                if (name.contains(".")) {
                    int dot = name.lastIndexOf(".");
                    newName = name.substring(0, dot) + "[conflict-" + version + "]" + name.substring(dot);
                } else
                    newName = name + "[conflict-" + version + "]";
                if (! taken.test(newName))
                    break;
                version++;
            }
        }
        Path newFile = getParent(f).resolve(newName);
        fs.moveTo(f, newFile);
        long newModified = fs.getLastModified(newFile);
        return new FileState(dirRel + newName, newModified, s.size, s.hashTree, s.chunkSize);
    }

    public static Optional<LocalDateTime> copyFileDiffAndTruncate(SyncFilesystem srcFs,
                                                                  SyncFilesystem targetFs,
                                                                  List<CopyOp> ops,
                                                                  SyncState syncDb,
                                                                  Supplier<Boolean> isCancelled,
                                                                  Consumer<String> progress) throws IOException {
        // first write the operation to the db
        syncDb.startCopies(ops);
        return copy(srcFs, targetFs, ops, true, syncDb, isCancelled, progress);
    }

    /** Runs copies, which are already recorded as started, checking that a target they write into is still the one
     *  they expect if checkTarget
     */
    private static Optional<LocalDateTime> copy(SyncFilesystem srcFs,
                                                SyncFilesystem targetFs,
                                                List<CopyOp> ops,
                                                boolean checkTarget,
                                                SyncState syncDb,
                                                Supplier<Boolean> isCancelled,
                                                Consumer<String> progress) throws IOException {
        if (ops.get(0).targetState != null && targetFs.overwritesInPlace()) {
            try {
                Optional<LocalDateTime> res = targetFs.overwrite(srcFs, ops, checkTarget, isCancelled, progress);
                syncDb.finishCopies(ops);
                return res;
            } catch (ConcurrentChangeException e) {
                // what someone else has written there is theirs, which the next sync sees as a change on both sides
                syncDb.finishCopies(ops);
                throw e;
            }
        }
        Optional<LocalDateTime> res = Optional.empty();

        for (CopyOp op : ops) {
            Optional<LocalDateTime> mod = applyCopyOp(srcFs, targetFs, op, isCancelled, progress);
            res = res.isEmpty() ? mod : mod.isEmpty() ? res : res.map(t -> mod.get().isAfter(t) ? mod.get() : t);
        }
        completeCopy(srcFs, targetFs, ops, syncDb);
        return res;
    }

    /** Puts a copy in place once all of it has been written, unless the target has been changed while it was being
     *  written, which the next sync sees as a change on both sides, or the source has, which would leave part of each
     *  version under the hash of the old one
     */
    private static void completeCopy(SyncFilesystem srcFs, SyncFilesystem targetFs, List<CopyOp> ops, SyncState syncDb) throws IOException {
        CopyOp op = ops.get(0);
        // a copy written to the target itself changed it
        boolean staged = ! targetFs.partialCopy(op.target).equals(op.target);
        if (staged && ! targetAsScanned(targetFs, op, syncDb)) {
            targetFs.discardCopy(op);
            syncDb.finishCopies(ops);
            throw new IllegalStateException(op.target + " changed while it was being synced");
        }
        if (staged && ! asScanned(srcFs, op.source, op.sourceState, syncDb)) {
            targetFs.discardCopy(op);
            syncDb.finishCopies(ops);
            throw new IllegalStateException(op.source + " changed while it was being copied");
        }
        targetFs.completeCopy(op.target, ranges(ops), op.sourceState.size);
        syncDb.finishCopies(ops);
    }

    private static List<Pair<Long, Long>> ranges(List<CopyOp> ops) {
        return ops.stream().map(op -> new Pair<>(op.diffStart, op.diffEnd)).collect(Collectors.toList());
    }

    /** Whether a file changed on one side is one moved there from a path where that side no longer has it, which
     *  deleted the file that was there
     */
    private static boolean movedOver(FileState changed, SyncState syncedVersions, SyncState tree) {
        return syncedVersions.byHash(changed.hashTree.rootHash).stream()
                .anyMatch(f -> ! f.relPath.equals(changed.relPath) && tree.byPath(f.relPath) == null);
    }

    /** Whether a copy over an existing file writes into the file itself, so has changed it if it was interrupted */
    private static boolean writtenInPlace(SyncFilesystem targetFs, CopyOp op) throws IOException {
        return op.targetState != null && targetFs.overwritesInPlace() && isFile(targetFs, op.target);
    }

    /** Whether the target of a copy is still as the sync saw it when it decided on the copy */
    private static boolean targetAsScanned(SyncFilesystem targetFs, CopyOp op, SyncState syncDb) {
        return asScanned(targetFs, op.target, op.targetState, syncDb);
    }

    /** Whether a file is still as the scan found it, or still absent, and could be put there, if scanned is null */
    private static boolean asScanned(SyncFilesystem fs, Path p, FileState scanned, SyncState syncDb) {
        if (! fs.exists(p))
            return scanned == null && fs.canCreate(p);
        if (scanned == null || fs.size(p) != scanned.size)
            return false;
        if (fs.getLastModified(p) == scanned.modificationTime)
            return true;
        // a scan keeps the synced version of a file whose contents haven't changed, whatever its modification time
        return fs.hashFile(p, Optional.empty(), scanned.relPath, syncDb, scanned.size, scanned.chunkSize)
                .rootHash.equals(scanned.hashTree.rootHash);
    }

    public static Optional<LocalDateTime> applyCopyOp(SyncFilesystem srcFs, SyncFilesystem targetFs, CopyOp op, Supplier<Boolean> isCancelled, Consumer<String> progress) throws IOException {
        if (isCancelled.get())
            throw new RuntimeException("Sync cancelled before copying " + op.target);
        log("COPY from " + op.source + " to " + op.target + " range=[" + op.diffStart +", " + op.diffEnd+"]");
        Path partial = targetFs.partialCopy(op.target);
        if (partial.equals(op.target))
            targetFs.mkdirs(getParent(op.target));
        long priorSize = op.targetState != null ? op.targetState.size : 0;
        long size = op.sourceState.size;
        long lastModified = op.sourceState.modificationTime;

        long start = op.diffStart;
        long end = op.diffEnd;
        Optional<LocalDateTime> res;
        try (AsyncReader fin = srcFs.getBytes(op.source, start)) {
            Optional<Thumbnail> thumbnail = srcFs.getThumbnail(op.source);
            LocalDateTime modified = LocalDateTime.ofInstant(Instant.ofEpochSecond(lastModified / 1000, 0), ZoneOffset.UTC);
            res = targetFs.setBytes(partial, start, fin, end - start, Optional.of(op.sourceState.hashTree),
                    Optional.of(modified), thumbnail, op.props, isCancelled, progress);
        }
        if (isCancelled.get())
            throw new RuntimeException("Sync cancelled before copying " + op.target);
        if (priorSize > size) {
            log("Sync Truncating file " + op.sourceState.relPath + " from " + priorSize + " to " + size);
            targetFs.truncate(partial, size);
        }
        return res;
    }

    /** Counts the mutable pointer commits this sync makes, per writer. A pointer's sequence
     *  advances by one per commit, and buffered writes coalesced into a single commit all
     *  report the sequence that commit reaches, so counting distinct sequences counts commits.
     */
    private static class CommitTracker implements WriteSynchronizer.CommitObserver {
        private final Map<PublicKeyHash, Set<Long>> sequences = new HashMap<>();

        @Override
        public synchronized void committed(PublicKeyHash writer, Optional<Long> sequence) {
            // a pointer that has never had a sequence is at 0, and its first commit is 1
            sequences.computeIfAbsent(writer, w -> new HashSet<>()).add(sequence.orElse(0L));
        }

        public synchronized Set<PublicKeyHash> writers() {
            return new HashSet<>(sequences.keySet());
        }

        /** A commit that changed nothing reports the sequence the pointer was already at, so
         *  only the sequences past where we started are advances we made.
         */
        public synchronized long commitsSince(PublicKeyHash writer, long sequence) {
            return sequences.getOrDefault(writer, Collections.emptySet()).stream()
                    .filter(s -> s > sequence)
                    .count();
        }
    }

    /** The sync's own uploads and deletes move the remote version on from the one we scanned,
     *  so saving the version from the start of the pass makes the next pass rescan all of
     *  Drive even when nothing but this sync has touched it. A pointer's sequence increments
     *  by exactly one per commit, so when a writer's sequence is now the one we started from
     *  plus the commits we made, every change to it since we looked was ours, and the state we
     *  have just recorded is the state that is there now.
     *
     *  Anything that doesn't add up - a concurrent change from another device, a pointer with
     *  no sequence - saves the version from before the scan instead, which costs a rescan and
     *  no more. That is also the version to save for a writer that moved on while we were
     *  scanning: the scan then read part of the tree before the change and part after, so its
     *  version claims more than we have seen.
     */
    private static Snapshot versionToSave(PublicKeyHash owner,
                                          NetworkAccess network,
                                          Snapshot initialVersion,
                                          Snapshot scannedVersion,
                                          CommitTracker ourCommits) {
        // the writers of the tree we just recorded, each at the version it was at before we
        // read anything. A writer only the scan found was added by a change to its parent,
        // which is in the baseline, so the scan's version of it is as good as we have.
        Set<PublicKeyHash> writers = scannedVersion.versions.keySet();
        HashMap<PublicKeyHash, CommittedWriterData> baseline = new HashMap<>();
        for (PublicKeyHash w : writers) {
            baseline.put(w, initialVersion.versions.getOrDefault(w, scannedVersion.get(w)));
        }
        Snapshot beforeSync = new Snapshot(baseline);
        if (network == null)
            return beforeSync;
        // a writer we wrote to that isn't in the snapshot we are about to save would leave
        // later changes under it invisible, so rescan and record it next pass
        if (! writers.containsAll(ourCommits.writers()))
            return beforeSync;
        Snapshot current = Futures.reduceAll(writers,
                new Snapshot(new HashMap<>()),
                (v, w) -> v.withWriter(owner, w, network),
                Snapshot::mergeAndOverwriteWith).join();
        if (! current.versions.keySet().equals(writers))
            return beforeSync;
        for (PublicKeyHash w : writers) {
            // a writer that didn't exist when we started is at 0, and its first commit is 1
            long before = baseline.get(w).sequence.orElse(0L);
            if (current.get(w).sequence.orElse(0L) != before + ourCommits.commitsSince(w, before))
                return beforeSync;
        }
        return current;
    }

    private static class SnapshotTracker {
        private Snapshot s;

        public SnapshotTracker(Snapshot s) {
            this.s = s;
        }

        public synchronized void update(Snapshot s2) {
            s = s.mergeAndOverwriteWith(s2);
        }

        public synchronized Snapshot get() {
            return s;
        }
    }

    /**
     * The chunk size to hash a file at: its own if we can see it, otherwise the one it had when
     * it was last synced, and for a file neither side has seen before, the one a new file gets.
     */
    private static int chunkSizeFor(Optional<FileWrapper> meta, FileState synced) {
        if (meta.isPresent())
            return meta.get().getFileProperties().chunkSize;
        return synced != null ? synced.chunkSize : FileProperties.chunkSizeForNewFiles();
    }

    public static Snapshot buildDirState(SyncFilesystem fs, SyncState res, SyncState synced,
                                         Supplier<Boolean> isCancelled) throws IOException {
        SnapshotTracker version = new SnapshotTracker(new Snapshot(new HashMap<>()));
        List<Triple<String, FileWrapper, HashTree>> toUpdate = new ArrayList<>();
        AtomicLong downloadedSize = new AtomicLong(0);
        Set<String> presentFiles = ConcurrentHashMap.newKeySet();
        Set<String> presentDirs = ConcurrentHashMap.newKeySet();

        Optional<PublicKeyHash> baseDirWriter = fs.applyToSubtree(props -> {
            // a pause stops the walk, not just the transfers: scanning a large tree is
            // minutes of hashing and listing that the user has asked us to stop
            if (isCancelled.get())
                throw new RuntimeException("Sync cancelled while scanning " + props.relPath);
            String relPath = props.relPath;
            presentFiles.add(relPath);
            FileState atSync = synced.byPath(relPath);
            // a Peergos file carries its hash, which catches an edit within the same second at the same size, and
            // loses it when it is written
            boolean sameHashIfKnown = atSync == null || props.meta
                    .map(m -> m.getFileProperties().treeHash
                            .map(h -> h.rootHash.equals(atSync.hashTree.rootHash))
                            .orElse(false))
                    .orElse(true);
            if (atSync != null && atSync.modificationTime == props.modifiedTime && atSync.size == props.size && sameHashIfKnown) {
                res.add(atSync);
                if (props.meta.isPresent())
                    version.update(props.meta.get().version);
            } else {
                int chunkSize = chunkSizeFor(props.meta, atSync);
                HashTree hashTree = fs.hashFile(PathUtil.getUntrimmed(props.relPath), props.meta, relPath, synced, props.size, chunkSize);
                if (props.meta.isPresent()) {
                    version.update(props.meta.get().version);
                    Optional<HashBranch> remoteHash = props.meta.get().getFileProperties().treeHash;
                    if (!remoteHash.isPresent()) {
                        // collect new hashes to set in bulk later
                        List<Triple<String, FileWrapper, HashTree>> flush = null;
                        synchronized (toUpdate) {
                            toUpdate.add(new Triple<>(relPath, props.meta.get(), hashTree));
                            if (downloadedSize.addAndGet(props.size) > 100 * 1024 * 1024L) {
                                flush = new ArrayList<>(toUpdate);
                                toUpdate.clear();
                                downloadedSize.set(0);
                            }
                        }
                        if (flush != null) {
                            // set hashes inline if we've downloaded a lot of data to avoid cache thrashing if there is
                            // an exception. This way we continue to make progress.
                            log("REMOTE: Updating " + flush.size() + " hashes: " + flush.stream().limit(10).map(p -> p.left).collect(Collectors.toList()));
                            fs.setHashes(flush);
                        }
                    }
                }
                FileState fstat = new FileState(relPath, props.modifiedTime, props.size, hashTree, chunkSize);
                if (atSync != null && atSync.equalsIgnoreModtime(fstat)) {
                    res.add(atSync);
                } else
                    res.add(fstat);
            }
        }, p -> {
            if (isCancelled.get())
                throw new RuntimeException("Sync cancelled while scanning " + p.relPath);
            String relPath = p.relPath;
            presentDirs.add(relPath);
            p.meta.ifPresent(d -> version.update(d.version));
            res.addDir(relPath);
        }, synced.hasCompletedSync());

        for (String path : new HashSet<>(res.allFilePaths())) {
            if (! presentFiles.contains(path))
                res.remove(path);
        }
        for (String dir : new HashSet<>(res.getDirs())) {
            if (! presentDirs.contains(dir))
                res.removeDir(dir);
        }
        if (! toUpdate.isEmpty()) {
            log("REMOTE: Updating " + toUpdate.size() + " hashes: " + toUpdate.stream().limit(10).map(p -> p.left).collect(Collectors.toList()));
            fs.setHashes(toUpdate);
        }
        // don't track the entry point writer which we only have read access to
        if (baseDirWriter.isEmpty())
            return version.get();
        return version.get().remove(baseDirWriter.get());
    }

    private static Path getParent(Path p) {
        Path parent = p.getParent();
        if (parent == null)
            return Paths.get("");
        return parent;
    }
}