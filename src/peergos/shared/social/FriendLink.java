package peergos.shared.social;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.bases.*;
import peergos.shared.routing.*;

import java.util.*;

/** What is scanned or pasted to ask someone to be friends (remove-pki §8). The identity is the only routing input; the
 *  hint only helps a server whose routing log doesn't hold the identity yet, and is neither signed nor needed.
 */
@JsType
public class FriendLink implements Cborable {
    public static final String PREFIX = "follow/";

    public final PublicKeyHash identity;
    public final byte[] linkId;
    public final Optional<ServerHint> hint;

    @JsIgnore
    public FriendLink(PublicKeyHash identity, byte[] linkId, Optional<ServerHint> hint) {
        if (linkId.length != FriendLinkBundle.LINK_ID_BYTES)
            throw new IllegalStateException("Invalid friend link id");
        this.identity = identity;
        this.linkId = linkId;
        this.hint = hint;
    }

    public String toLink() {
        return PREFIX + Multibase.Base.Base58BTC.prefix + Base58.encode(serialize());
    }

    public static boolean isFriendLink(String link) {
        return link.contains(PREFIX + Multibase.Base.Base58BTC.prefix);
    }

    /** Accepts the link alone or inside a url, e.g. https://example.com/#follow/z... */
    public static FriendLink fromLink(String link) {
        String trimmed = link.trim();
        int start = trimmed.indexOf(PREFIX);
        if (start < 0)
            throw new IllegalStateException("Not a friend link");
        String encoded = trimmed.substring(start + PREFIX.length());
        if (encoded.isEmpty() || encoded.charAt(0) != Multibase.Base.Base58BTC.prefix)
            throw new IllegalStateException("Not a friend link");
        return fromCbor(CborObject.fromByteArray(Base58.decode(encoded.substring(1))));
    }

    @Override
    @JsIgnore
    public CborObject toCbor() {
        SortedMap<String, Cborable> m = new TreeMap<>();
        m.put("i", identity);
        m.put("l", new CborObject.CborByteArray(linkId));
        hint.ifPresent(h -> m.put("h", h));
        return CborObject.CborMap.build(m);
    }

    @JsIgnore
    public static FriendLink fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for FriendLink: " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new FriendLink(m.get("i", PublicKeyHash::fromCbor), m.getByteArray("l"), m.getOptional("h", ServerHint::fromCbor));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (! (o instanceof FriendLink)) return false;
        FriendLink that = (FriendLink) o;
        return identity.equals(that.identity) && Arrays.equals(linkId, that.linkId);
    }

    @Override
    public int hashCode() {
        return 31 * identity.hashCode() + Arrays.hashCode(linkId);
    }
}
