package peergos.shared.storage;

import peergos.server.storage.*;
import peergos.shared.cbor.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;

public class LinkCounts implements Cborable {
    public final Map<Long, Pair<Long, LocalDateTime>> counts;

    public LinkCounts(Map<Long, Pair<Long, LocalDateTime>> counts) {
        this.counts = counts;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        ArrayList<CborObject> data = new ArrayList<>(counts.size() * 3);
        counts.forEach((k, v) -> {
            data.add(new CborObject.CborLong(k));
            data.add(new CborObject.CborLong(v.left));
            data.add(new CborObject.CborLong(v.right.toEpochSecond(ZoneOffset.UTC)));
        });
        state.put("d", new CborObject.CborList(data));
        return CborObject.CborMap.build(state);
    }

    public static LinkCounts fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for FileProperties! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        CborObject.CborList d = m.getList("d");
        Map<Long, Pair<Long, LocalDateTime>> counts = new HashMap<>();
        for (int i = 0; i < d.value.size(); i += 3) {
            long key = ((CborObject.CborLong) d.value.get(i)).value;
            long count = ((CborObject.CborLong) d.value.get(i + 1)).value;
            long seconds = ((CborObject.CborLong) d.value.get(i + 2)).value;
            counts.put(key, new Pair<>(count, LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC)));
        }
        return new LinkCounts(counts);
    }
}
