package peergos.shared.routing;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;

import java.util.*;
import java.util.concurrent.*;

/** ServerInception { server S0, next H(S1) } signed by S0, plus proof of work over S0 (remove-pki §4.1). */
public class ServerInception extends SignedRecord implements Establishment {
    public final PublicSigningKey server;
    public final byte[] next;
    public final Optional<byte[]> proof;

    public ServerInception(PublicSigningKey server, byte[] next, byte[] signature, Optional<byte[]> proof) {
        super(signature);
        this.server = server;
        this.next = next;
        this.proof = proof;
    }

    public static CompletableFuture<ServerInception> build(SigningKeyPair server, PublicSigningKey nextKey, Hasher hasher) {
        return hasher.sha256(nextKey.serialize())
                .thenCompose(next -> {
                    ServerInception unsigned = new ServerInception(server.publicSigningKey, next, new byte[0], Optional.empty());
                    return Signing.sign(server.secretSigningKey, unsigned.context(), unsigned.body().toByteArray())
                            .thenApply(sig -> new ServerInception(server.publicSigningKey, next, sig, Optional.empty()));
                });
    }

    public ServerInception withProof(byte[] proof) {
        return new ServerInception(server, next, signature, Optional.of(proof));
    }

    public Multihash peerId() {
        return ServerKeys.peerId(server);
    }

    @Override
    public PublicSigningKey key() {
        return server;
    }

    @Override
    public byte[] next() {
        return next;
    }

    @Override
    public CborObject.CborMap body() {
        Map<String, Cborable> m = new TreeMap<>();
        m.put("k", server.toCbor());
        m.put("n", new CborObject.CborByteArray(next));
        return CborObject.CborMap.build(m);
    }

    @Override
    public String context() {
        return Signing.SERVER_INCEPTION;
    }

    @Override
    public PublicSigningKey signer() {
        return server;
    }

    @Override
    protected List<? extends Cborable> keys() {
        return Collections.singletonList(server);
    }

    @Override
    protected void checkFields() {
        Bounds.checkCommitment(next);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> m = envelope();
        proof.ifPresent(w -> m.put("w", new CborObject.CborByteArray(w)));
        return CborObject.CborMap.build(m);
    }

    public static ServerInception fromCbor(Cborable cbor) {
        CborObject.CborMap m = RoutingCbor.map(cbor, "ServerInception");
        CborObject.CborMap body = RoutingCbor.map(m.get("b"), "ServerInception body");
        return new ServerInception(PublicSigningKey.fromCbor(body.get("k")),
                body.getByteArray("n"),
                m.getByteArray("s"),
                m.getOptionalByteArray("w"));
    }
}
