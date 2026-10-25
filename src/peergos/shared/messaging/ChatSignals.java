package peergos.shared.messaging;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** Typing, presence and read receipts for a chat, sent as {@link EphemeralSignals}.
 *
 *  Each chat that uses them has a random symmetric key in its group state, which every member can
 *  read and nobody else can. The relay channel is derived from that key, and every signal is signed
 *  with the sender's chat identity and then encrypted with it, so the relay can't read what is
 *  signalled or which chat it belongs to, and members can't send signals in each other's name.
 *  The relay does see which of its users post to and poll a channel, and when, as every call is
 *  made in a user's name for rate limiting; the channel changes daily, so it can link members of
 *  a chat within a day but not across days. Removing a member replaces the key, after which the
 *  removed member can't follow the chat's signals any more.
 */
public class ChatSignals {

    public static final String KEY_PROPERTY = "signals-key";

    public static final String TYPING = "typing";
    public static final String STOPPED_TYPING = "stopped";
    /** Something new was committed to the sender's message log, so pull it now rather than on the next poll. */
    public static final String UPDATED = "updated";
    public static final String PRESENCE = "presence";
    public static final String DELIVERED = "delivered";
    public static final String READ = "read";
    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(TYPING, STOPPED_TYPING, UPDATED, PRESENCE, DELIVERED, READ));
    /** These replace the sender's previous one of the same type, and are kept for members who connect later. */
    private static final Set<String> STICKY = new HashSet<>(Arrays.asList(PRESENCE, DELIVERED, READ));
    private static final int MAX_VALUE_LENGTH = 200;
    private static final long MAX_EVENT_AGE_MILLIS = 60_000;

    public static boolean isEnabled(ChatController chat) {
        return chat.hasGroupProperty(KEY_PROPERTY);
    }

    public static String newKey() {
        return ArrayOps.bytesToHex(SymmetricKey.random().serialize());
    }

    private static SymmetricKey key(ChatController chat) {
        return SymmetricKey.fromByteArray(ArrayOps.hexToBytes(chat.getGroupProperty(KEY_PROPERTY)));
    }

    private static CompletableFuture<byte[]> derive(Hasher hasher, SymmetricKey key, String purpose, byte[] extra) {
        return hasher.sha256(ArrayOps.concat(ArrayOps.concat(("peergos-chat-signals-" + purpose).getBytes(), key.getKey()), extra))
                .thenApply(h -> Arrays.copyOfRange(h, 0, 16));
    }

    /** The relay channel changes every day, so the relay can't link a chat's signals across days. */
    public static long epoch() {
        return System.currentTimeMillis() / (24 * 3600_000L);
    }

    public static CompletableFuture<byte[]> channel(ChatController chat, Hasher hasher) {
        return channel(chat, epoch(), hasher);
    }

    public static CompletableFuture<byte[]> channel(ChatController chat, long epoch, Hasher hasher) {
        return derive(hasher, key(chat), "channel", Long.toString(epoch).getBytes());
    }

    public static CompletableFuture<Boolean> send(ChatController chat,
                                                  String type,
                                                  String value,
                                                  String username,
                                                  SigningPrivateKeyAndPublicHash identity,
                                                  EphemeralSignals relay,
                                                  Hasher hasher) {
        if (! TYPES.contains(type))
            throw new IllegalArgumentException("Unknown signal type: " + type);
        if (value.length() > MAX_VALUE_LENGTH)
            throw new IllegalArgumentException("Signal value too long");
        SymmetricKey key = key(chat);
        Id author = chat.host().id;
        long now = System.currentTimeMillis();

        SortedMap<String, Cborable> inner = new TreeMap<>();
        inner.put("t", new CborObject.CborString(type));
        inner.put("v", new CborObject.CborString(value));
        inner.put("c", new CborObject.CborLong(now));
        return chat.privateChatState.chatIdentity.secret.signMessage(CborObject.CborMap.build(inner).serialize())
                .thenCompose(signed -> {
                    SortedMap<String, Cborable> outer = new TreeMap<>();
                    outer.put("a", author);
                    outer.put("s", new CborObject.CborByteArray(signed));
                    byte[] nonce = key.createNonce();
                    byte[] payload = ArrayOps.concat(nonce, key.encrypt(CborObject.CborMap.build(outer).serialize(), nonce));
                    CompletableFuture<byte[]> slot = STICKY.contains(type) ?
                            derive(hasher, key, "slot", ArrayOps.concat(author.serialize(), type.getBytes())) :
                            Futures.of(new byte[0]);
                    return channel(chat, epoch(), hasher).thenCompose(channel -> slot.thenCompose(s ->
                            relay.post(username, new EphemeralSignals.Post(channel, s, payload, now), identity.secret)));
                });
    }

    /** A verified signal from another member of the chat. */
    public static class Received {
        public final String author, type, value;
        public final long createdEpochMillis;

        public Received(String author, String type, String value, long createdEpochMillis) {
            this.author = author;
            this.type = type;
            this.value = value;
            this.createdEpochMillis = createdEpochMillis;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("author", author);
            res.put("type", type);
            res.put("value", value);
            res.put("time", Long.toString(createdEpochMillis));
            return res;
        }
    }

    /** Decrypt and verify signals; anything that doesn't decrypt with the chat key, isn't signed by a
     *  current member, is from ourselves, or is a stale event, is dropped.
     */
    public static CompletableFuture<List<Received>> open(ChatController chat,
                                                         List<EphemeralSignals.Signal> signals,
                                                         Map<String, PublicSigningKey> keyCache,
                                                         ContentAddressedStorage ipfs) {
        SymmetricKey key = key(chat);
        Id us = chat.host().id;
        long now = System.currentTimeMillis();
        List<CompletableFuture<Optional<Received>>> opened = signals.stream()
                .map(signal -> {
                    try {
                        int nonceSize = key.createNonce().length;
                        byte[] nonce = Arrays.copyOfRange(signal.payload, 0, nonceSize);
                        byte[] cipher = Arrays.copyOfRange(signal.payload, nonceSize, signal.payload.length);
                        CborObject.CborMap outer = (CborObject.CborMap) CborObject.fromByteArray(key.decrypt(cipher, nonce));
                        Id author = outer.get("a", Id::fromCbor);
                        byte[] signed = outer.getByteArray("s");
                        if (author.equals(us))
                            return Futures.of(Optional.<Received>empty());
                        String username = chat.getUsername(author);
                        Member member = username == null ? null : chat.getMember(username);
                        if (member == null || member.removed || member.chatIdentity.isEmpty())
                            return Futures.of(Optional.<Received>empty());
                        return signerOf(member, keyCache, ipfs)
                                .thenCompose(signer -> signer.unsignMessage(signed))
                                .thenApply(raw -> {
                                    CborObject.CborMap inner = (CborObject.CborMap) CborObject.fromByteArray(raw);
                                    String type = inner.getString("t");
                                    long created = inner.getLong("c");
                                    if (! TYPES.contains(type))
                                        return Optional.<Received>empty();
                                    if (! STICKY.contains(type) && Math.abs(now - created) > MAX_EVENT_AGE_MILLIS)
                                        return Optional.<Received>empty();
                                    return Optional.of(new Received(username, type, inner.getString("v"), created));
                                })
                                .exceptionally(t -> Optional.empty());
                    } catch (Exception e) {
                        return Futures.of(Optional.<Received>empty());
                    }
                })
                .collect(Collectors.toList());
        return Futures.combineAllInOrder(opened)
                .thenApply(all -> all.stream()
                        .flatMap(Optional::stream)
                        .collect(Collectors.toList()));
    }

    private static CompletableFuture<PublicSigningKey> signerOf(Member member,
                                                                Map<String, PublicSigningKey> keyCache,
                                                                ContentAddressedStorage ipfs) {
        OwnerProof chatIdentity = member.chatIdentity.get();
        String cacheKey = member.username + "/" + chatIdentity.ownedKey;
        PublicSigningKey cached = keyCache.get(cacheKey);
        if (cached != null)
            return Futures.of(cached);
        return chatIdentity.getAndVerifyOwner(member.identity, ipfs)
                .thenCompose(hash -> ipfs.getSigningKey(hash, hash))
                .thenApply(opt -> {
                    PublicSigningKey signer = opt.orElseThrow(() -> new IllegalStateException("Couldn't retrieve chat identity"));
                    keyCache.put(cacheKey, signer);
                    return signer;
                });
    }
}
