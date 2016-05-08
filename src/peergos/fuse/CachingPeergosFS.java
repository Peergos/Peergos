package peergos.fuse;

import jnr.ffi.Pointer;
import jnr.ffi.types.off_t;
import jnr.ffi.types.size_t;
import peergos.user.UserContext;
import peergos.user.fs.Chunk;
import ru.serce.jnrfuse.ErrorCodes;
import ru.serce.jnrfuse.struct.FuseFileInfo;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class CachingPeergosFS extends PeergosFS {

    private static final int DEFAULT_SYNC_SLEEP = 1000*30;
    private static final int DEFAULT_CACHE_SIZE = 1;

    private final Map<String, CacheEntry> entryMap;
    private final int chunkCacheSize, syncSleep;
//    private final Thread syncRunner;

    public CachingPeergosFS(UserContext userContext) {
        this(userContext, DEFAULT_CACHE_SIZE, DEFAULT_SYNC_SLEEP);
    }

    public CachingPeergosFS(UserContext userContext, int chunkCacheSize, int syncSleep) {
        super(userContext);

        boolean accessOrder = true;
        Map<String, CacheEntry> lruCache = new LinkedHashMap<String, CacheEntry>(chunkCacheSize, 0.75f, accessOrder) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> entry) {
                if (size() > chunkCacheSize) {
                    CacheEntry cacheEntry = entry.getValue();
                    cacheEntry.sync();
                    return true;
                }
                return false;
            }
        };

        this.chunkCacheSize = chunkCacheSize;
        this.syncSleep = syncSleep;
        this.entryMap = Collections.synchronizedMap(lruCache);
//        this.syncRunner = new Thread(new Syncher());
//        this.syncRunner.start();
    }

    @Override
    public int read(String s, Pointer pointer, @size_t long size, @off_t long offset, FuseFileInfo fuseFileInfo) {
        if (!containedInOneChunk(offset, offset + size))
            throw new IllegalStateException("write op. straddles boundary : offset " + offset + " with size " + size);

        CacheEntry cacheEntry = entryMap.get(s);
        long startPos = alignToChunkSize(offset);
        int chunkOffset  = intraChunkOffset(offset);

        if (cacheEntry != null) {
            boolean isSameChunk = cacheEntry.offset == startPos;
            if (isSameChunk)
                return cacheEntry.read(pointer, chunkOffset, (int) size);
            else
                return super.read(s, pointer, size, offset, fuseFileInfo);
        }
        else
            return super.read(s, pointer, size, offset, fuseFileInfo);
    }

    @Override
    public int write(String s, Pointer pointer, @size_t long size, @off_t long offset, FuseFileInfo fuseFileInfo) {
        if  (! containedInOneChunk(offset, offset+size))
            throw new  IllegalStateException("write op. straddles boundary : offset "+ offset  +" with size "+ size);

        long startPos  = alignToChunkSize(offset);
        int  chunkOffset  = intraChunkOffset(offset);
        int iSize = (int) size;

        CacheEntry cacheEntry = entryMap.get(s);

        if (cacheEntry != null) {
            boolean sameChunk  = startPos == cacheEntry.offset;
            if (sameChunk)
                return cacheEntry.write(pointer, chunkOffset, iSize);
            else
                cacheEntry.sync();
        }

        cacheEntry = new CacheEntry(s, startPos);
        entryMap.put(s, cacheEntry);
        return cacheEntry.write(pointer, chunkOffset, iSize);
    }

    @Override
    public int flush(String s, FuseFileInfo fuseFileInfo) {
        CacheEntry cacheEntry = entryMap.get(s);
        if  (cacheEntry != null) {
            cacheEntry.sync();
        }
        return super.flush(s, fuseFileInfo);
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

    private class CacheEntry {
        private final String path;
        private final byte[] data;
        private final long offset;
        private int maxPos;


        public CacheEntry(String path, long offset) {
            this.path = path;
            this.offset = offset;
            this.data = new byte[Chunk.MAX_SIZE];
            //read current data into data view
            PeergosStat stat = getByPath(path).orElseThrow(() -> new IllegalStateException("missing" + path));
            byte[] readData = CachingPeergosFS.this.read(stat, data.length, offset).orElseThrow(() -> new IllegalStateException("missing" + path));
            this.maxPos = readData.length;
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
            maxPos = Math.max(maxPos, offset+length);
            return length;
        }

        public synchronized void sync() {
            Path p = Paths.get(path);

            String parentPath = p.getParent().toString();
            String name = p.getFileName().toString();

            applyIfPresent(parentPath, (parent) -> CachingPeergosFS.this.write(parent, name, data, maxPos, offset), -ErrorCodes.ENOENT());
        }

    }

    private class Syncher implements Runnable {
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
            for (String previousEntryKey : previousEntryKeys) {
                CacheEntry cacheEntry = entryMap.get(previousEntryKey);
                if  (cacheEntry != null) {
                    cacheEntry.sync();
                    entryMap.remove(previousEntryKey);
                }
            }
        }
    }

    @Override
    public void close() throws Exception {

        super.close();
    }
}