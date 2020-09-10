package peergos.shared.user;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.*;

@JsType
public class CalendarEvent implements Cborable {

    public final String Id;
    public final String categoryId; //calendarId in calendar
    public final String title;
    public final boolean isAllDay;

    public final String start;
    public final String end;
    public final String location;
    public final boolean isPrivate;
    public final String state;
    public final String memo;

    public CalendarEvent(String Id, String categoryId, String title, boolean isAllDay, String start, String end,
                         String location, boolean isPrivate, String state, String memo) {
        this.Id = Id;
        this.categoryId = categoryId;
        this.title = title;
        this.isAllDay = isAllDay;
        this.start = start;
        this.end = end;
        this.location = location;
        this.isPrivate = isPrivate;
        this.state = state;
        this.memo = memo;
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> cbor = new TreeMap<>();
        cbor.put("id", new CborObject.CborString(Id));
        cbor.put("categoryId", new CborObject.CborString(categoryId));
        cbor.put("title", new CborObject.CborString(title));
        cbor.put("isAllDay", new CborObject.CborBoolean(isAllDay));
        cbor.put("start", new CborObject.CborString(start));
        cbor.put("end", new CborObject.CborString(end));
        cbor.put("location", new CborObject.CborString(location));
        cbor.put("isPrivate", new CborObject.CborBoolean(isPrivate));
        cbor.put("state", new CborObject.CborString(state));
        cbor.put("memo", new CborObject.CborString(memo));
        return CborObject.CborMap.build(cbor);
    }

    public static CalendarEvent fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for CalendarEvent: " + cbor);
        SortedMap<CborObject, ? extends Cborable> map = ((CborObject.CborMap) cbor).values;
        CborObject.CborMap m = (CborObject.CborMap) cbor;

        CborObject.CborString idStr = (CborObject.CborString)map.get(new CborObject.CborString("id"));
        CborObject.CborString categoryIdStr = (CborObject.CborString)map.get(new CborObject.CborString("categoryId"));
        CborObject.CborString titleStr = (CborObject.CborString)map.get(new CborObject.CborString("title"));
        boolean isAllDay = m.getBoolean("isAllDay");
        CborObject.CborString startStr = (CborObject.CborString)map.get(new CborObject.CborString("start"));
        CborObject.CborString endStr = (CborObject.CborString)map.get(new CborObject.CborString("end"));
        CborObject.CborString locationStr = (CborObject.CborString)map.get(new CborObject.CborString("location"));
        boolean isPrivate = m.getBoolean("isPrivate");
        CborObject.CborString stateStr = (CborObject.CborString)map.get(new CborObject.CborString("state"));
        CborObject.CborString memoStr = (CborObject.CborString)map.get(new CborObject.CborString("memo"));
        return new CalendarEvent(idStr.value, categoryIdStr.value, titleStr.value, isAllDay,
                startStr.value, endStr.value, locationStr.value, isPrivate, stateStr.value, memoStr.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CalendarEvent that = (CalendarEvent) o;

        if (Id != null ? !Id.equals(that.Id) : that.Id != null) return false;
        if (categoryId != null ? !categoryId.equals(that.categoryId) : that.categoryId != null) return false;
        if (title != null ? !title.equals(that.title) : that.title != null) return false;
        if (isAllDay != that.isAllDay) return false;
        if (start != null ? !start.equals(that.start) : that.start != null) return false;
        if (end != null ? !end.equals(that.end) : that.end != null) return false;
        if (location != null ? !location.equals(that.location) : that.location != null) return false;
        if (isPrivate != that.isPrivate) return false;
        if (state != null ? !state.equals(that.state) : that.state != null) return false;
        return memo != null ? memo.equals(that.memo) : that.memo == null;
    }

    @Override
    public int hashCode() {
        return Objects.hash(Id, categoryId, title, isAllDay, start, end, location, isPrivate, state, memo);
    }

    @Override
    public String toString() {
        return " Id:" + Id + " category Id:" + categoryId + " title:" + title + " isAllDay:" + isAllDay +
                " start:" + start + " end:" + end + " location:" + location + " isPrivate:" + isPrivate +
                " state:" + state + " memo:" + memo;
    }
}
