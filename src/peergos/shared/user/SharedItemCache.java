package peergos.shared.user;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.social.SharedItem;

import java.util.*;
import java.util.stream.Collectors;

public class SharedItemCache implements Cborable {

    private final List<SharedItem> items;
    private final int readIndex;
    public SharedItemCache(List<SharedItem> items, int readIndex) {
        this.items = items;
        this.readIndex = readIndex;
    }

    public static SharedItemCache empty() {
        return new SharedItemCache(new ArrayList<>(), 0);
    }
    public List<SharedItem> getItems() {
        return new ArrayList<>(items);
    }

    public int getReadIndex() {
        return readIndex;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> map = new HashMap<>();
        map.put("items", new CborObject.CborList(items));
        map.put("readIndex", new CborObject.CborLong(readIndex));
        return CborObject.CborMap.build(map);
    }

    public static SharedItemCache fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for SharedItemCacheFile: " + cbor);
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        int readIndex  = (int) map.getLong("readIndex");
        List<SharedItem> values = ((CborObject.CborList) map.get("items")).value
                .stream().map(item -> SharedItem.fromCbor(item)).collect(Collectors.toList());
        return new SharedItemCache(values, readIndex);
    }

}
