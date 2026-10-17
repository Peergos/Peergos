package peergos.shared.routing;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.*;

import java.util.*;
import java.util.concurrent.*;

/** IdentityInception { identity K0, providers, next H(K1) } signed by K0, plus an admission (remove-pki §4.1). */
public class IdentityInception extends SignedRecord implements Establishment {
    public final PublicSigningKey identity;
    public final List<Multihash> providers;
    public final byte[] next;
    public final Optional<Admission> admission;

    public IdentityInception(PublicSigningKey identity, List<Multihash> providers, byte[] next, byte[] signature, Optional<Admission> admission) {
        super(signature);
        this.identity = identity;
        this.providers = Collections.unmodifiableList(new ArrayList<>(providers));
        this.next = next;
        this.admission = admission;
    }

    public static CompletableFuture<IdentityInception> build(SigningKeyPair identity,
                                                             List<Multihash> providers,
                                                             PublicSigningKey nextKey,
                                                             Hasher hasher) {
        return hasher.sha256(nextKey.serialize())
                .thenCompose(next -> {
                    IdentityInception unsigned = new IdentityInception(identity.publicSigningKey, providers, next, new byte[0], Optional.empty());
                    return Signing.sign(identity.secretSigningKey, unsigned.context(), unsigned.body().toByteArray())
                            .thenApply(sig -> new IdentityInception(identity.publicSigningKey, providers, next, sig, Optional.empty()));
                });
    }

    public IdentityInception withAdmission(Admission admission) {
        return new IdentityInception(identity, providers, next, signature, Optional.of(admission));
    }

    public PublicKeyHash identityHash() {
        return ContentAddressedStorage.hashKey(identity);
    }

    public Multihash admittingServer() {
        return providers.get(0);
    }

    @Override
    public PublicSigningKey key() {
        return identity;
    }

    @Override
    public byte[] next() {
        return next;
    }

    @Override
    public CborObject.CborMap body() {
        Map<String, Cborable> m = new TreeMap<>();
        m.put("k", identity.toCbor());
        m.put("p", RoutingCbor.peerIds(providers));
        m.put("n", new CborObject.CborByteArray(next));
        return CborObject.CborMap.build(m);
    }

    @Override
    public String context() {
        return Signing.IDENTITY_INCEPTION;
    }

    @Override
    public PublicSigningKey signer() {
        return identity;
    }

    @Override
    protected List<? extends Cborable> keys() {
        return Collections.singletonList(identity);
    }

    @Override
    protected void checkFields() {
        Bounds.checkProviders(providers);
        Bounds.checkCommitment(next);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> m = envelope();
        admission.ifPresent(a -> m.put("a", a));
        return CborObject.CborMap.build(m);
    }

    public static IdentityInception fromCbor(Cborable cbor) {
        CborObject.CborMap m = RoutingCbor.map(cbor, "IdentityInception");
        CborObject.CborMap body = RoutingCbor.map(m.get("b"), "IdentityInception body");
        return new IdentityInception(PublicSigningKey.fromCbor(body.get("k")),
                RoutingCbor.peerIds(body.get("p")),
                body.getByteArray("n"),
                m.getByteArray("s"),
                m.getOptional("a", Admission::fromCbor));
    }
}
