package peergos.shared.storage;

import peergos.shared.cbor.*;

import java.util.*;

public class BlockStoreProperties implements Cborable {
    public final Optional<String> baseUrl;

    public BlockStoreProperties(Optional<String> baseUrl) {
        this.baseUrl = baseUrl;
    }

    public static BlockStoreProperties empty() {
        return new BlockStoreProperties(Optional.empty());
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> props = new TreeMap<>();
        baseUrl.ifPresent(base -> props.put("b", new CborObject.CborString(base)));
        return CborObject.CborMap.build(props);
    }

    public static BlockStoreProperties fromCbor(Cborable cbor) {
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        Optional<String> base = map.getOptional("b", c -> ((CborObject.CborString)c).value);
        return new BlockStoreProperties(base);
    }
}
