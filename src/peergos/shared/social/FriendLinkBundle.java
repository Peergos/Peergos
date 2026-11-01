package peergos.shared.social;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.routing.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** What a friend link's holder fetches from the identity's server: the key to encrypt a follow request to, signed by
 *  the identity's current key (remove-pki §8). The link id is signed in, so a bundle for one link can't answer a
 *  lookup for another.
 */
public class FriendLinkBundle implements Cborable {
    public static final int LINK_ID_BYTES = 16;
    public static final int MAX_NAME_LENGTH = 64;

    public final PublicKeyHash identity;
    public final byte[] linkId;
    public final PublicBoxingKey boxer;
    public final Optional<LocalDateTime> expiry;
    public final String suggestedName;

    public FriendLinkBundle(PublicKeyHash identity, byte[] linkId, PublicBoxingKey boxer, Optional<LocalDateTime> expiry, String suggestedName) {
        if (linkId.length != LINK_ID_BYTES)
            throw new IllegalStateException("Invalid friend link id");
        if (suggestedName.length() > MAX_NAME_LENGTH)
            throw new IllegalStateException("Suggested name too long");
        this.identity = identity;
        this.linkId = linkId;
        this.boxer = boxer;
        this.expiry = expiry;
        this.suggestedName = suggestedName;
    }

    public boolean isExpired(LocalDateTime nowUtc) {
        return expiry.isPresent() && expiry.get().isBefore(nowUtc);
    }

    public CompletableFuture<Signed> sign(SecretSigningKey signer) {
        byte[] body = serialize();
        return Signing.sign(signer, Signing.FRIEND_LINK_BUNDLE, body)
                .thenApply(sig -> new Signed(body, sig));
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> m = new TreeMap<>();
        m.put("i", identity);
        m.put("l", new CborObject.CborByteArray(linkId));
        m.put("b", boxer);
        expiry.ifPresent(e -> m.put("x", new CborObject.CborLong(e.toEpochSecond(ZoneOffset.UTC))));
        m.put("n", new CborObject.CborString(suggestedName));
        return CborObject.CborMap.build(m);
    }

    public static FriendLinkBundle fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for FriendLinkBundle: " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new FriendLinkBundle(m.get("i", PublicKeyHash::fromCbor),
                m.getByteArray("l"),
                m.get("b", PublicBoxingKey::fromCbor),
                m.getOptionalLong("x").map(s -> LocalDateTime.ofEpochSecond(s, 0, ZoneOffset.UTC)),
                m.getString("n"));
    }

    /** The bundle as signed, kept as the bytes that were signed so verifying never depends on re-encoding. */
    public static class Signed implements Cborable {
        public final byte[] body;
        public final byte[] signature;
        public final FriendLinkBundle bundle;

        public Signed(byte[] body, byte[] signature) {
            this.body = body;
            this.signature = signature;
            this.bundle = FriendLinkBundle.fromCbor(CborObject.fromByteArray(body));
        }

        public CompletableFuture<Boolean> isSignedBy(PublicSigningKey key) {
            return Signing.verify(key, Signing.FRIEND_LINK_BUNDLE, body, signature);
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> m = new TreeMap<>();
            m.put("b", new CborObject.CborByteArray(body));
            m.put("s", new CborObject.CborByteArray(signature));
            return CborObject.CborMap.build(m);
        }

        public static Signed fromCbor(Cborable cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor for a signed FriendLinkBundle: " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            return new Signed(m.getByteArray("b"), m.getByteArray("s"));
        }
    }
}
