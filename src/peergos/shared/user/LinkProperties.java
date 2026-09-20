package peergos.shared.user;

import jsinterop.annotations.JsMethod;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.user.fs.*;

import java.time.*;
import java.util.*;


public class LinkProperties implements Cborable {
    public final long label;
    public final String linkPassword, userPassword;
    public final boolean isLinkWritable, open;
    public final Optional<Integer> maxRetrievals;
    public final Optional<LocalDateTime> expiry;
    public final Optional<Multihash> existing;
    /**
     * What is in this link, as the owner remembers it. Owner-private and a display hint only:
     * the payload decides membership, since a recorded path goes stale on a rename while the
     * capability it describes does not.
     */
    public final List<LinkMember> members;

    public LinkProperties(long label, String linkPassword, String userPassword, boolean isLinkWritable,
                          Optional<Integer> maxRetrievals, Optional<LocalDateTime> expiry, boolean open, Optional<Multihash> existing) {
        this(label, linkPassword, userPassword, isLinkWritable, maxRetrievals, expiry, open, existing,
                Collections.emptyList());
    }

    public LinkProperties(long label, String linkPassword, String userPassword, boolean isLinkWritable,
                          Optional<Integer> maxRetrievals, Optional<LocalDateTime> expiry, boolean open,
                          Optional<Multihash> existing, List<LinkMember> members) {
        this.label = label;
        this.linkPassword = linkPassword;
        this.userPassword = userPassword;
        this.isLinkWritable = isLinkWritable;
        this.maxRetrievals = maxRetrievals;
        this.expiry = expiry;
        this.open = open;
        this.existing = existing;
        this.members = members;
    }

    /** Writability is a property of the members, not something a caller sets for the whole link. */
    public static LinkProperties build(long label, String linkPassword, String userPassword,
                                       Optional<Integer> maxRetrievals, Optional<LocalDateTime> expiry,
                                       boolean open, Optional<Multihash> existing,
                                       List<LinkMember> members) {
        boolean anyWritable = members.stream().anyMatch(m -> m.writable);
        return new LinkProperties(label, linkPassword, userPassword, anyWritable, maxRetrievals, expiry,
                open, existing, members);
    }

    public LinkProperties withMembers(List<LinkMember> newMembers) {
        return build(label, linkPassword, userPassword, maxRetrievals, expiry, open, existing, newMembers);
    }

    @JsMethod
    public List<LinkMember> getMembers() {
        return members;
    }

    @JsMethod
    public int memberCount() {
        return members.size();
    }

    @JsMethod
    public LinkProperties with(String userPassword, String maxRetrievals, Optional<LocalDateTime> expiry, boolean newOpen) {
        Optional<Integer> maxRetrievalsOpt = maxRetrievals.isEmpty() ? Optional.empty() : Optional.of(Integer.parseInt(maxRetrievals));
        return new LinkProperties(label, linkPassword, userPassword, isLinkWritable, maxRetrievalsOpt, expiry, newOpen, existing, members);
    }

    public LinkProperties withExisting(Optional<Multihash> existing) {
        return new LinkProperties(label, linkPassword, userPassword, isLinkWritable, maxRetrievals, expiry, open, existing, members);
    }

    public SecretLink toLink(PublicKeyHash owner) {
        return new SecretLink(owner, label, linkPassword);
    }

    @JsMethod
    public boolean autoOpen() {
        return open;
    }

    @JsMethod
    public String maxRetrievalsString() {
        return maxRetrievals.map(Long::toString).orElse("");
    }

    @JsMethod
    public String toLinkString(PublicKeyHash owner) {
        return toLink(owner).toLink();
    }

    @JsMethod
    public long getLinkLabel() {
        return label;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("l", new CborObject.CborLong(label));
        state.put("p", new CborObject.CborString(linkPassword));
        state.put("u", new CborObject.CborString(userPassword));
        state.put("w", new CborObject.CborBoolean(isLinkWritable));
        state.put("o", new CborObject.CborBoolean(open));
        existing.ifPresent(e -> state.put("h", new CborObject.CborMerkleLink(e)));
        maxRetrievals.ifPresent(m -> state.put("m", new CborObject.CborLong(m)));
        expiry.ifPresent(e -> state.put("e", new CborObject.CborLong(e.toEpochSecond(ZoneOffset.UTC))));
        // absent on every link written before members existed, which is a one member link
        if (! members.isEmpty())
            state.put("ms", new CborObject.CborList(members));
        return CborObject.CborMap.build(state);
    }

    public static LinkProperties fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for LinkProperties! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        long label = m.getLong("l");
        String password = m.getString("p");
        String userPassword = m.getString("u");
        boolean isWritable = m.getBoolean("w");
        boolean open = m.getBoolean("o", false);
        Optional<Integer> maxCount = m.getOptionalLong("m").map(Long::intValue);
        Optional<LocalDateTime> expiry = m.getOptionalLong("e").map(s -> LocalDateTime.ofEpochSecond(s, 0, ZoneOffset.UTC));
        List<LinkMember> members = m.getList("ms", LinkMember::fromCbor);
        return new LinkProperties(label, password, userPassword, isWritable, maxCount, expiry, open,
                m.getOptional("h", c -> ((CborObject.CborMerkleLink)c).target), members);
    }
}
