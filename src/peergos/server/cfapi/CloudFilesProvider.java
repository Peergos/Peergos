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

    public CloudFilesProvider(UserContext context, String syncRootPath) {
        this.context = context;
        this.syncRootPath = syncRootPath;
    }

    // -----------------------------------------------------------------------
    // FETCH_DATA callback
    // -----------------------------------------------------------------------

    public void onFetchData(MemorySegment info, MemorySegment params) {
        // Raw pointers arrive with byteSize=0; reinterpret before any field access.
        info   = info.reinterpret(256);
        params = params.reinterpret(256);
        long connectionKey = 0, transferKey = 0;
        System.err.println("[CF] FETCH_DATA callback entered");
        try {
            connectionKey  = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_CONNECTION_KEY_OFF);
            transferKey    = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_TRANSFER_KEY_OFF);
            long identityAddr  = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_FILE_IDENTITY_OFF);
            int  identityLen   = info.get(ValueLayout.JAVA_INT,  CfApi.CBI_FILE_IDENTITY_LEN_OFF);
            long requiredOffset = params.get(ValueLayout.JAVA_LONG, CfApi.CBP_FETCH_DATA_REQUIRED_OFFSET_OFF);
            long requiredLength = params.get(ValueLayout.JAVA_LONG, CfApi.CBP_FETCH_DATA_REQUIRED_LENGTH_OFF);
            System.err.println("[CF] FETCH_DATA: connKey=" + connectionKey + " transferKey=" + transferKey
                    + " identityAddr=0x" + Long.toHexString(identityAddr)
                    + " identityLen=" + identityLen
                    + " reqOffset=" + requiredOffset + " reqLen=" + requiredLength);

            String peergosPath = pathFromIdentity(identityAddr, identityLen);
            System.err.println("[CF] FETCH_DATA: peergosPath=" + peergosPath + " mapSize=" + identityToPath.size());
            if (peergosPath == null) {
                System.err.println("[CF] FETCH_DATA: path lookup FAILED identityAddr=0x" + Long.toHexString(identityAddr));
                failTransfer(connectionKey, transferKey, -1);
                return;
            }

            Optional<FileWrapper> fwOpt = context.getByPath(peergosPath).join();
            if (fwOpt.isEmpty() || fwOpt.get().isDirectory()) {
                failTransfer(connectionKey, transferKey, -1);
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
                    // CF API requires the buffer to be PAGE_SIZE-aligned in address AND
                    // padded to the next PAGE_SIZE multiple in size; Length stays as actual byte count.
                    long paddedSize = ((nRead + CLUSTER_SIZE - 1) / CLUSTER_SIZE) * CLUSTER_SIZE;
                    MemorySegment dataSeg = arena.allocate(paddedSize, CLUSTER_SIZE);
                    MemorySegment.copy(buf, 0, dataSeg, ValueLayout.JAVA_BYTE, 0, nRead);
                    transferData(arena, connectionKey, transferKey, dataSeg, offset, nRead);
                    offset    += nRead;
                    remaining -= nRead;
                }
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "FETCH_DATA callback exception (connKey=" + connectionKey + ")", e);
            failTransfer(connectionKey, transferKey, -1);
        }
    }

    private void transferData(Arena arena, long connectionKey, long transferKey,
                              MemorySegment data, long offset, long length) {
        MemorySegment opInfo   = buildOpInfo(arena, connectionKey, transferKey,
                CfApi.CF_OPERATION_TYPE_TRANSFER_DATA);
        MemorySegment opParams = arena.allocate(CfApi.OP_XFER_DATA_SIZE);
        opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_PARAM_SIZE_OFF,       (int) CfApi.OP_XFER_DATA_SIZE);
        opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_XFER_DATA_FLAGS_OFF,  CfApi.CF_OPERATION_TRANSFER_DATA_FLAG_NONE);
        opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_XFER_DATA_STATUS_OFF, CfApi.STATUS_SUCCESS);
        opParams.set(ValueLayout.JAVA_LONG, CfApi.OP_XFER_DATA_BUFFER_OFF, data.address());
        opParams.set(ValueLayout.JAVA_LONG, CfApi.OP_XFER_DATA_OFFSET_OFF, offset);
        opParams.set(ValueLayout.JAVA_LONG, CfApi.OP_XFER_DATA_LENGTH_OFF, length);

        int hr = CfApi.cfExecute(opInfo, opParams);
        System.err.println("[CF] CfExecute(TRANSFER_DATA) offset=" + offset + " length=" + length
                + " hr=0x" + Integer.toHexString(hr));
    }

    private void failTransfer(long connectionKey, long transferKey, int status) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment opInfo   = buildOpInfo(arena, connectionKey, transferKey,
                    CfApi.CF_OPERATION_TYPE_TRANSFER_DATA);
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
        long connectionKey = 0, transferKey = 0;
        try {
            connectionKey = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_CONNECTION_KEY_OFF);
            transferKey   = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_TRANSFER_KEY_OFF);
            String dirPath    = CfApi.readWideString(info, CfApi.CBI_NORMALIZED_PATH_OFF);
            String peergosPath = normalizedToPeregos(dirPath);

            Optional<FileWrapper> dirOpt = context.getByPath(peergosPath).join();
            if (dirOpt.isEmpty() || !dirOpt.get().isDirectory()) {
                failPlaceholders(connectionKey, transferKey);
                return;
            }
            Set<FileWrapper> children = dirOpt.get()
                    .getChildren(context.crypto.hasher, context.network).join();
            List<FileWrapper> visible = children.stream()
                    .filter(f -> !f.getFileProperties().isHidden)
                    .toList();

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment array = buildPlaceholderArray(visible, peergosPath, arena);
                transferPlaceholders(arena, connectionKey, transferKey, array, visible.size());
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "FETCH_PLACEHOLDERS callback error", e);
            failPlaceholders(connectionKey, transferKey);
        }
    }

    private void transferPlaceholders(Arena arena, long connectionKey, long transferKey,
                                      MemorySegment array, int count) {
        MemorySegment opInfo   = buildOpInfo(arena, connectionKey, transferKey,
                CfApi.CF_OPERATION_TYPE_TRANSFER_PLACEHOLDERS);
        MemorySegment opParams = arena.allocate(CfApi.OP_XFER_PH_SIZE);
        opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_PARAM_SIZE_OFF,          (int) CfApi.OP_XFER_PH_SIZE);
        opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_XFER_PH_FLAGS_OFF,       CfApi.CF_OPERATION_TRANSFER_PLACEHOLDERS_FLAG_NONE);
        opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_XFER_PH_STATUS_OFF,      CfApi.STATUS_SUCCESS);
        opParams.set(ValueLayout.JAVA_LONG, CfApi.OP_XFER_PH_TOTAL_COUNT_OFF, (long) count);
        opParams.set(ValueLayout.JAVA_LONG, CfApi.OP_XFER_PH_ARRAY_OFF,       array.address());
        opParams.set(ValueLayout.JAVA_INT,  CfApi.OP_XFER_PH_COUNT_OFF,       count);

        int hr = CfApi.cfExecute(opInfo, opParams);
        if (hr != CfApi.S_OK)
            LOG.warning("CfExecute(TRANSFER_PLACEHOLDERS) returned 0x" + Integer.toHexString(hr));
    }

    private void failPlaceholders(long connectionKey, long transferKey) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment opInfo   = buildOpInfo(arena, connectionKey, transferKey,
                    CfApi.CF_OPERATION_TYPE_TRANSFER_PLACEHOLDERS);
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
    }

    // -----------------------------------------------------------------------
    // NOTIFY_FILE_CLOSE_COMPLETION — write back modified files
    // -----------------------------------------------------------------------

    public void onFileCloseCompletion(MemorySegment info, MemorySegment params) {
        info   = info.reinterpret(256);
        params = params.reinterpret(256);
        int flags;
        try { flags = params.get(ValueLayout.JAVA_INT, CfApi.CBP_CLOSE_FLAGS_OFF); }
        catch (Exception e) { LOG.log(Level.WARNING, "CLOSE_COMPLETION: failed to read params", e); return; }
        if ((flags & CfApi.CF_CALLBACK_NOTIFY_FILE_CLOSE_COMPLETION_FLAG_DELETED) != 0)
            return; // file was deleted on close — handled by DELETE callback

        String normalizedPath = CfApi.readWideString(info, CfApi.CBI_NORMALIZED_PATH_OFF);
        String peergosPath    = normalizedToPeregos(normalizedPath);
        Path   localPath      = Path.of(syncRootPath).resolve(
                normalizedPath.replace('/', java.io.File.separatorChar)
                              .replaceFirst("^\\\\", ""));

        if (!Files.exists(localPath) || Files.isDirectory(localPath))
            return;

        try {
            byte[] data  = Files.readAllBytes(localPath);
            String name  = localPath.getFileName().toString();
            String parent = peergosPath.substring(0, peergosPath.lastIndexOf('/'));

            Optional<FileWrapper> parentOpt = context.getByPath(parent).join();
            if (parentOpt.isEmpty()) return;

            Optional<FileWrapper> existing = context.getByPath(peergosPath).join();
            if (existing.isPresent() && !existing.get().isDirectory()) {
                // overwrite existing file
                existing.get().overwriteChangedChunks(
                        AsyncReader.build(data), (long) data.length,
                        context.network, context.crypto, l -> {}).join();
            } else {
                // new file — upload
                parentOpt.get().uploadOrReplaceFile(name,
                        AsyncReader.build(data), (long) data.length,
                        context.network, context.crypto, () -> false, l -> {}).join();
            }
            LOG.fine("Uploaded " + peergosPath + " (" + data.length + " bytes)");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Write-back failed for " + peergosPath, e);
        }
    }

    // -----------------------------------------------------------------------
    // DELETE_PLACEHOLDER — ack then delete from Peergos
    // -----------------------------------------------------------------------

    public void onDeletePlaceholder(MemorySegment info, MemorySegment params) {
        info = info.reinterpret(256);
        params = params.reinterpret(256);
        long connectionKey, transferKey;
        String normalizedPath;
        try {
            connectionKey = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_CONNECTION_KEY_OFF);
            transferKey   = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_TRANSFER_KEY_OFF);
            normalizedPath = CfApi.readWideString(info, CfApi.CBI_NORMALIZED_PATH_OFF);
        } catch (Exception e) { LOG.log(Level.WARNING, "DELETE: failed to read params", e); return; }
        String peergosPath    = normalizedToPeregos(normalizedPath);

        // Ack first to let Windows proceed with the local delete
        try (Arena arena = Arena.ofConfined()) {
            ack(arena, connectionKey, transferKey,
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
        info = info.reinterpret(256);
        params = params.reinterpret(256);
        long connectionKey, transferKey;
        try {
            connectionKey = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_CONNECTION_KEY_OFF);
            transferKey   = info.get(ValueLayout.JAVA_LONG, CfApi.CBI_TRANSFER_KEY_OFF);
        } catch (Exception e) { LOG.log(Level.WARNING, "RENAME: failed to read params", e); return; }
        try (Arena arena = Arena.ofConfined()) {
            ack(arena, connectionKey, transferKey,
                CfApi.CF_OPERATION_TYPE_ACK_RENAME_SOURCE, CfApi.STATUS_SUCCESS);
        }
    }

    public void onRenameCompletionPlaceholder(MemorySegment info, MemorySegment params) {
        info   = info.reinterpret(256);
        params = params.reinterpret(256);
        String sourcePath, targetPath;
        try {
            sourcePath = CfApi.readWideString(params, CfApi.CBP_RENAME_COMPLETION_SOURCE_PATH_OFF);
            targetPath = CfApi.readWideString(info,   CfApi.CBI_NORMALIZED_PATH_OFF);
        } catch (Exception e) { LOG.log(Level.WARNING, "RENAME_COMPLETION: failed to read params", e); return; }
        String peergosSource = normalizedToPeregos(sourcePath);
        String peergosTarget = normalizedToPeregos(targetPath);

        try {
            Optional<FileWrapper> fwOpt = context.getByPath(peergosSource).join();
            if (fwOpt.isEmpty()) return;
            String sourceParent = peergosSource.substring(0, peergosSource.lastIndexOf('/'));
            String targetParent = peergosTarget.substring(0, peergosTarget.lastIndexOf('/'));
            String targetName   = peergosTarget.substring(peergosTarget.lastIndexOf('/') + 1);
            Optional<FileWrapper> sourceParentOpt = context.getByPath(sourceParent).join();
            Optional<FileWrapper> targetParentOpt = context.getByPath(targetParent).join();
            if (sourceParentOpt.isEmpty() || targetParentOpt.isEmpty()) return;

            if (sourceParent.equals(targetParent)) {
                // rename in place
                fwOpt.get().rename(targetName, targetParentOpt.get(),
                        PathUtil.get(peergosSource), context).join();
            } else {
                // move to different directory
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
            LOG.fine("Renamed " + peergosSource + " -> " + peergosTarget);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Rename failed " + peergosSource + " -> " + peergosTarget, e);
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

            // Flags
            array.set(ValueLayout.JAVA_INT, base + CfApi.PCI_FLAGS_OFF,
                    CfApi.CF_PLACEHOLDER_CREATE_FLAG_MARK_IN_SYNC);
        }
        return array;
    }

    private MemorySegment buildOpInfo(Arena arena, long connectionKey, long transferKey, int opType) {
        MemorySegment seg = arena.allocate(CfApi.OI_SIZE);
        seg.set(ValueLayout.JAVA_INT,  CfApi.OI_STRUCT_SIZE_OFF,    (int) CfApi.OI_SIZE);
        seg.set(ValueLayout.JAVA_INT,  CfApi.OI_TYPE_OFF,           opType);
        seg.set(ValueLayout.JAVA_LONG, CfApi.OI_CONNECTION_KEY_OFF, connectionKey);
        seg.set(ValueLayout.JAVA_LONG, CfApi.OI_TRANSFER_KEY_OFF,   transferKey);
        seg.set(ValueLayout.JAVA_LONG, CfApi.OI_CORRELATION_VEC_OFF, 0L);
        seg.set(ValueLayout.JAVA_LONG, CfApi.OI_SYNC_STATUS_OFF,    0L);
        seg.set(ValueLayout.JAVA_LONG, CfApi.OI_REQUEST_KEY_OFF,    0L);
        return seg;
    }

    private String pathFromIdentity(long identityAddr, int identityLen) {
        if (identityAddr == 0 || identityLen < 8) return null;
        MemorySegment idSeg = MemorySegment.ofAddress(identityAddr).reinterpret(identityLen);
        // Windows does not guarantee 8-byte alignment of the identity blob pointer.
        long key = idSeg.get(ValueLayout.JAVA_LONG.withByteAlignment(1), 0);
        return identityToPath.get(key);
    }

    private String normalizedToPeregos(String normalizedPath) {
        // NormalizedPath from CF API is relative to the sync root (e.g. "\Documents\file.txt").
        // Map to /<username>/relative/path.
        String rel = normalizedPath.replace('\\', '/');
        if (rel.startsWith("/")) rel = rel.substring(1);
        return rel.isEmpty() ? "/" + context.username : "/" + context.username + "/" + rel;
    }

    private void ack(Arena arena, long connectionKey, long transferKey, int opType, int status) {
        MemorySegment opInfo   = buildOpInfo(arena, connectionKey, transferKey, opType);
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
