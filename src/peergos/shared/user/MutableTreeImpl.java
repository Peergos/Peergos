package peergos.shared.user;
import java.util.*;
import java.util.logging.*;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.concurrent.*;
import java.util.function.*;

public class MutableTreeImpl implements MutableTree {
	private static final Logger LOG = Logger.getGlobal();
    private final MutablePointers mutable;
    private final ContentAddressedStorage dht;
    private final Hasher writeHasher;
    private static final boolean LOGGING = false;
    private final WriteSynchronizer synchronizer;
    private final Function<ByteArrayWrapper, CompletableFuture<byte[]>> hasher = x -> Futures.of(x.data);

    public MutableTreeImpl(MutablePointers mutable,
                           ContentAddressedStorage dht,
                           Hasher writeHasher,
                           WriteSynchronizer synchronizer) {
        this.mutable = mutable;
        this.dht = dht;
        this.writeHasher = writeHasher;
        this.synchronizer = synchronizer;
    }

    private <T> T log(T result, String toPrint) {
        if (LOGGING)
            LOG.info(toPrint);
        return result;
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
                ChampWrapper.create(owner, (Cid)base.tree.get(), Optional.empty(), hasher, dht, writeHasher, c -> (CborObject.CborMerkleLink)c) :
                ChampWrapper.create(owner, writer, hasher, tid, dht, writeHasher, c -> (CborObject.CborMerkleLink)c)
        ).thenCompose(tree -> tree.put(owner, writer, mapKey, existing.map(CborObject.CborMerkleLink::new), new CborObject.CborMerkleLink(value), Optional.empty(), tid))
                .thenApply(newRoot -> LOGGING ? log(newRoot, "TREE.put (" + ArrayOps.bytesToHex(mapKey)
                        + ", " + value + ") => CAS(" + base.tree + ", " + newRoot + ")") : newRoot)
                .thenApply(base::withChamp);
    }

    @Override
    public CompletableFuture<MaybeMultihash> get(WriterData base, PublicKeyHash owner, PublicKeyHash writer, byte[] mapKey) {
        if (! base.tree.isPresent())
            throw new IllegalStateException("Tree root not present for " + writer);
        return ChampWrapper.create(owner, (Cid)base.tree.get(), Optional.empty(), hasher, dht, writeHasher, c -> (CborObject.CborMerkleLink)c).thenCompose(tree -> tree.get(mapKey))
                .thenApply(c -> c.map(x -> x.target).map(MaybeMultihash::of).orElse(MaybeMultihash.empty()))
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
        return ChampWrapper.create(owner, (Cid)base.tree.get(), Optional.empty(), hasher, dht, writeHasher, c -> (CborObject.CborMerkleLink)c)
                .thenCompose(tree -> tree.remove(owner, writer, mapKey, existing.map(CborObject.CborMerkleLink::new), Optional.empty(), tid))
                .thenApply(pair -> LOGGING ? log(pair, "TREE.rm ("
                        + ArrayOps.bytesToHex(mapKey) + "  => " + pair) : pair)
                .thenApply(newTreeRoot -> base.withChamp(newTreeRoot));
    }
}
