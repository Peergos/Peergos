package peergos.shared.user;
import java.util.logging.*;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class MutableTreeImpl implements MutableTree {
	private static final Logger LOG = Logger.getGlobal();
    private final MutablePointers mutable;
    private final ContentAddressedStorage dht;
    private static final boolean LOGGING = false;
    private final Function<ByteArrayWrapper, byte[]> hasher = x -> x.data;
    private final Map<PublicKeyHash, CompletableFuture<CommittedWriterData>> pending = new HashMap<>();

    public MutableTreeImpl(MutablePointers mutable, ContentAddressedStorage dht) {
        this.mutable = mutable;
        this.dht = dht;
    }

    private <T> T log(T result, String toPrint) {
        if (LOGGING)
            LOG.info(toPrint);
        return result;
    }

    private CompletableFuture<CommittedWriterData> getWriterData(PublicKeyHash controller, MaybeMultihash hash) {
        if (!hash.isPresent())
            return CompletableFuture.completedFuture(new CommittedWriterData(MaybeMultihash.empty(), WriterData.createEmpty(controller)));
        return dht.get(hash.get())
                .thenApply(cborOpt -> {
                    if (! cborOpt.isPresent())
                        throw new IllegalStateException("Couldn't retrieve WriterData from dht! " + hash);
                    return new CommittedWriterData(hash, WriterData.fromCbor(cborOpt.get()));
                });
    }

    private CompletableFuture<CommittedWriterData> getWriterData(PublicKeyHash owner, PublicKeyHash hash) {
        return mutable.getPointer(owner, hash)
                .thenCompose(dataOpt -> dht.getSigningKey(hash)
                        .thenApply(signer -> dataOpt.isPresent() ?
                                HashCasPair.fromCbor(CborObject.fromByteArray(signer.get().unsignMessage(dataOpt.get()))).updated :
                                MaybeMultihash.empty())
                        .thenCompose(x -> getWriterData(hash, x)));
    }

    private CompletableFuture<CommittedWriterData> addToQueue(PublicKeyHash owner, PublicKeyHash writer, CompletableFuture<CommittedWriterData> lock) {
        synchronized (pending) {
            // This is subtle, but we need to ensure that there is only ever 1 thenAble waiting on the future for a given key
            // otherwise when the future completes, then the two or more waiters will both proceed with the existing hash,
            // and whoever commits first will win. We also need to retrieve the writer data again from the network after
            // a previous transaction has completed (another node/user may have updated the mapping)

            if (pending.containsKey(writer)) {
                return pending.put(writer, lock).thenCompose(x -> getWriterData(owner, writer));
            }
            pending.put(writer, lock);
            return getWriterData(owner, writer);
        }
    }

    @Override
    public CompletableFuture<Boolean> put(PublicKeyHash owner,
                                          SigningPrivateKeyAndPublicHash writer,
                                          byte[] mapKey,
                                          MaybeMultihash existing,
                                          Multihash value,
                                          TransactionId tid) {
        PublicKeyHash publicWriterKey = writer.publicKeyHash;
        CompletableFuture<CommittedWriterData> lock = new CompletableFuture<>();

        return addToQueue(owner, publicWriterKey, lock)
                .thenCompose(committed -> {
                    WriterData holder = committed.props;
                    return (holder.tree.isPresent() ?
                            ChampWrapper.create(holder.tree.get(), hasher, dht) :
                            ChampWrapper.create(owner, writer, x -> x.data, tid, dht)
                    ).thenCompose(tree -> tree.put(owner, writer, mapKey, existing, value, tid))
                            .thenApply(newRoot -> LOGGING ? log(newRoot, "TREE.put (" + ArrayOps.bytesToHex(mapKey)
                                    + ", " + value + ") => CAS(" + holder.tree + ", " + newRoot + ")") : newRoot)
                            .thenCompose(newTreeRoot -> holder.withChamp(newTreeRoot)
                                    .commit(owner, writer, committed.hash, mutable, dht, lock::complete, tid))
                            .thenApply(x -> true)
                            .exceptionally(e -> {
                                lock.complete(committed);
                                if (e instanceof RuntimeException)
                                    throw (RuntimeException) e;
                                throw new RuntimeException(e);
                            });
                });
    }

    @Override
    public CompletableFuture<MaybeMultihash> get(PublicKeyHash owner, PublicKeyHash writer, byte[] mapKey) {
        CompletableFuture<CommittedWriterData> lock = new CompletableFuture<>();

        return addToQueue(owner, writer, lock)
                .thenCompose(committed -> {
                    lock.complete(committed);
                    WriterData holder = committed.props;
                    if (! holder.tree.isPresent())
                        throw new IllegalStateException("Tree root not present for " + writer);
                    return ChampWrapper.create(holder.tree.get(), hasher, dht).thenCompose(tree -> tree.get(mapKey))
                            .thenApply(maybe -> LOGGING ?
                                    log(maybe, "TREE.get (" + ArrayOps.bytesToHex(mapKey) + ", root="+holder.tree.get()+" => " + maybe) : maybe);
                });
    }

    @Override
    public CompletableFuture<Boolean> remove(PublicKeyHash owner,
                                             SigningPrivateKeyAndPublicHash writer,
                                             byte[] mapKey,
                                             MaybeMultihash existing,
                                             TransactionId tid) {
        PublicKeyHash publicWriter = writer.publicKeyHash;
        CompletableFuture<CommittedWriterData> future = new CompletableFuture<>();

        return addToQueue(owner, publicWriter, future)
                .thenCompose(committed -> {
                    WriterData holder = committed.props;
                    if (! holder.tree.isPresent())
                        throw new IllegalStateException("Tree root not present!");
                    return ChampWrapper.create(holder.tree.get(), hasher, dht)
                            .thenCompose(tree -> tree.remove(owner, writer, mapKey, existing, tid))
                            .thenApply(pair -> LOGGING ? log(pair, "TREE.rm (" + ArrayOps.bytesToHex(mapKey) + "  => " + pair) : pair)
                            .thenCompose(newTreeRoot -> holder.withChamp(newTreeRoot)
                                    .commit(owner, writer, committed.hash, mutable, dht, future::complete, tid))
                            .thenApply(x -> true);
                });
    }
}
