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
    private final java.util.concurrent.ExecutorService uploadExecutor;
    private final java.util.concurrent.ScheduledExecutorService pullScheduler;
    private final peergos.server.sync.SyncState syncState;
    private final CloudFilesProvider provider;       // exposed for forcePullTick()
    private volatile boolean watcherRunning = true;

    private CloudFilesMount(String syncRootPath, long connectionKey, Arena callbackArena,
                            Thread watcherThread, java.nio.file.WatchService watchService,
                            java.util.concurrent.ExecutorService uploadExecutor,
                            java.util.concurrent.ScheduledExecutorService pullScheduler,
                            peergos.server.sync.SyncState syncState,
                            CloudFilesProvider provider) {
        this.syncRootPath   = syncRootPath;
        this.connectionKey  = connectionKey;
        this.callbackArena  = callbackArena;
        this.watcherThread  = watcherThread;
        this.watchService   = watchService;
        this.uploadExecutor = uploadExecutor;
        this.pullScheduler  = pullScheduler;
        this.syncState      = syncState;
        this.provider       = provider;
    }

    /** Trigger a pull tick synchronously. Test-only hook. */
    public void forcePullTick() {
        if (provider != null) provider.runPullTick();
    }

    public String getMountPoint() {
        return syncRootPath;
    }

    public static CloudFilesMount mount(UserContext context, Path peergosDir) throws Exception {
        return mount(context, peergosDir, Path.of(System.getProperty("user.home"), "Peergos").toString());
    }

    /** Overload for testing with a custom sync root path. State DB defaults to
     *  ~/.peergos/cf-state-<hash16>.db where the hash is derived from the sync root.
     *  Icon resolves to peergosDir/peergos.ico — extracted from the classpath resource
     *  /peergos.ico on first run if not already present. Falls back to Windows' generic
     *  cloud icon if neither is available. */
    public static CloudFilesMount mount(UserContext context,
                                        Path peergosDir,
                                        String syncRootPath) throws Exception {
        return mount(context, syncRootPath,
                defaultStateDbPath(peergosDir, syncRootPath),
                ensureIconExtracted(peergosDir));
    }

    /**
     * Materialise the bundled Peergos .ico into peergosDir/peergos.ico if it isn't
     * already there. The .ico is shipped as a classpath resource at /peergos.ico
     * (build.xml copies it from web-ui/packager/winicon.ico into ${build}/peergos.ico).
     * Returns the on-disk path Windows can reference from the registry, or the existing
     * file if it was already there. Returns null if no bundled icon is available — the
     * caller then falls back to the Windows generic cloud icon.
     */
    private static Path ensureIconExtracted(Path peergosDir) {
        try {
            Files.createDirectories(peergosDir);
            Path target = peergosDir.resolve("peergos.ico");
            if (Files.exists(target)) return target;
            try (java.io.InputStream in = CloudFilesMount.class.getResourceAsStream("/peergos.ico")) {
                if (in == null) return null;
                Files.copy(in, target);
                return target;
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to extract bundled peergos.ico", e);
            return null;
        }
    }

    /** Overload that defaults the icon to Windows' generic cloud icon. */
    public static CloudFilesMount mount(UserContext context,
                                        String syncRootPath,
                                        Path stateDbPath) throws Exception {
        return mount(context, syncRootPath, stateDbPath, null);
    }

    /** Full overload — used by tests that want to put the state DB in their own tempdir.
     *  @param iconPath  Optional Windows .ico to use as the sync-root icon in File Explorer
     *                   (the badge next to "Peergos" in the tree and on the root folder).
     *                   If null OR the file doesn't exist, falls back to imageres.dll's
     *                   generic cloud icon. */
    public static CloudFilesMount mount(UserContext context,
                                        String syncRootPath,
                                        Path stateDbPath,
                                        Path iconPath) throws Exception {
        CfApi.load();

        Files.createDirectories(Path.of(syncRootPath));

        // Register the sync root with the Windows shell BEFORE calling CfRegisterSyncRoot.
        // Without HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Explorer\\SyncRootManager\\<id>
        // entries, the shell doesn't recognize our provider and CF silently drops hydration
        // deliveries (CfExecute returns S_OK but data never reaches the requesting process).
        // Discovered by comparing with Nextcloud's createSyncRootRegistryKeys.
        String syncRootId = registerSyncRootInShell(syncRootPath, context.username, iconPath);
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
        // PROGRESSIVE (not FULL) so File Explorer's "Free up space" menu item is enabled
        // on hydrated, in-sync placeholders: FULL/ALWAYS_FULL pin local content in place
        // and grey the menu out. CF itself handles the dehydration (zeroes the on-disk
        // stream, keeps the metadata) — the canonical bytes already live on peergos, so
        // we don't need a NOTIFY_DEHYDRATE callback to validate the request.
        policies.set(ValueLayout.JAVA_SHORT, CfApi.POLICIES_HYDRATION_OFF,    CfApi.CF_HYDRATION_POLICY_PROGRESSIVE);
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

        // -- Sync state DB --
        // Lives outside the sync root so CF doesn't manage it. Path default:
        // ~/.peergos/cf-state-<hash16>.db (see defaultStateDbPath).
        Files.createDirectories(stateDbPath.getParent());
        peergos.server.sync.SyncState syncState =
                new peergos.server.sync.JdbcTreeState(stateDbPath.toString());
        LOG.info("CF sync state DB: " + stateDbPath);

        // -- Callbacks --
        CloudFilesProvider provider = new CloudFilesProvider(context, syncRootPath, syncState);

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

        // Proactively materialise the $username folder placeholder under the sync root
        // so external listings don't have to round-trip through FETCH_PLACEHOLDERS first.
        // Without this, Get-ChildItem on the sync root races against the asynchronous
        // creation of the $user dir, which surfaces as flaky CI failures where the
        // subsequent listing of $user/ comes back empty.
        try (Arena seedArena = Arena.ofConfined()) {
            provider.seedRootPlaceholders(seedArena);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "seedRootPlaceholders failed (non-fatal)", e);
        }

        // CF only fires NOTIFY_FILE_CLOSE_COMPLETION for placeholders it already tracks.
        // For files created locally inside the sync root by other processes (e.g. Copy-Item),
        // we get no callback at all — so run a WatchService to detect new/modified files and
        // upload them to Peergos. Debounce a little so we don't fire on every byte of a write.
        java.nio.file.WatchService ws = java.nio.file.FileSystems.getDefault().newWatchService();
        Path syncRootP = Path.of(syncRootPath);
        // The watcher registers the sync root and every existing subdir on startup, and
        // every newly-created subdir as it appears — see registerRecursive() in runWatcher.
        // The actual uploads run on a small pool so a slow upload doesn't block the watcher
        // from draining new ENTRY_CREATE/MODIFY events.
        int uploadParallelism = 4;
        java.util.concurrent.ExecutorService uploadExec = java.util.concurrent.Executors.newFixedThreadPool(
                uploadParallelism,
                r -> {
                    Thread t = new Thread(r, "CF upload worker");
                    t.setDaemon(true);
                    return t;
                });
        CloudFilesMount[] holder = new CloudFilesMount[1];
        Thread wt = new Thread(() -> runWatcher(ws, syncRootP, provider, uploadExec,
                () -> holder[0] != null && holder[0].watcherRunning),
                "CF local file watcher");
        wt.setDaemon(true);

        // Pull-loop scheduler. Detects remote-only edits on a 30-second tick.
        // Snapshot-equality check makes idle mounts essentially free; only when
        // a writer in scope moves do we walk individual paths.
        java.util.concurrent.ScheduledExecutorService pullSched =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "CF pull loop");
                    t.setDaemon(true);
                    return t;
                });
        pullSched.scheduleWithFixedDelay(provider::runPullTick, 30, 30, java.util.concurrent.TimeUnit.SECONDS);

        CloudFilesMount m = new CloudFilesMount(syncRootPath, connectionKey, callbackArena, wt, ws, uploadExec, pullSched, syncState, provider);
        holder[0] = m;
        wt.start();
        Runtime.getRuntime().addShutdownHook(new Thread(m::close, "CF unmount"));
        return m;
    }

    private static void runWatcher(java.nio.file.WatchService ws, Path syncRoot,
                                   CloudFilesProvider provider,
                                   java.util.concurrent.ExecutorService uploadExec,
                                   java.util.function.BooleanSupplier running) {
        // Coalesce bursts of write events: each path's upload kicks off only once size+mtime
        // have stayed stable for STABLE_MS, so we don't repeatedly upload a partially-written
        // file. After upload the path is removed from `inflight` so subsequent edits trigger
        // another upload — content-fingerprint dedup happens in CloudFilesProvider.
        long STABLE_MS = 750;
        java.util.Map<Path, Long> pending = new java.util.concurrent.ConcurrentHashMap<>();
        // Tracks which paths currently have an upload running so we don't double-submit
        // while a worker thread is busy. Bounded — uses peergos's LRU cache.
        java.util.Map<Path, Boolean> inflight = java.util.Collections.synchronizedMap(
                new peergos.shared.util.LRUCache<>(1024));
        // Recurse: register existing subdirs and any new ones the watcher discovers, so
        // edits inside subdir/ also fire CREATE/MODIFY.
        registerRecursive(ws, syncRoot);
        // Re-register placeholder dirs we populated in previous mounts. registerRecursive
        // SKIP_SUBTREEs every placeholder dir (descending would trigger same-process
        // FETCH_PLACEHOLDERS), and on a remount no CREATE/MODIFY ever fires for $user/
        // on syncRoot's watch — seedRootPlaceholders is a no-op when $user/ is already
        // on disk — so the lazy chain that worked on first mount never starts. Register
        // each known-populated dir explicitly via syncState.getDirs(). The register call
        // opens a ReadDirectoryChangesW handle without enumerating, so it doesn't fire
        // FETCH_PLACEHOLDERS.
        for (String relPath : provider.getKnownDirs()) {
            Path d = syncRoot.resolve(relPath.replace('/', java.io.File.separatorChar));
            if (!Files.isDirectory(d)) continue;
            try {
                d.register(ws,
                        java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                        java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY);
                System.err.println("[CF] watcher: re-registered (syncState) " + d);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "watcher: failed to re-register " + d, e);
            }
        }
        System.err.println("[CF] watcher: started, polling on syncRoot=" + syncRoot);
        while (running.getAsBoolean()) {
            java.nio.file.WatchKey key;
            try { key = ws.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            if (key != null) {
                // key.watchable() returns the Path that was used at register time. Windows'
                // ReadDirectoryChangesW follows the inode across renames so the watch keeps
                // firing, but the WatchKey's watchable Path object is frozen — for a dir we
                // registered as "foo" that the user has since renamed to "bar", events still
                // arrive with dir=foo, which resolve() turns into a non-existent path.
                // currentDirPath looks up rename(s) recorded by onRenameCompletionPlaceholder.
                Path watchableDir = (Path) key.watchable();
                Path dir = provider.currentDirPath(watchableDir);
                for (java.nio.file.WatchEvent<?> ev : key.pollEvents()) {
                    Object ctx = ev.context();
                    System.err.println("[CF] watcher: event " + ev.kind().name()
                            + " ctx=" + ctx + " dir=" + dir
                            + (watchableDir.equals(dir) ? "" : " (was " + watchableDir + ")"));
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
                            // Debounce dir uploads identically to files: if CF NOTIFY_RENAME
                            // is in flight on this dir (the standard Explorer "New > Folder
                            // → type name" produces a CREATE for the new name racing the CF
                            // rename callback), uploadLocalFile would otherwise create the
                            // renamed dir in peergos before the rename API call could land,
                            // causing "child already exists" and leaving both names in peergos.
                            // The debounce gives NOTIFY_RENAME_COMPLETION time to finish so
                            // uploadLocalFile's getByPath sees the renamed entry and is a no-op.
                            pending.put(full, System.currentTimeMillis() + STABLE_MS);
                        } else {
                            // Always enqueue files — don't gate on size. Copy-Item / Explorer
                            // copy often goes create-then-write, so ENTRY_CREATE fires with
                            // size=0 and gating here drops the file if the following MODIFY
                            // events get coalesced or don't propagate (which we've seen on
                            // CF placeholder dirs). The debounce timestamp keeps re-bumping
                            // on each MODIFY, and uploadLocalFile filters 0-byte files itself.
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
                // If an upload is already running for this path, leave the entry in pending —
                // a fresh modify event during the upload should retrigger another upload
                // once the worker clears inflight. We do NOT remove + drop here.
                if (inflight.containsKey(p)) continue;
                // Parents must land in peergos before their children, otherwise the child's
                // uploadLocalFile finds the parent missing and drops the file. Defer this
                // entry if any ancestor (up to syncRoot) is still pending or in-flight.
                if (ancestorPendingOrInflight(p, syncRoot, pending, inflight)) {
                    e.setValue(now + 250);
                    continue;
                }
                it.remove();
                inflight.put(p, Boolean.TRUE);
                try {
                    uploadExec.submit(() -> {
                        try { provider.uploadLocalFile(p); }
                        catch (Exception ex) { LOG.log(Level.WARNING, "watcher upload failed: " + p, ex); }
                        finally { inflight.remove(p); }
                    });
                } catch (java.util.concurrent.RejectedExecutionException rej) {
                    // Executor shut down — clear inflight and stop.
                    inflight.remove(p);
                    return;
                }
            }
        }
    }

    private static boolean ancestorPendingOrInflight(Path p, Path syncRoot,
                                                     java.util.Map<Path, Long> pending,
                                                     java.util.Map<Path, Boolean> inflight) {
        Path parent = p.getParent();
        while (parent != null && parent.startsWith(syncRoot) && !parent.equals(syncRoot)) {
            // Skip sync-root-level parents (the $user/ folder). uploadLocalFile rejects
            // them outright, so them being in pending/inflight does NOT mean we're racing
            // a real upload — but MODIFY events for $user/ fire repeatedly as CF does its
            // placeholder bookkeeping, and a strict check here would defer every child
            // file's upload indefinitely.
            Path rel = syncRoot.relativize(parent);
            if (rel.getNameCount() > 1
                    && (pending.containsKey(parent) || inflight.containsKey(parent)))
                return true;
            parent = parent.getParent();
        }
        return false;
    }

    /**
     * Register {@code root} and every existing non-placeholder subdirectory with the
     * WatchService.
     *
     * Placeholder dirs are still registered (so we catch CREATE events for their
     * direct children) but we SKIP_SUBTREE there: descending would call
     * Files.newDirectoryStream on the dir, which triggers FETCH_PLACEHOLDERS for
     * unpopulated placeholders, which we then fail same-process. Repeated failures
     * have been observed to leave Explorer's view of the dir empty, so we avoid them.
     * The trade-off: locally-dropped files inside a placeholder dir created in a
     * previous session aren't watched until that dir's parent fires a fresh CREATE
     * (or until something else re-registers it).
     */
    private static void registerRecursive(java.nio.file.WatchService ws, Path root) {
        try {
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    try {
                        dir.register(ws,
                                java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                                java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY);
                        System.err.println("[CF] watcher: registered " + dir
                                + " (placeholder=" + CfApi.isPlaceholder(dir) + ")");
                    } catch (IOException e) {
                        LOG.log(Level.WARNING, "watcher: failed to register " + dir, e);
                    }
                    return CfApi.isPlaceholder(dir)
                            ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                            : java.nio.file.FileVisitResult.CONTINUE;
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
        // Drain in-flight uploads so they finish before we disconnect from CF.
        if (uploadExecutor != null) {
            uploadExecutor.shutdown();
            try { uploadExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        // Stop the pull-loop scheduler.
        if (pullScheduler != null) {
            pullScheduler.shutdown();
            try { pullScheduler.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        try {
            int hr = CfApi.cfDisconnectSyncRoot(connectionKey);
            if (hr != CfApi.S_OK)
                LOG.warning("CfDisconnectSyncRoot returned 0x" + Integer.toHexString(hr));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error disconnecting CF sync root", e);
        } finally {
            try { callbackArena.close(); } catch (Exception ignored) {}
            if (syncState != null)
                try { syncState.close(); } catch (Exception ignored) {}
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
     * Default state-DB path. Lives in ~/.peergos so CF never sees or manages it,
     * and the watcher inside the sync root never picks it up. Keyed per-mount with
     * the first 16 hex chars of SHA-256(normalised syncRootPath).
     */
    public static Path defaultStateDbPath(Path peergosDir, String syncRootPath) {
        try {
            Path normalised = Path.of(syncRootPath).toAbsolutePath().normalize();
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalised.toString()
                    .toLowerCase(java.util.Locale.ROOT)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i] & 0xff));
            Files.createDirectories(peergosDir);
            return peergosDir.resolve("cf-state-" + hex + ".db");
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute default state DB path", e);
        }
    }

    /**
     * Returns the syncRootId we registered.
     */
    private static String registerSyncRootInShell(String syncRootPath, String accountName,
                                                  Path iconPath) throws Exception {
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
        // - IconResource (REG_EXPAND_SZ)  — path to a Windows .ico file with optional ",index"
        // - UserSyncRoots\<SID> (REG_SZ, value = sync root path)
        regAdd(baseKey, "Flags", "REG_DWORD", "0x14");
        regAdd(baseKey, "DisplayNameResource", "REG_EXPAND_SZ", "Peergos");
        // Prefer the Peergos icon when one is bundled in peergosDir. The path syntax
        // "<file>,0" picks resource index 0 from the .ico container (sized variants
        // are auto-selected by Explorer at draw time). Falls back to the Windows
        // generic cloud icon if no .ico was bundled or the file is missing.
        String iconResource;
        if (iconPath != null && Files.exists(iconPath)) {
            iconResource = iconPath.toString() + ",0";
        } else {
            iconResource = System.getenv("SystemRoot") + "\\System32\\imageres.dll,-1043";
        }
        regAdd(baseKey, "IconResource", "REG_EXPAND_SZ", iconResource);
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
