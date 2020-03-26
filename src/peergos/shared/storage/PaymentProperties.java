package peergos.shared.storage;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;

import java.util.*;

public final class PaymentProperties  implements Cborable {

    public final Optional<String> paymentServerUrl;
    public final Optional<String> clientSecret;
    public final long desiredQuota;

    public PaymentProperties() {
        this.paymentServerUrl = Optional.empty();
        this.clientSecret = Optional.empty();
        this.desiredQuota = 0;
    }

    public PaymentProperties(String paymentServerUrl, Optional<String> clientSecret, long desiredQuota) {
        this.paymentServerUrl = Optional.of(paymentServerUrl);
        this.clientSecret = clientSecret;
        this.desiredQuota = desiredQuota;
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
        return clientSecret.orElse("");
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("desiredQuota", new CborObject.CborLong(desiredQuota));
        paymentServerUrl.ifPresent(url -> state.put("url", new CborObject.CborString(url)));
        if (clientSecret.isPresent())
            state.put("client_secret", new CborObject.CborString(clientSecret.get()));
        return CborObject.CborMap.build(state);
    }

    public static PaymentProperties fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for FileProperties! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        Optional<String> url = m.getOptional("url", c -> ((CborObject.CborString)c).value);
        Optional<String> client_secret = m.getOptional("client_secret", c -> ((CborObject.CborString) c).value);
        long desiredQuota = m.getLong("desiredQuota");
        if (url.isPresent())
            return new PaymentProperties(url.get(), client_secret, desiredQuota);
        return new PaymentProperties();
    }
}
