package peergos.server.sync;

import peergos.server.Builder;
import peergos.server.Main;
import peergos.server.UserService;
import peergos.server.net.ProxyChooser;
import peergos.server.storage.FileBlockCache;
import peergos.server.user.JavaImageThumbnailer;
import peergos.server.util.Args;
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
import peergos.shared.storage.UnauthedCachingStorage;
import peergos.shared.user.LinkProperties;
import peergos.shared.user.Snapshot;
import peergos.shared.user.TrieNodeImpl;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.ProxySelector;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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
    private static Set<String> IGNORED_FILENAMES = Stream.of(".DS_Store")
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
            ThumbnailGenerator.setInstance(new JavaImageThumbnailer());
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

    public static boolean syncDirs(List<String> links,
                                   List<String> localDirs, //could be paths or URIs
                                   List<Boolean> syncLocalDeletes,
                                   List<Boolean> syncRemoteDeletes,
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
        if (syncLocalDeletes.size() != links.size())
            throw new IllegalStateException("Incorrect number of sync-local-deletes!");
        if (syncRemoteDeletes.size() != links.size())
            throw new IllegalStateException("Incorrect number of sync-remote-deletes!");

        List<String> linkPaths = links.stream()
                .map(link -> UserContext.fromSecretLinksV2(Arrays.asList(link), Arrays.asList(() -> Futures.of("")), network, crypto).join().getEntryPath().join())
                .collect(Collectors.toList());

        List<Path> syncDbPaths = IntStream.range(0, linkPaths.size())
                .mapToObj(i -> getSyncStateDbPath(peergosDir, linkPaths.get(i), localDirs.get(i)))
                .collect(Collectors.toList());

        // delete any old sync dbs that are no longer referenced
        try (Stream<Path> kids = Files.list(peergosDir)) {
            kids
                    .filter(p -> p.getFileName().endsWith(".sqlite"))
                    .filter(p -> p.getFileName().startsWith("dir-sync-state-v3-"))
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

        List<Supplier<SyncState>> syncedStates = syncDbPaths.stream()
                .<Supplier<SyncState>>map(p -> () -> new JdbcTreeState(p.toString()))
                .collect(Collectors.toList());
        if (links.size() != localDirs.size())
            throw new IllegalArgumentException("Mismatched number of local dirs and links");

        while (true) {
            LOG.accept("Syncing " + links.size() + " pairs of directories: " + IntStream.range(0, links.size()).mapToObj(i -> Arrays.asList(localDirs.get(i), linkPaths.get(i))).collect(Collectors.toList()));
            boolean errored = false;
            for (int i=0; i < links.size(); i++) {
                SyncState syncedState = null;
                try {
                    if (status.isCancelled()) {
                        status.resume();
                        return false;
                    }
                    Path localDir = Paths.get(localDirs.get(i));
                    Path remoteDir = PathUtil.get(linkPaths.get(i));
                    syncedState = syncedStates.get(i).get();
                    log("Syncing " + localDir + " to+from " + remoteDir);
                    long t0 = System.currentTimeMillis();
                    String username = remoteDir.getName(0).toString();
                    PublicKeyHash owner = network.coreNode.getPublicKeyHash(username).join().get();
                    PeergosSyncFS remote = buildRemote(links.get(i), network, crypto);
                    SyncFilesystem local = localBuilder.apply(localDirs.get(i));
                    syncDir(local, remote, syncLocalDeletes.get(i), syncRemoteDeletes.get(i),
                            owner, network, syncedState, maxDownloadParallelism, minFreeSpacePercent, crypto, status::isCancelled, LOG);
                    long t1 = System.currentTimeMillis();
                    LOG.accept("Dir sync took " + (t1 - t0) / 1000 + "s");
                } catch (Exception e) {
                    errored = true;
                    ERROR.accept(e);
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
            if (!errored)
                ERROR.accept(null);
            if (oneRun)
                break;
            Threads.sleep(30_000);
        }
        return true;
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
                               Crypto crypto,
                               Supplier<Boolean> isCancelled,
                               Consumer<String> LOG) throws IOException {
        // first complete any failed in progress copy ops
        List<CopyOp> ops = syncedVersions.getInProgressCopies();
        if (! ops.isEmpty())
            log("Rerunning failed copy operations...");
        for (CopyOp op : ops) {
            try {
                applyCopyOp(op.isLocalTarget ? remoteFS : localFS, op.isLocalTarget ? localFS : remoteFS, op, isCancelled, LOG);
            } catch (FileNotFoundException e) {
                // A local file has been added and removed concurrently with us trying to copy it, ignore the copy op now
            }
            syncedVersions.finishCopies(List.of(op));
        }
        if (! syncedVersions.hasCompletedSync()) {
            // Do an incremental sync of only files to make progress quicker
            // We don't need to prehash entire dirs here,
            // because we are not trying to detect moves/renames
            SyncProgress progress = new SyncProgress(localFS.filesCount() - syncedVersions.filesCount());

            localFS.applyToSubtree(file -> {
                if (file.size > 1024*1024) { // avoid doing many small files in non bulk uploads
                    FileState synced = syncedVersions.byPath(file.relPath);
                    if (synced != null)
                        return;
                    Path p = PathUtil.get(file.relPath);
                    if (IGNORED_FILENAMES.contains(p.getFileName().toString()))
                        return;
                    if (! remoteFS.exists(p)) {
                        try {
                            LOG.accept("REMOTE: Uploading " + file.relPath + " " + progress);
                            HashTree hashTree = localFS.hashFile(p, Optional.empty(), file.relPath, syncedVersions);
                            LocalDateTime modified = LocalDateTime.ofInstant(Instant.ofEpochSecond(file.modifiedTime / 1000, 0), ZoneOffset.UTC);
                            CopyOp op = new CopyOp(false, localFS.resolve(file.relPath),
                                    remoteFS.resolve(file.relPath), new FileState(file.relPath, file.modifiedTime, file.size, hashTree), null,
                                    0, file.size, ResumeUploadProps.random(crypto));
                            syncedVersions.startCopies(List.of(op));
                            remoteFS.setBytes(p, 0, localFS.getBytes(p, 0), file.size, Optional.of(hashTree),
                                    Optional.of(modified), localFS.getThumbnail(p), op.props, isCancelled, LOG);
                            syncedVersions.finishCopies(List.of(op));
                            syncedVersions.add(new FileState(file.relPath, file.modifiedTime, file.size, hashTree));
                            progress.doneFile();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    } else {
                        HashTree remoteHash = remoteFS.hashFile(p, Optional.empty(), file.relPath, syncedVersions);
                        HashTree localHash = localFS.hashFile(p, Optional.empty(), file.relPath, syncedVersions);
                        if (localHash.equals(remoteHash)) {
                            syncedVersions.add(new FileState(file.relPath, file.modifiedTime, file.size, localHash));
                            progress.doneFile();
                            LOG.accept("Skipping identical remote file in initial sync: " + file.relPath + " " + progress);
                        }
                    }
                }
            }, dir -> {});
        }

        SyncState localState = new RamTreeState();
        long t1 = System.currentTimeMillis();
        buildDirState(localFS, localState, syncedVersions);
        long t2 = System.currentTimeMillis();
        LOG.accept("Found " + localState.filesCount() + " local files in " + (t2-t1)/1_000 + "s");

        Snapshot syncedVersion = syncedVersions.getSnapshot(remoteFS.getRoot());
        Snapshot remoteVersion = network == null ?
                new Snapshot(new HashMap<>()) :
                Futures.reduceAll(syncedVersion.versions.keySet(),
                        new Snapshot(new HashMap<>()),
                        (v, w) -> v.withWriter(owner, w, network),
                        Snapshot::mergeAndOverwriteWith).join();

        boolean remoteChange = ! remoteVersion.equals(syncedVersion) || remoteVersion.versions.isEmpty();
        SyncState remoteState = remoteChange ? new RamTreeState() : syncedVersions;
        long t3 = System.currentTimeMillis();
        if (remoteChange)
            remoteVersion = buildDirState(remoteFS, remoteState, syncedVersions);
        long t4 = System.currentTimeMillis();
        LOG.accept("Found " + remoteState.filesCount() + " remote files in " + (t4-t3)/1_000 + "s");

        TreeSet<String> allPaths = new TreeSet<>(localState.allFilePaths());
        allPaths.addAll(remoteState.allFilePaths());
        TreeSet<String> allChangedPaths = new TreeSet<>();

        // remove identical paths
        for (String path : allPaths) {
            FileState local = localState.byPath(path);
            FileState remote = remoteState.byPath(path);
            FileState synced = syncedVersions.byPath(path);
            if (Objects.equals(local, remote)) {
                if (synced == null)
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
            } else if (! IGNORED_FILENAMES.contains(PathUtil.get(path).getFileName().toString()))
                allChangedPaths.add(path);
        }
        Set<String> doneFiles = Collections.synchronizedSet(new HashSet<>());
        SyncProgress progress = new SyncProgress(allChangedPaths.size());

        // upload new small files in a single bulk operation
        Set<String> smallFiles = new HashSet<>();
        Set<String> localDeletes = new HashSet<>();
        for (String relativePath : allChangedPaths) {
            FileState synced = syncedVersions.byPath(relativePath);
            FileState local = localState.byPath(relativePath);
            FileState remote = remoteState.byPath(relativePath);
            boolean isSmallRemoteCopy = synced == null && remote == null && local.size < Chunk.MAX_SIZE;
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
                if (extraRemote.size() != extraLocal.size() || localAtHashedPath.isPresent())
                    smallFiles.add(relativePath);
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
                if (extraLocal.size() != extraRemote.size() || remoteAtHashedPath.isPresent())
                    localDeletes.add(relativePath);
            }
        }
        if (isCancelled.get())
            return;

        if (! smallFiles.isEmpty()) {

            LOG.accept("Remote: bulk uploading " + smallFiles.size() + " small files");
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
                            if (!uploadStarted.get()) {
                                LOG.accept("REMOTE: Uploading " + relPath + " " + progress);
                                uploadStarted.set(true);
                            }
                            if (! uploadEnded.get()) {
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

        // do deletes in bulk
        if (! localDeletes.isEmpty()) {
            Map<String, Set<String>> byFolder = new HashMap<>();
            for (String relPath : localDeletes) {
                doneFiles.add(relPath);
                if (! syncLocalDeletes) {
                    LOG.accept("Sync ignore local delete " + relPath + " " + progress);
                    syncedVersions.addLocalDelete(relPath);
                } else {
                    String folderPath = relPath.contains("/") ? relPath.substring(0, relPath.lastIndexOf("/")) : "";
                    Set<String> folder = byFolder.get(folderPath);
                    if (folder == null) {
                        folder = new HashSet<>();
                        byFolder.put(folderPath, folder);
                    }
                    String filename = relPath.substring(relPath.lastIndexOf("/") + 1);
                    folder.add(filename);
                }
            }
            byFolder.forEach((dir, files) -> {
                LOG.accept("REMOTE: bulk deleting " + files.size() + " from " + remoteFS.resolve(dir));
                remoteFS.bulkDelete(remoteFS.resolve(dir), files);
                for (String file : files) {
                    String path = dir + (dir.isEmpty() ? "" : "/") + file;
                    progress.doneFile();
                    LOG.accept("REMOTE: deleted " + path + " " + progress);
                    syncedVersions.remove(path);
                }
            });
        }

        List<ForkJoinTask<?>> downloads = new ArrayList<>();
        AtomicInteger maxDownloadConcurrency = new AtomicInteger(maxParallelism);

        for (String relativePath : allChangedPaths) {
            if (doneFiles.contains(relativePath))
                continue;
            if (isCancelled.get())
                return;
            FileState synced = syncedVersions.byPath(relativePath);
            FileState local = localState.byPath(relativePath);
            FileState remote = remoteState.byPath(relativePath);
            boolean isLocalCopy = synced == null && local == null;
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
                        syncFile(synced, local, remote, localFS, remoteFS, syncedVersions,
                                localState, remoteState, syncLocalDeletes, syncRemoteDeletes, doneFiles, minPercentFreeSpace, crypto, isCancelled, LOG, progress);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    } finally {
                        maxDownloadConcurrency.incrementAndGet();
                    }
                }));
            } else
                syncFile(synced, local, remote, localFS, remoteFS, syncedVersions, localState,
                        remoteState, syncLocalDeletes, syncRemoteDeletes, doneFiles, minPercentFreeSpace, crypto, isCancelled, LOG, progress);
        }

        for (ForkJoinTask<?> download : downloads) {
            download.join();
        }

        // all files are in sync, now sync dirs
        Comparator<String> longestFirst = (a, b) -> -a.compareTo(b);
        SortedSet<String> allDirs = new TreeSet<>(longestFirst);
        allDirs.addAll(remoteState.getDirs());
        allDirs.addAll(localState.getDirs());
        allDirs.addAll(syncedVersions.getDirs());
        for (String dirPath : allDirs) {
            boolean hasLocal = localState.hasDir(dirPath);
            boolean hasRemote = remoteState.hasDir(dirPath);
            boolean hasSynced = syncedVersions.hasDir(dirPath);
            if (hasLocal && hasRemote) {
                syncedVersions.addDir(dirPath);
            } else if (!hasLocal && !hasRemote) {
                syncedVersions.removeDir(dirPath);
            } else if (hasLocal) {
                if (hasSynced) { // delete
                    if (syncRemoteDeletes) {
                        LOG.accept("Sync local: delete dir " + dirPath);
                        localFS.delete(localFS.resolve(dirPath));
                        syncedVersions.removeDir(dirPath);
                    }
                } else {
                    LOG.accept("Sync Remote: mkdir " + dirPath);
                    remoteFS.mkdirs(remoteFS.resolve(dirPath));
                    syncedVersions.addDir(dirPath);
                }
            } else {
                if (hasSynced) { // delete
                    if (syncLocalDeletes) {
                        LOG.accept("Sync Remote: delete dir " + dirPath);
                        remoteFS.delete(remoteFS.resolve(dirPath));
                        syncedVersions.removeDir(dirPath);
                    }
                } else {
                    LOG.accept("Sync Local: mkdir " + dirPath);
                    localFS.mkdirs(localFS.resolve(dirPath));
                    syncedVersions.addDir(dirPath);
                }
            }
        }

        syncedVersions.setSnapshot(remoteFS.getRoot(), remoteVersion);
        syncedVersions.setCompletedSync(true);
    }

    public static void log(String msg) {
        System.out.println(msg);
        LOG.info(msg);
    }

    public static void syncFile(FileState synced, FileState local, FileState remote,
                                SyncFilesystem localFs,
                                SyncFilesystem remoteFs,
                                SyncState syncedVersions, SyncState localTree, SyncState remoteTree,
                                boolean syncLocalDeletes,
                                boolean syncRemoteDeletes,
                                Set<String> doneFiles,
                                int minPercentFree,
                                Crypto crypto,
                                Supplier<Boolean> isCancelled,
                                Consumer<String> LOG,
                                SyncProgress progress) throws IOException {
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

                if (extraLocal.size() == extraRemote.size() && remoteAtHashedPath.isEmpty()) {// rename
                    FileState toMove = extraLocal.get(index);
                    LOG.accept("Sync Local: Moving " + toMove.relPath + " ==> " + remote.relPath + " " + progress);
                    localFs.moveTo(localFs.resolve(toMove.relPath), localFs.resolve(remote.relPath));
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
                    Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions, isCancelled, LOG);
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

                if (extraRemote.size() == extraLocal.size() && localAtHashedPath.isEmpty()) {// rename
                    FileState toMove = extraRemote.get(index);
                    LOG.accept("Sync Remote: Moving " + toMove.relPath + " ==> " + local.relPath + " " + progress);
                    remoteFs.moveTo(remoteFs.resolve(toMove.relPath), Paths.get(local.relPath));
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
                    Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(localFs, remoteFs, ops, syncedVersions, isCancelled, LOG);
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
                    FileState renamed = renameOnConflict(localFs, localFs.resolve(local.relPath), local);
                    List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(true, remoteFs.resolve(remote.relPath), localFs.resolve(remote.relPath), remote, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                            .collect(Collectors.toList());
                    Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions, isCancelled, LOG);
                    syncedVersions.add(remote.withModtime(actualModTime));

                    List<Pair<Long, Long>> diffs2 = local.diffRanges(null);
                    List<CopyOp> ops2 = diffs2.stream()
                            .map(d -> new CopyOp(false, localFs.resolve(renamed.relPath), remoteFs.resolve(renamed.relPath), renamed, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                            .collect(Collectors.toList());
                    Optional<LocalDateTime> actualModTime2 = copyFileDiffAndTruncate(localFs, remoteFs, ops2, syncedVersions, isCancelled, LOG);
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
                    if (extraRemote.size() == extraLocal.size() && localAtHashedPath.isEmpty()) {// rename
                        // we will do the local rename when we process the new remote entry
                    } else {
                        if (syncRemoteDeletes) {
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
                    if (syncedVersions.hasRemoteDelete(remote.relPath)) {
                        // remote file was deleted, then a different file with same path was added. Rename local and copy remote
                        LOG.accept("Sync Remote: Concurrent change: " + local.relPath + " renaming local version" + " " + progress);
                        FileState renamed = renameOnConflict(localFs, localFs.resolve(local.relPath), local);
                        List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                        List<CopyOp> ops = diffs.stream()
                                .map(d -> new CopyOp(true, remoteFs.resolve(remote.relPath), localFs.resolve(remote.relPath), remote, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                                .collect(Collectors.toList());
                        Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions, isCancelled, LOG);
                        syncedVersions.add(remote.withModtime(actualModTime));
                        progress.doneFile();

                        List<Pair<Long, Long>> diffs2 = local.diffRanges(null);
                        List<CopyOp> ops2 = diffs2.stream()
                                .map(d -> new CopyOp(false, localFs.resolve(renamed.relPath), remoteFs.resolve(renamed.relPath), renamed, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                                .collect(Collectors.toList());
                        Optional<LocalDateTime> actualModTime2 = copyFileDiffAndTruncate(localFs, remoteFs, ops2, syncedVersions, isCancelled, LOG);
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
                                .map(d -> new CopyOp(true, remoteFs.resolve(remote.relPath), localFs.resolve(remote.relPath), remote, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                                .collect(Collectors.toList());
                        Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions, isCancelled, LOG);
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
                    if (extraLocal.size() == extraRemote.size() && remoteAtHashedPath.isEmpty()) {// rename
                        // we will do the local rename when we process the new remote entry
                    } else {
                        if (syncLocalDeletes) {
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
                    if (syncedVersions.hasLocalDelete(local.relPath)) {
                        // local file was deleted, then a different file with same path was added. Keep remote, rename local.
                        LOG.accept("Sync Remote: Concurrent change: " + local.relPath + " renaming different local version after local delete" + " " + progress);
                        FileState renamed = renameOnConflict(localFs, localFs.resolve(local.relPath), local);
                        List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                        List<CopyOp> ops = diffs.stream()
                                .map(d -> new CopyOp(true, remoteFs.resolve(remote.relPath), localFs.resolve(remote.relPath), remote, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                                .collect(Collectors.toList());
                        Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions, isCancelled, LOG);
                        syncedVersions.add(remote.withModtime(actualModTime));
                        progress.doneFile();

                        List<Pair<Long, Long>> diffs2 = local.diffRanges(null);
                        List<CopyOp> ops2 = diffs2.stream()
                                .map(d -> new CopyOp(false, localFs.resolve(renamed.relPath), remoteFs.resolve(renamed.relPath), renamed, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                                .collect(Collectors.toList());
                        Optional<LocalDateTime> actualModTime2 = copyFileDiffAndTruncate(localFs, remoteFs, ops2, syncedVersions, isCancelled, LOG);
                        syncedVersions.add(renamed.withModtime(actualModTime2));
                        syncedVersions.removeLocalDelete(local.relPath);
                        progress.doneFile();
                    } else {
                        LOG.accept("Sync Remote: Copying changes to " + local.relPath + " " + progress);
                        List<Pair<Long, Long>> diffs = local.diffRanges(remote);
                        List<CopyOp> ops = diffs.stream()
                                .map(d -> new CopyOp(false, localFs.resolve(local.relPath), remoteFs.resolve(local.relPath), local, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                                .collect(Collectors.toList());
                        Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(localFs, remoteFs, ops, syncedVersions, isCancelled, LOG);
                        remoteFs.setHash(remoteFs.resolve(local.relPath), local.hashTree, local.size);
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
                    Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions, isCancelled, LOG);
                    syncedVersions.add(remote.withModtime(actualModTime));
                    progress.doneFile();
                    if (! syncLocalDeletes && syncedVersions.hasLocalDelete(remote.relPath))
                        syncedVersions.removeLocalDelete(remote.relPath);
                    return;
                }
                if (remote == null) { // remote delete, copy changed local
                    LOG.accept("Sync Remote: deleted, copying changed local " + local.relPath + " " + progress);
                    List<Pair<Long, Long>> diffs = local.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(false, localFs.resolve(local.relPath), remoteFs.resolve(local.relPath), local, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                            .collect(Collectors.toList());
                    Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(localFs, remoteFs, ops, syncedVersions, isCancelled, LOG);
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
                if (local.equals(remote)) {
                    syncedVersions.add(local);
                } else if (synced.hashTree.rootHash.equals(remote.hashTree.rootHash)) {
                    // synced content is same as remote, so just a local change
                    LOG.accept("Sync Remote: Copying changes to " + local.relPath + " " + progress);
                    List<Pair<Long, Long>> diffs = local.diffRanges(remote);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(false, localFs.resolve(local.relPath), remoteFs.resolve(local.relPath), local, remote, d.left, d.right, ResumeUploadProps.random(crypto)))
                            .collect(Collectors.toList());
                    Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(localFs, remoteFs, ops, syncedVersions, isCancelled, LOG);
                    remoteFs.setHash(remoteFs.resolve(local.relPath), local.hashTree, local.size);
                    syncedVersions.add(local.withModtime(actualModTime));
                    progress.doneFile();
                } else {
                    LOG.accept("Sync Remote: Concurrent change: " + local.relPath + " renaming local version" + " " + progress);
                    FileState renamed = renameOnConflict(localFs, localFs.resolve(local.relPath), local);
                    List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(true, remoteFs.resolve(remote.relPath), localFs.resolve(remote.relPath), remote, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                            .collect(Collectors.toList());
                    Optional<LocalDateTime> actualModTime = copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions, isCancelled, LOG);
                    syncedVersions.add(remote.withModtime(actualModTime));
                    progress.doneFile();

                    List<Pair<Long, Long>> diffs2 = local.diffRanges(null);
                    List<CopyOp> ops2 = diffs2.stream()
                            .map(d -> new CopyOp(false, localFs.resolve(renamed.relPath), remoteFs.resolve(renamed.relPath), renamed, null, d.left, d.right, ResumeUploadProps.random(crypto)))
                            .collect(Collectors.toList());
                    Optional<LocalDateTime> actualModTime2 = copyFileDiffAndTruncate(localFs, remoteFs, ops2, syncedVersions, isCancelled, LOG);
                    syncedVersions.add(renamed.withModtime(actualModTime2));
                    progress.doneFile();
                }
            }
        }
    }

    public static FileState renameOnConflict(SyncFilesystem fs, Path f, FileState s) {
        String name = f.getFileName().toString();
        String newName;
        if (name.contains("[conflict-")) {
            int start = name.lastIndexOf("[conflict-");
            int end = name.indexOf("]", start);
            int version = Integer.parseInt(name.substring(start + "[conflict-".length(), end));
            while (true) {
                newName = name.substring(0, start) + "[conflict-" + (version + 1) + "]" + name.substring(end + 1);
                if (! fs.exists(getParent(f).resolve(newName)))
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
                if (! fs.exists(getParent(f).resolve(newName)))
                    break;
                version++;
            }
        }
        Path newFile = getParent(f).resolve(newName);
        fs.moveTo(f, newFile);
        long newModified = fs.getLastModified(newFile);
        return new FileState(s.relPath.substring(0, s.relPath.length() - name.length()) + newName, newModified, s.size, s.hashTree);
    }

    public static Optional<LocalDateTime> copyFileDiffAndTruncate(SyncFilesystem srcFs,
                                                                  SyncFilesystem targetFs,
                                                                  List<CopyOp> ops,
                                                                  SyncState syncDb,
                                                                  Supplier<Boolean> isCancelled,
                                                                  Consumer<String> progress) throws IOException {
        // first write the operation to the db
        syncDb.startCopies(ops);

        Optional<LocalDateTime> res = Optional.empty();

        for (CopyOp op : ops) {
            Optional<LocalDateTime> mod = applyCopyOp(srcFs, targetFs, op, isCancelled, progress);
            res = res.isEmpty() ? mod : mod.isEmpty() ? res : res.map(t -> mod.get().isAfter(t) ? mod.get() : t);
        }

        // now remove the operation from the db
        syncDb.finishCopies(ops);
        return res;
    }

    public static Optional<LocalDateTime> applyCopyOp(SyncFilesystem srcFs, SyncFilesystem targetFs, CopyOp op, Supplier<Boolean> isCancelled, Consumer<String> progress) throws IOException {
        if (isCancelled.get())
            return Optional.empty();
        log("COPY from " + op.source + " to " + op.target + " range=[" + op.diffStart +", " + op.diffEnd+"]");
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
            res = targetFs.setBytes(op.target, start, fin, end - start, Optional.of(op.sourceState.hashTree),
                    Optional.of(modified), thumbnail, op.props, isCancelled, progress);
        }
        if (isCancelled.get())
            return res;
        if (priorSize > size) {
            log("Sync Truncating file " + op.sourceState.relPath + " from " + priorSize + " to " + size);
            targetFs.truncate(op.target, size);
        }
        return res;
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

    public static Snapshot buildDirState(SyncFilesystem fs, SyncState res, SyncState synced) throws IOException {
        SnapshotTracker version = new SnapshotTracker(new Snapshot(new HashMap<>()));
        List<Triple<String, FileWrapper, HashTree>> toUpdate = new ArrayList<>();
        AtomicLong downloadedSize = new AtomicLong(0);

        Optional<PublicKeyHash> baseDirWriter = fs.applyToSubtree(props -> {
            String relPath = props.relPath;
            FileState atSync = synced.byPath(relPath);
            if (atSync != null && atSync.modificationTime == props.modifiedTime && atSync.size == props.size) {
                res.add(atSync);
                if (props.meta.isPresent())
                    version.update(props.meta.get().version);
            } else {
                HashTree hashTree = fs.hashFile(PathUtil.get(props.relPath), props.meta, relPath, synced);
                if (props.meta.isPresent()) {
                    version.update(props.meta.get().version);
                    Optional<HashBranch> remoteHash = props.meta.get().getFileProperties().treeHash;
                    if (!remoteHash.isPresent()) {
                        // collect new hashes to set in bulk later
                        toUpdate.add(new Triple<>(relPath, props.meta.get(), hashTree));
                        downloadedSize.addAndGet(props.size);
                        if (downloadedSize.get() > 100 * 1024 * 1024L) {
                            // set hashes inline if we've downloaded a lot of data to avoid cache thrashing if there is
                            // an exception. This way we continue to make progress.
                            log("REMOTE: Updating " + toUpdate.size() + " hashes: " + toUpdate.stream().limit(10).map(p -> p.left).collect(Collectors.toList()));
                            fs.setHashes(toUpdate);
                            toUpdate.clear();
                            downloadedSize.set(0);
                        }
                    }
                }
                FileState fstat = new FileState(relPath, props.modifiedTime, props.size, hashTree);
                if (atSync != null && atSync.equalsIgnoreModtime(fstat)) {
                    res.add(atSync);
                } else
                    res.add(fstat);
            }
        }, p -> {
            String relPath = p.relPath;
            p.meta.ifPresent(d -> version.update(d.version));
            res.addDir(relPath);
        });
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