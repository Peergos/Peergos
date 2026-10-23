package peergos.server.sync;

import peergos.shared.util.Pair;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** A filesystem a copy writes aside, and puts in place once all of it is written, so a file is never seen part written,
 *  and a part written is never moved with its folder, or synced as a file of its own
 */
public abstract class StagedFileSystem implements SyncFilesystem {

    /** Where a copy to p writes until it is complete, starting from p as it is if it isn't already under way */
    protected abstract Path partialCopy(Path p) throws IOException;

    /** Puts a copy to p that is complete, which wrote ranges, and leaves p size long, in place of p */
    protected abstract void completeCopy(Path p, List<Pair<Long, Long>> ranges, long size) throws IOException;

    /** Whether a copy to p stopped while it was being put in place, which only finishing it can make p whole again */
    protected boolean completingCopy(Path p) {
        return false;
    }

    /** Throws away what a copy to p has written so far */
    protected abstract void discardCopy(Path p);

    @Override
    public void discardCopy(CopyOp op) {
        discardCopy(op.target);
    }

    @Override
    public Optional<LocalDateTime> copy(SyncFilesystem srcFs,
                                        List<CopyOp> ops,
                                        boolean resuming,
                                        SyncState synced,
                                        Supplier<Boolean> isCancelled,
                                        Consumer<String> progress) throws IOException {
        CopyOp first = ops.get(0);
        List<Pair<Long, Long>> ranges = ops.stream().map(op -> new Pair<>(op.diffStart, op.diffEnd)).collect(Collectors.toList());
        if (resuming && completingCopy(first.target)) {
            // all of the copy is here, and part of it already in the target, which only finishing makes whole
            completeCopy(first.target, ranges, first.sourceState.size);
            return Optional.empty();
        }
        // a source changed since would be copied with the range and hash of what it was, and a target changed since is
        // someone else's edit, so leave both to the sync
        if (resuming && ! (SyncFilesystem.unchanged(srcFs, first.source, first.sourceState) &&
                SyncFilesystem.asScanned(this, first.target, first.targetState, synced)))
            throw new ConcurrentChangeException(first.sourceState.relPath + " has changed since it was copied in part");
        Optional<LocalDateTime> res = Optional.empty();
        for (CopyOp op : ops) {
            Path partial = partialCopy(op.target);
            Optional<LocalDateTime> mod = SyncFilesystem.copyRange(srcFs, this, partial, op, isCancelled, progress);
            res = res.isEmpty() ? mod : mod.isEmpty() ? res : res.map(t -> mod.get().isAfter(t) ? mod.get() : t);
            long priorSize = op.targetState != null ? op.targetState.size : 0;
            if (priorSize > op.sourceState.size) {
                DirectorySync.log("Sync Truncating file " + op.sourceState.relPath + " from " + priorSize + " to " + op.sourceState.size);
                truncate(partial, op.sourceState.size);
            }
        }
        // putting it in place over an edit made meanwhile would lose the edit, and a source changed meanwhile would leave
        // part of each version under the hash of the old one
        if (! SyncFilesystem.asScanned(this, first.target, first.targetState, synced))
            throw new ConcurrentChangeException(first.target + " changed while it was being synced");
        if (! SyncFilesystem.asScanned(srcFs, first.source, first.sourceState, synced))
            throw new ConcurrentChangeException(first.source + " changed while it was being copied");
        completeCopy(first.target, ranges, first.sourceState.size);
        return res;
    }
}
