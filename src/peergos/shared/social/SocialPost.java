package peergos.shared.social;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.fs.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@JsType
public class SocialPost implements Cborable {
    @JsType
    public enum Type {
        Text, // body is just text
        Image, // 1 image in references
        Video, // 1 video in references
        Audio, // 1 audio file in references
        Media, // several images/video/audio files in references
        Poll // votes are merged in as comments, options are in body
    }

    /** This enum describes the audience that a post is allowed to be shared with.
     *
     */
    @JsType
    public enum Resharing {
        Author,
        Friends,
        Followers,
        Public
    }

    public final Type kind;
    public final String author;
    public final String body;
    public final List<String> tags;
    public final LocalDateTime postTime;
    public final Resharing shareTo;
    public final Optional<Ref> parent;
    public final List<Ref> references;
    public final List<SocialPost> previousVersions;
    // this is excluded from hash calculation when replying
    public final List<Ref> comments;

    @JsConstructor
    public SocialPost(Type kind,
                      String author,
                      String body,
                      List<String> tags,
                      LocalDateTime postTime,
                      Resharing shareTo,
                      Optional<Ref> parent,
                      List<Ref> references,
                      List<SocialPost> previousVersions,
                      List<Ref> comments) {
        this.kind = kind;
        this.author = author;
        this.body = body;
        this.tags = tags;
        this.postTime = postTime;
        this.shareTo = shareTo;
        this.parent = parent;
        this.references = references;
        this.previousVersions = previousVersions;
        this.comments = comments;
    }

    public static SocialPost createInitialPost(Type type, String author, String body, List<String> tags, List<Ref> references, Resharing resharing) {
        return new SocialPost(type, author, body, tags, LocalDateTime.now(), resharing,
                Optional.empty(), references, Collections.emptyList(), Collections.emptyList());
    }

    public static SocialPost createComment(Ref parent, Resharing fromParent, Type type, String author, String body, List<String> tags, List<Ref> references) {
        return new SocialPost(type, author, body, tags, LocalDateTime.now(), fromParent,
                Optional.of(parent), references, Collections.emptyList(), Collections.emptyList());
    }

    public SocialPost edit(String body,
                           List<String> tags,
                           LocalDateTime postTime,
                           List<Ref> references) {
        ArrayList<SocialPost> versions = new ArrayList<>(previousVersions);
        versions.add(this);
        return new SocialPost(kind, author, body, tags, postTime, shareTo, parent, references, versions, comments);
    }

    /** adding references to comments does not change the version of this comment (the hash ignores the comment refs)
     *
     * @param comment
     * @return
     */
    public SocialPost addComment(Ref comment) {
        ArrayList<Ref> updatedComments = new ArrayList<>(comments);
        if (! comments.contains(comment))
            updatedComments.add(comment);
        return new SocialPost(kind, author, body, tags, postTime, shareTo, parent, references,
                previousVersions, updatedComments);
    }

    public SocialPost addComments(List<Ref> newComments) {
        ArrayList<Ref> updatedComments = new ArrayList<>(comments);
        for (Ref comment : newComments) {
            if (!updatedComments.contains(comment))
                updatedComments.add(comment);
        }
        return new SocialPost(kind, author, body, tags, postTime, shareTo, parent, references,
                previousVersions, updatedComments);
    }

    private byte[] serializeWithoutComments() {
        return new SocialPost(kind, author, body, tags, postTime, shareTo, parent, references,
                previousVersions, Collections.emptyList()).serialize();
    }

    public CompletableFuture<Multihash> contentHash(Hasher h) {
        return h.bareHash(serializeWithoutComments());
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("k", new CborObject.CborString(kind.name()));
        state.put("a", new CborObject.CborString(author));
        state.put("b", new CborObject.CborString(body));
        if (! tags.isEmpty())
            state.put("c", CborObject.CborList.build(tags, CborObject.CborString::new));
        state.put("t", new CborObject.CborLong(postTime.toEpochSecond(ZoneOffset.UTC)));
        state.put("s", new CborObject.CborString(shareTo.name()));
        parent.ifPresent(r -> state.put("p", r));
        if (! references.isEmpty())
            state.put("r", new CborObject.CborList(references));
        if (! previousVersions.isEmpty())
            state.put("v", new CborObject.CborList(previousVersions));
        if (! comments.isEmpty())
            state.put("d", new CborObject.CborList(comments));

        List<CborObject> withMimeType = new ArrayList<>();
        withMimeType.add(new CborObject.CborLong(MimeTypes.CBOR_PEERGOS_POST_INT));
        withMimeType.add(CborObject.CborMap.build(state));

        return new CborObject.CborList(withMimeType);
    }

    public static SocialPost fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor! " + cbor);
        CborObject.CborList withMimeType = (CborObject.CborList) cbor;
        long mimeType = withMimeType.getLong(0);
        if (mimeType != MimeTypes.CBOR_PEERGOS_POST_INT)
            throw new IllegalStateException("Invalid mimetype for SocialPost: " + mimeType);

        CborObject.CborMap m = withMimeType.get(1, c -> (CborObject.CborMap)c);

        Type type = Type.valueOf(m.getString("k"));
        String author = m.getString("a");
        String body = m.getString("b");
        List<String> tags = m.getList("c", c -> ((CborObject.CborString)c).value);
        LocalDateTime postTime = m.get("t", c -> LocalDateTime.ofEpochSecond(((CborObject.CborLong)c).value, 0, ZoneOffset.UTC));
        Resharing shareTo = Resharing.valueOf(m.getString("s"));
        Optional<Ref> parent = m.getOptional("p", Ref::fromCbor);
        List<Ref> references = m.getList("r", Ref::fromCbor);
        List<SocialPost> previousVersions = m.getList("v", SocialPost::fromCbor);
        List<Ref> comments = m.getList("d", Ref::fromCbor);

        return new SocialPost(type, author, body, tags, postTime, shareTo, parent, references, previousVersions, comments);
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

    public static class MutableRef implements Cborable {
        public final String path;
        public final AbsoluteCapability cap;

        @JsConstructor
        public MutableRef(String path, AbsoluteCapability cap) {
            this.path = path;
            this.cap = cap;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("p", new CborObject.CborString(path));
            state.put("c", cap);
            return CborObject.CborMap.build(state);
        }

        public static MutableRef fromCbor(Cborable cbor) {
            if (!(cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;

            String path = m.getString("p");
            AbsoluteCapability cap = m.get("c", AbsoluteCapability::fromCbor);
            return new MutableRef(path, cap);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MutableRef that = (MutableRef) o;
            return path.equals(that.path) && cap.equals(that.cap);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, cap);
        }
    }
}
