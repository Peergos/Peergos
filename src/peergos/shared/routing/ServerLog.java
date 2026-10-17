package peergos.shared.routing;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.*;

import java.util.*;

/** A server's records and current state: its current key and addresses (remove-pki §4.2). */
public final class ServerLog {
    public final Multihash peerId;
    public final RecordLog<ServerInception, ServerRotation, ServerAddresses> records;

    private ServerLog(Multihash peerId, RecordLog<ServerInception, ServerRotation, ServerAddresses> records) {
        this.peerId = peerId;
        this.records = records;
    }

    public static ServerLog empty(Multihash peerId) {
        return new ServerLog(peerId, RecordLog.empty());
    }

    public RecordLog.Change<ServerLog> add(Verified<? extends SignedRecord> v) {
        SignedRecord r = v.record;
        if (r instanceof ServerInception) {
            if (! ((ServerInception) r).peerId().equals(peerId))
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
        return new RecordLog.Change<>(c.log == records ? this : new ServerLog(peerId, c.log), c.outcome);
    }

    public Optional<PublicSigningKey> currentKey() {
        return records.chain.current();
    }

    public Optional<ServerAddresses> addresses() {
        return records.update.map(v -> v.record);
    }

    public boolean isFrozen() {
        return records.chain.frozen;
    }

    public boolean hasDuplicityEvidence() {
        return records.evidence.isPresent();
    }
}
