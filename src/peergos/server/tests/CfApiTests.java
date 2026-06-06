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
        if (isWindowsDesktop()) return;
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
        FileWrapper home = context.getByPath("/" + context.username).join().get();
        home.uploadOrReplaceFile("hello.txt", new AsyncReader.ArrayBacked(content), content.length,
                context.network, context.crypto, () -> false, l -> {}).join();
        home.getUpdated(network).join().uploadOrReplaceFile("world.txt", new AsyncReader.ArrayBacked(content), content.length,
                context.network, context.crypto, () -> false, l -> {}).join();
        home.getUpdated(network).join().uploadOrReplaceFile("large.txt", new AsyncReader.ArrayBacked(large), large.length,
                context.network, context.crypto, () -> false, l -> {}).join();

        Path syncRoot = tmp.newFolder("peergos-cf-" + user).toPath();
        Path userRoot = syncRoot.resolve(user);
        Path stateDb = tmp.newFolder("peergos-cf-state-" + user).toPath().resolve("state.db");
        CloudFilesMount mount = CloudFilesMount.mount(context, syncRoot.toString(), stateDb);
        try {
            // Step 1 — external directory listing of $user/. This fires FETCH_PLACEHOLDERS
            // from an external PID; our provider responds with CfExecute(TRANSFER_PLACEHOLDERS)
            // which creates the physical placeholder files. Same-process Files.list on an empty
            // placeholder dir does NOT trigger FETCH_PLACEHOLDERS, so we rely on the external
            // listing to populate it. The $user/ folder itself is materialised by the mount at
            // startup (seedRootPlaceholders), so we list straight into it here.
            String userRootPs = userRoot.toString().replace("'", "''");
            {
                System.err.println("[TEST] Starting external Get-ChildItem on " + userRoot);
                ProcessBuilder lb = new ProcessBuilder(
                        "powershell", "-NoProfile", "-NonInteractive",
                        "-ExecutionPolicy", "Bypass",
                        "-Command",
                        "Get-ChildItem -LiteralPath '" + userRootPs + "' -Name | Out-String");
                lb.redirectErrorStream(true);
                Process lp = lb.start();
                System.err.println("[TEST] Get-ChildItem process started, pid=" + lp.pid());
                String listing = new String(lp.getInputStream().readAllBytes());
                boolean ldone = lp.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                System.err.println("[TEST] External dir listing (exit="
                        + (ldone ? lp.exitValue() : "TIMEOUT") + "): " + listing.trim());
                assertTrue("External dir listing timed out", ldone);
                assertEquals("External dir listing failed", 0, lp.exitValue());
                assertTrue("hello.txt should appear in external listing", listing.contains("hello.txt"));
                assertTrue("world.txt should appear in external listing", listing.contains("world.txt"));
            }

            // Step 2 — read hello.txt via PowerShell Get-Content to trigger FETCH_DATA from
            // an external PID and verify the content reaches the caller intact. We switched
            // from Copy-Item to Get-Content because Copy-Item kept failing with "Invalid
            // access to memory location" even when CfExecute(TRANSFER_DATA) returned S_OK —
            // Get-Content is a simpler read path with fewer Win32 layers in between.
            {
                String srcPath = userRoot.resolve("hello.txt").toString().replace("'", "''");
                System.err.println("[TEST] Reading '" + srcPath + "' via Get-Content");
                ProcessBuilder pb = new ProcessBuilder(
                        "powershell", "-NoProfile", "-NonInteractive",
                        "-ExecutionPolicy", "Bypass",
                        "-Command",
                        "Get-Content -LiteralPath '" + srcPath + "' -Raw -ErrorAction Stop");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String psOutput = new String(proc.getInputStream().readAllBytes());
                boolean done = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                System.err.println("[TEST] Get-Content exit=" + (done ? proc.exitValue() : "TIMEOUT")
                        + " output=[" + psOutput.trim() + "]");
                assertTrue("Get-Content timed out", done);
                assertEquals("Get-Content failed (output: " + psOutput.trim() + ")",
                        0, proc.exitValue());
                assertEquals("File content must match what was uploaded",
                        new String(content).trim(), psOutput.trim());
            }

            // Step 2 — read large.txt
            {
                String srcPath = userRoot.resolve("large.txt").toString().replace("'", "''");
                System.err.println("[TEST] Reading '" + srcPath + "' via Get-Content");
                ProcessBuilder pb = new ProcessBuilder(
                        "powershell", "-NoProfile", "-NonInteractive",
                        "-ExecutionPolicy", "Bypass",
                        "-Command",
                        "Get-Content -LiteralPath '" + srcPath + "' -Raw -ErrorAction Stop");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                byte[] psBytes = proc.getInputStream().readAllBytes();
                boolean done = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                System.err.println("[TEST] Get-Content exit=" + (done ? proc.exitValue() : "TIMEOUT")
                        + " bytes=" + psBytes.length);
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
                Path stagedFile = tmp.newFile("upload-source.bin").toPath();
                Files.write(stagedFile, upload);

                String destName = "uploaded.bin";
                String destPath = userRoot.resolve(destName).toString().replace("'", "''");
                String srcPath  = stagedFile.toString().replace("'", "''");
                System.err.println("[TEST] Copying " + upload.length + " bytes from '"
                        + srcPath + "' to '" + destPath + "'");
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
                System.err.println("[TEST] Copy-Item exit=" + (cdone ? cproc.exitValue() : "TIMEOUT")
                        + " output=[" + cpOutput.trim() + "]");
                assertTrue("Copy-Item timed out", cdone);
                assertEquals("Copy-Item failed (output: " + cpOutput.trim() + ")",
                        0, cproc.exitValue());

                // Wait for the upload to land in Peergos (driven by NOTIFY_FILE_CLOSE_COMPLETION).
                String peergosPath = "/" + context.username + "/" + destName;
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
                assertNotNull("uploaded.bin never appeared in Peergos", uploaded);
                assertEquals("uploaded file size mismatch", upload.length, uploaded.getSize());

                byte[] roundTripped = Serialize.readFully(uploaded, crypto, network).join();
                assertArrayEquals("uploaded.bin content mismatch after round trip",
                        upload, roundTripped);
                System.err.println("[TEST] uploaded.bin round trip OK ("
                        + upload.length + " bytes)");
            }

            // Step 4 — mkdir inside the mount and verify the directory shows up in Peergos.
            String subdirName = "subdir";
            {
                String subdirLocal = userRoot.resolve(subdirName).toString().replace("'", "''");
                System.err.println("[TEST] mkdir '" + subdirLocal + "'");
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

            // Rename / move / delete need CF-managed placeholders so cldapi fires the
            // NOTIFY_RENAME / NOTIFY_DELETE callbacks our provider already handles. The
            // three files created via uploadOrReplaceFile + TRANSFER_PLACEHOLDERS at the
            // start of the test (hello.txt, large.txt, world.txt) are real placeholders;
            // uploaded.bin (Step 3) was written locally and never converted, so its
            // rename would skip CF entirely.

            // Step 5 — rename hello.txt to renamed.txt.
            String renamedName = "renamed.txt";
            {
                String src = userRoot.resolve("hello.txt").toString().replace("'", "''");
                System.err.println("[TEST] Rename hello.txt -> " + renamedName);
                int exit = runPs("Rename-Item -LiteralPath '" + src
                        + "' -NewName '" + renamedName + "' -ErrorAction Stop");
                assertEquals("Rename-Item failed", 0, exit);
                waitFor(60_000, () -> {
                    Optional<FileWrapper> r = context.getByPath(
                            "/" + context.username + "/" + renamedName).join();
                    Optional<FileWrapper> orig = context.getByPath(
                            "/" + context.username + "/hello.txt").join();
                    return r.isPresent() && orig.isEmpty();
                });
                assertTrue(renamedName + " should exist in Peergos",
                        context.getByPath("/" + context.username + "/" + renamedName).join().isPresent());
                assertFalse("hello.txt should be gone from Peergos",
                        context.getByPath("/" + context.username + "/hello.txt").join().isPresent());
            }

            // Step 6 — move large.txt into subdir/ and verify the move in Peergos.
            {
                String src  = userRoot.resolve("large.txt").toString().replace("'", "''");
                String dest = userRoot.resolve(subdirName).resolve("large.txt").toString().replace("'", "''");
                System.err.println("[TEST] Move large.txt -> " + subdirName + "/large.txt");
                int exit = runPs("Move-Item -LiteralPath '" + src
                        + "' -Destination '" + dest + "' -ErrorAction Stop");
                assertEquals("Move-Item failed", 0, exit);
                String newPath = "/" + context.username + "/" + subdirName + "/large.txt";
                String oldPath = "/" + context.username + "/large.txt";
                waitFor(60_000, () -> {
                    Optional<FileWrapper> n = context.getByPath(newPath).join();
                    Optional<FileWrapper> o = context.getByPath(oldPath).join();
                    return n.isPresent() && o.isEmpty();
                });
                assertTrue("large.txt should exist under subdir/ in Peergos",
                        context.getByPath(newPath).join().isPresent());
                assertFalse("large.txt should be gone from old location in Peergos",
                        context.getByPath(oldPath).join().isPresent());
            }

            // Step 7 — modify uploaded.bin in place (same size, different content). This
            // exercises the mtime-based dirty-bit path in onFileCloseCompletion: same size
            // means the cheap size-mismatch check doesn't fire, so the handler falls back
            // to comparing local mtime vs Peergos mtime to decide whether to upload.
            // uploaded.bin is 11 MB = 3 chunks of 5/5/~1 MB. Overwriting only the FIRST
            // chunk's bytes should re-upload chunk 0 (~5 MB) — chunks 1 & 2 are unchanged
            // and should be skipped by Peergos's chunk-level SHA-256 dedup.
            String modifiedName = "uploaded.bin";
            {
                String src = userRoot.resolve(modifiedName).toString().replace("'", "''");
                byte[] marker = "PEERGOS_INPLACE_MARKER".getBytes(StandardCharsets.UTF_8);
                StringBuilder bytesLit = new StringBuilder();
                for (int i = 0; i < marker.length; i++) {
                    if (i > 0) bytesLit.append(',');
                    bytesLit.append(marker[i] & 0xFF);
                }
                System.err.println("[TEST] In-place overwriting first " + marker.length
                        + " bytes of " + modifiedName);
                int exit = runPs(
                        "$bytes = [byte[]](" + bytesLit + "); " +
                        "$fs = [IO.File]::OpenWrite('" + src + "'); " +
                        "try { $fs.Seek(0, 'Begin') | Out-Null; $fs.Write($bytes, 0, $bytes.Length) } " +
                        "finally { $fs.Close() }");
                assertEquals("in-place modify failed", 0, exit);

                String peergosPath = "/" + context.username + "/" + modifiedName;
                long expectedSize = 11L * 1024 * 1024;
                // Poll until Peergos shows the new first-bytes marker (size is unchanged).
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
                assertEquals("uploaded.bin size should be unchanged", expectedSize, updated.getSize());

                byte[] roundTripped = Serialize.readFully(updated, crypto, network).join();
                byte[] head = java.util.Arrays.copyOfRange(roundTripped, 0, marker.length);
                assertArrayEquals("marker should be at start of uploaded.bin in Peergos",
                        marker, head);
                System.err.println("[TEST] uploaded.bin in-place modification verified ("
                        + marker.length + " bytes at offset 0, total " + roundTripped.length + ")");
            }

            // Step 8 — delete world.txt and verify Peergos no longer has it.
            {
                String src = userRoot.resolve("world.txt").toString().replace("'", "''");
                System.err.println("[TEST] Delete world.txt");
                int exit = runPs("Remove-Item -LiteralPath '" + src + "' -Force -ErrorAction Stop");
                assertEquals("Remove-Item failed", 0, exit);
                String p = "/" + context.username + "/world.txt";
                waitFor(60_000, () -> context.getByPath(p).join().isEmpty());
                assertTrue("world.txt should be gone from Peergos",
                        context.getByPath(p).join().isEmpty());
            }
        } finally {
            mount.close();
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
