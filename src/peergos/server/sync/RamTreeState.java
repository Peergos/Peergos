package peergos.server.sync;

import peergos.shared.user.Snapshot;
import peergos.shared.user.fs.RootHash;

import java.util.*;

class RamTreeState implements SyncState {
    public final Map<String, FileState> filesByPath = new HashMap<>();
    public final Map<RootHash, List<FileState>> fileByHash = new HashMap<>();
    private final Set<String> dirs = new HashSet<>();
    private final List<CopyOp> inProgress = new ArrayList<>();
    private final Map<String, Snapshot> versions = new HashMap<>();

    @Override
    public long filesCount() {
        return filesByPath.size();
    }

    @Override
    public Set<String> allFilePaths() {
        return filesByPath.keySet();
    }

    @Override
    public synchronized void setSnapshot(String basePath, Snapshot s) {
        versions.put(basePath, s);
    }

    @Override
    public synchronized Snapshot getSnapshot(String basePath) {
        return versions.getOrDefault(basePath, new Snapshot(new HashMap<>()));
    }

    @Override
    public synchronized void add(FileState fs) {
        filesByPath.put(fs.relPath, fs);
        fileByHash.putIfAbsent(fs.hashTree.rootHash, new ArrayList<>());
        fileByHash.get(fs.hashTree.rootHash).add(fs);
    }

    public synchronized void addDir(String path) {
        dirs.add(path);
    }

    public synchronized void removeDir(String path) {
        dirs.remove(path);
    }

    public synchronized boolean hasDir(String path) {
        return dirs.contains(path);
    }

    public synchronized Set<String> getDirs() {
        return dirs;
    }

    @Override
    public synchronized void remove(String path) {
        FileState v = filesByPath.remove(path);
        if (v != null) {
            List<FileState> byHash = fileByHash.get(v.hashTree.rootHash);
            if (byHash.size() == 1)
                fileByHash.remove(v.hashTree.rootHash);
            else
                byHash.remove(v);
        }
    }

    @Override
    public synchronized FileState byPath(String path) {
        return filesByPath.get(path);
    }

    public List<FileState> byHash(RootHash b3) {
        return fileByHash.getOrDefault(b3, Collections.emptyList());
    }

    @Override
    public synchronized void startCopies(List<CopyOp> ops) {
        inProgress.addAll(ops);
    }

    @Override
    public synchronized void finishCopies(List<CopyOp> ops) {
        inProgress.removeAll(ops);
    }

    @Override
    public synchronized List<CopyOp> getInProgressCopies() {
        return inProgress;
    }
}
