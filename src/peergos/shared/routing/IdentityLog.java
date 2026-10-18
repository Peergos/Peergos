package peergos.shared.routing;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;

import java.util.*;

/** An identity's records and current state: its current key and storage providers (remove-pki §4.2). */
public final class IdentityLog implements SubjectLog {
    public final PublicKeyHash identity;
    public final RecordLog<IdentityInception, IdentityRotation, IdentityMove> records;
    public final Optional<LegacyIdentity> legacy;

    private IdentityLog(PublicKeyHash identity,
                        RecordLog<IdentityInception, IdentityRotation, IdentityMove> records,
                        Optional<LegacyIdentity> legacy) {
        this.identity = identity;
        this.records = records;
        this.legacy = legacy;
    }

    public static IdentityLog empty(PublicKeyHash identity) {
        return new IdentityLog(identity, RecordLog.empty(), Optional.empty());
    }

    public static IdentityLog legacy(LegacyIdentity legacy) {
        return new IdentityLog(legacy.identity(), RecordLog.empty(), Optional.of(legacy));
    }

    @Override
    public RecordLog.Change<IdentityLog> add(Verified<? extends SignedRecord> v) {
        SignedRecord r = v.record;
        if (r instanceof IdentityInception) {
            IdentityInception inception = (IdentityInception) r;
            boolean forThisIdentity = legacy.isPresent() ?
                    inception.identity.equals(legacy.get().currentKey) :
                    inception.identityHash().equals(identity);
            if (! forThisIdentity)
                return new RecordLog.Change<>(this, Outcome.Rejected);
            return wrap(records.addInception((Verified<IdentityInception>) v));
        }
        if (r instanceof IdentityRotation)
            return wrap(records.addRotation((Verified<IdentityRotation>) v));
        if (r instanceof IdentityMove)
            return wrap(records.addUpdate((Verified<IdentityMove>) v));
        return new RecordLog.Change<>(this, Outcome.Rejected);
    }

    private RecordLog.Change<IdentityLog> wrap(RecordLog.Change<RecordLog<IdentityInception, IdentityRotation, IdentityMove>> c) {
        return new RecordLog.Change<>(c.log == records ? this : new IdentityLog(identity, c.log, legacy), c.outcome);
    }

    @Override
    public Subject subject() {
        return Subject.identity(identity);
    }

    @Override
    public List<Verified<? extends SignedRecord>> held() {
        return records.records();
    }

    @Override
    public Optional<Verified<IdentityInception>> inception() {
        return records.inception;
    }

    @Override
    public IdentityLog withInception(Verified<? extends SignedRecord> v) {
        return new IdentityLog(identity, records.withInception((Verified<IdentityInception>) v), legacy);
    }

    /** Before its first inception, a legacy identity's key is its last legacy owner's. */
    public Optional<PublicSigningKey> currentKey() {
        Optional<PublicSigningKey> logged = records.chain.current();
        if (logged.isPresent())
            return logged;
        return legacy.map(l -> l.currentKey);
    }

    @Override
    public boolean exists() {
        return records.inception.isPresent() || legacy.isPresent();
    }

    public boolean isLegacy() {
        return legacy.isPresent();
    }

    /** The current key's latest move, or failing that the providers its establishment record names. */
    public List<Multihash> providers() {
        if (records.inception.isEmpty())
            return legacy.map(l -> l.providers).orElse(Collections.emptyList());
        if (records.update.isPresent())
            return records.update.get().record.providers;
        return records.chain.currentEstablishment()
                .map(e -> e instanceof IdentityInception ?
                        ((IdentityInception) e).providers :
                        ((IdentityRotation) e).providers)
                .orElse(Collections.emptyList());
    }

    public boolean isFrozen() {
        return records.chain.frozen;
    }

    /** True if the first key signed a second inception, so clients should warn. */
    public boolean hasDuplicityEvidence() {
        return records.evidence.isPresent();
    }
}
