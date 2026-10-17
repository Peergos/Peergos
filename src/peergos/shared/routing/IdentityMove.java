package peergos.shared.routing;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.*;

import java.util.*;
import java.util.concurrent.*;

/** IdentityMove { key Ki, providers, counter } signed by Ki: new providers without a new key (remove-pki §4.1). */
public class IdentityMove extends SignedRecord implements Update {
    public final PublicSigningKey key;
    public final List<Multihash> providers;
    public final long counter;

    public IdentityMove(PublicSigningKey key, List<Multihash> providers, long counter, byte[] signature) {
        super(signature);
        this.key = key;
        this.providers = Collections.unmodifiableList(new ArrayList<>(providers));
        this.counter = counter;
    }

    public static CompletableFuture<IdentityMove> build(SigningKeyPair key, List<Multihash> providers, long counter) {
        IdentityMove unsigned = new IdentityMove(key.publicSigningKey, providers, counter, new byte[0]);
        return Signing.sign(key.secretSigningKey, unsigned.context(), unsigned.body().toByteArray())
                .thenApply(sig -> new IdentityMove(key.publicSigningKey, providers, counter, sig));
    }

    @Override
    public PublicSigningKey key() {
        return key;
    }

    @Override
    public long counter() {
        return counter;
    }

    @Override
    public CborObject.CborMap body() {
        Map<String, Cborable> m = new TreeMap<>();
        m.put("k", key.toCbor());
        m.put("p", RoutingCbor.peerIds(providers));
        m.put("c", new CborObject.CborLong(counter));
        return CborObject.CborMap.build(m);
    }

    @Override
    public String context() {
        return Signing.IDENTITY_MOVE;
    }

    @Override
    public PublicSigningKey signer() {
        return key;
    }

    @Override
    protected List<? extends Cborable> keys() {
        return Collections.singletonList(key);
    }

    @Override
    protected void checkFields() {
        Bounds.checkProviders(providers);
        Bounds.checkCounter(counter);
    }

    public static IdentityMove fromCbor(Cborable cbor) {
        CborObject.CborMap m = RoutingCbor.map(cbor, "IdentityMove");
        CborObject.CborMap body = RoutingCbor.map(m.get("b"), "IdentityMove body");
        return new IdentityMove(PublicSigningKey.fromCbor(body.get("k")),
                RoutingCbor.peerIds(body.get("p")),
                body.getLong("c"),
                m.getByteArray("s"));
    }
}
