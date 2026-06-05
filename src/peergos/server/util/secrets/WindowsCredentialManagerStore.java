package peergos.server.util.secrets;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Windows {@link SecretStore} backed by Credential Manager via the
 * Foreign Function &amp; Memory API.
 *
 * <p>Binds three functions from {@code advapi32.dll} (plus {@code CredFree}):
 * {@code CredReadW}, {@code CredWriteW}, {@code CredDeleteW}. The
 * {@code CREDENTIALW} struct is built and read by hand; offsets are for the
 * Windows x64 ABI and annotated with the matching field from {@code wincred.h}.
 *
 * <p>The {@code (service, account)} tuple is encoded as the credential's
 * {@code TargetName} ({@code "service:account"}) so each lookup is a single
 * {@code CredReadW} call. The secret value is stored UTF-8 in
 * {@code CredentialBlob} for symmetry with the other {@link SecretStore}
 * implementations.
 *
 * <p>Same lazy-init pattern as {@link peergos.server.cfapi.CfApi}: {@link #load()}
 * is only called from inside the instance methods so that constructing this
 * class on a non-Windows host (where {@code SecretStore.detect()} should never
 * pick it) does not try to open advapi32.dll.
 */
public final class WindowsCredentialManagerStore implements SecretStore {

    // CRED_TYPE values (wincred.h)
    private static final int CRED_TYPE_GENERIC = 1;
    // CRED_PERSIST values
    private static final int CRED_PERSIST_LOCAL_MACHINE = 2;
    // ERROR_NOT_FOUND — distinguishes "no such credential" from a real failure.
    private static final int ERROR_NOT_FOUND = 1168;

    // CREDENTIALW field offsets (x64). Total size = 80 bytes.
    //   0  DWORD       Flags
    //   4  DWORD       Type
    //   8  LPWSTR      TargetName
    //  16  LPWSTR      Comment
    //  24  FILETIME    LastWritten (2x DWORD = 8 bytes)
    //  32  DWORD       CredentialBlobSize
    //  36  (4 bytes padding to 8-byte align the next pointer)
    //  40  LPBYTE      CredentialBlob
    //  48  DWORD       Persist
    //  52  DWORD       AttributeCount
    //  56  PCREDATTR   Attributes
    //  64  LPWSTR      TargetAlias
    //  72  LPWSTR      UserName
    private static final long CRED_SIZE                  = 80;
    private static final long OFF_FLAGS                  = 0;
    private static final long OFF_TYPE                   = 4;
    private static final long OFF_TARGET_NAME            = 8;
    private static final long OFF_COMMENT                = 16;
    private static final long OFF_CREDENTIAL_BLOB_SIZE   = 32;
    private static final long OFF_CREDENTIAL_BLOB        = 40;
    private static final long OFF_PERSIST                = 48;
    private static final long OFF_ATTRIBUTE_COUNT        = 52;
    private static final long OFF_ATTRIBUTES             = 56;
    private static final long OFF_TARGET_ALIAS           = 64;
    private static final long OFF_USER_NAME              = 72;

    private static volatile boolean loaded = false;
    private static MethodHandle hCredReadW;
    private static MethodHandle hCredWriteW;
    private static MethodHandle hCredDeleteW;
    private static MethodHandle hCredFree;
    private static MethodHandle hGetLastError;

    private static synchronized void load() {
        if (loaded) return;
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null) systemRoot = "C:\\Windows";
        SymbolLookup advapi32 = SymbolLookup.libraryLookup(
                java.nio.file.Path.of(systemRoot, "System32", "advapi32.dll"), Arena.global());
        SymbolLookup kernel32 = SymbolLookup.libraryLookup(
                java.nio.file.Path.of(systemRoot, "System32", "kernel32.dll"), Arena.global());
        Linker linker = Linker.nativeLinker();

        // BOOL CredReadW(LPCWSTR TargetName, DWORD Type, DWORD Flags, PCREDENTIALW *Credential)
        hCredReadW = linker.downcallHandle(
                advapi32.find("CredReadW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        // BOOL CredWriteW(PCREDENTIALW Credential, DWORD Flags)
        hCredWriteW = linker.downcallHandle(
                advapi32.find("CredWriteW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

        // BOOL CredDeleteW(LPCWSTR TargetName, DWORD Type, DWORD Flags)
        hCredDeleteW = linker.downcallHandle(
                advapi32.find("CredDeleteW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        // void CredFree(PVOID Buffer)
        hCredFree = linker.downcallHandle(
                advapi32.find("CredFree").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        // DWORD GetLastError(void)
        hGetLastError = linker.downcallHandle(
                kernel32.find("GetLastError").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));

        loaded = true;
    }

    private static String targetName(String service, String account) {
        return service + ":" + account;
    }

    /** Allocate a null-terminated UTF-16LE string (LPCWSTR) in {@code arena}. */
    private static MemorySegment wide(Arena arena, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment seg = arena.allocate(bytes.length + 2L);
        MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0, bytes.length);
        seg.set(ValueLayout.JAVA_SHORT, bytes.length, (short) 0);
        return seg;
    }

    @Override
    public void put(String service, String account, String value) throws IOException {
        load();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment target = wide(arena, targetName(service, account));
            MemorySegment user   = wide(arena, account);
            byte[] blobBytes = value.getBytes(StandardCharsets.UTF_8);
            MemorySegment blob = arena.allocate(blobBytes.length == 0 ? 1 : blobBytes.length);
            MemorySegment.copy(blobBytes, 0, blob, ValueLayout.JAVA_BYTE, 0, blobBytes.length);

            MemorySegment cred = arena.allocate(CRED_SIZE);
            // Zero the struct — most fields we don't care about must be 0 / null.
            cred.fill((byte) 0);
            cred.set(ValueLayout.JAVA_INT,     OFF_FLAGS,                0);
            cred.set(ValueLayout.JAVA_INT,     OFF_TYPE,                 CRED_TYPE_GENERIC);
            cred.set(ValueLayout.ADDRESS,      OFF_TARGET_NAME,          target);
            cred.set(ValueLayout.JAVA_INT,     OFF_CREDENTIAL_BLOB_SIZE, blobBytes.length);
            cred.set(ValueLayout.ADDRESS,      OFF_CREDENTIAL_BLOB,      blob);
            cred.set(ValueLayout.JAVA_INT,     OFF_PERSIST,              CRED_PERSIST_LOCAL_MACHINE);
            cred.set(ValueLayout.ADDRESS,      OFF_USER_NAME,            user);

            int ok;
            try {
                ok = (int) hCredWriteW.invokeExact(cred, 0);
            } catch (Throwable t) {
                throw new IOException("CredWriteW threw", t);
            }
            if (ok == 0) {
                int err = lastError();
                throw new IOException("CredWriteW failed for '" + targetName(service, account)
                        + "' (GetLastError=" + err + ")");
            }
        }
    }

    @Override
    public Optional<String> get(String service, String account) throws IOException {
        load();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment target = wide(arena, targetName(service, account));
            // out param: PCREDENTIALW* — we give CredReadW the address of a pointer slot.
            MemorySegment outPtr = arena.allocate(ValueLayout.ADDRESS);

            int ok;
            try {
                ok = (int) hCredReadW.invokeExact(target, CRED_TYPE_GENERIC, 0, outPtr);
            } catch (Throwable t) {
                throw new IOException("CredReadW threw", t);
            }
            if (ok == 0) {
                int err = lastError();
                if (err == ERROR_NOT_FOUND) return Optional.empty();
                throw new IOException("CredReadW failed for '" + targetName(service, account)
                        + "' (GetLastError=" + err + ")");
            }

            MemorySegment credPtr = outPtr.get(ValueLayout.ADDRESS, 0);
            try {
                MemorySegment cred = credPtr.reinterpret(CRED_SIZE);
                int size = cred.get(ValueLayout.JAVA_INT, OFF_CREDENTIAL_BLOB_SIZE);
                MemorySegment blobPtr = cred.get(ValueLayout.ADDRESS, OFF_CREDENTIAL_BLOB);
                if (size <= 0 || blobPtr.address() == 0) return Optional.of("");
                byte[] bytes = blobPtr.reinterpret(size)
                        .toArray(ValueLayout.JAVA_BYTE);
                return Optional.of(new String(bytes, StandardCharsets.UTF_8));
            } finally {
                try { hCredFree.invokeExact(credPtr); }
                catch (Throwable ignored) { /* Best-effort free; loss here just leaks a Win32 alloc. */ }
            }
        }
    }

    @Override
    public void delete(String service, String account) throws IOException {
        load();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment target = wide(arena, targetName(service, account));
            int ok;
            try {
                ok = (int) hCredDeleteW.invokeExact(target, CRED_TYPE_GENERIC, 0);
            } catch (Throwable t) {
                throw new IOException("CredDeleteW threw", t);
            }
            if (ok == 0) {
                int err = lastError();
                if (err == ERROR_NOT_FOUND) return; // idempotent
                throw new IOException("CredDeleteW failed for '" + targetName(service, account)
                        + "' (GetLastError=" + err + ")");
            }
        }
    }

    @Override
    public boolean isAvailable() {
        // Credential Manager is part of every supported Windows install; presence
        // of the DLL is enough — services are always running for an interactive user.
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows"))
            return false;
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null) systemRoot = "C:\\Windows";
        return new java.io.File(systemRoot + "\\System32\\advapi32.dll").exists();
    }

    private static int lastError() {
        try {
            return (int) hGetLastError.invokeExact();
        } catch (Throwable t) {
            return -1;
        }
    }
}
