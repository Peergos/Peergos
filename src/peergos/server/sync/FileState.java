package peergos.server.sync;

import peergos.shared.user.fs.Blake3state;
import peergos.shared.util.Pair;

import java.util.List;
import java.util.Objects;

class FileState {
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

    @Override
    public String toString() {
        return relPath;
    }
}
