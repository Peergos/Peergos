package peergos.shared.social;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.routing.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** A request or reply between two users. One sent to a friend link is signed by its sender's identity and names who
 *  it is for, the link it was sent to, and the link and key to answer on (remove-pki §8), so it can't claim someone
 *  else's identity or be replayed to another user or link. One sent to the legacy followRequestReceiver has none of
 *  that.
 */
@JsType
public class FollowRequest implements Cborable {

    public final Optional<EntryPoint> entry;
    public final Optional<SymmetricKey> key;
    @JsIgnore
    public final Optional<Linked> linked;
    // the signed body and its signature, for a request sent to a link
    private final Optional<Pair<byte[], byte[]>> signed;

    @JsIgnore
    public FollowRequest(Optional<EntryPoint> entry, Optional<SymmetricKey> key) {
        this(entry, key, Optional.empty(), Optional.empty());
    }

    private FollowRequest(Optional<EntryPoint> entry, Optional<SymmetricKey> key, Optional<Linked> linked, Optional<Pair<byte[], byte[]>> signed) {
        this.entry = entry;
        this.key = key;
        this.linked = linked;
        this.signed = signed;
    }

    @JsIgnore
    public FollowRequest withEntry(EntryPoint updated) {
        return new FollowRequest(Optional.of(updated), key, linked, signed);
    }

    public boolean isAccepted() {
        return entry.isPresent();
    }

    public boolean isReciprocated() {
        return key.isPresent();
    }

    @JsIgnore
    public CompletableFuture<FollowRequest> sign(Linked header, SecretSigningKey signer) {
        byte[] body = body(entry, key, header).serialize();
        return Signing.sign(signer, Signing.FOLLOW_REQUEST, body)
                .thenApply(sig -> new FollowRequest(entry, key, Optional.of(header), Optional.of(new Pair<>(body, sig))));
    }

    @JsIgnore
    public CompletableFuture<Boolean> isSignedBy(PublicSigningKey key) {
        if (signed.isEmpty())
            return Futures.of(false);
        return Signing.verify(key, Signing.FOLLOW_REQUEST, signed.get().left, signed.get().right);
    }

    private static CborObject.CborMap body(Optional<EntryPoint> entry, Optional<SymmetricKey> key, Linked header) {
        SortedMap<String, Cborable> m = new TreeMap<>();
        entry.ifPresent(e -> m.put("e", e.toCbor()));
        key.ifPresent(k -> m.put("k", k.toCbor()));
        m.put("h", header);
        return CborObject.CborMap.build(m);
    }

    @SuppressWarnings("unusable-by-js")
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        if (signed.isPresent()) {
            result.put("b", new CborObject.CborByteArray(signed.get().left));
            result.put("s", new CborObject.CborByteArray(signed.get().right));
            return CborObject.CborMap.build(result);
        }
        if (linked.isPresent())
            throw new IllegalStateException("A request to a friend link must be signed");
        entry.ifPresent(e -> result.put("e", e.toCbor()));
        key.ifPresent(k -> result.put("k", k.toCbor()));
        return CborObject.CborMap.build(result);
    }

    @SuppressWarnings("unusable-by-js")
    public static FollowRequest fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for FollowRequest: " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        if (m.containsKey("b")) {
            byte[] body = m.getByteArray("b");
            CborObject.CborMap b = (CborObject.CborMap) CborObject.fromByteArray(body);
            return new FollowRequest(b.getOptional("e", EntryPoint::fromCbor),
                    b.getOptional("k", SymmetricKey::fromCbor),
                    Optional.of(b.get("h", Linked::fromCbor)),
                    Optional.of(new Pair<>(body, m.getByteArray("s"))));
        }
        return new FollowRequest(m.getOptional("e", EntryPoint::fromCbor), m.getOptional("k", SymmetricKey::fromCbor));
    }

    @Override
    public String toString() {
        return entry + " - " + key.isPresent();
    }

    /** Who a request is from and for, and where to answer it. The reply link is the back-channel the recipient uses
     *  for anything it sends the sender later. */
    public static class Linked implements Cborable {
        public final PublicKeyHash target;
        public final byte[] targetLink;
        public final PublicKeyHash from;
        public final PublicBoxingKey replyBoxer;
        public final byte[] replyLink;
        public final String suggestedName;

        public Linked(PublicKeyHash target, byte[] targetLink, PublicKeyHash from, PublicBoxingKey replyBoxer, byte[] replyLink, String suggestedName) {
            if (targetLink.length != FriendLinkBundle.LINK_ID_BYTES || replyLink.length != FriendLinkBundle.LINK_ID_BYTES)
                throw new IllegalStateException("Invalid friend link id");
            if (suggestedName.length() > FriendLinkBundle.MAX_NAME_LENGTH)
                throw new IllegalStateException("Suggested name too long");
            this.target = target;
            this.targetLink = targetLink;
            this.from = from;
            this.replyBoxer = replyBoxer;
            this.replyLink = replyLink;
            this.suggestedName = suggestedName;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> m = new TreeMap<>();
            m.put("t", target);
            m.put("tl", new CborObject.CborByteArray(targetLink));
            m.put("f", from);
            m.put("rb", replyBoxer);
            m.put("rl", new CborObject.CborByteArray(replyLink));
            m.put("n", new CborObject.CborString(suggestedName));
            return CborObject.CborMap.build(m);
        }

        public static Linked fromCbor(Cborable cbor) {
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            return new Linked(m.get("t", PublicKeyHash::fromCbor),
                    m.getByteArray("tl"),
                    m.get("f", PublicKeyHash::fromCbor),
                    m.get("rb", PublicBoxingKey::fromCbor),
                    m.getByteArray("rl"),
                    m.getString("n"));
        }
    }
}
