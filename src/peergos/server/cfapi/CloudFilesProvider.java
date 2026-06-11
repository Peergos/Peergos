package peergos.server.cfapi;

import peergos.server.sync.CopyOp;
import peergos.server.sync.SyncState;
import peergos.server.util.Logging;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileProperties;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.user.fs.HashBranch;
import peergos.shared.user.fs.HashTree;
import peergos.shared.user.fs.ResumeUploadProps;
import peergos.shared.util.PathUtil;
import peergos.server.sync.FileState;

import java.io.IOException;
import java.lang.foreign.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.*;

/**
 * Handles CF API callbacks by translating them into Peergos UserContext calls.
 * Equivalent role to PeergosFS for FUSE.
 */
public class CloudFilesProvider {
    private static final Logger LOG = Logging.LOG();

    private static final int CHUNK_SIZE   = 4 * 1024 * 1024; // 4 MB transfer chunks
    private static final int CLUSTER_SIZE = 4096;            // CF API buffers must be padded to cluster boundary

    private final UserContext context;
    private final String syncRootPath;
    /** Persisted "last synced version" per path + last-seen Snapshot — feeds the
     *  conflict detector. Reuses peergos.server.sync.SyncState + FileState directly.
     *  Lifecycle owned by CloudFilesMount (opened in mount(), closed in close()). */
    private final peergos.server.sync.SyncState syncState;

    /**
     * Maps a file's identity bytes (stored as a long hash) back to its full Peergos path.
     * Windows echoes the identity blob back to us in every FETCH_DATA callback.
     */
    private final ConcurrentHashMap<Long, String> identityToPath = new ConcurrentHashMap<>();

    /**
     * VirtualAlloc'd transfer buffers awaiting deferred free. CF may still be reading
     * from them after CfExecute(TRANSFER_DATA) returns. After BUFFER_RETAIN_MS has
     * elapsed for an entry, it's safe to VirtualFree. Swept lazily on each enqueue —
     * no background thread.
     * Each entry is {segment, freeAfterMillis}.
     */
    private static final long BUFFER_RETAIN_MS = 30_000;
    private final java.util.Deque<Object[]> pendingBuffers =
            new java.util.concurrent.ConcurrentLinkedDeque<>();

    /**
     * Paths the local-file watcher just uploaded and converted to placeholders. The
     * conversion fires FILE_CLOSE_COMPLETION (CF treats the convert as a placeholder
     * lifecycle event), and any subsequent edit would also fire the close callback —
     * so we de-dup against this map to avoid uploading the same bytes twice.
     */
    private final java.util.Map<String, Boolean> recentlyUploaded =
            java.util.Collections.synchronizedMap(new peergos.shared.util.LRUCache<>(1024));

    /**
     * Acked NOTIFY_DELETE peergos paths waiting for the next drain. We accumulate
     * across a short window so a "delete 100 files" burst from Explorer collapses
     * into one {@link FileWrapper#deleteChildren} call per parent, instead of N
     * serial {@code applyComplexUpdate} cycles on the same writer. Keyed by
     * peergos parent path, values are filenames in that parent.
     *
     * <p>Guarded by its own monitor — both {@link #enqueueDelete} (CF thread) and
     * {@link #drainDeletes} (delete-scheduler thread) write to it.
     */
    private final java.util.Map<String, java.util.Set<String>> pendingDeletes =
            new java.util.HashMap<>();

    /**
     * Tracks local directory renames so the watcher can resolve
     * {@code WatchKey.watchable()} (which is frozen at registration time) to the dir's
     * current on-disk path. Without this, when the user renames a dir we registered, the
     * WatchService keeps firing events on the same kernel handle but with the dir's old
     * path — dir.resolve(ctx) then produces a non-existent path and the event is dropped.
     * {@link #recordDirRename} is called from onRenameCompletionPlaceholder; the watcher
     * loop consults {@link #currentDirPath} on every event.
     */
    private final java.util.Map<Path, Path> originalToCurrentDir =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Path, Path> currentToOriginalDir =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Paths whose {@link #uploadLocalFile} is currently running. Prevents concurrent
     * uploads of the same file when multiple callers (the watcher's uploadExec, the
     * pull tick's discoverMissedLocalUploads, etc.) racingly initiate one each — a
     * large upload can take minutes, so a 30-second pull tick will fire several times
     * before {@code recordSyncedVersion} marks the file as synced. Without this guard,
     * a 600 MB file gets uploaded N times in parallel, multiplying bandwidth.
     */
    private final java.util.Set<Path> uploadsInFlight =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Paths whose {@link #downloadRemoteToLocal} is currently writing bytes. A partial
     * write — interrupted by a mount close, network drop, or anything else — leaves
     * the local file with {@code Files.size < remote.size}. Without this guard, the
     * FILE_CLOSE_COMPLETION fired by the partial write's OutputStream close would see
     * the size mismatch and route into {@code overwriteChangedChunks}, which would
     * TRUNCATE the remote to the partial local size. {@link #onFileCloseCompletion}
     * consults this set before deciding to write back.
     */
    private final java.util.Set<Path> downloadsInFlight =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Returns the dir's current local path. If never renamed, returns the input. */
    public Path currentDirPath(Path original) {
        return originalToCurrentDir.getOrDefault(original, original);
    }

    /** Record that the dir whose current local path was {@code src} is now at {@code tgt}.
     *  Handles chained renames (A→B→C) by tracking back to the original registration. */
    private void recordDirRename(Path src, Path tgt) {
        Path original = currentToOriginalDir.remove(src);
        if (original == null) original = src;   // first rename: registration path was src
        currentToOriginalDir.put(tgt, original);
        originalToCurrentDir.put(original, tgt);
    }

    /**
     * Single-thread executor that owns every write to the {@link SyncState} SQLite DB.
     * SQLite serialises writers anyway (SQLITE_BUSY otherwise), and a CF callback thread
     * blowing up inside JNI exception construction while unwinding through an FFM upcall
     * stub crashed the JVM in testing — that path went through redefined-by-JVMTI
     * Throwable.fillInStackTrace and SIGSEGV'd in jvm.dll. Routing every write through
     * one thread sidesteps both the SQLite contention and the FFM-upcall-on-exception
     * fragility. Daemon thread so it doesn't keep the JVM alive on shutdown.
     */
    private final java.util.concurrent.ExecutorService syncStateExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "CF syncState");
                t.setDaemon(true);
                return t;
            });

    /** Submit a SyncState write off the calling thread. Any exception inside the task is
     *  logged on the executor thread instead of unwinding back through the caller. Use
     *  this from CF callback threads — direct calls from the upload executor and pull
     *  scheduler are also routed here so that all writers serialise on one thread. */
    private void asyncSyncState(Runnable r) {
        if (syncState == null) return;
        try {
            syncStateExec.submit(() -> {
                try { r.run(); }
                catch (Throwable t) {
                    LOG.log(Level.WARNING, "syncState write failed", t);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Executor shut down (mount closing) — drop the write; the next mount will
            // recover via its discoverMissedLocalUploads / discoverNewRemoteChildren walks.
        }
    }

    public CloudFilesProvider(UserContext context,
                              String syncRootPath,
                              SyncState syncState) {
        this.context = context;
        this.syncRootPath = syncRootPath;
        this.syncState = syncState;
    }

    // -----------------------------------------------------------------------
    // FETCH_DATA callback
    // -----------------------------------------------------------------------

    public void onFetchData(MemorySegment info, MemorySegment params) {
        info   = info.reinterpret(256);
        params = params.reinterpret(256);
        long connectionKey  = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_CONNECTION_KEY_OFF);
        long transferKey    = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_TRANSFER_KEY_OFF);
        long requestKey     = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_REQUEST_KEY_OFF);
        long identityAddr   = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_FILE_IDENTITY_OFF);
        int  identityLen    = info.get(ValueLayout.JAVA_INT,  CfApi.CBI_FILE_IDENTITY_LEN_OFF);
        long requiredOffset = params.get(ValueLayout.JAVA_LONG, CfApi.CBP_FETCH_DATA_REQUIRED_OFFSET_OFF);
        long requiredLength = params.get(ValueLayout.JAVA_LONG, CfApi.CBP_FETCH_DATA_REQUIRED_LENGTH_OFF);
        // Same-process guard: if the JVM itself reads a placeholder, CF dispatches FETCH_DATA
        // synchronously on a CF thread. Our handler then calls context.getByPath().join(),
        // which can deadlock if anything in-JVM is holding the Peergos synchronizer. Fail
        // the transfer so the caller gets an error instead of hanging. Mirrors the guard in
        // onFetchPlaceholders.
        if (isSameProcessCaller(info)) {
            LOG.info("[CF] FETCH_DATA: same-process caller → fail to avoid deadlock");
            failTransfer(connectionKey, transferKey, requestKey, -1);
            return;
        }
        // Path fallback: identityToPath is in-memory only and lost across JVM restarts.
        // If the identity lookup misses, derive the peergos path from the normalized path
        // CF gives us (CBI_NORMALIZED_PATH_OFF) — same conversion the other callbacks use.
        String normalizedPath = CfApi.readWideString(info, CfApi.CBI_NORMALIZED_PATH_OFF);
        LOG.info("[CF] FETCH_DATA: connKey=" + connectionKey + " xferKey=" + transferKey
                + " reqOff=" + requiredOffset + " reqLen=" + requiredLength);
        // Call synchronously on the CF callback thread — CF may correlate the CfExecute
        // back to the originating thread, so calling from a worker thread loses context.
        doFetchData(connectionKey, transferKey, requestKey, identityAddr, identityLen,
                normalizedPath, requiredOffset, requiredLength);
    }

    /**
     * Returns true if the caller PID in {@code info}'s CBI_PROCESS_INFO field matches the
     * current JVM's PID. Used to short-circuit CF callbacks that would re-enter Peergos
     * code paths on the same thread and risk deadlock against held synchronizer state.
     */
    private static boolean isSameProcessCaller(MemorySegment info) {
        long processInfoAddr = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_PROCESS_INFO_OFF);
        if (processInfoAddr == 0) return false;
        int callerPid = MemorySegment.ofAddress(processInfoAddr).reinterpret(16)
                .get(ValueLayout.JAVA_INT, 4);
        int ourPid = (int) ProcessHandle.current().pid();
        return callerPid == ourPid;
    }

    private void doFetchData(long connectionKey, long transferKey, long requestKey,
                             long identityAddr, int identityLen,
                             String normalizedPath,
                             long requiredOffset, long requiredLength) {
        try {
            String peergosPath = pathFromIdentity(identityAddr, identityLen);
            if (peergosPath == null) {
                // Fallback: identityToPath was lost (e.g., JVM restart after the placeholder
                // was already created on disk). Reconstruct the path from the normalized path
                // CF passed in. Re-register the mapping so subsequent fetches hit the cache.
                peergosPath = normalizedToPeergos(normalizedPath);
                LOG.info("[CF] FETCH_DATA: identity miss, derived peergosPath="
                        + peergosPath + " from normalizedPath=" + normalizedPath);
                if (peergosPath != null && identityLen == 8 && identityAddr != 0) {
                    try {
                        long key = MemorySegment.ofAddress(identityAddr).reinterpret(8)
                                .get(ValueLayout.JAVA_LONG, 0);
                        identityToPath.put(key, peergosPath);
                    } catch (Exception ignored) {}
                }
            }
            LOG.info("[CF] FETCH_DATA: peergosPath=" + peergosPath + " mapSize=" + identityToPath.size());
            if (peergosPath == null) {
                LOG.info("[CF] FETCH_DATA: path lookup FAILED identityAddr=0x" + Long.toHexString(identityAddr));
                failTransfer(connectionKey, transferKey, requestKey, -1);
                return;
            }

            Optional<FileWrapper> fwOpt = context.getByPath(peergosPath).join();
            if (fwOpt.isEmpty() || fwOpt.get().isDirectory()) {
                failTransfer(connectionKey, transferKey, requestKey, -1);
                return;
            }
            FileWrapper fw  = fwOpt.get();
            long fileSize = fw.getSize();
            long end = Math.min(requiredOffset + requiredLength, fileSize);

            // Fine-grained hydration progress: the AsyncReader's monitor fires per
            // IPFS block (~1 MB), so the File Explorer download dialog updates smoothly
            // rather than jumping in CHUNK_SIZE (4 MB) steps. Throttle to ~0.5% of the
            // file so we don't hammer CfReportProviderProgress for tiny files. Final
            // 100% is sent after the last transferData below.
            final long connKey = connectionKey, transferK = transferKey;
            java.util.concurrent.atomic.AtomicLong downloaded =
                    new java.util.concurrent.atomic.AtomicLong(requiredOffset);
            java.util.concurrent.atomic.AtomicLong lastReported =
                    new java.util.concurrent.atomic.AtomicLong(-1);
            long progressGrain = Math.max(CLUSTER_SIZE, fileSize / 200);
            peergos.shared.util.ProgressConsumer<Long> dlMonitor = bytes -> {
                if (bytes == null || bytes <= 0) return;
                long cum = Math.min(downloaded.addAndGet(bytes), fileSize);
                long lr = lastReported.get();
                if (cum - lr >= progressGrain && cum < fileSize
                        && lastReported.compareAndSet(lr, cum)) {
                    try { CfApi.cfReportProviderProgress(connKey, transferK, fileSize, cum); }
                    catch (Exception ignored) {}
                }
            };

            try (Arena arena = Arena.ofConfined();
                 AsyncReader reader = fw.getInputStream(context.network, context.crypto,
                         fileSize, dlMonitor).join()) {
                if (requiredOffset > 0)
                    reader.seek(requiredOffset).join();

                long remaining = end - requiredOffset;
                long offset    = requiredOffset;
                byte[] buf = new byte[(int) Math.min(CHUNK_SIZE, remaining)];
                while (remaining > 0) {
                    int toRead = (int) Math.min(buf.length, remaining);
                    int nRead  = reader.readIntoArray(buf, 0, toRead).join();
                    if (nRead <= 0) break;
                    // On Win 11 26100 CF requires page-aligned buffer (HeapAlloc 30 bytes is
                    // not page-aligned and CF rejects with E_INVALIDARG). Also try Length=padded
                    // (cluster-aligned) since Microsoft sends Length=numberOfBytesTransfered
                    // which in their chunked flow is always cluster-aligned except for the very
                    // last chunk.
                    long padded = ((nRead + CLUSTER_SIZE - 1) / CLUSTER_SIZE) * CLUSTER_SIZE;
                    MemorySegment ds = CfApi.virtualAlloc(padded).reinterpret(padded);
                    MemorySegment.copy(buf, 0, ds, ValueLayout.JAVA_BYTE, 0, nRead);
                    // Use padded length so all internal CF state machine boundaries are aligned
                    LOG.info("[CF] FETCH_DATA: nRead=" + nRead + " offset=" + offset);
                    // Length = actual bytes read (Microsoft passes numberOfBytesTransfered,
                    // which for a 30-byte file is 30, NOT the padded buffer size).
                    transferData(connectionKey, transferKey, requestKey, ds, offset, nRead, fileSize);
                    // After-delivery progress: belt-and-braces for the download monitor —
                    // small files / cached chunks may not fire the monitor, so bump the
                    // counter from the delivered offset too. Monotonic via compareAndSet.
                    long deliveredOffset = Math.min(offset + nRead, fileSize);
                    long lrAfter = lastReported.get();
                    if (deliveredOffset > lrAfter && deliveredOffset < fileSize
                            && lastReported.compareAndSet(lrAfter, deliveredOffset)) {
                        try { CfApi.cfReportProviderProgress(connKey, transferK, fileSize, deliveredOffset); }
                        catch (Exception ignored) {}
                    }
                    // Sweep already-expired buffers, then defer this one for BUFFER_RETAIN_MS.
                    sweepExpiredBuffers();
                    pendingBuffers.add(new Object[]{ds, System.currentTimeMillis() + BUFFER_RETAIN_MS});
                    offset    += nRead;
                    remaining -= nRead;
                }
            }
            // Final 100% — closes the File Explorer hydration dialog and unblocks
            // any same-process read waiting on the placeholder.
            if (requiredOffset == 0 && end >= fileSize) {
                try { CfApi.cfReportProviderProgress(connKey, transferK, fileSize, fileSize); }
                catch (Exception ignored) {}
            }
            // After the full file has been hydrated, the on-disk content matches the
            // current Peergos version. Record that so the next FILE_CLOSE_COMPLETION
            // for this path has a baseline for conflict detection.
            if (requiredOffset == 0 && end >= fw.getSize()) {
                String relPath = peergosPathToRelPath(peergosPath);
                if (relPath != null) recordSyncedVersion(relPath, fw);
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "FETCH_DATA callback exception (connKey=" + connectionKey + ")", e);
            failTransfer(connectionKey, transferKey, requestKey, -1);
        }
    }

    private void sweepExpiredBuffers() {
        long now = System.currentTimeMillis();
        // Entries are added in order, so peek at head — if it's not expired, none are.
        while (true) {
            Object[] head = pendingBuffers.peekFirst();
            if (head == null) return;
            long freeAt = (long) head[1];
            if (now < freeAt) return;
            if (!pendingBuffers.remove(head)) return; // raced; bail
            try { CfApi.virtualFree((MemorySegment) head[0]); }
            catch (Exception ignored) {}
        }
    }

    private void transferData(long connectionKey, long transferKey, long requestKey,
                              MemorySegment data, long offset, long length, long fileSize) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment opInfo   = buildOpInfo(arena, connectionKey, transferKey,
                    CfApi.CF_OPERATION_TYPE_TRANSFER_DATA, requestKey);
            MemorySegment opParams = arena.allocate(CfApi.OP_XFER_DATA_SIZE);
            opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_PARAM_SIZE_OFF,       (int) CfApi.OP_XFER_DATA_SIZE);
            opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_XFER_DATA_FLAGS_OFF,  CfApi.CF_OPERATION_TRANSFER_DATA_FLAG_NONE);
            opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_XFER_DATA_STATUS_OFF, CfApi.STATUS_SUCCESS);
            opParams.set(ValueLayout.JAVA_LONG, CfApi.OP_XFER_DATA_BUFFER_OFF, data.address());
            opParams.set(ValueLayout.JAVA_LONG, CfApi.OP_XFER_DATA_OFFSET_OFF, offset);
            opParams.set(ValueLayout.JAVA_LONG, CfApi.OP_XFER_DATA_LENGTH_OFF, length);

            int hr = CfApi.cfExecute(opInfo, opParams);
            LOG.info("[CF] CfExecute(TRANSFER_DATA) hr=0x" + Integer.toHexString(hr));
        }
    }

    private void failTransfer(long connectionKey, long transferKey, long requestKey, int status) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment opInfo   = buildOpInfo(arena, connectionKey, transferKey,
                    CfApi.CF_OPERATION_TYPE_TRANSFER_DATA, requestKey);
            MemorySegment opParams = arena.allocate(CfApi.OP_XFER_DATA_SIZE);
            opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_PARAM_SIZE_OFF,       (int) CfApi.OP_XFER_DATA_SIZE);
            opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_XFER_DATA_FLAGS_OFF,  0);
            opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_XFER_DATA_STATUS_OFF, status);
            opParams.set(ValueLayout.JAVA_LONG, CfApi.OP_XFER_DATA_BUFFER_OFF, 0L);
            opParams.set(ValueLayout.JAVA_LONG, CfApi.OP_XFER_DATA_OFFSET_OFF, 0L);
            opParams.set(ValueLayout.JAVA_LONG, CfApi.OP_XFER_DATA_LENGTH_OFF, 0L);
            CfApi.cfExecute(opInfo, opParams);
        }
    }

    // -----------------------------------------------------------------------
    // FETCH_PLACEHOLDERS callback
    // -----------------------------------------------------------------------

    public void onFetchPlaceholders(MemorySegment info, MemorySegment params) {
        info   = info.reinterpret(256);
        params = params.reinterpret(256);
        long connectionKey = 0, transferKey = 0, requestKey = 0;
        LOG.info("[CF] FETCH_PLACEHOLDERS entered");
        try {
            connectionKey = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_CONNECTION_KEY_OFF);
            transferKey   = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_TRANSFER_KEY_OFF);
            requestKey    = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_REQUEST_KEY_OFF);

            // Detect same-process callbacks (e.g. fired by CfConnectSyncRoot itself).
            if (isSameProcessCaller(info)) {
                LOG.info("[CF] FETCH_PLACEHOLDERS: same-process → fail to keep dir unpopulated");
                failPlaceholders(connectionKey, transferKey, requestKey);
                return;
            }

            String dirPath    = CfApi.readWideString(info, CfApi.CBI_NORMALIZED_PATH_OFF);
            LOG.info("[CF] FETCH_PLACEHOLDERS connKey=" + connectionKey
                    + " transferKey=" + transferKey + " dirPath='" + dirPath + "'");
            String peergosPath = normalizedToPeergos(dirPath);
            LOG.info("[CF] FETCH_PLACEHOLDERS fetching " + peergosPath);

            int fetchFlags = params.get(ValueLayout.JAVA_INT, CfApi.CBP_FETCH_PH_FLAGS_OFF);
            String pattern = CfApi.readWideString(params, CfApi.CBP_FETCH_PH_PATTERN_OFF);
            LOG.info("[CF] FETCH_PLACEHOLDERS: fetchFlags=0x" + Integer.toHexString(fetchFlags)
                    + " pattern='" + pattern + "'");

            // Synthetic root: the sync root only contains the user's home directory
            // (/$username). There's no peergos path "/" to enumerate, so we hand-craft
            // a single placeholder for the home folder.
            Set<FileWrapper> children;
            if (peergosPath.equals("/")) {
                Optional<FileWrapper> homeOpt = context.getByPath("/" + context.username).join();
                if (homeOpt.isEmpty() || !homeOpt.get().isDirectory()) {
                    LOG.info("[CF] FETCH_PLACEHOLDERS: user home not found → fail");
                    failPlaceholders(connectionKey, transferKey, requestKey);
                    return;
                }
                children = Set.of(homeOpt.get());
            } else {
                Optional<FileWrapper> dirOpt = context.getByPath(peergosPath).join();
                if (dirOpt.isEmpty() || !dirOpt.get().isDirectory()) {
                    LOG.info("[CF] FETCH_PLACEHOLDERS: path not found or not a dir → fail");
                    failPlaceholders(connectionKey, transferKey, requestKey);
                    return;
                }
                children = dirOpt.get()
                        .getChildren(context.crypto.hasher, context.network).join();
            }
            // Filter out files that already have physical placeholders on disk.
            // First FETCH_PLACEHOLDERS creates them; subsequent re-fires would error with
            // ALREADY_EXISTS. By sending count=0 with DISABLE_ON_DEMAND_POPULATION flag,
            // CF marks the directory as fully enumerated and stops firing the callback.
            Path localDir = localDirPath(dirPath);
            List<FileWrapper> visible = children.stream()
                    .filter(f -> !f.getFileProperties().isHidden)
                    .filter(f -> matchesPattern(f.getName(), pattern))
                    .filter(f -> !java.nio.file.Files.exists(localDir.resolve(f.getName())))
                    .toList();
            LOG.info("[CF] FETCH_PLACEHOLDERS: returning " + visible.size()
                    + " NEW entries matching pattern '" + pattern + "'");

            boolean syntheticRoot = peergosPath.equals("/");
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment array = visible.isEmpty()
                        ? MemorySegment.NULL
                        : buildPlaceholderArray(visible, peergosPath, arena, syntheticRoot);
                transferPlaceholders(arena, connectionKey, transferKey, requestKey, array, visible.size());
            }
            // Record initial "last synced version" for each new placeholder. After
            // TRANSFER_PLACEHOLDERS the local placeholder represents the current remote
            // state (dehydrated content + correct metadata), so synced == remote. All
            // SyncState writes go through asyncSyncState — see syncStateExec's javadoc
            // for why we don't run SQLite from CF callback threads.
            String dirRelPath = peergosPathToRelPath(peergosPath);
            if (dirRelPath != null) {
                final String dirRel = dirRelPath;
                final java.util.List<FileWrapper> snapshot = new java.util.ArrayList<>(visible);
                asyncSyncState(() -> {
                    for (FileWrapper fw : snapshot) {
                        String relPath = dirRel.isEmpty()
                                ? fw.getName()
                                : dirRel + "/" + fw.getName();
                        if (!fw.isDirectory()) recordSyncedVersion(relPath, fw);
                    }
                    // Remember that this dir is now populated. discoverNewRemoteChildren
                    // uses syncState.hasDir to decide whether descending into a placeholder
                    // dir is safe — without this, we couldn't tell a populated placeholder
                    // dir apart from an unpopulated one, and remote-side files added after
                    // CF marked the dir DISABLE_ON_DEMAND_POPULATION would stay invisible.
                    syncState.addDir(dirRel);
                });
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "FETCH_PLACEHOLDERS callback error", e);
            failPlaceholders(connectionKey, transferKey, requestKey);
        }
    }

    private void transferPlaceholders(Arena arena, long connectionKey, long transferKey, long requestKey,
                                      MemorySegment array, int count) {
        MemorySegment opInfo   = buildOpInfo(arena, connectionKey, transferKey,
                CfApi.CF_OPERATION_TYPE_TRANSFER_PLACEHOLDERS, requestKey);
        MemorySegment opParams = arena.allocate(CfApi.OP_XFER_PH_SIZE);
        opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_PARAM_SIZE_OFF,            (int) CfApi.OP_XFER_PH_SIZE);
        opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_XFER_PH_FLAGS_OFF,         CfApi.CF_OPERATION_TRANSFER_PLACEHOLDERS_FLAG_DISABLE_ON_DEMAND_POPULATION);
        opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_XFER_PH_STATUS_OFF,        CfApi.STATUS_SUCCESS);
        opParams.set(ValueLayout.JAVA_LONG, CfApi.OP_XFER_PH_ARRAY_OFF,         array == MemorySegment.NULL ? 0L : array.address());
        opParams.set(ValueLayout.JAVA_LONG, CfApi.OP_XFER_PH_TOTAL_COUNT_OFF,   (long) count);
        opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_XFER_PH_COUNT_OFF,         count);

        StringBuilder hexDump = new StringBuilder("[CF] opParams(PH) @0x")
                .append(Long.toHexString(opParams.address())).append(":");
        for (int i = 0; i < (int) CfApi.OP_XFER_PH_SIZE; i++)
            hexDump.append(String.format(" %02x", opParams.get(ValueLayout.JAVA_BYTE, i) & 0xFF));
        LOG.info(hexDump.toString());
        int hr = CfApi.cfExecute(opInfo, opParams);
        int entriesProcessed = opParams.get(ValueLayout.JAVA_INT, CfApi.OP_XFER_PH_ENTRIES_PROCESSED_OFF);
        LOG.info("[CF] CfExecute(TRANSFER_PLACEHOLDERS) hr=0x" + Integer.toHexString(hr)
                + " entriesProcessed=" + entriesProcessed);
        if (array != MemorySegment.NULL) {
            for (int i = 0; i < count; i++) {
                long base = CfApi.PCI_SIZE * i;
                int resultHr = array.get(ValueLayout.JAVA_INT, base + CfApi.PCI_RESULT_OFF);
                long createUsn = array.get(ValueLayout.JAVA_LONG, base + CfApi.PCI_CREATE_USN_OFF);
                LOG.info("[CF] Placeholder[" + i + "] Result=0x" + Integer.toHexString(resultHr)
                        + " CreateUsn=" + createUsn);
            }
        }
        java.io.File[] physical = new java.io.File(syncRootPath).listFiles();
        LOG.info("[CF] Physical files after TRANSFER_PLACEHOLDERS: "
                + (physical == null ? "null" : java.util.Arrays.stream(physical)
                        .map(java.io.File::getName).collect(java.util.stream.Collectors.joining(", "))));
        if (hr != CfApi.S_OK)
            LOG.warning("CfExecute(TRANSFER_PLACEHOLDERS) returned 0x" + Integer.toHexString(hr));
    }

    private void failPlaceholders(long connectionKey, long transferKey, long requestKey) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment opInfo   = buildOpInfo(arena, connectionKey, transferKey,
                    CfApi.CF_OPERATION_TYPE_TRANSFER_PLACEHOLDERS, requestKey);
            MemorySegment opParams = arena.allocate(CfApi.OP_XFER_PH_SIZE);
            opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_PARAM_SIZE_OFF,     (int) CfApi.OP_XFER_PH_SIZE);
            opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_XFER_PH_FLAGS_OFF,  0);
            opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_XFER_PH_STATUS_OFF, -1);
            opParams.set(ValueLayout.JAVA_LONG, CfApi.OP_XFER_PH_ARRAY_OFF,  0L);
            opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_XFER_PH_COUNT_OFF,  0);
            CfApi.cfExecute(opInfo, opParams);
        }
    }

    // -----------------------------------------------------------------------
    // Seed top-level placeholders into the sync root directory
    // -----------------------------------------------------------------------

    /**
     * Called once on mount to populate the sync root's single top-level entry
     * (the $username directory) so Explorer shows it without requiring an
     * explicit FETCH_PLACEHOLDERS callback. Idempotent — skips when the
     * placeholder is already on disk from a previous run.
     */
    public void seedRootPlaceholders(Arena arena) throws Exception {
        if (Files.exists(Path.of(syncRootPath, context.username))) {
            LOG.info("[CF] seedRootPlaceholders: " + context.username
                    + " already on disk, skipping");
            return;
        }
        Optional<FileWrapper> homeOpt = context.getByPath("/" + context.username).join();
        if (homeOpt.isEmpty()) return;

        List<FileWrapper> visible = List.of(homeOpt.get());

        MemorySegment baseDirW = CfApi.wideString(syncRootPath, arena);
        MemorySegment array    = buildPlaceholderArray(visible, "/", arena, true);
        MemorySegment processed = arena.allocate(ValueLayout.JAVA_INT);

        LOG.info("[CF] CfCreatePlaceholders: syncRoot=" + syncRootPath + " count=" + visible.size()
                + " names=" + visible.stream().map(f -> f.getName()).collect(java.util.stream.Collectors.joining(",")));
        int hr = CfApi.cfCreatePlaceholders(baseDirW, array, visible.size(),
                CfApi.CF_CREATE_FLAG_NONE, processed);
        int entriesProcessed = processed.get(ValueLayout.JAVA_INT, 0);
        LOG.info("[CF] CfCreatePlaceholders hr=0x" + Integer.toHexString(hr)
                + " entriesProcessed=" + entriesProcessed);
        // Verify physical files are visible on the real filesystem after creation.
        java.io.File[] physicalFiles = new java.io.File(syncRootPath).listFiles();
        LOG.info("[CF] Physical files in syncRoot after CfCreatePlaceholders: "
                + (physicalFiles == null ? "null" : java.util.Arrays.stream(physicalFiles)
                        .map(java.io.File::getName).collect(java.util.stream.Collectors.joining(", "))));
    }

    // -----------------------------------------------------------------------
    // NOTIFY_FILE_CLOSE_COMPLETION — write back modified files
    // -----------------------------------------------------------------------

    // CF requires these to be registered (matching Nextcloud's pattern) for the hydration
    // state machine to advance. We log only — CF handles the actual semantics.

    public void onValidateData(MemorySegment info, MemorySegment params) {
        LOG.info("[CF] VALIDATE_DATA callback");
    }

    public void onCancelFetchData(MemorySegment info, MemorySegment params) {
        LOG.info("[CF] CANCEL_FETCH_DATA callback");
    }

    public void onFileOpenCompletion(MemorySegment info, MemorySegment params) {
        LOG.info("[CF] FILE_OPEN_COMPLETION callback");
    }

    public void onFileCloseCompletion(MemorySegment info, MemorySegment params) {
        info   = info.reinterpret(256);
        params = params.reinterpret(256);
        int flags;
        try { flags = params.get(ValueLayout.JAVA_INT, CfApi.CBP_CLOSE_FLAGS_OFF); }
        catch (Exception e) { LOG.log(Level.WARNING, "CLOSE_COMPLETION: failed to read params", e); return; }
        if ((flags & CfApi.CF_CALLBACK_NOTIFY_FILE_CLOSE_COMPLETION_FLAG_DELETED) != 0) {
            LOG.info("[CF] FILE_CLOSE_COMPLETION: file deleted, skipping");
            return; // file was deleted on close — handled by DELETE callback
        }

        String normalizedPath = CfApi.readWideString(info, CfApi.CBI_NORMALIZED_PATH_OFF);
        String peergosPath    = normalizedToPeergos(normalizedPath);
        // normalizedPath is an absolute path within the volume ("\Users\...\file.txt").
        // Reattach the drive letter from syncRootPath ("C:") to form a full path.
        String drive  = syncRootPath.substring(0, 2); // e.g. "C:"
        Path   localPath = Path.of(drive + normalizedPath);

        LOG.info("[CF] FILE_CLOSE_COMPLETION: normalizedPath='" + normalizedPath
                + "' peergosPath='" + peergosPath + "' localPath='" + localPath + "' flags=0x"
                + Integer.toHexString(flags));

        // Truncation guard: if we're mid-download for this path, the local file is
        // partially written and Files.size returns the partial size. Routing it
        // through the write-back logic below would invoke overwriteChangedChunks
        // with the partial size and truncate the canonical remote file. Skip.
        if (downloadsInFlight.contains(localPath)) {
            LOG.info("[CF] FILE_CLOSE_COMPLETION: download in flight, skipping " + localPath);
            return;
        }
        if (recentlyUploaded.remove(normPathKey(localPath)) != null) {
            LOG.info("[CF] FILE_CLOSE_COMPLETION: just uploaded by watcher, skipping");
            return;
        }

        if (!Files.exists(localPath) || Files.isDirectory(localPath)) {
            LOG.info("[CF] FILE_CLOSE_COMPLETION: localPath missing or directory, skipping");
            return;
        }

        long localSize;
        long localMtimeMs;
        try {
            localSize = Files.size(localPath);
            localMtimeMs = Files.getLastModifiedTime(localPath).toMillis() / 1000 * 1000;
        } catch (Exception e) { LOG.log(Level.WARNING, "CLOSE_COMPLETION: size/mtime read failed", e); return; }

        // Fast-path: if syncState already records this file at the same size+mtime,
        // skip the network round-trip. CF fires FILE_CLOSE_COMPLETION for every read
        // of a placeholder (anti-virus scans, Explorer thumbnail/preview, indexer),
        // not just writes — without this guard each one pays a CHAMP traversal via
        // context.getByPath before the "no write-back needed" branch below catches
        // it. Observed ~130 close events/sec when AV / indexer touched a 1000-file
        // dir, which alone dominated the steady-state bandwidth.
        if (syncState != null) {
            Path syncRoot = Path.of(syncRootPath);
            try {
                String relPath = syncRoot.relativize(localPath).toString()
                        .replace(java.io.File.separatorChar, '/');
                FileState synced = syncState.byPath(relPath);
                if (synced != null && synced.size == localSize
                        && synced.modificationTime == localMtimeMs)
                    return;
            } catch (IllegalArgumentException ignored) {
                // localPath wasn't under syncRoot — fall through to the network path.
            }
        }
        LOG.info("[CF] FILE_CLOSE_COMPLETION: localSize=" + localSize);

        // CF doesn't tell us whether the close followed a write or just a read. We use the
        // local mtime as a cheap dirty-bit: when we materialise a placeholder via
        // TRANSFER_PLACEHOLDERS we stamp the local file with Peergos's stored mtime, so a
        // pristine placeholder has localMtime == peergosMtime. A local write bumps localMtime;
        // a read leaves it unchanged. So:
        //   size differs                 → user definitely wrote → upload
        //   size matches, localMtime >   → user probably wrote → upload (chunk-dedup decides)
        //   size matches, localMtime ≤   → no write detected → skip
        // overwriteChangedChunks does chunk-level SHA-256 dedup, so a false positive only
        // costs a local hash scan, not upload bandwidth.
        String peergosParent = peergosPath.substring(0, peergosPath.lastIndexOf('/'));
        Optional<FileWrapper> existingOpt;
        try { existingOpt = context.getByPath(peergosPath).join(); }
        catch (Exception e) { LOG.log(Level.WARNING, "CLOSE_COMPLETION: lookup failed", e); return; }
        // Read-only guard: skip write-back if the existing file is not writable.
        // For brand-new files (no existing), the parent-writable check happens further down
        // where parentOpt is resolved.
        if (existingOpt.isPresent() && !existingOpt.get().isWritable()) {
            LOG.info("[CF] FILE_CLOSE_COMPLETION: " + peergosPath
                    + " is read-only in peergos, skipping write-back");
            return;
        }
        if (existingOpt.isPresent() && !existingOpt.get().isDirectory()
                && existingOpt.get().getSize() == localSize) {
            LocalDateTime peergosMtime = existingOpt.get().getFileProperties().modified;
            LocalDateTime localMtime;
            try {
                localMtime = LocalDateTime.ofInstant(
                        Files.getLastModifiedTime(localPath).toInstant(),
                        java.time.ZoneOffset.UTC);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "CLOSE_COMPLETION: mtime read failed", e);
                return;
            }
            if (peergosMtime != null && !localMtime.isAfter(peergosMtime)) {
                LOG.info("[CF] FILE_CLOSE_COMPLETION: size+mtime match (peergos="
                        + peergosMtime + " local=" + localMtime + "), no write-back needed");
                return;
            }
            LOG.info("[CF] FILE_CLOSE_COMPLETION: size matches but localMtime="
                    + localMtime + " > peergosMtime=" + peergosMtime
                    + " → handing to overwriteChangedChunks (chunk-level dedup)");
        }
        if (localSize == 0) {
            LOG.info("[CF] FILE_CLOSE_COMPLETION: local file empty, skipping write-back");
            return;
        }

        String name = localPath.getFileName().toString();
        LOG.info("[CF] FILE_CLOSE_COMPLETION: uploading " + localSize
                + " bytes to " + peergosPath);

        Optional<FileWrapper> parentOpt = context.getByPath(peergosParent).join();
        if (parentOpt.isEmpty()) {
            LOG.info("[CF] FILE_CLOSE_COMPLETION: parent missing: " + peergosParent);
            return;
        }
        if (!parentOpt.get().isWritable()) {
            LOG.info("[CF] FILE_CLOSE_COMPLETION: parent " + peergosParent
                    + " is read-only, skipping upload of " + peergosPath);
            return;
        }

        // Conflict detection. We look up the "last synced version" in our state DB:
        //   case A — synced.hash == remote.hash:
        //              remote unchanged since our last sync; safe to push local update.
        //   case B — synced == null && remote == null:
        //              brand new file from this side; just upload.
        //   case C — synced.hash != remote.hash but local.hash == remote.hash:
        //              we already have the same content as remote; just update state, no upload.
        //   case D — synced.hash != remote.hash and local.hash != remote.hash:
        //              real conflict. rename local to foo[conflict-N].ext, pull remote into
        //              the original name, upload the renamed file as a new entry.
        try {
            String relPath = peergosPathToRelPath(peergosPath);
            FileState synced = (syncState != null && relPath != null)
                    ? syncState.byPath(relPath) : null;
            peergos.shared.user.fs.RootHash remoteHash = existingOpt
                    .flatMap(fw -> fw.getFileProperties().treeHash)
                    .map(b -> b.rootHash)
                    .orElse(null);

            boolean remoteUnchangedSinceSync =
                    synced != null && remoteHash != null
                    && synced.hashTree.rootHash.equals(remoteHash);

            if (remoteUnchangedSinceSync || existingOpt.isEmpty()) {
                // case A or B: safe to push
                pushLocalToPeergos(localPath, localSize, name, peergosPath,
                        peergosParent, existingOpt, parentOpt);
                return;
            }

            // case C or D: remote moved since our last sync. Need to hash local to tell apart.
            peergos.shared.user.fs.HashTree localHash = hashLocalFile(localPath, localSize);
            if (localHash != null && remoteHash != null
                    && localHash.rootHash.equals(remoteHash)) {
                // case C: local already matches remote, just record state.
                LOG.info("[CF] CONFLICT: local content already matches remote, no upload");
                if (relPath != null && existingOpt.isPresent())
                    recordSyncedVersion(relPath, existingOpt.get());
                return;
            }

            // case D: real conflict — rename local, pull remote into original name, upload renamed.
            LOG.info("[CF] CONFLICT: local and remote both moved since last sync — resolving");
            resolveLocalConflict(localPath, relPath, localHash, localSize,
                    peergosPath, peergosParent, existingOpt, parentOpt);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Write-back failed for " + peergosPath, e);
        }
    }

    /** Push local content to Peergos via overwriteChangedChunks (existing) or uploadFileWithHash (new).
     *  Always sets the file's treeHash explicitly afterwards — mirrors PeergosSyncFS.setBytes /
     *  PeergosSyncFS.setHashes. This guarantees every conflict check after this upload has a
     *  populated treeHash to compare against.
     *
     *  Surfaces upload progress in File Explorer by marking the placeholder NOT_IN_SYNC
     *  before the upload and flipping it to IN_SYNC after success. CF only fires
     *  FILE_CLOSE_COMPLETION for files it manages, so the local file is always already a
     *  placeholder here. */
    private void pushLocalToPeergos(Path localPath, long localSize, String name,
                                    String peergosPath, String peergosParent,
                                    Optional<FileWrapper> existingOpt,
                                    Optional<FileWrapper> parentOpt) throws Exception {
        setInSyncState(localPath, CfApi.CF_IN_SYNC_STATE_NOT_IN_SYNC);
        HashTree localHash = hashLocalFile(localPath, localSize);
        LocalDateTime localModified = LocalDateTime.ofInstant(
                Files.getLastModifiedTime(localPath).toInstant(),
                java.time.ZoneOffset.UTC);
        try (peergos.server.simulation.FileAsyncReader reader =
                     new peergos.server.simulation.FileAsyncReader(localPath.toFile())) {
            if (existingOpt.isPresent() && !existingOpt.get().isDirectory()) {
                existingOpt.get().overwriteChangedChunks(
                        reader, localSize,
                        context.network, context.crypto, l -> {}).join();
                if (localHash != null) applyHash(peergosPath, localHash);
                stampPeergosMtime(peergosPath, localModified);
            } else {
                parentOpt.get().uploadFileWithHash(name,
                        reader, localSize, Optional.ofNullable(localHash),
                        Optional.of(localModified), Optional.empty(),
                        context.network, context.crypto, l -> {}).join();
            }
            LOG.info("[CF] FILE_CLOSE_COMPLETION: upload complete for " + peergosPath);
            context.getByPath(peergosPath).join().ifPresent(
                    fw -> recordSyncedVersion(localPath, fw));
        }
        setInSyncState(localPath, CfApi.CF_IN_SYNC_STATE_IN_SYNC);
    }

    /** Set the file's modified time to {@code localModified} so syncState.modificationTime
     *  (recorded from the peergos FileWrapper) matches the local placeholder's mtime —
     *  applyRemoteDelete / applyRemoteChangeOrConflict use that equality as the "no local
     *  edits since last sync" signal. {@code overwriteChangedChunks} doesn't accept a
     *  modified time, so we update it as a follow-up; {@code uploadFileWithHash} takes
     *  it directly and doesn't need this. */
    private Optional<FileWrapper> stampPeergosMtime(String peergosPath, LocalDateTime localModified) {
        try {
            Optional<FileWrapper> fwOpt = context.getByPath(peergosPath).join();
            if (fwOpt.isEmpty()) return Optional.empty();
            FileWrapper fw = fwOpt.get();
            FileProperties updated = fw.getFileProperties().withModified(localModified);
            fw.setSameNameProperties(updated, context.network).join();
            return context.getByPath(peergosPath).join();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "stampPeergosMtime failed for " + peergosPath, e);
            return Optional.empty();
        }
    }

    /** Apply a precomputed HashTree to an already-uploaded file via PropsUpdate.
     *  Mirrors PeergosSyncFS.setHashes — used after overwriteChangedChunks (which doesn't
     *  set the hash itself) so conflict-detection always has a populated baseline. */
    private void applyHash(String peergosPath, HashTree hash) {
        try {
            Optional<FileWrapper> fwOpt = context.getByPath(peergosPath).join();
            if (fwOpt.isEmpty()) return;
            java.util.List<FileWrapper.PropsUpdate> updates =
                    fwOpt.get().getHashUpdates(hash, context.network, context.crypto.hasher).join();
            if (updates.isEmpty()) return;
            FileWrapper.bulkSetSameNameProperties(updates, context.network).join();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "applyHash failed for " + peergosPath, e);
        }
    }

    /**
     * Resolve a conflict: rename local to foo[conflict-N].ext, download the current
     * remote content into the original local path, then upload the renamed local file
     * as a new Peergos entry under the conflict name.
     */
    private void resolveLocalConflict(Path localPath, String relPath,
                                      peergos.shared.user.fs.HashTree localHash, long localSize,
                                      String peergosPath, String peergosParent,
                                      Optional<FileWrapper> existingOpt,
                                      Optional<FileWrapper> parentOpt) throws Exception {
        // 1) Rename local out of the way.
        long localMtime;
        try { localMtime = Files.getLastModifiedTime(localPath).toMillis(); }
        catch (IOException e) { localMtime = System.currentTimeMillis(); }
        FileState localFs = new FileState(relPath, localMtime, localSize, localHash);
        FileState renamed = peergos.server.sync.DirectorySync.renameOnConflict(
                LOCAL_FS_FOR_RENAME, localPath, localFs);
        Path renamedLocal = localPath.resolveSibling(
                renamed.relPath.substring(renamed.relPath.lastIndexOf('/') + 1));
        LOG.info("[CF] CONFLICT: renamed local " + localPath.getFileName()
                + " → " + renamedLocal.getFileName());
        // Pre-suppress the close-completion that the rename fires on the new name.
        recentlyUploaded.put(normPathKey(renamedLocal), Boolean.TRUE);

        // 2) Download remote content into the original local path. Wrapped with the
        // CopyOp + downloadsInFlight + recentlyUploaded guard inside downloadRemoteToLocal
        // so a mid-conflict interruption gets re-driven on next mount, and the partial
        // file can't trick onFileCloseCompletion into truncating the remote.
        FileWrapper remote = existingOpt.get();
        downloadRemoteToLocal(remote, localPath, peergosPath);
        if (relPath != null) recordSyncedVersion(relPath, remote);
        // Files.newOutputStream re-created localPath as a regular file, so the original
        // placeholder state was destroyed. Convert it back to an in-sync placeholder
        // since its content now matches the canonical remote — without this it would
        // show no cloud overlay at all in File Explorer.
        convertToPlaceholder(localPath, peergosPath, CfApi.CF_CONVERT_FLAG_MARK_IN_SYNC);

        // 4) Upload the renamed local file as a new Peergos entry under the conflict name.
        // We already have the localHash computed by the caller — pass it through so the
        // resulting Peergos file has its treeHash populated immediately.
        // The rename in step 1 carried the original placeholder state across, so
        // renamedLocal is itself a placeholder we can flip NOT_IN_SYNC → IN_SYNC around
        // the upload to surface progress in File Explorer.
        setInSyncState(renamedLocal, CfApi.CF_IN_SYNC_STATE_NOT_IN_SYNC);
        try (peergos.server.simulation.FileAsyncReader reader =
                     new peergos.server.simulation.FileAsyncReader(renamedLocal.toFile())) {
            String conflictName = renamedLocal.getFileName().toString();
            parentOpt.get().uploadFileWithHash(conflictName,
                    reader, localSize, Optional.ofNullable(localHash),
                    Optional.empty(), Optional.empty(),
                    context.network, context.crypto, l -> {}).join();
        }
        setInSyncState(renamedLocal, CfApi.CF_IN_SYNC_STATE_IN_SYNC);
        String conflictPeergosPath = peergosParent + "/" + renamedLocal.getFileName();
        context.getByPath(conflictPeergosPath).join().ifPresent(
                fw -> recordSyncedVersion(renamedLocal, fw));
        LOG.info("[CF] CONFLICT: resolved — local " + localPath.getFileName()
                + " now has remote content, conflict copy at "
                + renamedLocal.getFileName());
    }

    // -----------------------------------------------------------------------
    // Pull loop — detect remote-only edits and pull them into local placeholders.
    // Three tiers from cheap to expensive (see /home/ian/dev/conflict.md):
    //  Tier 1: Snapshot equality (writer-set vs last-synced snapshot). O(writers).
    //  Tier 2: per-path hash diff (remote vs synced). Only runs if Tier 1 differs.
    //  Tier 3: per-path apply with local mtime/size verification + pull or conflict.
    // -----------------------------------------------------------------------

    /** Relative paths of every dir we've populated locally (either via FETCH_PLACEHOLDERS
     *  or local mkdir+convert). Used by the watcher on mount startup to register watches
     *  on placeholder dirs that registerRecursive skips (because walking into them would
     *  trigger same-process FETCH_PLACEHOLDERS). Without this, on a remount no event ever
     *  fires for $user/ on syncRoot's watch and the lazy registration chain never starts. */
    java.util.Set<String> getKnownDirs() {
        return syncState == null ? java.util.Set.of() : syncState.getDirs();
    }

    /**
     * Re-drive any CopyOps that were in flight when the previous mount session closed.
     * Both {@link #uploadLocalFile} and {@link #downloadRemoteToLocal} persist a
     * CopyOp via {@code startCopies} before touching the network and remove it via
     * {@code finishCopies} on success — anything still in {@code getInProgressCopies}
     * on the next start is a transfer that didn't finish.
     * <p>
     * For uploads ({@code isLocalTarget == false}): re-call {@link #uploadLocalFile}
     * on the {@code uploadExec}. The {@link #uploadsInFlight} guard prevents racing
     * the watcher / pull-tick if they pick up the same path independently.
     * <p>
     * For downloads ({@code isLocalTarget == true}): re-resolve the remote via
     * {@code context.getByPath} and re-call {@link #downloadRemoteToLocal}. A missing
     * remote means the source was deleted while we were offline — drop the entry.
     * <p>
     * In all cases, a missing local source (uploads) or missing remote (downloads)
     * results in {@code finishCopies} clearing the stale row.
     */
    public void rerunPendingUploads(java.util.concurrent.ExecutorService uploadExec) {
        if (syncState == null) return;
        java.util.List<CopyOp> ops;
        try { ops = syncState.getInProgressCopies(); }
        catch (Exception e) {
            LOG.log(Level.WARNING, "rerunPendingUploads: failed to read syncState", e);
            return;
        }
        for (CopyOp op : ops) {
            if (op.isLocalTarget) {
                redriveDownload(op, uploadExec);
            } else {
                redriveUpload(op, uploadExec);
            }
        }
    }

    private void redriveUpload(CopyOp op, java.util.concurrent.ExecutorService uploadExec) {
        Path localPath = op.source;
        if (!Files.exists(localPath)) {
            try { syncState.finishCopies(java.util.List.of(op)); }
            catch (Exception e) {
                LOG.log(Level.WARNING, "redriveUpload: finishCopies failed for " + localPath, e);
            }
            return;
        }
        LOG.info("[CF] rerunPendingUploads: re-driving upload " + localPath
                + " -> " + op.target);
        try {
            uploadExec.submit(() -> uploadLocalFile(localPath));
        } catch (java.util.concurrent.RejectedExecutionException rej) {
            LOG.log(Level.WARNING, "redriveUpload: executor rejected " + localPath, rej);
        }
    }

    private void redriveDownload(CopyOp op, java.util.concurrent.ExecutorService uploadExec) {
        Path localPath = op.target;
        String peergosPath = op.source.toString().replace(java.io.File.separatorChar, '/');
        if (!peergosPath.startsWith("/")) peergosPath = "/" + peergosPath;
        final String fullPeergosPath = peergosPath;
        try {
            uploadExec.submit(() -> {
                try {
                    Optional<FileWrapper> remoteOpt = context.getByPath(fullPeergosPath).join();
                    if (remoteOpt.isEmpty() || remoteOpt.get().isDirectory()) {
                        // Remote vanished or is no longer a file — drop the stale op.
                        LOG.info("[CF] rerunPendingUploads: remote gone for "
                                + fullPeergosPath + ", dropping pending download");
                        syncState.finishCopies(java.util.List.of(op));
                        return;
                    }
                    LOG.info("[CF] rerunPendingUploads: re-driving download "
                            + fullPeergosPath + " -> " + localPath);
                    downloadRemoteToLocal(remoteOpt.get(), localPath, fullPeergosPath);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "redriveDownload failed for " + localPath, e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rej) {
            LOG.log(Level.WARNING, "redriveDownload: executor rejected " + localPath, rej);
        }
    }

    void runPullTick() {
        if (syncState == null) return;
        // Tier 2c — upload local files added while the mount wasn't running. Runs even
        // when the writer-set snapshot is empty or unchanged: offline-added files leave
        // no trace in any writer's history, so the snapshot-based checks below can't
        // see them.
        try {
            discoverMissedLocalUploads();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "pull: discoverMissedLocalUploads failed", e);
        }
        try {
            // Tier 1 — snapshot equality across all writers we've ever recorded.
            // The persisted snapshot accumulates writers via recordSyncedVersion (one entry
            // per writer that owns a file we know about). Empty snapshot means we haven't
            // synced anything yet — nothing remote to detect either, so we bail.
            peergos.shared.user.Snapshot syncedSnap = syncState.getSnapshot(syncRootPath);
            if (syncedSnap == null || syncedSnap.versions.isEmpty()) return;
            java.util.Set<peergos.shared.crypto.hash.PublicKeyHash> writers =
                    new java.util.HashSet<>(syncedSnap.versions.keySet());
            // Owner is needed by withWriters to look up each writer's data. We assume
            // owner == the user for now (matches single-user setups and most files);
            // shared subtrees with a different owner would need their owner tracked
            // alongside the writer in the persisted state. TODO when multi-owner is needed.
            peergos.shared.crypto.hash.PublicKeyHash owner = context.signer.publicKeyHash;
            peergos.shared.user.Snapshot remoteSnap = new peergos.shared.user.Snapshot(
                    new java.util.HashMap<>())
                    .withWriters(owner, writers, context.network).join();
            if (remoteSnap.equals(syncedSnap)) return;          // nothing moved

            // Tier 2 — path-level shortlist, grouped by parent dir so each dir's
            // child set is fetched in one capability listing plus one batched FW
            // fetch, instead of N separate getByPath calls per dir (each of which
            // re-walks the cryptree from root). On a 100-file dir with 5 remote
            // deletes the old shape issued ~100 CHAMP traversals; this shape
            // issues 1 parent lookup + 1 cap listing + 1 batched FW fetch.
            // Snapshot the set so applyRemoteDelete can mutate syncState while we iterate.
            java.util.Set<String> allPaths = new java.util.HashSet<>(syncState.allFilePaths());
            java.util.Map<String, java.util.List<String>> pathsByParent = new java.util.HashMap<>();
            for (String relPath : allPaths) {
                int slash = relPath.lastIndexOf('/');
                String parentRel = slash < 0 ? "" : relPath.substring(0, slash);
                pathsByParent.computeIfAbsent(parentRel, k -> new java.util.ArrayList<>()).add(relPath);
            }
            for (java.util.Map.Entry<String, java.util.List<String>> e : pathsByParent.entrySet()) {
                processParentDir(e.getKey(), e.getValue());
            }

            // Tier 2-dirs — propagate remote directory deletions. Tier 2 above cleared
            // the files inside any remotely-deleted dir, so the local dir itself should
            // now be empty and safe to delete. Runs AFTER the file tier so child files
            // have already been Files.deleted by applyRemoteDelete.
            applyRemoteDirDeletes();

            // Tier 2b — discover remote children that appeared in dirs the user has
            // already enumerated locally. CF sets DISABLE_ON_DEMAND_POPULATION on every
            // dir we serve via FETCH_PLACEHOLDERS, so it never fires the callback again
            // for those dirs — without this walk, new remote files in an enumerated dir
            // would stay invisible until the user manually re-listed (which they can't
            // force).
            discoverNewRemoteChildren();

            // Persist the new snapshot baseline.
            syncState.setSnapshot(syncRootPath, remoteSnap);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "pull tick failed", e);
        }
    }

    /** Per-parent step of the Tier-2 pull pass. Fetches the parent's child capability
     *  list once, then:
     *  <ul>
     *    <li>For each tracked path whose filename is missing from the cap list →
     *        {@link #applyRemoteDelete}.</li>
     *    <li>For each still-present name, batch-fetches the FileWrappers in one
     *        {@code getChildrenFromCaps} call and compares tree hashes for the
     *        Tier-3 change-or-conflict decision.</li>
     *  </ul>
     *  If the parent itself is gone, every tracked child gets {@code applyRemoteDelete}. */
    private void processParentDir(String parentRel, java.util.List<String> trackedRelPaths) {
        String parentPeergosPath = "/" + parentRel;
        Optional<FileWrapper> parentOpt;
        try { parentOpt = context.getByPath(parentPeergosPath).join(); }
        catch (Exception e) {
            LOG.log(Level.WARNING, "pull: parent lookup failed for " + parentPeergosPath, e);
            return;
        }
        if (parentOpt.isEmpty()) {
            for (String relPath : trackedRelPaths) {
                FileState synced = syncState.byPath(relPath);
                if (synced != null) applyRemoteDelete(relPath, synced);
            }
            return;
        }
        FileWrapper parent = parentOpt.get();
        if (!parent.isDirectory()) return;

        java.util.Set<peergos.shared.user.fs.NamedAbsoluteCapability> caps;
        try {
            caps = parent.getChildrenCapabilities(context.crypto.hasher, context.network).join();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "pull: child cap listing failed for " + parentPeergosPath, e);
            return;
        }
        java.util.Map<String, peergos.shared.user.fs.NamedAbsoluteCapability> capsByName = new java.util.HashMap<>();
        for (peergos.shared.user.fs.NamedAbsoluteCapability c : caps)
            capsByName.put(c.name.name, c);

        // Detect deletes (missing names) and collect caps for surviving children
        // so we can fetch their FileWrappers in one batch below.
        java.util.Set<peergos.shared.user.fs.NamedAbsoluteCapability> survivingCaps = new java.util.HashSet<>();
        java.util.Map<String, String> relByName = new java.util.HashMap<>();
        for (String relPath : trackedRelPaths) {
            FileState synced = syncState.byPath(relPath);
            if (synced == null) continue;
            String filename = relPath.substring(relPath.lastIndexOf('/') + 1);
            peergos.shared.user.fs.NamedAbsoluteCapability c = capsByName.get(filename);
            if (c == null) {
                applyRemoteDelete(relPath, synced);
                continue;
            }
            if (c.isDir.orElse(false)) continue;
            survivingCaps.add(c);
            relByName.put(filename, relPath);
        }
        if (survivingCaps.isEmpty()) return;

        // Tier 3 — batch-fetch FileWrappers for the survivors and compare tree hashes.
        java.util.Set<FileWrapper> fws = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        try {
            parent.getChildrenFromCaps(survivingCaps, fws::addAll,
                    context.crypto.hasher, context.network).join();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "pull: child FW fetch failed for " + parentPeergosPath, e);
            return;
        }
        for (FileWrapper remote : fws) {
            if (remote.isDirectory()) continue;
            String relPath = relByName.get(remote.getName());
            if (relPath == null) continue;
            FileState synced = syncState.byPath(relPath);
            if (synced == null) continue;
            peergos.shared.user.fs.RootHash remoteHash = remote.getFileProperties()
                    .treeHash.map(b -> b.rootHash).orElse(null);
            if (remoteHash == null || remoteHash.equals(synced.hashTree.rootHash)) continue;
            applyRemoteChangeOrConflict(relPath, synced, remote);
        }
    }

    /** Remote file at {@code relPath} no longer exists. Mirror the delete locally unless
     *  the local copy has diverged from the last synced version — in that case keep the
     *  local bytes (the user's unsynced edits) and just stop tracking the path; the watcher
     *  will treat any subsequent edit as a new local file and re-upload it. */
    private void applyRemoteDelete(String relPath, FileState synced) {
        Path localPath = Path.of(syncRootPath).resolve(relPath.replace('/', java.io.File.separatorChar));
        try {
            if (!Files.exists(localPath)) {
                // Already gone locally — just drop the stale entry.
                syncState.remove(relPath);
                identityToPath.values().removeIf(p -> p.equals("/" + relPath));
                return;
            }
            long localMtime = Files.getLastModifiedTime(localPath).toMillis() / 1000 * 1000;
            long localSize  = Files.size(localPath);
            if (localMtime != synced.modificationTime || localSize != synced.size) {
                // Local has unsynced edits — don't destroy them. Stop tracking so the next
                // pull tick doesn't keep re-firing; the watcher's MODIFY/CLOSE on a future
                // edit will re-upload it as a new entry.
                LOG.info("[CF] PULL: remote " + relPath + " deleted but local diverged"
                        + " (mtime/size mismatch) — keeping local, removing from sync state");
                syncState.remove(relPath);
                identityToPath.values().removeIf(p -> p.equals("/" + relPath));
                return;
            }
            Files.delete(localPath);
            syncState.remove(relPath);
            identityToPath.values().removeIf(p -> p.equals("/" + relPath));
            LOG.info("[CF] PULL: removed local " + relPath + " (deleted on remote)");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "pull: local delete failed for " + relPath, e);
        }
    }

    /**
     * Iterate every dir we've recorded in {@link SyncState#getDirs()}; for any whose
     * remote {@code getByPath} is empty, delete the local placeholder dir (if empty)
     * and drop the syncState entry. Sorted deepest-first so child dirs are removed
     * before their parents, otherwise the parent's {@code Files.delete} would fail
     * with {@code DirectoryNotEmptyException}.
     *
     * <p>Skips:
     * <ul>
     *   <li>Sync-root-level entries (the {@code $user/} folder — its absence remotely
     *       would mean the account itself is gone, never propagate that locally).</li>
     *   <li>Non-empty local dirs — the user may have unsynced files we don't want to
     *       destroy. The {@code syncState} entry stays in that case so the dir keeps
     *       being checked; the dir won't be removed until its contents drain.</li>
     * </ul>
     */
    private void applyRemoteDirDeletes() {
        java.util.List<String> dirs = new java.util.ArrayList<>(syncState.getDirs());
        // Deepest first: dir name count strictly descending, then any stable order.
        dirs.sort((a, b) -> Integer.compare(b.split("/").length, a.split("/").length));
        for (String relPath : dirs) {
            if (!relPath.contains("/")) continue;   // sync-root-level ($user/)
            try {
                Optional<FileWrapper> remoteOpt = context.getByPath("/" + relPath).join();
                if (remoteOpt.isPresent()) continue;
                Path localPath = Path.of(syncRootPath).resolve(
                        relPath.replace('/', java.io.File.separatorChar));
                if (Files.exists(localPath)) {
                    try {
                        Files.delete(localPath);
                        LOG.info("[CF] PULL: removed local dir " + relPath
                                + " (deleted on remote)");
                    } catch (java.nio.file.DirectoryNotEmptyException e) {
                        // Leave the dir alone — the user has unsynced content in there.
                        // Don't drop the syncState entry either, so we keep checking it
                        // each tick; once the user clears the leftovers we'll catch up.
                        LOG.info("[CF] PULL: remote dir " + relPath
                                + " gone but local has unsynced children — keeping local");
                        continue;
                    }
                }
                syncState.removeDir(relPath);
                identityToPath.values().removeIf(p ->
                        p.equals("/" + relPath) || p.startsWith("/" + relPath + "/"));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "pull: remote-dir-delete check failed for " + relPath, e);
            }
        }
    }

    /**
     * Walk the local sync root and upload any non-placeholder file with content that
     * isn't already in {@code syncState}. Compensates for WatchService events the
     * mount couldn't see — chiefly files dropped into the sync root while the mount
     * wasn't running (the watcher only catches changes during its own polling window).
     *
     * <p>Safety gate: descends only into the sync root, non-placeholder subdirs, and
     * placeholder subdirs already in {@code syncState.getDirs()} (i.e. ones we
     * populated previously). Walking into an un-populated placeholder dir would call
     * {@code newDirectoryStream}, which triggers FETCH_PLACEHOLDERS, which we fail
     * same-process — empirically that leaves the dir appearing empty in Explorer.
     * The gate keeps lazy population of un-enumerated dirs to the FETCH_PLACEHOLDERS
     * path where it belongs.
     *
     * <p>For each non-placeholder file with non-zero size that {@code syncState}
     * doesn't already track, calls {@link #uploadLocalFile} (which is idempotent —
     * no-op if peergos already has matching size). Cheap on a fully-synced tree
     * because the per-entry checks ({@code isPlaceholder}, {@code attrs.size}, the
     * sync-state lookup) are all in-process.
     */
    private void discoverMissedLocalUploads() {
        if (syncState == null) return;
        Path root = Path.of(syncRootPath);
        try {
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (dir.equals(root)) return java.nio.file.FileVisitResult.CONTINUE;
                    if (!CfApi.isPlaceholder(dir)) return java.nio.file.FileVisitResult.CONTINUE;
                    String rel = root.relativize(dir).toString()
                            .replace(java.io.File.separatorChar, '/');
                    return syncState.hasDir(rel)
                            ? java.nio.file.FileVisitResult.CONTINUE
                            : java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    try {
                        if (CfApi.isPlaceholder(file)) return java.nio.file.FileVisitResult.CONTINUE;
                        if (attrs.size() == 0) return java.nio.file.FileVisitResult.CONTINUE;
                        String rel = root.relativize(file).toString()
                                .replace(java.io.File.separatorChar, '/');
                        if (syncState.byPath(rel) != null) return java.nio.file.FileVisitResult.CONTINUE;
                        LOG.info("[CF] PULL: uploading offline-added " + rel);
                        uploadLocalFile(file);
                    } catch (Exception e) {
                        LOG.log(Level.FINE, "pull: missed-upload visit failed for " + file, e);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.log(Level.WARNING, "pull: discoverMissedLocalUploads walk failed", e);
        }
    }

    private void applyRemoteChangeOrConflict(String relPath, FileState synced, FileWrapper remote) {
        Path localPath = Path.of(syncRootPath).resolve(relPath.replace('/', java.io.File.separatorChar));
        long localMtime, localSize;
        try {
            if (!Files.exists(localPath)) return;        // not materialised yet; FETCH_DATA will pull on demand
            localMtime = Files.getLastModifiedTime(localPath).toMillis() / 1000 * 1000;
            localSize  = Files.size(localPath);
        } catch (IOException e) { return; }

        if (localMtime == synced.modificationTime && localSize == synced.size) {
            // Local hasn't been touched since last sync — safe to pull.
            try {
                downloadRemoteToLocal(remote, localPath, "/" + relPath);
                recordSyncedVersion(relPath, remote);
                LOG.info("[CF] PULL: " + relPath + " updated from remote");
            } catch (Exception e) {
                LOG.log(Level.WARNING, "pull download failed for " + relPath, e);
            }
        } else {
            // Both diverged. Same conflict flow as the close handler.
            try {
                peergos.shared.user.fs.HashTree localHash = hashLocalFile(localPath, localSize);
                if (localHash != null && localHash.rootHash.equals(
                        remote.getFileProperties().treeHash.map(b -> b.rootHash).orElse(null))) {
                    recordSyncedVersion(relPath, remote);   // already match — just update state
                    return;
                }
                String peergosPath = "/" + relPath;
                String peergosParent = peergosPath.substring(0, peergosPath.lastIndexOf('/'));
                Optional<FileWrapper> parentOpt = context.getByPath(peergosParent).join();
                if (parentOpt.isEmpty()) return;
                resolveLocalConflict(localPath, relPath, localHash, localSize,
                        peergosPath, peergosParent,
                        Optional.of(remote), parentOpt);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "pull conflict resolution failed for " + relPath, e);
            }
        }
    }

    /** Walk the local sync-root tree and create placeholders for any remote children
     *  that have appeared in dirs we've already populated. We descend into a placeholder
     *  dir only if {@code syncState.hasDir} says we've populated it previously — for
     *  un-populated placeholder dirs, walking would trigger FETCH_PLACEHOLDERS, which
     *  we fail same-process, which can leave the dir empty in Explorer's view. CF marks
     *  every successfully-populated dir DISABLE_ON_DEMAND_POPULATION, so without this
     *  walk new remote-side files added after first listing would stay invisible. */
    private void discoverNewRemoteChildren() {
        Path root = Path.of(syncRootPath);
        try {
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (!dir.equals(root) && CfApi.isPlaceholder(dir)) {
                        String relPath = root.relativize(dir).toString()
                                .replace(java.io.File.separatorChar, '/');
                        if (syncState == null || !syncState.hasDir(relPath))
                            return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    try { pullNewChildrenInto(dir); }
                    catch (Exception e) {
                        LOG.log(Level.FINE, "pull: failed to discover new children in " + dir, e);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (java.io.IOException e) {
            LOG.log(Level.WARNING, "pull: discoverNewRemoteChildren walk failed", e);
        }
    }

    /** For one already-enumerated local dir, list the corresponding remote dir and
     *  CfCreatePlaceholders the children that aren't on disk yet. No-op for the sync
     *  root itself (it only ever contains the $username folder, seeded at mount). */
    private void pullNewChildrenInto(Path localDir) {
        if (localDir.equals(Path.of(syncRootPath))) return;
        String peergosDir = localDirToPeergos(localDir);
        if (peergosDir == null) return;
        Optional<FileWrapper> dirOpt = context.getByPath(peergosDir).join();
        if (dirOpt.isEmpty() || !dirOpt.get().isDirectory()) return;
        FileWrapper dir = dirOpt.get();

        // List the dir's child capabilities first — cheap because it's bounded
        // by chunk count, not child count — and filter by name against local
        // placeholders BEFORE fetching any cryptree metadata. The previous
        // shape called dir.getChildren(), which fetched a FileWrapper for
        // every remote child even when all of them were already on disk —
        // dominating the steady-state bandwidth on a 1000-file dir whenever
        // the pull tick decided to iterate.
        java.util.Set<peergos.shared.user.fs.NamedAbsoluteCapability> allCaps =
                dir.getChildrenCapabilities(context.crypto.hasher, context.network).join();
        java.util.Set<peergos.shared.user.fs.NamedAbsoluteCapability> missingCaps = allCaps.stream()
                .filter(c -> !Files.exists(localDir.resolve(c.name.name)))
                .collect(java.util.stream.Collectors.toSet());
        if (missingCaps.isEmpty()) return;

        java.util.Set<FileWrapper> fetched = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        dir.getChildrenFromCaps(missingCaps, fetched::addAll,
                context.crypto.hasher, context.network).join();
        List<FileWrapper> newChildren = fetched.stream()
                .filter(f -> !f.getFileProperties().isHidden)
                .toList();
        if (newChildren.isEmpty()) return;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment baseDirW = CfApi.wideString(localDir.toString(), arena);
            MemorySegment array    = buildPlaceholderArray(newChildren, peergosDir, arena);
            MemorySegment processed = arena.allocate(ValueLayout.JAVA_INT);
            int hr = CfApi.cfCreatePlaceholders(baseDirW, array, newChildren.size(),
                    CfApi.CF_CREATE_FLAG_NONE, processed);
            int entriesProcessed = processed.get(ValueLayout.JAVA_INT, 0);
            LOG.info("[CF] pull: CfCreatePlaceholders dir=" + localDir
                    + " new=" + newChildren.size() + " entriesProcessed=" + entriesProcessed
                    + " hr=0x" + Integer.toHexString(hr));
        }

        // Record a "last synced version" for each new file placeholder so subsequent
        // pull ticks (Tier 2 hash diff) and close-completion conflict detection have a
        // baseline. Skip directories — they're recorded when files inside them are.
        String dirRelPath = peergosPathToRelPath(peergosDir);
        if (dirRelPath == null) return;
        for (FileWrapper fw : newChildren) {
            if (fw.isDirectory()) continue;
            String relPath = dirRelPath.isEmpty()
                    ? fw.getName()
                    : dirRelPath + "/" + fw.getName();
            recordSyncedVersion(relPath, fw);
        }
    }

    /** Convert a local directory under the sync root into its peergos path. Returns
     *  null for the sync root itself (no peergos equivalent — there's no "/" dir to
     *  enumerate; the synthetic root just exposes /$username). */
    private String localDirToPeergos(Path localDir) {
        Path root = Path.of(syncRootPath);
        if (localDir.equals(root)) return null;
        try {
            String rel = root.relativize(localDir).toString()
                    .replace(java.io.File.separatorChar, '/');
            return "/" + rel;
        } catch (Exception e) { return null; }
    }

    private void downloadRemoteToLocal(FileWrapper remote, Path localPath, String peergosPath) throws IOException {
        long remoteSize = remote.getSize();
        // Persist a download CopyOp so the next mount session can re-drive an
        // interrupted download. isLocalTarget=true marks this as a download; source
        // holds the peergos path (as a Path string container), target holds the local.
        // States are null — re-drive just calls downloadRemoteToLocal again with the
        // current remote, no need to capture the remote hash here.
        CopyOp pending = new CopyOp(true,
                java.nio.file.Paths.get(peergosPath), localPath,
                null, null, 0L, remoteSize,
                ResumeUploadProps.random(context.crypto));
        if (syncState != null) syncState.startCopies(java.util.List.of(pending));
        // Two layers of truncation guard for onFileCloseCompletion:
        //   downloadsInFlight  — covers the in-flight window while we're writing.
        //   recentlyUploaded   — covers the (short) window between our finally
        //                        clearing the set and CF actually delivering the
        //                        CLOSE_COMPLETION for the OutputStream close.
        // Without these, a partial write's close → CLOSE_COMPLETION sees
        // Files.size < peergosSize → routes into overwriteChangedChunks → truncates
        // the remote to the partial local bytes.
        downloadsInFlight.add(localPath);
        recentlyUploaded.put(normPathKey(localPath), Boolean.TRUE);
        boolean success = false;
        try {
            try (AsyncReader src = remote.getInputStream(context.network, context.crypto,
                                                        remoteSize, l -> {}).join();
                 java.io.OutputStream out = Files.newOutputStream(localPath)) {
                byte[] buf = new byte[CHUNK_SIZE];
                long remaining = remoteSize;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buf.length, remaining);
                    int n = src.readIntoArray(buf, 0, toRead).join();
                    if (n <= 0) break;
                    out.write(buf, 0, n);
                    remaining -= n;
                }
            } catch (Exception e) {
                if (e instanceof IOException) throw (IOException) e;
                throw new IOException(e);
            }
            success = true;
            // Mirror peergos's mtime onto the local file so the placeholder's on-disk
            // mtime matches syncState.modificationTime — keeps the "no local edits"
            // equality in applyRemoteDelete / applyRemoteChangeOrConflict honest after
            // a remote-driven content pull.
            LocalDateTime remoteModified = remote.getFileProperties().modified;
            if (remoteModified != null) {
                try {
                    Files.setLastModifiedTime(localPath, java.nio.file.attribute.FileTime.fromMillis(
                            remoteModified.toInstant(java.time.ZoneOffset.UTC).toEpochMilli()));
                } catch (Exception e) {
                    LOG.log(Level.FINE, "downloadRemoteToLocal: setLastModifiedTime failed for " + localPath, e);
                }
            }
        } finally {
            downloadsInFlight.remove(localPath);
            if (success && syncState != null)
                syncState.finishCopies(java.util.List.of(pending));
        }
    }

    /** Stream-hash a local file using a FileAsyncReader (no full-file load). */
    private peergos.shared.user.fs.HashTree hashLocalFile(Path localPath, long size) {
        try (peergos.server.simulation.FileAsyncReader reader =
                     new peergos.server.simulation.FileAsyncReader(localPath.toFile())) {
            return peergos.shared.user.fs.HashTree.build(
                    reader, (int) (size >>> 32), (int) size, context.crypto.hasher).join();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Local hash failed for " + localPath, e);
            return null;
        }
    }

    /**
     * Upload a file that was created locally inside the sync root by some external process.
     * CF doesn't fire FILE_CLOSE_COMPLETION for brand-new local files (only for placeholders
     * it already manages), so a separate watcher invokes this when it detects a new file.
     */
    public void uploadLocalFile(Path localPath) {
        // Single-flight guard: if another caller is already uploading this exact path,
        // bail out. This blocks the watcher's uploadExec and the pull tick's
        // discoverMissedLocalUploads from issuing parallel uploads for the same big
        // file (the watcher's own inflight map only covers its own thread pool —
        // it doesn't see calls from elsewhere in the provider).
        if (!uploadsInFlight.add(localPath)) {
            LOG.info("[CF] uploadLocalFile: already in flight, skipping " + localPath);
            return;
        }
        try {
            if (!Files.exists(localPath)) return;

            String name        = localPath.getFileName().toString();
            String relative    = Path.of(syncRootPath).relativize(localPath).toString()
                    .replace(java.io.File.separatorChar, '/');
            // The sync root itself is read-only: it only ever contains the $username
            // folder (you can't create new users from the file system, or rename your
            // user). Anything dropped here by the user is rejected.
            if (!relative.contains("/")) {
                LOG.info("[CF] uploadLocalFile: rejecting sync-root-level entry "
                        + localPath + " — the mount root is read-only");
                return;
            }
            String peergosPath = "/" + relative;
            String peergosParent = peergosPath.substring(0, peergosPath.lastIndexOf('/'));

            if (Files.isDirectory(localPath)) {
                createPeergosDirectory(name, peergosPath, peergosParent);
                // Convert the local dir to a CF placeholder so CF tracks subsequent
                // rename/delete and fires NOTIFY_RENAME / NOTIFY_DELETE (handled by our
                // existing callbacks). Without this, renaming a just-mkdir'd folder (the
                // standard Explorer "New Folder" → type-name flow) leaves both the original
                // "New folder" and the renamed entry in Peergos, because the local dir
                // is just an ordinary directory that CF doesn't intercept.
                if (Files.exists(localPath))
                    convertToPlaceholder(localPath, peergosPath, CfApi.CF_CONVERT_FLAG_MARK_IN_SYNC);
                // Race-window guard: the local dir wasn't a CF placeholder during the
                // peergos mkdir above, so a rename/delete that arrived mid-upload slipped
                // past CF (no NOTIFY_RENAME / NOTIFY_DELETE). If the local vanished, the
                // peergos dir we just created is orphaned — roll it back. The watcher's
                // CREATE event for any renamed target will reupload under the new name.
                if (!Files.exists(localPath))
                    rollbackEmptyPeergosDir(peergosPath);
                else if (syncState != null)
                    // Remember this dir is populated locally so the next mount's watcher
                    // re-registers it (see CloudFilesMount.runWatcher's getKnownDirs loop).
                    syncState.addDir(relative);
                return;
            }
            long localSize = Files.size(localPath);
            if (localSize == 0) return;

            // Fast-path: if syncState already records this file with matching size+mtime,
            // skip the network round-trip. CF fires NOTIFY_MODIFY on placeholders for its
            // own bookkeeping (in-sync flag transitions, attribute updates) even when
            // content is unchanged — without this guard each spurious event walks the
            // cryptree via context.getByPath, which compounds badly when 1000s of files
            // share a parent dir.
            if (syncState != null) {
                long localMtime = Files.getLastModifiedTime(localPath).toMillis() / 1000 * 1000;
                FileState synced = syncState.byPath(relative);
                if (synced != null && synced.size == localSize
                        && synced.modificationTime == localMtime)
                    return;
            }

            Optional<FileWrapper> existing = context.getByPath(peergosPath).join();
            if (existing.isPresent() && !existing.get().isDirectory()
                    && existing.get().getSize() == localSize)
                return; // already in sync
            if (existing.isPresent() && !existing.get().isWritable()) {
                LOG.info("[CF] uploadLocalFile: " + peergosPath
                        + " is read-only in peergos, skipping");
                return;
            }

            Optional<FileWrapper> parentOpt = context.getByPath(peergosParent).join();
            if (parentOpt.isEmpty()) {
                LOG.info("[CF] uploadLocalFile: parent missing: " + peergosParent);
                return;
            }
            if (!parentOpt.get().isWritable()) {
                LOG.info("[CF] uploadLocalFile: parent " + peergosParent
                        + " is read-only, skipping upload of " + peergosPath);
                return;
            }

            LOG.info("[CF] uploadLocalFile: uploading " + localSize
                    + " bytes to " + peergosPath);

            // Convert the file to a CF placeholder BEFORE uploading. We use
            // CF_CONVERT_FLAG_MARK_IN_SYNC (matches the original post-upload behaviour
            // that's known to render the green check), then toggle the in-sync bit via
            // CfSetInSyncState to surface "syncing" during the upload. Converting first
            // also means FETCH_DATA / NOTIFY_RENAME / NOTIFY_DELETE route correctly if
            // anything touches the file mid-upload. Empirically, converting WITHOUT
            // MARK_IN_SYNC leaves hydrated placeholders with no overlay at all in
            // Explorer (no active transfer to drive the syncing icon), so we drive it
            // explicitly with setInSyncState.
            int preConvertHr = convertToPlaceholder(localPath, peergosPath, CfApi.CF_CONVERT_FLAG_MARK_IN_SYNC);
            boolean isPlaceholder = (preConvertHr == CfApi.S_OK);
            if (isPlaceholder) {
                // Best-effort: flip to NOT_IN_SYNC for the upload window. If this call
                // fails, the file just stays IN_SYNC (no syncing overlay, but still has
                // the green check) — strictly better than landing in an invisible state.
                setInSyncState(localPath, CfApi.CF_IN_SYNC_STATE_NOT_IN_SYNC);
            }

            FileWrapper uploaded;
            HashTree localHash = hashLocalFile(localPath, localSize);
            // Persist a CopyOp BEFORE we touch the network. If the mount is closed (or
            // the JVM dies) mid-upload, the entry stays in syncState's in-progress copy
            // table and CloudFilesMount.mount picks it up next start via
            // rerunPendingUploads(). finishCopies below removes it on completion. We
            // only need source/target paths to re-drive — the re-drive just calls
            // uploadLocalFile again with current local bytes — so targetState is null
            // and we don't bother snapshotting the remote treeHash.
            long localMtime = Files.getLastModifiedTime(localPath).toMillis() / 1000 * 1000;
            LocalDateTime localModified = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(localMtime),
                    java.time.ZoneOffset.UTC);
            FileState sourceSt = localHash == null ? null
                    : new FileState(relative, localMtime, localSize, localHash);
            CopyOp pending = sourceSt == null ? null : new CopyOp(false, localPath,
                    java.nio.file.Paths.get(peergosPath),
                    sourceSt, null, 0L, localSize,
                    ResumeUploadProps.random(context.crypto));
            if (pending != null && syncState != null)
                syncState.startCopies(java.util.List.of(pending));
            try (peergos.server.simulation.FileAsyncReader reader =
                         new peergos.server.simulation.FileAsyncReader(localPath.toFile())) {
                if (existing.isPresent() && !existing.get().isDirectory()) {
                    uploaded = existing.get().overwriteChangedChunks(
                            reader, localSize,
                            context.network, context.crypto, l -> {}).join();
                    if (localHash != null) applyHash(peergosPath, localHash);
                    uploaded = stampPeergosMtime(peergosPath, localModified).orElse(uploaded);
                } else {
                    uploaded = parentOpt.get().uploadFileWithHash(name,
                            reader, localSize, Optional.ofNullable(localHash),
                            Optional.of(localModified), Optional.empty(),
                            context.network, context.crypto, l -> {}).join();
                }
            }
            if (pending != null && syncState != null)
                syncState.finishCopies(java.util.List.of(pending));
            LOG.info("[CF] uploadLocalFile: upload complete for " + peergosPath);
            // Record the new "last synced version" for conflict detection.
            context.getByPath(peergosPath).join().ifPresent(
                    fw -> recordSyncedVersion(localPath, fw));

            if (isPlaceholder) {
                // Pre-convert succeeded → re-mark IN_SYNC so the green check is restored.
                setInSyncState(localPath, CfApi.CF_IN_SYNC_STATE_IN_SYNC);
            } else {
                // Pre-convert failed (file held open elsewhere, already a placeholder,
                // etc). Fall back to the original post-upload convert-with-mark so the
                // file still ends up as an in-sync placeholder.
                convertToPlaceholder(localPath, uploaded, peergosPath);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "uploadLocalFile failed for " + localPath, e);
        } finally {
            uploadsInFlight.remove(localPath);
        }
    }

    /** Bulk version of {@link #uploadLocalFile}. Groups {@code localPaths} by parent
     *  directory and uploads each group via a single {@link FileWrapper#uploadSubtree}
     *  call — collapsing what would have been N serial {@code applyComplexUpdate}
     *  transactions on the parent writer into one. Directories and files in different
     *  parents fall through to the individual {@code uploadLocalFile} path.
     *
     *  Caps each batch at {@value #BULK_BATCH_FILE_CAP} files or
     *  {@value #BULK_BATCH_BYTE_CAP} bytes so a giant burst doesn't hold the writer
     *  lock for too long, blocking other operations on the same writer. */
    private static final int BULK_BATCH_FILE_CAP = 1000;
    private static final long BULK_BATCH_BYTE_CAP = 40L * 1024 * 1024;

    public void uploadLocalFiles(java.util.List<Path> localPaths) {
        if (localPaths.isEmpty()) return;
        java.util.Map<Path, java.util.List<Path>> filesByParent = new java.util.LinkedHashMap<>();
        java.util.List<Path> singles = new java.util.ArrayList<>();
        for (Path p : localPaths) {
            try {
                if (!Files.exists(p) || Files.isDirectory(p)) { singles.add(p); continue; }
            } catch (Exception e) { singles.add(p); continue; }
            filesByParent.computeIfAbsent(p.getParent(), k -> new java.util.ArrayList<>()).add(p);
        }
        // Directories (and any path we couldn't stat) go through the single-file path —
        // mkdir is fast and uploadLocalFile already handles the placeholder/rollback
        // dance for it.
        for (Path p : singles) uploadLocalFile(p);
        for (java.util.Map.Entry<Path, java.util.List<Path>> e : filesByParent.entrySet()) {
            java.util.List<Path> group = e.getValue();
            if (group.size() == 1) { uploadLocalFile(group.get(0)); continue; }
            bulkUploadSiblingFiles(e.getKey(), group);
        }
    }

    private void bulkUploadSiblingFiles(Path localParentDir, java.util.List<Path> localFiles) {
        Path syncRoot = Path.of(syncRootPath);
        String relParent = syncRoot.relativize(localParentDir).toString()
                .replace(java.io.File.separatorChar, '/');
        // Sync-root-level (no peergos parent on the inside): fall back per-file. The
        // single-file path rejects it with a clear log.
        if (relParent.isEmpty() || !relParent.contains("/")) {
            for (Path p : localFiles) uploadLocalFile(p);
            return;
        }
        String peergosParent = "/" + relParent;

        // Claim every path in-flight up front. If something else (the pull tick's
        // discoverMissedLocalUploads, say) is already uploading one, skip just that
        // one and bulk-upload the rest.
        java.util.List<Path> claimed = new java.util.ArrayList<>();
        for (Path p : localFiles) {
            if (uploadsInFlight.add(p)) claimed.add(p);
        }
        if (claimed.isEmpty()) return;

        try {
            Optional<FileWrapper> parentOpt = context.getByPath(peergosParent).join();
            if (parentOpt.isEmpty()) {
                LOG.info("[CF] bulkUpload: parent missing: " + peergosParent);
                return;
            }
            if (!parentOpt.get().isWritable()) {
                LOG.info("[CF] bulkUpload: parent " + peergosParent
                        + " is read-only, skipping " + claimed.size() + " files");
                return;
            }
            FileWrapper parent = parentOpt.get();

            // Per-file pre-processing: size + mtime + hash. Skip files the syncState
            // already says are current (same fast-path as uploadLocalFile uses for
            // spurious CF MODIFY events) and skip empty/missing/dir entries.
            java.util.List<Path> toUpload = new java.util.ArrayList<>();
            java.util.Map<Path, Long> sizes = new java.util.HashMap<>();
            java.util.Map<Path, LocalDateTime> mtimes = new java.util.HashMap<>();
            java.util.Map<Path, HashTree> hashes = new java.util.HashMap<>();
            for (Path p : claimed) {
                if (!Files.exists(p) || Files.isDirectory(p)) continue;
                long size;
                long mtimeMs;
                try {
                    size = Files.size(p);
                    mtimeMs = Files.getLastModifiedTime(p).toMillis() / 1000 * 1000;
                } catch (IOException ex) { continue; }
                if (size == 0) continue;
                String relPath = syncRoot.relativize(p).toString()
                        .replace(java.io.File.separatorChar, '/');
                if (syncState != null) {
                    FileState synced = syncState.byPath(relPath);
                    if (synced != null && synced.size == size && synced.modificationTime == mtimeMs)
                        continue;
                }
                sizes.put(p, size);
                mtimes.put(p, LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(mtimeMs), java.time.ZoneOffset.UTC));
                HashTree h = hashLocalFile(p, size);
                if (h != null) hashes.put(p, h);
                toUpload.add(p);
            }
            if (toUpload.isEmpty()) return;

            // Pre-convert each to a placeholder so CF tracks subsequent rename/delete
            // mid-upload, and flip to NOT_IN_SYNC so File Explorer shows the syncing
            // overlay. Failures (file held open, already a placeholder) get caught
            // up by the post-upload convert fallback below.
            java.util.Map<Path, Boolean> wasConverted = new java.util.HashMap<>();
            for (Path p : toUpload) {
                String peergosPath = peergosParent + "/" + p.getFileName().toString();
                int hr = convertToPlaceholder(p, peergosPath, CfApi.CF_CONVERT_FLAG_MARK_IN_SYNC);
                boolean ok = (hr == CfApi.S_OK);
                wasConverted.put(p, ok);
                if (ok) setInSyncState(p, CfApi.CF_IN_SYNC_STATE_NOT_IN_SYNC);
            }

            // Split into batches by file count and total byte cap.
            java.util.List<java.util.List<Path>> batches = new java.util.ArrayList<>();
            java.util.List<Path> currentBatch = new java.util.ArrayList<>();
            long currentBytes = 0;
            for (Path p : toUpload) {
                long sz = sizes.get(p);
                if (!currentBatch.isEmpty() && (currentBatch.size() >= BULK_BATCH_FILE_CAP
                        || currentBytes + sz > BULK_BATCH_BYTE_CAP)) {
                    batches.add(currentBatch);
                    currentBatch = new java.util.ArrayList<>();
                    currentBytes = 0;
                }
                currentBatch.add(p);
                currentBytes += sz;
            }
            if (!currentBatch.isEmpty()) batches.add(currentBatch);

            // One uploadSubtree call per batch — one applyComplexUpdate cycle per batch,
            // which is the whole point of this method.
            for (java.util.List<Path> batch : batches) {
                java.util.List<FileWrapper.FileUploadProperties> fileProps = new java.util.ArrayList<>();
                for (Path p : batch) {
                    final Path fp = p;
                    long size = sizes.get(p);
                    fileProps.add(new FileWrapper.FileUploadProperties(
                            p.getFileName().toString(),
                            () -> {
                                try { return new peergos.server.simulation.FileAsyncReader(fp.toFile()); }
                                catch (Exception ex) { throw new RuntimeException(ex); }
                            },
                            (int)(size >>> 32), (int) size,
                            Optional.of(mtimes.get(p)),
                            Optional.ofNullable(hashes.get(p)),
                            false, true, x -> {}));
                }
                FileWrapper.FolderUploadProperties folder = new FileWrapper.FolderUploadProperties(
                        java.util.Collections.emptyList(), fileProps);
                LOG.info("[CF] bulkUpload: " + batch.size() + " files (" + currentBytes
                        + " bytes) to " + peergosParent);
                try {
                    parent.uploadSubtree(
                            java.util.stream.Stream.of(folder),
                            Optional.empty(),
                            context.network, context.crypto,
                            context.getTransactionService(),
                            f -> peergos.shared.util.Futures.of(false),
                            f -> peergos.shared.util.Futures.of(true),
                            () -> true).join();
                } catch (Exception ex) {
                    LOG.log(Level.WARNING, "bulkUpload batch failed for " + peergosParent, ex);
                    continue;
                }
                Optional<FileWrapper> refreshed = context.getByPath(peergosParent).join();
                if (refreshed.isPresent()) parent = refreshed.get();
            }

            // Post-process: fetch all uploaded FWs in one batched call (cheap — same
            // mechanism the pull-tick optimisation uses), record synced versions,
            // flip CF in-sync state.
            java.util.Set<String> filenames = toUpload.stream()
                    .map(p -> p.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet());
            java.util.Set<FileWrapper> fws = parent.getChildren(filenames,
                    context.crypto.hasher, context.network, true).join();
            java.util.Map<String, FileWrapper> byName = new java.util.HashMap<>();
            for (FileWrapper fw : fws) byName.put(fw.getName(), fw);
            for (Path p : toUpload) {
                FileWrapper fw = byName.get(p.getFileName().toString());
                if (fw == null) continue;
                recordSyncedVersion(p, fw);
                if (wasConverted.getOrDefault(p, false)) {
                    setInSyncState(p, CfApi.CF_IN_SYNC_STATE_IN_SYNC);
                } else {
                    String peergosPath = peergosParent + "/" + p.getFileName().toString();
                    convertToPlaceholder(p, peergosPath, CfApi.CF_CONVERT_FLAG_MARK_IN_SYNC);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "bulkUpload failed for " + localParentDir, e);
        } finally {
            for (Path p : claimed) uploadsInFlight.remove(p);
        }
    }

    private void createPeergosDirectory(String name, String peergosPath, String peergosParent) {
        Optional<FileWrapper> existing = context.getByPath(peergosPath).join();
        if (existing.isPresent()) return;
        Optional<FileWrapper> parentOpt = context.getByPath(peergosParent).join();
        if (parentOpt.isEmpty()) {
            LOG.info("[CF] mkdir: parent missing: " + peergosParent);
            return;
        }
        if (!parentOpt.get().isWritable()) {
            LOG.info("[CF] mkdir: parent " + peergosParent
                    + " is read-only, skipping creation of " + peergosPath);
            return;
        }
        try {
            parentOpt.get().mkdir(name, context.network, false, Optional.empty(), context.crypto).join();
            LOG.info("[CF] mkdir: created " + peergosPath);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "mkdir failed for " + peergosPath, e);
        }
    }

    /** Undo a {@link #createPeergosDirectory} when the local dir disappeared before we could
     *  convert it to a placeholder (rename/delete during the mkdir network call). Only
     *  removes empty dirs: if a concurrent upload landed a child here we'd rather leave a
     *  stranded entry visible than silently lose data. */
    private void rollbackEmptyPeergosDir(String peergosPath) {
        try {
            Optional<FileWrapper> fwOpt = context.getByPath(peergosPath).join();
            if (fwOpt.isEmpty()) return;
            Set<FileWrapper> children = fwOpt.get()
                    .getChildren(context.crypto.hasher, context.network).join();
            if (!children.isEmpty()) {
                LOG.info("[CF] rollback skipped for " + peergosPath
                        + " — has " + children.size() + " children");
                return;
            }
            String parent = peergosPath.substring(0, peergosPath.lastIndexOf('/'));
            Optional<FileWrapper> parentOpt = context.getByPath(parent).join();
            if (parentOpt.isEmpty()) return;
            fwOpt.get().remove(parentOpt.get(), PathUtil.get(peergosPath), context).join();
            LOG.info("[CF] rollback: removed orphaned " + peergosPath
                    + " (local vanished during mkdir)");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "rollback failed for " + peergosPath, e);
        }
    }

    /** Convert a local file or directory into a CF placeholder.
     *  Returns the HRESULT from CfConvertToPlaceholder (S_OK on success), or -1 if we
     *  couldn't open a handle / hit an exception. The convert opens the file with
     *  dwDesiredAccess=0 (query only) and closes it before returning. In practice CF
     *  does NOT fire FILE_CLOSE_COMPLETION for that handle (no FILE_*_DATA access was
     *  requested), so no pre-mark in recentlyUploaded is needed. */
    private int convertToPlaceholder(Path localPath, String peergosPath, int convertFlags) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathW = CfApi.wideString(localPath.toString(), arena);
            boolean isDir = Files.isDirectory(localPath);
            MemorySegment handle = isDir
                    ? CfApi.createDirForConvert(pathW)
                    : CfApi.createFileForConvert(pathW);
            if (handle.address() == CfApi.INVALID_HANDLE_VALUE) {
                LOG.info("[CF] convertToPlaceholder: CreateFile failed for " + localPath);
                return -1;
            }
            try {
                // Identity must match the scheme used in buildPlaceholderArray (8-byte hash
                // of the Peergos path) so that later FETCH_DATA callbacks can look the file
                // up in identityToPath.
                long key = identityKey(peergosPath);
                identityToPath.put(key, peergosPath);
                MemorySegment idSeg = arena.allocate(8);
                idSeg.set(ValueLayout.JAVA_LONG, 0, key);
                int hr = CfApi.cfConvertToPlaceholder(handle, idSeg, 8, convertFlags);
                LOG.info("[CF] convertToPlaceholder hr=0x"
                        + Integer.toHexString(hr) + " flags=0x" + Integer.toHexString(convertFlags)
                        + " path=" + localPath);
                return hr;
            } finally {
                CfApi.closeHandle(handle);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "convertToPlaceholder failed for " + localPath, e);
            return -1;
        }
    }

    /** Backwards-compatible signature — convert + mark-in-sync in one call. Used as the
     *  fallback path when pre-upload conversion failed. */
    private void convertToPlaceholder(Path localPath, FileWrapper uploaded, String peergosPath) {
        convertToPlaceholder(localPath, peergosPath, CfApi.CF_CONVERT_FLAG_MARK_IN_SYNC);
    }

    /** Set an existing placeholder's in-sync state. {@code inSyncState} must be one of
     *  {@link CfApi#CF_IN_SYNC_STATE_IN_SYNC} or {@link CfApi#CF_IN_SYNC_STATE_NOT_IN_SYNC}.
     *  Drives the File Explorer overlay: IN_SYNC = green check, NOT_IN_SYNC = "syncing"
     *  cloud icon. CfSetInSyncState requires WRITE_DAC on the handle, which
     *  createFileForHydration provides without triggering hydration. */
    private void setInSyncState(Path localPath, int inSyncState) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathW = CfApi.wideString(localPath.toString(), arena);
            MemorySegment handle = CfApi.createFileForHydration(pathW);
            if (handle.address() == CfApi.INVALID_HANDLE_VALUE) {
                LOG.info("[CF] setInSyncState: CreateFile failed for " + localPath);
                return;
            }
            try {
                int hr = CfApi.cfSetInSyncState(handle, inSyncState, CfApi.CF_SET_IN_SYNC_FLAG_NONE);
                LOG.info("[CF] setInSyncState=" + inSyncState + " hr=0x"
                        + Integer.toHexString(hr) + " path=" + localPath);
            } finally {
                CfApi.closeHandle(handle);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "setInSyncState failed for " + localPath, e);
        }
    }

    // -----------------------------------------------------------------------
    // DELETE_PLACEHOLDER — ack then delete from Peergos
    // -----------------------------------------------------------------------

    public void onDeletePlaceholder(MemorySegment info, MemorySegment params) {
        LOG.info("[CF] NOTIFY_DELETE entered");
        info = info.reinterpret(256);
        params = params.reinterpret(256);
        long connectionKey, transferKey, requestKey;
        String normalizedPath;
        try {
            connectionKey = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_CONNECTION_KEY_OFF);
            transferKey   = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_TRANSFER_KEY_OFF);
            requestKey    = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_REQUEST_KEY_OFF);
            normalizedPath = CfApi.readWideString(info, CfApi.CBI_NORMALIZED_PATH_OFF);
        } catch (Exception e) { LOG.log(Level.WARNING, "DELETE: failed to read params", e); return; }
        String peergosPath    = normalizedToPeergos(normalizedPath);

        // Ack first to let Windows proceed with the local delete
        try (Arena arena = Arena.ofConfined()) {
            ack(arena, connectionKey, transferKey, requestKey,
                CfApi.CF_OPERATION_TYPE_ACK_DELETE, CfApi.STATUS_SUCCESS);
        }

        // Sync-root-level entries (just the $username dir) must never be deleted in
        // peergos from a CF event — surface it here so we don't enqueue something we'd
        // refuse to act on later.
        int slash = peergosPath.lastIndexOf('/');
        if (slash <= 0) {
            LOG.info("[CF] NOTIFY_DELETE: refusing to delete sync-root-level entry "
                    + peergosPath + " from peergos");
            return;
        }
        // Defer the peergos remove to the batched drain. Acked NOTIFY_DELETEs queue up
        // by parent and a small scheduler issues one FileWrapper.deleteChildren call
        // per parent, plus collapses subtree-wipes to their root (an ancestor's
        // deleteChildren cleans up all descendants in one shot). The CF ack above
        // has already let Windows complete the local delete — Peergos catches up on
        // the next drain tick.
        enqueueDelete(peergosPath);
    }

    private void enqueueDelete(String peergosPath) {
        int slash = peergosPath.lastIndexOf('/');
        if (slash <= 0) return;
        String parent = peergosPath.substring(0, slash);
        String filename = peergosPath.substring(slash + 1);
        synchronized (pendingDeletes) {
            pendingDeletes.computeIfAbsent(parent, k -> new java.util.HashSet<>()).add(filename);
        }
    }

    /** Drain {@link #pendingDeletes}: ancestor-collapse the pending paths so a
     *  subtree wipe issues just the ancestor's removal, then issue one
     *  {@link FileWrapper#deleteChildren} call per surviving parent. Called on a
     *  small fixed-rate tick from {@code CloudFilesMount} so a burst of
     *  NOTIFY_DELETEs from Explorer coalesces into a couple of writer-lock
     *  acquisitions instead of one per file. */
    void drainDeletes() {
        java.util.Map<String, java.util.Set<String>> snapshot;
        synchronized (pendingDeletes) {
            if (pendingDeletes.isEmpty()) return;
            snapshot = new java.util.HashMap<>(pendingDeletes);
            pendingDeletes.clear();
        }

        // Ancestor collapse: walk shortest path first, keep ones that aren't
        // strict descendants of an already-kept one. peergos drops the whole
        // subtree when a directory is removed, so a child delete pending under
        // an ancestor in the same batch is redundant work.
        java.util.List<String> allPaths = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, java.util.Set<String>> e : snapshot.entrySet()) {
            for (String name : e.getValue())
                allPaths.add(e.getKey() + "/" + name);
        }
        allPaths.sort(java.util.Comparator.comparingInt(String::length));
        java.util.List<String> survivors = new java.util.ArrayList<>();
        for (String p : allPaths) {
            boolean covered = false;
            for (String kept : survivors) {
                if (p.startsWith(kept + "/")) { covered = true; break; }
            }
            if (!covered) survivors.add(p);
        }

        // Re-group survivors by parent — some may have moved into an ancestor's
        // parent because the ancestor was itself collapsed.
        java.util.Map<String, java.util.Set<String>> byParent = new java.util.HashMap<>();
        for (String full : survivors) {
            int s = full.lastIndexOf('/');
            byParent.computeIfAbsent(full.substring(0, s), k -> new java.util.HashSet<>())
                    .add(full.substring(s + 1));
        }
        for (java.util.Map.Entry<String, java.util.Set<String>> e : byParent.entrySet()) {
            deleteChildrenInPeergos(e.getKey(), e.getValue());
        }
    }

    private void deleteChildrenInPeergos(String peergosParent, java.util.Set<String> filenames) {
        if (filenames.isEmpty()) return;
        try {
            Optional<FileWrapper> parentOpt = context.getByPath(peergosParent).join();
            if (parentOpt.isEmpty()) return;
            if (!parentOpt.get().isWritable()) {
                LOG.info("[CF] bulkDelete: parent " + peergosParent
                        + " is read-only, skipping " + filenames.size() + " entries");
                return;
            }
            FileWrapper parent = parentOpt.get();
            java.util.Set<FileWrapper> fws = parent.getChildren(filenames,
                    context.crypto.hasher, context.network, true).join();
            if (fws.isEmpty()) return;
            LOG.info("[CF] bulkDelete: removing " + fws.size() + " entries from " + peergosParent);
            FileWrapper.deleteChildren(parent, fws,
                    PathUtil.get(peergosParent), context).join();
            for (FileWrapper fw : fws) {
                String peergosPath = peergosParent + "/" + fw.getName();
                identityToPath.values().removeIf(p ->
                        p.equals(peergosPath) || p.startsWith(peergosPath + "/"));
                String relPath = peergosPathToRelPath(peergosPath);
                if (relPath != null) {
                    final String fp = relPath;
                    asyncSyncState(() -> syncState.remove(fp));
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "bulkDelete failed for " + peergosParent, e);
        }
    }

    // -----------------------------------------------------------------------
    // RENAME_PLACEHOLDER — ack source; RENAME_COMPLETION — do the rename
    // -----------------------------------------------------------------------

    public void onRenamePlaceholder(MemorySegment info, MemorySegment params) {
        LOG.info("[CF] NOTIFY_RENAME entered");
        info = info.reinterpret(256);
        params = params.reinterpret(256);
        long connectionKey, transferKey, requestKey;
        String targetPath;
        try {
            connectionKey = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_CONNECTION_KEY_OFF);
            transferKey   = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_TRANSFER_KEY_OFF);
            requestKey    = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_REQUEST_KEY_OFF);
            targetPath    = CfApi.readWideString(params, CfApi.CBP_RENAME_TARGET_PATH_OFF);
        } catch (Exception e) { LOG.log(Level.WARNING, "RENAME: failed to read params", e); return; }

        // Pre-suppress the FILE_CLOSE_COMPLETION that the OS will fire on the rename target.
        // NOTIFY_RENAME fires BEFORE the actual rename happens, so the target's close-completion
        // can't have raced yet. By contrast, populating recentlyUploaded in NOTIFY_RENAME_COMPLETION
        // is too late: the target's close-completion runs on a different thread and may complete
        // an upload before our completion handler reaches the put().
        try {
            String drive  = syncRootPath.substring(0, 2);
            Path localTarget = Path.of(drive + targetPath);
            recentlyUploaded.put(normPathKey(localTarget), Boolean.TRUE);
            LOG.info("[CF] NOTIFY_RENAME pre-marked target=" + localTarget);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "RENAME: failed to pre-mark target", e);
        }

        LOG.info("[CF] NOTIFY_RENAME connKey=" + connectionKey
                + " xferKey=" + transferKey + " reqKey=" + requestKey);
        try (Arena arena = Arena.ofConfined()) {
            ack(arena, connectionKey, transferKey, requestKey,
                CfApi.CF_OPERATION_TYPE_ACK_RENAME, CfApi.STATUS_SUCCESS);
        }
        LOG.info("[CF] NOTIFY_RENAME ack sent");
    }

    public void onRenameCompletionPlaceholder(MemorySegment info, MemorySegment params) {
        LOG.info("[CF] NOTIFY_RENAME_COMPLETION entered");
        info   = info.reinterpret(256);
        params = params.reinterpret(256);
        String sourcePath, targetPath;
        try {
            sourcePath = CfApi.readWideString(params, CfApi.CBP_RENAME_COMPLETION_SOURCE_PATH_OFF);
            targetPath = CfApi.readWideString(info,   CfApi.CBI_NORMALIZED_PATH_OFF);
        } catch (Exception e) { LOG.log(Level.WARNING, "RENAME_COMPLETION: failed to read params", e); return; }
        String peergosSource = normalizedToPeergos(sourcePath);
        String peergosTarget = normalizedToPeergos(targetPath);

        LOG.info("[CF] NOTIFY_RENAME_COMPLETION src=" + peergosSource
                + " tgt=" + peergosTarget);

        // Suppress the upcoming FILE_CLOSE_COMPLETION on the target — the OS fires it for
        // the rename's "open new name" handle, and without this short-circuit our close
        // handler would treat the renamed file as a brand-new local file and re-upload it.
        recentlyUploaded.put(normPathKey(Path.of(syncRootPath).resolve(
                peergosTarget.substring(1)
                              .replace('/', java.io.File.separatorChar))), Boolean.TRUE);

        try {
            Optional<FileWrapper> fwOpt = context.getByPath(peergosSource).join();
            if (fwOpt.isEmpty()) {
                LOG.info("[CF] NOTIFY_RENAME_COMPLETION: source not found in peergos: "
                        + peergosSource);
                return;
            }
            // Record the local dir rename so the watcher can keep resolving events on the
            // (still-valid) WatchKey to the dir's NEW on-disk path. The peergos rename
            // below can fail (read-only, parent missing, etc.) but the local rename has
            // already happened by the time CF fires this callback — we still need the
            // path-resolution fix to work in either case.
            if (fwOpt.get().isDirectory()) {
                Path oldLocal = Path.of(syncRootPath).resolve(peergosSource.substring(1)
                        .replace('/', java.io.File.separatorChar));
                Path newLocal = Path.of(syncRootPath).resolve(peergosTarget.substring(1)
                        .replace('/', java.io.File.separatorChar));
                recordDirRename(oldLocal, newLocal);
            }
            String sourceParent = peergosSource.substring(0, peergosSource.lastIndexOf('/'));
            String targetParent = peergosTarget.substring(0, peergosTarget.lastIndexOf('/'));
            String targetName   = peergosTarget.substring(peergosTarget.lastIndexOf('/') + 1);
            // Read-only guards: the sync root is read-only (you can't rename your user folder
            // or move things into the sync-root level), and a read-only source can't be moved.
            if (sourceParent.isEmpty() || targetParent.isEmpty()) {
                LOG.info("[CF] NOTIFY_RENAME_COMPLETION: refusing rename involving the "
                        + "read-only sync root (src=" + peergosSource + " tgt=" + peergosTarget + ")");
                return;
            }
            if (!fwOpt.get().isWritable()) {
                LOG.info("[CF] NOTIFY_RENAME_COMPLETION: source " + peergosSource
                        + " is read-only in peergos, skipping rename");
                return;
            }
            Optional<FileWrapper> sourceParentOpt = context.getByPath(sourceParent).join();
            Optional<FileWrapper> targetParentOpt = context.getByPath(targetParent).join();
            if (sourceParentOpt.isEmpty() || targetParentOpt.isEmpty()) {
                LOG.info("[CF] NOTIFY_RENAME_COMPLETION: parents missing src="
                        + sourceParentOpt.isPresent() + " tgt=" + targetParentOpt.isPresent());
                return;
            }
            if (!targetParentOpt.get().isWritable()) {
                LOG.info("[CF] NOTIFY_RENAME_COMPLETION: target parent " + targetParent
                        + " is read-only, skipping rename to " + peergosTarget);
                return;
            }

            if (sourceParent.equals(targetParent)) {
                LOG.info("[CF] NOTIFY_RENAME_COMPLETION: in-place rename to " + targetName);
                fwOpt.get().rename(targetName, targetParentOpt.get(),
                        PathUtil.get(peergosSource), context).join();
            } else {
                LOG.info("[CF] NOTIFY_RENAME_COMPLETION: moveTo " + targetParent);
                fwOpt.get().moveTo(targetParentOpt.get(), sourceParentOpt.get(),
                        PathUtil.get(peergosSource), context, () -> peergos.shared.util.Futures.of(true)).join();
            }
            // Update identity map
            identityToPath.replaceAll((k, v) -> {
                if (v.equals(peergosSource)) return peergosTarget;
                if (v.startsWith(peergosSource + "/"))
                    return peergosTarget + v.substring(peergosSource.length());
                return v;
            });
            // Move the SyncState entry: remove old, add at target path with current props.
            // Both writes are routed off the CF callback thread via asyncSyncState.
            String relSource = peergosPathToRelPath(peergosSource);
            if (relSource != null)
                asyncSyncState(() -> syncState.remove(relSource));
            context.getByPath(peergosTarget).join().ifPresent(fw -> {
                String relTarget = peergosPathToRelPath(peergosTarget);
                if (relTarget != null)
                    asyncSyncState(() -> recordSyncedVersion(relTarget, fw));
            });
            LOG.info("[CF] NOTIFY_RENAME_COMPLETION: done " + peergosSource
                    + " -> " + peergosTarget);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Rename failed " + peergosSource + " -> " + peergosTarget, e);
            LOG.info("[CF] NOTIFY_RENAME_COMPLETION FAILED: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private MemorySegment buildPlaceholderArray(List<FileWrapper> children,
                                                String parentPeergosPath, Arena arena) {
        return buildPlaceholderArray(children, parentPeergosPath, arena, false);
    }

    /** {@code forceReadOnly}: OR FILE_ATTRIBUTE_READONLY into every entry regardless of
     *  the FileWrapper's own writability. Used for the synthetic sync-root listing — the
     *  {@code $username} entry is writable on the inside, but at the sync-root level it
     *  must not be renamed/deleted (you can't rename your peergos user), so we surface
     *  it as read-only. */
    private MemorySegment buildPlaceholderArray(List<FileWrapper> children,
                                                String parentPeergosPath, Arena arena,
                                                boolean forceReadOnly) {
        MemorySegment array = arena.allocate(CfApi.PCI_SIZE * children.size());
        for (int i = 0; i < children.size(); i++) {
            FileWrapper fw = children.get(i);
            FileProperties props = fw.getFileProperties();
            long base = CfApi.PCI_SIZE * i;

            // Relative file name pointer
            MemorySegment nameW = CfApi.wideString(props.name, arena);
            array.set(ValueLayout.JAVA_LONG, base + CfApi.PCI_RELATIVE_FILE_NAME_OFF, nameW.address());
            LOG.info("[CF] buildPlaceholderArray[" + i + "] name='" + props.name
                    + "' nameW=0x" + Long.toHexString(nameW.address())
                    + " array=0x" + Long.toHexString(array.address()));

            // FS metadata — FILE_BASIC_INFO
            long fbiBase = base + CfApi.PCI_FS_METADATA_OFF + CfApi.FSM_BASIC_INFO_OFF;
            long ft = CfApi.toFileTime(props.modified);
            array.set(ValueLayout.JAVA_LONG, fbiBase + CfApi.FBI_CREATION_TIME_OFF,   ft);
            array.set(ValueLayout.JAVA_LONG, fbiBase + CfApi.FBI_LAST_ACCESS_OFF,     ft);
            array.set(ValueLayout.JAVA_LONG, fbiBase + CfApi.FBI_LAST_WRITE_OFF,      ft);
            array.set(ValueLayout.JAVA_LONG, fbiBase + CfApi.FBI_CHANGE_TIME_OFF,     ft);
            int attrs = fw.isDirectory()
                    ? CfApi.FILE_ATTRIBUTE_DIRECTORY
                    : CfApi.FILE_ATTRIBUTE_NORMAL;
            if (forceReadOnly || !fw.isWritable())
                attrs |= CfApi.FILE_ATTRIBUTE_READONLY;
            array.set(ValueLayout.JAVA_INT, fbiBase + CfApi.FBI_FILE_ATTRIBUTES_OFF, attrs);

            // FS metadata — FileSize
            long fileSize = fw.isDirectory() ? 0 : props.size;
            array.set(ValueLayout.JAVA_LONG,
                    base + CfApi.PCI_FS_METADATA_OFF + CfApi.FSM_FILE_SIZE_OFF, fileSize);

            // File identity: store the full Peergos path as UTF-8 bytes (max 4096)
            String fullPath = parentPeergosPath.equals("/")
                    ? "/" + props.name
                    : parentPeergosPath + "/" + props.name;
            long key = identityKey(fullPath);
            identityToPath.put(key, fullPath);
            byte[] idBytes = java.nio.ByteBuffer.allocate(8)
                    .order(java.nio.ByteOrder.nativeOrder())
                    .putLong(key).array();
            MemorySegment idSeg = arena.allocate(idBytes.length);
            MemorySegment.copy(idBytes, 0, idSeg, ValueLayout.JAVA_BYTE, 0, idBytes.length);
            array.set(ValueLayout.JAVA_LONG, base + CfApi.PCI_FILE_IDENTITY_OFF,     idSeg.address());
            array.set(ValueLayout.JAVA_INT,  base + CfApi.PCI_FILE_IDENTITY_LEN_OFF, idBytes.length);

            array.set(ValueLayout.JAVA_INT, base + CfApi.PCI_FLAGS_OFF,
                    CfApi.CF_PLACEHOLDER_CREATE_FLAG_MARK_IN_SYNC);
        }
        return array;
    }

    private MemorySegment buildOpInfo(Arena arena, long connectionKey, long transferKey, int opType, long requestKey) {
        MemorySegment seg = arena.allocate(CfApi.OI_SIZE);
        seg.set(ValueLayout.JAVA_INT,  CfApi.OI_STRUCT_SIZE_OFF,    (int) CfApi.OI_SIZE);
        seg.set(ValueLayout.JAVA_INT,  CfApi.OI_TYPE_OFF,           opType);
        seg.set(ValueLayout.JAVA_LONG, CfApi.OI_CONNECTION_KEY_OFF, connectionKey);
        seg.set(ValueLayout.JAVA_LONG, CfApi.OI_TRANSFER_KEY_OFF,   transferKey);
        seg.set(ValueLayout.JAVA_LONG, CfApi.OI_CORRELATION_VEC_OFF, 0L);
        seg.set(ValueLayout.JAVA_LONG, CfApi.OI_SYNC_STATUS_OFF,    0L);
        seg.set(ValueLayout.JAVA_LONG, CfApi.OI_REQUEST_KEY_OFF,    requestKey);
        return seg;
    }

    private String pathFromIdentity(long identityAddr, int identityLen) {
        if (identityAddr == 0 || identityLen < 8) return null;
        MemorySegment idSeg = MemorySegment.ofAddress(identityAddr).reinterpret(identityLen);
        // Windows does not guarantee 8-byte alignment of the identity blob pointer.
        long key = idSeg.get(ValueLayout.JAVA_LONG.withByteAlignment(1), 0);
        return identityToPath.get(key);
    }

    private static boolean matchesPattern(String name, String pattern) {
        if (pattern == null || pattern.isEmpty() || pattern.equals("*"))
            return true;
        // CF passes Windows-style glob with * and ?. Convert to regex.
        StringBuilder rx = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*': rx.append(".*"); break;
                case '?': rx.append("."); break;
                case '.': case '\\': case '(': case ')': case '[': case ']':
                case '{': case '}': case '+': case '|': case '^': case '$':
                    rx.append('\\').append(c); break;
                default: rx.append(c);
            }
        }
        rx.append("$");
        return name.matches("(?i)" + rx.toString());
    }

    private Path localDirPath(String normalizedPath) {
        // Convert volume-relative NormalizedPath to an absolute local filesystem Path.
        if (normalizedPath == null || normalizedPath.isEmpty())
            return Path.of(syncRootPath);
        String volRelRoot = syncRootPath.replaceFirst("^[A-Za-z]:", "").replace('\\', '/');
        String norm = normalizedPath.replace('\\', '/');
        if (norm.equalsIgnoreCase(volRelRoot) || !norm.toLowerCase().startsWith(volRelRoot.toLowerCase() + "/"))
            return Path.of(syncRootPath);
        String rel = norm.substring(volRelRoot.length() + 1).replace('/', java.io.File.separatorChar);
        return Path.of(syncRootPath).resolve(rel);
    }

    private String normalizedToPeergos(String normalizedPath) {
        // NormalizedPath from CF API is volume-relative (e.g. "\Users\...\syncroot\sub\file.txt"),
        // NOT relative to the sync root.  Strip the sync-root prefix to get the in-root path.
        // The in-root path is the full Peergos path without its leading slash —
        // e.g. "alice/foo.txt" ⇔ "/alice/foo.txt". The synthetic root "/" only contains
        // the user's home directory ("/" + context.username); onFetchPlaceholders handles
        // that case specially since there's no real peergos path "/" to enumerate.
        // syncRootPath is absolute ("C:\Users\...\syncroot") — strip the drive letter for comparison.
        if (normalizedPath == null || normalizedPath.isEmpty()) {
            return "/";
        }
        String volRelRoot = syncRootPath.replaceFirst("^[A-Za-z]:", "").replace('\\', '/');
        String norm = normalizedPath.replace('\\', '/');
        String rel;
        if (norm.equalsIgnoreCase(volRelRoot)) {
            rel = ""; // listing the sync root directory itself
        } else if (norm.toLowerCase().startsWith(volRelRoot.toLowerCase() + "/")) {
            rel = norm.substring(volRelRoot.length() + 1);
        } else {
            rel = ""; // fallback to root (e.g. empty NormalizedPath)
        }
        return rel.isEmpty() ? "/" : "/" + rel;
    }

    private void ack(Arena arena, long connectionKey, long transferKey, long requestKey, int opType, int status) {
        MemorySegment opInfo   = buildOpInfo(arena, connectionKey, transferKey, opType, requestKey);
        MemorySegment opParams = arena.allocate(CfApi.OP_ACK_SIZE);
        opParams.set(ValueLayout.JAVA_INT, CfApi.OP_PARAM_SIZE_OFF, (int) CfApi.OP_ACK_SIZE);
        opParams.set(ValueLayout.JAVA_INT, CfApi.OP_ACK_FLAGS_OFF,  CfApi.CF_OPERATION_ACK_FLAG_NONE);
        opParams.set(ValueLayout.JAVA_INT, CfApi.OP_ACK_STATUS_OFF, status);
        int hr = CfApi.cfExecute(opInfo, opParams);
        if (hr != CfApi.S_OK)
            LOG.warning("CfExecute(ACK op=" + opType + ") returned 0x" + Integer.toHexString(hr));
    }

    /**
     * Normalise a local path into a stable LRU key. Windows paths are case-insensitive,
     * and CF may hand us either the standard form (C:\...) or the Win32 long-path form
     * (\\?\C:\...) for the same file. Without normalisation, our recentlyUploaded LRU
     * would miss for case differences or prefix differences and we'd re-upload.
     */
    /**
     * Persist a "last synced version" entry for {@code relPath} pointing at the given
     * FileWrapper's current state. Used after every successful push/pull so the conflict
     * detector knows what version local and remote last agreed on.
     *
     * Skips silently if {@code syncState} wasn't configured (legacy code path) or if
     * the FileWrapper has no treeHash yet (rare for committed files; the next event
     * will record it).
     */
    void recordSyncedVersion(String relPath, FileWrapper fw) {
        recordSyncedVersion(relPath, fw, null);
    }

    /** {@code localPath} optional — used to compute the HashTree by streaming the local
     *  file when Peergos's FileProperties.treeHash isn't populated yet (which can happen
     *  immediately after an upload). Mirrors PeergosSyncFS.hashFile's fallback. */
    void recordSyncedVersion(String relPath, FileWrapper fw, Path localPath) {
        if (syncState == null) return;
        FileProperties props = fw.getFileProperties();
        HashTree tree;
        if (props.treeHash.isPresent()) {
            HashBranch branch = props.treeHash.get();
            tree = new HashTree(
                    branch.rootHash,
                    branch.level1.map(List::of).orElse(Collections.emptyList()),
                    Collections.emptyList(),
                    Collections.emptyList());
        } else if (localPath != null && Files.exists(localPath)) {
            tree = hashLocalFile(localPath, props.size);
            if (tree == null) return;
        } else {
            return;
        }
        long modTime = props.modified == null ? 0L
                : props.modified.toInstant(java.time.ZoneOffset.UTC).toEpochMilli() / 1000 * 1000;
        syncState.add(new FileState(relPath, modTime, props.size, tree));

        // Expand the persisted Snapshot to include this file's writer. The Tier-1 pull check
        // (Snapshot.equals) only catches remote changes for writers in the persisted set, so
        // we accumulate writers as we sync files from them. First call seeds the snapshot;
        // subsequent calls add new writers if any (typical case is one user → one writer).
        try {
            peergos.shared.crypto.hash.PublicKeyHash owner  = fw.owner();
            peergos.shared.crypto.hash.PublicKeyHash writer = fw.writer();
            peergos.shared.user.Snapshot current = syncState.getSnapshot(syncRootPath);
            if (current == null) current = new peergos.shared.user.Snapshot(new java.util.HashMap<>());
            if (!current.versions.containsKey(writer)) {
                peergos.shared.user.Snapshot updated =
                        current.withWriter(owner, writer, context.network).join();
                syncState.setSnapshot(syncRootPath, updated);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "recordSyncedVersion: snapshot expansion failed for " + relPath, e);
        }
    }

    /** Same as above but compute the relPath from the local placeholder path under syncRoot. */
    void recordSyncedVersion(Path localPath, FileWrapper fw) {
        String relPath = Path.of(syncRootPath).relativize(localPath).toString()
                .replace(java.io.File.separatorChar, '/');
        recordSyncedVersion(relPath, fw, localPath);
    }

    /**
     * Minimal SyncFilesystem adapter — only implements the three methods
     * peergos.server.sync.DirectorySync.renameOnConflict needs (exists, moveTo,
     * getLastModified) so we can reuse its [conflict-N] naming logic without
     * implementing the full ~15-method interface.
     */
    private static final peergos.server.sync.SyncFilesystem LOCAL_FS_FOR_RENAME =
            new peergos.server.sync.SyncFilesystem() {
        @Override public boolean exists(Path p) { return Files.exists(p); }
        @Override public void moveTo(Path src, Path target) {
            try { Files.move(src, target); }
            catch (IOException e) { throw new RuntimeException(e); }
        }
        @Override public long getLastModified(Path p) {
            try { return Files.getLastModifiedTime(p).toMillis(); }
            catch (IOException e) { throw new RuntimeException(e); }
        }
        @Override public long totalSpace() { throw new UnsupportedOperationException(); }
        @Override public long freeSpace() { throw new UnsupportedOperationException(); }
        @Override public String getRoot() { throw new UnsupportedOperationException(); }
        @Override public Path resolve(String p) { throw new UnsupportedOperationException(); }
        @Override public void mkdirs(Path p) { throw new UnsupportedOperationException(); }
        @Override public void delete(Path p) { throw new UnsupportedOperationException(); }
        @Override public void bulkDelete(Path dir, java.util.Set<String> children) { throw new UnsupportedOperationException(); }
        @Override public void setModificationTime(Path p, long t) { throw new UnsupportedOperationException(); }
        @Override public void setHash(Path p, peergos.shared.user.fs.HashTree h, long sz) { throw new UnsupportedOperationException(); }
        @Override public void setHashes(java.util.List<peergos.shared.util.Triple<String, FileWrapper, peergos.shared.user.fs.HashTree>> u) { throw new UnsupportedOperationException(); }
        @Override public long size(Path p) { throw new UnsupportedOperationException(); }
        @Override public void truncate(Path p, long size) { throw new UnsupportedOperationException(); }
        @Override public Optional<LocalDateTime> setBytes(Path p, long off, AsyncReader d, long sz,
                Optional<peergos.shared.user.fs.HashTree> h, Optional<LocalDateTime> mt,
                Optional<peergos.shared.user.fs.Thumbnail> th,
                peergos.shared.user.fs.ResumeUploadProps props,
                java.util.function.Supplier<Boolean> c, java.util.function.Consumer<String> pr) {
            throw new UnsupportedOperationException();
        }
        @Override public AsyncReader getBytes(Path p, long off) { throw new UnsupportedOperationException(); }
        @Override public void uploadSubtree(java.util.stream.Stream<FileWrapper.FolderUploadProperties> d) { throw new UnsupportedOperationException(); }
        @Override public Optional<peergos.shared.user.fs.Thumbnail> getThumbnail(Path p) { throw new UnsupportedOperationException(); }
        @Override public peergos.shared.user.fs.HashTree hashFile(Path p, Optional<FileWrapper> m, String r,
                peergos.server.sync.SyncState s, long sz) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<peergos.shared.crypto.hash.PublicKeyHash> applyToSubtree(
                java.util.function.Consumer<FileProps> f, java.util.function.Consumer<FileProps> d) {
            throw new UnsupportedOperationException();
        }
        @Override public long filesCount() { throw new UnsupportedOperationException(); }
    };

    /** Convert a Peergos absolute path "/&lt;user&gt;/a/b/c.txt" into the relPath
     *  format ("&lt;user&gt;/a/b/c.txt") used as the SyncState key — i.e. the
     *  in-sync-root path, which mirrors the full peergos path without the leading slash.
     *  Returns null for null input or the synthetic root "/". */
    private String peergosPathToRelPath(String peergosPath) {
        if (peergosPath == null || peergosPath.isEmpty() || peergosPath.equals("/")) return null;
        return peergosPath.startsWith("/") ? peergosPath.substring(1) : peergosPath;
    }

    private static String normPathKey(Path p) {
        String s = p.toAbsolutePath().normalize().toString();
        if (s.startsWith("\\\\?\\")) s = s.substring(4);
        return s.toLowerCase(java.util.Locale.ROOT);
    }

    private static long identityKey(String path) {
        // Simple deterministic hash for the path — stable across JVM restarts
        // since java.lang.String.hashCode() is specified.
        long h = 0;
        for (char c : path.toCharArray()) {
            h = h * 31 + c;
        }
        return h;
    }
}
