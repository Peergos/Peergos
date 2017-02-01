package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.corenode.CoreNode;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.MaybeMultihash;
import peergos.shared.merklebtree.MerkleBTree;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class BtreeImpl implements Btree {
    private final CoreNode coreNode;
    private final ContentAddressedStorage dht;
    private static final boolean LOGGING = true;
    private final Map<PublicSigningKey, CompletableFuture<CommittedWriterData>> pending = new HashMap<>();

    public BtreeImpl(CoreNode coreNode, ContentAddressedStorage dht) {
        this.coreNode = coreNode;
        this.dht = dht;
    }

    private <T> T log(T result, String toPrint) {
        if (LOGGING)
            System.out.println(toPrint);
        return result;
    }

    private CompletableFuture<CommittedWriterData> getWriterData(MaybeMultihash hash) {
        if (!hash.isPresent())
            return CompletableFuture.completedFuture(new CommittedWriterData(MaybeMultihash.EMPTY(), WriterData.createEmpty()));
        return dht.get(hash.get())
                .thenApply(cborOpt -> {
                    if (! cborOpt.isPresent())
                        throw new IllegalStateException("Couldn't retrieve WriterData from dht! " + hash);
                    return new CommittedWriterData(hash, WriterData.fromCbor(cborOpt.get(), null));
                });
    }

    private CompletableFuture<CommittedWriterData> getWriterData(PublicSigningKey pubKey) {
        return coreNode.getMetadataBlob(pubKey)
                .thenCompose(this::getWriterData);
    }

    private CompletableFuture<CommittedWriterData> addToQueue(PublicSigningKey pubKey, CompletableFuture<CommittedWriterData> replacement) {
        synchronized (pending) {
            // This is subtle, but we need to ensure that there is only ever 1 thenAble waiting on the future for a given key
            // otherwise when the future completes, then the two or more waiters will both proceed with the existing hash,
            // and whoever commits first will win

            if (pending.containsKey(pubKey)) {
                return pending.put(pubKey, replacement);
            }
            pending.put(pubKey, replacement);
            return getWriterData(pubKey);
        }
    }

    @Override
    public CompletableFuture<Boolean> put(SigningKeyPair writer, byte[] mapKey, Multihash value) {
        PublicSigningKey publicWriterKey = writer.publicSigningKey;
        CompletableFuture<CommittedWriterData> lock = new CompletableFuture<>();

        return addToQueue(publicWriterKey, lock)
                .thenCompose(committed -> {
                    WriterData holder = committed.props;
                    MaybeMultihash btreeRootHash = holder.btree.isPresent() ? MaybeMultihash.of(holder.btree.get()) : MaybeMultihash.EMPTY();
                    return MerkleBTree.create(publicWriterKey, btreeRootHash, dht)
                            .thenCompose(btree -> btree.put(publicWriterKey, mapKey, value))
                            .thenApply(newRoot -> log(newRoot, "BTREE.put (" + ArrayOps.bytesToHex(mapKey)
                                    + ", " + value + ") => CAS(" + btreeRootHash + ", " + newRoot + ")"))
                            .thenCompose(newBtreeRoot -> holder.withBtree(newBtreeRoot)
                                    .commit(writer, committed.hash, coreNode, dht, lock::complete))
                            .thenApply(x -> true);
                });
    }

    @Override
    public CompletableFuture<MaybeMultihash> get(PublicSigningKey writer, byte[] mapKey) {
        CompletableFuture<CommittedWriterData> future = new CompletableFuture<>();

        return addToQueue(writer, future)
                .thenCompose(committed -> {
                    future.complete(committed);
                    WriterData holder = committed.props;
                    MaybeMultihash btreeRootHash = holder.btree.isPresent() ? MaybeMultihash.of(holder.btree.get()) : MaybeMultihash.EMPTY();
                    return MerkleBTree.create(writer, btreeRootHash, dht)
                            .thenCompose(btree -> btree.get(mapKey))
                            .thenApply(maybe -> log(maybe, "BTREE.get (" + ArrayOps.bytesToHex(mapKey) + ", root="+btreeRootHash+" => " + maybe));
                });
    }

    @Override
    public CompletableFuture<Boolean> remove(SigningKeyPair writer, byte[] mapKey) {
        PublicSigningKey publicWriter = writer.publicSigningKey;
        CompletableFuture<CommittedWriterData> future = new CompletableFuture<>();

        return addToQueue(publicWriter, future)
                .thenCompose(committed -> {
                    WriterData holder = committed.props;
                    MaybeMultihash btreeRootHash = holder.btree.isPresent() ? MaybeMultihash.of(holder.btree.get()) : MaybeMultihash.EMPTY();
                    return MerkleBTree.create(publicWriter, btreeRootHash, dht)
                            .thenCompose(btree -> btree.delete(publicWriter, mapKey))
                            .thenApply(pair -> log(pair, "BTREE.rm (" + ArrayOps.bytesToHex(mapKey) + "  => " + pair))
                            .thenCompose(newBtreeRoot -> holder.withBtree(newBtreeRoot)
                                    .commit(writer, committed.hash, coreNode, dht, future::complete))
                            .thenApply(x -> true);
                });
    }
}
