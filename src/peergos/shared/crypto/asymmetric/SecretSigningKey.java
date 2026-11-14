package peergos.shared.crypto.asymmetric;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.asymmetric.mldsa.*;

import java.util.concurrent.*;

@JsType
public interface SecretSigningKey extends Cborable {

    PublicSigningKey.Type type();

    /**
     *
     * @param message
     * @return The signature + message
     */
    @SuppressWarnings("unusable-by-js")
    CompletableFuture<byte[]> signMessage(byte[] message, SigningDomain domain);

    /**
     *
     * @param message
     * @return Only the signature, excluding the original message
     */
    @SuppressWarnings("unusable-by-js")
    CompletableFuture<byte[]> signatureOnly(byte[] message, SigningDomain domain);

    @SuppressWarnings("unusable-by-js")
    static SecretSigningKey fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for SecretSigningKey! " + cbor);
        CborObject.CborLong type = (CborObject.CborLong) ((CborObject.CborList) cbor).value.get(0);
        PublicSigningKey.Type t = PublicSigningKey.Type.byValue((int) type.value);
        switch (t) {
            case Ed25519:
                return Ed25519SecretKey.fromCbor(cbor, PublicSigningKey.PROVIDERS.get(t));
            case HybridEd25519MLDSA:
                return HybridEd25519MLDSASecretKey.fromCbor(cbor, PublicSigningKey.PROVIDERS.get(PublicSigningKey.Type.Ed25519),
                        PublicSigningKey.MLDSA_PROVIDERS.get(t));
            default: throw new IllegalStateException("Unknown Secret Signing Key type: "+t.name());
        }
    }
}
