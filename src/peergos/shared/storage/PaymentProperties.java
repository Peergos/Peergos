package peergos.shared.storage;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;

import java.util.*;

public final class PaymentProperties  implements Cborable {

    public final Optional<String> paymentServerUrl;
    public final Optional<String> clientSecret;
    public final long freeQuota;
    public final long desiredQuota;

    private PaymentProperties(Optional<String> paymentServerUrl, Optional<String> clientSecret, long freeQuota, long desiredQuota) {
        this.paymentServerUrl = paymentServerUrl;
        this.clientSecret = clientSecret;
        this.freeQuota = freeQuota;
        this.desiredQuota = desiredQuota;
    }

    public PaymentProperties(long freeQuota) {
        this(Optional.empty(), Optional.empty(), freeQuota, 0);
    }

    public PaymentProperties(String paymentServerUrl, Optional<String> clientSecret, long freeQuota, long desiredQuota) {
        this(Optional.of(paymentServerUrl), clientSecret, freeQuota, desiredQuota);
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
        state.put("freeQuota", new CborObject.CborLong(desiredQuota));
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
        long freeQuota = m.getLong("freeQuota");
        long desiredQuota = m.getLong("desiredQuota");
        return new PaymentProperties(url, client_secret, freeQuota, desiredQuota);
    }
}
