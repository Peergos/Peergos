package peergos.server.cfapi;

import peergos.server.util.Logging;
import peergos.shared.user.UserContext;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.logging.*;

/**
 * Manages the lifetime of a Windows Cloud Files API sync root.
 * Mirrors the interface of WebdavMount: static mount() factory + close().
 */
public class CloudFilesMount implements Closeable {
    private static final Logger LOG = Logging.LOG();

    private static final String PROVIDER_NAME    = "Peergos";
    private static final String PROVIDER_VERSION = "1.0";

    private final String syncRootPath;
    private final long   connectionKey;
    private final Arena  callbackArena;   // keeps upcall stubs alive
    private final Thread watcherThread;
    private final java.nio.file.WatchService watchService;
    private volatile boolean watcherRunning = true;

    private CloudFilesMount(String syncRootPath, long connectionKey, Arena callbackArena,
                            Thread watcherThread, java.nio.file.WatchService watchService) {
        this.syncRootPath  = syncRootPath;
        this.connectionKey = connectionKey;
        this.callbackArena = callbackArena;
        this.watcherThread = watcherThread;
        this.watchService  = watchService;
    }

    public String getMountPoint() {
        return syncRootPath;
    }

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    public static CloudFilesMount mount(UserContext context) throws Exception {
        return mount(context, Path.of(System.getProperty("user.home"), "Peergos").toString());
    }

    /** Overload for testing with a custom sync root path. */
    public static CloudFilesMount mount(UserContext context, String syncRootPath) throws Exception {
        CfApi.load();

        Files.createDirectories(Path.of(syncRootPath));

        // Register the sync root with the Windows shell BEFORE calling CfRegisterSyncRoot.
        // Without HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Explorer\\SyncRootManager\\<id>
        // entries, the shell doesn't recognize our provider and CF silently drops hydration
        // deliveries (CfExecute returns S_OK but data never reaches the requesting process).
        // Discovered by comparing with Nextcloud's createSyncRootRegistryKeys.
        String syncRootId = registerSyncRootInShell(syncRootPath, context.username);
        System.err.println("[CF] Registered syncRootId in shell: " + syncRootId);

        // Arena for the sync root registration structs (registration is persistent)
        Arena globalArena = Arena.ofAuto();

        MemorySegment pathW = CfApi.wideString(syncRootPath, globalArena);

        // -- CF_SYNC_REGISTRATION --
        MemorySegment reg = globalArena.allocate(CfApi.REG_SIZE);
        MemorySegment providerNameW    = CfApi.wideString(PROVIDER_NAME,    globalArena);
        MemorySegment providerVersionW = CfApi.wideString(PROVIDER_VERSION, globalArena);
        UUID guid = UUID.nameUUIDFromBytes(("peergos-cfapi-" + PROVIDER_NAME).getBytes());
        reg.set(ValueLayout.JAVA_INT,  CfApi.REG_STRUCT_SIZE_OFF,            (int) CfApi.REG_SIZE);
        reg.set(ValueLayout.JAVA_LONG, CfApi.REG_PROVIDER_NAME_OFF,          providerNameW.address());
        reg.set(ValueLayout.JAVA_LONG, CfApi.REG_PROVIDER_VERSION_OFF,       providerVersionW.address());
        reg.set(ValueLayout.JAVA_LONG, CfApi.REG_SYNC_ROOT_IDENTITY_OFF,     0L);  // none
        reg.set(ValueLayout.JAVA_INT,  CfApi.REG_SYNC_ROOT_IDENTITY_LEN_OFF, 0);
        reg.set(ValueLayout.JAVA_LONG, CfApi.REG_FILE_IDENTITY_OFF,          0L);  // none
        reg.set(ValueLayout.JAVA_INT,  CfApi.REG_FILE_IDENTITY_LEN_OFF,      0);
        writeGuid(reg, CfApi.REG_PROVIDER_CLSID_OFF, guid, globalArena);

        // -- CF_SYNC_POLICIES --
        MemorySegment policies = globalArena.allocate(CfApi.POLICIES_SIZE);
        policies.set(ValueLayout.JAVA_INT,   CfApi.POLICIES_STRUCT_SIZE_OFF,  (int) CfApi.POLICIES_SIZE);
        policies.set(ValueLayout.JAVA_SHORT, CfApi.POLICIES_HYDRATION_OFF,    CfApi.CF_HYDRATION_POLICY_FULL);
        policies.set(ValueLayout.JAVA_SHORT, CfApi.POLICIES_HYDRATION_OFF + 2, CfApi.CF_POLICY_MODIFIER_NONE);
        policies.set(ValueLayout.JAVA_SHORT, CfApi.POLICIES_POPULATION_OFF,   CfApi.CF_POPULATION_POLICY_PARTIAL);
        policies.set(ValueLayout.JAVA_SHORT, CfApi.POLICIES_POPULATION_OFF + 2, CfApi.CF_POLICY_MODIFIER_NONE);
        policies.set(ValueLayout.JAVA_INT,   CfApi.POLICIES_INSYNC_OFF,       CfApi.CF_INSYNC_POLICY_PRESERVE_INSYNC_FOR_SYNC_ENGINE);
        policies.set(ValueLayout.JAVA_INT,   CfApi.POLICIES_HARDLINK_OFF,     CfApi.CF_HARDLINK_POLICY_NONE);
        policies.set(ValueLayout.JAVA_INT,   CfApi.POLICIES_PLACEHOLDER_MGMT_OFF, 0);

        // Silently unregister any stale registration before re-registering
        CfApi.cfUnregisterSyncRoot(pathW);

        int hr = CfApi.cfRegisterSyncRoot(pathW, reg, policies, CfApi.CF_REGISTER_FLAG_NONE);
        if (hr != CfApi.S_OK)
            throw new Exception("CfRegisterSyncRoot failed: 0x" + Integer.toHexString(hr));
        LOG.info("CF sync root registered at " + syncRootPath);

        // -- Callbacks --
        CloudFilesProvider provider = new CloudFilesProvider(context, syncRootPath);

        // callbackArena must outlive the connection — closed in CloudFilesMount.close()
        Arena callbackArena = Arena.ofShared();

        MemorySegment fetchDataStub          = CfApi.upcallStub(provider::onFetchData,                  callbackArena);
        MemorySegment validateDataStub       = CfApi.upcallStub(provider::onValidateData,               callbackArena);
        MemorySegment cancelFetchDataStub    = CfApi.upcallStub(provider::onCancelFetchData,            callbackArena);
        MemorySegment fetchPlaceholdersStub  = CfApi.upcallStub(provider::onFetchPlaceholders,          callbackArena);
        MemorySegment openCompletionStub     = CfApi.upcallStub(provider::onFileOpenCompletion,         callbackArena);
        MemorySegment closeCompletionStub    = CfApi.upcallStub(provider::onFileCloseCompletion,        callbackArena);
        MemorySegment deletePlaceholderStub  = CfApi.upcallStub(provider::onDeletePlaceholder,          callbackArena);
        MemorySegment renamePlaceholderStub  = CfApi.upcallStub(provider::onRenamePlaceholder,          callbackArena);
        MemorySegment renameCompletionStub   = CfApi.upcallStub(provider::onRenameCompletionPlaceholder, callbackArena);

        // CF_CALLBACK_REGISTRATION array terminated with CF_CALLBACK_TYPE_NONE sentinel
        MemorySegment cbTable = callbackArena.allocate(CfApi.CBR_ENTRY_SIZE * 10);

        writeCbEntry(cbTable, 0, CfApi.CF_CALLBACK_TYPE_FETCH_DATA,                    fetchDataStub);
        writeCbEntry(cbTable, 1, CfApi.CF_CALLBACK_TYPE_VALIDATE_DATA,                 validateDataStub);
        writeCbEntry(cbTable, 2, CfApi.CF_CALLBACK_TYPE_CANCEL_FETCH_DATA,             cancelFetchDataStub);
        writeCbEntry(cbTable, 3, CfApi.CF_CALLBACK_TYPE_FETCH_PLACEHOLDERS,            fetchPlaceholdersStub);
        writeCbEntry(cbTable, 4, CfApi.CF_CALLBACK_TYPE_NOTIFY_FILE_OPEN_COMPLETION,   openCompletionStub);
        writeCbEntry(cbTable, 5, CfApi.CF_CALLBACK_TYPE_NOTIFY_FILE_CLOSE_COMPLETION,  closeCompletionStub);
        writeCbEntry(cbTable, 6, CfApi.CF_CALLBACK_TYPE_NOTIFY_DELETE,                 deletePlaceholderStub);
        writeCbEntry(cbTable, 7, CfApi.CF_CALLBACK_TYPE_NOTIFY_RENAME,                 renamePlaceholderStub);
        writeCbEntry(cbTable, 8, CfApi.CF_CALLBACK_TYPE_NOTIFY_RENAME_COMPLETION,      renameCompletionStub);
        writeCbEntry(cbTable, 9, CfApi.CF_CALLBACK_TYPE_NONE,                          MemorySegment.NULL);

        // -- Connect --
        MemorySegment connectionKeyOut = callbackArena.allocate(ValueLayout.JAVA_LONG);
        System.err.println("[CF] CfConnectSyncRoot: cbTable=0x" + Long.toHexString(cbTable.address())
                + " fetchDataStub=0x" + Long.toHexString(fetchDataStub.address()));
        // REQUIRE_PROCESS_INFO: needed to detect same-process FETCH_PLACEHOLDERS (fired on
        // CfConnectSyncRoot) so we can fail them immediately and keep the directory unpopulated
        // for external processes. Previously crashed with wrong OP_XFER_PH_SIZE (40 vs 32);
        // safe now that the correct struct size is used.
        // CallbackContext: Nextcloud passes their vfs instance pointer. Pass a non-NULL value
        // (the cbTable address itself works as a unique identifier) — CF may require this to
        // properly route the callback's state machine internally.
        hr = CfApi.cfConnectSyncRoot(pathW, cbTable, cbTable,
                CfApi.CF_CONNECT_FLAG_REQUIRE_PROCESS_INFO | CfApi.CF_CONNECT_FLAG_REQUIRE_FULL_FILE_PATH,
                connectionKeyOut);
        if (hr != CfApi.S_OK) {
            callbackArena.close();
            throw new Exception("CfConnectSyncRoot failed: 0x" + Integer.toHexString(hr));
        }
        long connectionKey = connectionKeyOut.get(ValueLayout.JAVA_LONG, 0);
        System.err.println("[CF] Connected key=" + connectionKey);

        // CF only fires NOTIFY_FILE_CLOSE_COMPLETION for placeholders it already tracks.
        // For files created locally inside the sync root by other processes (e.g. Copy-Item),
        // we get no callback at all — so run a WatchService to detect new/modified files and
        // upload them to Peergos. Debounce a little so we don't fire on every byte of a write.
        java.nio.file.WatchService ws = java.nio.file.FileSystems.getDefault().newWatchService();
        Path syncRootP = Path.of(syncRootPath);
        // The watcher registers the sync root and every existing subdir on startup, and
        // every newly-created subdir as it appears — see registerRecursive() in runWatcher.
        CloudFilesMount[] holder = new CloudFilesMount[1];
        Thread wt = new Thread(() -> runWatcher(ws, syncRootP, provider, () -> holder[0] != null && holder[0].watcherRunning),
                "CF local file watcher");
        wt.setDaemon(true);

        CloudFilesMount m = new CloudFilesMount(syncRootPath, connectionKey, callbackArena, wt, ws);
        holder[0] = m;
        wt.start();
        Runtime.getRuntime().addShutdownHook(new Thread(m::close, "CF unmount"));
        return m;
    }

    private static void runWatcher(java.nio.file.WatchService ws, Path syncRoot,
                                   CloudFilesProvider provider,
                                   java.util.function.BooleanSupplier running) {
        // Coalesce bursts of write events: each path's upload kicks off only once size+mtime
        // have stayed stable for STABLE_MS, so we don't repeatedly upload a partially-written
        // file. After upload the path is left out of `inflight` so subsequent edits trigger
        // another upload — content-fingerprint dedup happens in CloudFilesProvider.
        long STABLE_MS = 750;
        java.util.Map<Path, Long> pending = new java.util.concurrent.ConcurrentHashMap<>();
        // Tracks which paths currently have an upload running so we don't double-fire while
        // join() is blocked. Bounded — uses peergos's LRU cache.
        java.util.Map<Path, Boolean> inflight = java.util.Collections.synchronizedMap(
                new peergos.shared.util.LRUCache<>(1024));
        // Recurse: register existing subdirs and any new ones the watcher discovers, so
        // edits inside subdir/ also fire CREATE/MODIFY.
        registerRecursive(ws, syncRoot);
        while (running.getAsBoolean()) {
            java.nio.file.WatchKey key;
            try { key = ws.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            if (key != null) {
                Path dir = (Path) key.watchable();
                for (java.nio.file.WatchEvent<?> ev : key.pollEvents()) {
                    Object ctx = ev.context();
                    if (!(ctx instanceof Path)) continue;
                    Path full = dir.resolve((Path) ctx);
                    try {
                        if (!Files.exists(full)) continue;
                        // A new directory: register it for events too so files dropped inside
                        // get picked up. Then forward the dir create to Peergos via the pending
                        // queue (immediate, no STABLE_MS wait — dirs have no "in-progress" state).
                        if (Files.isDirectory(full)) {
                            try { full.register(ws,
                                    java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                                    java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY); }
                            catch (Exception ignored) {}
                            pending.put(full, System.currentTimeMillis());
                        } else if (Files.size(full) > 0) {
                            pending.put(full, System.currentTimeMillis() + STABLE_MS);
                        }
                    } catch (Exception ignored) {}
                }
                key.reset();
            }
            long now = System.currentTimeMillis();
            for (java.util.Iterator<java.util.Map.Entry<Path, Long>> it = pending.entrySet().iterator(); it.hasNext(); ) {
                java.util.Map.Entry<Path, Long> e = it.next();
                if (now < e.getValue()) continue;
                Path p = e.getKey();
                it.remove();
                if (inflight.putIfAbsent(p, Boolean.TRUE) != null) continue;
                try { provider.uploadLocalFile(p); }
                catch (Exception ex) { LOG.log(Level.WARNING, "watcher upload failed: " + p, ex); }
                finally { inflight.remove(p); }
            }
        }
    }

    /**
     * Register {@code root} and every existing non-placeholder subdirectory with the
     * WatchService.
     *
     * Placeholder directories (any of FILE_ATTRIBUTE_OFFLINE / RECALL_ON_DATA_ACCESS /
     * RECALL_ON_OPEN set) are still registered so we'll catch later edits to their
     * direct children, but we SKIP_SUBTREE on them to avoid forcing NTFS to enumerate
     * their contents — that enumeration would trigger one FETCH_PLACEHOLDERS callback
     * per dir, and one Peergos directory listing per callback. The check uses
     * GetFileAttributesW which doesn't trigger hydration.
     */
    private static void registerRecursive(java.nio.file.WatchService ws, Path root) {
        try {
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                        java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    dir.register(ws,
                            java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                            java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY);
                    return CfApi.isPlaceholder(dir)
                            ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                            : java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.log(Level.WARNING, "watcher: recursive register failed at " + root, e);
        }
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    /**
     * Explicitly hydrate a placeholder so the same process can read its content.
     *
     * CfHydratePlaceholder may deliver the FETCH_DATA callback synchronously on the
     * calling thread via a Windows APC. If the caller is a JVM-managed thread that is
     * already in "native" state (inside the downcall), the re-entrant upcall causes an
     * illegal JVM thread-state transition and crashes the VM. Running the hydration on a
     * dedicated platform thread that has no prior JVM-downcall context on its stack keeps
     * any APC-delivered upcall on a clean thread, avoiding the crash.
     */
    public void hydratePlaceholder(String relativeName) throws Exception {
        java.nio.file.Path fullPath = java.nio.file.Path.of(syncRootPath).resolve(relativeName);
        // Open the file on the calling thread; the HANDLE is process-wide.
        MemorySegment handle;
        try (Arena arena = Arena.ofConfined()) {
            handle = CfApi.createFileForHydration(CfApi.wideString(fullPath.toString(), arena));
        }
        if (handle.address() == CfApi.INVALID_HANDLE_VALUE)
            throw new Exception("CreateFile failed for " + relativeName);
        try {
            long addr = handle.address();
            int[] hr = {0};
            Thread.ofPlatform().start(() ->
                hr[0] = CfApi.cfHydratePlaceholder(
                        MemorySegment.ofAddress(addr), 0L, -1L, CfApi.CF_HYDRATE_FLAG_NONE)
            ).join();
            if (hr[0] != CfApi.S_OK)
                throw new Exception("CfHydratePlaceholder failed: 0x" + Integer.toHexString(hr[0]));
        } finally {
            CfApi.closeHandle(handle);
        }
    }

    @Override
    public void close() {
        watcherRunning = false;
        try { if (watchService != null) watchService.close(); } catch (Exception ignored) {}
        if (watcherThread != null) {
            try { watcherThread.join(2000); } catch (InterruptedException ignored) {}
        }
        try {
            int hr = CfApi.cfDisconnectSyncRoot(connectionKey);
            if (hr != CfApi.S_OK)
                LOG.warning("CfDisconnectSyncRoot returned 0x" + Integer.toHexString(hr));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error disconnecting CF sync root", e);
        } finally {
            try { callbackArena.close(); } catch (Exception ignored) {}
        }
        LOG.info("CF sync root disconnected");
    }

    // -----------------------------------------------------------------------
    // Struct helpers
    // -----------------------------------------------------------------------

    /**
     * Register the sync root in the Windows shell registry so CF recognises us as a real
     * cloud provider. Without this, CfExecute calls succeed but hydrated data is silently
     * dropped. Mirrors Nextcloud's createSyncRootRegistryKeys() and what
     * StorageProviderSyncRootManager::Register does under the hood.
     *
     * Returns the syncRootId we registered.
     */
    private static String registerSyncRootInShell(String syncRootPath, String accountName) throws Exception {
        String sid = currentUserSid();
        // syncRootId format: ProviderName!SID!AccountName!FolderAlias
        // FolderAlias is a unique per-folder identifier; using the path hash keeps it unique
        String folderAlias = Integer.toHexString(syncRootPath.hashCode());
        String syncRootId = PROVIDER_NAME + "!" + sid + "!" + accountName + "!" + folderAlias;
        // MSDN explicitly says HKLM for SyncRootManager (not HKCU). Requires admin to write.
        String baseKey = "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Explorer\\SyncRootManager\\" + syncRootId;

        // Required values per Microsoft's "Integrate a cloud storage provider" docs:
        // - Flags (DWORD): 0x14 = SHOW_SIBLING_DISPLAY_NAME | HIDE_LIBRARY (typical values)
        // - DisplayNameResource (REG_EXPAND_SZ)
        // - IconResource (REG_EXPAND_SZ)
        // - UserSyncRoots\<SID> (REG_SZ, value = sync root path)
        regAdd(baseKey, "Flags", "REG_DWORD", "0x14");
        regAdd(baseKey, "DisplayNameResource", "REG_EXPAND_SZ", "Peergos");
        regAdd(baseKey, "IconResource", "REG_EXPAND_SZ",
                System.getenv("SystemRoot") + "\\System32\\imageres.dll,-1043");
        regAdd(baseKey + "\\UserSyncRoots", sid, "REG_SZ", syncRootPath);
        return syncRootId;
    }

    private static void regAdd(String key, String valueName, String type, String value) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("reg", "add", key, "/v", valueName, "/t", type, "/d", value, "/f");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0)
            throw new Exception("reg add failed for " + key + " " + valueName + ": " + out.trim());
    }

    private static String currentUserSid() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                "-Command", "[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String sid = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        if (sid.isEmpty()) throw new Exception("Failed to get current user SID");
        return sid;
    }

    private static void writeCbEntry(MemorySegment table, int index, int type, MemorySegment fn) {
        long base = CfApi.CBR_ENTRY_SIZE * index;
        table.set(ValueLayout.JAVA_INT,  base + CfApi.CBR_TYPE_OFF,     type);
        table.set(ValueLayout.JAVA_LONG, base + CfApi.CBR_CALLBACK_OFF,
                fn == MemorySegment.NULL ? 0L : fn.address());
    }

    /**
     * Write a Windows GUID struct.
     * GUID layout: DWORD Data1, WORD Data2, WORD Data3, BYTE[8] Data4
     */
    private static void writeGuid(MemorySegment seg, long offset, UUID uuid, Arena arena) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        // Data1 = upper 32 bits of msb
        seg.set(ValueLayout.JAVA_INT,   offset,      (int) (msb >>> 32));
        // Data2 = bits 31:16 of msb
        seg.set(ValueLayout.JAVA_SHORT, offset + 4,  (short) (msb >>> 16));
        // Data3 = bits 15:0 of msb
        seg.set(ValueLayout.JAVA_SHORT, offset + 6,  (short) msb);
        // Data4 = all 8 bytes of lsb
        for (int i = 0; i < 8; i++) {
            seg.set(ValueLayout.JAVA_BYTE, offset + 8 + i,
                    (byte) (lsb >>> (56 - i * 8)));
        }
    }
}
