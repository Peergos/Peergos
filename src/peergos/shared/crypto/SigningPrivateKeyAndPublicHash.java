package peergos.shared.crypto;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
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
