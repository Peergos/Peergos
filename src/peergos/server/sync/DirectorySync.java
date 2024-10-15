package peergos.server.sync;

import peergos.server.util.Logging;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.util.ArrayOps;
import peergos.shared.util.Pair;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;

public class DirectorySync {
    private static final Logger LOG = Logging.LOG();

    public static void main(String[] args) throws Exception {
        TreeState syncedState = new TreeState();
        LocalFileSystem local = new LocalFileSystem();
        LocalFileSystem remote = new LocalFileSystem();
        while (true) {
            syncedState = syncDirs(local, Paths.get("sync/local"), remote, Paths.get("sync/remote"), syncedState);
//            Thread.sleep(30_000);
        }
    }

    public static TreeState syncDirs(SyncFilesystem localFS, Path localDir, SyncFilesystem remoteFS, Path remoteDir, TreeState syncedVersions) throws IOException {
        TreeState localState = new TreeState();
        buildDirState(localFS, localDir, localState, syncedVersions);

        TreeState remoteState = new TreeState();
        buildDirState(remoteFS, remoteDir, remoteState, syncedVersions);

        TreeState finalSyncedState = new TreeState();
        for (FileState local : localState.filesByPath.values()) {

            FileState synced = syncedVersions.filesByPath.get(local.relPath);
            FileState remote = remoteState.filesByPath.get(local.relPath);
            List<FileState> syncedResults = syncFile(localFS, localDir, remoteFS, remoteDir, synced, local, remote, localState, remoteState);
            syncedResults.forEach(finalSyncedState::add);
        }
        for (FileState remote : remoteState.filesByPath.values()) {
            if (! finalSyncedState.filesByPath.containsKey(remote.relPath)) {

                FileState synced = syncedVersions.filesByPath.get(remote.relPath);
                List<FileState> syncedResults = syncFile(localFS, localDir, remoteFS, remoteDir, synced, null, remote, localState, remoteState);
                syncedResults.forEach(finalSyncedState::add);
            }
        }
        return finalSyncedState;
    }

    public static List<FileState> syncFile(SyncFilesystem localFs, Path localDir,
                                           SyncFilesystem remoteFs, Path remoteDir,
                                           FileState synced, FileState local, FileState remote,
                                           TreeState localTree, TreeState remoteTree) throws IOException {
        if (synced == null) {
            if (local == null) { // remotely added or renamed
                List<FileState> byHash = localTree.byHash(remote.hash);
                if (byHash.size() == 1) {// rename
                    LOG.info("Local: Moving " + byHash.get(0).relPath + " ==> " + remote.relPath);
                    localFs.moveTo(localDir.resolve(byHash.get(0).relPath), localDir.resolve(remote.relPath));
                } else {
                    LOG.info("Local: Copying " + remote.relPath);
                    copyFileDiffAndTruncate(remoteFs, remoteDir.resolve(remote.relPath), remote, localFs, localDir.resolve(remote.relPath), null);
                }
                return List.of(remote);
            } else if (remote == null) { // locally added or renamed
                List<FileState> byHash = remoteTree.byHash(local.hash);
                if (byHash.size() == 1) {// rename
                    LOG.info("Remote: Moving " + byHash.get(0).relPath + " ==> " + local.relPath);
                    remoteFs.moveTo(remoteDir.resolve(byHash.get(0).relPath), remoteDir.resolve(Paths.get(local.relPath)));
                } else {
                    LOG.info("Remote: Copying " + local.relPath);
                    copyFileDiffAndTruncate(localFs, localDir.resolve(local.relPath), local, remoteFs, remoteDir.resolve(local.relPath), null);
                }
                return List.of(local);
            } else {
                // concurrent addition, rename 1 if contents are different
                if (remote.hash.equals(local.hash)) {
                    if (local.modificationTime >= remote.modificationTime) {
                        LOG.info("Remote: Set mod time " + local.relPath);
                        remoteFs.setModificationTime(remoteDir.resolve(local.relPath), local.modificationTime);
                        return List.of(local);
                    } else {
                        LOG.info("Local: Set mod time " + local.relPath);
                        localFs.setModificationTime(localDir.resolve(local.relPath), remote.modificationTime);
                        return List.of(remote);
                    }
                } else {
                    LOG.info("Remote: Concurrent file addition: " + local.relPath + " renaming local version");
                    FileState renamed = renameOnConflict(localFs, localDir.resolve(local.relPath), local);
                    copyFileDiffAndTruncate(remoteFs, remoteDir.resolve(remote.relPath), remote, localFs, localDir.resolve(remote.relPath), null);
                    copyFileDiffAndTruncate(localFs, localDir.resolve(renamed.relPath), renamed, remoteFs, remoteDir.resolve(renamed.relPath), null);
                    return List.of(renamed, remote);
                }
            }
        } else {
            if (synced.equals(local)) { // remote change only
                if (remote == null) { // deletion or rename
                    List<FileState> byHash = remoteTree.byHash(local.hash);
                    if (byHash.size() == 1) {// rename
                        // we will do the local rename when we process the new remote entry
                    } else {
                        LOG.info("Local: delete " + local.relPath);
                        localFs.delete(localDir.resolve(local.relPath));
                    }
                    return Collections.emptyList();
                } else if (remote.hash.equals(local.hash)) {
                    // already synced
                    return List.of(local);
                } else {
                    LOG.info("Local: Copying changes to " + remote.relPath);
                    copyFileDiffAndTruncate(remoteFs, remoteDir.resolve(remote.relPath), remote, localFs, localDir.resolve(remote.relPath), local);
                    return List.of(remote);
                }
            } else if (synced.equals(remote)) { // local only change
                if (local == null) { // deletion or rename
                    List<FileState> byHash = localTree.byHash(remote.hash);
                    if (byHash.size() == 1) {// rename
                        // we will do the local rename when we process the new remote entry
                    } else {
                        LOG.info("Remote: delete " + remote.relPath);
                        remoteFs.delete(remoteDir.resolve(remote.relPath));
                    }
                    return Collections.emptyList();
                } else if (remote.hash.equals(local.hash)) {
                    // already synced
                    return List.of(local);
                } else {
                    LOG.info("Remote: Copying changes to " + local.relPath);
                    copyFileDiffAndTruncate(localFs, localDir.resolve(local.relPath), local, remoteFs, remoteDir.resolve(local.relPath), remote);
                    return List.of(local);
                }
            } else { // concurrent change/deletion
                if (local == null && remote == null) {// concurrent deletes
                    LOG.info("Concurrent delete on " + synced.relPath);
                    return Collections.emptyList();
                }
                if (local == null) { // local delete, copy changed remote
                    LOG.info("Local: deleted, copying changed remote " + remote.relPath);
                    copyFileDiffAndTruncate(remoteFs, remoteDir.resolve(remote.relPath), remote, localFs, localDir.resolve(remote.relPath), local);
                    return List.of(remote);
                }
                if (remote == null) { // remote delete, copy changed local
                    LOG.info("Remote: deleted, copying changed local " + local.relPath);
                    copyFileDiffAndTruncate(localFs, localDir.resolve(local.relPath), local, remoteFs, remoteDir.resolve(local.relPath), remote);
                    return List.of(local);
                }
                // concurrent change, rename one sync the other
                LOG.info("Remote: Concurrent change: " + local.relPath + " renaming local version");
                FileState renamed = renameOnConflict(localFs, localDir.resolve(local.relPath), local);
                copyFileDiffAndTruncate(remoteFs, remoteDir.resolve(remote.relPath), remote, localFs, localDir.resolve(remote.relPath), local);
                copyFileDiffAndTruncate(localFs, localDir.resolve(renamed.relPath), local, remoteFs, remoteDir.resolve(renamed.relPath), remote);
                return List.of(renamed, remote);
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
        fs.moveTo(f, f.getParent().resolve(newName));
        return new FileState(s.relPath.substring(0, s.relPath.length() - name.length()) + newName, s.modificationTime, s.size, s.hash);
    }

    public static void copyFileDiffAndTruncate(SyncFilesystem srcFs, Path source, FileState sourceState,
                                               SyncFilesystem targetFs, Path target, FileState targetState) throws IOException {
        targetFs.mkdirs(target.getParent());
        long priorSize = targetFs.size(target);
        long size = srcFs.size(source);

        List<Pair<Long, Long>> diffRanges = sourceState.diffRanges(targetState);

        for (Pair<Long, Long> range : diffRanges) {
            long start = range.left;
            long end = range.right;
            try (AsyncReader fin = srcFs.getBytes(source, start)) {
                targetFs.setBytes(target, start, fin, end - start);
            }
            if (priorSize > size)
                targetFs.truncate(target, size);
        }
        targetFs.setModificationTime(target, srcFs.getLastModified(source));
    }

    static class Blake3state {
        public final byte[] hash;

        public Blake3state(byte[] hash) {
            this.hash = hash;
        }

        @Override
        public String toString() {
            return ArrayOps.bytesToHex(hash);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Blake3state that = (Blake3state) o;
            return Objects.deepEquals(hash, that.hash);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(hash);
        }
    }

    static class FileState {
        public final String relPath;
        public final long modificationTime;
        public final long size;
        public final Blake3state hash;

        public FileState(String relPath, long modificationTime, long size, Blake3state hash) {
            this.relPath = relPath;
            this.modificationTime = modificationTime;
            this.size = size;
            this.hash = hash;
        }

        public List<Pair<Long, Long>> diffRanges(FileState other) {
            if (other == null)
                return List.of(new Pair<>(0L, size));
            // TODO use bao tree to extract small diff ranges
            return List.of(new Pair<>(0L, size));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FileState fileState = (FileState) o;
            return modificationTime == fileState.modificationTime && Objects.equals(relPath, fileState.relPath) && Objects.equals(hash, fileState.hash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(relPath, modificationTime, hash);
        }
    }

    static class TreeState {
        public final Map<String, FileState> filesByPath = new HashMap<>();
        public final Map<Blake3state, List<FileState>> fileByHash = new HashMap<>();

        public void add(FileState fs) {
            filesByPath.put(fs.relPath, fs);
            fileByHash.putIfAbsent(fs.hash, new ArrayList<>());
            fileByHash.get(fs.hash).add(fs);
        }

        public List<FileState> byHash(Blake3state b3) {
            return fileByHash.getOrDefault(b3, Collections.emptyList());
        }
    }

    public static void buildDirState(SyncFilesystem fs, Path dir, TreeState res, TreeState synced) {
        fs.applyToSubtree(dir, f -> {
            String relPath = f.toString().substring(dir.toString().length() + 1);
            FileState atSync = synced.filesByPath.get(relPath);
            long modified = fs.getLastModified(f);
            if (atSync != null && atSync.modificationTime == modified) {
                res.add(atSync);
            } else {
                Blake3state b3 = fs.hashFile(f);
                FileState fstat = new FileState(relPath, modified, fs.size(f), b3);
                res.add(fstat);
            }
        }, d -> {});
    }
}