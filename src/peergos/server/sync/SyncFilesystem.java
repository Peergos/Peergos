package peergos.server.sync;

import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.user.fs.*;
import peergos.shared.util.ProgressConsumer;
import peergos.shared.util.Triple;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

public interface SyncFilesystem {

    /** Progress for a transfer, reported once per MiB. Callers are called per chunk or per
     *  read, and a repeated figure costs a log line and a status file write for nothing. */
    static ProgressConsumer<Long> perMiB(String verb, long size, String path, Consumer<String> progress) {
        AtomicLong done = new AtomicLong(0);
        AtomicLong reported = new AtomicLong(0);
        long total = size / 1024 / 1024;
        return delta -> {
            long mib = done.addAndGet(delta) / 1024 / 1024;
            if (mib > 0 && reported.getAndSet(mib) != mib)
                progress.accept(verb + " " + mib + " / " + total + " MiB of " + path);
        };
    }

    long totalSpace() throws IOException;

    long freeSpace() throws IOException;

    String getRoot();

    Path resolve(String p);

    boolean exists(Path p);

    void mkdirs(Path p);

    /** Whether a file can be put at p, which a file at any of its ancestors prevents */
    default boolean canCreate(Path p) {
        return true;
    }

    void delete(Path p);

    /** Deletes the files in dir that are still as scanned, and returns the names of those that are now gone. Anything
     *  else there has been changed since, or replaced by a folder.
     */
    default Set<String> bulkDeleteFiles(Path dir, java.util.Map<String, FileState> scanned) {
        Set<String> gone = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, FileState> e : scanned.entrySet()) {
            Path p = dir.resolve(e.getKey());
            if (! exists(p))
                gone.add(e.getKey());
            // only a folder can have something put in it
            else if (! canCreate(p.resolve("f")) && asScanned(this, p, e.getValue(), null)) {
                delete(p);
                gone.add(e.getKey());
            }
        }
        return gone;
    }

    void moveTo(Path src, Path target);

    long getLastModified(Path p);

    void setModificationTime(Path p, long t);

    void setHash(Path p, HashTree hashTree, long fileSize);

    void setHashes(List<Triple<String, FileWrapper, HashTree>> toUpdate);

    long size(Path p);

    void truncate(Path p, long size) throws IOException;

    /**
     *
     * @param p
     * @param fileOffset
     * @param data
     * @param size
     * @param hash
     * @param modificationTime
     * @param thumbnail
     * @param props
     * @param isCancelled
     * @param progress
     * @return The actual modification time the filesystem returns after write
     * @throws IOException
     */
    Optional<LocalDateTime> setBytes(Path p,
                                     long fileOffset,
                                     AsyncReader data,
                                     long size,
                                     Optional<HashTree> hash,
                                     Optional<LocalDateTime> modificationTime,
                                     Optional<Thumbnail> thumbnail,
                                     ResumeUploadProps props,
                                     Supplier<Boolean> isCancelled,
                                     Consumer<String> progress) throws IOException;

    AsyncReader getBytes(Path p, long fileOffset) throws IOException;

    /** Copies ops, which all write to the one file at their target, from srcFs, carrying on from what a copy of the same
     *  ops that stopped wrote, if resuming. The file is never seen part written, unless it is written in place, as one
     *  in Peergos is, in one change. Fails with a ConcurrentChangeException, having written nothing more, once the
     *  source or the target is no longer as the ops expect, after which discardCopy throws away what it wrote.
     *
     * @return The actual modification time the filesystem returns after write
     */
    Optional<LocalDateTime> copy(SyncFilesystem srcFs,
                                 List<CopyOp> ops,
                                 boolean resuming,
                                 SyncState synced,
                                 Supplier<Boolean> isCancelled,
                                 Consumer<String> progress) throws IOException;

    /** Throws away what op has written so far, wherever it wrote it */
    void discardCopy(CopyOp op);

    /** Throws away what any copy has written so far */
    void discardCopies();

    /** Writes the range of op's source into to, which is where the copy writes, which may not be its target */
    static Optional<LocalDateTime> copyRange(SyncFilesystem srcFs,
                                             SyncFilesystem targetFs,
                                             Path to,
                                             CopyOp op,
                                             Supplier<Boolean> isCancelled,
                                             Consumer<String> progress) throws IOException {
        if (isCancelled.get())
            throw new RuntimeException("Sync cancelled before copying " + op.target);
        DirectorySync.log("COPY from " + op.source + " to " + op.target + " range=[" + op.diffStart +", " + op.diffEnd+"]");
        Optional<LocalDateTime> res;
        try (AsyncReader fin = op.diffStart == op.diffEnd ? AsyncReader.build(new byte[0]) : srcFs.getBytes(op.source, op.diffStart)) {
            Optional<Thumbnail> thumbnail = srcFs.getThumbnail(op.source);
            LocalDateTime modified = LocalDateTime.ofInstant(Instant.ofEpochSecond(op.sourceState.modificationTime / 1000, 0), ZoneOffset.UTC);
            res = targetFs.setBytes(to, op.diffStart, fin, op.diffEnd - op.diffStart, Optional.of(op.sourceState.hashTree),
                    Optional.of(modified), thumbnail, op.props, isCancelled, progress);
        }
        if (isCancelled.get())
            throw new RuntimeException("Sync cancelled before copying " + op.target);
        return res;
    }

    /** Whether a file has the size and modification time it had */
    static boolean unchanged(SyncFilesystem fs, Path p, FileState was) {
        return fs.exists(p) && fs.size(p) == was.size && fs.getLastModified(p) == was.modificationTime;
    }

    /** Whether a file is still as the scan found it, or still absent, and could be put there, if scanned is null */
    static boolean asScanned(SyncFilesystem fs, Path p, FileState scanned, SyncState syncDb) {
        if (! fs.exists(p))
            return scanned == null && fs.canCreate(p);
        if (scanned == null || fs.size(p) != scanned.size)
            return false;
        if (fs.getLastModified(p) == scanned.modificationTime)
            return true;
        // a scan keeps the synced version of a file whose contents haven't changed, whatever its modification time
        return fs.hashFile(p, Optional.empty(), scanned.relPath, syncDb, scanned.size, scanned.chunkSize)
                .rootHash.equals(scanned.hashTree.rootHash);
    }

    void uploadSubtree(Stream<FileWrapper.FolderUploadProperties> directories);

    Optional<Thumbnail> getThumbnail(Path p);

    /**
     * @param chunkSize the chunk size, and so the scheme, of the file this one is compared
     *                  against. A local file has no properties to read it from, and the two
     *                  schemes give different roots for the same bytes, so a wrong value here
     *                  makes an unchanged file look changed.
     */
    HashTree hashFile(Path p, Optional<FileWrapper> meta, String relativePath, SyncState syncedState, long size, int chunkSize);

    /**
     *
     * @param file
     * @param dir
     * @return the writer to ignore from snapshots (we only have read access to it as the entry point)
     * @throws IOException
     */
    /**
     * @param parallel if true, walk children concurrently (safe once an initial sync
     *                 has completed for this pair).
     */
    Optional<PublicKeyHash> applyToSubtree(Consumer<FileProps> file, Consumer<FileProps> dir, boolean parallel) throws IOException;

    long filesCount() throws IOException;

    class FileProps {
        public final String relPath;
        public final long modifiedTime;
        public final long size;
        public final Optional<FileWrapper> meta;

        public FileProps(String relPath, long modifiedTime, long size, Optional<FileWrapper> meta) {
            this.relPath = relPath;
            this.modifiedTime = modifiedTime;
            this.size = size;
            this.meta = meta;
        }
    }
}
