package peergos.server.storage;

import peergos.server.storage.auth.*;
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

    private final DeletableContentAddressedStorage target;
    private final TransactionStore transactions;
    private final BlockRequestAuthoriser authoriser;
    private final JdbcLegacyRawBlockStore legacyRawBlocks;
    private final Cid id;
    private final Hasher hasher;

    public TransactionalIpfs(DeletableContentAddressedStorage target,
                             TransactionStore transactions,
                             BlockRequestAuthoriser authoriser,
                             JdbcLegacyRawBlockStore legacyRawBlocks,
                             Cid id,
                             Hasher hasher) {
        super(target);
        this.target = target;
        this.transactions = transactions;
        this.authoriser = authoriser;
        this.legacyRawBlocks = legacyRawBlocks;
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
        return getRaw(hash, auth).thenApply(bopt -> bopt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, Optional<BatWithId> bat) {
        return getRaw(hash, bat, id, hasher);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, String auth) {
        boolean newLegacyRaw = hash.isRaw() && auth.isEmpty() && ! hasBlock(hash);
        return getRaw(hash, auth, true).thenApply(opt -> {
            if (opt.isPresent() && newLegacyRaw)
                legacyRawBlocks.addBlock(hash);
            return opt;
        });
    }

    private CompletableFuture<Optional<byte[]>> getRaw(Cid hash, String auth, boolean doAuth) {
        return target.getRaw(hash, auth).thenApply(bopt -> {
            if (bopt.isEmpty())
                return Optional.empty();
            byte[] block = bopt.get();
            if (doAuth && ! authoriser.allowRead(hash, block, id().join(), auth).join())
                throw new IllegalStateException("Unauthorised!");
            return bopt;
        });
    }

    @Override
    public boolean hasBlock(Cid hash) {
        return target.hasBlock(hash);
    }

    @Override
    public CompletableFuture<List<Cid>> getLinks(Cid root, String auth) {
        if (root.isRaw())
            return CompletableFuture.completedFuture(Collections.emptyList());
        return getRaw(root, auth, false)
                .thenApply(opt -> opt.map(CborObject::fromByteArray))
                .thenApply(opt -> opt
                        .map(cbor -> cbor.links().stream().map(c -> (Cid) c).collect(Collectors.toList()))
                        .orElse(Collections.emptyList())
                );
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner,
                                                          Multihash root,
                                                          byte[] champKey,
                                                          Optional<BatWithId> bat) {
        return getChampLookup(root, champKey, bat, hasher);
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
