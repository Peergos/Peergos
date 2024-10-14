package peergos.server;

import peergos.server.crypto.hash.Blake3;
import peergos.shared.util.ArrayOps;
import peergos.shared.util.Pair;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;

public class DirectorySync {

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
                    localFs.moveTo(localDir.resolve(byHash.get(0).relPath), localDir.resolve(remote.relPath));
                } else
                    copyFileDiffAndTruncate(remoteFs, remoteDir.resolve(remote.relPath), remote, localFs, localDir.resolve(remote.relPath), null);
                return List.of(remote);
            } else if (remote == null) { // locally added or renamed
                List<FileState> byHash = remoteTree.byHash(local.hash);
                if (byHash.size() == 1) {// rename
                    remoteFs.moveTo(remoteDir.resolve(byHash.get(0).relPath), remoteDir.resolve(Paths.get(local.relPath)));
                } else
                    copyFileDiffAndTruncate(localFs, localDir.resolve(local.relPath), local, remoteFs, remoteDir.resolve(local.relPath), null);
                return List.of(local);
            } else {
                // concurrent addition, rename 1 if contents are different
                if (remote.hash.equals(local.hash)) {
                    if (local.modificationTime >= remote.modificationTime) {
                        remoteFs.setModificationTime(remoteDir.resolve(local.relPath), local.modificationTime);
                        return List.of(local);
                    } else {
                        localFs.setModificationTime(localDir.resolve(local.relPath), remote.modificationTime);
                        return List.of(remote);
                    }
                } else {
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
                    } else
                        localFs.delete(localDir.resolve(local.relPath));
                    return Collections.emptyList();
                } else {
                    copyFileDiffAndTruncate(remoteFs, remoteDir.resolve(remote.relPath), remote, localFs, localDir.resolve(remote.relPath), local);
                    return List.of(remote);
                }
            } else if (synced.equals(remote)) { // local only change
                if (local == null) { // deletion or rename
                    List<FileState> byHash = localTree.byHash(remote.hash);
                    if (byHash.size() == 1) {// rename
                        // we will do the local rename when we process the new remote entry
                    } else
                        remoteFs.delete(remoteDir.resolve(remote.relPath));
                    return Collections.emptyList();
                } else {
                    copyFileDiffAndTruncate(localFs, localDir.resolve(local.relPath), local, remoteFs, remoteDir.resolve(local.relPath), remote);
                    return List.of(local);
                }
            } else { // concurrent change/deletion
                if (local == null && remote == null) // concurrent deletes
                    return Collections.emptyList();
                if (local == null) { // local delete, copy changed remote
                    copyFileDiffAndTruncate(remoteFs, remoteDir.resolve(remote.relPath), remote, localFs, localDir.resolve(remote.relPath), local);
                    return List.of(remote);
                }
                if (remote == null) { // remote delete, copy changed local
                    copyFileDiffAndTruncate(localFs, localDir.resolve(local.relPath), local, remoteFs, remoteDir.resolve(local.relPath), remote);
                    return List.of(local);
                }
                // concurrent change, rename one sync the other
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

        byte[] buf = new byte[4096];

        for (Pair<Long, Long> range : diffRanges) {
            long start = range.left;
            long end = range.right;
            try (InputStream fin = srcFs.getBytes(source, start)) {
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

    interface SyncFilesystem {

        boolean exists(Path p);

        void mkdirs(Path p);

        void delete(Path p);

        void moveTo(Path src, Path target);

        long getLastModified(Path p);

        void setModificationTime(Path p, long t);

        long size(Path p);

        void truncate(Path p, long size) throws IOException;

        void setBytes(Path p, long fileOffset, InputStream data, long size) throws IOException;

        InputStream getBytes(Path p, long fileOffset) throws IOException;

        Blake3state hashFile(Path p);

        void applyToSubtree(Path start, Consumer<Path> file, Consumer<Path> dir);
    }

    static class LocalFileSystem implements SyncFilesystem {

        @Override
        public boolean exists(Path p) {
            return p.toFile().exists();
        }

        @Override
        public void mkdirs(Path p) {
            File f = p.toFile();
            if (f.exists() && f.isDirectory())
                return;
            if (! f.mkdirs())
                throw new IllegalStateException("Couldn't create " + p);
        }

        @Override
        public void delete(Path p) {
            p.toFile().delete();
        }

        @Override
        public void moveTo(Path src, Path target) {
            target.getParent().toFile().mkdirs();
            src.toFile().renameTo(target.toFile());
        }

        @Override
        public long getLastModified(Path p) {
            return p.toFile().lastModified();
        }

        @Override
        public void setModificationTime(Path p, long modificationTime) {
            p.toFile().setLastModified(modificationTime);
        }

        @Override
        public long size(Path p) {
            return p.toFile().length();
        }

        @Override
        public void truncate(Path p, long size) throws IOException {
            try (FileChannel channel = new FileOutputStream(p.toFile()).getChannel()) {
                channel.truncate(size);
            }
        }

        @Override
        public void setBytes(Path p, long fileOffset, InputStream fin, long size) throws IOException {
            try (FileOutputStream fout = new FileOutputStream(p.toFile());
                 FileChannel channel = fout.getChannel()) {
                channel.position(fileOffset);
                byte[] buf = new byte[4096];
                long done = 0;
                while (done < size) {
                    int read = fin.read(buf);
                    fout.write(buf, 0, read);
                    done += read;
                }
            }
        }

        @Override
        public InputStream getBytes(Path p, long fileOffset) throws IOException {
            FileInputStream fin = new FileInputStream(p.toFile());
            fin.getChannel().position(fileOffset);
            return fin;
        }

        @Override
        public Blake3state hashFile(Path p) {
            byte[] buf = new byte[4*1024];
            long size = p.toFile().length();
            Blake3 state = Blake3.initHash();

            try {
                FileInputStream fin = new FileInputStream(p.toFile());
                for (long i = 0; i < size; ) {
                    int read = fin.read(buf);
                    state.update(buf, 0, read);
                    i+= read;
                }

                byte[] hash = state.doFinalize(32);
                return new Blake3state(hash);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void applyToSubtree(Path start, Consumer<Path> file, Consumer<Path> dir) {
            for (File f : start.toFile().listFiles()) {
                if (f.isFile()) {
                    file.accept(f.toPath());
                } else if (f.isDirectory()) {
                    applyToSubtree(start.resolve(f.getName()), file, dir);
                }
            }
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