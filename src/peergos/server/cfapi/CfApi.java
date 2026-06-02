package peergos.server.cfapi;

import peergos.server.util.Logging;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.logging.Logger;

/**
 * JDK Foreign Function & Memory (FFM) bindings for CldApi.dll.
 * Note: the header is cfapi.h but the actual DLL is CldApi.dll.
 *
 * Struct layout offsets are for Windows x64 ABI.  Every offset has a comment
 * showing the corresponding C field so they can be verified against the SDK.
 */
public class CfApi {
    private static final Logger LOG = Logging.LOG();

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    // CF_REGISTER_FLAGS
    public static final int CF_REGISTER_FLAG_NONE   = 0x0;
    public static final int CF_REGISTER_FLAG_UPDATE = 0x1;

    // CF_CONNECT_FLAGS
    public static final int CF_CONNECT_FLAG_NONE                  = 0x0;
    public static final int CF_CONNECT_FLAG_REQUIRE_PROCESS_INFO  = 0x2;
    public static final int CF_CONNECT_FLAG_REQUIRE_FULL_FILE_PATH = 0x4;

    // CF_CREATE_FLAGS
    public static final int CF_CREATE_FLAG_NONE          = 0x0;
    public static final int CF_CREATE_FLAG_STOP_ON_ERROR = 0x1;

    // CF_OPERATION_TYPE
    public static final int CF_OPERATION_TYPE_TRANSFER_DATA         = 1;
    public static final int CF_OPERATION_TYPE_TRANSFER_PLACEHOLDERS = 5;
    public static final int CF_OPERATION_TYPE_ACK_DELETE            = 7;
    public static final int CF_OPERATION_TYPE_ACK_RENAME_SOURCE     = 8;

    // CF_CALLBACK_TYPE
    public static final int CF_CALLBACK_TYPE_FETCH_DATA                     = 0;
    public static final int CF_CALLBACK_TYPE_FETCH_PLACEHOLDERS             = 3;
    public static final int CF_CALLBACK_TYPE_NOTIFY_FILE_CLOSE_COMPLETION   = 5;
    public static final int CF_CALLBACK_TYPE_DELETE_PLACEHOLDER             = 8;
    public static final int CF_CALLBACK_TYPE_RENAME_PLACEHOLDER             = 10;
    public static final int CF_CALLBACK_TYPE_RENAME_COMPLETION_PLACEHOLDER  = 11;
    public static final int CF_CALLBACK_TYPE_NONE                           = 0xFFFF_FFFF;

    // CF_CALLBACK_NOTIFY_FILE_CLOSE_COMPLETION_FLAGS
    public static final int CF_CALLBACK_NOTIFY_FILE_CLOSE_COMPLETION_FLAG_NONE    = 0x0;
    public static final int CF_CALLBACK_NOTIFY_FILE_CLOSE_COMPLETION_FLAG_DELETED = 0x1;

    // CF_CALLBACK_DELETE_FLAGS
    public static final int CF_CALLBACK_DELETE_FLAG_NONE                   = 0x0;
    public static final int CF_CALLBACK_DELETE_FLAG_IS_DIRECTORY           = 0x1;

    // CF_CALLBACK_RENAME_FLAGS
    public static final int CF_CALLBACK_RENAME_FLAG_NONE                   = 0x0;
    public static final int CF_CALLBACK_RENAME_FLAG_IS_DIRECTORY           = 0x1;
    public static final int CF_CALLBACK_RENAME_FLAG_SOURCE_IN_SCOPE        = 0x2;
    public static final int CF_CALLBACK_RENAME_FLAG_TARGET_IN_SCOPE        = 0x4;

    // CF_OPERATION_ACK_DELETE_FLAGS / CF_OPERATION_ACK_RENAME_SOURCE_FLAGS
    public static final int CF_OPERATION_ACK_FLAG_NONE = 0x0;

    // CF_HYDRATION_POLICY_PRIMARY
    public static final short CF_HYDRATION_POLICY_PARTIAL = 2;
    // CF_POPULATION_POLICY_PRIMARY
    public static final short CF_POPULATION_POLICY_PARTIAL = 2;

    // Modifiers (none)
    public static final short CF_POLICY_MODIFIER_NONE = 0;

    // CF_INSYNC_POLICY / CF_HARDLINK_POLICY
    public static final int CF_INSYNC_POLICY_NONE  = 0;
    public static final int CF_HARDLINK_POLICY_NONE = 0;

    // CF_PLACEHOLDER_CREATE_FLAGS
    public static final int CF_PLACEHOLDER_CREATE_FLAG_NONE        = 0x0;
    public static final int CF_PLACEHOLDER_CREATE_FLAG_MARK_IN_SYNC = 0x2;

    // CF_OPERATION_TRANSFER_DATA_FLAGS
    public static final int CF_OPERATION_TRANSFER_DATA_FLAG_NONE = 0x0;

    // CF_OPERATION_TRANSFER_PLACEHOLDERS_FLAGS
    public static final int CF_OPERATION_TRANSFER_PLACEHOLDERS_FLAG_NONE = 0x0;

    // NTSTATUS / HRESULT success
    public static final int S_OK       = 0;
    public static final int STATUS_SUCCESS = 0;

    // FILE_ATTRIBUTE flags
    public static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;
    public static final int FILE_ATTRIBUTE_NORMAL    = 0x80;

    // -----------------------------------------------------------------------
    // Struct sizes & field offsets  (Windows x64 ABI)
    // -----------------------------------------------------------------------

    // CF_SYNC_REGISTRATION (size 72)
    // The first field is StructSize (undocumented on MSDN but present in cfapi.h).
    //   +0   ULONG  StructSize                [+4 pad]
    //   +8   PCWSTR ProviderName
    //  +16   PCWSTR ProviderVersion
    //  +24   LPCVOID SyncRootIdentity
    //  +32   DWORD  SyncRootIdentityLength    [+4 pad]
    //  +40   LPCVOID FileIdentity
    //  +48   DWORD  FileIdentityLength
    //  +52   GUID   ProviderClsid  (16 bytes: DWORD+WORD+WORD+BYTE[8])
    //  +68   CF_SYNC_REGISTRATION_FLAGS Flags (DWORD)
    //  +72 end
    public static final long REG_SIZE                         = 72;
    public static final long REG_STRUCT_SIZE_OFF              =  0;
    public static final long REG_PROVIDER_NAME_OFF            =  8;
    public static final long REG_PROVIDER_VERSION_OFF         = 16;
    public static final long REG_SYNC_ROOT_IDENTITY_OFF       = 24;
    public static final long REG_SYNC_ROOT_IDENTITY_LEN_OFF   = 32;
    public static final long REG_FILE_IDENTITY_OFF            = 40;
    public static final long REG_FILE_IDENTITY_LEN_OFF        = 48;
    public static final long REG_PROVIDER_CLSID_OFF           = 52;

    // CF_SYNC_POLICIES (size 24, all 4-byte fields)
    //   +0  ULONG StructSize
    //   +4  CF_HYDRATION_POLICY Hydration  (USHORT Primary + USHORT Modifier)
    //   +8  CF_POPULATION_POLICY Population (USHORT Primary + USHORT Modifier)
    //  +12  CF_INSYNC_POLICY InSync
    //  +16  CF_HARDLINK_POLICY HardLink
    //  +20  CF_PLACEHOLDER_MANAGEMENT_POLICY PlaceholderManagement
    public static final long POLICIES_SIZE              = 24;
    public static final long POLICIES_STRUCT_SIZE_OFF   =  0;
    public static final long POLICIES_HYDRATION_OFF     =  4;  // Primary(u16) + Modifier(u16)
    public static final long POLICIES_POPULATION_OFF    =  8;  // Primary(u16) + Modifier(u16)
    public static final long POLICIES_INSYNC_OFF        = 12;
    public static final long POLICIES_HARDLINK_OFF      = 16;
    public static final long POLICIES_PLACEHOLDER_MGMT_OFF = 20;

    // FILE_BASIC_INFO (size 40)
    //   +0  LARGE_INTEGER CreationTime
    //   +8  LARGE_INTEGER LastAccessTime
    //  +16  LARGE_INTEGER LastWriteTime
    //  +24  LARGE_INTEGER ChangeTime
    //  +32  DWORD FileAttributes  [+4 pad → total 40]
    public static final long FBI_SIZE               = 40;
    public static final long FBI_CREATION_TIME_OFF  =  0;
    public static final long FBI_LAST_ACCESS_OFF    =  8;
    public static final long FBI_LAST_WRITE_OFF     = 16;
    public static final long FBI_CHANGE_TIME_OFF    = 24;
    public static final long FBI_FILE_ATTRIBUTES_OFF = 32;

    // CF_FS_METADATA (size 48)
    //   +0  FILE_BASIC_INFO BasicInfo  (40 bytes)
    //  +40  LARGE_INTEGER FileSize
    public static final long FSM_SIZE          = 48;
    public static final long FSM_BASIC_INFO_OFF =  0;
    public static final long FSM_FILE_SIZE_OFF  = 40;

    // CF_PLACEHOLDER_CREATE_INFO (size 88)
    //   +0   LPCWSTR RelativeFileName
    //   +8   CF_FS_METADATA FsMetadata   (48 bytes)
    //  +56   LPCVOID FileIdentity
    //  +64   DWORD FileIdentityLength
    //  +68   CF_PLACEHOLDER_CREATE_FLAGS Flags
    //  +72   HRESULT Result
    //  +76   [4 pad]
    //  +80   USN CreateUsn  (LONGLONG)
    public static final long PCI_SIZE                    = 88;
    public static final long PCI_RELATIVE_FILE_NAME_OFF  =  0;
    public static final long PCI_FS_METADATA_OFF         =  8;
    public static final long PCI_FILE_IDENTITY_OFF       = 56;
    public static final long PCI_FILE_IDENTITY_LEN_OFF   = 64;
    public static final long PCI_FLAGS_OFF               = 68;
    public static final long PCI_RESULT_OFF              = 72;
    public static final long PCI_CREATE_USN_OFF          = 80;

    // CF_CALLBACK_REGISTRATION (size 16 per entry)
    //   +0  CF_CALLBACK_TYPE Type  (DWORD)
    //   +4  [4 pad]
    //   +8  CF_CALLBACK Callback   (function pointer)
    public static final long CBR_ENTRY_SIZE   = 16;
    public static final long CBR_TYPE_OFF     =  0;
    public static final long CBR_CALLBACK_OFF =  8;

    // CF_OPERATION_INFO (size 48)
    //   +0   ULONG StructSize
    //   +4   CF_OPERATION_TYPE Type
    //   +8   CF_CONNECTION_KEY ConnectionKey  (LONGLONG)
    //  +16   LARGE_INTEGER TransferKey
    //  +24   const GUID* CorrelationVector    (pointer, can be null)
    //  +32   const CF_PROCESS_INFO* SyncStatus (pointer, can be null)
    //  +40   CF_REQUEST_KEY RequestKey         (LONGLONG)
    public static final long OI_SIZE               = 48;
    public static final long OI_STRUCT_SIZE_OFF    =  0;
    public static final long OI_TYPE_OFF           =  4;
    public static final long OI_CONNECTION_KEY_OFF =  8;
    public static final long OI_TRANSFER_KEY_OFF   = 16;
    public static final long OI_CORRELATION_VEC_OFF = 24;
    public static final long OI_SYNC_STATUS_OFF    = 32;
    public static final long OI_REQUEST_KEY_OFF    = 40;

    // CF_OPERATION_PARAMETERS for TRANSFER_DATA (size 40)
    //   +0   ULONG ParamSize
    //   +4   [4 pad — union requires 8-byte alignment due to LARGE_INTEGER members]
    //   +8   DWORD Flags
    //  +12   NTSTATUS CompletionStatus
    //  +16   LPCVOID Buffer
    //  +24   LARGE_INTEGER Offset
    //  +32   LARGE_INTEGER Length
    public static final long OP_XFER_DATA_SIZE          = 40;
    public static final long OP_PARAM_SIZE_OFF           =  0;
    public static final long OP_XFER_DATA_FLAGS_OFF      =  8;
    public static final long OP_XFER_DATA_STATUS_OFF     = 12;
    public static final long OP_XFER_DATA_BUFFER_OFF     = 16;
    public static final long OP_XFER_DATA_OFFSET_OFF     = 24;
    public static final long OP_XFER_DATA_LENGTH_OFF     = 32;

    // CF_OPERATION_PARAMETERS for TRANSFER_PLACEHOLDERS (size 40)
    //   +0   ULONG ParamSize
    //   +4   [4 pad]
    //   +8   DWORD Flags
    //  +12   NTSTATUS CompletionStatus
    //  +16   LARGE_INTEGER PlaceholderTotalCount
    //  +24   CF_PLACEHOLDER_CREATE_INFO* PlaceholderArray
    //  +32   DWORD PlaceholderCount
    //  +36   DWORD EntriesProcessed (output)
    public static final long OP_XFER_PH_SIZE             = 40;
    public static final long OP_XFER_PH_FLAGS_OFF        =  8;
    public static final long OP_XFER_PH_STATUS_OFF       = 12;
    public static final long OP_XFER_PH_TOTAL_COUNT_OFF  = 16;
    public static final long OP_XFER_PH_ARRAY_OFF        = 24;
    public static final long OP_XFER_PH_COUNT_OFF        = 32;

    // CF_OPERATION_PARAMETERS for ACK_DELETE / ACK_RENAME_SOURCE (size 16)
    //   +0  ULONG ParamSize
    //   +4  [4 pad — same union alignment rule]
    //   +8  DWORD Flags
    //  +12  NTSTATUS CompletionStatus
    public static final long OP_ACK_SIZE           = 16;
    public static final long OP_ACK_FLAGS_OFF      =  8;
    public static final long OP_ACK_STATUS_OFF     = 12;

    // CF_CALLBACK_PARAMETERS offsets for NOTIFY_FILE_CLOSE_COMPLETION
    //  +0  ULONG ParamSize
    //  +4  [4 pad]
    //  +8  CF_CALLBACK_NOTIFY_FILE_CLOSE_COMPLETION_FLAGS Flags (DWORD)
    // +12  BOOLEAN IsCreatedPlaceholder (BYTE)
    public static final long CBP_CLOSE_FLAGS_OFF              =  8;
    public static final long CBP_CLOSE_IS_CREATED_PH_OFF      = 12;

    // CF_CALLBACK_PARAMETERS offsets for DELETE_PLACEHOLDER
    //  +8  CF_CALLBACK_DELETE_FLAGS Flags (DWORD)
    public static final long CBP_DELETE_FLAGS_OFF = 8;

    // CF_CALLBACK_PARAMETERS offsets for RENAME_PLACEHOLDER
    //  +8  CF_CALLBACK_RENAME_FLAGS Flags (DWORD)
    //  +12 [4 pad]
    //  +16 PCWSTR TargetPath (pointer)
    public static final long CBP_RENAME_FLAGS_OFF       =  8;
    public static final long CBP_RENAME_TARGET_PATH_OFF = 16;

    // CF_CALLBACK_PARAMETERS offsets for RENAME_COMPLETION_PLACEHOLDER
    //  +8  CF_CALLBACK_RENAME_COMPLETION_FLAGS Flags (DWORD)
    //  +12 [4 pad]
    //  +16 PCWSTR SourcePath (pointer)
    public static final long CBP_RENAME_COMPLETION_FLAGS_OFF       =  8;
    public static final long CBP_RENAME_COMPLETION_SOURCE_PATH_OFF = 16;

    // CF_CALLBACK_INFO field offsets (size 152)
    //   +0   DWORD StructSize
    //   +4   [4 pad]
    //   +8   CF_CONNECTION_KEY ConnectionKey  (LONGLONG)
    //  +16   LPVOID CallbackContext
    //  +24   PCWSTR VolumeGuidName
    //  +32   PCWSTR VolumeDosName
    //  +40   DWORD VolumeSerialNumber  [+4 pad]
    //  +48   LARGE_INTEGER SyncRootFileId
    //  +56   LPCVOID SyncRootIdentity
    //  +64   DWORD SyncRootIdentityLength  [+4 pad]
    //  +72   LARGE_INTEGER FileId
    //  +80   LARGE_INTEGER FileSize
    //  +88   LPCVOID FileIdentity
    //  +96   DWORD FileIdentityLength  [+4 pad]
    // +104   PCWSTR NormalizedPath
    // +112   LARGE_INTEGER TransferKey
    // +120   UCHAR PriorityHint  [+7 pad]
    // +128   PCCORRELATION_VECTOR CorrelationVector
    // +136   CF_PROCESS_INFO* ProcessInfo
    // +144   CF_REQUEST_KEY RequestKey  (LONGLONG)
    public static final long CBI_CONNECTION_KEY_OFF    =   8;
    public static final long CBI_FILE_SIZE_OFF         =  80;
    public static final long CBI_FILE_IDENTITY_OFF     =  88;
    public static final long CBI_FILE_IDENTITY_LEN_OFF =  96;
    public static final long CBI_NORMALIZED_PATH_OFF   = 104;
    public static final long CBI_TRANSFER_KEY_OFF      = 112;

    // CF_CALLBACK_PARAMETERS offsets for FETCH_DATA
    //  +0   ULONG ParamSize
    //  +4   [4 pad]
    //  +8   DWORD Flags  (FetchData.Flags)
    //  +12  [4 pad]
    //  +16  LARGE_INTEGER RequiredFileOffset
    //  +24  LARGE_INTEGER RequiredLength
    public static final long CBP_FETCH_DATA_FLAGS_OFF           =  8;
    public static final long CBP_FETCH_DATA_REQUIRED_OFFSET_OFF = 16;
    public static final long CBP_FETCH_DATA_REQUIRED_LENGTH_OFF = 24;

    // CF_CALLBACK_PARAMETERS offsets for FETCH_PLACEHOLDERS
    //  +8   DWORD Flags
    //  +12  [4 pad]
    //  +16  PCWSTR Pattern
    public static final long CBP_FETCH_PH_FLAGS_OFF   =  8;
    public static final long CBP_FETCH_PH_PATTERN_OFF = 16;

    // -----------------------------------------------------------------------
    // Function handles (loaded lazily on first use)
    // -----------------------------------------------------------------------

    private static volatile boolean loaded = false;
    private static MethodHandle hCfRegisterSyncRoot;
    private static MethodHandle hCfUnregisterSyncRoot;
    private static MethodHandle hCfConnectSyncRoot;
    private static MethodHandle hCfDisconnectSyncRoot;
    private static MethodHandle hCfCreatePlaceholders;
    private static MethodHandle hCfExecute;
    private static MethodHandle hVirtualAlloc;
    private static MethodHandle hVirtualFree;

    // MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE
    private static final int MEM_COMMIT_RESERVE = 0x3000;
    private static final int PAGE_READWRITE      = 0x4;
    private static final int MEM_RELEASE         = 0x8000;

    public static synchronized void load() {
        if (loaded) return;
        // The CF API header is cfapi.h but the actual DLL is CldApi.dll.
        // Use the full path because System32 is not on the JVM's default library path.
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null) systemRoot = "C:\\Windows";
        java.nio.file.Path cldapiPath = java.nio.file.Path.of(systemRoot, "System32", "cldapi.dll");
        SymbolLookup cfapi = SymbolLookup.libraryLookup(cldapiPath, Arena.global());
        Linker linker = Linker.nativeLinker();

        // HRESULT CfRegisterSyncRoot(LPCWSTR, const CF_SYNC_REGISTRATION*, const CF_SYNC_POLICIES*, CF_REGISTER_FLAGS)
        hCfRegisterSyncRoot = linker.downcallHandle(
                cfapi.find("CfRegisterSyncRoot").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

        // HRESULT CfUnregisterSyncRoot(LPCWSTR)
        hCfUnregisterSyncRoot = linker.downcallHandle(
                cfapi.find("CfUnregisterSyncRoot").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        // HRESULT CfConnectSyncRoot(LPCWSTR, const CF_CALLBACK_REGISTRATION*, LPVOID, CF_CONNECT_FLAGS, CF_CONNECTION_KEY*)
        hCfConnectSyncRoot = linker.downcallHandle(
                cfapi.find("CfConnectSyncRoot").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        // HRESULT CfDisconnectSyncRoot(CF_CONNECTION_KEY)  — struct passed by value as LONGLONG on x64
        hCfDisconnectSyncRoot = linker.downcallHandle(
                cfapi.find("CfDisconnectSyncRoot").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

        // HRESULT CfCreatePlaceholders(LPCWSTR, CF_PLACEHOLDER_CREATE_INFO*, DWORD, CF_CREATE_FLAGS, PDWORD)
        hCfCreatePlaceholders = linker.downcallHandle(
                cfapi.find("CfCreatePlaceholders").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        // HRESULT CfExecute(const CF_OPERATION_INFO*, CF_OPERATION_PARAMETERS*)
        hCfExecute = linker.downcallHandle(
                cfapi.find("CfExecute").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        // LPVOID VirtualAlloc(LPVOID lpAddress, SIZE_T dwSize, DWORD flAllocationType, DWORD flProtect)
        SymbolLookup kernel32 = SymbolLookup.libraryLookup(
                java.nio.file.Path.of(systemRoot, "System32", "kernel32.dll"), Arena.global());
        hVirtualAlloc = linker.downcallHandle(
                kernel32.find("VirtualAlloc").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        // BOOL VirtualFree(LPVOID lpAddress, SIZE_T dwSize, DWORD dwFreeType)
        hVirtualFree = linker.downcallHandle(
                kernel32.find("VirtualFree").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));

        loaded = true;
    }

    // -----------------------------------------------------------------------
    // Wrapper methods
    // -----------------------------------------------------------------------

    public static int cfRegisterSyncRoot(MemorySegment path, MemorySegment reg, MemorySegment policies, int flags) {
        try {
            return (int) hCfRegisterSyncRoot.invokeExact(path, reg, policies, flags);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static int cfUnregisterSyncRoot(MemorySegment path) {
        try {
            return (int) hCfUnregisterSyncRoot.invokeExact(path);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static int cfConnectSyncRoot(MemorySegment path, MemorySegment callbackTable,
                                        MemorySegment context, int flags, MemorySegment connectionKeyOut) {
        try {
            return (int) hCfConnectSyncRoot.invokeExact(path, callbackTable, context, flags, connectionKeyOut);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static int cfDisconnectSyncRoot(long connectionKey) {
        try {
            return (int) hCfDisconnectSyncRoot.invokeExact(connectionKey);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static int cfCreatePlaceholders(MemorySegment baseDir, MemorySegment array, int count,
                                           int flags, MemorySegment entriesProcessed) {
        try {
            return (int) hCfCreatePlaceholders.invokeExact(baseDir, array, count, flags, entriesProcessed);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static int cfExecute(MemorySegment opInfo, MemorySegment opParams) {
        try {
            return (int) hCfExecute.invokeExact(opInfo, opParams);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    /**
     * Allocate a page-aligned buffer via VirtualAlloc (MEM_COMMIT|MEM_RESERVE, PAGE_READWRITE).
     * Returns a zero-initialised segment of the requested size, or NULL on failure.
     */
    public static MemorySegment virtualAlloc(long size) {
        try {
            return (MemorySegment) hVirtualAlloc.invokeExact(
                    MemorySegment.NULL, size, MEM_COMMIT_RESERVE, PAGE_READWRITE);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    /** Free a buffer previously returned by virtualAlloc. */
    public static void virtualFree(MemorySegment ptr) {
        try {
            hVirtualFree.invokeExact(ptr, 0L, MEM_RELEASE);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    // -----------------------------------------------------------------------
    // Upcall stub creation
    // -----------------------------------------------------------------------

    /**
     * Creates a native function pointer (upcall stub) for a CF_CALLBACK:
     *   void callback(const CF_CALLBACK_INFO*, const CF_CALLBACK_PARAMETERS*)
     * The Arena controls stub lifetime — keep it alive for the connection duration.
     */
    public static MemorySegment upcallStub(CfCallback callback, Arena arena) {
        MethodHandle handle;
        try {
            handle = MethodHandles.lookup().findVirtual(CfCallback.class, "invoke",
                    MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class))
                    .bindTo(callback);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return Linker.nativeLinker().upcallStub(handle,
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                arena);
    }

    // -----------------------------------------------------------------------
    // Helpers for encoding wide strings and building structs
    // -----------------------------------------------------------------------

    /** Allocate a null-terminated UTF-16LE wide string in the given Arena. */
    public static MemorySegment wideString(String s, Arena arena) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        // allocate len + 2 bytes for the null terminator (UTF-16 null = 2 bytes)
        MemorySegment seg = arena.allocate(bytes.length + 2);
        MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0, bytes.length);
        seg.set(ValueLayout.JAVA_SHORT, bytes.length, (short) 0);
        return seg;
    }

    /**
     * Read a null-terminated UTF-16LE wide string from a pointer stored at the given
     * offset within a MemorySegment (i.e., dereference the pointer then decode).
     */
    public static String readWideString(MemorySegment base, long ptrOffset) {
        long addr = base.get(ValueLayout.JAVA_LONG, ptrOffset);
        if (addr == 0) return "";
        MemorySegment ptr = MemorySegment.ofAddress(addr)
                .reinterpret(Long.MAX_VALUE);
        // Find null terminator (two consecutive zero bytes aligned to 2)
        int len = 0;
        while (ptr.get(ValueLayout.JAVA_SHORT, len * 2L) != 0) len++;
        byte[] bytes = new byte[len * 2];
        MemorySegment.copy(ptr, ValueLayout.JAVA_BYTE, 0, bytes, 0, bytes.length);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_16LE);
    }

    /** Windows FILETIME: 100-nanosecond intervals since 1601-01-01. */
    public static long toFileTime(java.time.LocalDateTime dt) {
        if (dt == null) return 0;
        // Seconds from 1601-01-01 to Unix epoch (1970-01-01) = 11644473600
        long epochSeconds = dt.toEpochSecond(java.time.ZoneOffset.UTC);
        return (epochSeconds + 11_644_473_600L) * 10_000_000L;
    }
}
