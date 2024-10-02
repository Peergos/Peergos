package peergos.shared.storage;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

public class Blocks implements Cborable {

    public final List<byte[]> blocks;

    public Blocks(List<byte[]> blocks) {
        this.blocks = blocks;
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(blocks.stream()
                .map(CborObject.CborByteArray::new)
                .collect(Collectors.toList()));
    }

    public static Blocks fromCbor(Cborable cbor) {
        return new Blocks(((CborObject.CborList)cbor).map(b -> ((CborObject.CborByteArray)b).value));
    }
}
