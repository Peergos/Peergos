package peergos.shared.user;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.social.SharedItem;

import java.util.*;
import java.util.stream.Collectors;

public class SharedItemCacheFile implements Cborable {

    private final List<SharedItem> items;
    public SharedItemCacheFile(List<SharedItem> items) {
        this.items = items;
    }

    public List<SharedItem> getItems() {
        return new ArrayList<>(items);
    }
    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(items);
    }

    public static SharedItemCacheFile fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect cbor for CachedItems: " + cbor);
        List<SharedItem> values = ((CborObject.CborList) cbor).value
                .stream().map(item -> SharedItem.fromCbor(item)).collect(Collectors.toList());
        return new SharedItemCacheFile(values);
    }

}
