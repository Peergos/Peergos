package peergos.shared.messaging;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.Map;
import java.util.TreeMap;

@JsType
public class Attachment implements Cborable {

    public final String filename;
    public final byte[] data;
    public Attachment(String filename, byte[] attachment) {
        this.filename = filename;
        this.data = attachment;
    }
    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("f", new CborObject.CborString(filename));
        result.put("d", new CborObject.CborByteArray(data));
        return CborObject.CborMap.build(result);
    }

    public static Attachment fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor: " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        String filename = m.getString("f");
        byte[] data = m.getByteArray("d");
        return new Attachment(filename, data);
    }
}
