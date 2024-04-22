package peergos.server.storage;

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
    private final Hasher hasher;

    public MetadataCachingStorage(DeletableContentAddressedStorage target, BlockMetadataStore metadata, Hasher hasher) {
        super(target);
        this.target = target;
        this.metadata = metadata;
        this.hasher = hasher;
    }

    public void updateMetadataStoreIfEmpty() {
        if (metadata.size() > 0)
            return;
        LOG.info("Populating block metadata db..");
        target.getAllBlockHashes(true).forEach(c -> {
            Optional<BlockMetadata> existing = metadata.get(c);
            if (existing.isEmpty())
                metadata.put(c, null, target.getBlockMetadata(c).join());
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
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        Optional<BlockMetadata> meta = metadata.get((Cid) block);
        if (meta.isPresent())
            return Futures.of(Optional.of(meta.get().size));
        return target.getSize(block);
    }

    @Override
    public CompletableFuture<List<Cid>> getLinks(Cid block) {
        if (block.isRaw())
            return Futures.of(Collections.emptyList());
        Optional<BlockMetadata> meta = metadata.get(block);
        if (meta.isPresent())
            return Futures.of(meta.get().links);
        return getBlockMetadata(block).thenApply(res -> res.links);
    }

    @Override
    public CompletableFuture<BlockMetadata> getBlockMetadata(Cid block) {
        Optional<BlockMetadata> meta = metadata.get(block);
        if (meta.isPresent())
            return Futures.of(meta.get());
        return target.getBlockMetadata(block)
                .thenApply(blockmeta -> {
                    metadata.put(block, null, blockmeta);
                    return blockmeta;
                });
    }

    private void cacheBlockMetadata(byte[] block, boolean isRaw) {
        Cid cid = hashToCid(block, isRaw, hasher).join();
        metadata.put(cid, null, block);
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat, Optional<Cid> committedRoot) {
        return target.getChampLookup(owner, root, champKey, bat,committedRoot).thenApply(blocks -> {
            for (byte[] block : blocks) {
                cacheBlockMetadata(block, false);
            }
            return blocks;
        });
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return target.get(owner, hash, bat).thenApply(res -> {
            res.ifPresent(cbor -> cacheBlockMetadata(cbor.toByteArray(), hash.isRaw()));
            return res;
        });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, Cid hash, String auth, boolean doAuth) {
        return target.getRaw(peerIds, hash, auth, doAuth);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return target.getRaw(owner, hash, bat).thenApply(bopt -> {
            bopt.ifPresent(b -> cacheBlockMetadata(b, hash.isRaw()));
            return bopt;
        });
    }

    @Override
    public CompletableFuture<List<Cid>> mirror(PublicKeyHash owner, List<Multihash> peerIds, Optional<Cid> existing,
                                               Optional<Cid> updated, Optional<BatWithId> mirrorBat, Cid ourNodeId,
                                               Consumer<List<Cid>> newBlockProcessor, TransactionId tid, Hasher hasher) {
        return target.mirror(owner, peerIds, existing, updated, mirrorBat, ourNodeId, b -> addMetadata(peerIds, b), tid, hasher);
    }

    private void addMetadata(List<Multihash> peerIds, List<Cid> hashes) {
        for (Cid c : hashes) {
            getRaw(peerIds, c, "", false);
        }
    }
}
