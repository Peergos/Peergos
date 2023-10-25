package peergos.server.storage;

import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class MetadataWritingStorage extends DelegatingDeletableStorage {

    private final DeletableContentAddressedStorage target;
    private final BlockMetadataStore metadata;

    public MetadataWritingStorage(DeletableContentAddressedStorage target, BlockMetadataStore metadata) {
        super(target);
        this.target = target;
        this.metadata = metadata;
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
        return target.getBlockMetadata(block);
    }
}
