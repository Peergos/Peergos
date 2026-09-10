package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.storage.auth.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** A request to authorise a batch of raw block writes with a single signature.
 *
 *  The v1 call signs each block's hash separately, which costs the client one signature per block and
 *  carries one per block on the wire. Here the writer signs the ordered list of hashes once instead,
 *  so the cost is per call rather than per block - which is what makes a move to larger, slower
 *  signatures affordable. The hashes travel explicitly because the signature is no longer carrying them.
 */
public class BlockWriteAuth implements Cborable {

    public final List<Cid> hashes;
    public final List<Long> sizes;
    public final List<List<BatId>> batIds;
    public final byte[] signature;

    public BlockWriteAuth(List<Cid> hashes, List<Long> sizes, List<List<BatId>> batIds, byte[] signature) {
        if (hashes.size() != sizes.size() || hashes.size() != batIds.size())
            throw new IllegalArgumentException("Mismatched hashes, sizes and bats in a write auth request!");
        this.hashes = hashes;
        this.sizes = sizes;
        this.batIds = batIds;
        this.signature = signature;
    }

    /** What the writer signs: the owner whose space is being written to, then the ordered hashes of
     *  the blocks.
     *
     *  The owner is in here because it is the owner that decides where a block is stored, so a
     *  signature that only covered the hashes would authorise writing that content into anybody's
     *  space. It is not bound to a transaction: re-sending this only ever re-authorises writing the
     *  same content addressed blocks to the same owner, which is what the v1 signatures already allow.
     */
    public static CompletableFuture<byte[]> payload(PublicKeyHash owner, List<Cid> hashes, Hasher hasher) {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try {
            bout.write(owner.toBytes());
            for (Cid hash : hashes)
                bout.write(hash.toBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return hasher.sha256(bout.toByteArray());
    }

    @Override
    @SuppressWarnings("unusable-by-js")
    public CborObject toCbor() {
        SortedMap<String, Cborable> props = new TreeMap<>();
        props.put("h", new CborObject.CborList(hashes.stream()
                .map(CborObject.CborMerkleLink::new)
                .collect(Collectors.toList())));
        props.put("l", new CborObject.CborList(sizes.stream()
                .map(CborObject.CborLong::new)
                .collect(Collectors.toList())));
        props.put("b", new CborObject.CborList(batIds.stream()
                .map(CborObject.CborList::new)
                .collect(Collectors.toList())));
        props.put("s", new CborObject.CborByteArray(signature));
        return CborObject.CborMap.build(props);
    }

    public static BlockWriteAuth fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for BlockWriteAuth! " + cbor);
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        return new BlockWriteAuth(map.getList("h", c -> (Cid) ((CborObject.CborMerkleLink) c).target),
                map.getList("l", c -> ((CborObject.CborLong) c).value),
                map.getList("b", c -> ((CborObject.CborList) c).map(BatId::fromCbor)),
                map.getByteArray("s"));
    }
}
