package peergos.server.cfapi;

import peergos.server.util.Logging;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileProperties;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.PathUtil;

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

    public CloudFilesProvider(UserContext context, String syncRootPath) {
        this.context = context;
        this.syncRootPath = syncRootPath;
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
        System.err.println("[CF] FETCH_DATA: connKey=" + connectionKey + " xferKey=" + transferKey
                + " reqOff=" + requiredOffset + " reqLen=" + requiredLength);
        // Call synchronously on the CF callback thread — CF may correlate the CfExecute
        // back to the originating thread, so calling from a worker thread loses context.
        doFetchData(connectionKey, transferKey, requestKey, identityAddr, identityLen, requiredOffset, requiredLength);
    }

    private void doFetchData(long connectionKey, long transferKey, long requestKey,
                             long identityAddr, int identityLen, long requiredOffset, long requiredLength) {
        try {
            String peergosPath = pathFromIdentity(identityAddr, identityLen);
            System.err.println("[CF] FETCH_DATA: peergosPath=" + peergosPath + " mapSize=" + identityToPath.size());
            if (peergosPath == null) {
                System.err.println("[CF] FETCH_DATA: path lookup FAILED identityAddr=0x" + Long.toHexString(identityAddr));
                failTransfer(connectionKey, transferKey, requestKey, -1);
                return;
            }

            Optional<FileWrapper> fwOpt = context.getByPath(peergosPath).join();
            if (fwOpt.isEmpty() || fwOpt.get().isDirectory()) {
                failTransfer(connectionKey, transferKey, requestKey, -1);
                return;
            }
            FileWrapper fw  = fwOpt.get();
            long end = Math.min(requiredOffset + requiredLength, fw.getSize());

            try (Arena arena = Arena.ofConfined()) {
                AsyncReader reader = fw.getInputStream(context.network, context.crypto,
                        fw.getSize(), l -> {}).join();
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
                    System.err.println("[CF] FETCH_DATA: nRead=" + nRead + " offset=" + offset);
                    // Length = actual bytes read (Microsoft passes numberOfBytesTransfered,
                    // which for a 30-byte file is 30, NOT the padded buffer size).
                    transferData(connectionKey, transferKey, requestKey, ds, offset, nRead, fw.getSize());
                    // Sweep already-expired buffers, then defer this one for BUFFER_RETAIN_MS.
                    sweepExpiredBuffers();
                    pendingBuffers.add(new Object[]{ds, System.currentTimeMillis() + BUFFER_RETAIN_MS});
                    offset    += nRead;
                    remaining -= nRead;
                }
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

            // Pre-progress: completed < total so CF doesn't consider hydration done prematurely.
            long total = fileSize + CLUSTER_SIZE;
            long completed = offset + CLUSTER_SIZE;
            int progressHr = CfApi.cfReportProviderProgress(connectionKey, transferKey, total, completed);
            System.err.println("[CF] CfReportProviderProgress(total=" + total + ", completed=" + completed
                    + ") hr=0x" + Integer.toHexString(progressHr));

            int hr = CfApi.cfExecute(opInfo, opParams);
            System.err.println("[CF] CfExecute(TRANSFER_DATA) hr=0x" + Integer.toHexString(hr));

            // If this CfExecute delivered the final chunk (offset+length == fileSize), mark
            // the operation 100% complete so the UI popup closes and the read unblocks.
            if (offset + length >= fileSize) {
                int finalHr = CfApi.cfReportProviderProgress(connectionKey, transferKey, fileSize, fileSize);
                System.err.println("[CF] CfReportProviderProgress(final 100%) hr=0x" + Integer.toHexString(finalHr));
            }
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
        System.err.println("[CF] FETCH_PLACEHOLDERS entered");
        try {
            connectionKey = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_CONNECTION_KEY_OFF);
            transferKey   = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_TRANSFER_KEY_OFF);
            requestKey    = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_REQUEST_KEY_OFF);

            // Detect same-process callbacks (e.g. fired by CfConnectSyncRoot itself).
            long processInfoAddr = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_PROCESS_INFO_OFF);
            int ourPid = (int) ProcessHandle.current().pid();
            int callerPid = -1;
            if (processInfoAddr != 0) {
                callerPid = MemorySegment.ofAddress(processInfoAddr).reinterpret(16)
                        .get(ValueLayout.JAVA_INT, 4);
            }
            System.err.println("[CF] FETCH_PLACEHOLDERS: processInfoAddr=0x" + Long.toHexString(processInfoAddr)
                    + " callerPid=" + callerPid + " ourPid=" + ourPid);
            if (processInfoAddr != 0 && callerPid == ourPid) {
                System.err.println("[CF] FETCH_PLACEHOLDERS: same-process → fail to keep dir unpopulated");
                failPlaceholders(connectionKey, transferKey, requestKey);
                return;
            }

            String dirPath    = CfApi.readWideString(info, CfApi.CBI_NORMALIZED_PATH_OFF);
            System.err.println("[CF] FETCH_PLACEHOLDERS connKey=" + connectionKey
                    + " transferKey=" + transferKey + " dirPath='" + dirPath + "'");
            String peergosPath = normalizedToPeregos(dirPath);
            System.err.println("[CF] FETCH_PLACEHOLDERS fetching " + peergosPath);

            Optional<FileWrapper> dirOpt = context.getByPath(peergosPath).join();
            if (dirOpt.isEmpty() || !dirOpt.get().isDirectory()) {
                System.err.println("[CF] FETCH_PLACEHOLDERS: path not found or not a dir → fail");
                failPlaceholders(connectionKey, transferKey, requestKey);
                return;
            }
            int fetchFlags = params.get(ValueLayout.JAVA_INT, CfApi.CBP_FETCH_PH_FLAGS_OFF);
            String pattern = CfApi.readWideString(params, CfApi.CBP_FETCH_PH_PATTERN_OFF);
            System.err.println("[CF] FETCH_PLACEHOLDERS: fetchFlags=0x" + Integer.toHexString(fetchFlags)
                    + " pattern='" + pattern + "'");

            Set<FileWrapper> children = dirOpt.get()
                    .getChildren(context.crypto.hasher, context.network).join();
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
            System.err.println("[CF] FETCH_PLACEHOLDERS: returning " + visible.size()
                    + " NEW entries matching pattern '" + pattern + "'");

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment array = visible.isEmpty()
                        ? MemorySegment.NULL
                        : buildPlaceholderArray(visible, peergosPath, arena);
                transferPlaceholders(arena, connectionKey, transferKey, requestKey, array, visible.size());
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

        System.err.print("[CF] opParams(PH) @0x" + Long.toHexString(opParams.address()) + ":");
        for (int i = 0; i < (int) CfApi.OP_XFER_PH_SIZE; i++)
            System.err.printf(" %02x", opParams.get(ValueLayout.JAVA_BYTE, i) & 0xFF);
        System.err.println();
        int hr = CfApi.cfExecute(opInfo, opParams);
        int entriesProcessed = opParams.get(ValueLayout.JAVA_INT, CfApi.OP_XFER_PH_ENTRIES_PROCESSED_OFF);
        System.err.println("[CF] CfExecute(TRANSFER_PLACEHOLDERS) hr=0x" + Integer.toHexString(hr)
                + " entriesProcessed=" + entriesProcessed);
        if (array != MemorySegment.NULL) {
            for (int i = 0; i < count; i++) {
                long base = CfApi.PCI_SIZE * i;
                int resultHr = array.get(ValueLayout.JAVA_INT, base + CfApi.PCI_RESULT_OFF);
                long createUsn = array.get(ValueLayout.JAVA_LONG, base + CfApi.PCI_CREATE_USN_OFF);
                System.err.println("[CF] Placeholder[" + i + "] Result=0x" + Integer.toHexString(resultHr)
                        + " CreateUsn=" + createUsn);
            }
        }
        java.io.File[] physical = new java.io.File(syncRootPath).listFiles();
        System.err.println("[CF] Physical files after TRANSFER_PLACEHOLDERS: "
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
     * Called once on mount to populate the sync root's immediate children so
     * Explorer shows them without requiring an explicit FETCH_PLACEHOLDERS.
     */
    public void seedRootPlaceholders(Arena arena) throws Exception {
        Optional<FileWrapper> rootOpt = context.getByPath("/" + context.username).join();
        if (rootOpt.isEmpty()) return;

        Set<FileWrapper> children = rootOpt.get()
                .getChildren(context.crypto.hasher, context.network).join();
        List<FileWrapper> visible = children.stream()
                .filter(f -> !f.getFileProperties().isHidden)
                .toList();
        if (visible.isEmpty()) return;

        MemorySegment baseDirW = CfApi.wideString(syncRootPath, arena);
        MemorySegment array    = buildPlaceholderArray(visible, "/" + context.username, arena);
        MemorySegment processed = arena.allocate(ValueLayout.JAVA_INT);

        System.err.println("[CF] CfCreatePlaceholders: syncRoot=" + syncRootPath + " count=" + visible.size()
                + " names=" + visible.stream().map(f -> f.getName()).collect(java.util.stream.Collectors.joining(",")));
        int hr = CfApi.cfCreatePlaceholders(baseDirW, array, visible.size(),
                CfApi.CF_CREATE_FLAG_NONE, processed);
        int entriesProcessed = processed.get(ValueLayout.JAVA_INT, 0);
        System.err.println("[CF] CfCreatePlaceholders hr=0x" + Integer.toHexString(hr)
                + " entriesProcessed=" + entriesProcessed);
        // Verify physical files are visible on the real filesystem after creation.
        java.io.File[] physicalFiles = new java.io.File(syncRootPath).listFiles();
        System.err.println("[CF] Physical files in syncRoot after CfCreatePlaceholders: "
                + (physicalFiles == null ? "null" : java.util.Arrays.stream(physicalFiles)
                        .map(java.io.File::getName).collect(java.util.stream.Collectors.joining(", "))));
    }

    // -----------------------------------------------------------------------
    // NOTIFY_FILE_CLOSE_COMPLETION — write back modified files
    // -----------------------------------------------------------------------

    // CF requires these to be registered (matching Nextcloud's pattern) for the hydration
    // state machine to advance. We log only — CF handles the actual semantics.

    public void onValidateData(MemorySegment info, MemorySegment params) {
        System.err.println("[CF] VALIDATE_DATA callback");
    }

    public void onCancelFetchData(MemorySegment info, MemorySegment params) {
        System.err.println("[CF] CANCEL_FETCH_DATA callback");
    }

    public void onFileOpenCompletion(MemorySegment info, MemorySegment params) {
        System.err.println("[CF] FILE_OPEN_COMPLETION callback");
    }

    public void onFileCloseCompletion(MemorySegment info, MemorySegment params) {
        info   = info.reinterpret(256);
        params = params.reinterpret(256);
        int flags;
        try { flags = params.get(ValueLayout.JAVA_INT, CfApi.CBP_CLOSE_FLAGS_OFF); }
        catch (Exception e) { LOG.log(Level.WARNING, "CLOSE_COMPLETION: failed to read params", e); return; }
        if ((flags & CfApi.CF_CALLBACK_NOTIFY_FILE_CLOSE_COMPLETION_FLAG_DELETED) != 0) {
            System.err.println("[CF] FILE_CLOSE_COMPLETION: file deleted, skipping");
            return; // file was deleted on close — handled by DELETE callback
        }

        String normalizedPath = CfApi.readWideString(info, CfApi.CBI_NORMALIZED_PATH_OFF);
        String peergosPath    = normalizedToPeregos(normalizedPath);
        // normalizedPath is an absolute path within the volume ("\Users\...\file.txt").
        // Reattach the drive letter from syncRootPath ("C:") to form a full path.
        String drive  = syncRootPath.substring(0, 2); // e.g. "C:"
        Path   localPath = Path.of(drive + normalizedPath);

        System.err.println("[CF] FILE_CLOSE_COMPLETION: normalizedPath='" + normalizedPath
                + "' peergosPath='" + peergosPath + "' localPath='" + localPath + "' flags=0x"
                + Integer.toHexString(flags));

        if (recentlyUploaded.remove(localPath.toString()) != null) {
            System.err.println("[CF] FILE_CLOSE_COMPLETION: just uploaded by watcher, skipping");
            return;
        }

        if (!Files.exists(localPath) || Files.isDirectory(localPath)) {
            System.err.println("[CF] FILE_CLOSE_COMPLETION: localPath missing or directory, skipping");
            return;
        }

        long localSize;
        try { localSize = Files.size(localPath); }
        catch (Exception e) { LOG.log(Level.WARNING, "CLOSE_COMPLETION: size read failed", e); return; }
        System.err.println("[CF] FILE_CLOSE_COMPLETION: localSize=" + localSize);

        // A close callback can fire for events that don't represent a finished write — e.g.
        // an open-for-read that we serviced via FETCH_DATA and the caller then closed. Only
        // write back when this side actually owns fresh data: the file has non-zero size and
        // either doesn't yet exist in Peergos or its size differs from what's on disk.
        String peergosParent = peergosPath.substring(0, peergosPath.lastIndexOf('/'));
        Optional<FileWrapper> existingOpt;
        try { existingOpt = context.getByPath(peergosPath).join(); }
        catch (Exception e) { LOG.log(Level.WARNING, "CLOSE_COMPLETION: lookup failed", e); return; }
        if (existingOpt.isPresent() && !existingOpt.get().isDirectory()
                && existingOpt.get().getSize() == localSize) {
            System.err.println("[CF] FILE_CLOSE_COMPLETION: peergos size matches disk ("
                    + localSize + "), no write-back needed");
            return;
        }
        if (localSize == 0) {
            System.err.println("[CF] FILE_CLOSE_COMPLETION: local file empty, skipping write-back");
            return;
        }

        String name = localPath.getFileName().toString();
        System.err.println("[CF] FILE_CLOSE_COMPLETION: uploading " + localSize
                + " bytes to " + peergosPath);

        Optional<FileWrapper> parentOpt = context.getByPath(peergosParent).join();
        if (parentOpt.isEmpty()) {
            System.err.println("[CF] FILE_CLOSE_COMPLETION: parent missing: " + peergosParent);
            return;
        }

        try (peergos.server.simulation.FileAsyncReader reader =
                     new peergos.server.simulation.FileAsyncReader(localPath.toFile())) {
            if (existingOpt.isPresent() && !existingOpt.get().isDirectory()) {
                existingOpt.get().overwriteChangedChunks(
                        reader, localSize,
                        context.network, context.crypto, l -> {}).join();
            } else {
                parentOpt.get().uploadOrReplaceFile(name,
                        reader, localSize,
                        context.network, context.crypto, () -> false, l -> {}).join();
            }
            System.err.println("[CF] FILE_CLOSE_COMPLETION: upload complete for " + peergosPath);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Write-back failed for " + peergosPath, e);
        }
    }

    /**
     * Upload a file that was created locally inside the sync root by some external process.
     * CF doesn't fire FILE_CLOSE_COMPLETION for brand-new local files (only for placeholders
     * it already manages), so a separate watcher invokes this when it detects a new file.
     */
    public void uploadLocalFile(Path localPath) {
        try {
            if (!Files.exists(localPath)) return;

            String name        = localPath.getFileName().toString();
            String relative    = Path.of(syncRootPath).relativize(localPath).toString()
                    .replace(java.io.File.separatorChar, '/');
            String peergosPath = "/" + context.username + "/" + relative;
            String peergosParent = peergosPath.substring(0, peergosPath.lastIndexOf('/'));

            if (Files.isDirectory(localPath)) {
                createPeergosDirectory(name, peergosPath, peergosParent);
                return;
            }
            long localSize = Files.size(localPath);
            if (localSize == 0) return;

            Optional<FileWrapper> existing = context.getByPath(peergosPath).join();
            if (existing.isPresent() && !existing.get().isDirectory()
                    && existing.get().getSize() == localSize)
                return; // already in sync

            Optional<FileWrapper> parentOpt = context.getByPath(peergosParent).join();
            if (parentOpt.isEmpty()) {
                System.err.println("[CF] uploadLocalFile: parent missing: " + peergosParent);
                return;
            }

            System.err.println("[CF] uploadLocalFile: uploading " + localSize
                    + " bytes to " + peergosPath);
            FileWrapper uploaded;
            try (peergos.server.simulation.FileAsyncReader reader =
                         new peergos.server.simulation.FileAsyncReader(localPath.toFile())) {
                if (existing.isPresent() && !existing.get().isDirectory()) {
                    uploaded = existing.get().overwriteChangedChunks(
                            reader, localSize,
                            context.network, context.crypto, l -> {}).join();
                } else {
                    uploaded = parentOpt.get().uploadOrReplaceFile(name,
                            reader, localSize,
                            context.network, context.crypto, () -> false, l -> {}).join();
                }
            }
            System.err.println("[CF] uploadLocalFile: upload complete for " + peergosPath);

            // Convert the local file to a CF placeholder so subsequent reads go through
            // FETCH_DATA and CF fires NOTIFY_RENAME / NOTIFY_DELETE for managed files.
            convertToPlaceholder(localPath, uploaded, peergosPath);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "uploadLocalFile failed for " + localPath, e);
        }
    }

    private void createPeergosDirectory(String name, String peergosPath, String peergosParent) {
        Optional<FileWrapper> existing = context.getByPath(peergosPath).join();
        if (existing.isPresent()) return;
        Optional<FileWrapper> parentOpt = context.getByPath(peergosParent).join();
        if (parentOpt.isEmpty()) {
            System.err.println("[CF] mkdir: parent missing: " + peergosParent);
            return;
        }
        try {
            parentOpt.get().mkdir(name, context.network, false, Optional.empty(), context.crypto).join();
            System.err.println("[CF] mkdir: created " + peergosPath);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "mkdir failed for " + peergosPath, e);
        }
    }

    private void convertToPlaceholder(Path localPath, FileWrapper uploaded, String peergosPath) {
        // The convert itself triggers FILE_CLOSE_COMPLETION on the convert handle. Record
        // the path so the close handler short-circuits and we don't re-upload.
        recentlyUploaded.put(localPath.toString(), Boolean.TRUE);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathW = CfApi.wideString(localPath.toString(), arena);
            MemorySegment handle = CfApi.createFileForConvert(pathW);
            if (handle.address() == CfApi.INVALID_HANDLE_VALUE) {
                System.err.println("[CF] convertToPlaceholder: CreateFile failed for " + localPath);
                recentlyUploaded.remove(localPath.toString());
                return;
            }
            try {
                // Identity must match the scheme used in buildPlaceholderArray (8-byte hash
                // of the Peergos path) so that later FETCH_DATA callbacks can look the file
                // up in identityToPath.
                long key = identityKey(peergosPath);
                identityToPath.put(key, peergosPath);
                MemorySegment idSeg = arena.allocate(8);
                idSeg.set(ValueLayout.JAVA_LONG, 0, key);
                int hr = CfApi.cfConvertToPlaceholder(handle, idSeg, 8,
                        CfApi.CF_CONVERT_FLAG_MARK_IN_SYNC);
                System.err.println("[CF] convertToPlaceholder hr=0x"
                        + Integer.toHexString(hr) + " path=" + localPath);
                if (hr != CfApi.S_OK)
                    recentlyUploaded.remove(localPath.toString());
            } finally {
                CfApi.closeHandle(handle);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "convertToPlaceholder failed for " + localPath, e);
            recentlyUploaded.remove(localPath.toString());
        }
    }

    // -----------------------------------------------------------------------
    // DELETE_PLACEHOLDER — ack then delete from Peergos
    // -----------------------------------------------------------------------

    public void onDeletePlaceholder(MemorySegment info, MemorySegment params) {
        System.err.println("[CF] NOTIFY_DELETE entered");
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
        String peergosPath    = normalizedToPeregos(normalizedPath);

        // Ack first to let Windows proceed with the local delete
        try (Arena arena = Arena.ofConfined()) {
            ack(arena, connectionKey, transferKey, requestKey,
                CfApi.CF_OPERATION_TYPE_ACK_DELETE, CfApi.STATUS_SUCCESS);
        }

        // Then remove from Peergos
        try {
            Optional<FileWrapper> fwOpt = context.getByPath(peergosPath).join();
            if (fwOpt.isEmpty()) return;
            String parent = peergosPath.substring(0, peergosPath.lastIndexOf('/'));
            Optional<FileWrapper> parentOpt = context.getByPath(parent).join();
            if (parentOpt.isEmpty()) return;
            fwOpt.get().remove(parentOpt.get(), PathUtil.get(peergosPath), context).join();
            identityToPath.values().removeIf(p -> p.equals(peergosPath) || p.startsWith(peergosPath + "/"));
            LOG.fine("Deleted " + peergosPath);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Delete failed for " + peergosPath, e);
        }
    }

    // -----------------------------------------------------------------------
    // RENAME_PLACEHOLDER — ack source; RENAME_COMPLETION — do the rename
    // -----------------------------------------------------------------------

    public void onRenamePlaceholder(MemorySegment info, MemorySegment params) {
        System.err.println("[CF] NOTIFY_RENAME entered");
        info = info.reinterpret(256);
        params = params.reinterpret(256);
        long connectionKey, transferKey, requestKey;
        try {
            connectionKey = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_CONNECTION_KEY_OFF);
            transferKey   = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_TRANSFER_KEY_OFF);
            requestKey    = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_REQUEST_KEY_OFF);
        } catch (Exception e) { LOG.log(Level.WARNING, "RENAME: failed to read params", e); return; }
        System.err.println("[CF] NOTIFY_RENAME connKey=" + connectionKey
                + " xferKey=" + transferKey + " reqKey=" + requestKey);
        try (Arena arena = Arena.ofConfined()) {
            ack(arena, connectionKey, transferKey, requestKey,
                CfApi.CF_OPERATION_TYPE_ACK_RENAME, CfApi.STATUS_SUCCESS);
        }
        System.err.println("[CF] NOTIFY_RENAME ack sent");
    }

    public void onRenameCompletionPlaceholder(MemorySegment info, MemorySegment params) {
        System.err.println("[CF] NOTIFY_RENAME_COMPLETION entered");
        info   = info.reinterpret(256);
        params = params.reinterpret(256);
        String sourcePath, targetPath;
        try {
            sourcePath = CfApi.readWideString(params, CfApi.CBP_RENAME_COMPLETION_SOURCE_PATH_OFF);
            targetPath = CfApi.readWideString(info,   CfApi.CBI_NORMALIZED_PATH_OFF);
        } catch (Exception e) { LOG.log(Level.WARNING, "RENAME_COMPLETION: failed to read params", e); return; }
        String peergosSource = normalizedToPeregos(sourcePath);
        String peergosTarget = normalizedToPeregos(targetPath);

        System.err.println("[CF] NOTIFY_RENAME_COMPLETION src=" + peergosSource
                + " tgt=" + peergosTarget);

        // Suppress the upcoming FILE_CLOSE_COMPLETION on the target — the OS fires it for
        // the rename's "open new name" handle, and without this short-circuit our close
        // handler would treat the renamed file as a brand-new local file and re-upload it.
        recentlyUploaded.put(Path.of(syncRootPath).resolve(
                peergosTarget.substring(peergosTarget.indexOf('/', 1) + 1)
                              .replace('/', java.io.File.separatorChar)).toString(), Boolean.TRUE);

        try {
            Optional<FileWrapper> fwOpt = context.getByPath(peergosSource).join();
            if (fwOpt.isEmpty()) {
                System.err.println("[CF] NOTIFY_RENAME_COMPLETION: source not found in peergos: "
                        + peergosSource);
                return;
            }
            String sourceParent = peergosSource.substring(0, peergosSource.lastIndexOf('/'));
            String targetParent = peergosTarget.substring(0, peergosTarget.lastIndexOf('/'));
            String targetName   = peergosTarget.substring(peergosTarget.lastIndexOf('/') + 1);
            Optional<FileWrapper> sourceParentOpt = context.getByPath(sourceParent).join();
            Optional<FileWrapper> targetParentOpt = context.getByPath(targetParent).join();
            if (sourceParentOpt.isEmpty() || targetParentOpt.isEmpty()) {
                System.err.println("[CF] NOTIFY_RENAME_COMPLETION: parents missing src="
                        + sourceParentOpt.isPresent() + " tgt=" + targetParentOpt.isPresent());
                return;
            }

            if (sourceParent.equals(targetParent)) {
                System.err.println("[CF] NOTIFY_RENAME_COMPLETION: in-place rename to " + targetName);
                fwOpt.get().rename(targetName, targetParentOpt.get(),
                        PathUtil.get(peergosSource), context).join();
            } else {
                System.err.println("[CF] NOTIFY_RENAME_COMPLETION: moveTo " + targetParent);
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
            System.err.println("[CF] NOTIFY_RENAME_COMPLETION: done " + peergosSource
                    + " -> " + peergosTarget);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Rename failed " + peergosSource + " -> " + peergosTarget, e);
            System.err.println("[CF] NOTIFY_RENAME_COMPLETION FAILED: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private MemorySegment buildPlaceholderArray(List<FileWrapper> children,
                                                String parentPeregosPath, Arena arena) {
        MemorySegment array = arena.allocate(CfApi.PCI_SIZE * children.size());
        for (int i = 0; i < children.size(); i++) {
            FileWrapper fw = children.get(i);
            FileProperties props = fw.getFileProperties();
            long base = CfApi.PCI_SIZE * i;

            // Relative file name pointer
            MemorySegment nameW = CfApi.wideString(props.name, arena);
            array.set(ValueLayout.JAVA_LONG, base + CfApi.PCI_RELATIVE_FILE_NAME_OFF, nameW.address());
            System.err.println("[CF] buildPlaceholderArray[" + i + "] name='" + props.name
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
            array.set(ValueLayout.JAVA_INT, fbiBase + CfApi.FBI_FILE_ATTRIBUTES_OFF, attrs);

            // FS metadata — FileSize
            long fileSize = fw.isDirectory() ? 0 : props.size;
            array.set(ValueLayout.JAVA_LONG,
                    base + CfApi.PCI_FS_METADATA_OFF + CfApi.FSM_FILE_SIZE_OFF, fileSize);

            // File identity: store the full Peergos path as UTF-8 bytes (max 4096)
            String fullPath = parentPeregosPath + "/" + props.name;
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

    private String normalizedToPeregos(String normalizedPath) {
        // NormalizedPath from CF API is volume-relative (e.g. "\Users\...\syncroot\sub\file.txt"),
        // NOT relative to the sync root.  Strip the sync-root prefix to get the in-root path.
        // syncRootPath is absolute ("C:\Users\...\syncroot") — strip the drive letter for comparison.
        if (normalizedPath == null || normalizedPath.isEmpty()) {
            return "/" + context.username;
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
        return rel.isEmpty() ? "/" + context.username : "/" + context.username + "/" + rel;
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
