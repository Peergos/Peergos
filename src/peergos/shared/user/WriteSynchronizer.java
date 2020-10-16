package peergos.shared.user;

import peergos.shared.MaybeMultihash;
import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.HashCasPair;
import peergos.shared.mutable.MutablePointers;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class WriteSynchronizer {

    private final MutablePointers mutable;
    private final ContentAddressedStorage dht;
    private final Hasher hasher;
    // The keys are <owner, writer> pairs. The owner is only needed to handle identity changes
    private final Map<Pair<PublicKeyHash, PublicKeyHash>, AsyncLock<Snapshot>> pending = new ConcurrentHashMap<>();

    public WriteSynchronizer(MutablePointers mutable, ContentAddressedStorage dht, Hasher hasher) {
        this.mutable = mutable;
        this.dht = dht;
        this.hasher = hasher;
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
                Optional.empty());
        CommittedWriterData emptyUserData = new CommittedWriterData(MaybeMultihash.empty(), emptyWD);
        put(owner, writer, emptyUserData);
    }

    public CompletableFuture<Snapshot> getWriterData(PublicKeyHash owner, PublicKeyHash writer) {
        return mutable.getPointer(owner, writer)
                .thenCompose(dataOpt -> dht.getSigningKey(writer)
                        .thenApply(signer -> dataOpt.isPresent() ?
                                HashCasPair.fromCbor(CborObject.fromByteArray(signer.get().unsignMessage(dataOpt.get()))).updated :
                                MaybeMultihash.empty())
                        .thenCompose(x -> WriterData.getWriterData(x.get(), dht))
                        .thenApply(cwd -> new Snapshot(writer, cwd))
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
                .runWithLock(current -> IpfsTransaction.call(owner, tid -> transformer.apply(current.get(writer).props, tid)
                                .thenCompose(wd -> wd.commit(owner, writer, current.get(writer).hash, mutable, dht, hasher, tid)), dht),
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
                                                          ComplexMutation transformer) {
        return pending.computeIfAbsent(new Pair<>(owner, writer.publicKeyHash), p -> new AsyncLock<>(getWriterData(owner, p.right)))
                .runWithLock(current -> transformer.apply(current,
                        (aOwner, signer, wd, existing, tid) -> wd.commit(aOwner, signer, existing.hash, mutable, dht, hasher, tid)
                        .thenCompose(s -> {
                            if (signer.publicKeyHash.equals(writer.publicKeyHash))
                                return CompletableFuture.completedFuture(s);
                            // need to update local queue for other writer
                            return pending.computeIfAbsent(
                                    new Pair<>(owner, signer.publicKeyHash),
                                    p -> new AsyncLock<>(getWriterData(owner, p.right))
                            ).runWithLock(v -> CompletableFuture.completedFuture(v.withVersion(signer.publicKeyHash, s.get(signer))))
                                    .thenApply(x -> s);
                        })),
                        () -> getWriterData(owner, writer.publicKeyHash));
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
                        (aOwner, signer, wd, existing, tid) -> wd.commit(aOwner, signer, existing.hash, mutable, dht, hasher, tid)
                        .thenCompose(s -> {
                            if (signer.publicKeyHash.equals(writer.publicKeyHash))
                                return CompletableFuture.completedFuture(s);
                            // need to update local queue for other writer
                            return pending.computeIfAbsent(
                                    new Pair<>(owner, signer.publicKeyHash),
                                    p -> new AsyncLock<>(getWriterData(owner, p.right))
                            ).runWithLock(v -> CompletableFuture.completedFuture(v.withVersion(signer.publicKeyHash, s.get(signer))))
                                    .thenApply(x -> s);
                        })).thenApply(p -> {
                            res.complete(p);
                            return p.left;
                        }),
                        () -> getWriterData(owner, writer.publicKeyHash))
                .thenCompose(x -> res);
    }
}
