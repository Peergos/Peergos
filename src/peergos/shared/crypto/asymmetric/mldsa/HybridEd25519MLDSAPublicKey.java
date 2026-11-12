package peergos.shared.crypto.asymmetric.mldsa;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** A composite Ed25519 + ML-DSA-44 key: a signature is valid only if both components verify.
 *
 *  Both components sign DOMAIN || message, so neither signature can be stripped off and passed off as a plain
 *  signature on the message. Signed messages are ed25519 sig || ml-dsa sig || message.
 */
public class HybridEd25519MLDSAPublicKey implements PublicSigningKey {
    public static final byte[] DOMAIN = "peergos/ed25519+ml-dsa-44".getBytes();
    public static final int SIGNATURE_SIZE_BYTES = Ed25519PublicKey.SIGNATURE_SIZE_BYTES + Mldsa.SIGNATURE_BYTES;

    public final Ed25519PublicKey ed25519;
    private final byte[] mldsa;
    private final Mldsa provider;

    public HybridEd25519MLDSAPublicKey(Ed25519PublicKey ed25519, byte[] mldsa, Mldsa provider) {
        if (mldsa.length != Mldsa.PUBLIC_KEY_BYTES)
            throw new IllegalStateException("Invalid ML-DSA-44 public key size: " + mldsa.length);
        this.ed25519 = ed25519;
        this.mldsa = mldsa;
        this.provider = provider;
    }

    @Override
    public Type type() {
        return Type.HybridEd25519MLDSA;
    }

    static byte[] domainSeparated(byte[] message) {
        return ArrayOps.concat(DOMAIN, message);
    }

    @Override
    public CompletableFuture<byte[]> unsignMessage(byte[] signed) {
        if (provider == null)
            throw new IllegalStateException("Uninitialized crypto-implementation: call peergos.shared.Crypto::init");
        if (signed.length < SIGNATURE_SIZE_BYTES)
            return Futures.errored(new IllegalStateException("Invalid signature"));
        int edLen = Ed25519PublicKey.SIGNATURE_SIZE_BYTES;
        byte[] edSig = Arrays.copyOfRange(signed, 0, edLen);
        byte[] mldsaSig = Arrays.copyOfRange(signed, edLen, SIGNATURE_SIZE_BYTES);
        byte[] message = Arrays.copyOfRange(signed, SIGNATURE_SIZE_BYTES, signed.length);
        byte[] separated = domainSeparated(message);
        return ed25519.unsignMessage(ArrayOps.concat(edSig, separated))
                .thenCompose(edMessage -> {
                    if (! Arrays.equals(edMessage, separated))
                        throw new IllegalStateException("Invalid signature");
                    return provider.verify(mldsaSig, separated, mldsa);
                }).thenApply(valid -> {
                    if (! valid)
                        throw new IllegalStateException("Invalid signature");
                    return message;
                });
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("e", ed25519.toCbor());
        state.put("m", new CborObject.CborByteArray(mldsa));
        return new CborObject.CborList(Arrays.asList(new CborObject.CborLong(type().value), CborObject.CborMap.build(state)));
    }

    public static HybridEd25519MLDSAPublicKey fromCbor(Cborable cbor, Ed25519 ed25519, Mldsa mldsa) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for HybridEd25519MLDSAPublicKey! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) ((CborObject.CborList) cbor).value.get(1);
        Ed25519PublicKey ed = m.get("e", c -> Ed25519PublicKey.fromCbor(c, ed25519));
        return new HybridEd25519MLDSAPublicKey(ed, m.getByteArray("m"), mldsa);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HybridEd25519MLDSAPublicKey that = (HybridEd25519MLDSAPublicKey) o;
        return ed25519.equals(that.ed25519) && Arrays.equals(mldsa, that.mldsa);
    }

    @Override
    public int hashCode() {
        return 31 * ed25519.hashCode() + Arrays.hashCode(mldsa);
    }

    @Override
    public String toString() {
        return Base64.getEncoder().encodeToString(serialize());
    }
}
