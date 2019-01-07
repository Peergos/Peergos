package peergos.shared.crypto;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;

import java.util.*;

public class SigningPrivateKeyAndPublicHash implements Cborable {
    public final PublicKeyHash publicKeyHash;
    public final SecretSigningKey secret;

    public SigningPrivateKeyAndPublicHash(PublicKeyHash publicKeyHash, SecretSigningKey secret) {
        this.publicKeyHash = publicKeyHash;
        this.secret = secret;
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> result = new TreeMap<>();
        result.put("p", publicKeyHash.toCbor());
        result.put("s", secret.toCbor());
        return CborObject.CborMap.build(result);
    }

    public static SigningPrivateKeyAndPublicHash fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for SigningPrivateKeyAndPublicHash: " + cbor);
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        PublicKeyHash publicKeyHash = PublicKeyHash.fromCbor(map.get("p"));
        SecretSigningKey secretKey = SecretSigningKey.fromCbor(map.get("s"));
        return new SigningPrivateKeyAndPublicHash(publicKeyHash, secretKey);
    }
}
