package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class TransactionalIpfs extends DelegatingStorage implements DeletableContentAddressedStorage {

    private final TransactionStore transactions;
    private final DeletableContentAddressedStorage target;
    private final Cid id;
    private final Hasher hasher;

    public TransactionalIpfs(DeletableContentAddressedStorage target,
                             TransactionStore transactions,
                             Cid id,
                             Hasher hasher) {
        super(target);
        this.target = target;
        this.transactions = transactions;
        this.id = id;
        this.hasher = hasher;
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        return Futures.of(transactions.startTransaction(owner));
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        transactions.closeTransaction(owner, tid);
        return Futures.of(true);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid object, Optional<BatWithId> bat) {
        return get(object, bat, id, hasher);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid hash, String auth) {
        return target.get(hash, auth);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, Optional<BatWithId> bat) {
        return getRaw(hash, bat, id, hasher);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, String auth) {
        return target.getRaw(hash, auth);
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Multihash root, byte[] champKey) {
        return getChampLookup(root, champKey, hasher);
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                            PublicKeyHash writer,
                                            List<byte[]> signedHashes,
                                            List<byte[]> blocks,
                                            TransactionId tid) {
        for (byte[] signedHash : signedHashes) {
            Multihash hash = new Multihash(Multihash.Type.sha2_256, Arrays.copyOfRange(signedHash, signedHash.length - 32, signedHash.length));
            Cid cid = new Cid(1, Cid.Codec.DagCbor, hash.type, hash.getHash());
            transactions.addBlock(cid, tid, owner);
        }
        return target.put(owner, writer, signedHashes, blocks, tid);
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signedHashes,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressConsumer) {
        for (byte[] signedHash : signedHashes) {
            Multihash hash = new Multihash(Multihash.Type.sha2_256, Arrays.copyOfRange(signedHash, signedHash.length - 32, signedHash.length));
            Cid cid = new Cid(1, Cid.Codec.Raw, hash.type, hash.getHash());
            transactions.addBlock(cid, tid, owner);
        }
        return target.putRaw(owner, writer, signedHashes, blocks, tid, progressConsumer);
    }

    @Override
    public Stream<Cid> getAllBlockHashes() {
        return target.getAllBlockHashes();
    }

    @Override
    public void delete(Multihash hash) {
        target.delete(hash);
    }

    @Override
    public List<Multihash> getOpenTransactionBlocks() {
        return transactions.getOpenTransactionBlocks();
    }
}
