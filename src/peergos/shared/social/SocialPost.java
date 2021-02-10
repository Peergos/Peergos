package peergos.shared.social;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.fs.*;

import java.time.*;
import java.util.*;

@JsType
public class SocialPost implements Cborable {

    public final String author;
    public final String body;
    public final LocalDateTime postTime;
    public final boolean resharingAllowed;
    public final boolean isPublic;
    public final Optional<Ref> parent;
    public final List<Ref> references;
    public final List<SocialPost> previousVersions;

    @JsConstructor
    public SocialPost(String author,
                      String body,
                      LocalDateTime postTime,
                      boolean resharingAllowed,
                      boolean isPublic,
                      Optional<Ref> parent,
                      List<Ref> references,
                      List<SocialPost> previousVersions) {
        this.author = author;
        this.body = body;
        this.postTime = postTime;
        this.resharingAllowed = resharingAllowed;
        this.isPublic = isPublic;
        this.parent = parent;
        this.references = references;
        this.previousVersions = previousVersions;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("a", new CborObject.CborString(author));
        state.put("b", new CborObject.CborString(body));
        state.put("t", new CborObject.CborLong(postTime.toEpochSecond(ZoneOffset.UTC)));
        if (resharingAllowed)
            state.put("s", new CborObject.CborBoolean(true));
        if (isPublic)
            state.put("i", new CborObject.CborBoolean(true));
        parent.ifPresent(r -> state.put("p", r));
        if (! references.isEmpty())
            state.put("r", new CborObject.CborList(references));
        if (! previousVersions.isEmpty())
            state.put("v", new CborObject.CborList(previousVersions));

        return CborObject.CborMap.build(state);
    }

    public static SocialPost fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        String author = m.getString("a");
        String body = m.getString("b");
        LocalDateTime postTime = m.get("t", c -> LocalDateTime.ofEpochSecond(((CborObject.CborLong)c).value, 0, ZoneOffset.UTC));
        boolean sharingAllowed = m.getBoolean("s");
        boolean isPublic = m.getBoolean("p");
        Optional<Ref> parent = m.getOptional("p", Ref::fromCbor);
        List<Ref> references = m.getList("r", Ref::fromCbor);
        List<SocialPost> previousVersions = m.getList("v", SocialPost::fromCbor);

        return new SocialPost(author, body, postTime, sharingAllowed, isPublic, parent, references, previousVersions);
    }

    public static class Ref implements Cborable {
        public final String path;
        public final AbsoluteCapability cap;
        public final Multihash contentHash;

        @JsConstructor
        public Ref(String path, AbsoluteCapability cap, Multihash contentHash) {
            this.path = path;
            this.cap = cap;
            this.contentHash = contentHash;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("p", new CborObject.CborString(path));
            state.put("c", cap);
            state.put("h", new CborObject.CborMerkleLink(contentHash));

            return CborObject.CborMap.build(state);
        }

        public static Ref fromCbor(Cborable cbor) {
            if (!(cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;

            String path = m.getString("p");
            AbsoluteCapability cap = m.get("c", AbsoluteCapability::fromCbor);
            Multihash contentHash = m.getMerkleLink("h");
            return new Ref(path, cap, contentHash);
        }
    }
}
