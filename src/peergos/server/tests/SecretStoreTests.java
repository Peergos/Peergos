package peergos.server.tests;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import peergos.server.net.MountConfigHandler;
import peergos.server.util.secrets.*;
import peergos.server.webdav.MountConfig;
import peergos.shared.io.ipfs.api.JSONParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class SecretStoreTests {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /* ------------------------------------------------------------------ */
    /* MemorySecretStore — exercises the interface contract that all      */
    /* implementations should obey.                                       */
    /* ------------------------------------------------------------------ */

    @Test
    public void memoryStore_putThenGetRoundTrips() throws IOException {
        SecretStore store = new MemorySecretStore();
        store.put("svc", "alice", "hunter2");
        assertEquals(Optional.of("hunter2"), store.get("svc", "alice"));
    }

    @Test
    public void memoryStore_putOverwritesExisting() throws IOException {
        SecretStore store = new MemorySecretStore();
        store.put("svc", "alice", "first");
        store.put("svc", "alice", "second");
        assertEquals(Optional.of("second"), store.get("svc", "alice"));
    }

    @Test
    public void memoryStore_serviceAndAccountAreBothPartOfKey() throws IOException {
        SecretStore store = new MemorySecretStore();
        store.put("svc", "alice", "A");
        store.put("svc", "bob",   "B");
        store.put("other", "alice", "C");
        assertEquals(Optional.of("A"), store.get("svc", "alice"));
        assertEquals(Optional.of("B"), store.get("svc", "bob"));
        assertEquals(Optional.of("C"), store.get("other", "alice"));
    }

    @Test
    public void memoryStore_getMissingReturnsEmpty() throws IOException {
        SecretStore store = new MemorySecretStore();
        assertEquals(Optional.empty(), store.get("svc", "missing"));
    }

    @Test
    public void memoryStore_deleteRemovesEntry() throws IOException {
        SecretStore store = new MemorySecretStore();
        store.put("svc", "alice", "secret");
        store.delete("svc", "alice");
        assertEquals(Optional.empty(), store.get("svc", "alice"));
    }

    @Test
    public void memoryStore_deleteMissingIsNoOp() throws IOException {
        new MemorySecretStore().delete("svc", "ghost");
    }

    /* ------------------------------------------------------------------ */
    /* JsonFileSecretStore — defers everything to MountConfigHandler so   */
    /* its own methods are intentional no-ops; verify that's still true.  */
    /* ------------------------------------------------------------------ */

    @Test
    public void jsonFileStore_embedsInConfigFile() {
        assertTrue(new JsonFileSecretStore().embedsInConfigFile());
    }

    @Test
    public void jsonFileStore_putGetDeleteAreNoOps() throws IOException {
        SecretStore store = new JsonFileSecretStore();
        store.put("svc", "alice", "ignored");
        assertEquals(Optional.empty(), store.get("svc", "alice"));
        store.delete("svc", "alice");
        assertTrue(store.isAvailable());
    }

    /* ------------------------------------------------------------------ */
    /* SecretStore.detect — should return JsonFileSecretStore on a Linux  */
    /* CI worker (no FLATPAK_ID set).                                     */
    /* ------------------------------------------------------------------ */

    @Test
    public void detect_picksJsonFileOnLinuxOutsideFlatpak() {
        String os = System.getProperty("os.name", "").toLowerCase();
        assumeTrue("Linux-only check", os.startsWith("linux"));
        assumeTrue("Not running under Flatpak", System.getenv("FLATPAK_ID") == null);
        assertTrue(SecretStore.detect() instanceof JsonFileSecretStore);
    }

    /* ------------------------------------------------------------------ */
    /* Integration: round-trip through a live secret-tool if one is on    */
    /* PATH and the daemon is reachable. Skipped otherwise so CI without  */
    /* a keyring daemon (the common case) doesn't fail.                   */
    /* ------------------------------------------------------------------ */

    @Test
    public void flatpakStore_roundTripsAgainstLiveSecretService() throws IOException {
        FlatpakSecretToolStore store = new FlatpakSecretToolStore();
        assumeTrue("secret-tool not available / Secret Service unreachable", store.isAvailable());

        String service = "peergos-test-" + UUID.randomUUID();
        String account = "user";
        String value = "round-trip-" + UUID.randomUUID();
        try {
            store.put(service, account, value);
            assertEquals(Optional.of(value), store.get(service, account));
            store.delete(service, account);
            assertEquals(Optional.empty(), store.get(service, account));
        } finally {
            // Belt and braces — make sure we don't leak test entries into the
            // user's keyring if an assertion fails partway through.
            store.delete(service, account);
        }
    }

    /* ------------------------------------------------------------------ */
    /* WindowsCredentialManagerStore — exercised in three layers:         */
    /*   1. The OS guard on isAvailable() works on non-Windows.           */
    /*   2. Construction is safe (the class' "lazy load" claim).          */
    /*   3. Live round-trip against Credential Manager, gated on a real   */
    /*      Windows host so Linux/Mac CI skips silently.                  */
    /* ------------------------------------------------------------------ */

    @Test
    public void windowsStore_isUnavailableOffWindows() {
        assumeTrue("Off-Windows check",
                !System.getProperty("os.name", "").toLowerCase().startsWith("windows"));
        assertFalse(new WindowsCredentialManagerStore().isAvailable());
    }

    @Test
    public void windowsStore_constructorDoesNotTriggerNativeLoad() {
        // The class doc promises lazy initialisation so it's safe to instantiate on
        // a non-Windows host — verify by constructing one. Eager native loading would
        // throw UnsatisfiedLinkError trying to open advapi32.dll on Linux/Mac.
        new WindowsCredentialManagerStore();
    }

    @Test
    public void windowsStore_roundTripsAgainstLiveCredentialManager() throws IOException {
        WindowsCredentialManagerStore store = new WindowsCredentialManagerStore();
        assumeTrue("Credential Manager not available (non-Windows host)", store.isAvailable());

        String service = "peergos-test-" + UUID.randomUUID();
        String account = "user";
        String value = "round-trip-" + UUID.randomUUID();
        try {
            store.put(service, account, value);
            assertEquals(Optional.of(value), store.get(service, account));
            store.delete(service, account);
            assertEquals(Optional.empty(), store.get(service, account));
        } finally {
            store.delete(service, account);
        }
    }

    /* ------------------------------------------------------------------ */
    /* MountConfigHandler.readConfig — where a saved mount is loaded, on  */
    /* every desktop start and every android process start.               */
    /* ------------------------------------------------------------------ */

    private Path writeConfig(MountConfig config) throws IOException {
        Path dir = tmp.newFolder().toPath();
        Files.write(dir.resolve(MountConfig.FILENAME),
                JSONParser.toString(config.toJson()).getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    @Test
    public void readConfig_missingFileIsDisabled() throws IOException {
        assertFalse(MountConfigHandler.readConfig(tmp.newFolder().toPath(), new MemorySecretStore()).enabled);
    }

    @Test
    public void readConfig_readsASavedConfig() throws IOException {
        MountConfig saved = new MountConfig(true, "alice", "pw", "dav-user", "dav-pass",
                8090, "digest", "", "");
        MountConfig read = MountConfigHandler.readConfig(writeConfig(saved), new JsonFileSecretStore());
        assertTrue(read.enabled);
        assertEquals("alice", read.peergosUsername);
        assertEquals(8090, read.webdavPort);
        assertEquals("digest", read.authType);
    }

    /** A keyring-backed store redacts the secrets from the file, so a restore that
     *  cannot splice them back in logs in with nothing and is rejected. */
    @Test
    public void readConfig_splicesKeyringSecretsBackIn() throws IOException {
        SecretStore store = new MemorySecretStore();
        store.put("peergos-mount", "alice:password", "the-password");
        MountConfig onDisk = new MountConfig(true, "alice", "pw", "dav-user", "dav-pass",
                8090, "digest", "", "").withoutSecrets();
        assertEquals("", onDisk.peergosPassword);
        MountConfig read = MountConfigHandler.readConfig(writeConfig(onDisk), store);
        assertEquals("the-password", read.peergosPassword);
    }
}
