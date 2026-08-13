package peergos.server.webauthn;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

/** A security key driven by webauthn.dll, the api browsers themselves call.
 *
 *  Windows draws the prompt and handles usb, nfc and platform authenticators, and - the
 *  part that matters here - takes the rpId and the clientDataJSON from the caller, so a
 *  credential registered in a browser for peergos.net can be used from a window whose page
 *  came from localhost. That is the same trick the linux path plays over raw CTAP2.
 *
 *  Everything is pinned to structure version 1, which carries every field we need. Later
 *  versions only append, and the dll reads no further than dwVersion says, so there is
 *  nothing to gate on WebAuthNGetApiVersionNumber beyond the dll being usable at all.
 *
 *  This class must not be named from anywhere that android reaches: java.lang.foreign does
 *  not exist there, and this jar is dexed into the android app. SecurityKey loads it
 *  reflectively for that reason.
 */
public class WindowsSecurityKey implements SecurityKey {

    private static final ValueLayout.OfInt DWORD = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfInt LONG32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfInt BOOL = ValueLayout.JAVA_INT;
    private static final MemoryLayout PTR = ValueLayout.ADDRESS;
    private static final MemoryLayout PAD4 = MemoryLayout.paddingLayout(4);

    // Every struct below is transcribed from webauthn.h. The explicit padding is not
    // decoration: a DWORD followed by a pointer leaves a 4 byte hole on x64, and getting
    // that wrong does not fail loudly, it passes garbage to the dll.
    private static final MemoryLayout RP_ENTITY = MemoryLayout.structLayout(
            DWORD.withName("dwVersion"),
            PAD4,
            PTR.withName("pwszId"),
            PTR.withName("pwszName"),
            PTR.withName("pwszIcon"));

    private static final MemoryLayout USER_ENTITY = MemoryLayout.structLayout(
            DWORD.withName("dwVersion"),
            DWORD.withName("cbId"),
            PTR.withName("pbId"),
            PTR.withName("pwszName"),
            PTR.withName("pwszIcon"),
            PTR.withName("pwszDisplayName"));

    private static final MemoryLayout CLIENT_DATA = MemoryLayout.structLayout(
            DWORD.withName("dwVersion"),
            DWORD.withName("cbClientDataJSON"),
            PTR.withName("pbClientDataJSON"),
            PTR.withName("pwszHashAlgId"));

    private static final MemoryLayout COSE_PARAMETER = MemoryLayout.structLayout(
            DWORD.withName("dwVersion"),
            PAD4,
            PTR.withName("pwszCredentialType"),
            LONG32.withName("lAlg"),
            PAD4);

    private static final MemoryLayout COSE_PARAMETERS = MemoryLayout.structLayout(
            DWORD.withName("cCredentialParameters"),
            PAD4,
            PTR.withName("pCredentialParameters"));

    private static final MemoryLayout CREDENTIAL = MemoryLayout.structLayout(
            DWORD.withName("dwVersion"),
            DWORD.withName("cbId"),
            PTR.withName("pbId"),
            PTR.withName("pwszCredentialType"));

    private static final MemoryLayout CREDENTIALS = MemoryLayout.structLayout(
            DWORD.withName("cCredentials"),
            PAD4,
            PTR.withName("pCredentials"));

    private static final MemoryLayout EXTENSIONS = MemoryLayout.structLayout(
            DWORD.withName("cExtensions"),
            PAD4,
            PTR.withName("pExtensions"));

    private static final MemoryLayout MAKE_CREDENTIAL_OPTIONS = MemoryLayout.structLayout(
            DWORD.withName("dwVersion"),
            DWORD.withName("dwTimeoutMilliseconds"),
            CREDENTIALS.withName("CredentialList"),
            EXTENSIONS.withName("Extensions"),
            DWORD.withName("dwAuthenticatorAttachment"),
            BOOL.withName("bRequireResidentKey"),
            DWORD.withName("dwUserVerificationRequirement"),
            DWORD.withName("dwAttestationConveyancePreference"),
            DWORD.withName("dwFlags"),
            PAD4);

    private static final MemoryLayout GET_ASSERTION_OPTIONS = MemoryLayout.structLayout(
            DWORD.withName("dwVersion"),
            DWORD.withName("dwTimeoutMilliseconds"),
            CREDENTIALS.withName("CredentialList"),
            EXTENSIONS.withName("Extensions"),
            DWORD.withName("dwAuthenticatorAttachment"),
            DWORD.withName("dwUserVerificationRequirement"),
            DWORD.withName("dwFlags"),
            PAD4);

    // The two the dll allocates and we only read. Reinterpreting to the version 1 size is
    // safe whatever version it actually returned, because we never read past these fields.
    private static final MemoryLayout CREDENTIAL_ATTESTATION = MemoryLayout.structLayout(
            DWORD.withName("dwVersion"),
            PAD4,
            PTR.withName("pwszFormatType"),
            DWORD.withName("cbAuthenticatorData"),
            PAD4,
            PTR.withName("pbAuthenticatorData"),
            DWORD.withName("cbAttestation"),
            PAD4,
            PTR.withName("pbAttestation"),
            DWORD.withName("dwAttestationDecodeType"),
            PAD4,
            PTR.withName("pvAttestationDecode"),
            DWORD.withName("cbAttestationObject"),
            PAD4,
            PTR.withName("pbAttestationObject"),
            DWORD.withName("cbCredentialId"),
            PAD4,
            PTR.withName("pbCredentialId"));

    private static final MemoryLayout ASSERTION = MemoryLayout.structLayout(
            DWORD.withName("dwVersion"),
            DWORD.withName("cbAuthenticatorData"),
            PTR.withName("pbAuthenticatorData"),
            DWORD.withName("cbSignature"),
            PAD4,
            PTR.withName("pbSignature"),
            CREDENTIAL.withName("Credential"),
            DWORD.withName("cbUserId"),
            PAD4,
            PTR.withName("pbUserId"));

    private static final int VERSION_1 = 1;
    private static final int S_OK = 0;
    private static final int ATTACHMENT_ANY = 0;
    private static final int USER_VERIFICATION_ANY = 0;
    private static final int ATTESTATION_NONE = 1;
    private static final String PUBLIC_KEY = "public-key";
    private static final String SHA_256 = "SHA-256";

    /** Linked on first use rather than at construction, so a machine without a usable
     *  webauthn.dll fails inside the ceremony, where the message reaches the page, rather
     *  than while the server is starting up. */
    private static final class Native {
        static final SymbolLookup WEBAUTHN = SymbolLookup.libraryLookup("webauthn.dll", Arena.global());
        static final MethodHandle MAKE_CREDENTIAL = downcall("WebAuthNAuthenticatorMakeCredential",
                FunctionDescriptor.of(LONG32, PTR, PTR, PTR, PTR, PTR, PTR, PTR));
        static final MethodHandle GET_ASSERTION = downcall("WebAuthNAuthenticatorGetAssertion",
                FunctionDescriptor.of(LONG32, PTR, PTR, PTR, PTR, PTR));
        static final MethodHandle FREE_ATTESTATION = downcall("WebAuthNFreeCredentialAttestation",
                FunctionDescriptor.ofVoid(PTR));
        static final MethodHandle FREE_ASSERTION = downcall("WebAuthNFreeAssertion",
                FunctionDescriptor.ofVoid(PTR));
        static final MethodHandle ERROR_NAME = downcall("WebAuthNGetErrorName",
                FunctionDescriptor.of(PTR, LONG32));
        static final int API_VERSION = apiVersion();

        private static MethodHandle downcall(String name, FunctionDescriptor descriptor) {
            return Linker.nativeLinker().downcallHandle(WEBAUTHN.findOrThrow(name), descriptor);
        }

        private static int apiVersion() {
            try {
                return (int) downcall("WebAuthNGetApiVersionNumber", FunctionDescriptor.of(DWORD))
                        .invokeExact();
            } catch (Throwable t) {
                throw new IllegalStateException("webauthn.dll is present but unusable", t);
            }
        }
    }

    /** Set by the desktop host, which is a different process and owns the only window we
     *  have. Volatile because the host posts it on its own thread, and 0 - a null HWND -
     *  until it does, which windows accepts but may then draw the prompt behind the app. */
    private volatile long windowHandle = 0;

    @Override
    public void setWindowHandle(long handle) {
        this.windowHandle = handle;
    }

    @Override
    public Ctap2.Credential makeCredential(byte[] clientDataJson,
                                           String rpId,
                                           String rpName,
                                           byte[] userId,
                                           String userName,
                                           String displayName,
                                           List<Long> algorithms,
                                           long deadline) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rp = arena.allocate(RP_ENTITY);
            setInt(rp, RP_ENTITY, "dwVersion", VERSION_1);
            setPointer(rp, RP_ENTITY, "pwszId", wide(arena, rpId));
            setPointer(rp, RP_ENTITY, "pwszName", wide(arena, rpName));

            MemorySegment user = arena.allocate(USER_ENTITY);
            MemorySegment id = arena.allocateFrom(ValueLayout.JAVA_BYTE, userId);
            setInt(user, USER_ENTITY, "dwVersion", VERSION_1);
            setInt(user, USER_ENTITY, "cbId", userId.length);
            setPointer(user, USER_ENTITY, "pbId", id);
            setPointer(user, USER_ENTITY, "pwszName", wide(arena, userName));
            setPointer(user, USER_ENTITY, "pwszDisplayName", wide(arena, displayName));

            MemorySegment publicKey = wide(arena, PUBLIC_KEY);
            MemorySegment parameters = arena.allocate(COSE_PARAMETER, algorithms.size());
            for (int i = 0; i < algorithms.size(); i++) {
                MemorySegment parameter = parameters.asSlice(i * COSE_PARAMETER.byteSize(), COSE_PARAMETER.byteSize());
                setInt(parameter, COSE_PARAMETER, "dwVersion", VERSION_1);
                setPointer(parameter, COSE_PARAMETER, "pwszCredentialType", publicKey);
                setInt(parameter, COSE_PARAMETER, "lAlg", algorithms.get(i).intValue());
            }
            MemorySegment credentialParameters = arena.allocate(COSE_PARAMETERS);
            setInt(credentialParameters, COSE_PARAMETERS, "cCredentialParameters", algorithms.size());
            setPointer(credentialParameters, COSE_PARAMETERS, "pCredentialParameters", parameters);

            MemorySegment options = arena.allocate(MAKE_CREDENTIAL_OPTIONS);
            setInt(options, MAKE_CREDENTIAL_OPTIONS, "dwVersion", VERSION_1);
            setInt(options, MAKE_CREDENTIAL_OPTIONS, "dwTimeoutMilliseconds", timeout(deadline));
            // ANY leaves the choice to the windows chooser, which offers hello alongside a
            // security key. Narrow this to CROSS_PLATFORM if we decide a peergos second
            // factor should never be a passkey bound to one machine.
            setInt(options, MAKE_CREDENTIAL_OPTIONS, "dwAuthenticatorAttachment", ATTACHMENT_ANY);
            setInt(options, MAKE_CREDENTIAL_OPTIONS, "dwUserVerificationRequirement", USER_VERIFICATION_ANY);
            // We rebuild a none attestation from the authenticator data below in any case,
            // so this only saves the user a "share the make and model?" prompt.
            setInt(options, MAKE_CREDENTIAL_OPTIONS, "dwAttestationConveyancePreference", ATTESTATION_NONE);

            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            int result = call(() -> (int) Native.MAKE_CREDENTIAL.invokeExact(window(), rp, user,
                    credentialParameters, clientData(arena, clientDataJson), options, out));
            if (result != S_OK)
                throw new IOException(errorMessage("Registration", result));

            MemorySegment attestation = owned(out, CREDENTIAL_ATTESTATION, arena, Native.FREE_ATTESTATION);
            byte[] authenticatorData = bytes(attestation, CREDENTIAL_ATTESTATION, "cbAuthenticatorData", "pbAuthenticatorData");
            byte[] credentialId = bytes(attestation, CREDENTIAL_ATTESTATION, "cbCredentialId", "pbCredentialId");
            if (authenticatorData == null)
                throw new IOException("No authenticator data in the registration response");
            // The server takes a self attestation now, so pbAttestationObject would do; this
            // keeps one shape across all three platforms and stores the least.
            return new Ctap2.Credential(credentialId != null ? credentialId : Ctap2.credentialIdOf(authenticatorData),
                    authenticatorData, null, null, Ctap2.noneAttestation(authenticatorData));
        }
    }

    @Override
    public Ctap2.Credential getAssertion(byte[] clientDataJson,
                                         String rpId,
                                         List<byte[]> allowedCredentials,
                                         long deadline) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment options = arena.allocate(GET_ASSERTION_OPTIONS);
            setInt(options, GET_ASSERTION_OPTIONS, "dwVersion", VERSION_1);
            setInt(options, GET_ASSERTION_OPTIONS, "dwTimeoutMilliseconds", timeout(deadline));
            setInt(options, GET_ASSERTION_OPTIONS, "dwAuthenticatorAttachment", ATTACHMENT_ANY);
            setInt(options, GET_ASSERTION_OPTIONS, "dwUserVerificationRequirement", USER_VERIFICATION_ANY);
            if (! allowedCredentials.isEmpty()) {
                MemorySegment publicKey = wide(arena, PUBLIC_KEY);
                MemorySegment credentials = arena.allocate(CREDENTIAL, allowedCredentials.size());
                for (int i = 0; i < allowedCredentials.size(); i++) {
                    byte[] allowed = allowedCredentials.get(i);
                    MemorySegment credential = credentials.asSlice(i * CREDENTIAL.byteSize(), CREDENTIAL.byteSize());
                    setInt(credential, CREDENTIAL, "dwVersion", VERSION_1);
                    setInt(credential, CREDENTIAL, "cbId", allowed.length);
                    setPointer(credential, CREDENTIAL, "pbId", arena.allocateFrom(ValueLayout.JAVA_BYTE, allowed));
                    setPointer(credential, CREDENTIAL, "pwszCredentialType", publicKey);
                }
                setInt(options, GET_ASSERTION_OPTIONS, "CredentialList.cCredentials", allowedCredentials.size());
                setPointer(options, GET_ASSERTION_OPTIONS, "CredentialList.pCredentials", credentials);
            }

            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            int result = call(() -> (int) Native.GET_ASSERTION.invokeExact(window(), wide(arena, rpId),
                    clientData(arena, clientDataJson), options, out));
            if (result != S_OK)
                throw new IOException(errorMessage("Login", result));

            MemorySegment assertion = owned(out, ASSERTION, arena, Native.FREE_ASSERTION);
            byte[] authenticatorData = bytes(assertion, ASSERTION, "cbAuthenticatorData", "pbAuthenticatorData");
            byte[] signature = bytes(assertion, ASSERTION, "cbSignature", "pbSignature");
            if (authenticatorData == null || signature == null)
                throw new IOException("Incomplete assertion from the security key");
            byte[] credentialId = bytes(assertion, ASSERTION, "Credential.cbId", "Credential.pbId");
            if (credentialId == null && allowedCredentials.size() == 1)
                credentialId = allowedCredentials.get(0); // as CTAP2 does: it may omit the one we named
            byte[] userHandle = bytes(assertion, ASSERTION, "cbUserId", "pbUserId");
            return new Ctap2.Credential(credentialId, authenticatorData, signature, userHandle, null);
        }
    }

    private MemorySegment window() {
        return MemorySegment.ofAddress(windowHandle);
    }

    private static MemorySegment clientData(Arena arena, byte[] clientDataJson) {
        MemorySegment json = arena.allocateFrom(ValueLayout.JAVA_BYTE, clientDataJson);
        MemorySegment clientData = arena.allocate(CLIENT_DATA);
        setInt(clientData, CLIENT_DATA, "dwVersion", VERSION_1);
        setInt(clientData, CLIENT_DATA, "cbClientDataJSON", clientDataJson.length);
        setPointer(clientData, CLIENT_DATA, "pbClientDataJSON", json);
        // Windows hashes the json itself, which is the whole reason it will sign for an
        // origin the window does not have.
        setPointer(clientData, CLIENT_DATA, "pwszHashAlgId", wide(arena, SHA_256));
        return clientData;
    }

    private static int timeout(long deadline) {
        return (int) Math.max(1_000, Math.min(Integer.MAX_VALUE, deadline - System.currentTimeMillis()));
    }

    /** The dll owns what it returned; freeing is tied to the arena so the error paths
     *  cannot leak it. */
    private static MemorySegment owned(MemorySegment out, MemoryLayout layout, Arena arena, MethodHandle free) throws IOException {
        MemorySegment returned = out.get(ValueLayout.ADDRESS, 0);
        if (returned.equals(MemorySegment.NULL))
            throw new IOException("The security key returned nothing");
        Consumer<MemorySegment> release = segment -> {
            try {
                free.invokeExact(segment);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        };
        return returned.reinterpret(layout.byteSize(), arena, release);
    }

    private interface NativeCall {
        int invoke() throws Throwable;
    }

    private static int call(NativeCall call) throws IOException {
        try {
            return call.invoke();
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Could not reach webauthn.dll: " + t, t);
        }
    }

    private static String errorMessage(String what, int hresult) {
        String name;
        try {
            MemorySegment message = (MemorySegment) Native.ERROR_NAME.invokeExact(hresult);
            name = message.equals(MemorySegment.NULL) ? "" :
                    message.reinterpret(1024).getString(0, StandardCharsets.UTF_16LE);
        } catch (Throwable t) {
            name = "";
        }
        // NotAllowedError is what a cancel, a timeout and a wrong key all come back as, and
        // is the one the page already knows how to phrase.
        return what + " failed: " + (name.isEmpty() ? "0x" + Integer.toHexString(hresult) : name)
                + " (api version " + Native.API_VERSION + ")";
    }

    private static MemorySegment wide(Arena arena, String text) {
        return arena.allocateFrom(text == null ? "" : text, StandardCharsets.UTF_16LE);
    }

    /** Field paths, so this reads like webauthn.h rather than like a list of magic numbers.
     *  "Credential.pbId" walks into a nested struct. */
    private static long offset(MemoryLayout layout, String path) {
        String[] names = path.split("\\.");
        MemoryLayout.PathElement[] elements = new MemoryLayout.PathElement[names.length];
        for (int i = 0; i < names.length; i++)
            elements[i] = MemoryLayout.PathElement.groupElement(names[i]);
        return layout.byteOffset(elements);
    }

    private static void setInt(MemorySegment struct, MemoryLayout layout, String path, int value) {
        struct.set(ValueLayout.JAVA_INT, offset(layout, path), value);
    }

    private static void setPointer(MemorySegment struct, MemoryLayout layout, String path, MemorySegment value) {
        struct.set(ValueLayout.ADDRESS, offset(layout, path), value);
    }

    private static byte[] bytes(MemorySegment struct, MemoryLayout layout, String countPath, String pointerPath) {
        int length = struct.get(ValueLayout.JAVA_INT, offset(layout, countPath));
        MemorySegment pointer = struct.get(ValueLayout.ADDRESS, offset(layout, pointerPath));
        if (length <= 0 || pointer.equals(MemorySegment.NULL))
            return null;
        return pointer.reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
    }
}
