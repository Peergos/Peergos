package peergos.shared.routing;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** The home server's proof of work over a new identity key, signed with its own server key (remove-pki §4.1).
 *  It sits outside the inception's signed body, so it can be computed after signup and re-signed after the
 *  server rotates.
 */
public class Admission implements Cborable {
    public final byte[] proof;
    public final byte[] signature;

    public Admission(byte[] proof, byte[] signature) {
        this.proof = proof;
        this.signature = signature;
    }

    static byte[] signedBytes(PublicSigningKey identity, byte[] proof) {
        return new CborObject.CborList(Arrays.asList(identity.toCbor(), new CborObject.CborByteArray(proof))).toByteArray();
    }

    public static CompletableFuture<Admission> build(PublicSigningKey identity, byte[] proof, SecretSigningKey server) {
        return Signing.sign(server, Signing.ADMISSION, signedBytes(identity, proof))
                .thenApply(sig -> new Admission(proof, sig));
    }

    public CompletableFuture<Boolean> verify(PublicSigningKey identity, PublicSigningKey server, Work work) {
        return work.verify(identity.serialize(), proof)
                .thenCompose(worked -> ! worked ?
                        Futures.of(false) :
                        Signing.verify(server, Signing.ADMISSION, signedBytes(identity, proof), signature));
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> m = new TreeMap<>();
        m.put("w", new CborObject.CborByteArray(proof));
        m.put("s", new CborObject.CborByteArray(signature));
        return CborObject.CborMap.build(m);
    }

    public static Admission fromCbor(Cborable cbor) {
        CborObject.CborMap m = RoutingCbor.map(cbor, "Admission");
        return new Admission(m.getByteArray("w"), m.getByteArray("s"));
    }
}
