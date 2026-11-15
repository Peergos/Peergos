package peergos.shared.crypto.asymmetric.mldsa;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class HybridEd25519MLDSASecretKey implements SecretSigningKey {

    public final SecretSigningKey ed25519;
    private final byte[] mldsaSeed;
    private final Mldsa provider;

    public HybridEd25519MLDSASecretKey(SecretSigningKey ed25519, byte[] mldsaSeed, Mldsa provider) {
        if (ed25519.type() != PublicSigningKey.Type.Ed25519)
            throw new IllegalStateException("Composite key needs an Ed25519 component");
        if (mldsaSeed.length != Mldsa.SEED_BYTES)
            throw new IllegalStateException("Invalid ML-DSA-44 seed size: " + mldsaSeed.length);
        this.ed25519 = ed25519;
        this.mldsaSeed = mldsaSeed;
        this.provider = provider;
    }

    @Override
    public PublicSigningKey.Type type() {
        return PublicSigningKey.Type.HybridEd25519MLDSA;
    }

    @Override
    @SuppressWarnings("unusable-by-js")
    public CompletableFuture<byte[]> signMessage(byte[] message, SigningDomain domain) {
        return signatureOnly(message, domain)
                .thenApply(sig -> ArrayOps.concat(sig, message));
    }

    @Override
    @SuppressWarnings("unusable-by-js")
    public CompletableFuture<byte[]> signatureOnly(byte[] message, SigningDomain domain) {
        byte[] separated = HybridEd25519MLDSAPublicKey.domainSeparated(message, domain);
        return ed25519.signatureOnly(separated, domain)
                .thenCompose(edSig -> provider.sign(separated, mldsaSeed)
                        .thenApply(mldsaSig -> {
                            if (mldsaSig.length != Mldsa.SIGNATURE_BYTES)
                                throw new IllegalStateException("Invalid ML-DSA-44 signature size: " + mldsaSig.length);
                            return ArrayOps.concat(edSig, mldsaSig);
                        }));
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("e", ed25519.toCbor());
        state.put("m", new CborObject.CborByteArray(mldsaSeed));
        return new CborObject.CborList(Arrays.asList(new CborObject.CborLong(type().value), CborObject.CborMap.build(state)));
    }

    public static HybridEd25519MLDSASecretKey fromCbor(Cborable cbor, Ed25519 ed25519, Mldsa mldsa) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for HybridEd25519MLDSASecretKey! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) ((CborObject.CborList) cbor).value.get(1);
        SecretSigningKey ed = m.get("e", c -> Ed25519SecretKey.fromCbor(c, ed25519));
        return new HybridEd25519MLDSASecretKey(ed, m.getByteArray("m"), mldsa);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HybridEd25519MLDSASecretKey that = (HybridEd25519MLDSASecretKey) o;
        return ed25519.equals(that.ed25519) && Arrays.equals(mldsaSeed, that.mldsaSeed);
    }

    @Override
    public int hashCode() {
        return 31 * ed25519.hashCode() + Arrays.hashCode(mldsaSeed);
    }
}
