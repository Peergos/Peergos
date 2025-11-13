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
    private final String linkHost;
    private final Hasher hasher;
    private CoreNode pki;

    public TransactionalIpfs(DeletableContentAddressedStorage target,
                             TransactionStore transactions,
                             BlockRequestAuthoriser authoriser,
                             Cid id,
                             String linkHost,
                             Hasher hasher) {
        super(target);
        this.target = target;
        this.transactions = transactions;
        this.authoriser = authoriser;
        this.id = id;
        this.linkHost = linkHost;
        this.hasher = hasher;
    }

    @Override
    public CompletableFuture<Cid> id() {
        return Futures.of(id);
    }

    @Override
    public CompletableFuture<String> linkHost(PublicKeyHash owner) {
        return Futures.of(linkHost);
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
        List<Multihash> providers = hasBlock(hash) ? List.of(id) : pki.getStorageProviders(owner);
        return get(providers, owner, hash, bat, id, hasher, true);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds,
                                                       PublicKeyHash owner,
                                                       Cid hash,
                                                       String auth,
                                                       boolean persistBlock) {
        return getRaw(peerIds, owner, hash, auth, persistBlock).thenApply(bopt -> bopt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds,
                                                       PublicKeyHash owner,
                                                       Cid hash,
                                                       Optional<BatWithId> bat,
                                                       Cid ourId,
                                                       Hasher h,
                                                       boolean persistBlock) {
        if (bat.isEmpty())
            return getRaw(peerIds, owner, hash, bat, ourId, hasher, true, persistBlock)
                    .thenApply(opt -> opt.map(CborObject::fromByteArray));
        return Futures.asyncExceptionally(() -> bat.get().bat.generateAuth(hash, ourId, 300, S3Request.currentDatetime(), bat.get().id, h)
                        .thenApply(BlockAuth::encode)
                        .thenCompose(auth -> get(peerIds, owner, hash, auth, persistBlock)),
                t -> AuthedStorage.getWithAbsentMirrorBat(t, peerIds, owner, hash, bat, ourId, h, this)
        );
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner,
                                                      Cid hash,
                                                      Optional<BatWithId> bat) {
        return getRaw(pki.getStorageProviders(owner), owner, hash, bat, id, hasher, true);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds,
                                                      PublicKeyHash owner,
                                                      Cid hash,
                                                      Optional<BatWithId> bat,
                                                      Cid ourId,
                                                      Hasher h,
                                                      boolean doAuth,
                                                      boolean persistBlock) {
        return target.getRaw(peerIds, owner, hash, bat, ourId, h, doAuth, persistBlock).thenApply(bopt -> {
            if (bopt.isEmpty())
                return Optional.empty();
            byte[] block = bopt.get();
            if (doAuth) {
                String auth = bat.isEmpty() ? "" : bat.get().bat.generateAuth(hash, ourId, 300, S3Request.currentDatetime(), bat.get().id, h)
                        .thenApply(BlockAuth::encode).join();
                if (! authoriser.allowRead(hash, block, id().join(), auth).join()) {
                    throw new IllegalStateException("Unauthorised!");
                }
            }
            return bopt;
        });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds,
                                                      PublicKeyHash owner,
                                                      Cid hash,
                                                      String auth,
                                                      boolean persistBlock) {
        return getRaw(peerIds, owner, hash, auth, true, persistBlock);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds,
                                                      PublicKeyHash owner,
                                                      Cid hash,
                                                      String auth,
                                                      boolean doAuth,
                                                      boolean persistBlock) {
        if (hash.isIdentity())
            return Futures.of(Optional.of(hash.getHash()));
        return target.getRaw(peerIds, owner, hash, auth, persistBlock).thenApply(bopt -> {
            if (bopt.isEmpty())
                return Optional.empty();
            byte[] block = bopt.get();
            if (doAuth && ! authoriser.allowRead(hash, block, id().join(), auth).join())
                throw new IllegalStateException("Unauthorised!");
            return bopt;
        });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds,
                                                      PublicKeyHash owner,
                                                      Cid hash,
                                                      Optional<BatWithId> bat,
                                                      Cid ourId,
                                                      Hasher h,
                                                      boolean persistBlock) {
        return getRaw(peerIds, owner, hash, bat, ourId, h, true, persistBlock);
    }

    @Override
    public boolean hasBlock(Cid hash) {
        return target.hasBlock(hash);
    }

    @Override
    public CompletableFuture<List<Cid>> getLinks(PublicKeyHash owner, Cid root, List<Multihash> peerids) {
        if (root.isRaw())
            return CompletableFuture.completedFuture(Collections.emptyList());
        return getRaw(Arrays.asList(id), owner, root, Optional.empty(), id, hasher, false, true)
                .thenApply(opt -> opt.map(CborObject::fromByteArray))
                .thenApply(opt -> opt
                        .map(cbor -> cbor.links().stream().map(c -> (Cid) c).collect(Collectors.toList()))
                        .orElse(Collections.emptyList())
                );
    }

    @Override
    public List<BlockMetadata> bulkGetLinks(List<Multihash> peerIds, PublicKeyHash owner, List<Want> wants) {
        return target.bulkGetLinks(peerIds, owner, wants);
    }

    @Override
    public CompletableFuture<BlockMetadata> getBlockMetadata(PublicKeyHash owner, Cid block) {
        return getRaw(List.of(id), owner, block, Optional.empty(), id, hasher, false, true)
                .thenApply(data -> BlockMetadataStore.extractMetadata(block, data.get()));
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner,
                                                          Cid root,
                                                          List<ChunkMirrorCap> caps,
                                                          Optional<Cid> committedRoot) {
        if (! hasBlock(root))
            return Futures.errored(new IllegalStateException("Champ root not present locally: " + root));
        return getChampLookup(owner, root, caps, committedRoot, hasher);
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
    public Stream<Pair<PublicKeyHash, Cid>> getAllBlockHashes(boolean useBlockstore) {
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
