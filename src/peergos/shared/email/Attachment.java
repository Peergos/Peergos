package peergos.shared.email;

import jsinterop.annotations.JsConstructor;
import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.display.FileRef;

import java.util.SortedMap;
import java.util.TreeMap;

@JsType
public class Attachment implements Cborable {

    public final String filename;
    public final int size;
    public final String type;
    public final String uuid;

    @JsConstructor
    public Attachment(String filename, int size,
                      String type, String uuid
    ) {
        this.filename = filename;
        this.size = size;
        this.type = type;
        this.uuid = uuid;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("f", new CborObject.CborString(filename));
        state.put("s", new CborObject.CborLong(size));
        state.put("t", new CborObject.CborString(type));
        state.put("u", new CborObject.CborString(uuid));
        return CborObject.CborMap.build(state);
    }

    public static Attachment fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;

        String filename = m.getString("f");
        int size = (int) m.getLong("s");
        String type = m.getString("t");
        String uuid = m.getString("u");
        return new Attachment(filename, size, type, uuid);
    }
}
