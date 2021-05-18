package peergos.shared.user;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.*;
import java.util.stream.Collectors;

@JsType
public class TagsList implements Cborable {

    private final List<TagsListItem> tags;

    public static TagsList build(List<TagsListItem> tags) {
        return new TagsList(tags);
    }

    public static TagsList buildFromJs(TagsListItem[] tags) {
        return new TagsList(Arrays.asList(tags));
    }

    private TagsList(List<TagsListItem> tags) {
        this.tags = tags;
    }

    public List<TagsListItem> getTagsItems() {
        return new ArrayList<>(tags);
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> cbor = new TreeMap<>();
        cbor.put("tags", new CborObject.CborList(tags));
        return CborObject.CborMap.build(cbor);
    }

    public static TagsList fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("TagsList cbor must be a Map! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        List<TagsListItem> tags = m.getList("tags")
                .value.stream()
                .map(TagsListItem::fromCbor)
                .collect(Collectors.toList());
        return new TagsList(tags);
    }

}
