package peergos.server.sync;

import peergos.shared.user.fs.Blake3state;

import java.util.*;

class RamTreeState implements SyncState {
    public final Map<String, FileState> filesByPath = new HashMap<>();
    public final Map<Blake3state, List<FileState>> fileByHash = new HashMap<>();
    private final Set<String> dirs = new HashSet<>();
    private final List<CopyOp> inProgress = new ArrayList<>();

    @Override
    public synchronized void add(FileState fs) {
        filesByPath.put(fs.relPath, fs);
        fileByHash.putIfAbsent(fs.hash, new ArrayList<>());
        fileByHash.get(fs.hash).add(fs);
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
            List<FileState> byHash = fileByHash.get(v.hash);
            if (byHash.size() == 1)
                fileByHash.remove(v.hash);
            else
                byHash.remove(v);
        }
    }

    @Override
    public synchronized FileState byPath(String path) {
        return filesByPath.get(path);
    }

    public List<FileState> byHash(Blake3state b3) {
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
