package peergos.shared.storage;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;

import java.util.*;

public final class PaymentProperties  implements Cborable {

    public final Optional<String> paymentServerUrl;
    public final Optional<String> clientSecret;
    public final Optional<String> error;
    public final long freeQuota;
    public final long desiredQuota;

    private PaymentProperties(Optional<String> paymentServerUrl,
                              Optional<String> error,
                              Optional<String> clientSecret,
                              long freeQuota,
                              long desiredQuota) {
        this.paymentServerUrl = paymentServerUrl;
        this.error = error;
        this.clientSecret = clientSecret;
        this.freeQuota = freeQuota;
        this.desiredQuota = desiredQuota;
    }

    public PaymentProperties(long freeQuota) {
        this(Optional.empty(), Optional.empty(), Optional.empty(), freeQuota, 0);
    }

    public PaymentProperties(String paymentServerUrl, Optional<String> clientSecret, long freeQuota, long desiredQuota) {
        this(Optional.of(paymentServerUrl), Optional.empty(), clientSecret, freeQuota, desiredQuota);
    }

    @JsMethod
    public boolean isPaid() {
        return paymentServerUrl.isPresent();
    }

    @JsMethod
    public int freeMb() {
        return (int)(freeQuota / (1000 * 1000));
    }

    @JsMethod
    public int desiredMb() {
        return (int)(desiredQuota / (1000 * 1000));
    }

    @JsMethod
    public boolean hasError() {
        return error.isPresent();
    }

    @JsMethod
    public String getError() {
        return error.get();
    }

    @JsMethod
    public String getUrl() {
        return paymentServerUrl.get();
    }

    @JsMethod
    public String getClientSecret() {
        return clientSecret.orElse("");
    }

    public static PaymentProperties errored(String paymentServerUrl,
                                            String error,
                                            Optional<String> clientSecret,
                                            long freeQuota,
                                            long desiredQuota) {
        return new PaymentProperties(Optional.of(paymentServerUrl), Optional.of(error), clientSecret, freeQuota, desiredQuota);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("freeQuota", new CborObject.CborLong(freeQuota));
        state.put("desiredQuota", new CborObject.CborLong(desiredQuota));
        paymentServerUrl.ifPresent(url -> state.put("url", new CborObject.CborString(url)));
        error.ifPresent(err -> state.put("err", new CborObject.CborString(err)));
        if (clientSecret.isPresent())
            state.put("client_secret", new CborObject.CborString(clientSecret.get()));
        return CborObject.CborMap.build(state);
    }

    public static PaymentProperties fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for FileProperties! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        Optional<String> url = m.getOptional("url", c -> ((CborObject.CborString)c).value);
        Optional<String> err = m.getOptional("err", c -> ((CborObject.CborString)c).value);
        Optional<String> client_secret = m.getOptional("client_secret", c -> ((CborObject.CborString) c).value);
        long freeQuota = m.getLong("freeQuota");
        long desiredQuota = m.getLong("desiredQuota");
        return new PaymentProperties(url, err, client_secret, freeQuota, desiredQuota);
    }
}
