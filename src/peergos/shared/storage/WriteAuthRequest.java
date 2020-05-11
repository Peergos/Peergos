package peergos.shared.storage;

import peergos.shared.cbor.*;

import java.util.*;
import java.util.stream.*;

public class WriteAuthRequest implements Cborable {

    public final List<byte[]> signatures;
    public final List<Long> sizes;

    public WriteAuthRequest(List<byte[]> signatures, List<Long> sizes) {
        this.signatures = signatures;
        this.sizes = sizes;
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> props = new TreeMap<>();
        props.put("s", new CborObject.CborList(signatures.stream()
                .map(CborObject.CborByteArray::new)
                .collect(Collectors.toList())));
        props.put("l", new CborObject.CborList(sizes.stream()
                .map(CborObject.CborLong::new)
                .collect(Collectors.toList())));
        return CborObject.CborMap.build(props);
    }

    public static WriteAuthRequest fromCbor(Cborable cbor) {
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        List<byte[]> signatures = map.getList("s", c -> ((CborObject.CborByteArray)c).value);
        List<Long> sizes = map.getList("l", c -> ((CborObject.CborLong)c).value);
        return new WriteAuthRequest(signatures, sizes);
    }
}
