package peergos.shared.user;

import jsinterop.annotations.JsMethod;
import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.io.ipfs.bases.Base58;
import peergos.shared.user.fs.AbsoluteCapability;

import java.util.Arrays;
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

    /** Characters of base58 mapKey prefix that name a member in an auto-open URL. */
    private static final int SELECTOR_BYTES = 8;

    public final String path;
    public final boolean writable;
    /** Names this member in an auto-open URL fragment, and survives rename, move and reorder. */
    public final String selector;

    public LinkMember(String path, boolean writable, String selector) {
        this.path = path;
        this.writable = writable;
        this.selector = selector;
    }

    /**
     * The name for a member in an auto-open URL: the first {@value SELECTOR_BYTES} bytes of its
     * map key, base58.
     *
     * An index would break on removal, since every later member shifts, and a path would break on
     * rename or move - both cases where a URL someone already holds would silently open the wrong
     * file. A map key prefix survives all three, and if the member it names is later removed the
     * link falls back to its listing rather than opening something else.
     *
     * This leaks nothing: the fragment already carries the link password, so anyone who can read
     * a selector already holds the link, and a map key without keys is not a capability.
     */
    public static String selectorFor(AbsoluteCapability cap) {
        return Base58.encode(Arrays.copyOfRange(cap.getMapKey(), 0, SELECTOR_BYTES));
    }

    public static LinkMember build(String path, AbsoluteCapability cap) {
        return new LinkMember(path, cap.isWritable(), selectorFor(cap));
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
    public String getSelector() {
        return selector;
    }

    public LinkMember withPath(String newPath) {
        return new LinkMember(newPath, writable, selector);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("p", new CborObject.CborString(path));
        state.put("s", new CborObject.CborString(selector));
        if (writable)
            state.put("w", new CborObject.CborBoolean(true));
        return CborObject.CborMap.build(state);
    }

    public static LinkMember fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for LinkMember! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new LinkMember(m.getString("p"), m.getBoolean("w", false), m.getString("s"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LinkMember that = (LinkMember) o;
        return writable == that.writable && Objects.equals(path, that.path) && Objects.equals(selector, that.selector);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, writable, selector);
    }

    @Override
    public String toString() {
        return path + (writable ? " (writable)" : "");
    }
}
