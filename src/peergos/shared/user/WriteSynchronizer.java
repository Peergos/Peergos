package peergos.shared.user;

import peergos.shared.MaybeMultihash;
import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.mutable.HashCasPair;
import peergos.shared.mutable.MutablePointers;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class WriteSynchronizer {
    private final MutablePointers mutable;
    private final ContentAddressedStorage dht;
    // The keys are <owner, writer> pairs. The owner is only needed to handle identity changes
    private final Map<Pair<PublicKeyHash, PublicKeyHash>, AsyncLock<CommittedWriterData>> pending = new ConcurrentHashMap<>();

    public WriteSynchronizer(MutablePointers mutable, ContentAddressedStorage dht) {
        this.mutable = mutable;
        this.dht = dht;
    }

    public void put(PublicKeyHash owner, PublicKeyHash writer, CommittedWriterData val) {
        pending.put(new Pair<>(owner, writer), new AsyncLock<>(CompletableFuture.completedFuture(val)));
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

    public CompletableFuture<CommittedWriterData> getWriterData(PublicKeyHash owner, PublicKeyHash writer) {
        return mutable.getPointer(owner, writer)
                .thenCompose(dataOpt -> dht.getSigningKey(writer)
                        .thenApply(signer -> dataOpt.isPresent() ?
                                HashCasPair.fromCbor(CborObject.fromByteArray(signer.get().unsignMessage(dataOpt.get()))).updated :
                                MaybeMultihash.empty())
                        .thenCompose(x -> WriterData.getWriterData(x.get(), dht)));
    }

    /**
     *
     * @param owner
     * @param writer
     * @return The current WriterData committed by writer
     */
    public CompletableFuture<CommittedWriterData> getValue(PublicKeyHash owner,
                                                           PublicKeyHash writer) {
        return pending.computeIfAbsent(new Pair<>(owner, writer), p -> new AsyncLock<>(getWriterData(owner, p.right)))
                .runWithLock(x -> getWriterData(owner, writer), () -> getWriterData(owner, writer));
    }

    public CompletableFuture<CommittedWriterData> applyUpdate(PublicKeyHash owner,
                                                              SigningPrivateKeyAndPublicHash writer,
                                                              Mutation transformer) {
        // This is subtle, but we need to ensure that there is only ever 1 thenAble waiting on the future for a given key
        // otherwise when the future completes, then the two or more waiters will both proceed with the existing hash,
        // and whoever commits first will win. We also need to retrieve the writer data again from the network after
        // a previous transaction has completed (another node/user with write access may have concurrently updated the mapping)
        return pending.computeIfAbsent(new Pair<>(owner, writer.publicKeyHash), p -> new AsyncLock<>(getWriterData(owner, p.right)))
                .runWithLock(current -> IpfsTransaction.call(owner, tid -> transformer.apply(current.props, tid)
                                .thenCompose(wd -> wd.commit(owner, writer, current.hash, mutable, dht, tid)), dht),
                        () -> getWriterData(owner, writer.publicKeyHash));
    }

    public CompletableFuture<CommittedWriterData> applyComplexUpdate(PublicKeyHash owner,
                                                                     SigningPrivateKeyAndPublicHash writer,
                                                                     ComplexMutation transformer) {
        return pending.computeIfAbsent(new Pair<>(owner, writer.publicKeyHash), p -> new AsyncLock<>(getWriterData(owner, p.right)))
                .runWithLock(current -> transformer.apply(current,
                        (signer, wd, existing, tid) -> wd.commit(owner, signer, existing, mutable, dht, tid)),
                        () -> getWriterData(owner, writer.publicKeyHash));
    }

    public interface Committer {
        CompletableFuture<CommittedWriterData> commit(SigningPrivateKeyAndPublicHash signer, WriterData wd, MaybeMultihash existing, TransactionId tid);
    }
}
