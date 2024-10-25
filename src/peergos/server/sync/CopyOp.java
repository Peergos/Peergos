package peergos.server.sync;

import java.nio.file.Path;
import java.util.Objects;

class CopyOp {
    public final boolean isLocalTarget;
    public final Path source, target;
    public final FileState sourceState, targetState;
    public final long diffStart, diffEnd;

    public CopyOp(boolean isLocalTarget, Path source, Path target, FileState sourceState, FileState targetState, long diffStart, long diffEnd) {
        this.isLocalTarget = isLocalTarget;
        this.source = source;
        this.target = target;
        this.sourceState = sourceState;
        this.targetState = targetState;
        this.diffStart = diffStart;
        this.diffEnd = diffEnd;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CopyOp copyOp = (CopyOp) o;
        return isLocalTarget == copyOp.isLocalTarget && diffStart == copyOp.diffStart && diffEnd == copyOp.diffEnd &&
                Objects.equals(source, copyOp.source) && Objects.equals(target, copyOp.target) && Objects.equals(sourceState, copyOp.sourceState) && Objects.equals(targetState, copyOp.targetState);
    }

    @Override
    public int hashCode() {
        return Objects.hash(isLocalTarget, source, target, sourceState, targetState, diffStart, diffEnd);
    }
}
