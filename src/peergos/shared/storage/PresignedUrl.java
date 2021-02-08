package peergos.shared.storage;

import peergos.shared.cbor.*;

import java.util.*;

public class PresignedUrl implements Cborable {

    public final String base;
    public final Map<String, String> fields;

    public PresignedUrl(String base, Map<String, String> fields) {
        this.base = base;
        this.fields = fields;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> props = new TreeMap<>();
        props.put("b", new CborObject.CborString(base));
        Map<String, Cborable> headers = new TreeMap<>();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            headers.put(e.getKey(), new CborObject.CborString(e.getValue()));
        }
        props.put("h", CborObject.CborMap.build(headers));
        return CborObject.CborMap.build(props);
    }

    public static PresignedUrl fromCbor(Cborable cbor) {
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        String base = map.getString("b");
        Map<String, String> headers = ((CborObject.CborMap)map.get("h"))
                .toMap(k -> ((CborObject.CborString)k).value, k -> ((CborObject.CborString)k).value);
        return new PresignedUrl(base, headers);
    }
}
