package peergos.server.storage;

import peergos.server.space.UsageStore;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;

public class MetadataCachingStorage extends DelegatingDeletableStorage {

    private static final Logger LOG = Logger.getGlobal();
    private final DeletableContentAddressedStorage target;
    private final BlockMetadataStore metadata;
    private final UsageStore usage;
    private final Hasher hasher;

    public MetadataCachingStorage(DeletableContentAddressedStorage target,
                                  BlockMetadataStore metadata,
                                  UsageStore usage,
                                  Hasher hasher) {
        super(target);
        this.target = target;
        this.metadata = metadata;
        this.usage = usage;
        this.hasher = hasher;
    }

    public void updateMetadataStoreIfEmpty() {
        if (metadata.size() > 0)
            return;
        LOG.info("Populating block metadata db..");
        target.getAllBlockHashes(true).forEach(p -> {
            Optional<BlockMetadata> existing = metadata.get(p.right);
            if (existing.isEmpty())
                metadata.put(p.right, null, target.getBlockMetadata(p.left, p.right).join());
        });
    }

    @Override
    public Optional<BlockMetadataStore> getBlockMetadataStore() {
        return Optional.of(metadata);
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signedHashes, List<byte[]> blocks, TransactionId tid) {
        return target.put(owner, writer, signedHashes, blocks, tid)
                .thenApply(cids -> {
                    for (int i=0; i < cids.size(); i++)
                        metadata.put(cids.get(i), null, blocks.get(i));
                    return cids;
                });
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signedHashes, List<byte[]> blocks, TransactionId tid, ProgressConsumer<Long> progressCounter) {
        return target.putRaw(owner, writer, signedHashes, blocks, tid, progressCounter)
                .thenApply(cids -> {
                    for (int i=0; i < cids.size(); i++)
                        metadata.put(cids.get(i), null, blocks.get(i));
                    return cids;
                });
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(PublicKeyHash owner, Multihash block) {
        Optional<BlockMetadata> meta = metadata.get((Cid) block);
        if (meta.isPresent())
            return Futures.of(Optional.of(meta.get().size));
        return target.getSize(owner, block);
    }

    @Override
    public CompletableFuture<List<Cid>> getLinks(PublicKeyHash owner, Cid block, List<Multihash> peerids) {
        if (block.isRaw())
            return Futures.of(Collections.emptyList());
        if (block.isIdentity())
            return Futures.of(CborObject.getLinks(block, block.getHash()));
        Optional<BlockMetadata> meta = metadata.get(block);
        if (meta.isPresent())
            return Futures.of(meta.get().links);
        return getBlockMetadata(owner, block).thenApply(res -> res.links);
    }

    @Override
    public CompletableFuture<BlockMetadata> getBlockMetadata(PublicKeyHash owner, Cid block) {
        if (block.isIdentity())
            return Futures.of(BlockMetadataStore.extractMetadata(block, block.getHash()));
        Optional<BlockMetadata> meta = metadata.get(block);
        if (meta.isPresent())
            return Futures.of(meta.get());
        return target.getBlockMetadata(owner, block);
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, List<ChunkMirrorCap> caps, Optional<Cid> committedRoot) {
        return target.getChampLookup(owner, root, caps, committedRoot);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return target.get(owner, hash, bat);
    }

    private BlockMetadata writeBlockMetadata(byte[] block, boolean isRaw) {
        Cid cid = hashToCid(block, isRaw, hasher).join();
        return metadata.put(cid, null, block);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, String auth, boolean persistBlock) {
        return target.get(peerIds, owner, hash, auth, persistBlock).thenApply(res -> {
            if (persistBlock)
                res.ifPresent(cbor -> writeBlockMetadata(cbor.toByteArray(), hash.isRaw()));
            return res;
        });
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h, boolean persistBlock) {
        return target.get(peerIds, owner, hash, bat, ourId, h, persistBlock).thenApply(res -> {
            if (persistBlock)
                res.ifPresent(cbor -> writeBlockMetadata(cbor.toByteArray(), hash.isRaw()));
            return res;
        });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, String auth, boolean doAuth, boolean persistBlock) {
        return target.getRaw(peerIds, owner, hash, auth, doAuth, persistBlock).thenApply(bopt -> {
            if (persistBlock)
                bopt.ifPresent(b -> writeBlockMetadata(b, hash.isRaw()));
            return bopt;
        });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h, boolean persistBlock) {
        return target.getRaw(peerIds, owner, hash, bat, ourId, h, persistBlock).thenApply(bopt -> {
            if (persistBlock)
                bopt.ifPresent(b -> writeBlockMetadata(b, hash.isRaw()));
            return bopt;
        });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h, boolean doAuth, boolean persistBlock) {
        return target.getRaw(peerIds, owner, hash, bat, ourId, h, doAuth, persistBlock).thenApply(bopt -> {
            if (persistBlock)
                bopt.ifPresent(b -> writeBlockMetadata(b, hash.isRaw()));
            return bopt;
        });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, String auth, boolean persistBlock) {
        return target.getRaw(peerIds, owner, hash, auth, persistBlock).thenApply(bopt -> {
            if (persistBlock)
                bopt.ifPresent(b -> writeBlockMetadata(b, hash.isRaw()));
            return bopt;
        });
    }

    @Override
    public CompletableFuture<List<Cid>> mirror(String username, PublicKeyHash owner, PublicKeyHash writer, List<Multihash> peerIds, Optional<Cid> existing,
                                               Optional<Cid> updated, Optional<BatWithId> mirrorBat, Cid ourNodeId,
                                               NewBlocksProcessor newBlockProcessor, TransactionId tid, Hasher hasher) {
        return target.mirror(username, owner, writer, peerIds, existing, updated, mirrorBat, ourNodeId,
                (w, bs, size) -> usage.addPendingUsage(username, w, addMetadata(peerIds, owner, bs, mirrorBat, hasher)), tid, hasher);
    }

    private int addMetadata(List<Multihash> peerIds, PublicKeyHash owner, List<Cid> hashes, Optional<BatWithId> mirrorBat, Hasher h) {
        int totalSize = 0;
        Cid us = id().join();
        for (Cid c : hashes) {
            totalSize += target.getRaw(peerIds, owner, c, mirrorBat, us, h, false)
                    .thenApply(bopt -> bopt.map(b -> writeBlockMetadata(b, c.isRaw()).size)
                            .orElse(0))
                    .join();
        }
        return totalSize;
    }
}
