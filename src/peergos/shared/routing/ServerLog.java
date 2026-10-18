package peergos.shared.routing;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.*;

import java.util.*;

/** A server's records and current state: its current key and addresses (remove-pki §4.2). */
public final class ServerLog implements SubjectLog {
    public final Multihash peerId;
    public final RecordLog<ServerInception, ServerRotation, ServerAddresses> records;
    public final Optional<LegacyServer> legacy;

    private ServerLog(Multihash peerId,
                      RecordLog<ServerInception, ServerRotation, ServerAddresses> records,
                      Optional<LegacyServer> legacy) {
        this.peerId = peerId;
        this.records = records;
        this.legacy = legacy;
    }

    public static ServerLog empty(Multihash peerId) {
        return new ServerLog(peerId, RecordLog.empty(), Optional.empty());
    }

    public static ServerLog legacy(LegacyServer legacy) {
        return new ServerLog(legacy.first(), RecordLog.empty(), Optional.of(legacy));
    }

    @Override
    public RecordLog.Change<ServerLog> add(Verified<? extends SignedRecord> v) {
        SignedRecord r = v.record;
        if (r instanceof ServerInception) {
            Multihash named = legacy.map(LegacyServer::current).orElse(peerId);
            if (! ((ServerInception) r).peerId().equals(named.bareMultihash()))
                return new RecordLog.Change<>(this, Outcome.Rejected);
            return wrap(records.addInception((Verified<ServerInception>) v));
        }
        if (r instanceof ServerRotation)
            return wrap(records.addRotation((Verified<ServerRotation>) v));
        if (r instanceof ServerAddresses)
            return wrap(records.addUpdate((Verified<ServerAddresses>) v));
        return new RecordLog.Change<>(this, Outcome.Rejected);
    }

    private RecordLog.Change<ServerLog> wrap(RecordLog.Change<RecordLog<ServerInception, ServerRotation, ServerAddresses>> c) {
        return new RecordLog.Change<>(c.log == records ? this : new ServerLog(peerId, c.log, legacy), c.outcome);
    }

    @Override
    public Subject subject() {
        return Subject.server(peerId);
    }

    @Override
    public List<Verified<? extends SignedRecord>> held() {
        return records.records();
    }

    @Override
    public Optional<Verified<ServerInception>> inception() {
        return records.inception;
    }

    @Override
    public ServerLog withInception(Verified<? extends SignedRecord> v) {
        return new ServerLog(peerId, records.withInception((Verified<ServerInception>) v), legacy);
    }

    public Optional<PublicSigningKey> currentKey() {
        return records.chain.current();
    }

    public Optional<ServerAddresses> addresses() {
        return records.update.map(v -> v.record);
    }

    @Override
    public boolean exists() {
        return records.inception.isPresent() || legacy.isPresent();
    }

    public boolean isFrozen() {
        return records.chain.frozen;
    }

    public boolean hasDuplicityEvidence() {
        return records.evidence.isPresent();
    }
}
