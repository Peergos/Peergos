package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

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
        return getLinksAndSize(block, auth).thenApply(res -> res.right);
    }

    @Override
    public CompletableFuture<Pair<Integer, List<Cid>>> getLinksAndSize(Cid block, String auth) {
        Optional<BlockMetadata> meta = metadata.get(block);
        if (meta.isPresent())
            return Futures.of(new Pair<>(meta.get().size, meta.get().links));
        return target.getLinksAndSize(block, auth).thenApply(res -> {
            metadata.put(block, new BlockMetadata(res.left, res.right));
            return res;
        });
    }

    private void cacheBlockMetadata(byte[] block, boolean isRaw) {
        Cid cid = hashToCid(block, isRaw, hasher).join();
        metadata.put(cid, block);
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat) {
        return target.getChampLookup(owner, root, champKey, bat).thenApply(blocks -> {
            for (byte[] block : blocks) {
                cacheBlockMetadata(block, false);
            }
            return blocks;
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
