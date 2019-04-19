package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;

import java.util.*;
import java.util.concurrent.*;

public class Snapshot {

    public final Map<PublicKeyHash, CommittedWriterData> versions;

    public Snapshot(Map<PublicKeyHash, CommittedWriterData> versions) {
        this.versions = Collections.unmodifiableMap(versions);
    }

    public Snapshot(PublicKeyHash writer, CommittedWriterData base) {
        HashMap<PublicKeyHash, CommittedWriterData> state = new HashMap<>();
        state.put(writer, base);
        this.versions = Collections.unmodifiableMap(state);
    }

    public Snapshot merge(Snapshot other) {
        HashMap<PublicKeyHash, CommittedWriterData> merge = new HashMap<>(versions);
        for (Map.Entry<PublicKeyHash, CommittedWriterData> entry : other.versions.entrySet()) {
            if (merge.containsKey(entry.getKey()))
                throw new IllegalStateException("Conflicting merge of Snapshots!");
            merge.put(entry.getKey(), entry.getValue());
        }
        return new Snapshot(merge);
    }

    public CommittedWriterData get(PublicKeyHash writer) {
        if (! versions.containsKey(writer))
            throw new IllegalStateException("writer not present in snapshot!");
        return versions.get(writer);
    }

    public CommittedWriterData get(SigningPrivateKeyAndPublicHash writer) {
        if (! versions.containsKey(writer.publicKeyHash))
            throw new IllegalStateException("writer not present in snapshot!");
        return versions.get(writer.publicKeyHash);
    }

    public CompletableFuture<Snapshot> withWriter(PublicKeyHash owner, PublicKeyHash writer, NetworkAccess network) {
        if (versions.containsKey(writer))
            return CompletableFuture.completedFuture(this);
        return network.synchronizer.getValue(owner, writer).thenApply(s -> s.merge(this));
    }

    @Override
    public String toString() {
        return versions.toString();
    }
}
