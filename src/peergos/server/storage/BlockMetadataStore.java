package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.storage.auth.*;
import peergos.shared.io.ipfs.Cid;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public interface BlockMetadataStore {

    Optional<BlockMetadata> get(Cid block);

    List<Cid> hasBlocks(List<Cid> blocks);

    Map<Cid, BlockMetadata> getAll(List<Cid> blocks);

    /** The metadata for these blocks, but only where they are recorded under this owner.
     *
     *  Authorising a read has to use this rather than the owner blind lookup: the bats in a block's
     *  metadata can be supplied by whoever wrote it, so a row written under one owner must never be
     *  able to authorise a read under another. A miss is safe - the caller falls back to reading the
     *  block itself and deriving its bats from the bytes.
     *
     *  A null owner matches no row.
     */
    Map<Cid, BlockMetadata> getAll(PublicKeyHash owner, List<Cid> blocks);

    /** @return an owner holding this block, if any */
    Optional<PublicKeyHash> getOwner(Cid block);

    /** Update the version of an owner's existing row */
    void setVersion(PublicKeyHash owner, Cid block, String version);

    /** Rows are keyed by owner and cid, so each owner holding a block has its own row. A null owner is not stored. */
    void put(PublicKeyHash owner, Cid block, String version, BlockMetadata meta);

    /** Remove only this owner's rows, leaving those of any other owner of the same blocks */
    void remove(PublicKeyHash owner, List<Cid> blocks);

    /** Whether gc must list this owner's blocks from the blockstore once, to find blocks without a row */
    default boolean needsBlockstoreListing(PublicKeyHash owner) {
        return false;
    }

    default void blockstoreListed(PublicKeyHash owner) {}

    long size(PublicKeyHash owner);

    boolean isEmpty();

    void applyToAll(Consumer<Cid> consumer);

    void applyToAllSizes(BiConsumer<Cid, Long> action);

    Stream<BlockVersion> list(PublicKeyHash owner);

    void listCbor(PublicKeyHash owner, Consumer<List<BlockVersion>> res);

    default BlockMetadata put(PublicKeyHash owner, Cid block, String version, byte[] data) {
        BlockMetadata meta = extractMetadata(block, data);
        put(owner, block, version, meta);
        return meta;
    }

    /** Store the metadata for a batch of blocks written together. */
    default void put(PublicKeyHash owner, List<Cid> blocks, List<byte[]> data) {
        for (int i=0; i < blocks.size(); i++)
            put(owner, blocks.get(i), null, data.get(i));
    }

    static BlockMetadata extractMetadata(Cid block, byte[] data) {
        if (block.isRaw()) {
            BlockMetadata meta = new BlockMetadata(data.length, Collections.emptyList(), Bat.getRawBlockBats(data));
            return meta;
        } else {
            CborObject cbor = CborObject.fromByteArray(data);
            List<Cid> links = cbor
                    .links().stream()
                    .map(h -> (Cid) h)
                    .collect(Collectors.toList());
            List<BatId> batIds = cbor instanceof CborObject.CborMap ?
                    ((CborObject.CborMap) cbor).getList("bats", BatId::fromCbor) :
                    Collections.emptyList();
            BlockMetadata meta = new BlockMetadata(data.length, links, batIds);
            return meta;
        }
    }

    void compact();
}
