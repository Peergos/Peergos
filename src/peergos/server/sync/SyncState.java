package peergos.server.sync;

import peergos.shared.user.fs.Blake3state;

import java.util.List;

public interface SyncState {

    void add(FileState fs);

    FileState byPath(String path);

    List<FileState> byHash(Blake3state b3);
}
