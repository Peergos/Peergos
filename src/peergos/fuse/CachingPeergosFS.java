package peergos.fuse;

import jnr.ffi.Pointer;
import jnr.ffi.types.off_t;
import jnr.ffi.types.size_t;
import peergos.user.UserContext;
import peergos.user.fs.Chunk;
import peergos.user.fs.FileProperties;
import ru.serce.jnrfuse.ErrorCodes;
import ru.serce.jnrfuse.struct.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class CachingPeergosFS extends PeergosFS {

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
        //TODO add GC thread to optimistically reduce size
//        this.syncRunner = new Thread(new Syncher());
//        this.syncRunner.start();
    }

    @Override
    public int read(String s, Pointer pointer, @size_t long size, @off_t long offset, FuseFileInfo fuseFileInfo) {
        if (DEBUG)
            System.out.printf("read(%s, offset=%d, size=%d)\n", s, offset, size);
        if (!containedInOneChunk(offset, offset + size))
            throw new IllegalStateException("write op. straddles boundary : offset " + offset + " with size " + size);

        long startPos = alignToChunkSize(offset);
        int chunkOffset  = intraChunkOffset(offset);
        int iSize = (int) size;

        CacheEntryHolder cacheEntryHolder = entryMap.computeIfAbsent(s, path -> new CacheEntryHolder(new CacheEntry(path, startPos)));
        return cacheEntryHolder.apply(c -> c != null && c.offset == startPos, () -> new CacheEntry(s, startPos), ce -> ce.read(pointer, chunkOffset, iSize));
    }

    @Override
    public int write(String s, Pointer pointer, @size_t long size, @off_t long offset, FuseFileInfo fuseFileInfo) {
        if (DEBUG)
            System.out.printf("write(%s, offset=%d, size=%d)\n", s, offset, size);
        if  (! containedInOneChunk(offset, offset+size))
            throw new  IllegalStateException("write op. straddles boundary : offset "+ offset  +" with size "+ size);

        long startPos  = alignToChunkSize(offset);
        int  chunkOffset  = intraChunkOffset(offset);
        int iSize = (int) size;

        CacheEntryHolder cacheEntry = entryMap.computeIfAbsent(s, path -> new CacheEntryHolder(new CacheEntry(path, startPos)));
        return cacheEntry.apply(c -> c != null && c.offset == startPos, () -> new CacheEntry(s, startPos), ce -> ce.write(pointer, chunkOffset, iSize));
    }

    @Override
    public int lock(String s, FuseFileInfo fuseFileInfo, int i, Flock flock) {
        if (DEBUG)
            System.out.printf("lock(%s)\n", s);
        CacheEntryHolder cacheEntryHolder = entryMap.get(s);
        if (cacheEntryHolder != null)
            cacheEntryHolder.syncAndClear();
        return 0;
    }

    @Override
    public int flush(String s, FuseFileInfo fuseFileInfo) {
        if (DEBUG)
            System.out.printf("flush(%s)\n", s);
        CacheEntryHolder cacheEntry = entryMap.get(s);
        if  (cacheEntry != null) {
            cacheEntry.syncAndClear();
        }
        return super.flush(s, fuseFileInfo);
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
                syncAndClear();
                setEntry(supplier.get());
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
            byte[] readData = CachingPeergosFS.this.read(stat, data.length, offset).orElseThrow(() -> new IllegalStateException("missing" + path));
            this.maxDirtyPos = 0;
            System.arraycopy(readData, 0, data, 0, readData.length);

        }

        private void ensureInBounds(int offset, int length) {
            if (offset + length > data.length)
                throw new  IllegalStateException("cannot op with offset "+ offset +" and length "+ length +" with length "+ data.length);
        }

        public synchronized int read(Pointer pointer,  int offset, int length) {
            ensureInBounds(offset, length);
            pointer.put(0, data, offset, length);
            return length;
        }
        public synchronized int write(Pointer pointer, int offset, int length) {
            ensureInBounds(offset, length);
            pointer.get(0, data, offset, length);
            maxDirtyPos = Math.max(maxDirtyPos, offset+length);
            return length;
        }

        public synchronized void sync() {
            Path p = Paths.get(path);

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

    private class GarbageCollector implements Runnable {

        private final Set<String> previousEntryKeys = new HashSet<>();

        @Override
        public void run() {
            while (! isClosed) {
                try {
                    Thread.sleep(syncSleep);
                } catch (InterruptedException ie){}
            }

        }

        private void sync()  {
            for (String previousEntryKey : previousEntryKeys) {}
        }
    }

    @Override
    public void close() throws Exception {

        super.close();
    }
}