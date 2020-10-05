package peergos.shared.user;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.*;

//let item = {id: id, created: created, text: text, checked: checked};
@JsType
public class TodoListItem implements Cborable {

    public final String Id;
    public final String created;
    public final String text;
    public final boolean checked;

    public TodoListItem(String Id, String created, String text, boolean checked) {
        this.Id = Id;
        this.created = created;
        this.text = text;
        this.checked = checked;
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> cbor = new TreeMap<>();
        cbor.put("i", new CborObject.CborString(Id));
        cbor.put("z", new CborObject.CborString(created));
        cbor.put("t", new CborObject.CborString(text));
        cbor.put("c", new CborObject.CborBoolean(checked));
        return CborObject.CborMap.build(cbor);
    }

    public static TodoListItem fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for TodoListItem: " + cbor);
        SortedMap<CborObject, ? extends Cborable> map = ((CborObject.CborMap) cbor).values;
        CborObject.CborString idStr = (CborObject.CborString)map.get(new CborObject.CborString("i"));
        CborObject.CborString createdStr = (CborObject.CborString)map.get(new CborObject.CborString("z"));
        CborObject.CborString textStr = (CborObject.CborString)map.get(new CborObject.CborString("t"));
        CborObject.CborBoolean checkedStr = (CborObject.CborBoolean)map.get(new CborObject.CborString("c"));
        return new TodoListItem(idStr.value, createdStr.value, textStr.value, checkedStr.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TodoListItem that = (TodoListItem) o;

        if (Id != null ? !Id.equals(that.Id) : that.Id != null) return false;
        if (created != null ? !created.equals(that.created) : that.created != null) return false;
        if (text != null ? !text.equals(that.text) : that.text != null) return false;
        return checked == that.checked;
    }

    @Override
    public int hashCode() {
        return Objects.hash(Id, created, text, checked);
    }

    @Override
    public String toString() {
        return " Id:" + Id + " created:" + created + " text:" + text + " checked:" + checked;
    }
}
