package peergos.shared.messaging;

import peergos.shared.cbor.*;

import java.util.*;

public class GroupProperty implements Cborable {

    public static final String ADMINS_STATE_KEY = "admins";

    public final Id author;
    public final TreeClock updateTimestamp;
    public final String value;

    public GroupProperty(Id author, TreeClock updateTimestamp, String value) {
        this.author = author;
        this.updateTimestamp = updateTimestamp;
        this.value = value;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("a", author);
        result.put("t", updateTimestamp);
        result.put("v", new CborObject.CborString(value));
        return CborObject.CborMap.build(result);
    }

    public static GroupProperty fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor: " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;

        Id author = m.get("a", Id::fromCbor);
        TreeClock timestamp = m.get("t", TreeClock::fromCbor);
        String value = m.getString("v");

        return new GroupProperty(author, timestamp, value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GroupProperty that = (GroupProperty) o;
        return Objects.equals(author, that.author) &&
                Objects.equals(updateTimestamp, that.updateTimestamp) &&
                Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(author, updateTimestamp, value);
    }
}
