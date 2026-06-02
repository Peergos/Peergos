package peergos.server.cfapi;

import peergos.server.util.Logging;
import peergos.shared.user.UserContext;

import java.io.Closeable;
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

    private CloudFilesMount(String syncRootPath, long connectionKey, Arena callbackArena) {
        this.syncRootPath  = syncRootPath;
        this.connectionKey = connectionKey;
        this.callbackArena = callbackArena;
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
        policies.set(ValueLayout.JAVA_SHORT, CfApi.POLICIES_HYDRATION_OFF,    CfApi.CF_HYDRATION_POLICY_PARTIAL);
        policies.set(ValueLayout.JAVA_SHORT, CfApi.POLICIES_HYDRATION_OFF + 2, CfApi.CF_POLICY_MODIFIER_NONE);
        policies.set(ValueLayout.JAVA_SHORT, CfApi.POLICIES_POPULATION_OFF,   CfApi.CF_POPULATION_POLICY_PARTIAL);
        policies.set(ValueLayout.JAVA_SHORT, CfApi.POLICIES_POPULATION_OFF + 2, CfApi.CF_POLICY_MODIFIER_NONE);
        policies.set(ValueLayout.JAVA_INT,   CfApi.POLICIES_INSYNC_OFF,       CfApi.CF_INSYNC_POLICY_NONE);
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

        MemorySegment fetchDataStub             = CfApi.upcallStub(provider::onFetchData,                   callbackArena);
        MemorySegment fetchPlaceholdersStub      = CfApi.upcallStub(provider::onFetchPlaceholders,            callbackArena);
        MemorySegment closeCompletionStub         = CfApi.upcallStub(provider::onFileCloseCompletion,         callbackArena);
        MemorySegment deletePlaceholderStub       = CfApi.upcallStub(provider::onDeletePlaceholder,           callbackArena);
        MemorySegment renamePlaceholderStub       = CfApi.upcallStub(provider::onRenamePlaceholder,           callbackArena);
        MemorySegment renameCompletionStub        = CfApi.upcallStub(provider::onRenameCompletionPlaceholder, callbackArena);

        // CF_CALLBACK_REGISTRATION array terminated with CF_CALLBACK_TYPE_NONE sentinel
        MemorySegment cbTable = callbackArena.allocate(CfApi.CBR_ENTRY_SIZE * 7);

        writeCbEntry(cbTable, 0, CfApi.CF_CALLBACK_TYPE_FETCH_DATA,                    fetchDataStub);
        writeCbEntry(cbTable, 1, CfApi.CF_CALLBACK_TYPE_FETCH_PLACEHOLDERS,            fetchPlaceholdersStub);
        writeCbEntry(cbTable, 2, CfApi.CF_CALLBACK_TYPE_NOTIFY_FILE_CLOSE_COMPLETION,  closeCompletionStub);
        writeCbEntry(cbTable, 3, CfApi.CF_CALLBACK_TYPE_DELETE_PLACEHOLDER,            deletePlaceholderStub);
        writeCbEntry(cbTable, 4, CfApi.CF_CALLBACK_TYPE_RENAME_PLACEHOLDER,            renamePlaceholderStub);
        writeCbEntry(cbTable, 5, CfApi.CF_CALLBACK_TYPE_RENAME_COMPLETION_PLACEHOLDER, renameCompletionStub);
        writeCbEntry(cbTable, 6, CfApi.CF_CALLBACK_TYPE_NONE,                          MemorySegment.NULL);

        // -- Connect --
        MemorySegment connectionKeyOut = callbackArena.allocate(ValueLayout.JAVA_LONG);
        System.err.println("[CF] CfConnectSyncRoot: cbTable=0x" + Long.toHexString(cbTable.address())
                + " fetchDataStub=0x" + Long.toHexString(fetchDataStub.address()));
        hr = CfApi.cfConnectSyncRoot(pathW, cbTable, MemorySegment.NULL,
                CfApi.CF_CONNECT_FLAG_NONE,
                connectionKeyOut);
        if (hr != CfApi.S_OK) {
            callbackArena.close();
            throw new Exception("CfConnectSyncRoot failed: 0x" + Integer.toHexString(hr));
        }
        long connectionKey = connectionKeyOut.get(ValueLayout.JAVA_LONG, 0);
        System.err.println("[CF] Connected key=" + connectionKey);

        // Seed top-level placeholders so Explorer shows files immediately
        try (Arena seedArena = Arena.ofConfined()) {
            provider.seedRootPlaceholders(seedArena);
        }

        CloudFilesMount m = new CloudFilesMount(syncRootPath, connectionKey, callbackArena);
        Runtime.getRuntime().addShutdownHook(new Thread(m::close, "CF unmount"));
        return m;
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    @Override
    public void close() {
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
