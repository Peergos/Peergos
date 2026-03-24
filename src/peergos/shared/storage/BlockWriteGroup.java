package peergos.shared.storage;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class BlockWriteGroup implements Cborable {

    public final List<byte[]> blocks, signatures;

    public BlockWriteGroup(List<byte[]> blocks, List<byte[]> signatures) {
        if (blocks.size() != signatures.size())
            throw new IllegalArgumentException("Different number of of blocks and signatures! " + blocks.size() + " != " + signatures.size());
        this.blocks = blocks;
        this.signatures = signatures;
    }

    @Override
    @SuppressWarnings("unusable-by-js")
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("b", new CborObject.CborList(blocks.stream().map(CborObject.CborByteArray::new).collect(Collectors.toList())));
        state.put("s", new CborObject.CborList(signatures.stream().map(CborObject.CborByteArray::new).collect(Collectors.toList())));
        return CborObject.CborMap.build(state);
    }

    public static BlockWriteGroup fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for FileProperties! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        List<byte[]> blocks = m.getList("b", c -> ((CborObject.CborByteArray)c).value);
        List<byte[]> signatures = m.getList("s", c -> ((CborObject.CborByteArray)c).value);
        return new BlockWriteGroup(blocks, signatures);
    }
}
