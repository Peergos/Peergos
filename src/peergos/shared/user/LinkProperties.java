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
    public final Optional<Integer> maxRetrievals;
    public final Optional<LocalDateTime> expiry;
    public final Optional<Multihash> existing;


    public LinkProperties(long label, String linkPassword, String userPassword, Optional<Integer> maxRetrievals, Optional<LocalDateTime> expiry, Optional<Multihash> existing) {
        this.label = label;
        this.linkPassword = linkPassword;
        this.userPassword = userPassword;
        this.maxRetrievals = maxRetrievals;
        this.expiry = expiry;
        this.existing = existing;
    }

    public LinkProperties withExisting(Optional<Multihash> existing) {
        return new LinkProperties(label, linkPassword, userPassword, maxRetrievals, expiry, existing);
    }

    public SecretLink toLink(PublicKeyHash owner) {
        return new SecretLink(owner, label, linkPassword);
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
        existing.ifPresent(e -> state.put("h", new CborObject.CborMerkleLink(e)));
        maxRetrievals.ifPresent(m -> state.put("m", new CborObject.CborLong(m)));
        expiry.ifPresent(e -> state.put("e", new CborObject.CborLong(e.toEpochSecond(ZoneOffset.UTC))));
        return CborObject.CborMap.build(state);
    }

    public static LinkProperties fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for LinkProperties! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        long label = m.getLong("l");
        String password = m.getString("p");
        String userPassword = m.getString("u");
        Optional<Integer> maxCount = m.getOptionalLong("m").map(Long::intValue);
        Optional<LocalDateTime> expiry = m.getOptionalLong("e").map(s -> LocalDateTime.ofEpochSecond(s, 0, ZoneOffset.UTC));
        return new LinkProperties(label, password, userPassword, maxCount, expiry, m.getOptional("h", c -> ((CborObject.CborMerkleLink)c).target));
    }
}
