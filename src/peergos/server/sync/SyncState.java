package peergos.server.sync;

import peergos.shared.user.fs.Blake3state;

import java.util.List;
import java.util.Set;

public interface SyncState {

    void add(FileState fs);

    void remove(String path);

    FileState byPath(String path);

    List<FileState> byHash(Blake3state b3);

    void addDir(String path);

    void removeDir(String path);

    boolean hasDir(String path);

    Set<String> getDirs();

    void startCopies(List<CopyOp> ops);

    void finishCopies(List<CopyOp> ops);

    List<CopyOp> getInProgressCopies();
}
