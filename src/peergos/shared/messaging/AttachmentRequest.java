package peergos.shared.messaging;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.Map;
import java.util.TreeMap;

@JsType
public class AttachmentRequest implements Cborable {

    public final Attachment attachment;
    public AttachmentRequest(Attachment attachment) {
        this.attachment = attachment;
    }
    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("a", attachment);
        return CborObject.CborMap.build(result);
    }

    public static AttachmentRequest fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor: " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        Attachment attachment = m.get("a", Attachment::fromCbor);
        return new AttachmentRequest(attachment);
    }
}
