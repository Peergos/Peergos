package peergos.shared.messaging;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.display.*;
import peergos.shared.messaging.messages.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class Chat implements Cborable {

    public final String chatUid;
    public final Id host;
    public final TreeClock current;
    public final Map<Id, Member> members;
    public final Map<String, GroupProperty> groupState;
    private final List<MessageEnvelope> recentMessages;

    public Chat(String chatUid,
                Id host,
                TreeClock current,
                Map<Id, Member> members,
                Map<String, GroupProperty> groupState,
                List<MessageEnvelope> recentMessages) {
        this.chatUid = chatUid;
        this.host = host;
        this.current = current;
        this.members = Collections.unmodifiableMap(members);
        this.groupState = Collections.unmodifiableMap(groupState);
        this.recentMessages = Collections.unmodifiableList(recentMessages);
    }

    public String getTitle() {
        GroupProperty prop = groupState.get("title");
        if (prop != null) {
            return prop.value;
        }
        return "";
    }

    public Set<String> getAdmins() {
        GroupProperty current = groupState.get(GroupProperty.ADMINS_STATE_KEY);
        if (current == null)
            return Collections.emptySet();
        return new HashSet<>(Arrays.asList(current.value.split(",")));
    }

    public Member host() {
        return members.get(host);
    }

    public Member getMember(Id id) {
        return members.get(id);
    }

    public Member getMember(String username) {
        List<Member> matching = members.values().stream()
                .filter(m -> m.username.equals(username))
                .collect(Collectors.toList());
        return matching.stream().filter(m -> !m.removed).findFirst().orElse(matching.get(0));
    }

    public CompletableFuture<ChatUpdate> sendMessage(Message body,
                                                     SigningPrivateKeyAndPublicHash signer,
                                                     SigningPrivateKeyAndPublicHash userIdentity,
                                                     MessageStore store,
                                                     ContentAddressedStorage ipfs,
                                                     Crypto crypto) {
        boolean nonEmpty = host().messagesMergedUpto > 0;
        return (nonEmpty ?
                store.getMessages(host().messagesMergedUpto - 1, host().messagesMergedUpto) :
                Futures.of(Collections.<SignedMessage>emptyList()))
                .thenCompose(recent -> Futures.combineAllInOrder(recent.stream()
                        .map(s -> crypto.hasher.bareHash(s.msg.serialize())
                                .thenApply(MessageRef::new))
                        .collect(Collectors.toList())))
                .thenCompose(recentRefs -> sendMessage(body, signer, userIdentity, recentRefs, ipfs, crypto));
    }

    private CompletableFuture<ChatUpdate> sendMessage(Message body,
                                                      SigningPrivateKeyAndPublicHash signer,
                                                      SigningPrivateKeyAndPublicHash userIdentity,
                                                      List<MessageRef> recentRefs,
                                                      ContentAddressedStorage ipfs,
                                                      Crypto crypto) {
        TreeClock msgTime = current.increment(host);
        MessageEnvelope msg = new MessageEnvelope(host, msgTime, LocalDateTime.now(ZoneOffset.UTC), recentRefs, body);
        return signer.secret.signatureOnly(msg.serialize()).thenCompose(signature -> {
            SignedMessage signed = new SignedMessage(signature, msg);
            return mergeMessage(chatUid, signed, host(), userIdentity, ipfs, crypto);
        });
    }

    public synchronized List<MessageEnvelope> getRecent() {
        return new ArrayList<>(recentMessages);
    }

    private Chat withMembers(Map<Id, Member> updated) {
        return new Chat(chatUid, host, current, updated, groupState, recentMessages);
    }

    private Chat withTime(TreeClock newTime) {
        return new Chat(chatUid, host, newTime, members, groupState, recentMessages);
    }

    private Chat withHost(Id host) {
        return new Chat(chatUid, host, current, members, groupState, recentMessages);
    }

    private Chat withProperties(Map<String, GroupProperty> updated) {
        return new Chat(chatUid, host, current, members, updated, recentMessages);
    }

    private Chat addToRecent(MessageEnvelope m) {
        ArrayList<MessageEnvelope> updated = new ArrayList<>(recentMessages);
        if (updated.size() >= 10)
            updated.remove(0);
        updated.add(m);
        return new Chat(chatUid, host, current, members, groupState, updated);
    }

    public static PrivateChatState generateChatIdentity(Crypto crypto) {
        SigningKeyPair chatIdentity = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash preHash = ContentAddressedStorage.hashKey(chatIdentity.publicSigningKey);
        SigningPrivateKeyAndPublicHash chatIdWithHash =
                new SigningPrivateKeyAndPublicHash(preHash, chatIdentity.secretSigningKey);
        return new PrivateChatState(chatIdWithHash, chatIdentity.publicSigningKey, Collections.emptySet());
    }

    /** Apply message to this Chat's state
     *
     * @return The state after applying the message
     */
    public CompletableFuture<ChatUpdate> applyMessage(SignedMessage signed,
                                                      String chatUid,
                                                      SigningPrivateKeyAndPublicHash userIdentity,
                                                      ContentAddressedStorage ipfs,
                                                      Crypto crypto) {
        MessageEnvelope msg = signed.msg;
        Member author = members.get(msg.author);
        switch (msg.payload.type()) {
            case Invite: {
                Invite invite = (Invite) msg.payload;
                Id newMember = invite.recipientId;
                if (members.containsKey(newMember))
                    throw new IllegalStateException("Id already exists in this chat!");
                if (!newMember.parent().equals(author.id))
                    throw new IllegalStateException("Invalid invite Id!");
                HashMap<Id, Member> updated = new HashMap<>(members);
                updated.put(author.id, members.get(author.id).incrementInvited());
                long indexIntoParent = getMember(newMember.parent()).messagesMergedUpto;
                String username = invite.username;
                PublicKeyHash identity = invite.identity;
                // If we have been removed and re-invited, generate a new chat identity
                Member host = host();
                if (host.username.equals(username) && host.removed) {
                    PrivateChatState newIdentity = generateChatIdentity(crypto);
                    return OwnerProof.build(userIdentity, newIdentity.chatIdentity.publicKeyHash).thenCompose(chatId -> {
                        Member newHost = new Member(username, newMember, identity, indexIntoParent, 0);
                        updated.put(newMember, newHost);
                        ChatUpdate afterInvite = new ChatUpdate(withMembers(updated).addToRecent(msg), Arrays.asList(signed), Collections.emptyList(), Collections.emptySet());
                        Join joinMsg = new Join(host.username, host.identity, chatId, newIdentity.chatIdPublic);
                        return crypto.hasher.bareHash(signed.msg.serialize())
                                .thenApply(MessageRef::new)
                                .thenCompose(ref -> afterInvite.state.withTime(afterInvite.state.current.withMember(newHost.id))
                                        .withHost(newHost.id)
                                        .sendMessage(joinMsg, userIdentity, userIdentity, Arrays.asList(ref), ipfs, crypto)
                                        .thenApply(afterInvite::apply));
                    });
                }

                updated.put(newMember, new Member(username, newMember, identity, indexIntoParent, 0));
                return Futures.of(new ChatUpdate(withMembers(updated).addToRecent(msg), Arrays.asList(signed), Collections.emptyList(), Collections.emptySet()));
            }
            case Join:
                if (author.chatIdentity.isEmpty()) {
                    // This is a Join message from a new member
                    Join join = (Join)msg.payload;
                    OwnerProof chatIdentity = join.chatIdentity;
                    if (!chatIdentity.ownedKey.equals(author.identity))
                        throw new IllegalStateException("Identity keys don't match!");
                    // verify signature
                    return chatIdentity.getAndVerifyOwner(author.identity, ipfs).thenApply(x -> {
                        Map<Id, Member> updated = new HashMap<>(members);
                        updated.put(author.id, author.withChatId(chatIdentity));
                        return new ChatUpdate(withMembers(updated).addToRecent(msg), Arrays.asList(signed), Collections.emptyList(), Collections.emptySet());
                    });
                }
                break;
            case GroupState: {
                SetGroupState update = (SetGroupState) msg.payload;
                GroupProperty existing = groupState.get(update.key);
                // only admins can update the list of admins
                // concurrent allowed modifications are tie-broken by Id
                if (existing == null ||
                        ((!update.key.equals(GroupProperty.ADMINS_STATE_KEY) || getAdmins().contains(author.username)) &&
                                (existing.updateTimestamp.isBeforeOrEqual(msg.timestamp) ||
                                        (existing.updateTimestamp.isConcurrentWith(msg.timestamp) &&
                                                msg.author.compareTo(existing.author) < 0)))) {
                    Map<String, GroupProperty> updated = new HashMap<>(groupState);
                    updated.put(update.key, new GroupProperty(msg.author, msg.timestamp, update.value));
                    return Futures.of(new ChatUpdate(withProperties(updated).addToRecent(msg), Arrays.asList(signed), Collections.emptyList(), Collections.emptySet()));
                }
                break;
            }
            case Application: {
                if (msg.author.equals(host))
                    break; // Don't attempt to copy own own media
                ApplicationMessage content = (ApplicationMessage) msg.payload;
                List<FileRef> fileRefs = content.body.stream()
                        .flatMap(c -> c.reference().stream())
                        .collect(Collectors.toList());
                // note media to mirror our storage
                return Futures.of(new ChatUpdate(addToRecent(msg), Arrays.asList(signed), fileRefs, Collections.emptySet()));
            }
            case RemoveMember: {
                RemoveMember rem = (RemoveMember) msg.payload;
                if (!rem.chatUid.equals(chatUid))
                    return Futures.of(new ChatUpdate(this, Collections.emptyList(), Collections.emptyList(), Collections.emptySet())); // ignore message from incorrect chat
                // anyone can remove themselves
                // an admin can remove anyone
                if (rem.memberToRemove.equals(msg.author) || getAdmins().contains(author.username)) {
                    String username = getMember(rem.memberToRemove).username;
                    Member updatedMember = members.get(rem.memberToRemove).removed(true);
                    Map<Id, Member> updated = new HashMap<>(members);
                    updated.put(rem.memberToRemove, updatedMember);
                    // revoke read access to shared chat state from removee (unless removee is us!)
                    return Futures.of(new ChatUpdate(addToRecent(msg).withMembers(updated), Arrays.asList(signed), Collections.emptyList(),
                                    username.equals(host().username) ? Collections.emptySet() : Collections.singleton(username)));
                }
                break;
            }
            case ReplyTo: {
                ReplyTo content = (ReplyTo) msg.payload;
                List<FileRef> fileRefs = content.content.body.stream()
                        .flatMap(c -> c.reference().stream())
                        .collect(Collectors.toList());
                // note media to mirror our storage
                return Futures.of(new ChatUpdate(addToRecent(msg), Arrays.asList(signed), fileRefs, Collections.emptySet()));
            }
            case Delete:
                break;
        }
        return Futures.of(new ChatUpdate(addToRecent(msg), Arrays.asList(signed), Collections.emptyList(), Collections.emptySet()));
    }

    public CompletableFuture<ChatUpdate> merge(String chatUid,
                                               Id mirrorHostId,
                                               SigningPrivateKeyAndPublicHash userIdentity,
                                               MessageStore mirrorStore,
                                               ContentAddressedStorage ipfs,
                                               Crypto crypto) {
        Member host = getMember(mirrorHostId);
        return mirrorStore.getMessagesFrom(host.messagesMergedUpto)
                .thenCompose(newMessages -> Futures.reduceAll(newMessages,
                        ChatUpdate.empty(this),
                        (u, msg) -> u.state.mergeMessage(chatUid, msg, u.state.getMember(mirrorHostId), userIdentity, ipfs, crypto)
                                .thenApply(u::apply),
                        (a, b) -> a.apply(b)));
    }

    private CompletableFuture<ChatUpdate> mergeMessage(String chatUid,
                                                       SignedMessage signed,
                                                       Member host,
                                                       SigningPrivateKeyAndPublicHash userIdentity,
                                                       ContentAddressedStorage ipfs,
                                                       Crypto crypto) {
        Member author = members.get(signed.msg.author);
        MessageEnvelope msg = signed.msg;
        if (! msg.timestamp.isBeforeOrEqual(current) && !author.removed) {
            // check signature
            return (author.chatIdentity.isPresent() ?
                    author.chatIdentity.get().getAndVerifyOwner(author.identity, ipfs) :
                    Futures.of(author.identity))
                    .thenCompose(hash -> ipfs.getSigningKey(hash, hash))
                    .thenCompose(signerOpt -> {
                        if (signerOpt.isEmpty())
                            throw new IllegalStateException("Couldn't retrieve public signing key!");
                        signerOpt.get().unsignMessage(ArrayOps.concat(signed.signature, signed.msg.serialize()));
                        return applyMessage(signed, chatUid, userIdentity, ipfs, crypto);
                    }).thenApply(u -> u.withState(u.state.mergeMessageTimestamp(msg.timestamp, host)));
        }
        return Futures.of(ChatUpdate.empty(incrementHost(host)));
    }

    private Chat incrementHost(Member source) {
        Map<Id, Member> updated = new HashMap<>(members);
        updated.put(source.id, source.incrementMessages());
        return new Chat(chatUid, host, current, updated, groupState, recentMessages);
    }

    private Chat mergeMessageTimestamp(TreeClock timestamp, Member source) {
        TreeClock newTime = current.merge(timestamp);
        Map<Id, Member> updated = new HashMap<>(members);
        updated.put(source.id, members.get(source.id).incrementMessages());
        updated.put(host, host().incrementMessages());
        return new Chat(chatUid, host, newTime, updated, groupState, recentMessages);
    }

    public CompletableFuture<ChatUpdate> join(Member host,
                                              OwnerProof chatId,
                                              PublicSigningKey chatIdPublic,
                                              SigningPrivateKeyAndPublicHash identity,
                                              MessageStore ourStore,
                                              ContentAddressedStorage ipfs,
                                              Crypto crypto) {
        Join joinMsg = new Join(host.username, host.identity, chatId, chatIdPublic);
        return withTime(current.withMember(host.id)).sendMessage(joinMsg, identity, identity, ourStore, ipfs, crypto);
    }

    public Chat copy(Member host) {
        if (! members.containsKey(host.id))
            throw new IllegalStateException("Only an invited member can mirror a conversation!");
        Map<Id, Member> clonedMembers = members.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().copy()));
        clonedMembers.put(host.id, host.copy());
        return new Chat(chatUid, host.id, current, clonedMembers, new HashMap<>(groupState), new ArrayList<>(recentMessages));
    }

    public CompletableFuture<ChatUpdate> inviteMember(String username,
                                                      PublicKeyHash identity,
                                                      SigningPrivateKeyAndPublicHash ourChatIdentity,
                                                      SigningPrivateKeyAndPublicHash userIdentity,
                                                      MessageStore ourStore,
                                                      ContentAddressedStorage ipfs,
                                                      Crypto crypto) {
        return inviteMembers(Arrays.asList(username), Arrays.asList(identity), ourChatIdentity, userIdentity, ourStore, ipfs, crypto);
    }

    public CompletableFuture<ChatUpdate> inviteMembers(List<String> usernames,
                                                       List<PublicKeyHash> identities,
                                                       SigningPrivateKeyAndPublicHash ourChatIdentity,
                                                       SigningPrivateKeyAndPublicHash userIdentity,
                                                       MessageStore ourStore,
                                                       ContentAddressedStorage ipfs,
                                                       Crypto crypto) {
        List<Integer> range = IntStream.range(0, usernames.size()).mapToObj(i -> i).collect(Collectors.toList());
        return Futures.reduceAll(range, ChatUpdate.empty(this),
                (u, i) -> {
                    String username = usernames.get(i);
                    PublicKeyHash identity = identities.get(i);

                    Member us = u.state.host();
                    Id newMember = u.state.host.fork(us.membersInvited);
                    Invite invite = new Invite(username, identity, newMember);

                    TreeClock newTime = u.state.current.withMember(newMember);
                    return u.state.withTime(newTime).sendMessage(invite, ourChatIdentity, userIdentity, ourStore, ipfs, crypto)
                            .thenApply(u::apply);
                },
                ChatUpdate::apply);
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("v", new CborObject.CborLong(0));
        result.put("i", new CborObject.CborString(chatUid));
        result.put("h", host);
        result.put("c", current);
        result.put("m", new CborObject.CborList(members));
        result.put("g", CborObject.CborMap.build(groupState.entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
        result.put("r", new CborObject.CborList(recentMessages));
        return CborObject.CborMap.build(result);
    }

    public static Chat fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor: " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        long version = m.getLong("v");
        String chatUid = m.getString("i");
        Id host = m.get("h", Id::fromCbor);
        TreeClock current = m.get("c", TreeClock::fromCbor);
        Map<Id, Member> members = m.getListMap("m", Id::fromCbor, Member::fromCbor);
        Map<String, GroupProperty> group = m.getMap("g", CborObject.CborString::getString, GroupProperty::fromCbor);
        List<MessageEnvelope> recent = new ArrayList<>(m.getList("r", MessageEnvelope::fromCbor));
        return new Chat(chatUid, host, current, members, group, recent);
    }

    public static Chat createNew(String uid, String username, PublicKeyHash identity) {
        Id creator = Id.creator();
        Member us = new Member(username, creator, identity, 0, 0);
        HashMap<Id, Member> members = new HashMap<>();
        members.put(creator, us);
        TreeClock zero = TreeClock.init(Arrays.asList(us.id));
        HashMap<String, GroupProperty> groupState = new HashMap<>();
        return new Chat(uid, creator, zero, members, groupState, new ArrayList<>());
    }

    public static List<Chat> createNew(String uid, List<String> usernames, List<PublicKeyHash> identities) {
        HashMap<Id, Member> members = new HashMap<>();
        List<Id> initialMembers = new ArrayList<>();

        for (int i=0; i < usernames.size(); i++) {
            Id id = new Id(i);
            initialMembers.add(id);
            Member member = new Member(usernames.get(i), id, identities.get(i), 0, 0);
            members.put(id, member);
        }
        TreeClock genesis = TreeClock.init(initialMembers);

        return initialMembers.stream()
                .map(id -> new Chat(uid, id, genesis, members.entrySet().stream()
                        .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().copy())), new HashMap<>(), new ArrayList<>()))
                .collect(Collectors.toList());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Chat chat = (Chat) o;
        return Objects.equals(chatUid, chat.chatUid) &&
                Objects.equals(host, chat.host) &&
                Objects.equals(current, chat.current) &&
                Objects.equals(members, chat.members) &&
                Objects.equals(groupState, chat.groupState) &&
                Objects.equals(recentMessages, chat.recentMessages);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chatUid, host, current, members, groupState, recentMessages);
    }
}