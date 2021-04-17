package peergos.shared.display;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.social.*;

import java.util.*;

public interface Content extends Cborable {

    @JsMethod
    String inlineText();

    @JsMethod
    Optional<SocialPost.Ref> reference();

    static Content fromCbor(Cborable cbor) {
        if (cbor instanceof CborObject.CborString)
            return new Text(((CborObject.CborString) cbor).value);
        if (cbor instanceof CborObject.CborMap) {
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            String type = m.getString("t");
            switch (type) {
                case "Ref":
                    return new Reference(m.get("r", SocialPost.Ref::fromCbor));
                default:
                    throw new IllegalStateException("Unknown content type in Social Post: " + type);
            }
        }
        throw new IllegalStateException("Unknown Content type in Social Post");
    }
}
