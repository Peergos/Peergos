package peergos.shared.routing;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;

import java.util.*;
import java.util.concurrent.*;

/** ServerRotation { from Si, to Si+1, next H(Si+2) } signed by Si+1 (remove-pki §3.3). */
public class ServerRotation extends SignedRecord implements Rotation {
    public final PublicSigningKey from, to;
    public final byte[] next;

    public ServerRotation(PublicSigningKey from, PublicSigningKey to, byte[] next, byte[] signature) {
        super(signature);
        this.from = from;
        this.to = to;
        this.next = next;
    }

    public static CompletableFuture<ServerRotation> build(PublicSigningKey from, SigningKeyPair to, PublicSigningKey nextKey, Hasher hasher) {
        return hasher.sha256(nextKey.serialize())
                .thenCompose(next -> {
                    ServerRotation unsigned = new ServerRotation(from, to.publicSigningKey, next, new byte[0]);
                    return Signing.sign(to.secretSigningKey, unsigned.context(), unsigned.body().toByteArray())
                            .thenApply(sig -> new ServerRotation(from, to.publicSigningKey, next, sig));
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
        m.put("n", new CborObject.CborByteArray(next));
        return CborObject.CborMap.build(m);
    }

    @Override
    public String context() {
        return Signing.SERVER_ROTATION;
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
        Bounds.checkCommitment(next);
    }

    public static ServerRotation fromCbor(Cborable cbor) {
        CborObject.CborMap m = RoutingCbor.map(cbor, "ServerRotation");
        CborObject.CborMap body = RoutingCbor.map(m.get("b"), "ServerRotation body");
        return new ServerRotation(PublicSigningKey.fromCbor(body.get("f")),
                PublicSigningKey.fromCbor(body.get("t")),
                body.getByteArray("n"),
                m.getByteArray("s"));
    }
}
