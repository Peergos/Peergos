package peergos.shared.user;

import peergos.shared.cbor.*;

import java.util.*;

class LinkProperties implements Cborable {
    public final long label;
    public final String password;

    public LinkProperties(long label, String password) {
        this.label = label;
        this.password = password;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("l", new CborObject.CborLong(label));
        state.put("p", new CborObject.CborString(password));
        return CborObject.CborMap.build(state);
    }

    public static LinkProperties fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for LinkProperties! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new LinkProperties(m.getLong("l"), m.getString("p"));
    }
}
