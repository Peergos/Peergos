package peergos.shared.user;

import jsinterop.annotations.JsMethod;
import peergos.shared.cbor.*;

import java.time.*;
import java.util.*;


class LinkProperties implements Cborable {
    public final long label;
    public final String password;
    public final Optional<Integer> maxCount;
    public final Optional<LocalDateTime> expiry;


    public LinkProperties(long label, String password, Optional<Integer> maxCount, Optional<LocalDateTime> expiry) {
        this.label = label;
        this.password = password;
        this.maxCount = maxCount;
        this.expiry = expiry;
    }

    @JsMethod
    public long getLinkLabel() {
        return label;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("l", new CborObject.CborLong(label));
        state.put("p", new CborObject.CborString(password));
        maxCount.ifPresent(m -> state.put("m", new CborObject.CborLong(m)));
        expiry.ifPresent(e -> state.put("e", new CborObject.CborLong(e.toEpochSecond(ZoneOffset.UTC))));
        return CborObject.CborMap.build(state);
    }

    public static LinkProperties fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for LinkProperties! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        long label = m.getLong("l");
        String password = m.getString("p");
        Optional<Integer> maxCount = m.getOptionalLong("m").map(Long::intValue);
        Optional<LocalDateTime> expiry = m.getOptionalLong("e").map(s -> LocalDateTime.ofEpochSecond(s, 0, ZoneOffset.UTC));
        return new LinkProperties(label, password, maxCount, expiry);
    }
}
