package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;

import java.lang.foreign.MemoryLayout;
import java.lang.reflect.Field;

/**
 *  The one part of the windows security key support that can be checked without windows.
 *
 *  A struct laid out wrongly does not fail loudly, it passes garbage to webauthn.dll: a
 *  DWORD followed by a pointer leaves a 4 byte hole on x64, and missing one shifts every
 *  field after it. So the offsets are pinned here against webauthn.h, where they are read
 *  off the declared layouts exactly as the calls read them.
 *
 *  Loading the class is itself part of the test: everything that touches the dll lives in
 *  a holder class, so this must initialise on linux without one.
 */
public class WindowsWebauthnLayout {

    private static final Class<?> WINDOWS_KEY = load();

    private static Class<?> load() {
        try {
            return Class.forName("peergos.server.webauthn.WindowsSecurityKey");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void inputStructs() {
        // WEBAUTHN_RP_ENTITY_INFORMATION
        check("RP_ENTITY", 32, "dwVersion", 0, "pwszId", 8, "pwszName", 16, "pwszIcon", 24);
        // WEBAUTHN_USER_ENTITY_INFORMATION
        check("USER_ENTITY", 40, "dwVersion", 0, "cbId", 4, "pbId", 8, "pwszName", 16,
                "pwszIcon", 24, "pwszDisplayName", 32);
        // WEBAUTHN_CLIENT_DATA
        check("CLIENT_DATA", 24, "dwVersion", 0, "cbClientDataJSON", 4, "pbClientDataJSON", 8,
                "pwszHashAlgId", 16);
        // WEBAUTHN_COSE_CREDENTIAL_PARAMETER{,S}
        check("COSE_PARAMETER", 24, "dwVersion", 0, "pwszCredentialType", 8, "lAlg", 16);
        check("COSE_PARAMETERS", 16, "cCredentialParameters", 0, "pCredentialParameters", 8);
        // WEBAUTHN_CREDENTIAL{,S}
        check("CREDENTIAL", 24, "dwVersion", 0, "cbId", 4, "pbId", 8, "pwszCredentialType", 16);
        check("CREDENTIALS", 16, "cCredentials", 0, "pCredentials", 8);
        check("EXTENSIONS", 16, "cExtensions", 0, "pExtensions", 8);
    }

    @Test
    public void optionStructs() {
        // WEBAUTHN_AUTHENTICATOR_MAKE_CREDENTIAL_OPTIONS, version 1
        check("MAKE_CREDENTIAL_OPTIONS", 64, "dwVersion", 0, "dwTimeoutMilliseconds", 4,
                "CredentialList", 8, "Extensions", 24, "dwAuthenticatorAttachment", 40,
                "bRequireResidentKey", 44, "dwUserVerificationRequirement", 48,
                "dwAttestationConveyancePreference", 52, "dwFlags", 56);
        // WEBAUTHN_AUTHENTICATOR_GET_ASSERTION_OPTIONS, version 1
        check("GET_ASSERTION_OPTIONS", 56, "dwVersion", 0, "dwTimeoutMilliseconds", 4,
                "CredentialList", 8, "Extensions", 24, "dwAuthenticatorAttachment", 40,
                "dwUserVerificationRequirement", 44, "dwFlags", 48);
        // the allow list is written through the version 1 CredentialList
        check("GET_ASSERTION_OPTIONS", 56, "CredentialList.cCredentials", 8,
                "CredentialList.pCredentials", 16);
    }

    /** These two the dll allocates and we only read, so the offsets have to be right in a
     *  struct we never see the size of. Reading version 1 fields is safe at any version,
     *  because later versions only append. */
    @Test
    public void returnedStructs() {
        // WEBAUTHN_CREDENTIAL_ATTESTATION
        check("CREDENTIAL_ATTESTATION", 96, "dwVersion", 0, "pwszFormatType", 8,
                "cbAuthenticatorData", 16, "pbAuthenticatorData", 24, "cbAttestation", 32,
                "pbAttestation", 40, "dwAttestationDecodeType", 48, "pvAttestationDecode", 56,
                "cbAttestationObject", 64, "pbAttestationObject", 72, "cbCredentialId", 80,
                "pbCredentialId", 88);
        // WEBAUTHN_ASSERTION, including the credential the key actually asserted with
        check("ASSERTION", 72, "dwVersion", 0, "cbAuthenticatorData", 4, "pbAuthenticatorData", 8,
                "cbSignature", 16, "pbSignature", 24, "Credential", 32, "Credential.cbId", 36,
                "Credential.pbId", 40, "cbUserId", 56, "pbUserId", 64);
    }

    private static void check(String struct, long size, Object... fieldsAndOffsets) {
        MemoryLayout layout = layout(struct);
        Assert.assertEquals(struct + " size", size, layout.byteSize());
        for (int i = 0; i < fieldsAndOffsets.length; i += 2) {
            String field = (String) fieldsAndOffsets[i];
            long expected = ((Number) fieldsAndOffsets[i + 1]).longValue();
            Assert.assertEquals(struct + "." + field, expected, offset(layout, field));
        }
    }

    private static long offset(MemoryLayout layout, String path) {
        String[] names = path.split("\\.");
        MemoryLayout.PathElement[] elements = new MemoryLayout.PathElement[names.length];
        for (int i = 0; i < names.length; i++)
            elements[i] = MemoryLayout.PathElement.groupElement(names[i]);
        return layout.byteOffset(elements);
    }

    private static MemoryLayout layout(String name) {
        try {
            Field field = WINDOWS_KEY.getDeclaredField(name);
            field.setAccessible(true);
            return (MemoryLayout) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("No layout " + name + " in WindowsSecurityKey", e);
        }
    }
}
