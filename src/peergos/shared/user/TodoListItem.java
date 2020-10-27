package peergos.shared.user;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

//let item = {id: id, created: created, text: text, checked: checked};
@JsType
public class TodoListItem implements Cborable {

    public final String Id;
    private final LocalDateTime created;
    public final String text;
    public final boolean checked;

    public TodoListItem(String Id, LocalDateTime created, String text, boolean checked) {
        this.Id = Id;
        this.created = created;
        this.text = text;
        this.checked = checked;
    }

    public String getCreatedAsMillisecondsString() {
        long milliseconds = 1000 * created.toEpochSecond(java.time.ZoneOffset.UTC);
        return "" + milliseconds;
    }

    public static TodoListItem build(String Id, String timeInMillis, String text, boolean checked) {
        long milliseconds = Long.parseLong(timeInMillis);
        LocalDateTime createdDateTime = LocalDateTime.ofEpochSecond(milliseconds/1000, 0, ZoneOffset.UTC);
        return new TodoListItem(Id, createdDateTime, text, checked);
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> cbor = new TreeMap<>();
        cbor.put("i", new CborObject.CborString(Id));
        cbor.put("z", new CborObject.CborLong(created.toEpochSecond(ZoneOffset.UTC)));
        cbor.put("t", new CborObject.CborString(text.substring(0, Math.min(text.length(), 60))));
        cbor.put("c", new CborObject.CborBoolean(checked));
        return CborObject.CborMap.build(cbor);
    }

    public static TodoListItem fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for TodoListItem: " + cbor);
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        String id = map.getString("i");
        long modifiedEpochSeconds = map.getLong("z");
        LocalDateTime modified = LocalDateTime.ofEpochSecond(modifiedEpochSeconds, 0, ZoneOffset.UTC);
        String text = map.getString("t");
        boolean checked = map.getBoolean("c");
        return new TodoListItem(id, modified, text, checked);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TodoListItem that = (TodoListItem) o;

        if (Id != null ? !Id.equals(that.Id) : that.Id != null) return false;
        if (created.toEpochSecond(ZoneOffset.UTC) != that.created.toEpochSecond(ZoneOffset.UTC)) {
            return false;
        }
        //Can't use due to missing GWT emulation
        // if (created != null ? !created.truncatedTo(ChronoUnit.SECONDS)
        //        .equals(that.created.truncatedTo(ChronoUnit.SECONDS)) : that.created != null) return false;
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
