package peergos.shared.social;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.stream.*;

/** The private side of a user's friend links: each live link's boxing keypair and what the user knows about it
 *  (remove-pki §8). Use counts live here, not on the server, since a blind request doesn't say who sent it.
 */
public class FriendLinkStore implements Cborable {

    public static final int MAX_SEEN = 128;

    public enum Kind {
        /** Made by the user to hand out */
        LINK,
        /** Made when sending a request, for the reply and then as the back-channel from that contact */
        REPLY,
        /** Given to a contact to pass on with anything of ours they share, so its recipients can ask us (remove-pki §9) */
        INTRODUCTION
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
        // hashes of the requests counted as uses, the latest MAX_SEEN, so a request still waiting isn't counted twice
        @JsIgnore
        public final List<ByteArrayWrapper> seen;

        @JsIgnore
        public Entry(byte[] linkId,
                     BoxingKeyPair boxer,
                     Kind kind,
                     String label,
                     LocalDateTime created,
                     Optional<LocalDateTime> expiry,
                     Optional<Integer> maxUses,
                     int uses,
                     Optional<PublicKeyHash> peer,
                     List<ByteArrayWrapper> seen) {
            this.linkId = linkId;
            this.boxer = boxer;
            this.kind = kind;
            this.label = label;
            this.created = created;
            this.expiry = expiry;
            this.maxUses = maxUses;
            this.uses = uses;
            this.peer = peer;
            this.seen = Collections.unmodifiableList(seen);
        }

        public String id() {
            return ArrayOps.bytesToHex(linkId);
        }

        public double getCreatedMillis() {
            return created.toEpochSecond(ZoneOffset.UTC) * 1000.0;
        }

        /** 0 for a link that doesn't expire */
        public double getExpiryMillis() {
            return expiry.map(e -> e.toEpochSecond(ZoneOffset.UTC) * 1000.0).orElse(0.0);
        }

        public int getMaxUses() {
            return maxUses.orElse(0);
        }

        public boolean isUsedUp() {
            return maxUses.isPresent() && uses >= maxUses.get();
        }

        @JsIgnore
        public boolean hasSeen(byte[] requestHash) {
            return seen.contains(new ByteArrayWrapper(requestHash));
        }

        @JsIgnore
        public Entry use(byte[] requestHash) {
            List<ByteArrayWrapper> updated = new ArrayList<>(seen);
            updated.add(new ByteArrayWrapper(requestHash));
            if (updated.size() > MAX_SEEN)
                updated = updated.subList(updated.size() - MAX_SEEN, updated.size());
            return new Entry(linkId, boxer, kind, label, created, expiry, maxUses, uses + 1, peer, updated);
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
            m.put("s", new CborObject.CborList(seen.stream()
                    .map(h -> new CborObject.CborByteArray(h.data))
                    .collect(Collectors.toList())));
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
                    m.getOptional("p", PublicKeyHash::fromCbor),
                    m.getList("s", c -> new ByteArrayWrapper(((CborObject.CborByteArray) c).value)));
        }
    }

    /** Where to send anything to a contact: the link and key from their last signed request or reply to us. */
    public static class BackChannel implements Cborable {
        public final byte[] linkId;
        public final PublicBoxingKey boxer;

        public BackChannel(byte[] linkId, PublicBoxingKey boxer) {
            this.linkId = linkId;
            this.boxer = boxer;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> m = new TreeMap<>();
            m.put("l", new CborObject.CborByteArray(linkId));
            m.put("b", boxer);
            return CborObject.CborMap.build(m);
        }

        public static BackChannel fromCbor(Cborable cbor) {
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            return new BackChannel(m.getByteArray("l"), m.get("b", PublicBoxingKey::fromCbor));
        }
    }

    public final Map<ByteArrayWrapper, Entry> links;
    public final Map<PublicKeyHash, BackChannel> backChannels;
    // contacts' introductions, to pass on with anything of theirs we share
    public final Map<PublicKeyHash, FriendLink> introductions;
    public final boolean introductionsOff;

    public FriendLinkStore(Map<ByteArrayWrapper, Entry> links,
                           Map<PublicKeyHash, BackChannel> backChannels,
                           Map<PublicKeyHash, FriendLink> introductions,
                           boolean introductionsOff) {
        this.links = Collections.unmodifiableMap(links);
        this.backChannels = Collections.unmodifiableMap(backChannels);
        this.introductions = Collections.unmodifiableMap(introductions);
        this.introductionsOff = introductionsOff;
    }

    public static FriendLinkStore empty() {
        return new FriendLinkStore(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), false);
    }

    public FriendLinkStore withIntroductionFrom(PublicKeyHash contact, FriendLink introduction) {
        Map<PublicKeyHash, FriendLink> updated = new HashMap<>(introductions);
        updated.put(contact, introduction);
        return new FriendLinkStore(links, backChannels, updated, introductionsOff);
    }

    public FriendLinkStore withIntroductionsOff(boolean off) {
        return new FriendLinkStore(links, backChannels, introductions, off);
    }

    /** The introduction we gave a contact to pass on. */
    public Optional<Entry> introductionFor(PublicKeyHash contact) {
        return links.values().stream()
                .filter(e -> e.kind == Kind.INTRODUCTION && e.peer.equals(Optional.of(contact)))
                .findFirst();
    }

    public Optional<BackChannel> backChannel(PublicKeyHash contact) {
        return Optional.ofNullable(backChannels.get(contact));
    }

    public FriendLinkStore withBackChannel(PublicKeyHash contact, BackChannel channel) {
        Map<PublicKeyHash, BackChannel> updated = new HashMap<>(backChannels);
        updated.put(contact, channel);
        return new FriendLinkStore(links, updated, introductions, introductionsOff);
    }

    /** Our reply link for a contact, which their requests and replies arrive on. */
    public Optional<Entry> replyLinkFor(PublicKeyHash contact) {
        return links.values().stream()
                .filter(e -> e.kind == Kind.REPLY && e.peer.equals(Optional.of(contact)))
                .findFirst();
    }

    public Optional<Entry> get(byte[] linkId) {
        return Optional.ofNullable(links.get(new ByteArrayWrapper(linkId)));
    }

    public FriendLinkStore with(Entry e) {
        Map<ByteArrayWrapper, Entry> updated = new HashMap<>(links);
        updated.put(new ByteArrayWrapper(e.linkId), e);
        return new FriendLinkStore(updated, backChannels, introductions, introductionsOff);
    }

    public FriendLinkStore without(byte[] linkId) {
        Map<ByteArrayWrapper, Entry> updated = new HashMap<>(links);
        updated.remove(new ByteArrayWrapper(linkId));
        return new FriendLinkStore(updated, backChannels, introductions, introductionsOff);
    }

    public List<Entry> ofKind(Kind kind) {
        return links.values().stream()
                .filter(e -> e.kind == kind)
                .sorted(Comparator.comparing(e -> e.created))
                .collect(Collectors.toList());
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> m = new TreeMap<>();
        m.put("l", new CborObject.CborList(links.values().stream()
                .sorted(Comparator.comparing(e -> ArrayOps.bytesToHex(e.linkId)))
                .collect(Collectors.toList())));
        m.put("c", new CborObject.CborList(backChannels.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().toString()))
                .map(e -> new CborObject.CborList(Arrays.asList(e.getKey(), e.getValue())))
                .collect(Collectors.toList())));
        m.put("i", new CborObject.CborList(introductions.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().toString()))
                .map(e -> new CborObject.CborList(Arrays.asList(e.getKey(), e.getValue())))
                .collect(Collectors.toList())));
        if (introductionsOff)
            m.put("o", new CborObject.CborBoolean(true));
        return CborObject.CborMap.build(m);
    }

    public static FriendLinkStore fromCbor(Cborable cbor) {
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        Map<ByteArrayWrapper, Entry> links = new HashMap<>();
        for (Entry e : m.getList("l", Entry::fromCbor))
            links.put(new ByteArrayWrapper(e.linkId), e);
        Map<PublicKeyHash, BackChannel> backChannels = new HashMap<>();
        for (CborObject.CborList pair : m.getList("c", c -> (CborObject.CborList) c))
            backChannels.put(PublicKeyHash.fromCbor(pair.value.get(0)), BackChannel.fromCbor(pair.value.get(1)));
        Map<PublicKeyHash, FriendLink> introductions = new HashMap<>();
        if (m.containsKey("i"))
            for (CborObject.CborList pair : m.getList("i", c -> (CborObject.CborList) c))
                introductions.put(PublicKeyHash.fromCbor(pair.value.get(0)), FriendLink.fromCbor(pair.value.get(1)));
        return new FriendLinkStore(links, backChannels, introductions, m.getBoolean("o", false));
    }
}
