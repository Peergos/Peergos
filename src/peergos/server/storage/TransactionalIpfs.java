package peergos.server.storage;

import peergos.server.storage.auth.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class TransactionalIpfs extends DelegatingDeletableStorage {

    private final DeletableContentAddressedStorage target;
    private final TransactionStore transactions;
    private final BlockRequestAuthoriser authoriser;
    private final Cid id;
    private final Hasher hasher;
    private CoreNode pki;

    public TransactionalIpfs(DeletableContentAddressedStorage target,
                             TransactionStore transactions,
                             BlockRequestAuthoriser authoriser,
                             Cid id,
                             Hasher hasher) {
        super(target);
        this.target = target;
        this.transactions = transactions;
        this.authoriser = authoriser;
        this.id = id;
        this.hasher = hasher;
    }

    @Override
    public CompletableFuture<Cid> id() {
        return Futures.of(id);
    }

    @Override
    public void setPki(CoreNode pki) {
        this.pki = pki;
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
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        List<Multihash> providers = hasBlock(hash) ? Collections.emptyList() : pki.getStorageProviders(owner);
        return get(providers, hash, bat, id, hasher);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, Cid hash, String auth) {
        return getRaw(peerIds, hash, auth).thenApply(bopt -> bopt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h) {
        if (bat.isEmpty())
            return get(peerIds, hash, "");
        return bat.get().bat.generateAuth(hash, ourId, 300, S3Request.currentDatetime(), bat.get().id, h)
                .thenApply(BlockAuth::encode)
                .thenCompose(auth -> get(peerIds, hash, auth));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return getRaw(pki.getStorageProviders(owner), hash, bat, id, hasher);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, Cid hash, String auth) {
        return getRaw(peerIds, hash, auth, true);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, Cid hash, String auth, boolean doAuth) {
        return target.getRaw(peerIds, hash, auth).thenApply(bopt -> {
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
    public CompletableFuture<List<Cid>> getLinks(Cid root) {
        if (root.isRaw())
            return CompletableFuture.completedFuture(Collections.emptyList());
        return getRaw(Collections.emptyList(), root, "", false)
                .thenApply(opt -> opt.map(CborObject::fromByteArray))
                .thenApply(opt -> opt
                        .map(cbor -> cbor.links().stream().map(c -> (Cid) c).collect(Collectors.toList()))
                        .orElse(Collections.emptyList())
                );
    }

    @Override
    public List<List<Cid>> bulkGetLinks(List<Multihash> peerIds, List<Want> wants) {
        return target.bulkGetLinks(peerIds, wants);
    }

    @Override
    public CompletableFuture<BlockMetadata> getBlockMetadata(Cid block) {
        return getRaw(Collections.emptyList(), block, "", false)
                .thenApply(data -> BlockMetadataStore.extractMetadata(block, data.get()));
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner,
                                                          Cid root,
                                                          byte[] champKey,
                                                          Optional<BatWithId> bat,
                                                          Optional<Cid> committedRoot) {
        if (! hasBlock(root))
            return Futures.errored(new IllegalStateException("Champ root not present locally: " + root));
        return getChampLookup(owner, root, champKey, bat, committedRoot, hasher);
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
    public Stream<Cid> getAllBlockHashes(boolean useBlockstore) {
        return target.getAllBlockHashes(useBlockstore);
    }

    @Override
    public void getAllBlockHashVersions(Consumer<List<BlockVersion>> res) {
        target.getAllBlockHashVersions(res);
    }

    @Override
    public void delete(Cid hash) {
        target.delete(hash);
    }

    @Override
    public void bulkDelete(List<BlockVersion> blocks) {
        target.bulkDelete(blocks);
    }

    @Override
    public List<Cid> getOpenTransactionBlocks() {
        return transactions.getOpenTransactionBlocks();
    }

    @Override
    public void clearOldTransactions(long cutoffMillis) {
        transactions.clearOldTransactions(cutoffMillis);
    }
}
