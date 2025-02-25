package peergos.server.sync;

import peergos.server.Builder;
import peergos.server.Main;
import peergos.server.UserService;
import peergos.server.storage.FileBlockCache;
import peergos.server.user.JavaImageThumbnailer;
import peergos.server.util.Args;
import peergos.server.util.Logging;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.corenode.HTTPCoreNode;
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
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DirectorySync {
    private static final Logger LOG = Logging.LOG();

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
            String cacheSize = args.getArg("block-cache-size-bytes");
            long blockCacheSizeBytes = cacheSize.endsWith("g") ?
                    Long.parseLong(cacheSize.substring(0, cacheSize.length() - 1)) * 1024L*1024*1024 :
                    Long.parseLong(cacheSize);
            NetworkAccess network = Builder.buildJavaNetworkAccess(serverURL, address.startsWith("https"), Optional.of("Peergos-" + UserService.CURRENT_VERSION + "-sync")).join()
                    .withStorage(s -> new UnauthedCachingStorage(s, new FileBlockCache(args.fromPeergosDir("block-cache-dir", "block-cache"), blockCacheSizeBytes), crypto.hasher));
            ThumbnailGenerator.setInstance(new JavaImageThumbnailer());
            List<String> links = Arrays.asList(args.getArg("links").split(","));
            Supplier<CompletableFuture<String>> linkUserPassword = () -> Futures.of("");
            List<Supplier<CompletableFuture<String>>> linkPasswords = IntStream.range(0, links.size())
                    .mapToObj(i -> linkUserPassword)
                    .collect(Collectors.toList());
            UserContext context = UserContext.fromSecretLinksV2(links, linkPasswords, network, crypto).join();
            List<String> linkPaths = links.stream()
                    .map(link -> UserContext.fromSecretLinksV2(Arrays.asList(link), Arrays.asList(linkUserPassword), network, crypto).join().getEntryPath().join())
                    .collect(Collectors.toList());
            int maxDownloadParallelism = args.getInt("max-parallelism", 32);
            int minFreeSpacePercent = args.getInt("min-free-space-percent", 5);

            PeergosSyncFS remote = new PeergosSyncFS(context);
            LocalFileSystem local = new LocalFileSystem(crypto.hasher);
            List<String> localDirs = Arrays.asList(args.getArg("local-dirs").split(","));
            List<SyncState> syncedStates = IntStream.range(0, linkPaths.size())
                    .mapToObj(i -> new JdbcTreeState(args.getPeergosDirChild("dir-sync-state-v3-" + ArrayOps.bytesToHex(Hash.sha256(linkPaths.get(i) + "///" + localDirs.get(i))) + ".sqlite").toString()))
                    .collect(Collectors.toList());
            if (links.size() != localDirs.size())
                throw new IllegalArgumentException("Mismatched number of local dirs and links");
            boolean oneRun = args.getBoolean("run-once", false);
            while (true) {
                try {
                    log("Syncing " + links.size() + " pairs of directories: " + IntStream.range(0, links.size()).mapToObj(i -> Arrays.asList(localDirs.get(i), linkPaths.get(i))).collect(Collectors.toList()));
                    for (int i=0; i < links.size(); i++) {
                        Path localDir = Paths.get(localDirs.get(i));
                        Path remoteDir = PathUtil.get(linkPaths.get(i));
                        SyncState syncedState = syncedStates.get(i);
                        log("Syncing " + localDir + " to+from " + remoteDir);
                        long t0 = System.currentTimeMillis();
                        String username = remoteDir.getName(0).toString();
                        PublicKeyHash owner = network.coreNode.getPublicKeyHash(username).join().get();
                        syncDirs(local, localDir, remote, remoteDir, owner, network, syncedState, maxDownloadParallelism, minFreeSpacePercent);
                        long t1 = System.currentTimeMillis();
                        log("Dir sync took " + (t1 - t0) / 1000 + "s");
                    }
                    if (oneRun)
                        break;
                    Thread.sleep(30_000);
                } catch (Exception e) {
                    e.printStackTrace();
                    LOG.log(Level.WARNING, e, e::getMessage);
                    Thread.sleep(30_000);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, e, e::getMessage);
            throw new RuntimeException(e);
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
            NetworkAccess network = Builder.buildJavaNetworkAccess(serverURL, address.startsWith("https"), Optional.of("Peergos-" + UserService.CURRENT_VERSION + "-sync")).join();
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

    public static void syncDirs(SyncFilesystem localFS, Path localDir,
                                SyncFilesystem remoteFS, Path remoteDir, PublicKeyHash owner, NetworkAccess network,
                                SyncState syncedVersions,
                                int maxParallelism,
                                int minPercentFreeSpace) throws IOException {
        // first complete any failed in progress copy ops
        List<CopyOp> ops = syncedVersions.getInProgressCopies();
        if (! ops.isEmpty())
            log("Rerunning failed copy operations...");
        for (CopyOp op : ops) {
            try {
                applyCopyOp(op.isLocalTarget ? remoteFS : localFS, op.isLocalTarget ? localFS : remoteFS, op, syncedVersions);
            } catch (FileNotFoundException e) {
                // A local file has been added and removed concurrently with us trying to copy it, ignore the copy op now
            }
            syncedVersions.finishCopies(List.of(op));
        }

        RamTreeState localState = new RamTreeState();
        buildDirState(localFS, localDir, localState, syncedVersions);
        log("Found " + localState.filesByPath.size() + " local files");

        Snapshot syncedVersion = syncedVersions.getSnapshot(remoteDir.toString());
        Snapshot remoteVersion = network == null ?
                new Snapshot(new HashMap<>()) :
                Futures.reduceAll(syncedVersion.versions.keySet(),
                        new Snapshot(new HashMap<>()),
                        (v, w) -> v.withWriter(owner, w, network),
                        (a, b) -> a.mergeAndOverwriteWith(b)).join();
        SyncState remoteState = remoteVersion.equals(syncedVersion) && ! remoteVersion.versions.isEmpty() ? syncedVersions : new RamTreeState();
        if (! remoteVersion.equals(syncedVersion) || remoteVersion.versions.isEmpty())
            buildDirState(remoteFS, remoteDir, remoteState, syncedVersions);
        log("Found " + remoteState.filesCount() + " remote files");

        TreeSet<String> allPaths = new TreeSet<>(localState.filesByPath.keySet());
        allPaths.addAll(remoteState.allFilePaths());
        Set<String> doneFiles = Collections.synchronizedSet(new HashSet<>());

        // upload new small files in a single bulk operation
        Set<String> smallFiles = new HashSet<>();
        Set<String> deletes = new HashSet<>();
        for (String relativePath : allPaths) {
            FileState synced = syncedVersions.byPath(relativePath);
            FileState local = localState.byPath(relativePath);
            FileState remote = remoteState.byPath(relativePath);
            boolean isSmallRemoteCopy = synced == null && remote == null && local.size < Chunk.MAX_SIZE;
            if (isSmallRemoteCopy)
                smallFiles.add(relativePath);

            boolean isLocalDelete = local == null && Objects.equals(remote, synced);
            if (isLocalDelete && remote != null) {
                List<FileState> byHash = localState.byHash(remote.hashTree.rootHash);
                Optional<FileState> remoteAtHashedPath = byHash.size() == 1 ?
                        Optional.ofNullable(remoteState.byPath(byHash.get(0).relPath)) :
                        Optional.empty();
                if (byHash.size() != 1 || ! remoteAtHashedPath.isEmpty())
                    deletes.add(relativePath);
            }
        }

        if (! smallFiles.isEmpty()) {
            log("Remote: bulk uploading " + smallFiles.size() + " small files");
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
                LocalDateTime modificationTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(local.modificationTime / 1000, 0), ZoneOffset.UTC);
                folder.files.add(new FileWrapper.FileUploadProperties(filename,
                        () -> {
                            try {
                                return localFS.getBytes(localDir.resolve(relPath), 0);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        },
                        (int)(local.size >> 32), (int) local.size, Optional.of(modificationTime), Optional.of(local.hashTree), false, true, x -> {
                    if (! uploadStarted.get()) {
                        log("REMOTE: Uploading " + relPath);
                        uploadStarted.set(true);
                    }
                }));
            }
            remoteFS.uploadSubtree(remoteDir, folders.values().stream());
            for (String relPath : smallFiles) {
                syncedVersions.add(localState.byPath(relPath));
            }
        }

        // do deletes in bulk
        if (! deletes.isEmpty()) {
            Map<String, Set<String>> byFolder = new HashMap<>();
            for (String relPath : deletes) {
                doneFiles.add(relPath);
                String folderPath = relPath.contains("/") ? relPath.substring(0, relPath.lastIndexOf("/")) : "";
                Set<String> folder = byFolder.get(folderPath);
                if (folder == null) {
                    folder = new HashSet<>();
                    byFolder.put(folderPath, folder);
                }
                String filename = relPath.substring(relPath.lastIndexOf("/") + 1);
                folder.add(filename);
            }
            byFolder.forEach((dir, files) -> {
                log("REMOTE: bulk deleting " + files.size() + " from " + remoteDir.resolve(dir));
                remoteFS.bulkDelete(remoteDir.resolve(dir), files);
                for (String file : files) {
                    String path = dir + (dir.isEmpty() ? "" : "/") + file;
                    log("REMOTE: deleted " + path);
                    syncedVersions.remove(path);
                }
            });
        }

        List<ForkJoinTask<?>> downloads = new ArrayList<>();
        AtomicInteger maxDownloadConcurrency = new AtomicInteger(maxParallelism);

        for (String relativePath : allPaths) {
            if (doneFiles.contains(relativePath))
                continue;
            FileState synced = syncedVersions.byPath(relativePath);
            FileState local = localState.byPath(relativePath);
            FileState remote = remoteState.byPath(relativePath);
            boolean isLocalCopy = synced == null && local == null;
            if (isLocalCopy) {
                while (maxDownloadConcurrency.get() == 0) {
                    try {Thread.sleep(100); } catch (InterruptedException e) {}
                }
                maxDownloadConcurrency.decrementAndGet();
                downloads.add(ForkJoinPool.commonPool().submit(() -> {
                    try {
                        syncFile(synced, local, remote, localFS, localDir, remoteFS, remoteDir, syncedVersions, localState, remoteState, doneFiles, minPercentFreeSpace);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    } finally {
                        maxDownloadConcurrency.incrementAndGet();
                    }
                }));
            } else
                syncFile(synced, local, remote, localFS, localDir, remoteFS, remoteDir, syncedVersions, localState, remoteState, doneFiles, minPercentFreeSpace);
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
            } else if (! hasLocal && ! hasRemote) {
                syncedVersions.removeDir(dirPath);
            } else if (hasLocal) {
                if (hasSynced) { // delete
                    log("Sync local: delete dir " + dirPath);
                    localFS.delete(localDir.resolve(dirPath));
                } else {
                    log("Sync Remote: mkdir " + dirPath);
                    remoteFS.mkdirs(remoteDir.resolve(dirPath));
                }
            } else {
                if (hasSynced) { // delete
                    log("Sync Remote: delete dir " + dirPath);
                    remoteFS.delete(remoteDir.resolve(dirPath));
                } else {
                    log("Sync Local: mkdir " + dirPath);
                    localFS.mkdirs(localDir.resolve(dirPath));
                }
            }
        }

        syncedVersions.setSnapshot(remoteDir.toString(), remoteVersion);
    }

    private static void log(String msg) {
        System.out.println(msg);
        LOG.info(msg);
    }

    public static void syncFile(FileState synced, FileState local, FileState remote,
                                SyncFilesystem localFs, Path localDir,
                                SyncFilesystem remoteFs, Path remoteDir,
                                SyncState syncedVersions, RamTreeState localTree, SyncState remoteTree,
                                Set<String> doneFiles, int minPercentFree) throws IOException {
        long totalSpace = Files.getFileStore(localDir).getTotalSpace();
        long freeSpace = Files.getFileStore(localDir).getUsableSpace();
        if (synced == null) {
            if (local == null) { // remotely added or renamed
                List<FileState> byHash = localTree.byHash(remote.hashTree.rootHash);
                Optional<FileState> remoteAtHashedPath = byHash.size() == 1 ?
                        Optional.ofNullable(remoteTree.byPath(byHash.get(0).relPath)) :
                        Optional.empty();
                if (byHash.size() == 1 && remoteAtHashedPath.isEmpty()) {// rename
                    FileState toMove = byHash.get(0);
                    log("Sync Local: Moving " + toMove.relPath + " ==> " + remote.relPath);
                    localFs.moveTo(localDir.resolve(toMove.relPath), localDir.resolve(remote.relPath));
                    syncedVersions.remove(toMove.relPath);
                    doneFiles.add(toMove.relPath);
                } else {
                    if ((freeSpace - remote.size) * 100 / totalSpace < minPercentFree)
                        throw new IllegalStateException("Not enough free space to sync and keep " + minPercentFree + "% free");
                    log("Sync Local: Copying " + remote.relPath);
                    List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(true, remoteDir.resolve(remote.relPath), localDir.resolve(remote.relPath), remote, null, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions);
                }
                syncedVersions.add(remote);
            } else if (remote == null) { // locally added or renamed
                List<FileState> byHash = remoteTree.byHash(local.hashTree.rootHash);
                Optional<FileState> localAtHashedPath = byHash.size() == 1 ?
                        Optional.ofNullable(localTree.byPath(byHash.get(0).relPath)) :
                        Optional.empty();
                if (byHash.size() == 1 && localAtHashedPath.isEmpty()) {// rename
                    FileState toMove = byHash.get(0);
                    log("Sync Remote: Moving " + toMove.relPath + " ==> " + local.relPath);
                    remoteFs.moveTo(remoteDir.resolve(toMove.relPath), remoteDir.resolve(Paths.get(local.relPath)));
                    syncedVersions.remove(toMove.relPath);
                    doneFiles.add(toMove.relPath);
                } else {
                    log("Sync Remote: Copying " + local.relPath);
                    List<Pair<Long, Long>> diffs = local.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(false, localDir.resolve(local.relPath), remoteDir.resolve(local.relPath), local, null, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(localFs, remoteFs, ops, syncedVersions);
                }
                syncedVersions.add(local);
            } else {
                // concurrent addition, rename 1 if contents are different
                if (remote.hashTree.rootHash.equals(local.hashTree.rootHash)) {
                    if (local.modificationTime > remote.modificationTime) {
                        log("Remote: Set mod time " + local.relPath);
                        remoteFs.setModificationTime(remoteDir.resolve(local.relPath), local.modificationTime);
                        syncedVersions.add(local);
                        return;
                    } else if (remote.modificationTime > local.modificationTime) {
                        log("Sync Local: Set mod time " + local.relPath);
                        localFs.setModificationTime(localDir.resolve(local.relPath), remote.modificationTime);
                        syncedVersions.add(remote);
                        return;
                    }
                    syncedVersions.add(local);
                } else {
                    if ((freeSpace - remote.size) * 100 / totalSpace < minPercentFree)
                        throw new IllegalStateException("Not enough free space to sync and keep " + minPercentFree + "% free. Conflict on " + local.relPath);
                    log("Sync Remote: Concurrent file addition: " + local.relPath + " renaming local version");
                    FileState renamed = renameOnConflict(localFs, localDir.resolve(local.relPath), local);
                    List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(true, remoteDir.resolve(remote.relPath), localDir.resolve(remote.relPath), remote, null, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions);
                    syncedVersions.add(remote);

                    List<Pair<Long, Long>> diffs2 = local.diffRanges(null);
                    List<CopyOp> ops2 = diffs2.stream()
                            .map(d -> new CopyOp(false, localDir.resolve(renamed.relPath), remoteDir.resolve(renamed.relPath), renamed, null, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(localFs, remoteFs, ops2, syncedVersions);
                    syncedVersions.add(renamed);
                }
            }
        } else { // synced != null
            if (synced.equals(local)) { // remote change only
                if (remote == null) { // deletion or rename
                    List<FileState> byHash = remoteTree.byHash(local.hashTree.rootHash);
                    Optional<FileState> localAtHashedPath = byHash.size() == 1 ?
                            Optional.ofNullable(localTree.byPath(byHash.get(0).relPath)) :
                            Optional.empty();
                    if (byHash.size() == 1 && localAtHashedPath.isEmpty()) {// rename
                        // we will do the local rename when we process the new remote entry
                    } else {
                        log("Sync Local: delete " + local.relPath);
                        localFs.delete(localDir.resolve(local.relPath));
                        syncedVersions.remove(local.relPath);
                    }
                } else if (remote.hashTree.rootHash.equals(local.hashTree.rootHash)) {
                    // already synced
                } else {
                    if (remote.size > local.size && (freeSpace + local.size - remote.size) * 100 / totalSpace < minPercentFree)
                        throw new IllegalStateException("Not enough free space to sync and keep " + minPercentFree + "% free");
                    log("Sync Local: Copying changes to " + remote.relPath);
                    List<Pair<Long, Long>> diffs = remote.diffRanges(local);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(true, remoteDir.resolve(remote.relPath), localDir.resolve(remote.relPath), remote, null, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions);
                    syncedVersions.add(remote);
                }
            } else if (synced.equals(remote)) { // local only change
                if (local == null) { // deletion or rename
                    List<FileState> byHash = localTree.byHash(remote.hashTree.rootHash);
                    Optional<FileState> remoteAtHashedPath = byHash.size() == 1 ?
                            Optional.ofNullable(remoteTree.byPath(byHash.get(0).relPath)) :
                            Optional.empty();
                    if (byHash.size() == 1 && remoteAtHashedPath.isEmpty()) {// rename
                        // we will do the local rename when we process the new remote entry
                    } else {
                        log("Sync Remote: delete " + remote.relPath);
                        remoteFs.delete(remoteDir.resolve(remote.relPath));
                        syncedVersions.remove(remote.relPath);
                    }
                } else if (remote.hashTree.rootHash.equals(local.hashTree.rootHash)) {
                    // already synced
                } else {
                    log("Sync Remote: Copying changes to " + local.relPath);
                    List<Pair<Long, Long>> diffs = local.diffRanges(remote);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(false, localDir.resolve(local.relPath), remoteDir.resolve(local.relPath), local, null, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(localFs, remoteFs, ops, syncedVersions);
                    remoteFs.setHash(remoteDir.resolve(local.relPath), local.hashTree, local.size);
                    syncedVersions.add(local);
                }
            } else { // concurrent change/deletion
                if (local == null && remote == null) {// concurrent deletes
                    log("Sync Concurrent delete on " + synced.relPath);
                    syncedVersions.remove(synced.relPath);
                    return;
                }
                if (local == null) { // local delete, copy changed remote
                    if ((freeSpace - remote.size) * 100 / totalSpace < minPercentFree)
                        throw new IllegalStateException("Not enough free space to sync and keep " + minPercentFree + "% free");
                    log("Sync Local: deleted, copying changed remote " + remote.relPath + ", Synced: " + synced.prettyPrint() + ", remote: " + remote.prettyPrint());
                    List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(true, remoteDir.resolve(remote.relPath), localDir.resolve(remote.relPath), remote, null, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions);
                    syncedVersions.add(remote);
                    return;
                }
                if (remote == null) { // remote delete, copy changed local
                    log("Sync Remote: deleted, copying changed local " + local.relPath);
                    List<Pair<Long, Long>> diffs = local.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(false, localDir.resolve(local.relPath), remoteDir.resolve(local.relPath), local, null, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(localFs, remoteFs, ops, syncedVersions);
                    syncedVersions.add(local);
                    return;
                }
                // concurrent change, rename one sync the other
                if ((freeSpace - remote.size) * 100 / totalSpace < minPercentFree)
                    throw new IllegalStateException("Not enough free space to sync and keep " + minPercentFree + "% free");
                // if local and remote are the same, update sync and return
                if (local.equals(remote)) {
                    syncedVersions.add(local);
                } else if (synced.hashTree.rootHash.equals(remote.hashTree.rootHash)) {
                    // synced content is same as remote, so just a local change
                    log("Sync Remote: Copying changes to " + local.relPath);
                    List<Pair<Long, Long>> diffs = local.diffRanges(remote);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(false, localDir.resolve(local.relPath), remoteDir.resolve(local.relPath), local, remote, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(localFs, remoteFs, ops, syncedVersions);
                    remoteFs.setHash(remoteDir.resolve(local.relPath), local.hashTree, local.size);
                    syncedVersions.add(local);
                } else {
                    log("Sync Remote: Concurrent change: " + local.relPath + " renaming local version");
                    FileState renamed = renameOnConflict(localFs, localDir.resolve(local.relPath), local);
                    List<Pair<Long, Long>> diffs = remote.diffRanges(null);
                    List<CopyOp> ops = diffs.stream()
                            .map(d -> new CopyOp(true, remoteDir.resolve(remote.relPath), localDir.resolve(remote.relPath), remote, null, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(remoteFs, localFs, ops, syncedVersions);
                    syncedVersions.add(remote);

                    List<Pair<Long, Long>> diffs2 = local.diffRanges(null);
                    List<CopyOp> ops2 = diffs2.stream()
                            .map(d -> new CopyOp(false, localDir.resolve(renamed.relPath), remoteDir.resolve(renamed.relPath), renamed, null, d.left, d.right))
                            .collect(Collectors.toList());
                    copyFileDiffAndTruncate(localFs, remoteFs, ops2, syncedVersions);
                    syncedVersions.add(renamed);
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
                if (! fs.exists(f.getParent().resolve(newName)))
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
                if (! fs.exists(f.getParent().resolve(newName)))
                    break;
                version++;
            }
        }
        Path newFile = f.getParent().resolve(newName);
        fs.moveTo(f, newFile);
        long newModified = fs.getLastModified(newFile);
        return new FileState(s.relPath.substring(0, s.relPath.length() - name.length()) + newName, newModified, s.size, s.hashTree);
    }

    public static void copyFileDiffAndTruncate(SyncFilesystem srcFs, SyncFilesystem targetFs, List<CopyOp> ops, SyncState syncDb) throws IOException {
        // first write the operation to the db
        syncDb.startCopies(ops);

        for (CopyOp op : ops) {
            applyCopyOp(srcFs, targetFs, op, syncDb);
        }

        // now remove the operation from the db
        syncDb.finishCopies(ops);
    }

    public static void applyCopyOp(SyncFilesystem srcFs, SyncFilesystem targetFs, CopyOp op, SyncState syncDb) throws IOException {
        log("COPY from " + op.source + " to " + op.target + " range=[" + op.diffStart +", " + op.diffEnd+"]");
        targetFs.mkdirs(op.target.getParent());
        long priorSize = op.targetState != null ? op.targetState.size : 0;
        long size = op.sourceState.size;
        long lastModified = op.sourceState.modificationTime;

        long start = op.diffStart;
        long end = op.diffEnd;
        try (AsyncReader fin = srcFs.getBytes(op.source, start)) {
            targetFs.setBytes(op.target, start, fin, end - start, Optional.of(op.sourceState.hashTree), Optional.of(LocalDateTime.ofInstant(Instant.ofEpochSecond(lastModified / 1000, 0), ZoneOffset.UTC)));
        }
        if (priorSize > size) {
            log("Sync Truncating file " + op.sourceState.relPath + " from " + priorSize + " to " + size);
            targetFs.truncate(op.target, size);
        }
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

    public static Snapshot buildDirState(SyncFilesystem fs, Path dir, SyncState res, SyncState synced) throws IOException {
        if (! fs.exists(dir))
            throw new IllegalStateException("Dir does not exist: " + dir);
        SnapshotTracker version = new SnapshotTracker(new Snapshot(new HashMap<>()));
        List<Triple<String, FileWrapper, HashTree>> toUpdate = new ArrayList<>();
        AtomicLong downloadedSize = new AtomicLong(0);
        fs.applyToSubtree(dir, props -> {
            String relPath = props.path.toString().substring(dir.toString().length() + 1);
            FileState atSync = synced.byPath(relPath);
            if (atSync != null && atSync.modificationTime == props.modifiedTime) {
                res.add(atSync);
            } else {
                HashTree hashTree = fs.hashFile(props.path, props.meta, relPath, synced);
                if (props.meta.isPresent()) {
                    version.update(props.meta.get().version);
                    Optional<HashBranch> remoteHash = props.meta.get().getFileProperties().treeHash;
                    if (! remoteHash.isPresent()) {
                        // collect new hashes to set in bulk later
                        toUpdate.add(new Triple<>(relPath, props.meta.get(), hashTree));
                        downloadedSize.addAndGet(props.size);
                        if (downloadedSize.get() > 100*1024*1024L) {
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
                res.add(fstat);
            }
        }, p -> {
            String relPath = p.path.toString().substring(dir.toString().length() + 1);
            p.meta.ifPresent(d -> version.update(d.version));
            res.addDir(relPath);
        });
        if (! toUpdate.isEmpty()) {
            log("REMOTE: Updating " + toUpdate.size() + " hashes: " + toUpdate.stream().limit(10).map(p -> p.left).collect(Collectors.toList()));
            fs.setHashes(toUpdate);
        }
        return version.get();
    }
}