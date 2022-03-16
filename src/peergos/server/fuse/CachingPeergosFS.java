package peergos.server.fuse;
import java.util.logging.*;

import peergos.server.util.Logging;

import jnr.ffi.Pointer;
import jnr.ffi.types.off_t;
import jnr.ffi.types.size_t;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.Chunk;
import peergos.shared.user.fs.FileProperties;
import peergos.shared.util.*;
import ru.serce.jnrfuse.ErrorCodes;
import ru.serce.jnrfuse.struct.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class CachingPeergosFS extends PeergosFS {
	private static final Logger LOG = Logging.LOG();

    private static final int DEFAULT_SYNC_SLEEP = 1000*30;
    private static final int DEFAULT_CACHE_SIZE = 1;
    private static final boolean DEBUG = true;

    private final ConcurrentMap<String, CacheEntryHolder> entryMap;
    private final int chunkCacheSize, syncSleep;
//    private final Thread syncRunner;

    public CachingPeergosFS(UserContext userContext) {
        this(userContext, DEFAULT_CACHE_SIZE, DEFAULT_SYNC_SLEEP);
    }

    public CachingPeergosFS(UserContext userContext, int chunkCacheSize, int syncSleep) {
        super(userContext);

        this.chunkCacheSize = chunkCacheSize;
        this.syncSleep = syncSleep;
        this.entryMap = new ConcurrentHashMap<>();
    }

    @Override
    public int read(String s, Pointer pointer, @size_t long size, @off_t long offset, FuseFileInfo fuseFileInfo) {
        try {
            return read(s, pointer, 0, size, offset, fuseFileInfo);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, t.getMessage(), t);
            throw t;
        }
    }

    public int read(String s, Pointer pointer, int pointerOffset, @size_t long size, @off_t long offset, FuseFileInfo fuseFileInfo) {
        if (DEBUG)
            System.out.printf("read(%s, offset=%d, size=%d)\n", s, offset, size);
        if (!containedInOneChunk(offset, offset + size)) {
            long boundary = alignToChunkSize(offset + Chunk.MAX_SIZE);
            int r1 = read(s, pointer, 0, boundary - offset, offset, fuseFileInfo);
            if (r1 <= 0)
                return r1;
            int r2 = read(s, pointer, (int)(boundary - offset), size + offset - boundary, boundary, fuseFileInfo);
            if (r2 <= 0)
                return r2;
            return r1 + r2;
        }

        long startPos = alignToChunkSize(offset);
        int chunkOffset  = intraChunkOffset(offset);
        int iSize = (int) size;

        CacheEntryHolder cacheEntryHolder = entryMap.computeIfAbsent(s, path -> new CacheEntryHolder(new CacheEntry(path, startPos)));
        return cacheEntryHolder.apply(c -> c != null && c.offset == startPos, () -> new CacheEntry(s, startPos),
                ce -> ce.read(pointer, pointerOffset, chunkOffset, iSize));
    }

    @Override
    public int write(String s, Pointer pointer, @size_t long size, @off_t long offset, FuseFileInfo fuseFileInfo) {
        try {
            return write(s, pointer, 0, size, offset, fuseFileInfo);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, t.getMessage(), t);
            throw t;
        }
    }

    public int write(String s, Pointer pointer, int pointerOffset, @size_t long size, @off_t long offset, FuseFileInfo fuseFileInfo) {
        if (DEBUG)
            System.out.printf("write(%s, offset=%d, size=%d)\n", s, offset, size);
        if  (! containedInOneChunk(offset, offset+size)) {
            long boundary = alignToChunkSize(offset + Chunk.MAX_SIZE);
            int w1 = write(s, pointer, 0, boundary - offset, offset, fuseFileInfo);
            if (w1 <= 0)
                return w1;
            int w2 = write(s, pointer, (int)(boundary - offset), size + offset - boundary, boundary, fuseFileInfo);
            if (w2 <= 0)
                return w2;
            return w1 + w2;
        }

        long startPos  = alignToChunkSize(offset);
        int  chunkOffset  = intraChunkOffset(offset);
        int iSize = (int) size;

        CacheEntryHolder cacheEntry = entryMap.computeIfAbsent(s, path -> new CacheEntryHolder(new CacheEntry(path, startPos)));
        return cacheEntry.apply(c -> c != null && c.offset == startPos, () -> new CacheEntry(s, startPos),
                ce -> ce.write(pointer, pointerOffset, chunkOffset, iSize));
    }

    @Override
    public int lock(String s, FuseFileInfo fuseFileInfo, int i, Flock flock) {
        try {
            if (DEBUG)
                System.out.printf("lock(%s)\n", s);
            CacheEntryHolder cacheEntryHolder = entryMap.get(s);
            if (cacheEntryHolder != null)
                cacheEntryHolder.syncAndClear();
            return 0;
        } catch (Throwable t) {
            LOG.log(Level.WARNING, t.getMessage(), t);
            throw t;
        }
    }

    @Override
    public int flush(String s, FuseFileInfo fuseFileInfo) {
        try {
            if (DEBUG)
                System.out.printf("flush(%s)\n", s);
            CacheEntryHolder cacheEntry = entryMap.get(s);
            if (cacheEntry != null) {
                cacheEntry.sync();
            }
            return super.flush(s, fuseFileInfo);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, t.getMessage(), t);
            throw t;
        }
    }

    @Override
    protected int annotateAttributes(String fullPath, PeergosStat peergosStat, FileStat fileStat) {
        if (DEBUG)
            System.out.printf("annotate(%s)\n", fullPath);
        CacheEntryHolder cacheEntry = entryMap.get(fullPath);
        Optional<PeergosStat> updatedStat = Optional.empty();
        if (cacheEntry != null) {
            updatedStat = cacheEntry.applyIfPresent(ce -> {
                if (ce == null)
                    return peergosStat;
                long maxSize = ce.offset + ce.maxDirtyPos;
                if (peergosStat.properties.size < maxSize) {
                    FileProperties updated = peergosStat.properties.withSize(maxSize);
                    return new PeergosStat(peergosStat.treeNode, updated);
                }
                return peergosStat;
            });
        }
        return super.annotateAttributes(fullPath, updatedStat.orElse(peergosStat), fileStat);
    }

    private boolean containedInOneChunk(long start, long end) {
        return alignToChunkSize(start) == alignToChunkSize(end-1);
    }

    private long alignToChunkSize(long pos) {
        return Math.max(0, pos / Chunk.MAX_SIZE) * Chunk.MAX_SIZE;
    }
    private int intraChunkOffset(long  pos) {
        return (int) pos % Chunk.MAX_SIZE;
    }

    private class CacheEntryHolder {
        private CacheEntry entry;

        public CacheEntryHolder(CacheEntry entry) {
            this.entry = entry;
        }

        public synchronized <A> A apply(Predicate<CacheEntry> correctChunk, Supplier<CacheEntry> supplier, Function<CacheEntry, A> func) {
            if (!correctChunk.test(entry)) {
                long oldOffset = entry != null ? entry.offset : -1;
                syncAndClear();
                setEntry(supplier.get());
                LOG.info("Ejecting chunk from " + entry.path + " " + oldOffset + " -> "+ entry.offset);

            }
            return func.apply(entry);
        }

        public synchronized <A> Optional<A> applyIfPresent(Function<CacheEntry, A> func) {
            if (entry != null) {
                return Optional.of(func.apply(entry));
            }
            return Optional.empty();
        }

        public synchronized void setEntry(CacheEntry entry) {
            this.entry = entry;
        }

        public synchronized void sync() {
            if (entry == null)
                return;
            if (DEBUG)
                System.out.printf("sync(%s)\n", entry.path);
            entry.sync();
        }

        public synchronized void syncAndClear() {
            if (entry == null)
                return;
            if (DEBUG)
                System.out.printf("fsync(%s)\n", entry.path);
            entry.sync();
            entry = null;
        }
    }

    private class CacheEntry {
        private final String path;
        private final byte[] data;
        private final long offset;
        private int maxDirtyPos;

        public CacheEntry(String path, long offset) {
            this.path = path;
            this.offset = offset;
            this.data = new byte[Chunk.MAX_SIZE];
            //read current data into data view
            PeergosStat stat = getByPath(path).orElseThrow(() -> new IllegalStateException("missing" + path));
            byte[] readData = CachingPeergosFS.this.read(stat, data.length, offset)
                    .orElseThrow(() -> new IllegalStateException("missing: " + path));
            this.maxDirtyPos = 0;
            System.arraycopy(readData, 0, data, 0, readData.length);

        }

        private void ensureInBounds(int offset, int length) {
            if (offset + length > data.length)
                throw new  IllegalStateException("cannot op with offset "+ offset +" and length "+ length +" with length "+ data.length);
        }

        public int read(Pointer pointer, int pointerOffset, int chunkOffset, int length) {
            ensureInBounds(chunkOffset, length);
            pointer.put(pointerOffset, data, chunkOffset, length);
            return length;
        }

        public int write(Pointer pointer, int pointerOffset, int chunkOffset, int length) {
            ensureInBounds(chunkOffset, length);
            pointer.get(pointerOffset, data, chunkOffset, length);
            maxDirtyPos = Math.max(maxDirtyPos, chunkOffset+length);
            return length;
        }

        public void sync() {
            Path p = PathUtil.get(path);

            String parentPath = p.getParent().toString();
            String name = p.getFileName().toString();

            if (maxDirtyPos ==0)
                return;
            applyIfPresent(parentPath, (parent) -> CachingPeergosFS.this.write(parent, name, data, maxDirtyPos, offset), -ErrorCodes.ENOENT());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            CacheEntry that = (CacheEntry) o;

            return Objects.equals(path, that.path);

        }

        @Override
        public int hashCode() {
            return path != null ? path.hashCode() : 0;
        }
    }

    @Override
    public void close() throws Exception {

        super.close();
    }
}