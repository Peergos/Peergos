package peergos.server.login;

import com.webauthn4j.*;
import com.webauthn4j.authenticator.*;
import com.webauthn4j.converter.exception.*;
import com.webauthn4j.data.*;
import com.webauthn4j.data.attestation.authenticator.*;
import com.webauthn4j.data.attestation.statement.*;
import com.webauthn4j.data.client.*;
import com.webauthn4j.data.client.challenge.*;
import com.webauthn4j.server.*;
import com.webauthn4j.validator.exception.*;
import peergos.shared.cbor.*;

import java.io.*;
import java.util.*;

public class Webauthn {

    public static class Verifier implements Authenticator, Cborable {

        private final AttestedCredentialData credData;
        private final AttestationStatement statement;
        private long signCount;

        public Verifier(AttestedCredentialData credData, AttestationStatement statement, long signCount) {
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
            state.put("d", new CborObject.CborByteArray(serializeObject(credData)));
            state.put("s", new CborObject.CborByteArray(serializeObject(statement)));
            state.put("c", new CborObject.CborLong(signCount));
            return CborObject.CborMap.build(state);
        }

        private static byte[] serializeObject(Serializable obj) {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            try {
                ObjectOutputStream oos = new ObjectOutputStream(bout);
                oos.writeObject(obj);
                oos.flush();
                return bout.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private static Object deserializeObject(byte[] in) {
            try {
                return new ObjectInputStream(new ByteArrayInputStream(in)).readObject();
            } catch (IOException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        public static Verifier fromCbor(Cborable cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor for Verifier");
            AttestedCredentialData credData = (AttestedCredentialData) deserializeObject(((CborObject.CborMap) cbor).getByteArray("d"));
            AttestationStatement statement = (AttestationStatement) deserializeObject(((CborObject.CborMap) cbor).getByteArray("s"));
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
        } catch (ValidationException e) {
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
        } catch (ValidationException e) {
            // If you would like to handle WebAuthn data validation error, please catch ValidationException
            throw e;
        }
        return authenticationData.getAuthenticatorData().getSignCount();
    }
}
