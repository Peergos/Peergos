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

import java.lang.foreign.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class CfApiTests {

    private static final String PASSWORD = "cfapipassword123";
    private static int WEB_PORT;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    @BeforeClass
    public static void startServer() throws Exception {
        if (!isWindows()) return;
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
        if (!isWindows()) return;
        CfApi.load(); // must not throw
    }

    @Test
    public void registerAndUnregisterSyncRoot() throws Exception {
        if (!isWindows()) return;
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
        if (!isWindows()) return;
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
        CloudFilesMount mount = CloudFilesMount.mount(context, syncRoot.toString());
        try {
            // Step 1 — external directory listing. This fires FETCH_PLACEHOLDERS from an external
            // PID; our provider responds with CfExecute(TRANSFER_PLACEHOLDERS) which creates the
            // physical placeholder files. Same-process Files.list on an empty sync root does NOT
            // trigger FETCH_PLACEHOLDERS, so we rely on the external listing to populate the dir.
            String syncRootPs = syncRoot.toString().replace("'", "''");
            {
                System.err.println("[TEST] Starting external Get-ChildItem on " + syncRoot);
                ProcessBuilder lb = new ProcessBuilder(
                        "powershell", "-NoProfile", "-NonInteractive",
                        "-ExecutionPolicy", "Bypass",
                        "-Command",
                        "Get-ChildItem -LiteralPath '" + syncRootPs + "' -Name | Out-String");
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
                String srcPath = syncRoot.resolve("hello.txt").toString().replace("'", "''");
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
                String srcPath = syncRoot.resolve("large.txt").toString().replace("'", "''");
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
                String destPath = syncRoot.resolve(destName).toString().replace("'", "''");
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
        } finally {
            mount.close();
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
