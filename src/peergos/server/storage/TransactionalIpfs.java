package peergos.server.storage;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class TransactionalIpfs extends DelegatingStorage implements DeletableContentAddressedStorage {

    private final TransactionStore transactions;
    private final DeletableContentAddressedStorage target;

    public TransactionalIpfs(DeletableContentAddressedStorage target, TransactionStore transactions) {
        super(target);
        this.target = target;
        this.transactions = transactions;
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
    public CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
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
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
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
    public Stream<Multihash> getAllBlockHashes() {
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
