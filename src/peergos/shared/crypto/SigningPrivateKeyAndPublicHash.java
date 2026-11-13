package peergos.shared.crypto;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.asymmetric.mldsa.*;
import peergos.shared.crypto.hash.*;

import java.util.*;

@JsType
public class SigningPrivateKeyAndPublicHash implements Cborable {
    public final PublicKeyHash publicKeyHash;
    public final SecretSigningKey secret;

    public SigningPrivateKeyAndPublicHash(PublicKeyHash publicKeyHash, SecretSigningKey secret) {
        this.publicKeyHash = publicKeyHash;
        this.secret = secret;
    }

    /** Whether this is an Ed25519 writer holding the composite key it can upgrade to in place, keeping its hash */
    public boolean isUpgradable() {
        return publicKeyHash.isIdentity() && secret.type() == PublicSigningKey.Type.HybridEd25519MLDSA;
    }

    @SuppressWarnings("unusable-by-js")
    public Optional<HybridEd25519MLDSAPublicKey> upgradedKey() {
        if (! isUpgradable())
            return Optional.empty();
        Ed25519PublicKey ed25519 = (Ed25519PublicKey) PublicSigningKey.fromCbor(CborObject.fromByteArray(publicKeyHash.getHash()));
        return Optional.of(((HybridEd25519MLDSASecretKey) secret).publicKey(ed25519));
    }

    /** What signs everything but pointers. The server checks those against the key a writer's hash names,
     *  which for an upgradable writer is its Ed25519 key.
     */
    public SigningPrivateKeyAndPublicHash blockSigner() {
        return isUpgradable() ?
                new SigningPrivateKeyAndPublicHash(publicKeyHash, ((HybridEd25519MLDSASecretKey) secret).ed25519) :
                this;
    }

    /** What signs a pointer to WriterData with this controller: the composite key once the writer has upgraded */
    @SuppressWarnings("unusable-by-js")
    public SigningPrivateKeyAndPublicHash pointerSigner(PublicKeyHash controller) {
        return isUpgradable() && controller.equals(publicKeyHash) ? blockSigner() : this;
    }

    @Override
    @SuppressWarnings("unusable-by-js")
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("p", publicKeyHash.toCbor());
        result.put("s", secret.toCbor());
        return CborObject.CborMap.build(result);
    }

    @SuppressWarnings("unusable-by-js")
    public static SigningPrivateKeyAndPublicHash fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for SigningPrivateKeyAndPublicHash: " + cbor);
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        PublicKeyHash publicKeyHash = PublicKeyHash.fromCbor(map.get("p"));
        SecretSigningKey secretKey = SecretSigningKey.fromCbor(map.get("s"));
        return new SigningPrivateKeyAndPublicHash(publicKeyHash, secretKey);
    }

    @Override
    public String toString() {
        return publicKeyHash.toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(publicKeyHash, secret);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SigningPrivateKeyAndPublicHash that = (SigningPrivateKeyAndPublicHash) o;
        return Objects.equals(publicKeyHash, that.publicKeyHash) && Objects.equals(secret, that.secret);
    }
}
