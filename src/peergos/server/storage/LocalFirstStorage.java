package peergos.server.storage;

import io.libp2p.core.PeerId;
import peergos.server.storage.auth.Want;
import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.ContentAddressedStorageProxy;
import peergos.shared.storage.TransactionId;
import peergos.shared.storage.auth.BatWithId;
import peergos.shared.storage.auth.BlockAuth;
import peergos.shared.storage.auth.S3Request;
import peergos.shared.util.Futures;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LocalFirstStorage extends DelegatingDeletableStorage {

    private final DeletableContentAddressedStorage local;
    private final ContentAddressedStorageProxy p2pGets;
    private final PeerId ourId;
    private final Cid ourNodeId;
    private final Hasher hasher;

    public LocalFirstStorage(DeletableContentAddressedStorage local,
                             ContentAddressedStorageProxy p2pGets,
                             List<PeerId> ourIds,
                             Hasher hasher) {
        super(local);
        this.local = local;
        this.p2pGets = p2pGets;
        this.ourId = ourIds.get(ourIds.size() - 1);
        Multihash barePeerId = Multihash.decode(ourId.getBytes());
        this.ourNodeId = new Cid(1, Cid.Codec.LibP2pKey, barePeerId.type, barePeerId.getHash());
        this.hasher = hasher;
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds,
                                                      PublicKeyHash owner,
                                                      Cid hash,
                                                      Optional<BatWithId> bat,
                                                      Cid ourId,
                                                      Hasher h,
                                                      boolean persistBlock) {
        if (hash.isIdentity())
            return Futures.of(Optional.of(hash.getHash()));
        boolean localBlock = local.hasBlock(hash);
        if (localBlock)
            return local.getRaw(peerIds, owner, hash, bat, ourId, h, persistBlock);
        return p2pGets.getRaw(peerIds.get(0), owner, hash, bat).thenCompose(res -> {
            if (res.isPresent() && persistBlock) {
                return (hash.isRaw() ?
                        local.putRaw(owner, owner, new byte[0], res.get(), new TransactionId(""), x -> {}) :
                        local.put(owner, owner, new byte[0], res.get(), new TransactionId("")))
                        .thenApply(x -> res);
            }
            return Futures.of(res);
        });
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
        if (hash.isIdentity())
            return Futures.of(Optional.of(hash.getHash()));
        boolean localBlock = local.hasBlock(hash);
        if (localBlock)
            return local.getRaw(owner, hash, bat);
        if (peerIds.get(0).equals(ourId))
            throw new IllegalStateException("We should have this block!");
        return p2pGets.getRaw(peerIds.get(0), owner, hash, bat).thenCompose(res -> {
            if (res.isPresent() && persistBlock) {
                return (hash.isRaw() ?
                        local.putRaw(owner, owner, new byte[0], res.get(), new TransactionId(""), x -> {}) :
                        local.put(owner, owner, new byte[0], res.get(), new TransactionId("")))
                        .thenApply(x -> res);
            }
            return Futures.of(res);
        });
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds,
                                                       PublicKeyHash owner,
                                                       Cid hash,
                                                       String auth,
                                                       boolean persistBlock) {
        if (! auth.isEmpty())
            throw new IllegalStateException("Can't retrieve private block!");
        if (hash.isIdentity())
            return Futures.of(Optional.of(CborObject.fromByteArray(hash.getHash())));
        boolean localBlock = local.hasBlock(hash);
        if (localBlock)
            return local.get(peerIds, owner, hash, auth, persistBlock);
        return p2pGets.get(peerIds.get(0), owner, hash, Optional.empty()).thenCompose(res -> {
            if (res.isPresent() && persistBlock) {
                return (hash.isRaw() ?
                        local.putRaw(owner, owner, new byte[0], res.get().serialize(), new TransactionId(""), x -> {}) :
                        local.put(owner, owner, new byte[0], res.get().serialize(), new TransactionId("")))
                        .thenApply(x -> res);
            }
            return Futures.of(res);
        });
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds,
                                                       PublicKeyHash owner,
                                                       Cid hash,
                                                       Optional<BatWithId> bat,
                                                       Cid ourId,
                                                       Hasher h,
                                                       boolean persistblock) {
        if (hash.isIdentity())
            return Futures.of(Optional.of(CborObject.fromByteArray(hash.getHash())));
        boolean localBlock = local.hasBlock(hash);
        if (localBlock)
            return local.get(owner, hash, bat);
        return p2pGets.get(peerIds.get(0), owner, hash, bat).thenCompose(res -> {
            if (res.isPresent() && persistblock) {
                return (hash.isRaw() ?
                        local.putRaw(owner, owner, new byte[0], res.get().serialize(), new TransactionId(""), x -> {}) :
                        local.put(owner, owner, new byte[0], res.get().serialize(), new TransactionId("")))
                        .thenApply(x -> res);
            }
            return Futures.of(res);
        });
    }

    @Override
    public CompletableFuture<BlockMetadata> getBlockMetadata(PublicKeyHash owner, Cid block) {
        return getRaw(Arrays.asList(ourNodeId), owner, block, Optional.empty(), ourNodeId, hasher, true)
                .thenApply(rawOpt -> BlockMetadataStore.extractMetadata(block, rawOpt.get()));
    }

    @Override
    public List<BlockMetadata> bulkGetLinks(List<Multihash> peerIds,
                                            PublicKeyHash owner,
                                            Cid ourId,
                                            List<Cid> blocks,
                                            Optional<BatWithId> mirrorBat,
                                            Hasher h) {
        List<Cid> localHashes = blocks.stream()
                .filter(c -> local.hasBlock(c))
                .collect(Collectors.toList());
        List<BlockMetadata> localMeta = localHashes.stream()
                .map(c -> getBlockMetadata(owner, c).join())
                .collect(Collectors.toList());
        List<Cid> remoteHashes = blocks.stream()
                .filter(c -> !localHashes.contains(c))
                .collect(Collectors.toList());
        List<BlockMetadata> remoteMeta = remoteHashes.stream()
                .map(c -> p2pGets.get(peerIds.get(0), owner, c, mirrorBat).thenApply(res -> {
                    if (res.isPresent()) {
                        return (c.isRaw() ?
                                local.putRaw(owner, owner, new byte[0], res.get().serialize(), new TransactionId(""), x -> {}) :
                                local.put(owner, owner, new byte[0], res.get().serialize(), new TransactionId("")))
                                .thenApply(x -> BlockMetadataStore.extractMetadata(c, res.get().serialize())).join();
                    }
                    throw new IllegalStateException("Couldn't retrieve " + c);
                }).join())
                .collect(Collectors.toList());
        return Stream.concat(localMeta.stream(), remoteMeta.stream())
                .collect(Collectors.toList());
    }

    @Override
    public CompletableFuture<List<Cid>> mirror(String username, PublicKeyHash owner, PublicKeyHash writer, List<Multihash> peerIds, Optional<Cid> existing,
                                               Optional<Cid> updated, Optional<BatWithId> mirrorBat, Cid ourNodeId,
                                               NewBlocksProcessor newBlockProcessor, TransactionId tid, Hasher hasher) {
        return DeletableContentAddressedStorage.mirror(username, owner, writer, peerIds, existing, updated, mirrorBat, ourNodeId, newBlockProcessor, tid, hasher, this);
    }
}
