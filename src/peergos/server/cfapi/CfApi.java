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
    public static final int CF_CONNECT_FLAG_NONE                          = 0x0;
    public static final int CF_CONNECT_FLAG_REQUIRE_PROCESS_INFO          = 0x2;
    public static final int CF_CONNECT_FLAG_REQUIRE_FULL_FILE_PATH        = 0x4;
    public static final int CF_CONNECT_FLAG_BLOCK_SELF_IMPLICIT_HYDRATION = 0x8;

    // CF_CREATE_FLAGS
    public static final int CF_CREATE_FLAG_NONE          = 0x0;
    public static final int CF_CREATE_FLAG_STOP_ON_ERROR = 0x1;

    // CF_OPERATION_TYPE — 0-indexed per MSDN. We had these off by one which caused
    // CfExecute(TRANSFER_PLACEHOLDERS) to be interpreted as ACK_DELETE → no-op,
    // and CfExecute(TRANSFER_DATA) to be interpreted as RETRIEVE_DATA → invalid.
    public static final int CF_OPERATION_TYPE_TRANSFER_DATA         = 0;
    public static final int CF_OPERATION_TYPE_RETRIEVE_DATA         = 1;
    public static final int CF_OPERATION_TYPE_ACK_DATA              = 2;
    public static final int CF_OPERATION_TYPE_RESTART_HYDRATION     = 3;
    public static final int CF_OPERATION_TYPE_TRANSFER_PLACEHOLDERS = 4;
    public static final int CF_OPERATION_TYPE_ACK_DEHYDRATE         = 5;
    public static final int CF_OPERATION_TYPE_ACK_DELETE            = 6;
    public static final int CF_OPERATION_TYPE_ACK_RENAME            = 7;

    // CF_CALLBACK_TYPE — corrected to MSDN values. Previously off-by-one: we had close
    // completion at 5 (which is actually FILE_OPEN_COMPLETION), and delete/rename at
    // values that meant dehydrate/delete completion events.
    public static final int CF_CALLBACK_TYPE_FETCH_DATA                       = 0;
    public static final int CF_CALLBACK_TYPE_VALIDATE_DATA                    = 1;
    public static final int CF_CALLBACK_TYPE_CANCEL_FETCH_DATA                = 2;
    public static final int CF_CALLBACK_TYPE_FETCH_PLACEHOLDERS               = 3;
    public static final int CF_CALLBACK_TYPE_CANCEL_FETCH_PLACEHOLDERS        = 4;
    public static final int CF_CALLBACK_TYPE_NOTIFY_FILE_OPEN_COMPLETION      = 5;
    public static final int CF_CALLBACK_TYPE_NOTIFY_FILE_CLOSE_COMPLETION     = 6;
    public static final int CF_CALLBACK_TYPE_NOTIFY_DEHYDRATE                 = 7;
    public static final int CF_CALLBACK_TYPE_NOTIFY_DEHYDRATE_COMPLETION      = 8;
    public static final int CF_CALLBACK_TYPE_NOTIFY_DELETE                    = 9;
    public static final int CF_CALLBACK_TYPE_NOTIFY_DELETE_COMPLETION         = 10;
    public static final int CF_CALLBACK_TYPE_NOTIFY_RENAME                    = 11;
    public static final int CF_CALLBACK_TYPE_NOTIFY_RENAME_COMPLETION         = 12;
    public static final int CF_CALLBACK_TYPE_NONE                             = 0xFFFF_FFFF;

    // CF_CALLBACK_FETCH_DATA_FLAGS
    public static final int CF_CALLBACK_FETCH_DATA_FLAG_NONE               = 0x0;
    public static final int CF_CALLBACK_FETCH_DATA_FLAG_RECOVERY           = 0x1;
    public static final int CF_CALLBACK_FETCH_DATA_FLAG_EXPLICIT_HYDRATION = 0x2;

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

    // CF_HYDRATION_POLICY_PRIMARY (MSDN values)
    public static final short CF_HYDRATION_POLICY_PARTIAL     = 0;
    public static final short CF_HYDRATION_POLICY_PROGRESSIVE = 1;
    public static final short CF_HYDRATION_POLICY_FULL        = 2;
    public static final short CF_HYDRATION_POLICY_ALWAYS_FULL = 3;
    // CF_POPULATION_POLICY_PRIMARY (MSDN values)
    public static final short CF_POPULATION_POLICY_PARTIAL    = 0;
    public static final short CF_POPULATION_POLICY_FULL       = 2;
    public static final short CF_POPULATION_POLICY_ALWAYS_FULL = 3;

    // Modifiers (none)
    public static final short CF_POLICY_MODIFIER_NONE = 0;

    // CF_INSYNC_POLICY (bit flags per MSDN)
    public static final int CF_INSYNC_POLICY_NONE                            = 0x00000000;
    public static final int CF_INSYNC_POLICY_TRACK_FILE_ALL                  = 0x0000ffff;
    public static final int CF_INSYNC_POLICY_TRACK_DIRECTORY_ALL             = 0xffff0000;
    public static final int CF_INSYNC_POLICY_TRACK_ALL                       = 0xffffffff;
    public static final int CF_INSYNC_POLICY_PRESERVE_INSYNC_FOR_SYNC_ENGINE = 0x80000000;
    public static final int CF_HARDLINK_POLICY_NONE = 0;

    // CF_PLACEHOLDER_CREATE_FLAGS
    public static final int CF_PLACEHOLDER_CREATE_FLAG_NONE                          = 0x0;
    public static final int CF_PLACEHOLDER_CREATE_FLAG_DISABLE_ON_DEMAND_POPULATION_P = 0x1;
    public static final int CF_PLACEHOLDER_CREATE_FLAG_MARK_IN_SYNC                  = 0x2;
    public static final int CF_PLACEHOLDER_CREATE_FLAG_SUPERSEDE                     = 0x4;
    public static final int CF_PLACEHOLDER_CREATE_FLAG_ALWAYS_FULL                   = 0x8;

    // CF_OPERATION_TRANSFER_DATA_FLAGS
    public static final int CF_OPERATION_TRANSFER_DATA_FLAG_NONE = 0x0;

    // CF_OPERATION_TRANSFER_PLACEHOLDERS_FLAGS (MSDN values)
    public static final int CF_OPERATION_TRANSFER_PLACEHOLDERS_FLAG_NONE                       = 0x0;
    public static final int CF_OPERATION_TRANSFER_PLACEHOLDERS_FLAG_STOP_ON_ERROR              = 0x1;
    public static final int CF_OPERATION_TRANSFER_PLACEHOLDERS_FLAG_DISABLE_ON_DEMAND_POPULATION = 0x2;

    // NTSTATUS / HRESULT success
    public static final int S_OK       = 0;
    public static final int STATUS_SUCCESS = 0;

    // FILE_ATTRIBUTE flags
    public static final int FILE_ATTRIBUTE_DIRECTORY              = 0x10;
    public static final int FILE_ATTRIBUTE_NORMAL                 = 0x80;
    public static final int FILE_ATTRIBUTE_OFFLINE                = 0x1000;
    public static final int FILE_ATTRIBUTE_RECALL_ON_OPEN         = 0x40000;
    public static final int FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS  = 0x400000;
    public static final int INVALID_FILE_ATTRIBUTES               = 0xFFFFFFFF;
    /** Bitmask: any of these set means the file/dir is a cloud placeholder we shouldn't
     *  force-enumerate or read locally. */
    public static final int CF_PLACEHOLDER_ATTRIBUTES_MASK =
            FILE_ATTRIBUTE_OFFLINE
            | FILE_ATTRIBUTE_RECALL_ON_OPEN
            | FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS;

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

    // CF_OPERATION_INFO (size 48) — MSDN order: SyncStatus BEFORE RequestKey.
    //   +0   ULONG StructSize
    //   +4   CF_OPERATION_TYPE Type
    //   +8   CF_CONNECTION_KEY ConnectionKey  (LONGLONG)
    //  +16   LARGE_INTEGER TransferKey
    //  +24   const GUID* CorrelationVector    (pointer, can be null)
    //  +32   CF_SYNC_STATUS* SyncStatus        (pointer, can be null)
    //  +40   CF_REQUEST_KEY RequestKey         (LONGLONG)
    // (Previously had these swapped, which worked for TRANSFER_DATA/PLACEHOLDERS but
    // crashed cldapi during ACK_RENAME/ACK_DELETE — cldapi dereferenced our requestKey
    // value as the SyncStatus pointer.)
    public static final long OI_SIZE                = 48;
    public static final long OI_STRUCT_SIZE_OFF     =  0;
    public static final long OI_TYPE_OFF            =  4;
    public static final long OI_CONNECTION_KEY_OFF  =  8;
    public static final long OI_TRANSFER_KEY_OFF    = 16;
    public static final long OI_CORRELATION_VEC_OFF = 24;
    public static final long OI_SYNC_STATUS_OFF     = 32;
    public static final long OI_REQUEST_KEY_OFF     = 40;

    // CF_OPERATION_PARAMETERS for TRANSFER_DATA (size 40)
    //   +0   ULONG ParamSize
    //   +4   [4 pad — union requires 8-byte alignment due to LARGE_INTEGER members]
    //   +8   DWORD Flags
    //  +12   NTSTATUS CompletionStatus
    //  +16   LPCVOID Buffer    (cfapi.h order: Buffer BEFORE Offset, confirmed by Nextcloud)
    //  +24   LARGE_INTEGER Offset
    //  +32   LARGE_INTEGER Length
    public static final long OP_XFER_DATA_SIZE          = 40;
    public static final long OP_PARAM_SIZE_OFF           =  0;
    public static final long OP_XFER_DATA_FLAGS_OFF      =  8;
    public static final long OP_XFER_DATA_STATUS_OFF     = 12;
    public static final long OP_XFER_DATA_BUFFER_OFF     = 16;
    public static final long OP_XFER_DATA_OFFSET_OFF     = 24;
    public static final long OP_XFER_DATA_LENGTH_OFF     = 32;

    // CF_OPERATION_PARAMETERS for TRANSFER_PLACEHOLDERS (size 40) — MSDN documented layout.
    // cldapi DOES use the documented field order (verified by hs_err: writing PlaceholderArray
    // at +16 makes cldapi read TotalCount=2 from +16 as a pointer → crash reading address 2).
    //   +0   ULONG ParamSize
    //   +4   [4 pad — union alignment to 8]
    //   +8   DWORD Flags
    //  +12   NTSTATUS CompletionStatus
    //  +16   LARGE_INTEGER PlaceholderTotalCount    (8 bytes, must be set to count)
    //  +24   CF_PLACEHOLDER_CREATE_INFO* PlaceholderArray  (8 bytes)
    //  +32   DWORD PlaceholderCount
    //  +36   DWORD EntriesProcessed (output)
    public static final long OP_XFER_PH_SIZE                  = 40;
    public static final long OP_XFER_PH_FLAGS_OFF             =  8;
    public static final long OP_XFER_PH_STATUS_OFF            = 12;
    public static final long OP_XFER_PH_TOTAL_COUNT_OFF       = 16;
    public static final long OP_XFER_PH_ARRAY_OFF             = 24;
    public static final long OP_XFER_PH_COUNT_OFF             = 32;
    public static final long OP_XFER_PH_ENTRIES_PROCESSED_OFF = 36;

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
    public static final long CBI_PROCESS_INFO_OFF      = 136;
    public static final long CBI_REQUEST_KEY_OFF       = 144;

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
    private static MethodHandle hCfConvertToPlaceholder;
    private static MethodHandle hCfExecute;
    private static MethodHandle hCfReportProviderProgress;
    private static MethodHandle hCfHydratePlaceholder;
    private static MethodHandle hVirtualAlloc;
    private static MethodHandle hVirtualFree;
    private static MethodHandle hCreateFileW;
    private static MethodHandle hGetFileAttributesW;
    private static MethodHandle hCloseHandle;
    private static MethodHandle hGetProcessHeap;
    private static MethodHandle hHeapAlloc;
    private static MethodHandle hHeapFree;
    private static MethodHandle hSleepEx;

    // MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE
    private static final int MEM_COMMIT_RESERVE = 0x3000;
    private static final int PAGE_READWRITE      = 0x4;
    private static final int MEM_RELEASE         = 0x8000;

    // CreateFile constants
    public static final int  GENERIC_READ                = 0x80000000;
    public static final int  WRITE_DAC                   = 0x00040000;
    public static final int  FILE_SHARE_READ             = 0x00000001;
    public static final int  FILE_SHARE_WRITE            = 0x00000002;
    public static final int  FILE_SHARE_DELETE           = 0x00000004;
    public static final int  OPEN_EXISTING               = 3;
    public static final int  FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000;
    public static final long INVALID_HANDLE_VALUE        = -1L;

    // CF_HYDRATE_FLAGS
    public static final int CF_HYDRATE_FLAG_NONE = 0x0;

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

        // HRESULT CfReportProviderProgress(CF_CONNECTION_KEY, CF_TRANSFER_KEY, LARGE_INTEGER total, LARGE_INTEGER completed)
        // Both LARGE_INTEGER values are passed by value as 8-byte values on x64.
        hCfReportProviderProgress = linker.downcallHandle(
                cfapi.find("CfReportProviderProgress").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

        // HRESULT CfHydratePlaceholder(HANDLE, LARGE_INTEGER startingOffset, LARGE_INTEGER length, CF_HYDRATE_FLAGS)
        // LARGE_INTEGER is 8 bytes and passed by value in a single register on x64
        hCfHydratePlaceholder = linker.downcallHandle(
                cfapi.find("CfHydratePlaceholder").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT));

        // HRESULT CfConvertToPlaceholder(HANDLE, LPCVOID FileIdentity, DWORD FileIdentityLength,
        //                                CF_CONVERT_FLAGS, USN* ConvertUsn, OVERLAPPED*)
        hCfConvertToPlaceholder = linker.downcallHandle(
                cfapi.find("CfConvertToPlaceholder").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

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

        // HANDLE CreateFileW(LPCWSTR, DWORD, DWORD, LPSECURITY_ATTRIBUTES, DWORD, DWORD, HANDLE)
        hCreateFileW = linker.downcallHandle(
                kernel32.find("CreateFileW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS));

        // BOOL CloseHandle(HANDLE)
        hCloseHandle = linker.downcallHandle(
                kernel32.find("CloseHandle").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        // DWORD GetFileAttributesW(LPCWSTR lpFileName)
        // Returns the file attribute bitfield, or INVALID_FILE_ATTRIBUTES (0xFFFFFFFF) on failure.
        // Reading attributes does NOT trigger CF hydration / placeholder enumeration — safe to
        // call on every directory at mount time.
        hGetFileAttributesW = linker.downcallHandle(
                kernel32.find("GetFileAttributesW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        // HANDLE GetProcessHeap(void)
        hGetProcessHeap = linker.downcallHandle(
                kernel32.find("GetProcessHeap").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS));

        // LPVOID HeapAlloc(HANDLE hHeap, DWORD dwFlags, SIZE_T dwBytes)
        hHeapAlloc = linker.downcallHandle(
                kernel32.find("HeapAlloc").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

        // BOOL HeapFree(HANDLE hHeap, DWORD dwFlags, LPVOID lpMem)
        hHeapFree = linker.downcallHandle(
                kernel32.find("HeapFree").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        // DWORD SleepEx(DWORD dwMilliseconds, BOOL bAlertable)
        hSleepEx = linker.downcallHandle(
                kernel32.find("SleepEx").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        loaded = true;
    }

    /**
     * Sleep with alertable=TRUE to allow pending APCs (Asynchronous Procedure Calls)
     * to fire on this thread. CF may queue APCs for hydration delivery that only
     * execute when the thread enters an alertable wait state.
     */
    public static int sleepAlertable(int millis) {
        try {
            return (int) hSleepEx.invokeExact(millis, 1);
        } catch (Throwable t) { throw new RuntimeException(t); }
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

    public static int cfReportProviderProgress(long connectionKey, long transferKey, long total, long completed) {
        try {
            return (int) hCfReportProviderProgress.invokeExact(connectionKey, transferKey, total, completed);
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

    /**
     * Allocate from the process heap via Win32 HeapAlloc(GetProcessHeap(), 0, size).
     * Matches Microsoft CloudMirror's buffer allocation for CfExecute(TRANSFER_DATA).
     * Returns a segment of the requested size, or NULL on failure.
     */
    public static MemorySegment heapAlloc(long size) {
        try {
            MemorySegment heap = (MemorySegment) hGetProcessHeap.invokeExact();
            MemorySegment ptr = (MemorySegment) hHeapAlloc.invokeExact(heap, 0, size);
            return ptr.reinterpret(size);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    /** Free a buffer previously returned by heapAlloc. */
    public static void heapFree(MemorySegment ptr) {
        try {
            MemorySegment heap = (MemorySegment) hGetProcessHeap.invokeExact();
            hHeapFree.invokeExact(heap, 0, ptr);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    /**
     * Open a placeholder file for use with CfHydratePlaceholder.
     * WRITE_DAC satisfies CF's "READ_DATA or WRITE_DAC" requirement without triggering data
     * hydration on open. We intentionally do NOT use FILE_FLAG_OPEN_REPARSE_POINT: that flag
     * bypasses the CF filter driver, which causes CfExecute to return E_INVALIDARG because CF
     * cannot associate the handle with its internal placeholder management state.
     * Returns INVALID_HANDLE_VALUE on failure.
     */
    public static MemorySegment createFileForHydration(MemorySegment pathW) {
        try {
            return (MemorySegment) hCreateFileW.invokeExact(
                    pathW,
                    WRITE_DAC,
                    FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                    MemorySegment.NULL,
                    OPEN_EXISTING,
                    FILE_ATTRIBUTE_NORMAL,
                    MemorySegment.NULL);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    /**
     * Explicitly hydrate a placeholder file.
     * offset=0 and length=-1L hydrate the entire file.
     * This fires FETCH_DATA via a non-locking path, safe to call from the same process.
     */
    public static int cfHydratePlaceholder(MemorySegment fileHandle, long offset, long length, int flags) {
        try {
            return (int) hCfHydratePlaceholder.invokeExact(fileHandle, offset, length, flags);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static final int CF_CONVERT_FLAG_NONE         = 0x00000000;
    public static final int CF_CONVERT_FLAG_MARK_IN_SYNC = 0x00000001;

    public static int cfConvertToPlaceholder(MemorySegment fileHandle, MemorySegment fileIdentity,
                                             int fileIdentityLength, int flags) {
        try {
            return (int) hCfConvertToPlaceholder.invokeExact(
                    fileHandle, fileIdentity, fileIdentityLength, flags,
                    MemorySegment.NULL, MemorySegment.NULL);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    /**
     * Open a HANDLE suitable for CfConvertToPlaceholder. Matches Nextcloud's pattern:
     * dwDesiredAccess=0 (query only) and dwShareMode=0 (exclusive). CfConvertToPlaceholder
     * still works because the kernel filter manipulates the placeholder state via the FCB,
     * not the user-mode handle's access rights.
     */
    public static MemorySegment createFileForConvert(MemorySegment pathW) {
        try {
            return (MemorySegment) hCreateFileW.invokeExact(
                    pathW,
                    0,                       // dwDesiredAccess
                    0,                       // dwShareMode (exclusive)
                    MemorySegment.NULL,
                    OPEN_EXISTING,
                    FILE_ATTRIBUTE_NORMAL,
                    MemorySegment.NULL);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static boolean closeHandle(MemorySegment handle) {
        try {
            return (int) hCloseHandle.invokeExact(handle) != 0;
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    /** Returns Win32 file attributes for the given path, or INVALID_FILE_ATTRIBUTES on error. */
    public static int getFileAttributes(java.nio.file.Path path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathW = wideString(path.toString(), arena);
            return (int) hGetFileAttributesW.invokeExact(pathW);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    /** True if the path is a CF placeholder (offline / recall-on-* bits set). Reads attributes
     *  via GetFileAttributesW, which does NOT trigger hydration or directory enumeration. */
    public static boolean isPlaceholder(java.nio.file.Path path) {
        int attrs = getFileAttributes(path);
        if (attrs == INVALID_FILE_ATTRIBUTES) return false;
        return (attrs & CF_PLACEHOLDER_ATTRIBUTES_MASK) != 0;
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
