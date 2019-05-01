package peergos.shared.user;
import java.util.logging.*;

import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.concurrent.*;
import java.util.function.*;

public class MutableTreeImpl implements MutableTree {
	private static final Logger LOG = Logger.getGlobal();
    private final MutablePointers mutable;
    private final ContentAddressedStorage dht;
    private static final boolean LOGGING = false;
    private final WriteSynchronizer synchronizer;
    private final Function<ByteArrayWrapper, byte[]> hasher = x -> x.data;

    public MutableTreeImpl(MutablePointers mutable, ContentAddressedStorage dht, WriteSynchronizer synchronizer) {
        this.mutable = mutable;
        this.dht = dht;
        this.synchronizer = synchronizer;
    }

    private <T> T log(T result, String toPrint) {
        if (LOGGING)
            LOG.info(toPrint);
        return result;
    }

    @Override
    public CompletableFuture<CommittedWriterData> put(PublicKeyHash owner,
                                                      SigningPrivateKeyAndPublicHash writer,
                                                      byte[] mapKey,
                                                      MaybeMultihash existing,
                                                      Multihash value) {
        return synchronizer.applyUpdate(owner, writer,
                (wd, tid) -> put(wd, owner, writer, mapKey, existing, value, tid))
                .thenApply(version -> version.get(writer));
    }

    @Override
    public CompletableFuture<WriterData> put(WriterData base,
                                             PublicKeyHash owner,
                                             SigningPrivateKeyAndPublicHash writer,
                                             byte[] mapKey,
                                             MaybeMultihash existing,
                                             Multihash value,
                                             TransactionId tid) {
        return (base.tree.isPresent() ?
                ChampWrapper.create(base.tree.get(), hasher, dht) :
                ChampWrapper.create(owner, writer, x -> x.data, tid, dht)
        ).thenCompose(tree -> tree.put(owner, writer, mapKey, existing, value, tid))
                .thenApply(newRoot -> LOGGING ? log(newRoot, "TREE.put (" + ArrayOps.bytesToHex(mapKey)
                        + ", " + value + ") => CAS(" + base.tree + ", " + newRoot + ")") : newRoot)
                .thenApply(base::withChamp);
    }

    @Override
    public CompletableFuture<MaybeMultihash> get(PublicKeyHash owner, PublicKeyHash writer, byte[] mapKey) {
        return synchronizer.getValue(owner, writer)
                .thenCompose(version -> get(version.get(writer).props, owner, writer, mapKey));
    }

    @Override
    public CompletableFuture<MaybeMultihash> get(WriterData base, PublicKeyHash owner, PublicKeyHash writer, byte[] mapKey) {
        if (! base.tree.isPresent())
            throw new IllegalStateException("Tree root not present for " + writer);
        return ChampWrapper.create(base.tree.get(), hasher, dht).thenCompose(tree -> tree.get(mapKey))
                .thenApply(maybe -> LOGGING ?
                        log(maybe, "TREE.get (" + ArrayOps.bytesToHex(mapKey)
                                + ", root="+base.tree.get()+" => " + maybe) : maybe);
    }

    @Override
    public CompletableFuture<WriterData> remove(WriterData base,
                                                PublicKeyHash owner,
                                                SigningPrivateKeyAndPublicHash writer,
                                                byte[] mapKey,
                                                MaybeMultihash existing,
                                                TransactionId tid) {
        if (! base.tree.isPresent())
            throw new IllegalStateException("Tree root not present!");
        return ChampWrapper.create(base.tree.get(), hasher, dht)
                .thenCompose(tree -> tree.remove(owner, writer, mapKey, existing, tid))
                .thenApply(pair -> LOGGING ? log(pair, "TREE.rm ("
                        + ArrayOps.bytesToHex(mapKey) + "  => " + pair) : pair)
                .thenApply(newTreeRoot -> base.withChamp(newTreeRoot));
    }
}
