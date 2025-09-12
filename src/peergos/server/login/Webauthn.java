package peergos.server.login;

import com.webauthn4j.*;
import com.webauthn4j.authenticator.*;
import com.webauthn4j.converter.exception.*;
import com.webauthn4j.converter.util.CborConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.*;
import com.webauthn4j.data.attestation.authenticator.*;
import com.webauthn4j.data.attestation.statement.*;
import com.webauthn4j.data.client.*;
import com.webauthn4j.data.client.challenge.*;
import com.webauthn4j.server.*;
import com.webauthn4j.verifier.exception.*;
import peergos.shared.cbor.*;
import peergos.shared.util.ArrayOps;

import java.io.*;
import java.util.*;

public class Webauthn {

    public static class Verifier implements Authenticator, Cborable {

        private final AttestedCredentialData credData;
        private final AttestationStatement statement;
        private long signCount;

        public Verifier(AttestedCredentialData credData, AttestationStatement statement, long signCount) {
            if (!(statement instanceof NoneAttestationStatement))
                throw new IllegalStateException("Attested keys not supported!");
            this.credData = credData;
            this.statement = statement;
            this.signCount = signCount;
        }

        @Override
        public AttestedCredentialData getAttestedCredentialData() {
            return credData;
        }

        @Override
        public long getCounter() {
            return signCount;
        }

        @Override
        public void setCounter(long count) {
            signCount = count;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            CborConverter cborConverter = new ObjectConverter().getCborConverter();
            state.put("d", new CborObject.CborByteArray(cborConverter.writeValueAsBytes(credData)));
            state.put("s", new CborObject.CborByteArray(cborConverter.writeValueAsBytes((statement))));
            state.put("c", new CborObject.CborLong(signCount));
            return CborObject.CborMap.build(state);
        }

        public static Verifier fromCbor(Cborable cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor for Verifier");
            CborConverter cborConverter = new ObjectConverter().getCborConverter();
            try {
                AttestedCredentialData credData = cborConverter.readValue(((CborObject.CborMap) cbor).getByteArray("d"), AttestedCredentialData.class);
                byte[] s = ((CborObject.CborMap) cbor).getByteArray("s");
                CborObject cb = CborObject.fromByteArray(s);
                if (! (cb instanceof CborObject.CborMap && ((CborObject.CborMap) cb).keySet().isEmpty()))
                    throw new IllegalStateException("Unsupported Webauthn Attestation type");
                AttestationStatement statement = new NoneAttestationStatement();
                long signCount = ((CborObject.CborMap) cbor).getLong("c");
                return new Verifier(credData, statement, signCount);
            } catch (Exception e) {
                return fromLegacyCbor(cbor);
            }
        }

        private static byte[] NO_ATTESTATION = ArrayOps.hexToBytes("aced000573720042636f6d2e776562617574686e346a2e646174612e6174746573746174696f6e2e73746174656d656e742e4e6f6e654174746573746174696f6e53746174656d656e746b3b9efd2e6430530200007870");
        private static byte[] ED25519 = ArrayOps.hexToBytes("aced000573720044636f6d2e776562617574686e346a2e646174612e6174746573746174696f6e2e61757468656e74696361746f722e417474657374656443726564656e7469616c4461746185df6d0f6d8aacb40200034c00066161677569647400364c636f6d2f776562617574686e346a2f646174612f6174746573746174696f6e2f61757468656e74696361746f722f4141475549443b4c0007636f73654b65797400374c636f6d2f776562617574686e346a2f646174612f6174746573746174696f6e2f61757468656e74696361746f722f434f53454b65793b5b000c63726564656e7469616c49647400025b42787073720034636f6d2e776562617574686e346a2e646174612e6174746573746174696f6e2e61757468656e74696361746f722e414147554944fb3908248cfbd7d40200014c000576616c75657400104c6a6176612f7574696c2f555549443b78707372000e6a6176612e7574696c2e55554944bc9903f7986d852f0200024a000c6c65617374536967426974734a000b6d6f7374536967426974737870000000000000000000000000000000007372003a636f6d2e776562617574686e346a2e646174612e6174746573746174696f6e2e61757468656e74696361746f722e4564445341434f53454b65794f34c5f4776431400200034c000563757276657400354c636f6d2f776562617574686e346a2f646174612f6174746573746174696f6e2f61757468656e74696361746f722f43757276653b5b00016471007e00035b00017871007e00037872003d636f6d2e776562617574686e346a2e646174612e6174746573746174696f6e2e61757468656e74696361746f722e4162737472616374434f53454b657911b0302464c2e4c90200044c0009616c676f726974686d7400434c636f6d2f776562617574686e346a2f646174612f6174746573746174696f6e2f73746174656d656e742f434f5345416c676f726974686d4964656e7469666965723b5b000662617365495671007e00035b00056b6579496471007e00034c00066b65794f70737400104c6a6176612f7574696c2f4c6973743b787073720041636f6d2e776562617574686e346a2e646174612e6174746573746174696f6e2e73746174656d656e742e434f5345416c676f726974686d4964656e746966696572b5e5a801c4dc74180200014a000576616c75657870fffffffffffffff87070707e720033636f6d2e776562617574686e346a2e646174612e6174746573746174696f6e2e61757468656e74696361746f722e437572766500000000000000001200007872000e6a6176612e6c616e672e456e756d000000000000000012000078707400074544323535313970757200025b42acf317f8060854e0020000787000000020");
        private static byte[] SECP_PREFIX = ArrayOps.hexToBytes("aced000573720044636f6d2e776562617574686e346a2e646174612e6174746573746174696f6e2e61757468656e74696361746f722e417474657374656443726564656e7469616c4461746185df6d0f6d8aacb40200034c00066161677569647400364c636f6d2f776562617574686e346a2f646174612f6174746573746174696f6e2f61757468656e74696361746f722f4141475549443b4c0007636f73654b65797400374c636f6d2f776562617574686e346a2f646174612f6174746573746174696f6e2f61757468656e74696361746f722f434f53454b65793b5b000c63726564656e7469616c49647400025b42787073720034636f6d2e776562617574686e346a2e646174612e6174746573746174696f6e2e61757468656e74696361746f722e414147554944fb3908248cfbd7d40200014c000576616c75657400104c6a6176612f7574696c2f555549443b78707372000e6a6176612e7574696c2e55554944bc9903f7986d852f0200024a000c6c65617374536967426974734a000b6d6f7374536967426974737870");

        public static Verifier fromLegacyCbor(Cborable cbor) {
            // This is a reversing of the default Java object serialisation in an older version of the webauthn library
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor for Verifier");
            byte[] rawD = ((CborObject.CborMap) cbor).getByteArray("d");
            if (! ArrayOps.equalArrays(rawD, 0, 1017, ED25519, 0, 1017)) {
                if (! ArrayOps.equalArrays(rawD, 0, 399, SECP_PREFIX, 0, 399))
                    throw new IllegalStateException("Unknown passkey type!");
                byte[] rawAguid = Arrays.copyOfRange(rawD, 399, 399 + 16);
                byte[] x = Arrays.copyOfRange(rawD, 1026, 1026 + 32);
                byte[] y = Arrays.copyOfRange(rawD, 1068, 1068 + 32);
                byte[] credentialId = Arrays.copyOfRange(rawD, 1110, 1110 + 16 + (rawD.length - 1126));
                byte[] aguid = ArrayOps.concat(Arrays.copyOfRange(rawAguid, 8, 16), Arrays.copyOfRange(rawAguid, 0, 8));
                AttestedCredentialData credData = new AttestedCredentialData(
                        new AAGUID(aguid),
                        credentialId,
                        new EC2COSEKey(null, COSEAlgorithmIdentifier.ES256, null, Curve.SECP256R1, x, y, null));
                AttestationStatement statement = new NoneAttestationStatement();
                long signCount = ((CborObject.CborMap) cbor).getLong("c");
                return new Verifier(credData, statement, signCount);
            }
            AttestedCredentialData credData = new AttestedCredentialData(AAGUID.ZERO,
                    Arrays.copyOfRange(rawD, 1059, 1059+128 + (rawD.length - 1187)),
                    new EdDSACOSEKey(null, COSEAlgorithmIdentifier.EdDSA, null, Curve.ED25519, Arrays.copyOfRange(rawD, 1017, 1017 + 32), null));
            byte[] rawS = ((CborObject.CborMap) cbor).getByteArray("s");
            if (! Arrays.equals(rawS, NO_ATTESTATION))
                throw new IllegalStateException("Unknown webauthn attestation type!");
            AttestationStatement statement = new NoneAttestationStatement();
            long signCount = ((CborObject.CborMap) cbor).getLong("c");
            return new Verifier(credData, statement, signCount);
        }
    }

    public static Verifier validateRegistration(WebAuthnManager webAuthnManager,
                                                Origin origin,
                                                String rpId,
                                                byte[] rawChallenge,
                                                byte[] attestationObject,
                                                byte[] clientDataJSON) {
        Challenge challenge = () -> rawChallenge;
        byte[] tokenBindingId = null;
        ServerProperty serverProperty = new ServerProperty(origin, rpId, challenge, tokenBindingId);

        // expectations
        boolean userVerificationRequired = false;
        boolean userPresenceRequired = true;

        String clientExtensionsJSON = null;
        Set<String> transports = null;
        RegistrationRequest registrationRequest = new RegistrationRequest(attestationObject, clientDataJSON, clientExtensionsJSON, transports);
        RegistrationParameters registrationParameters = new RegistrationParameters(serverProperty, userVerificationRequired, userPresenceRequired);
        RegistrationData registrationData;
        try {
            registrationData = webAuthnManager.parse(registrationRequest);
        } catch (DataConversionException e) {
            // If you would like to handle WebAuthn data structure parse error, please catch DataConversionException
            throw e;
        }
        try {
            webAuthnManager.validate(registrationData, registrationParameters);
        } catch (VerificationException e) {
            // If you would like to handle WebAuthn data validation error, please catch ValidationException
            throw e;
        }

        return new Verifier(
                registrationData.getAttestationObject().getAuthenticatorData().getAttestedCredentialData(),
                registrationData.getAttestationObject().getAttestationStatement(),
                registrationData.getAttestationObject().getAuthenticatorData().getSignCount()
        );
    }

    public static long validateLogin(WebAuthnManager webAuthnManager,
                                     Origin origin,
                                     String rpId,
                                     byte[] rawChallenge,
                                     Authenticator user,
                                     byte[] credentialId,
                                     byte[] userHandle,
                                     byte[] authenticatorData,
                                     byte[] clientDataJSON,
                                     byte[] signature) {
        String clientExtensionJSON = null /* set clientExtensionJSON */;

        Challenge challenge = () -> rawChallenge;
        byte[] tokenBindingId = null;
        ServerProperty serverProperty = new ServerProperty(origin, rpId, challenge, tokenBindingId);

        // expectations
        List<byte[]> allowCredentials = null;
        boolean userVerificationRequired = false;
        boolean userPresenceRequired = true;

        AuthenticationRequest authenticationRequest =
                new AuthenticationRequest(
                        credentialId,
                        userHandle,
                        authenticatorData,
                        clientDataJSON,
                        clientExtensionJSON,
                        signature
                );
        AuthenticationParameters authenticationParameters =
                new AuthenticationParameters(
                        serverProperty,
                        user,
                        allowCredentials,
                        userVerificationRequired,
                        userPresenceRequired
                );

        AuthenticationData authenticationData;
        try {
            authenticationData = webAuthnManager.parse(authenticationRequest);
        } catch (DataConversionException e) {
            // If you would like to handle WebAuthn data structure parse error, please catch DataConversionException
            throw e;
        }
        try {
            webAuthnManager.validate(authenticationData, authenticationParameters);
        } catch (VerificationException e) {
            // If you would like to handle WebAuthn data validation error, please catch ValidationException
            throw e;
        }
        return authenticationData.getAuthenticatorData().getSignCount();
    }
}
