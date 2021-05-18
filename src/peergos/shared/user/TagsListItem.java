package peergos.shared.user;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@JsType
public class TagsListItem implements Cborable {

    public final String Id;
    public final String tagName;

    public TagsListItem(String Id, String tagName) {
        this.Id = Id;
        this.tagName = tagName;
    }

    public static TagsListItem build(String Id, String text) {
        return new TagsListItem(Id, text);
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> cbor = new TreeMap<>();
        cbor.put("i", new CborObject.CborString(Id));
        cbor.put("t", new CborObject.CborString(tagName.substring(0, Math.min(tagName.length(), 60))));
        return CborObject.CborMap.build(cbor);
    }

    public static TagsListItem fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for TagsListItem: " + cbor);
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        String id = map.getString("i");
        String tagName = map.getString("t");
        return new TagsListItem(id, tagName);
    }

    @Override
    public boolean equals(Object o) {

        if (this == o){
            return true;
        }

        if (o == null || getClass() != o.getClass()){
            return false;
        }

        TagsListItem that = (TagsListItem) o;

        if (Id != null ? !Id.equals(that.Id) : that.Id != null){
            return false;
        }

        return Objects.equals(tagName, that.tagName);

    }

    @Override
    public int hashCode() {
        return Objects.hash(Id, tagName);
    }

    @Override
    public String toString() {
        return " Id:" + Id + " tagName:" + tagName;
    }
}
