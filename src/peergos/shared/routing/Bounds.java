package peergos.shared.routing;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.*;

import java.util.*;

/** remove-pki §4.1: a record over any bound is invalid. */
public class Bounds {
    public static final int MAX_PROVIDERS = 4;
    public static final int MAX_ADDRESSES = 8;
    public static final int MAX_ADDRESS_BYTES = 64;
    public static final int MAX_RELAYS = 3;
    public static final int MAX_TRANSPORT_NAME = 16;
    /** Excluding signatures, proof of work and keys, whose sizes are fixed by their type. */
    public static final int MAX_BODY_BYTES = 1024;
    public static final int MAX_PEERID_BYTES = 64;
    /** A Cuckoo Cycle proof is about 190 bytes. */
    public static final int MAX_PROOF_BYTES = 256;
    /** An ML-KEM-1024 public key. */
    public static final int MAX_KEM_BYTES = 1568;
    /** The cbor framing of what sits beside a record's body: its signature, and a proof or an admission. */
    private static final int MAX_FRAMING_BYTES = 64;

    /** Every key and signature has the one size its type gives it, so neither can carry anything else. */
    public static int serializedKeyBytes(PublicSigningKey.Type type) {
        switch (type) {
            case Ed25519:
                return 36;
            default:
                throw new IllegalStateException("No key size for " + type);
        }
    }

    public static int signatureBytes(PublicSigningKey.Type type) {
        switch (type) {
            case Ed25519:
                return 64;
            default:
                throw new IllegalStateException("No signature size for " + type);
        }
    }

    /** An admission is signed by a server key the record doesn't carry, so its signature may be any type's. */
    public static int maxSignatureBytes() {
        int max = 0;
        for (PublicSigningKey.Type t : PublicSigningKey.Type.values())
            max = Math.max(max, signatureBytes(t));
        return max;
    }

    public static void checkKey(PublicSigningKey key) {
        if (key.serialize().length != serializedKeyBytes(key.type()))
            throw new IllegalStateException("Wrong key size");
    }

    public static void checkSignature(PublicSigningKey signer, byte[] signature) {
        if (signature.length != signatureBytes(signer.type()))
            throw new IllegalStateException("Wrong signature size: " + signature.length);
    }

    public static void checkProof(byte[] proof) {
        if (proof.length > MAX_PROOF_BYTES)
            throw new IllegalStateException("Proof of work too large: " + proof.length);
    }

    public static void checkAdmission(Admission admission) {
        checkProof(admission.proof);
        if (admission.signature.length > maxSignatureBytes())
            throw new IllegalStateException("Admission signature too large: " + admission.signature.length);
    }

    /** The record as stored and passed on, so nothing outside its body escapes the bounds. */
    public static void checkTotal(SignedRecord record) {
        int outside = record.serialize().length - record.body().toByteArray().length;
        if (outside > signatureBytes(record.signer().type()) + MAX_PROOF_BYTES + maxSignatureBytes() + MAX_FRAMING_BYTES)
            throw new IllegalStateException("Record too large");
    }

    public static void checkProviders(List<Multihash> providers) {
        if (providers.isEmpty())
            throw new IllegalStateException("A record must name at least one provider");
        if (providers.size() > MAX_PROVIDERS)
            throw new IllegalStateException("Too many providers: " + providers.size());
        checkPeerIds(providers);
    }

    public static void checkPeerIds(List<Multihash> ids) {
        for (Multihash id : ids)
            if (id.toBytes().length > MAX_PEERID_BYTES)
                throw new IllegalStateException("Peerid too large");
        if (new HashSet<>(ids).size() != ids.size())
            throw new IllegalStateException("Duplicate peerids");
    }

    public static void checkBody(CborObject body, List<? extends Cborable> keys) {
        int size = body.toByteArray().length;
        for (Cborable key : keys)
            size -= key.serialize().length;
        if (size > MAX_BODY_BYTES)
            throw new IllegalStateException("Record body too large: " + size);
    }

    public static void checkCounter(long counter) {
        if (counter < 0)
            throw new IllegalStateException("Negative counter");
    }

    public static void checkCommitment(byte[] next) {
        if (next.length != 32)
            throw new IllegalStateException("A next-key commitment is a 32 byte hash");
    }
}
