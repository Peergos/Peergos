package peergos.shared.user;

import jsinterop.annotations.JsMethod;
import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.user.fs.AbsoluteCapability;

import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One item in a secret link, as the owner remembers it.
 *
 * This is owner-private bookkeeping: it lives in the owner's {@code SharedWithState} and never
 * travels to whoever opens the link, who gets the capabilities themselves. The path here is a
 * display hint and can go stale - a capability points at a map key, not a path, so a member
 * survives being renamed or moved while this string does not. Membership is decided by the
 * payload, never by this list.
 */
@JsType
public class LinkMember implements Cborable {

    public final String path;
    public final boolean writable;
    // whoever opens the link can't see where in the drive this is
    public final boolean hidden;

    public LinkMember(String path, boolean writable, boolean hidden) {
        this.path = path;
        this.writable = writable;
        this.hidden = hidden;
    }

    public LinkMember(String path, boolean writable) {
        this(path, writable, false);
    }

    public static LinkMember build(String path, AbsoluteCapability cap, boolean hidden) {
        return new LinkMember(path, cap.isWritable(), hidden);
    }

    @JsMethod
    public String getPath() {
        return path;
    }

    @JsMethod
    public boolean isWritable() {
        return writable;
    }

    @JsMethod
    public boolean isHidden() {
        return hidden;
    }

    public LinkMember withPath(String newPath) {
        return new LinkMember(newPath, writable, hidden);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("p", new CborObject.CborString(path));
        if (writable)
            state.put("w", new CborObject.CborBoolean(true));
        if (hidden)
            state.put("h", new CborObject.CborBoolean(true));
        return CborObject.CborMap.build(state);
    }

    public static LinkMember fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for LinkMember! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new LinkMember(m.getString("p"), m.getBoolean("w", false), m.getBoolean("h", false));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LinkMember that = (LinkMember) o;
        return writable == that.writable && hidden == that.hidden && Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, writable, hidden);
    }

    @Override
    public String toString() {
        return path + (writable ? " (writable)" : "") + (hidden ? " (hidden)" : "");
    }
}
