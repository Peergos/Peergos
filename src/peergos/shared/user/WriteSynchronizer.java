package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.mutable.MutablePointers;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;

public class WriteSynchronizer {

    private final MutablePointers mutable;
    private final ContentAddressedStorage dht;
    private final Hasher hasher;
    // The keys are <owner, writer> pairs. The owner is only needed to handle identity changes
    private final Map<Pair<PublicKeyHash, PublicKeyHash>, AsyncLock<Snapshot>> pending = new ConcurrentHashMap<>();
    private CommitterBuilder committerBuilder = (c, o, w) -> c;
    private BufferedNetworkAccess.Flusher flusher = (o, v, w) -> Futures.of(v);

    public WriteSynchronizer(MutablePointers mutable, ContentAddressedStorage dht, Hasher hasher) {
        this.mutable = mutable;
        this.dht = dht;
        this.hasher = hasher;
    }

    public void clear() {
        pending.clear();
    }

    public void setCommitterBuilder(CommitterBuilder committerBuilder) {
        this.committerBuilder = committerBuilder;
    }

    public void setFlusher(BufferedNetworkAccess.Flusher flusher) {
        this.flusher = flusher;
    }

    public void put(PublicKeyHash owner, PublicKeyHash writer, CommittedWriterData val) {
        pending.put(new Pair<>(owner, writer),
                new AsyncLock<>(CompletableFuture.completedFuture(new Snapshot(writer, val))));
    }

    public void putEmpty(PublicKeyHash owner, PublicKeyHash writer) {
        WriterData emptyWD = new WriterData(writer,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Collections.emptyMap(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        CommittedWriterData emptyUserData = new CommittedWriterData(MaybeMultihash.empty(), emptyWD, Optional.empty());
        put(owner, writer, emptyUserData);
    }

    public CompletableFuture<Snapshot> getWriterData(PublicKeyHash owner, PublicKeyHash writer) {
        return mutable.getPointerTarget(owner, writer, dht)
                .thenCompose(x -> x.updated.isPresent() ?
                        WriterData.getWriterData(owner, (Cid)x.updated.get(), x.sequence, dht)
                                .thenApply(cwd -> new Snapshot(writer, cwd)) :
                        Futures.of(new Snapshot(Collections.emptyMap()))
                );
    }

    /**
     *
     * @param owner
     * @param writer
     * @return The current version committed by writer
     */
    public CompletableFuture<Snapshot> getValue(PublicKeyHash owner, PublicKeyHash writer) {
        return pending.computeIfAbsent(new Pair<>(owner, writer), p -> new AsyncLock<>(getWriterData(owner, p.right)))
                .runWithLock(x -> getWriterData(owner, writer), () -> getWriterData(owner, writer));
    }

    public CompletableFuture<Snapshot> applyUpdate(PublicKeyHash owner,
                                                   SigningPrivateKeyAndPublicHash writer,
                                                   Mutation transformer) {
        // This is subtle, but we need to ensure that there is only ever 1 thenAble waiting on the future for a given key
        // otherwise when the future completes, then the two or more waiters will both proceed with the existing hash,
        // and whoever commits first will win. We also need to retrieve the writer data again from the network after
        // a previous transaction has completed (another node/user with write access may have concurrently updated the mapping)
        return pending.computeIfAbsent(new Pair<>(owner, writer.publicKeyHash), p -> new AsyncLock<>(getWriterData(owner, p.right)))
                .runWithLock(current -> IpfsTransaction.call(owner, tid -> transformer.apply(current.get(writer).props.get(), tid)
                                .thenCompose(wd -> committerBuilder.buildCommitter((aOwner, signer, wdr, existing, t) -> wdr.get().commit(aOwner, signer,
                                        existing.hash, existing.sequence, mutable, dht, hasher, t), owner, () -> true)
                                        .commit(owner, writer, wd, current.get(writer), tid)
                                        .thenCompose(v -> flusher.commit(owner, v, () -> true))), dht),
                        () -> getWriterData(owner, writer.publicKeyHash));
    }

    /** Apply an update
     *
     * @param owner
     * @param writer
     * @param transformer
     * @return
     */
    public CompletableFuture<Snapshot> applyComplexUpdate(PublicKeyHash owner,
                                                          SigningPrivateKeyAndPublicHash writer,
                                                          ComplexMutation transformer,
                                                          Supplier<Boolean> commitWatcher) {
        return pending.computeIfAbsent(new Pair<>(owner, writer.publicKeyHash), p -> new AsyncLock<>(getWriterData(owner, p.right)))
                .runWithLock(current -> transformer.apply(current,
                                        committerBuilder.buildCommitter((aOwner, signer, wd, existing, tid) -> (wd.isPresent() ?
                                                wd.get().commit(aOwner, signer, existing.hash, existing.sequence, mutable, dht, hasher, tid) :
                                                WriterData.commitDeletion(aOwner, signer, existing.hash, existing.sequence, mutable))
                                                .thenCompose(s -> updateWriterState(owner, signer.publicKeyHash, s).thenApply(x -> s)), owner, commitWatcher))
                                .thenCompose(v -> flusher.commit(owner, v, commitWatcher)),
                        () -> getWriterData(owner, writer.publicKeyHash));
    }

    public CompletableFuture<Snapshot> applyComplexUpdate(PublicKeyHash owner,
                                                          SigningPrivateKeyAndPublicHash writer,
                                                          ComplexMutation transformer) {
        return applyComplexUpdate(owner, writer, transformer, () -> true);
    }

    public CompletableFuture<Boolean> updateWriterState(PublicKeyHash owner,
                                                        PublicKeyHash writer,
                                                        Snapshot value) {
        AsyncLock<Snapshot> existing = pending.get(new Pair<>(owner, writer));
        if (existing != null && ! existing.isDone()) // don't modify the lock we are in
            return CompletableFuture.completedFuture(true);
        // need to update local queue for other writer
        return pending.computeIfAbsent(
                        new Pair<>(owner, writer),
                        p -> new AsyncLock<>(getWriterData(owner, p.right))
                ).runWithLock(v -> CompletableFuture.completedFuture(v.withVersion(writer, value.get(writer))))
                .thenApply(x -> true);
    }

    /** Apply an update and return a computed value
     *
     * @param owner
     * @param writer
     * @param transformer
     * @param <V>
     * @return
     */
    public <V> CompletableFuture<Pair<Snapshot, V>> applyComplexComputation(PublicKeyHash owner,
                                                                            SigningPrivateKeyAndPublicHash writer,
                                                                            ComplexComputation<V> transformer) {
        CompletableFuture<Pair<Snapshot, V>> res = new CompletableFuture<>();
        return pending.computeIfAbsent(new Pair<>(owner, writer.publicKeyHash), p -> new AsyncLock<>(getWriterData(owner, p.right)))
                .runWithLock(current -> transformer.apply(current,
                                committerBuilder.buildCommitter((aOwner, signer, wd, existing, tid) ->
                                                (wd.isPresent() ?
                                                        wd.get().commit(aOwner, signer, existing.hash, existing.sequence, mutable, dht, hasher, tid) :
                                                        WriterData.commitDeletion(aOwner, signer, existing.hash, existing.sequence, mutable))
                                                        .thenCompose(s -> updateWriterState(owner, signer.publicKeyHash, s)
                                                                .thenApply(x -> s)),
                                        owner, () -> true))
                                .thenCompose(p -> flusher.commit(owner, p.left, () -> true).thenApply(x -> p))
                                .thenApply(p -> {
                                    res.complete(p);
                                    return p.left;
                                }),
                        () -> getWriterData(owner, writer.publicKeyHash))
                .thenCompose(x -> res);
    }
}
