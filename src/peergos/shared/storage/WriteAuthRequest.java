package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.storage.auth.*;

import java.util.*;
import java.util.stream.*;

public class WriteAuthRequest implements Cborable {

    public final List<byte[]> signatures;
    public final List<Long> sizes;
    public final List<List<BatId>> batIds;

    public WriteAuthRequest(List<byte[]> signatures, List<Long> sizes, List<List<BatId>> batIds) {
        this.signatures = signatures;
        this.sizes = sizes;
        this.batIds = batIds;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> props = new TreeMap<>();
        props.put("s", new CborObject.CborList(signatures.stream()
                .map(CborObject.CborByteArray::new)
                .collect(Collectors.toList())));
        props.put("l", new CborObject.CborList(sizes.stream()
                .map(CborObject.CborLong::new)
                .collect(Collectors.toList())));
        props.put("b", new CborObject.CborList(batIds.stream()
                .map(CborObject.CborList::new)
                .collect(Collectors.toList())));
        return CborObject.CborMap.build(props);
    }

    public static WriteAuthRequest fromCbor(Cborable cbor) {
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        List<byte[]> signatures = map.getList("s", c -> ((CborObject.CborByteArray)c).value);
        List<Long> sizes = map.getList("l", c -> ((CborObject.CborLong)c).value);
        List<List<BatId>> batIds = map.getList("b", c -> ((CborObject.CborList)c).map(BatId::fromCbor));
        return new WriteAuthRequest(signatures, sizes, batIds);
    }
}
