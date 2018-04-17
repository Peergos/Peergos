package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class MutableTreeImpl implements MutableTree {
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
            System.out.println(toPrint);
        return result;
    }

    private CompletableFuture<CommittedWriterData> getWriterData(PublicKeyHash controller, MaybeMultihash hash) {
        if (!hash.isPresent())
            return CompletableFuture.completedFuture(new CommittedWriterData(MaybeMultihash.empty(), WriterData.createEmpty(controller)));
        return dht.get(hash.get())
                .thenApply(cborOpt -> {
                    if (! cborOpt.isPresent())
                        throw new IllegalStateException("Couldn't retrieve WriterData from dht! " + hash);
                    return new CommittedWriterData(hash, WriterData.fromCbor(cborOpt.get(), null));
                });
    }

    private CompletableFuture<CommittedWriterData> getWriterData(PublicKeyHash hash) {
        return mutable.getPointer(hash)
                .thenCompose(dataOpt -> dht.getSigningKey(hash)
                        .thenApply(signer -> dataOpt.isPresent() ?
                                HashCasPair.fromCbor(CborObject.fromByteArray(signer.get().unsignMessage(dataOpt.get()))).updated :
                                MaybeMultihash.empty())
                        .thenCompose(x -> getWriterData(hash, x)));
    }

    private CompletableFuture<CommittedWriterData> addToQueue(PublicKeyHash pubKey, CompletableFuture<CommittedWriterData> lock) {
        synchronized (pending) {
            // This is subtle, but we need to ensure that there is only ever 1 thenAble waiting on the future for a given key
            // otherwise when the future completes, then the two or more waiters will both proceed with the existing hash,
            // and whoever commits first will win. We also need to retrieve the writer data again from the network after
            // a previous transaction has completed (another node/user may have updated the mapping)

            if (pending.containsKey(pubKey)) {
                return pending.put(pubKey, lock).thenCompose(x -> getWriterData(pubKey));
            }
            pending.put(pubKey, lock);
            return getWriterData(pubKey);
        }
    }

    @Override
    public CompletableFuture<Boolean> put(SigningPrivateKeyAndPublicHash writer, byte[] mapKey, MaybeMultihash existing, Multihash value) {
        PublicKeyHash publicWriterKey = writer.publicKeyHash;
        CompletableFuture<CommittedWriterData> lock = new CompletableFuture<>();

        return addToQueue(publicWriterKey, lock)
                .thenCompose(committed -> {
                    WriterData holder = committed.props;
                    boolean isChamp = ! holder.btree.isPresent();
                    return (holder.tree.isPresent() ?
                            ChampWrapper.create(writer.publicKeyHash, holder.tree.get(), hasher, dht) :
                            isChamp ?
                                    ChampWrapper.create(writer, x -> x.data, dht) :
                                    MerkleBTree.create(writer.publicKeyHash, holder.btree.get(), dht)
                    ).thenCompose(tree -> tree.put(writer, mapKey, existing, value))
                            .thenApply(newRoot -> LOGGING ? log(newRoot, "TREE.put (" + ArrayOps.bytesToHex(mapKey)
                                    + ", " + value + ") => CAS(" + holder.tree + ", " + newRoot + ")") : newRoot)
                            .thenCompose(newTreeRoot -> (isChamp ? holder.withChamp(newTreeRoot) : holder.withBtree(newTreeRoot))
                                    .commit(writer, committed.hash, mutable, dht, lock::complete))
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
    public CompletableFuture<MaybeMultihash> get(PublicKeyHash writer, byte[] mapKey) {
        CompletableFuture<CommittedWriterData> lock = new CompletableFuture<>();

        return addToQueue(writer, lock)
                .thenCompose(committed -> {
                    lock.complete(committed);
                    WriterData holder = committed.props;
                    if (! holder.tree.isPresent() && ! holder.btree.isPresent())
                        throw new IllegalStateException("Tree root not present for " + writer);
                    boolean isChamp = ! holder.btree.isPresent();
                    return (isChamp ?
                            ChampWrapper.create(writer, holder.tree.get(), hasher, dht) :
                            MerkleBTree.create(writer, holder.btree.get(), dht)
                    ).thenCompose(tree -> tree.get(mapKey))
                            .thenApply(maybe -> LOGGING ?
                                    log(maybe, "TREE.get (" + ArrayOps.bytesToHex(mapKey) + ", root="+holder.tree.get()+" => " + maybe) : maybe);
                });
    }

    @Override
    public CompletableFuture<Boolean> remove(SigningPrivateKeyAndPublicHash writer, byte[] mapKey, MaybeMultihash existing) {
        PublicKeyHash publicWriter = writer.publicKeyHash;
        CompletableFuture<CommittedWriterData> future = new CompletableFuture<>();

        return addToQueue(publicWriter, future)
                .thenCompose(committed -> {
                    WriterData holder = committed.props;
                    if (! holder.tree.isPresent() && ! holder.btree.isPresent())
                        throw new IllegalStateException("Tree root not present!");
                    boolean isChamp = ! holder.btree.isPresent();
                    return (isChamp ?
                            ChampWrapper.create(writer.publicKeyHash, holder.tree.get(), hasher, dht) :
                            MerkleBTree.create(writer.publicKeyHash, holder.btree.get(), dht)
                    ).thenCompose(tree -> tree.remove(writer, mapKey, existing))
                            .thenApply(pair -> LOGGING ? log(pair, "TREE.rm (" + ArrayOps.bytesToHex(mapKey) + "  => " + pair) : pair)
                            .thenCompose(newTreeRoot -> (isChamp ? holder.withChamp(newTreeRoot) : holder.withBtree(newTreeRoot))
                                    .commit(writer, committed.hash, mutable, dht, future::complete))
                            .thenApply(x -> true);
                });
    }
}
