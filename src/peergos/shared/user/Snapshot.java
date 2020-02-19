package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** This class represents a snapshot of a group of signing subspaces.
 *
 */
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
            if (merge.containsKey(entry.getKey()) && ! merge.get(entry.getKey()).equals(other.versions.get(entry.getKey())))
                throw new IllegalStateException("Conflicting merge of Snapshots!");
            merge.put(entry.getKey(), entry.getValue());
        }
        return new Snapshot(merge);
    }

    public Snapshot mergeAndOverwriteWith(Snapshot other) {
        HashMap<PublicKeyHash, CommittedWriterData> merge = new HashMap<>(versions);
        for (Map.Entry<PublicKeyHash, CommittedWriterData> entry : other.versions.entrySet()) {
            merge.put(entry.getKey(), entry.getValue());
        }
        return new Snapshot(merge);
    }

    public boolean contains(PublicKeyHash writer) {
        return versions.containsKey(writer);
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

    public Snapshot withVersion(PublicKeyHash writer, CommittedWriterData version) {
        HashMap<PublicKeyHash, CommittedWriterData> result = new HashMap<>(versions);
        result.put(writer, version);
        return new Snapshot(result);
    }

    public CompletableFuture<Snapshot> withWriter(PublicKeyHash owner, PublicKeyHash writer, NetworkAccess network) {
        if (versions.containsKey(writer))
            return CompletableFuture.completedFuture(this);
        return network.synchronizer.getValue(owner, writer).thenApply(s -> s.merge(this));
    }

    public CompletableFuture<Snapshot> withWriters(PublicKeyHash owner, Set<PublicKeyHash> writers, NetworkAccess network) {
        return Futures.reduceAll(writers, this,
                (s, writer) -> s.withWriter(owner, writer, network), (a, b) -> b);
    }

    @Override
    public String toString() {
        return versions.toString();
    }
}
