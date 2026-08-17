package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** This class represents a snapshot of a group of signing subspaces.
 *
 */
public class Snapshot implements Cborable {

    public final Map<PublicKeyHash, CommittedWriterData> versions;
    /** True if this was retrieved without waiting for in-flight writes, in which case any further
     *  writer retrievals from it are also read only.
     */
    public final boolean readOnly;

    public Snapshot(Map<PublicKeyHash, CommittedWriterData> versions) {
        this(versions, false);
    }

    public Snapshot(Map<PublicKeyHash, CommittedWriterData> versions, boolean readOnly) {
        this.versions = Collections.unmodifiableMap(versions);
        this.readOnly = readOnly;
    }

    public Snapshot(PublicKeyHash writer, CommittedWriterData base) {
        HashMap<PublicKeyHash, CommittedWriterData> state = new HashMap<>();
        state.put(writer, base);
        this.versions = Collections.unmodifiableMap(state);
        this.readOnly = false;
    }

    public Snapshot asReadOnly() {
        return readOnly ? this : new Snapshot(versions, true);
    }

    /** The value stored in a write queue must never route subsequent retrievals to the read only path,
     *  even if a mutation returned a version it retrieved read only.
     */
    public Snapshot asWritable() {
        return readOnly ? new Snapshot(versions, false) : this;
    }

    public Snapshot merge(Snapshot other) {
        HashMap<PublicKeyHash, CommittedWriterData> merge = new HashMap<>(versions);
        for (Map.Entry<PublicKeyHash, CommittedWriterData> entry : other.versions.entrySet()) {
            if (merge.containsKey(entry.getKey()) && ! merge.get(entry.getKey()).equals(other.versions.get(entry.getKey())))
                throw new IllegalStateException("Conflicting merge of Snapshots!");
            merge.put(entry.getKey(), entry.getValue());
        }
        return new Snapshot(merge, readOnly && other.readOnly);
    }

    public Snapshot mergeAndOverwriteWith(Snapshot other) {
        HashMap<PublicKeyHash, CommittedWriterData> merge = new HashMap<>(versions);
        for (Map.Entry<PublicKeyHash, CommittedWriterData> entry : other.versions.entrySet()) {
            merge.put(entry.getKey(), entry.getValue());
        }
        return new Snapshot(merge, readOnly && other.readOnly);
    }

    public Snapshot retainOnly(PublicKeyHash writer) {
        HashMap<PublicKeyHash, CommittedWriterData> retained = new HashMap<>();
        retained.put(writer, versions.get(writer));
        return new Snapshot(retained, readOnly);
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

    public Snapshot remove(PublicKeyHash w) {
        HashMap<PublicKeyHash, CommittedWriterData> removed = new HashMap<>(versions);
        removed.remove(w);
        return new Snapshot(removed, readOnly);
    }

    public Snapshot withVersion(PublicKeyHash writer, CommittedWriterData version) {
        HashMap<PublicKeyHash, CommittedWriterData> result = new HashMap<>(versions);
        result.put(writer, version);
        return new Snapshot(result, readOnly);
    }

    public CompletableFuture<Snapshot> withWriter(PublicKeyHash owner, PublicKeyHash writer, NetworkAccess network) {
        if (versions.containsKey(writer))
            return CompletableFuture.completedFuture(this);
        return (readOnly ?
                network.synchronizer.readOnlyValue(owner, writer) :
                network.synchronizer.getValue(owner, writer))
                .thenApply(s -> s.merge(this));
    }

    public CompletableFuture<Snapshot> withWriters(PublicKeyHash owner, Set<PublicKeyHash> writers, NetworkAccess network) {
        return Futures.reduceAll(writers, this,
                (s, writer) -> s.withWriter(owner, writer, network), (a, b) -> b);
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(versions.entrySet()
                .stream()
                .sorted((a, b) -> a.getKey().target.compareTo(b.getKey()))
                .flatMap(e -> Stream.of(e.getKey().toCbor(), e.getValue().toCbor()))
                .collect(Collectors.toList()));
    }

    public static Snapshot fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for Snapshot!");
        CborObject.CborList list = (CborObject.CborList) cbor;
        if (list.value.size() % 2 != 0)
            throw new IllegalStateException("Invalid cbor list length for Snapshot!");
        HashMap<PublicKeyHash, CommittedWriterData> res = new HashMap<>();
        for (int i=0; i < list.value.size()/2; i++)
            res.put(list.get(2*i, PublicKeyHash::fromCbor), list.get(2*i + 1, CommittedWriterData::fromCbor));
        return new Snapshot(res);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Snapshot snapshot = (Snapshot) o;
        return Objects.equals(versions, snapshot.versions);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(versions);
    }

    @Override
    public String toString() {
        return versions.toString();
    }
}
