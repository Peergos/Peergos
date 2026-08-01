package peergos.server.tests;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.data.client.Origin;
import org.junit.Assert;
import org.junit.Test;
import peergos.server.login.Webauthn;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.util.ArrayOps;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 *  A Nitrokey 3 has no batch attestation certificate, so it signs its registrations with the freshly generated
 *  credential key, i.e. packed self attestation, and reports an all zero aaguid. When the relying party asks for
 *  attestation "none" the browser normally rewrites the attestation to the none format, but the webauthn spec
 *  (5.1.3 step 22) makes an exception exactly for this case:
 *
 *    "If the aaguid in the attested credential data is 16 zero bytes, attestationObjectResult.fmt is "packed",
 *     and "x5c" is absent, then self attestation is being used and no further action is needed."
 *
 *  so the packed statement reaches us untouched, and we have to be able to register it.
 */
public class NitrokeyWebauthn {

    private static final String RP_ID = "peergos.net";
    private static final Origin ORIGIN = new Origin("https://" + RP_ID);
    private static final WebAuthnManager MANAGER = WebAuthnManager.createNonStrictWebAuthnManager();
    private static final byte[] ZERO_AAGUID = new byte[16];

    @Test
    public void nitrokeySelfAttestation() {
        SimulatedKey nitrokey = new SimulatedKey();
        Webauthn.Verifier registered = register(nitrokey, true);
        Webauthn.Verifier stored = roundTrip(registered);
        Assert.assertArrayEquals(nitrokey.credentialId, stored.getAttestedCredentialData().getCredentialId());
        Assert.assertEquals(1, login(nitrokey, stored));
    }

    /** The same authenticator, but with the attestation stripped by the browser, which already works. */
    @Test
    public void noneAttestation() {
        SimulatedKey key = new SimulatedKey();
        Webauthn.Verifier registered = register(key, false);
        Webauthn.Verifier stored = roundTrip(registered);
        Assert.assertArrayEquals(key.credentialId, stored.getAttestedCredentialData().getCredentialId());
        Assert.assertEquals(1, login(key, stored));
    }

    /** An ed25519 (COSE alg -8) authenticator with a long, non resident credential id. */
    private static class SimulatedKey {
        private final KeyPair keys;
        public final byte[] credentialId = new byte[157];

        public SimulatedKey() {
            try {
                this.keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            new SecureRandom().nextBytes(credentialId);
        }

        public byte[] sign(byte[] toSign) {
            try {
                Signature sig = Signature.getInstance("Ed25519");
                sig.initSign(keys.getPrivate());
                sig.update(toSign);
                return sig.sign();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /** COSE_Key = {1: 1 (OKP), 3: -8 (EdDSA), -1: 6 (ed25519), -2: public key} */
        public byte[] coseKey() {
            byte[] spki = keys.getPublic().getEncoded();
            byte[] raw = Arrays.copyOfRange(spki, spki.length - 32, spki.length);
            return ArrayOps.concat(new byte[]{(byte) 0xa4, 1, 1, 3, 0x27, 0x20, 6, 0x21, 0x58, 0x20}, raw);
        }

        public byte[] authenticatorData(boolean includeCredential, int signCount) {
            byte[] flags = new byte[]{(byte) (includeCredential ? 0x45 : 0x05), 0, 0, 0, (byte) signCount};
            byte[] header = ArrayOps.concat(sha256(RP_ID.getBytes(StandardCharsets.UTF_8)), flags);
            if (! includeCredential)
                return header;
            byte[] credIdLength = new byte[]{(byte) (credentialId.length >> 8), (byte) credentialId.length};
            return ArrayOps.concat(header, ArrayOps.concat(ArrayOps.concat(ZERO_AAGUID, credIdLength),
                    ArrayOps.concat(credentialId, coseKey())));
        }
    }

    private static Webauthn.Verifier register(SimulatedKey key, boolean selfAttestation) {
        byte[] challenge = randomChallenge();
        byte[] authData = key.authenticatorData(true, 0);
        byte[] clientDataJson = clientDataJson("webauthn.create", challenge);

        SortedMap<String, Cborable> attStmt = new TreeMap<>();
        if (selfAttestation) {
            attStmt.put("alg", new CborObject.CborLong(-8));
            attStmt.put("sig", new CborObject.CborByteArray(key.sign(ArrayOps.concat(authData, sha256(clientDataJson)))));
        }
        SortedMap<String, Cborable> attestation = new TreeMap<>();
        attestation.put("fmt", new CborObject.CborString(selfAttestation ? "packed" : "none"));
        attestation.put("attStmt", CborObject.CborMap.build(attStmt));
        attestation.put("authData", new CborObject.CborByteArray(authData));

        return Webauthn.validateRegistration(MANAGER, ORIGIN, RP_ID, challenge,
                CborObject.CborMap.build(attestation).serialize(), clientDataJson);
    }

    /** Verifiers are serialised into the mfa table and read back on the next login. */
    private static Webauthn.Verifier roundTrip(Webauthn.Verifier verifier) {
        return Webauthn.Verifier.fromCbor(CborObject.fromByteArray(verifier.serialize()));
    }

    private static long login(SimulatedKey key, Webauthn.Verifier verifier) {
        byte[] challenge = randomChallenge();
        byte[] authData = key.authenticatorData(false, 1);
        byte[] clientDataJson = clientDataJson("webauthn.get", challenge);
        byte[] signature = key.sign(ArrayOps.concat(authData, sha256(clientDataJson)));
        return Webauthn.validateLogin(MANAGER, ORIGIN, RP_ID, challenge, verifier, key.credentialId,
                "someuser".getBytes(StandardCharsets.UTF_8), authData, clientDataJson, signature);
    }

    private static byte[] clientDataJson(String type, byte[] challenge) {
        return ("{\"type\":\"" + type + "\",\"challenge\":\""
                + Base64.getUrlEncoder().withoutPadding().encodeToString(challenge)
                + "\",\"origin\":\"" + ORIGIN.toString() + "\",\"crossOrigin\":false}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] randomChallenge() {
        byte[] challenge = new byte[32];
        new SecureRandom().nextBytes(challenge);
        return challenge;
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
