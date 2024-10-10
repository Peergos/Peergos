package peergos.server;

import peergos.server.crypto.hash.Blake3;
import peergos.shared.util.ArrayOps;
import peergos.shared.util.Pair;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.*;

public class DirectorySync {

    public static void main(String[] args) throws Exception {
        File local = new File("sync/local");
        File remote = new File("sync/remote");
        TreeState syncedState = new TreeState();
        while (true) {
            syncedState = syncDirs(local, remote, syncedState);
            Thread.sleep(30_000);
        }
    }

    public static TreeState syncDirs(File localDir, File remoteDir, TreeState syncedVersions) throws IOException {
        TreeState localState = new TreeState();
        buildDirState("", localDir, localState, syncedVersions);

        TreeState remoteState = new TreeState();
        buildDirState("", remoteDir, remoteState, syncedVersions);

        TreeState finalSyncedState = new TreeState();
        for (FileState local : localState.filesByPath.values()) {

            FileState synced = syncedVersions.filesByPath.get(local.relPath);
            FileState remote = remoteState.filesByPath.get(local.relPath);
            List<FileState> syncedResults = syncFile(localDir, remoteDir, synced, local, remote);
            syncedResults.forEach(finalSyncedState::add);
        }
        for (FileState remote : remoteState.filesByPath.values()) {
            if (! finalSyncedState.filesByPath.containsKey(remote.relPath)) {

                FileState synced = syncedVersions.filesByPath.get(remote.relPath);
                List<FileState> syncedResults = syncFile(localDir, remoteDir, synced, null, remote);
                syncedResults.forEach(finalSyncedState::add);
            }
        }
        return finalSyncedState;
    }

    public static List<FileState> syncFile(File localDir, File remoteDir, FileState synced, FileState local, FileState remote) throws IOException {
        if (synced == null) {
            if (local == null) { // remotely added
                copyFileDiffAndTruncate(remoteDir.toPath().resolve(remote.relPath).toFile(), remote, localDir.toPath().resolve(remote.relPath).toFile(), null);
                return List.of(remote);
            } else if (remote == null) { // locally added
                copyFileDiffAndTruncate(localDir.toPath().resolve(local.relPath).toFile(), local, remoteDir.toPath().resolve(local.relPath).toFile(), null);
                return List.of(local);
            } else {
                // concurrent addition, rename 1 if contents are different
                if (remote.hash.equals(local.hash)) {
                    if (local.modificationTime >= remote.modificationTime) {
                        setModificationTime(remoteDir.toPath().resolve(local.relPath).toFile(), local.modificationTime);
                        return List.of(local);
                    } else {
                        setModificationTime(localDir.toPath().resolve(local.relPath).toFile(), remote.modificationTime);
                        return List.of(remote);
                    }
                } else {
                    FileState renamed = renameOnConflict(localDir.toPath().resolve(local.relPath).toFile(), local);
                    copyFileDiffAndTruncate(remoteDir.toPath().resolve(remote.relPath).toFile(), remote, localDir.toPath().resolve(remote.relPath).toFile(), null);
                    copyFileDiffAndTruncate(localDir.toPath().resolve(renamed.relPath).toFile(), renamed, remoteDir.toPath().resolve(renamed.relPath).toFile(), null);
                    return List.of(renamed, remote);
                }
            }
        } else {
            if (synced.equals(local)) { // remote change only
                if (remote == null) { // deletion
                    deleteFile(localDir.toPath().resolve(local.relPath).toFile());
                    return Collections.emptyList();
                } else {
                    copyFileDiffAndTruncate(remoteDir.toPath().resolve(remote.relPath).toFile(), remote, localDir.toPath().resolve(remote.relPath).toFile(), local);
                    return List.of(remote);
                }
            } else if (synced.equals(remote)) { // local only change
                if (local == null) { // deletion
                    deleteFile(remoteDir.toPath().resolve(remote.relPath).toFile());
                    return Collections.emptyList();
                } else {
                    copyFileDiffAndTruncate(localDir.toPath().resolve(local.relPath).toFile(), local, remoteDir.toPath().resolve(local.relPath).toFile(), remote);
                    return List.of(local);
                }
            } else { // concurrent change, rename one
                FileState renamed = renameOnConflict(localDir.toPath().resolve(local.relPath).toFile(), local);
                copyFileDiffAndTruncate(remoteDir.toPath().resolve(remote.relPath).toFile(), remote, localDir.toPath().resolve(remote.relPath).toFile(), local);
                copyFileDiffAndTruncate(localDir.toPath().resolve(renamed.relPath).toFile(), local, remoteDir.toPath().resolve(renamed.relPath).toFile(), remote);
                return List.of(renamed, remote);
            }
        }
    }

    public static void deleteFile(File f) {
        f.delete();
    }

    public static FileState renameOnConflict(File f, FileState s) {
        String name = f.getName();
        String newName;
        if (name.contains("[conflict-")) {
            int start = name.lastIndexOf("[conflict-");
            int end = name.indexOf("]", start);
            int version = Integer.parseInt(name.substring(start + "[conflict-".length(), end));
            newName = name.substring(0, start) + "[conflict-" + (version + 1) + "]" + name.substring(end + 1);
        } else {
            if (name.contains(".")) {
                int dot = name.lastIndexOf(".");
                newName = name.substring(0, dot) + "[conflict-0]" + name.substring(dot);
            } else
                newName = name + "[conflict-0]";
        }
        f.renameTo(f.toPath().getParent().resolve(newName).toFile());
        return new FileState(s.relPath.substring(0, s.relPath.length() - name.length()) + newName, s.modificationTime, s.size, s.hash);
    }

    public static void setModificationTime(File f, long modificationTime) {
        f.setLastModified(modificationTime);
    }

    public static void copyFileDiffAndTruncate(File source, FileState sourceState, File target, FileState targetState) throws IOException {
        target.getParentFile().mkdirs();
        long priorSize = target.length();
        long size = source.length();

        List<Pair<Long, Long>> diffRanges = sourceState.diffRanges(targetState);

        byte[] buf = new byte[4096];

        try (FileInputStream fin = new FileInputStream(source);
             FileOutputStream fout = new FileOutputStream(target);
             FileChannel channel = fout.getChannel()) {
            for (Pair<Long, Long> range : diffRanges) {
                long start = range.left;
                long end = range.right;
                fin.getChannel().position(start);
                channel.position(start);
                long done = 0;
                while (done < end - start) {
                    int read = fin.read(buf);
                    fout.write(buf, 0, read);
                    done += read;
                }
                if (priorSize > size)
                    channel.truncate(size);
            }
        }
        setModificationTime(target, source.lastModified());
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
        public final Map<Blake3state, FileState> fileByHash = new HashMap<>();

        public void add(FileState fs) {
            filesByPath.put(fs.relPath, fs);
            fileByHash.put(fs.hash, fs);
        }
    }

    public static void buildDirState(String pathPrefix, File localDir, TreeState res, TreeState synced) {
        for (File file : localDir.listFiles()) {
            if (file.isFile()) {
                String relPath = pathPrefix + file.getName();
                FileState atSync = synced.filesByPath.get(relPath);
                long modified = file.lastModified();
                if (atSync != null && atSync.modificationTime == modified) {
                    res.add(atSync);
                } else {
                    Blake3state b3 = hashFile(file);
                    FileState fstat = new FileState(relPath, modified, file.length(), b3);
                    res.add(fstat);
                }
            } else if (file.isDirectory()) {
                buildDirState(pathPrefix + file.getName() + "/", file, res, synced);
            }
        }
    }

    public static Blake3state hashFile(File f) {
        byte[] buf = new byte[4*1024];
        long size = f.length();
        Blake3 state = Blake3.initHash();

        try {
            FileInputStream fin = new FileInputStream(f);
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
}
