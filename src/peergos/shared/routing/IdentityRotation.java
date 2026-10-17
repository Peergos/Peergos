package peergos.shared.routing;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;

import java.util.*;
import java.util.concurrent.*;

/** IdentityRotation { from Ki, to Ki+1, providers, next H(Ki+2) } signed by Ki+1, valid only if H(Ki+1) is the
 *  commitment in Ki's establishment record (remove-pki §3.2, §4.2).
 */
public class IdentityRotation extends SignedRecord implements Rotation {
    public final PublicSigningKey from, to;
    public final List<Multihash> providers;
    public final byte[] next;

    public IdentityRotation(PublicSigningKey from, PublicSigningKey to, List<Multihash> providers, byte[] next, byte[] signature) {
        super(signature);
        this.from = from;
        this.to = to;
        this.providers = Collections.unmodifiableList(new ArrayList<>(providers));
        this.next = next;
    }

    public static CompletableFuture<IdentityRotation> build(PublicSigningKey from,
                                                            SigningKeyPair to,
                                                            List<Multihash> providers,
                                                            PublicSigningKey nextKey,
                                                            Hasher hasher) {
        return hasher.sha256(nextKey.serialize())
                .thenCompose(next -> {
                    IdentityRotation unsigned = new IdentityRotation(from, to.publicSigningKey, providers, next, new byte[0]);
                    return Signing.sign(to.secretSigningKey, unsigned.context(), unsigned.body().toByteArray())
                            .thenApply(sig -> new IdentityRotation(from, to.publicSigningKey, providers, next, sig));
                });
    }

    @Override
    public PublicSigningKey from() {
        return from;
    }

    @Override
    public PublicSigningKey key() {
        return to;
    }

    @Override
    public byte[] next() {
        return next;
    }

    @Override
    public CborObject.CborMap body() {
        Map<String, Cborable> m = new TreeMap<>();
        m.put("f", from.toCbor());
        m.put("t", to.toCbor());
        m.put("p", RoutingCbor.peerIds(providers));
        m.put("n", new CborObject.CborByteArray(next));
        return CborObject.CborMap.build(m);
    }

    @Override
    public String context() {
        return Signing.IDENTITY_ROTATION;
    }

    @Override
    public PublicSigningKey signer() {
        return to;
    }

    @Override
    protected List<? extends Cborable> keys() {
        return Arrays.asList(from, to);
    }

    @Override
    protected void checkFields() {
        Bounds.checkProviders(providers);
        Bounds.checkCommitment(next);
    }

    public static IdentityRotation fromCbor(Cborable cbor) {
        CborObject.CborMap m = RoutingCbor.map(cbor, "IdentityRotation");
        CborObject.CborMap body = RoutingCbor.map(m.get("b"), "IdentityRotation body");
        return new IdentityRotation(PublicSigningKey.fromCbor(body.get("f")),
                PublicSigningKey.fromCbor(body.get("t")),
                RoutingCbor.peerIds(body.get("p")),
                body.getByteArray("n"),
                m.getByteArray("s"));
    }
}
