package peergos.server.sync;

import peergos.shared.user.Snapshot;
import peergos.shared.user.fs.RootHash;

import java.io.Closeable;
import java.util.List;
import java.util.Set;

public interface SyncState extends Closeable {

    boolean hasCompletedSync();

    void setCompletedSync(boolean done);

    long filesCount();

    Set<String> allFilePaths();

    void add(FileState fs);

    void remove(String path);

    FileState byPath(String path);

    List<FileState> byHash(RootHash b3);

    void addDir(String path);

    void removeDir(String path);

    boolean hasDir(String path);

    Set<String> getDirs();

    void addLocalDelete(String path);

    void removeLocalDelete(String path);

    boolean hasLocalDelete(String p);

    void addRemoteDelete(String path);

    void removeRemoteDelete(String path);

    boolean hasRemoteDelete(String p);

    void startCopies(List<CopyOp> ops);

    void finishCopies(List<CopyOp> ops);

    List<CopyOp> getInProgressCopies();

    void setSnapshot(String basePath, Snapshot s);

    Snapshot getSnapshot(String basePath);
}
