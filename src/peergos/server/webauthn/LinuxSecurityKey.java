package peergos.server.webauthn;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

/** CTAP2 over /dev/hidraw, which is a usb security key and nothing else.
 *
 *  The key is opened for the length of one ceremony and closed again, so nothing holds
 *  the device between logins.
 */
public class LinuxSecurityKey implements SecurityKey {

    @Override
    public Ctap2.Credential makeCredential(byte[] clientDataJson,
                                           String rpId,
                                           String rpName,
                                           byte[] userId,
                                           String userName,
                                           String displayName,
                                           List<Long> algorithms,
                                           long deadline) throws IOException {
        try (CtapHid key = openKey()) {
            return Ctap2.makeCredential(key, sha256(clientDataJson), rpId, rpName, userId,
                    userName, displayName, algorithms, deadline);
        }
    }

    @Override
    public Ctap2.Credential getAssertion(byte[] clientDataJson,
                                         String rpId,
                                         List<byte[]> allowedCredentials,
                                         long deadline) throws IOException {
        try (CtapHid key = openKey()) {
            return Ctap2.getAssertion(key, rpId, sha256(clientDataJson), allowedCredentials, deadline);
        }
    }

    private static CtapHid openKey() throws IOException {
        Optional<CtapHid> key = CtapHid.openFirst();
        if (! key.isPresent())
            throw new IOException("No security key found. Plug your key in and try again.");
        return key.get();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
