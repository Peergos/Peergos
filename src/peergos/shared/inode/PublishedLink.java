package peergos.shared.inode;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.fs.*;

import java.util.*;

/** The secret link a path is published through. Its password sits in the public tree on purpose:
 *  anyone may open a published file, and a link is how the web ui opens anything. */
public class PublishedLink implements Cborable {
    public final long label;
    public final String linkPassword;

    public PublishedLink(long label, String linkPassword) {
        this.label = label;
        this.linkPassword = linkPassword;
    }

    public SecretLink toLink(PublicKeyHash owner) {
        return new SecretLink(owner, label, linkPassword);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("l", new CborObject.CborLong(label));
        state.put("p", new CborObject.CborString(linkPassword));
        return CborObject.CborMap.build(state);
    }

    public static PublishedLink fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for PublishedLink!");
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new PublishedLink(m.getLong("l"), m.getString("p"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PublishedLink that = (PublishedLink) o;
        return label == that.label && linkPassword.equals(that.linkPassword);
    }

    @Override
    public int hashCode() {
        return Objects.hash(label, linkPassword);
    }
}
