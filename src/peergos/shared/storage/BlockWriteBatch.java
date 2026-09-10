package peergos.shared.storage;

import peergos.shared.cbor.*;

import java.util.*;
import java.util.stream.*;

/** A batch of blocks authorised by a single signature rather than one per block.
 *
 *  The signature is over {@link BlockWriteAuth#payload}: the owner whose space is being written to,
 *  then the ordered hashes of these blocks. The server recomputes the hashes from the blocks, so they
 *  are not sent separately.
 */
public class BlockWriteBatch implements Cborable {

    public final List<byte[]> blocks;
    public final byte[] signature;

    public BlockWriteBatch(List<byte[]> blocks, byte[] signature) {
        this.blocks = blocks;
        this.signature = signature;
    }

    @Override
    @SuppressWarnings("unusable-by-js")
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("b", new CborObject.CborList(blocks.stream()
                .map(CborObject.CborByteArray::new)
                .collect(Collectors.toList())));
        state.put("s", new CborObject.CborByteArray(signature));
        return CborObject.CborMap.build(state);
    }

    public static BlockWriteBatch fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for BlockWriteBatch! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new BlockWriteBatch(m.getList("b", c -> ((CborObject.CborByteArray) c).value),
                m.getByteArray("s"));
    }
}
