package peergos.server.sync;

import peergos.shared.user.fs.Blake3state;

import java.util.*;

class RamTreeState implements SyncState {
    public final Map<String, FileState> filesByPath = new HashMap<>();
    public final Map<Blake3state, List<FileState>> fileByHash = new HashMap<>();

    public void add(FileState fs) {
        filesByPath.put(fs.relPath, fs);
        fileByHash.putIfAbsent(fs.hash, new ArrayList<>());
        fileByHash.get(fs.hash).add(fs);
    }

    @Override
    public FileState byPath(String path) {
        return filesByPath.get(path);
    }

    public List<FileState> byHash(Blake3state b3) {
        return fileByHash.getOrDefault(b3, Collections.emptyList());
    }
}
