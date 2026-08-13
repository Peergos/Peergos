package peergos.server.webauthn;

import peergos.shared.cbor.CborConstants;
import peergos.shared.cbor.CborDecoder;
import peergos.shared.cbor.CborEncoder;
import peergos.shared.cbor.CborType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The two CTAP2 commands a login needs, and their results in the shape the
 *  WebAuthn api hands to a page.
 *
 *  CTAP2 maps use integer keys and must be in canonical order, so these are
 *  written with the raw encoder rather than CborObject, which is string keyed.
 */
public class Ctap2 {
    private static final byte MAKE_CREDENTIAL = 0x01;
    private static final byte GET_ASSERTION = 0x02;
    private static final int MAX_ITEM = 64 * 1024;

    /** The parts of an authenticator's answer that a page expects back. */
    public static class Credential {
        public final byte[] credentialId;
        public final byte[] authenticatorData;
        public final byte[] signature;      // assertions only
        public final byte[] userHandle;     // assertions only, may be null
        public final byte[] attestationObject; // registrations only

        Credential(byte[] credentialId, byte[] authenticatorData, byte[] signature,
                   byte[] userHandle, byte[] attestationObject) {
            this.credentialId = credentialId;
            this.authenticatorData = authenticatorData;
            this.signature = signature;
            this.userHandle = userHandle;
            this.attestationObject = attestationObject;
        }
    }

    public static Credential makeCredential(CtapHid key,
                                            byte[] clientDataHash,
                                            String rpId,
                                            String rpName,
                                            byte[] userId,
                                            String userName,
                                            String displayName,
                                            List<Long> algorithms,
                                            long deadline) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        CborEncoder request = new CborEncoder(bout);
        request.writeMapStart(4);
        request.writeInt(1);
        request.writeByteString(clientDataHash);
        request.writeInt(2);
        request.writeMapStart(2);
        request.writeTextString("id");
        request.writeTextString(rpId);
        request.writeTextString("name");
        request.writeTextString(rpName);
        request.writeInt(3);
        request.writeMapStart(3);
        request.writeTextString("id");
        request.writeByteString(userId);
        request.writeTextString("name");
        request.writeTextString(userName);
        request.writeTextString("displayName");
        request.writeTextString(displayName);
        request.writeInt(4);
        request.writeArrayStart(algorithms.size());
        for (long algorithm : algorithms) {
            request.writeMapStart(2);
            request.writeTextString("alg");
            request.writeInt(algorithm);
            request.writeTextString("type");
            request.writeTextString("public-key");
        }

        Map<Object, Object> response = asMap(decode(key.cbor(MAKE_CREDENTIAL, bout.toByteArray(), deadline)));
        byte[] authenticatorData = (byte[]) response.get(2L);
        if (authenticatorData == null)
            throw new IOException("No authenticator data in the registration response");
        return new Credential(credentialIdOf(authenticatorData), authenticatorData, null, null,
                noneAttestation(authenticatorData));
    }

    public static Credential getAssertion(CtapHid key,
                                          String rpId,
                                          byte[] clientDataHash,
                                          List<byte[]> allowedCredentials,
                                          long deadline) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        CborEncoder request = new CborEncoder(bout);
        request.writeMapStart(allowedCredentials.isEmpty() ? 2 : 3);
        request.writeInt(1);
        request.writeTextString(rpId);
        request.writeInt(2);
        request.writeByteString(clientDataHash);
        if (! allowedCredentials.isEmpty()) {
            request.writeInt(3);
            request.writeArrayStart(allowedCredentials.size());
            for (byte[] credential : allowedCredentials) {
                request.writeMapStart(2);
                request.writeTextString("id");
                request.writeByteString(credential);
                request.writeTextString("type");
                request.writeTextString("public-key");
            }
        }

        Map<Object, Object> response = asMap(decode(key.cbor(GET_ASSERTION, bout.toByteArray(), deadline)));
        byte[] authenticatorData = (byte[]) response.get(2L);
        byte[] signature = (byte[]) response.get(3L);
        if (authenticatorData == null || signature == null)
            throw new IOException("Incomplete assertion from the security key");
        byte[] credentialId = null;
        Object descriptor = response.get(1L);
        if (descriptor instanceof Map)
            credentialId = (byte[]) ((Map) descriptor).get("id");
        if (credentialId == null && allowedCredentials.size() == 1)
            credentialId = allowedCredentials.get(0); // the key may omit it when we named only one
        byte[] userHandle = null;
        Object user = response.get(4L);
        if (user instanceof Map)
            userHandle = (byte[]) ((Map) user).get("id");
        return new Credential(credentialId, authenticatorData, signature, userHandle, null);
    }

    /** Keep authData - which carries the public key - and drop whatever the key said
     *  about itself.
     *
     *  The server no longer requires this: since Nitrokey support it accepts a self
     *  attestation too, and never keeps the statement either way. We still send none
     *  because it is the same shape from every platform, and the smallest thing that
     *  registers. */
    static byte[] noneAttestation(byte[] authenticatorData) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        CborEncoder out = new CborEncoder(bout);
        out.writeMapStart(3);
        out.writeTextString("fmt");
        out.writeTextString("none");
        out.writeTextString("attStmt");
        out.writeMapStart(0);
        out.writeTextString("authData");
        out.writeByteString(authenticatorData);
        return bout.toByteArray();
    }

    /** authData is rpIdHash(32) flags(1) signCount(4) aaguid(16) idLength(2) credentialId. */
    static byte[] credentialIdOf(byte[] authenticatorData) throws IOException {
        int lengthAt = 32 + 1 + 4 + 16;
        if (authenticatorData.length < lengthAt + 2)
            throw new IOException("Registration response has no credential in it");
        int length = ((authenticatorData[lengthAt] & 0xff) << 8) | (authenticatorData[lengthAt + 1] & 0xff);
        int from = lengthAt + 2;
        if (authenticatorData.length < from + length)
            throw new IOException("Truncated credential id");
        byte[] credentialId = new byte[length];
        System.arraycopy(authenticatorData, from, credentialId, 0, length);
        return credentialId;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> asMap(Object value) throws IOException {
        if (! (value instanceof Map))
            throw new IOException("Expected a map from the security key");
        return (Map<Object, Object>) value;
    }

    static Object decode(byte[] cbor) throws IOException {
        return readValue(new CborDecoder(new ByteArrayInputStream(cbor)));
    }

    /** Enough of CBOR to walk a CTAP2 response, including the parts we ignore. */
    private static Object readValue(CborDecoder in) throws IOException {
        CborType type = in.peekType();
        switch (type.getMajorType()) {
            case CborConstants.TYPE_UNSIGNED_INTEGER:
            case CborConstants.TYPE_NEGATIVE_INTEGER:
                return in.readInt();
            case CborConstants.TYPE_BYTE_STRING:
                return in.readByteString(MAX_ITEM);
            case CborConstants.TYPE_TEXT_STRING:
                return in.readTextString(MAX_ITEM);
            case CborConstants.TYPE_ARRAY: {
                long length = in.readArrayLength();
                List<Object> values = new ArrayList<>();
                for (long i = 0; i < length; i++)
                    values.add(readValue(in));
                return values;
            }
            case CborConstants.TYPE_MAP: {
                long length = in.readMapLength();
                Map<Object, Object> values = new LinkedHashMap<>();
                for (long i = 0; i < length; i++) {
                    Object key = readValue(in);
                    values.put(key, readValue(in));
                }
                return values;
            }
            case CborConstants.TYPE_FLOAT_SIMPLE:
                if (type.getAdditionalInfo() == CborConstants.TRUE
                        || type.getAdditionalInfo() == CborConstants.FALSE)
                    return in.readBoolean();
                in.readNull();
                return null;
            default:
                throw new IOException("Unhandled cbor type " + type.getMajorType());
        }
    }
}
