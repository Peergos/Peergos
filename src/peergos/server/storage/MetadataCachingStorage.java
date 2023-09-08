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

public class MetadataCachingStorage extends DelegatingDeletableStorage {

    private final DeletableContentAddressedStorage target;
    private final BlockMetadataStore metadata;
    private final Hasher hasher;

    public MetadataCachingStorage(DeletableContentAddressedStorage target, BlockMetadataStore metadata, Hasher hasher) {
        super(target);
        this.target = target;
        this.metadata = metadata;
        this.hasher = hasher;
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
                        metadata.put(cids.get(i), blocks.get(i));
                    return cids;
                });
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signedHashes, List<byte[]> blocks, TransactionId tid, ProgressConsumer<Long> progressCounter) {
        return target.putRaw(owner, writer, signedHashes, blocks, tid, progressCounter)
                .thenApply(cids -> {
                    for (int i=0; i < cids.size(); i++)
                        metadata.put(cids.get(i), blocks.get(i));
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
    public CompletableFuture<List<Cid>> getLinks(Cid block, String auth) {
        Optional<BlockMetadata> meta = metadata.get(block);
        if (meta.isPresent())
            return Futures.of(meta.get().links);
        return getBlockMetadata(block, auth).thenApply(res -> res.links);
    }

    @Override
    public CompletableFuture<BlockMetadata> getBlockMetadata(Cid block, String auth) {
        Optional<BlockMetadata> meta = metadata.get(block);
        if (meta.isPresent())
            return Futures.of(meta.get());
        return target.getBlockMetadata(block, auth)
                .thenApply(blockmeta -> {
                    metadata.put(block, blockmeta);
                    return blockmeta;
                });
    }

    private void cacheBlockMetadata(byte[] block, boolean isRaw) {
        Cid cid = hashToCid(block, isRaw, hasher).join();
        metadata.put(cid, block);
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
    public CompletableFuture<Optional<CborObject>> get(Cid hash, Optional<BatWithId> bat) {
        return target.get(hash, bat).thenApply(res -> {
            res.ifPresent(cbor -> cacheBlockMetadata(cbor.toByteArray(), hash.isRaw()));
            return res;
        });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, Optional<BatWithId> bat) {
        return target.getRaw(hash, bat).thenApply(bopt -> {
            bopt.ifPresent(b -> cacheBlockMetadata(b, hash.isRaw()));
            return bopt;
        });
    }
}
