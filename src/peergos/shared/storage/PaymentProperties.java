package peergos.shared.storage;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;

import java.util.*;

public final class PaymentProperties  implements Cborable {

    public final Optional<String> paymentServerUrl;

    public PaymentProperties(Optional<String> paymentServerUrl) {
        this.paymentServerUrl = paymentServerUrl;
    }

    @JsMethod
    public boolean isPaid() {
        return paymentServerUrl.isPresent();
    }

    @JsMethod
    public String getUrl() {
        return paymentServerUrl.get();
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        paymentServerUrl.ifPresent(url -> state.put("url", new CborObject.CborString(url)));
        return CborObject.CborMap.build(state);
    }

    public static PaymentProperties fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for FileProperties! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        Optional<String> url = m.getOptional("url", c -> ((CborObject.CborString)c).value);
        return new PaymentProperties(url);
    }
}
