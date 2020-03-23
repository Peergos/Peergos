package peergos.shared.storage;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;

import java.util.*;

public final class PaymentProperties  implements Cborable {

    public final Optional<String> paymentServerUrl;
    public final String clientSecret;

    public PaymentProperties() {
        this.paymentServerUrl = Optional.empty();
        this.clientSecret = "";
    }

    public PaymentProperties(String paymentServerUrl, String clientSecret) {
        this.paymentServerUrl = Optional.of(paymentServerUrl);
        this.clientSecret = clientSecret;
    }

    @JsMethod
    public boolean isPaid() {
        return paymentServerUrl.isPresent();
    }

    @JsMethod
    public String getUrl() {
        return paymentServerUrl.get();
    }

    @JsMethod
    public String getClientSecret() {
        return clientSecret;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        paymentServerUrl.ifPresent(url -> state.put("url", new CborObject.CborString(url)));
        if (clientSecret.length() > 0)
            state.put("client_secret", new CborObject.CborString(clientSecret));
        return CborObject.CborMap.build(state);
    }

    public static PaymentProperties fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for FileProperties! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        Optional<String> url = m.getOptional("url", c -> ((CborObject.CborString)c).value);
        Optional<String> client_secret = m.getOptional("client_secret", c -> ((CborObject.CborString) c).value);
        if (url.isPresent())
            return new PaymentProperties(url.get(), client_secret.get());
        return new PaymentProperties();
    }
}
