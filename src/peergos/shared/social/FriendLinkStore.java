package peergos.shared.social;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.stream.*;

/** The private side of a user's friend links: each live link's boxing keypair and what the user knows about it
 *  (remove-pki §8). Use counts live here, not on the server, since a blind request doesn't say who sent it.
 */
public class FriendLinkStore implements Cborable {

    public enum Kind {
        /** Made by the user to hand out */
        LINK,
        /** Made when sending a request, for the reply and then as the back-channel from that contact */
        REPLY
    }

    @JsType
    public static class Entry implements Cborable {
        public final byte[] linkId;
        @JsIgnore
        public final BoxingKeyPair boxer;
        public final Kind kind;
        public final String label;
        public final LocalDateTime created;
        @JsIgnore
        public final Optional<LocalDateTime> expiry;
        @JsIgnore
        public final Optional<Integer> maxUses;
        public final int uses;
        // the identity a reply link was made for
        @JsIgnore
        public final Optional<PublicKeyHash> peer;

        @JsIgnore
        public Entry(byte[] linkId,
                     BoxingKeyPair boxer,
                     Kind kind,
                     String label,
                     LocalDateTime created,
                     Optional<LocalDateTime> expiry,
                     Optional<Integer> maxUses,
                     int uses,
                     Optional<PublicKeyHash> peer) {
            this.linkId = linkId;
            this.boxer = boxer;
            this.kind = kind;
            this.label = label;
            this.created = created;
            this.expiry = expiry;
            this.maxUses = maxUses;
            this.uses = uses;
            this.peer = peer;
        }

        public String id() {
            return ArrayOps.bytesToHex(linkId);
        }

        public LocalDateTime getExpiry() {
            return expiry.orElse(null);
        }

        public int getMaxUses() {
            return maxUses.orElse(0);
        }

        public boolean isUsedUp() {
            return maxUses.isPresent() && uses >= maxUses.get();
        }

        @JsIgnore
        public Entry used() {
            return new Entry(linkId, boxer, kind, label, created, expiry, maxUses, uses + 1, peer);
        }

        @Override
        @JsIgnore
        public CborObject toCbor() {
            SortedMap<String, Cborable> m = new TreeMap<>();
            m.put("l", new CborObject.CborByteArray(linkId));
            m.put("b", boxer);
            m.put("k", new CborObject.CborString(kind.name()));
            m.put("n", new CborObject.CborString(label));
            m.put("c", new CborObject.CborLong(created.toEpochSecond(ZoneOffset.UTC)));
            expiry.ifPresent(e -> m.put("x", new CborObject.CborLong(e.toEpochSecond(ZoneOffset.UTC))));
            maxUses.ifPresent(u -> m.put("m", new CborObject.CborLong(u)));
            m.put("u", new CborObject.CborLong(uses));
            peer.ifPresent(p -> m.put("p", p));
            return CborObject.CborMap.build(m);
        }

        @JsIgnore
        public static Entry fromCbor(Cborable cbor) {
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            return new Entry(m.getByteArray("l"),
                    m.get("b", BoxingKeyPair::fromCbor),
                    Kind.valueOf(m.getString("k")),
                    m.getString("n"),
                    LocalDateTime.ofEpochSecond(m.getLong("c"), 0, ZoneOffset.UTC),
                    m.getOptionalLong("x").map(s -> LocalDateTime.ofEpochSecond(s, 0, ZoneOffset.UTC)),
                    m.getOptionalLong("m").map(Long::intValue),
                    (int) m.getLong("u"),
                    m.getOptional("p", PublicKeyHash::fromCbor));
        }
    }

    public final Map<ByteArrayWrapper, Entry> links;

    public FriendLinkStore(Map<ByteArrayWrapper, Entry> links) {
        this.links = Collections.unmodifiableMap(links);
    }

    public static FriendLinkStore empty() {
        return new FriendLinkStore(Collections.emptyMap());
    }

    public Optional<Entry> get(byte[] linkId) {
        return Optional.ofNullable(links.get(new ByteArrayWrapper(linkId)));
    }

    public FriendLinkStore with(Entry e) {
        Map<ByteArrayWrapper, Entry> updated = new HashMap<>(links);
        updated.put(new ByteArrayWrapper(e.linkId), e);
        return new FriendLinkStore(updated);
    }

    public FriendLinkStore without(byte[] linkId) {
        Map<ByteArrayWrapper, Entry> updated = new HashMap<>(links);
        updated.remove(new ByteArrayWrapper(linkId));
        return new FriendLinkStore(updated);
    }

    public List<Entry> ofKind(Kind kind) {
        return links.values().stream()
                .filter(e -> e.kind == kind)
                .sorted(Comparator.comparing(e -> e.created))
                .collect(Collectors.toList());
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(links.values().stream()
                .sorted(Comparator.comparing(e -> ArrayOps.bytesToHex(e.linkId)))
                .collect(Collectors.toList()));
    }

    public static FriendLinkStore fromCbor(Cborable cbor) {
        Map<ByteArrayWrapper, Entry> links = new HashMap<>();
        for (Cborable c : ((CborObject.CborList) cbor).value) {
            Entry e = Entry.fromCbor(c);
            links.put(new ByteArrayWrapper(e.linkId), e);
        }
        return new FriendLinkStore(links);
    }
}
