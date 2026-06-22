package peergos.server.tests;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import peergos.server.*;
import peergos.server.cfapi.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.IOException;
import java.lang.foreign.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.Assert.*;

public class CfApiTests {

    private static final String PASSWORD = "cfapipassword123";
    private static int WEB_PORT;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static boolean isWindowsDesktop() {
        String osName = System.getProperty("os.name", "").toLowerCase();

        return osName.startsWith("windows")
                && !osName.contains("server");
    }
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    @BeforeClass
    public static void startServer() throws Exception {
        if (!isWindowsDesktop()) return;
        Args args = UserTests.buildArgs().with("useIPFS", "false");
        WEB_PORT = args.getInt("port");
        Main.PKI_INIT.main(args);
    }

    // -----------------------------------------------------------------------
    // Platform-independent tests (always run)
    // -----------------------------------------------------------------------

    @Test
    public void versionCheckReturnsFalseOnNonWindows() {
        if (isWindows()) return;
        assertFalse(WindowsVersionCheck.isCfApiAvailable());
    }

    @Test
    public void wideStringRoundTrip() {
        String[] cases = {"", "hello", "Peergos", "/username/Documents/file.txt", "cafe"};
        try (Arena arena = Arena.ofConfined()) {
            for (String s : cases) {
                MemorySegment seg = CfApi.wideString(s, arena);
                MemorySegment info = arena.allocate(CfApi.CBI_NORMALIZED_PATH_OFF + 8);
                info.set(ValueLayout.JAVA_LONG, CfApi.CBI_NORMALIZED_PATH_OFF, seg.address());
                assertEquals("Round-trip failed for: " + s, s,
                        CfApi.readWideString(info, CfApi.CBI_NORMALIZED_PATH_OFF));
            }
        }
    }

    @Test
    public void fileTimeConversion() {
        LocalDateTime epoch = LocalDateTime.of(1970, 1, 1, 0, 0, 0);
        assertEquals(116_444_736_000_000_000L, CfApi.toFileTime(epoch));
        assertEquals(0L, CfApi.toFileTime(null));
        LocalDateTime ts = LocalDateTime.of(2001, 9, 9, 1, 46, 40);
        long expected = (1_000_000_000L + 11_644_473_600L) * 10_000_000L;
        assertEquals(expected, CfApi.toFileTime(ts));
    }

    @Test
    public void structSizeConstants() {
        assertTrue(CfApi.REG_SIZE      >= 48);
        assertEquals(24, CfApi.POLICIES_SIZE);
        assertEquals(88, CfApi.PCI_SIZE);
        assertEquals(48, CfApi.OI_SIZE);
        assertEquals(16, CfApi.CBR_ENTRY_SIZE);
        assertTrue(CfApi.PCI_FS_METADATA_OFF + CfApi.FSM_FILE_SIZE_OFF + 8 <= CfApi.PCI_FILE_IDENTITY_OFF);
    }

    // -----------------------------------------------------------------------
    // Windows-only tests
    // -----------------------------------------------------------------------

    @Test
    public void loadCfapiDll() {
        if (!isWindowsDesktop()) return;
        CfApi.load(); // must not throw
    }

    @Test
    public void registerAndUnregisterSyncRoot() throws Exception {
        if (!isWindowsDesktop()) return;
        CfApi.load();
        Path syncRoot = tmp.newFolder("peergos-cfapi-test").toPath();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathW    = CfApi.wideString(syncRoot.toString(), arena);
            MemorySegment reg      = buildRegistration(arena);
            MemorySegment policies = buildPolicies(arena);
            CfApi.cfUnregisterSyncRoot(pathW); // clear any stale registration
            int hr = CfApi.cfRegisterSyncRoot(pathW, reg, policies, CfApi.CF_REGISTER_FLAG_NONE);
            assertEquals("CfRegisterSyncRoot", CfApi.S_OK, hr);
            hr = CfApi.cfUnregisterSyncRoot(pathW);
            assertEquals("CfUnregisterSyncRoot", CfApi.S_OK, hr);
        }
    }

    @Test
    public void mountLifecyclePlaceholdersAppear() throws Exception {
        if (!isWindowsDesktop()) return;
        NetworkAccess network = Builder.buildLocalJavaNetworkAccess(WEB_PORT).join();
        Crypto crypto = Main.initCrypto();
        String user = "cfapi" + UUID.randomUUID().toString().substring(0, 8);
        UserContext context = UserContext.signUp(user, PASSWORD, "", network, crypto).join();

        byte[] content = "Hello from Peergos CF API test".getBytes(StandardCharsets.UTF_8);
        byte[] large = new byte[11*1024*1024];
        for (int i=0; i < large.length; i += content.length) {
            System.arraycopy(content, 0, large, i, Math.min(content.length, large.length - i));
        }

        Path syncRoot = tmp.newFolder("peergos-cf-" + user).toPath();
        Path userRoot = syncRoot.resolve(user);
        Path stateDb = tmp.newFolder("peergos-cf-state-" + user).toPath().resolve("state.db");

        // Round 1 — fresh mount, exercise the full sequence with "r1-" prefixed names.
        // CloudFilesMount.mount() is the same entry point MountConfigHandler uses in
        // production: it cfRegisterSyncRoot + cfConnectSyncRoot, seeds the $user/
        // placeholder, starts the local WatchService thread and the remote pull-tick
        // scheduler. The test exercises end-to-end against the full stack.
        CloudFilesMount mount1 = CloudFilesMount.mount(context, syncRoot.toString(), stateDb);
        try {
            runMountTestSequence(mount1, context, network, crypto, userRoot, "r1", content, large);
        } finally {
            mount1.close();
        }

        // Inter-round sanity: the state we left in peergos and on disk should survive
        // an unmount. Re-check before remounting so a regression here points at the
        // closed-mount path rather than the remount path.
        // - r1-subdir was mkdir'd in step 4 and survives (only its children moved).
        // - r1-uploaded.bin was copied in step 3, edited in step 7, and not removed.
        // - r1-renamed.txt was removed remotely in step 9, so it must NOT be there.
        // - r1-world.txt was removed locally in step 8.
        assertTrue("r1 subdir should still be in peergos after unmount",
                context.getByPath("/" + user + "/r1-subdir").join().isPresent());
        assertTrue("r1 uploaded.bin should still be in peergos after unmount",
                context.getByPath("/" + user + "/r1-uploaded.bin").join().isPresent());
        assertFalse("r1 renamed.txt was remotely deleted in step 9, must be gone",
                context.getByPath("/" + user + "/r1-renamed.txt").join().isPresent());
        assertFalse("r1 world.txt was locally deleted in step 8, must be gone",
                context.getByPath("/" + user + "/r1-world.txt").join().isPresent());
        assertTrue("r1 subdir should still be on disk after unmount",
                Files.exists(userRoot.resolve("r1-subdir")));
        assertFalse("r1 renamed.txt should be off disk after step 9's pull-tick delete",
                Files.exists(userRoot.resolve("r1-renamed.txt")));

        // Round 2 — remount the same syncRoot and stateDb. The persisted state DB
        // means the new mount inherits round 1's known-synced versions; the on-disk
        // placeholders for r1 files are still there. Run the full sequence again with
        // "r2-" prefixed names to verify the second mount handles every operation
        // (listing, FETCH_DATA, upload, mkdir, rename, move, in-place edit, delete)
        // independently of the first.
        CloudFilesMount mount2 = CloudFilesMount.mount(context, syncRoot.toString(), stateDb);
        try {
            runMountTestSequence(mount2, context, network, crypto, userRoot, "r2", content, large);
        } finally {
            mount2.close();
        }
    }

    /**
     * Runs the full file/dir operation sequence (listing, read, upload, mkdir, rename,
     * move, in-place edit, delete) against an already-mounted sync root. Uses the
     * {@code prefix} to namespace every fixture so the sequence can be run repeatedly
     * across a mount/remount cycle on the same on-disk sync root.
     *
     * Steps:
     * <ol>
     *   <li>Seed three Peergos fixtures via UserContext: prefix-hello.txt,
     *       prefix-world.txt, prefix-large.txt (the mount picks them up via
     *       FETCH_PLACEHOLDERS on the listing in step 1).</li>
     *   <li>External Get-ChildItem to force-populate the placeholders.</li>
     *   <li>Read prefix-hello.txt and prefix-large.txt via Get-Content (FETCH_DATA).</li>
     *   <li>Copy an 11 MB file in from outside (CLOSE_COMPLETION → upload).</li>
     *   <li>mkdir prefix-subdir (watcher → createPeergosDirectory + convertToPlaceholder).</li>
     *   <li>Rename prefix-hello.txt → prefix-renamed.txt (CF NOTIFY_RENAME).</li>
     *   <li>Move prefix-large.txt → prefix-subdir/prefix-large.txt (CF NOTIFY_RENAME).</li>
     *   <li>In-place overwrite first bytes of prefix-uploaded.bin (CLOSE_COMPLETION re-upload).</li>
     *   <li>Delete prefix-world.txt (CF NOTIFY_DELETE).</li>
     * </ol>
     */
    private void runMountTestSequence(CloudFilesMount mount, UserContext context,
                                      NetworkAccess network, Crypto crypto,
                                      Path userRoot, String prefix,
                                      byte[] content, byte[] large) throws Exception {
        String helloName  = prefix + "-hello.txt";
        String worldName  = prefix + "-world.txt";
        String largeName  = prefix + "-large.txt";
        String uploadedName = prefix + "-uploaded.bin";
        String subdirName = prefix + "-subdir";
        String renamedName = prefix + "-renamed.txt";

        // Seed fixtures directly via the UserContext (bypassing the mount) so the
        // pull tick / FETCH_PLACEHOLDERS pick them up as remote-only files.
        FileWrapper home = context.getByPath("/" + context.username).join().get();
        home.uploadOrReplaceFile(helloName, new AsyncReader.ArrayBacked(content), content.length,
                context.network, context.crypto, () -> false, l -> {}).join();
        home.getUpdated(network).join().uploadOrReplaceFile(worldName, new AsyncReader.ArrayBacked(content), content.length,
                context.network, context.crypto, () -> false, l -> {}).join();
        home.getUpdated(network).join().uploadOrReplaceFile(largeName, new AsyncReader.ArrayBacked(large), large.length,
                context.network, context.crypto, () -> false, l -> {}).join();

        // Force a pull tick so the remote-only fixtures we just uploaded land as local
        // placeholders. On the first mount of a session this is a no-op for $user/
        // (CF will fire FETCH_PLACEHOLDERS as soon as we list it), but on a remount CF
        // has already set DISABLE_ON_DEMAND_POPULATION from the previous round, so the
        // listing won't trigger another FETCH_PLACEHOLDERS — discoverNewRemoteChildren
        // in the pull tick is the only path that surfaces newly-added remote files.
        mount.forcePullTick();

        // Step 1 — external directory listing of $user/. On the first mount this fires
        // FETCH_PLACEHOLDERS from an external PID and our provider responds with
        // CfExecute(TRANSFER_PLACEHOLDERS) to create the physical placeholders. On a
        // remount the dir is already DISABLE_ON_DEMAND_POPULATION; the forcePullTick
        // above is what surfaced the new fixtures.
        String userRootPs = userRoot.toString().replace("'", "''");
        {
            System.err.println("[TEST] " + prefix + ": Starting external Get-ChildItem on " + userRoot);
            ProcessBuilder lb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-Command",
                    "Get-ChildItem -LiteralPath '" + userRootPs + "' -Name | Out-String");
            lb.redirectErrorStream(true);
            Process lp = lb.start();
            String listing = new String(lp.getInputStream().readAllBytes());
            boolean ldone = lp.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            System.err.println("[TEST] " + prefix + ": dir listing (exit="
                    + (ldone ? lp.exitValue() : "TIMEOUT") + "): " + listing.trim());
            assertTrue("External dir listing timed out", ldone);
            assertEquals("External dir listing failed", 0, lp.exitValue());
            assertTrue(helloName + " should appear in external listing", listing.contains(helloName));
            assertTrue(worldName + " should appear in external listing", listing.contains(worldName));
        }

        // Step 2 — read prefix-hello.txt via PowerShell Get-Content to trigger FETCH_DATA
        // from an external PID and verify the content reaches the caller intact. We use
        // Get-Content rather than Copy-Item because it's a simpler read path with fewer
        // Win32 layers in between.
        {
            String srcPath = userRoot.resolve(helloName).toString().replace("'", "''");
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-Command",
                    "Get-Content -LiteralPath '" + srcPath + "' -Raw -ErrorAction Stop");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String psOutput = new String(proc.getInputStream().readAllBytes());
            boolean done = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue("Get-Content timed out", done);
            assertEquals("Get-Content failed (output: " + psOutput.trim() + ")",
                    0, proc.exitValue());
            assertEquals("File content must match what was uploaded",
                    new String(content).trim(), psOutput.trim());
        }

        // Step 2b — read prefix-large.txt
        {
            String srcPath = userRoot.resolve(largeName).toString().replace("'", "''");
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-Command",
                    "Get-Content -LiteralPath '" + srcPath + "' -Raw -ErrorAction Stop");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            byte[] psBytes = proc.getInputStream().readAllBytes();
            boolean done = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue("Get-Content timed out", done);
            assertEquals("Get-Content failed", 0, proc.exitValue());
            assertEquals("File content must match what was uploaded",
                    new String(large).trim(), new String(psBytes).trim());
        }

        // Step 3 — write a new 11MB file INTO the mount from an external process and
        // verify it gets uploaded back to Peergos. We stage the content in a temp file
        // outside the sync root, then Copy-Item it in. Closing the file triggers
        // NOTIFY_FILE_CLOSE_COMPLETION, which reads the local bytes and uploads them.
        {
            byte[] upload = new byte[11 * 1024 * 1024];
            for (int i = 0; i < upload.length; i++)
                upload[i] = (byte) (i * 31 + 7);
            Path stagedFile = tmp.newFile(prefix + "-upload-source.bin").toPath();
            Files.write(stagedFile, upload);

            String destPath = userRoot.resolve(uploadedName).toString().replace("'", "''");
            String srcPath  = stagedFile.toString().replace("'", "''");
            System.err.println("[TEST] " + prefix + ": Copy-Item " + srcPath + " -> " + destPath);
            ProcessBuilder cp = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-Command",
                    "Copy-Item -LiteralPath '" + srcPath + "' -Destination '" + destPath
                            + "' -ErrorAction Stop");
            cp.redirectErrorStream(true);
            Process cproc = cp.start();
            String cpOutput = new String(cproc.getInputStream().readAllBytes());
            boolean cdone = cproc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue("Copy-Item timed out", cdone);
            assertEquals("Copy-Item failed (output: " + cpOutput.trim() + ")",
                    0, cproc.exitValue());
            System.err.println("[TEST] " + prefix + ": Copy-Item exit=" + cproc.exitValue()
                    + " destOnDisk=" + Files.exists(userRoot.resolve(uploadedName))
                    + " destSize=" + (Files.exists(userRoot.resolve(uploadedName))
                            ? Files.size(userRoot.resolve(uploadedName)) : -1));

            String peergosPath = "/" + context.username + "/" + uploadedName;
            FileWrapper uploaded = null;
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                Optional<FileWrapper> opt = context.getByPath(peergosPath).join();
                if (opt.isPresent() && opt.get().getSize() == upload.length) {
                    uploaded = opt.get();
                    break;
                }
                Thread.sleep(500);
            }
            assertNotNull(uploadedName + " never appeared in Peergos", uploaded);
            assertEquals("uploaded file size mismatch", upload.length, uploaded.getSize());

            byte[] roundTripped = Serialize.readFully(uploaded, crypto, network).join();
            assertArrayEquals(uploadedName + " content mismatch after round trip",
                    upload, roundTripped);
        }

        // Step 4 — mkdir inside the mount and verify the directory shows up in Peergos.
        {
            String subdirLocal = userRoot.resolve(subdirName).toString().replace("'", "''");
            int exit = runPs("New-Item -ItemType Directory -Path '" + subdirLocal
                    + "' -Force | Out-Null");
            assertEquals("New-Item mkdir failed", 0, exit);
            waitFor(60_000, () -> {
                Optional<FileWrapper> d = context.getByPath(
                        "/" + context.username + "/" + subdirName).join();
                return d.isPresent() && d.get().isDirectory();
            });
            Optional<FileWrapper> d = context.getByPath(
                    "/" + context.username + "/" + subdirName).join();
            assertTrue(subdirName + " should be a directory in Peergos",
                    d.isPresent() && d.get().isDirectory());
        }

        // Step 4b — copy a NEW file into the just-mkdir'd subdir. Exercises the
        // "local mkdir + drop a file inside" flow: the watcher must register the
        // new subdir for events as soon as it sees the CREATE for it on $user/'s
        // watch, and then the child file's CREATE must fire on subdir's watch and
        // reach uploadLocalFile after subdir's own upload+convertToPlaceholder has
        // landed in peergos (so the file's parent lookup succeeds).
        String inSubdirName = prefix + "-insub.bin";
        {
            byte[] insub = new byte[256 * 1024];
            for (int i = 0; i < insub.length; i++)
                insub[i] = (byte) (i * 13 + 5);
            Path stagedInSub = tmp.newFile(prefix + "-insub-source.bin").toPath();
            Files.write(stagedInSub, insub);
            String destPath = userRoot.resolve(subdirName).resolve(inSubdirName).toString()
                    .replace("'", "''");
            String srcPath  = stagedInSub.toString().replace("'", "''");
            System.err.println("[TEST] " + prefix + ": Copy-Item into new subdir "
                    + srcPath + " -> " + destPath);
            int exit = runPs("Copy-Item -LiteralPath '" + srcPath + "' -Destination '"
                    + destPath + "' -ErrorAction Stop");
            assertEquals("Copy-Item into subdir failed", 0, exit);

            String peergosPath = "/" + context.username + "/" + subdirName + "/" + inSubdirName;
            FileWrapper landed = null;
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                Optional<FileWrapper> opt = context.getByPath(peergosPath).join();
                if (opt.isPresent() && opt.get().getSize() == insub.length) {
                    landed = opt.get();
                    break;
                }
                Thread.sleep(500);
            }
            assertNotNull(inSubdirName + " never appeared in Peergos under " + subdirName,
                    landed);
            byte[] roundTripped = Serialize.readFully(landed, crypto, network).join();
            assertArrayEquals(inSubdirName + " content mismatch after round trip",
                    insub, roundTripped);
        }

        // Step 4c — mimic Explorer's "New > Folder → type name" flow: mkdir a
        // throwaway folder, rename it to the intended name, then drop a file in.
        // The watcher registers the throwaway folder's WatchKey on its CREATE
        // event; on Windows the rename leaves WatchKey.watchable() pointing at
        // the old (now non-existent) path, so the file's CREATE event resolves
        // to a path that fails Files.exists and the event is silently dropped.
        String renamedSubName  = prefix + "-renamedsub";
        String renamedInSubName = prefix + "-renamed-insub.bin";
        {
            String tmpName = prefix + "-tmpsub";
            String tmpLocal = userRoot.resolve(tmpName).toString().replace("'", "''");
            int mk = runPs("New-Item -ItemType Directory -Path '" + tmpLocal
                    + "' -Force | Out-Null");
            assertEquals("New-Item tmpsub failed", 0, mk);
            waitFor(60_000, () -> context.getByPath(
                    "/" + context.username + "/" + tmpName).join().isPresent());

            int rn = runPs("Rename-Item -LiteralPath '" + tmpLocal
                    + "' -NewName '" + renamedSubName + "' -ErrorAction Stop");
            assertEquals("Rename of tmpsub failed", 0, rn);
            waitFor(60_000, () -> {
                Optional<FileWrapper> r = context.getByPath(
                        "/" + context.username + "/" + renamedSubName).join();
                Optional<FileWrapper> o = context.getByPath(
                        "/" + context.username + "/" + tmpName).join();
                return r.isPresent() && o.isEmpty();
            });

            byte[] payload = new byte[128 * 1024];
            for (int i = 0; i < payload.length; i++)
                payload[i] = (byte) (i * 17 + 3);
            Path stagedRen = tmp.newFile(prefix + "-renamed-insub-source.bin").toPath();
            Files.write(stagedRen, payload);
            String destPath = userRoot.resolve(renamedSubName).resolve(renamedInSubName)
                    .toString().replace("'", "''");
            String srcPath = stagedRen.toString().replace("'", "''");
            System.err.println("[TEST] " + prefix
                    + ": Copy-Item into renamed subdir " + srcPath + " -> " + destPath);
            int cp = runPs("Copy-Item -LiteralPath '" + srcPath + "' -Destination '"
                    + destPath + "' -ErrorAction Stop");
            assertEquals("Copy-Item into renamed subdir failed", 0, cp);

            String peergosPath = "/" + context.username + "/" + renamedSubName
                    + "/" + renamedInSubName;
            FileWrapper landed = null;
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                Optional<FileWrapper> opt = context.getByPath(peergosPath).join();
                if (opt.isPresent() && opt.get().getSize() == payload.length) {
                    landed = opt.get();
                    break;
                }
                Thread.sleep(500);
            }
            assertNotNull(renamedInSubName + " never appeared under " + renamedSubName,
                    landed);
            byte[] roundTripped = Serialize.readFully(landed, crypto, network).join();
            assertArrayEquals(renamedInSubName + " content mismatch after round trip",
                    payload, roundTripped);
        }

        // Rename / move / delete need CF-managed placeholders so cldapi fires the
        // NOTIFY_RENAME / NOTIFY_DELETE callbacks our provider already handles. The
        // three fixture files were created via uploadOrReplaceFile + TRANSFER_PLACEHOLDERS,
        // so they're real placeholders; the uploaded.bin from step 3 is too (its
        // post-upload convertToPlaceholder runs in uploadLocalFile).

        // Step 5 — rename prefix-hello.txt to prefix-renamed.txt.
        {
            String src = userRoot.resolve(helloName).toString().replace("'", "''");
            int exit = runPs("Rename-Item -LiteralPath '" + src
                    + "' -NewName '" + renamedName + "' -ErrorAction Stop");
            assertEquals("Rename-Item failed", 0, exit);
            waitFor(60_000, () -> {
                Optional<FileWrapper> r = context.getByPath(
                        "/" + context.username + "/" + renamedName).join();
                Optional<FileWrapper> orig = context.getByPath(
                        "/" + context.username + "/" + helloName).join();
                return r.isPresent() && orig.isEmpty();
            });
            assertTrue(renamedName + " should exist in Peergos",
                    context.getByPath("/" + context.username + "/" + renamedName).join().isPresent());
            assertFalse(helloName + " should be gone from Peergos",
                    context.getByPath("/" + context.username + "/" + helloName).join().isPresent());
        }

        // Step 6 — move prefix-large.txt into prefix-subdir/ and verify the move in Peergos.
        {
            String src  = userRoot.resolve(largeName).toString().replace("'", "''");
            String dest = userRoot.resolve(subdirName).resolve(largeName).toString().replace("'", "''");
            int exit = runPs("Move-Item -LiteralPath '" + src
                    + "' -Destination '" + dest + "' -ErrorAction Stop");
            assertEquals("Move-Item failed", 0, exit);
            String newPath = "/" + context.username + "/" + subdirName + "/" + largeName;
            String oldPath = "/" + context.username + "/" + largeName;
            waitFor(60_000, () -> {
                Optional<FileWrapper> n = context.getByPath(newPath).join();
                Optional<FileWrapper> o = context.getByPath(oldPath).join();
                return n.isPresent() && o.isEmpty();
            });
            assertTrue(largeName + " should exist under " + subdirName + "/ in Peergos",
                    context.getByPath(newPath).join().isPresent());
            assertFalse(largeName + " should be gone from old location in Peergos",
                    context.getByPath(oldPath).join().isPresent());
        }

        // Step 7 — modify prefix-uploaded.bin in place (same size, different content).
        // Exercises the mtime-based dirty-bit path in onFileCloseCompletion: same size
        // means the cheap size-mismatch check doesn't fire, so the handler falls back
        // to comparing local mtime vs Peergos mtime to decide whether to upload.
        {
            String src = userRoot.resolve(uploadedName).toString().replace("'", "''");
            byte[] marker = ("PEERGOS_" + prefix + "_INPLACE").getBytes(StandardCharsets.UTF_8);
            StringBuilder bytesLit = new StringBuilder();
            for (int i = 0; i < marker.length; i++) {
                if (i > 0) bytesLit.append(',');
                bytesLit.append(marker[i] & 0xFF);
            }
            int exit = runPs(
                    "$bytes = [byte[]](" + bytesLit + "); " +
                    "$fs = [IO.File]::OpenWrite('" + src + "'); " +
                    "try { $fs.Seek(0, 'Begin') | Out-Null; $fs.Write($bytes, 0, $bytes.Length) } " +
                    "finally { $fs.Close() }");
            assertEquals("in-place modify failed", 0, exit);

            String peergosPath = "/" + context.username + "/" + uploadedName;
            long expectedSize = 11L * 1024 * 1024;
            waitFor(120_000, () -> {
                Optional<FileWrapper> u = context.getByPath(peergosPath).join();
                if (u.isEmpty() || u.get().getSize() != expectedSize) return false;
                try {
                    byte[] data = Serialize.readFully(u.get(), crypto, network).join();
                    return data.length >= marker.length
                            && java.util.Arrays.equals(
                                    java.util.Arrays.copyOfRange(data, 0, marker.length),
                                    marker);
                } catch (Exception e) { return false; }
            });
            FileWrapper updated = context.getByPath(peergosPath).join().orElseThrow();
            assertEquals(uploadedName + " size should be unchanged", expectedSize, updated.getSize());

            byte[] roundTripped = Serialize.readFully(updated, crypto, network).join();
            byte[] head = java.util.Arrays.copyOfRange(roundTripped, 0, marker.length);
            assertArrayEquals("marker should be at start of " + uploadedName + " in Peergos",
                    marker, head);
        }

        // Step 8 — delete prefix-world.txt and verify Peergos no longer has it.
        {
            String src = userRoot.resolve(worldName).toString().replace("'", "''");
            int exit = runPs("Remove-Item -LiteralPath '" + src + "' -Force -ErrorAction Stop");
            assertEquals("Remove-Item failed", 0, exit);
            String p = "/" + context.username + "/" + worldName;
            waitFor(60_000, () -> context.getByPath(p).join().isEmpty());
            assertTrue(worldName + " should be gone from Peergos",
                    context.getByPath(p).join().isEmpty());
        }

        // Step 9 — opposite direction: a delete that happens on the remote (another
        // device / web UI / second session) must remove the local materialised
        // placeholder. Sign in as a second context for the same user, remove
        // prefix-renamed.txt remotely, wait for the mount's own context to observe
        // the deletion, then force a pull tick. The pull loop's applyRemoteDelete
        // should Files.delete the local entry (its mtime/size still match the synced
        // baseline, since we only read it via Get-Content).
        {
            UserContext contextB = UserContext.signIn(context.username, PASSWORD,
                    req -> { throw new IllegalStateException("no MFA"); },
                    network, crypto).join();
            String remoteTarget = "/" + context.username + "/" + renamedName;
            Optional<FileWrapper> targetOnB = contextB.getByPath(remoteTarget).join();
            assertTrue("contextB should see " + renamedName, targetOnB.isPresent());
            Optional<FileWrapper> parentOnB = contextB.getByPath(
                    "/" + context.username).join();
            assertTrue("contextB should see $user/", parentOnB.isPresent());
            System.err.println("[TEST] " + prefix + ": remotely deleting " + renamedName);
            targetOnB.get().remove(parentOnB.get(), PathUtil.get(remoteTarget), contextB).join();

            // Wait for the mount's context to observe the remote deletion before we
            // force the pull tick — otherwise getByPath in applyRemoteDelete may still
            // return the stale entry from the local synchronizer cache.
            waitFor(60_000, () -> context.getByPath(remoteTarget).join().isEmpty());

            mount.forcePullTick();

            Path localPath = userRoot.resolve(renamedName);
            waitFor(60_000, () -> !Files.exists(localPath));
            assertFalse("local " + renamedName
                            + " should be removed after the remote delete",
                    Files.exists(localPath));
        }
    }

    /**
     * Verifies the conflict-handling path: a remote edit by another UserContext
     * happens concurrently with a local edit via the CF mount. Expected outcome
     * is that Peergos ends up with BOTH the remote-edited content under the
     * original name AND the local-edited content renamed to foo[conflict-0].txt.
     */
    @Test
    public void localAndRemoteEditConflict() throws Exception {
        if (!isWindowsDesktop()) return;
        NetworkAccess network = Builder.buildLocalJavaNetworkAccess(WEB_PORT).join();
        Crypto crypto = Main.initCrypto();
        String user = "cfapi" + UUID.randomUUID().toString().substring(0, 8);
        UserContext contextA = UserContext.signUp(user, PASSWORD, "", network, crypto).join();

        // 1) Context A: upload the initial file "AAAA..." (large enough to span >1 chunk
        //    so we exercise multi-chunk hashing in the conflict path).
        byte[] aBytes = new byte[7 * 1024 * 1024];
        java.util.Arrays.fill(aBytes, (byte) 'A');
        FileWrapper home = contextA.getByPath("/" + contextA.username).join().get();
        home.uploadOrReplaceFile("conflict.txt",
                new AsyncReader.ArrayBacked(aBytes), aBytes.length,
                contextA.network, contextA.crypto, () -> false, l -> {}).join();

        Path syncRoot = tmp.newFolder("peergos-cf-" + user).toPath();
        Path userRoot = syncRoot.resolve(user);
        Path stateDb  = tmp.newFolder("peergos-cf-state-" + user).toPath().resolve("state.db");
        CloudFilesMount mount = CloudFilesMount.mount(contextA, syncRoot.toString(), stateDb);
        try {
            // 2) Force the file placeholders to materialise on disk. The $user/ folder itself
            //    is seeded by the mount at startup, so we list straight into it.
            String userRootPs = userRoot.toString().replace("'", "''");
            assertEquals("userRoot listing failed", 0,
                    runPs("Get-ChildItem -LiteralPath '" + userRootPs + "' -Name | Out-String"));
            assertTrue("conflict.txt should be on disk",
                    Files.exists(userRoot.resolve("conflict.txt")));

            // 3) Hydrate it via Get-Content so the local placeholder has real bytes
            //    and our syncState records the synced baseline (A).
            String filePs = userRoot.resolve("conflict.txt").toString().replace("'", "''");
            int readExit = runPs("$null = Get-Content -LiteralPath '" + filePs + "' -Raw -ErrorAction Stop");
            assertEquals("hydrate read failed", 0, readExit);

            // 4) Sign in as Context B (same user, separate session) and overwrite Peergos
            //    with "BBBB..." — simulates a concurrent edit from another device.
            UserContext contextB = UserContext.signIn(user, PASSWORD,
                    req -> { throw new IllegalStateException("no MFA"); },
                    network, crypto).join();
            byte[] bBytes = new byte[7 * 1024 * 1024];
            java.util.Arrays.fill(bBytes, (byte) 'B');
            Optional<FileWrapper> existingOnB = contextB.getByPath(
                    "/" + user + "/conflict.txt").join();
            assertTrue("context B should see conflict.txt", existingOnB.isPresent());
            existingOnB.get().overwriteChangedChunks(
                    new AsyncReader.ArrayBacked(bBytes), bBytes.length,
                    contextB.network, contextB.crypto, l -> {}).join();

            // Wait for context A's synchronizer to actually observe context B's update.
            // Without this poll, the close handler below sees A's stale snapshot, decides
            // synced == remote (case A) and silently last-writer-wins over B's change. The
            // pull-loop daemon would normally close this gap; in production sync, the
            // 30-second tick window is when this race can bite. Here we force A to refresh
            // by waiting for the first byte of the file to read as 'B'.
            waitFor(60_000, () -> {
                Optional<FileWrapper> fw = contextA.getByPath(
                        "/" + user + "/conflict.txt").join();
                if (fw.isEmpty()) return false;
                try {
                    byte[] head = Serialize.readFully(fw.get(), crypto, network).join();
                    return head.length > 0 && head[0] == 'B';
                } catch (Exception e) { return false; }
            });

            // 5) From the mount side (Context A's view), overwrite the local file with
            //    "CCCC..." via an explicit OpenWrite + Write + Close sequence. Copy-Item
            //    -Force uses CopyFileEx underneath, which CF does NOT surface to the
            //    provider as a FILE_CLOSE_COMPLETION callback for the destination — so
            //    the conflict path never runs. The OpenWrite path does fire the callback
            //    (same pattern as the in-place modify in mountLifecyclePlaceholdersAppear).
            byte[] cBytes = new byte[7 * 1024 * 1024];
            java.util.Arrays.fill(cBytes, (byte) 'C');
            Path stagedC = tmp.newFile("conflict-c-source.bin").toPath();
            Files.write(stagedC, cBytes);
            int cpExit = runPs(
                    "$bytes = [IO.File]::ReadAllBytes('"
                            + stagedC.toString().replace("'", "''") + "'); " +
                    "$fs = [IO.File]::OpenWrite('" + filePs + "'); " +
                    "try { $fs.SetLength([int64]$bytes.Length); " +
                    "      $fs.Seek(0, 'Begin') | Out-Null; " +
                    "      $fs.Write($bytes, 0, $bytes.Length) } " +
                    "finally { $fs.Close() }");
            assertEquals("local in-place modify failed", 0, cpExit);

            // 6) Wait for conflict resolution to land in Peergos. Expected end state:
            //    /user/conflict.txt              = B  (remote pulled into original name)
            //    /user/conflict[conflict-0].txt  = C  (local renamed and re-uploaded)
            String conflictPath = "/" + user + "/conflict[conflict-0].txt";
            String origPath     = "/" + user + "/conflict.txt";
            waitFor(120_000, () -> {
                Optional<FileWrapper> orig = contextA.getByPath(origPath).join();
                Optional<FileWrapper> ren  = contextA.getByPath(conflictPath).join();
                return orig.isPresent() && ren.isPresent()
                        && orig.get().getSize() == bBytes.length
                        && ren.get().getSize()  == cBytes.length;
            });

            FileWrapper finalOrig = contextA.getByPath(origPath).join().orElseThrow();
            FileWrapper finalRen  = contextA.getByPath(conflictPath).join().orElseThrow();
            byte[] origRound = Serialize.readFully(finalOrig, crypto, network).join();
            byte[] renRound  = Serialize.readFully(finalRen,  crypto, network).join();

            assertEquals("original name should hold B's content size",
                    bBytes.length, origRound.length);
            assertEquals("conflict copy should hold C's content size",
                    cBytes.length, renRound.length);
            assertEquals("B's first byte", 'B', origRound[0]);
            assertEquals("C's first byte", 'C', renRound[0]);
            System.err.println("[TEST] conflict resolved: " + origPath
                    + "=B, " + conflictPath + "=C");
        } finally {
            mount.close();
        }
    }

    /**
     * Verifies the pull loop: a remote edit happens, local hasn't been touched
     * since last sync, so the loop's Tier 1 snapshot check fires, Tier 2 finds
     * the file's hash diverged from synced, and Tier 3 pulls the new bytes into
     * the local placeholder (no conflict file).
     */
    @Test
    public void pullLoopPicksUpRemoteEdit() throws Exception {
        if (!isWindowsDesktop()) return;
        NetworkAccess network = Builder.buildLocalJavaNetworkAccess(WEB_PORT).join();
        Crypto crypto = Main.initCrypto();
        String user = "cfapi" + UUID.randomUUID().toString().substring(0, 8);
        UserContext contextA = UserContext.signUp(user, PASSWORD, "", network, crypto).join();

        byte[] before = "remote-edit-before".getBytes(StandardCharsets.UTF_8);
        FileWrapper home = contextA.getByPath("/" + contextA.username).join().get();
        home.uploadOrReplaceFile("remote-edit.txt",
                new AsyncReader.ArrayBacked(before), before.length,
                contextA.network, contextA.crypto, () -> false, l -> {}).join();

        Path syncRoot = tmp.newFolder("peergos-cf-" + user).toPath();
        Path userRoot = syncRoot.resolve(user);
        Path stateDb  = tmp.newFolder("peergos-cf-state-" + user).toPath().resolve("state.db");
        CloudFilesMount mount = CloudFilesMount.mount(contextA, syncRoot.toString(), stateDb);
        try {
            // 1) Materialise the file placeholder inside $user/, then hydrate it so syncState
            //    records the baseline. The $user/ folder itself is seeded by the mount at startup.
            String userRootPs = userRoot.toString().replace("'", "''");
            assertEquals(0, runPs("Get-ChildItem -LiteralPath '" + userRootPs + "' -Name | Out-String"));
            Path localFile = userRoot.resolve("remote-edit.txt");
            assertTrue("placeholder should exist", Files.exists(localFile));
            String filePs = localFile.toString().replace("'", "''");
            assertEquals(0, runPs("$null = Get-Content -LiteralPath '" + filePs + "' -Raw -ErrorAction Stop"));

            // 2) Another UserContext for the same user edits the file in Peergos.
            UserContext contextB = UserContext.signIn(user, PASSWORD,
                    req -> { throw new IllegalStateException("no MFA"); },
                    network, crypto).join();
            byte[] after = "remote-edit-AFTER-the-pull-loop".getBytes(StandardCharsets.UTF_8);
            Optional<FileWrapper> existingOnB = contextB.getByPath(
                    "/" + user + "/remote-edit.txt").join();
            assertTrue(existingOnB.isPresent());
            existingOnB.get().overwriteChangedChunks(
                    new AsyncReader.ArrayBacked(after), after.length,
                    contextB.network, contextB.crypto, l -> {}).join();

            // 3) Force a pull tick. Tier 1 should detect the writer's snapshot moved,
            //    Tier 2 should shortlist remote-edit.txt, Tier 3 should pull (local
            //    untouched since synced → no conflict).
            mount.forcePullTick();

            // 4) Local file should now match the new content.
            waitFor(60_000, () -> {
                try { return Files.size(localFile) == after.length; }
                catch (IOException e) { return false; }
            });
            byte[] onDisk = Files.readAllBytes(localFile);
            assertEquals("pulled content size", after.length, onDisk.length);
            assertArrayEquals("pulled content bytes", after, onDisk);

            // 5) And no conflict copy should have been created.
            assertFalse("no conflict copy expected for a clean pull",
                    Files.exists(userRoot.resolve("remote-edit[conflict-0].txt")));
            assertTrue("Peergos still has the file at original name",
                    contextA.getByPath("/" + user + "/remote-edit.txt").join().isPresent());
            System.err.println("[TEST] pull loop applied remote edit cleanly ("
                    + after.length + " bytes)");
        } finally {
            mount.close();
        }
    }

    /**
     * A file added to the sync root while the mount is offline (no WatchService
     * running to catch the CREATE) must be uploaded by the pull loop's safety-net
     * scan after the next mount comes up. We mount once to populate $user/, close,
     * drop a file directly into $user/ via Files.write (bypassing CF and the
     * watcher), remount and forcePullTick.
     */
    @Test
    public void offlineDropIsUploadedOnRemount() throws Exception {
        if (!isWindowsDesktop()) return;
        NetworkAccess network = Builder.buildLocalJavaNetworkAccess(WEB_PORT).join();
        Crypto crypto = Main.initCrypto();
        String user = "cfapi" + UUID.randomUUID().toString().substring(0, 8);
        UserContext context = UserContext.signUp(user, PASSWORD, "", network, crypto).join();

        // Seed one remote file so the first mount has a reason to enumerate $user/
        // and thereby addDir($user) to syncState — which is the gate the safety-net
        // walk uses to decide $user/ is safe to descend into on remount.
        byte[] seed = "seed".getBytes(StandardCharsets.UTF_8);
        FileWrapper home = context.getByPath("/" + user).join().get();
        home.uploadOrReplaceFile("seed.txt", new AsyncReader.ArrayBacked(seed), seed.length,
                context.network, context.crypto, () -> false, l -> {}).join();

        Path syncRoot = tmp.newFolder("peergos-cf-" + user).toPath();
        Path userRoot = syncRoot.resolve(user);
        Path stateDb  = tmp.newFolder("peergos-cf-state-" + user).toPath().resolve("state.db");

        // First mount: external Get-ChildItem to populate $user/ (FETCH_PLACEHOLDERS
        // → addDir($user)), then close.
        CloudFilesMount first = CloudFilesMount.mount(context, syncRoot.toString(), stateDb);
        try {
            String userRootPs = userRoot.toString().replace("'", "''");
            assertEquals(0, runPs("Get-ChildItem -LiteralPath '" + userRootPs + "' -Name | Out-String"));
            assertTrue("seed.txt should materialise during first mount",
                    Files.exists(userRoot.resolve("seed.txt")));
        } finally {
            first.close();
        }

        // Offline drop: while the mount is closed, write a file directly into the
        // sync root. Nothing in our process sees this — no watcher events, no CF
        // callbacks. The file exists on disk as an ordinary file (not a placeholder).
        byte[] offline = "added-while-offline".getBytes(StandardCharsets.UTF_8);
        Path offlinePath = userRoot.resolve("offline.txt");
        Files.write(offlinePath, offline);
        assertTrue("offline.txt must be on disk before remount", Files.exists(offlinePath));
        assertFalse("offline.txt must not be in peergos yet",
                context.getByPath("/" + user + "/offline.txt").join().isPresent());

        // Remount and force a pull tick. discoverMissedLocalUploads should walk
        // $user/ (it's in syncState.getDirs() from the first mount's FETCH_PLACEHOLDERS)
        // and upload offline.txt because no syncState entry exists for it.
        CloudFilesMount second = CloudFilesMount.mount(context, syncRoot.toString(), stateDb);
        try {
            second.forcePullTick();
            waitFor(60_000, () -> context.getByPath("/" + user + "/offline.txt").join().isPresent());
            Optional<FileWrapper> remote = context.getByPath("/" + user + "/offline.txt").join();
            assertTrue("offline.txt should be in peergos after remount + pull tick",
                    remote.isPresent());
            assertEquals("offline.txt size mismatch", offline.length, remote.get().getSize());
            byte[] roundTripped = Serialize.readFully(remote.get(), crypto, network).join();
            assertArrayEquals("offline.txt bytes mismatch after round trip",
                    offline, roundTripped);
        } finally {
            second.close();
        }
    }

    /**
     * Verify that a directory deleted on the remote (via another UserContext) is
     * cleaned up locally — both the file placeholders inside (handled by per-file
     * applyRemoteDelete) and the empty dir itself (handled by applyRemoteDirDeletes,
     * the parallel pass over syncState.getDirs()). Without the dir pass, the empty
     * placeholder dir would linger on disk and the syncState entry would persist
     * across remounts.
     */
    @Test
    public void remoteDirDeletePropagatesToLocal() throws Exception {
        if (!isWindowsDesktop()) return;
        NetworkAccess network = Builder.buildLocalJavaNetworkAccess(WEB_PORT).join();
        Crypto crypto = Main.initCrypto();
        String user = "cfapi" + UUID.randomUUID().toString().substring(0, 8);
        UserContext contextA = UserContext.signUp(user, PASSWORD, "", network, crypto).join();

        // Seed a remote subdir with one file inside, so it's a real populated dir
        // (not just an empty placeholder) and exercises the file-then-dir order.
        byte[] inside = "inside".getBytes(StandardCharsets.UTF_8);
        FileWrapper home = contextA.getByPath("/" + user).join().get();
        FileWrapper folder = home.mkdir("folder", contextA.network, false,
                Optional.empty(), contextA.crypto).join();
        contextA.getByPath("/" + user + "/folder").join().get()
                .uploadOrReplaceFile("inside.txt",
                        new AsyncReader.ArrayBacked(inside), inside.length,
                        contextA.network, contextA.crypto, () -> false, l -> {}).join();

        Path syncRoot = tmp.newFolder("peergos-cf-" + user).toPath();
        Path userRoot = syncRoot.resolve(user);
        Path stateDb  = tmp.newFolder("peergos-cf-state-" + user).toPath().resolve("state.db");
        CloudFilesMount mount = CloudFilesMount.mount(contextA, syncRoot.toString(), stateDb);
        try {
            // External enumerations: $user/ then $user/folder/ — populate both via
            // FETCH_PLACEHOLDERS so addDir runs for each (the gate the dir-delete pass
            // iterates over) and so inside.txt's placeholder is on disk + in syncState.
            String userRootPs = userRoot.toString().replace("'", "''");
            assertEquals(0, runPs("Get-ChildItem -LiteralPath '" + userRootPs + "' -Name | Out-String"));
            String folderPs = userRoot.resolve("folder").toString().replace("'", "''");
            assertEquals(0, runPs("Get-ChildItem -LiteralPath '" + folderPs + "' -Name | Out-String"));
            // Hydrate inside.txt so its mtime/size match the synced baseline (so the
            // per-file applyRemoteDelete in the pull tick agrees to delete it cleanly
            // rather than treating it as locally-diverged).
            String insidePs = userRoot.resolve("folder").resolve("inside.txt")
                    .toString().replace("'", "''");
            assertEquals(0, runPs("$null = Get-Content -LiteralPath '" + insidePs
                    + "' -Raw -ErrorAction Stop"));

            assertTrue("folder should be on disk after first enumeration",
                    Files.exists(userRoot.resolve("folder")));
            assertTrue("inside.txt should be on disk after hydrate",
                    Files.exists(userRoot.resolve("folder").resolve("inside.txt")));

            // Remote delete via a second context. Remove the file first, then the dir.
            UserContext contextB = UserContext.signIn(user, PASSWORD,
                    req -> { throw new IllegalStateException("no MFA"); },
                    network, crypto).join();
            String remoteFile   = "/" + user + "/folder/inside.txt";
            String remoteFolder = "/" + user + "/folder";
            FileWrapper fileOnB = contextB.getByPath(remoteFile).join().orElseThrow();
            FileWrapper folderOnB = contextB.getByPath(remoteFolder).join().orElseThrow();
            fileOnB.remove(folderOnB, PathUtil.get(remoteFile), contextB).join();
            FileWrapper homeOnB = contextB.getByPath("/" + user).join().orElseThrow();
            FileWrapper folderOnB2 = contextB.getByPath(remoteFolder).join().orElseThrow();
            folderOnB2.remove(homeOnB, PathUtil.get(remoteFolder), contextB).join();

            // Wait for context A to observe the remote deletes.
            waitFor(60_000, () -> contextA.getByPath(remoteFolder).join().isEmpty()
                    && contextA.getByPath(remoteFile).join().isEmpty());

            // Force the pull tick — first the file is locally deleted, then the dir.
            mount.forcePullTick();

            // Eventual: both gone locally, and getDirs no longer has folder.
            waitFor(60_000, () -> !Files.exists(userRoot.resolve("folder")));
            assertFalse("local folder/ should be deleted after remote dir delete",
                    Files.exists(userRoot.resolve("folder")));
            assertFalse("local folder/inside.txt should be deleted",
                    Files.exists(userRoot.resolve("folder").resolve("inside.txt")));
        } finally {
            mount.close();
        }
    }

    /**
     * Verifies the unmount → remount lifecycle for CF placeholders:
     *   (1) unmount() purges dehydrated file placeholders and dirs left empty by
     *       that purge.
     *   (2) Materialised (hydrated) files survive unmount unchanged.
     *   (3) After remount, the pull tick recreates the purged top-level entries
     *       as placeholders; listing a recreated placeholder dir then re-populates
     *       its children via FETCH_PLACEHOLDERS.
     *
     * Fixtures laid out so the purge has to discriminate:
     *   hydrated.txt          — read after listing, content on disk → keep
     *   dehydrated.txt        — listed only, still OFFLINE          → purge
     *   mixed-dir/mixed-hyd.txt — hydrated; parent must survive
     *   empty-dir/orphan.txt  — never read; parent should be removed after purge
     */
    @Test
    public void unmountPurgesPlaceholdersAndRemountRestoresThem() throws Exception {
        if (!isWindowsDesktop()) return;
        NetworkAccess network = Builder.buildLocalJavaNetworkAccess(WEB_PORT).join();
        Crypto crypto = Main.initCrypto();
        String user = "cfapi" + UUID.randomUUID().toString().substring(0, 8);
        UserContext context = UserContext.signUp(user, PASSWORD, "", network, crypto).join();

        byte[] hydContent  = "hydrated bytes".getBytes(StandardCharsets.UTF_8);
        byte[] dehContent  = "dehydrated bytes".getBytes(StandardCharsets.UTF_8);
        byte[] mixContent  = "mixed-dir hydrated child".getBytes(StandardCharsets.UTF_8);
        byte[] orphContent = "empty-dir orphan placeholder".getBytes(StandardCharsets.UTF_8);

        FileWrapper home = context.getByPath("/" + user).join().get();
        home.uploadOrReplaceFile("hydrated.txt", new AsyncReader.ArrayBacked(hydContent),
                hydContent.length, context.network, context.crypto, () -> false, l -> {}).join();
        home.getUpdated(network).join().uploadOrReplaceFile("dehydrated.txt",
                new AsyncReader.ArrayBacked(dehContent), dehContent.length,
                context.network, context.crypto, () -> false, l -> {}).join();
        home.getUpdated(network).join().mkdir("mixed-dir", context.network, false,
                Optional.empty(), context.crypto).join();
        context.getByPath("/" + user + "/mixed-dir").join().get()
                .uploadOrReplaceFile("mixed-hyd.txt",
                        new AsyncReader.ArrayBacked(mixContent), mixContent.length,
                        context.network, context.crypto, () -> false, l -> {}).join();
        home.getUpdated(network).join().mkdir("empty-dir", context.network, false,
                Optional.empty(), context.crypto).join();
        context.getByPath("/" + user + "/empty-dir").join().get()
                .uploadOrReplaceFile("orphan.txt",
                        new AsyncReader.ArrayBacked(orphContent), orphContent.length,
                        context.network, context.crypto, () -> false, l -> {}).join();

        Path syncRoot = tmp.newFolder("peergos-cf-" + user).toPath();
        Path userRoot = syncRoot.resolve(user);
        Path stateDb  = tmp.newFolder("peergos-cf-state-" + user).toPath().resolve("state.db");

        CloudFilesMount mount1 = CloudFilesMount.mount(context, syncRoot.toString(), stateDb);
        try {
            String userRootPs = userRoot.toString().replace("'", "''");
            assertEquals("list $user/ failed", 0,
                    runPs("Get-ChildItem -LiteralPath '" + userRootPs + "' -Name | Out-String"));
            String mixedPs = userRoot.resolve("mixed-dir").toString().replace("'", "''");
            assertEquals("list mixed-dir failed", 0,
                    runPs("Get-ChildItem -LiteralPath '" + mixedPs + "' -Name | Out-String"));
            String emptyPs = userRoot.resolve("empty-dir").toString().replace("'", "''");
            assertEquals("list empty-dir failed", 0,
                    runPs("Get-ChildItem -LiteralPath '" + emptyPs + "' -Name | Out-String"));

            // Hydrate the two files that should survive the purge.
            String hydPs = userRoot.resolve("hydrated.txt").toString().replace("'", "''");
            assertEquals("read hydrated.txt failed", 0,
                    runPs("$null = Get-Content -LiteralPath '" + hydPs + "' -Raw -ErrorAction Stop"));
            String mixHydPs = userRoot.resolve("mixed-dir").resolve("mixed-hyd.txt")
                    .toString().replace("'", "''");
            assertEquals("read mixed-hyd.txt failed", 0,
                    runPs("$null = Get-Content -LiteralPath '" + mixHydPs + "' -Raw -ErrorAction Stop"));

            assertTrue(Files.exists(userRoot.resolve("hydrated.txt")));
            assertTrue(Files.exists(userRoot.resolve("dehydrated.txt")));
            assertTrue(Files.exists(userRoot.resolve("mixed-dir").resolve("mixed-hyd.txt")));
            assertTrue(Files.exists(userRoot.resolve("empty-dir").resolve("orphan.txt")));
            assertTrue("dehydrated.txt should still be OFFLINE before unmount",
                    (CfApi.getFileAttributes(userRoot.resolve("dehydrated.txt"))
                            & CfApi.FILE_ATTRIBUTE_OFFLINE) != 0);
            assertTrue("empty-dir/orphan.txt should still be OFFLINE before unmount",
                    (CfApi.getFileAttributes(userRoot.resolve("empty-dir").resolve("orphan.txt"))
                            & CfApi.FILE_ATTRIBUTE_OFFLINE) != 0);
            assertEquals("hydrated.txt should NOT be OFFLINE",
                    0,
                    CfApi.getFileAttributes(userRoot.resolve("hydrated.txt"))
                            & CfApi.FILE_ATTRIBUTE_OFFLINE);
        } finally {
            mount1.unmount();
        }

        // (1) Purge: dehydrated placeholders and the dir left empty by the purge.
        assertFalse("dehydrated.txt should be purged by unmount",
                Files.exists(userRoot.resolve("dehydrated.txt")));
        assertFalse("empty-dir/orphan.txt should be purged by unmount",
                Files.exists(userRoot.resolve("empty-dir").resolve("orphan.txt")));
        assertFalse("empty-dir should be removed after its only child was purged",
                Files.exists(userRoot.resolve("empty-dir")));

        // (2) Hydrated files survive untouched.
        assertTrue("hydrated.txt should remain on disk after unmount",
                Files.exists(userRoot.resolve("hydrated.txt")));
        assertArrayEquals("hydrated.txt content should be untouched",
                hydContent, Files.readAllBytes(userRoot.resolve("hydrated.txt")));
        assertTrue("mixed-dir should survive (it has a hydrated child)",
                Files.exists(userRoot.resolve("mixed-dir")));
        assertTrue("mixed-dir/mixed-hyd.txt should survive",
                Files.exists(userRoot.resolve("mixed-dir").resolve("mixed-hyd.txt")));
        assertArrayEquals("mixed-hyd.txt content should be untouched",
                mixContent, Files.readAllBytes(userRoot.resolve("mixed-dir").resolve("mixed-hyd.txt")));
        assertTrue("syncRoot itself should remain", Files.exists(syncRoot));
        assertTrue("$user/ should remain (has a hydrated child)", Files.exists(userRoot));

        // (3) Remount: forcePullTick re-discovers remote children of dirs still
        // on disk and CfCreatePlaceholders them — recreating dehydrated.txt and
        // empty-dir/ under $user/. Listing the recreated empty-dir/ then fires
        // FETCH_PLACEHOLDERS on the fresh placeholder (no DISABLE_ON_DEMAND_POPULATION
        // yet) which surfaces its child.
        CloudFilesMount mount2 = CloudFilesMount.mount(context, syncRoot.toString(), stateDb);
        try {
            mount2.forcePullTick();
            waitFor(30_000, () -> Files.exists(userRoot.resolve("dehydrated.txt")));
            waitFor(30_000, () -> Files.exists(userRoot.resolve("empty-dir")));

            assertTrue("dehydrated.txt should reappear as a placeholder after remount",
                    Files.exists(userRoot.resolve("dehydrated.txt")));
            assertTrue("recreated dehydrated.txt should be a CF placeholder",
                    CfApi.isPlaceholder(userRoot.resolve("dehydrated.txt")));
            assertTrue("empty-dir should reappear after remount",
                    Files.exists(userRoot.resolve("empty-dir"))
                            && Files.isDirectory(userRoot.resolve("empty-dir")));
            assertTrue("recreated empty-dir should be a CF placeholder",
                    CfApi.isPlaceholder(userRoot.resolve("empty-dir")));

            String emptyPs = userRoot.resolve("empty-dir").toString().replace("'", "''");
            assertEquals("list empty-dir after remount failed", 0,
                    runPs("Get-ChildItem -LiteralPath '" + emptyPs + "' -Name | Out-String"));
            waitFor(30_000, () -> Files.exists(userRoot.resolve("empty-dir").resolve("orphan.txt")));
            assertTrue("orphan.txt should reappear under empty-dir after listing it",
                    Files.exists(userRoot.resolve("empty-dir").resolve("orphan.txt")));
        } finally {
            mount2.close();
        }
    }

    private static int runPs(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-Command", command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        boolean done = p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        System.err.println("[TEST] ps exit=" + (done ? p.exitValue() : "TIMEOUT")
                + (out.isEmpty() ? "" : " out=[" + out.trim() + "]"));
        if (!done) { p.destroyForcibly(); return -1; }
        return p.exitValue();
    }

    private static void waitFor(long timeoutMs, java.util.function.BooleanSupplier cond) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(500);
        }
    }

    // -----------------------------------------------------------------------
    // Struct builder helpers
    // -----------------------------------------------------------------------

    private static MemorySegment buildRegistration(Arena arena) {
        MemorySegment reg = arena.allocate(CfApi.REG_SIZE);
        MemorySegment nameW    = CfApi.wideString("PeergosTest", arena);
        MemorySegment versionW = CfApi.wideString("1.0", arena);
        UUID guid = UUID.nameUUIDFromBytes("peergos-cfapi-test".getBytes());
        reg.set(ValueLayout.JAVA_INT,  CfApi.REG_STRUCT_SIZE_OFF,            (int) CfApi.REG_SIZE);
        reg.set(ValueLayout.JAVA_LONG, CfApi.REG_PROVIDER_NAME_OFF,          nameW.address());
        reg.set(ValueLayout.JAVA_LONG, CfApi.REG_PROVIDER_VERSION_OFF,       versionW.address());
        reg.set(ValueLayout.JAVA_LONG, CfApi.REG_SYNC_ROOT_IDENTITY_OFF,     0L);
        reg.set(ValueLayout.JAVA_INT,  CfApi.REG_SYNC_ROOT_IDENTITY_LEN_OFF, 0);
        reg.set(ValueLayout.JAVA_LONG, CfApi.REG_FILE_IDENTITY_OFF,          0L);
        reg.set(ValueLayout.JAVA_INT,  CfApi.REG_FILE_IDENTITY_LEN_OFF,      0);
        writeGuid(reg, CfApi.REG_PROVIDER_CLSID_OFF, guid);
        return reg;
    }

    private static MemorySegment buildPolicies(Arena arena) {
        MemorySegment p = arena.allocate(CfApi.POLICIES_SIZE);
        p.set(ValueLayout.JAVA_INT,   CfApi.POLICIES_STRUCT_SIZE_OFF,       (int) CfApi.POLICIES_SIZE);
        p.set(ValueLayout.JAVA_SHORT, CfApi.POLICIES_HYDRATION_OFF,         CfApi.CF_HYDRATION_POLICY_PARTIAL);
        p.set(ValueLayout.JAVA_SHORT, CfApi.POLICIES_HYDRATION_OFF   + 2,   CfApi.CF_POLICY_MODIFIER_NONE);
        p.set(ValueLayout.JAVA_SHORT, CfApi.POLICIES_POPULATION_OFF,        CfApi.CF_POPULATION_POLICY_PARTIAL);
        p.set(ValueLayout.JAVA_SHORT, CfApi.POLICIES_POPULATION_OFF  + 2,   CfApi.CF_POLICY_MODIFIER_NONE);
        p.set(ValueLayout.JAVA_INT,   CfApi.POLICIES_INSYNC_OFF,            CfApi.CF_INSYNC_POLICY_NONE);
        p.set(ValueLayout.JAVA_INT,   CfApi.POLICIES_HARDLINK_OFF,          CfApi.CF_HARDLINK_POLICY_NONE);
        p.set(ValueLayout.JAVA_INT,   CfApi.POLICIES_PLACEHOLDER_MGMT_OFF,  0);
        return p;
    }

    private static void writeGuid(MemorySegment seg, long offset, UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        seg.set(ValueLayout.JAVA_INT,   offset,     (int) (msb >>> 32));
        seg.set(ValueLayout.JAVA_SHORT, offset + 4, (short) (msb >>> 16));
        seg.set(ValueLayout.JAVA_SHORT, offset + 6, (short) msb);
        for (int i = 0; i < 8; i++)
            seg.set(ValueLayout.JAVA_BYTE, offset + 8 + i, (byte) (lsb >>> (56 - i * 8)));
    }
}
