package peergos.shared.messaging;

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
import java.util.function.*;
import java.util.stream.*;

public class Chat implements Cborable {

    public final Id host;
    public TreeClock current;
    public final Map<Id, Member> members;
    public final Map<String, GroupProperty> groupState;
    private final List<MessageEnvelope> recentMessages;

    public Chat(Id host,
                TreeClock current,
                Map<Id, Member> members,
                Map<String, GroupProperty> groupState,
                List<MessageEnvelope> recentMessages) {
        this.host = host;
        this.current = current;
        this.members = members;
        this.groupState = groupState;
        this.recentMessages = recentMessages;
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
        return members.values().stream().filter(m -> m.username.equals(username)).findFirst().get();
    }

    public CompletableFuture<MessageEnvelope> addMessage(Message body,
                                                         SigningPrivateKeyAndPublicHash signer,
                                                         MessageStore store,
                                                         Hasher hasher) {
        TreeClock msgTime = current.increment(host);
        boolean nonEmpty = host().messagesMergedUpto > 0;
        return (nonEmpty ?
                store.getMessages(host().messagesMergedUpto - 1, host().messagesMergedUpto) :
                Futures.of(Collections.<SignedMessage>emptyList()))
                .thenCompose(recent -> Futures.combineAllInOrder(recent.stream()
                        .map(s -> hasher.bareHash(s.msg.serialize())
                                .thenApply(MessageRef::new))
                        .collect(Collectors.toList())))
                .thenCompose(recentRefs -> {
                    MessageEnvelope msg = new MessageEnvelope(host, msgTime, LocalDateTime.now(ZoneOffset.UTC), recentRefs, body);
                    current = msgTime;
                    byte[] signature = signer.secret.signatureOnly(msg.serialize());
                    host().messagesMergedUpto++;
                    long msgCount = host().messagesMergedUpto;
                    return store.addMessage(msgCount - 1, new SignedMessage(signature, msg)).thenApply(x -> msg);
                });
    }

    public synchronized List<MessageEnvelope> getRecent() {
        return new ArrayList<>(recentMessages);
    }

    private synchronized boolean addToRecent(MessageEnvelope m) {
        if (recentMessages.size() >= 10)
            recentMessages.remove(0);
        recentMessages.add(m);
        return true;
    }

    /** Apply message to this Chat's state
     *
     * @param msg
     * @return
     */
    public CompletableFuture<Boolean> apply(MessageEnvelope msg,
                                            String chatUid,
                                            Function<FileRef, CompletableFuture<Boolean>> mediaCopier,
                                            MessageStore ourStore,
                                            ContentAddressedStorage ipfs) {
        Member author = members.get(msg.author);
        switch (msg.payload.type()) {
            case Invite: {
                Set<Id> newMembers = current.newMembersFrom(msg.timestamp);
                for (Id newMember : newMembers) {
                    long indexIntoParent = getMember(newMember.parent()).messagesMergedUpto;
                    Invite invite = (Invite) msg.payload;
                    String username = invite.username;
                    PublicKeyHash identity = invite.identity;
                    members.put(newMember, new Member(username, newMember, identity, indexIntoParent, 0));
                }
                break;
            }
            case Join:
                if (author.chatIdentity.isEmpty()) {
                    // This is a Join message from a new member
                    Join join = (Join)msg.payload;
                    OwnerProof chatIdentity = join.chatIdentity;
                    if (!chatIdentity.ownedKey.equals(author.identity))
                        throw new IllegalStateException("Identity keys don't match!");
                    // verify signature
                    return chatIdentity.getOwner(ipfs).thenApply(x -> {
                        members.put(author.id, author.withChatId(chatIdentity));
                        addToRecent(msg);
                        return true;
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
                    groupState.put(update.key, new GroupProperty(msg.author, msg.timestamp, update.value));
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
                // mirror media to our storage
                List<CompletableFuture<Boolean>> mirroredMedia = fileRefs.stream().parallel()
                        .map(mediaCopier)
                        .collect(Collectors.toList());
                return Futures.combineAll(mirroredMedia).thenApply(x -> addToRecent(msg));
            }
            case RemoveMember: {
                RemoveMember rem = (RemoveMember) msg.payload;
                if (!rem.chatUid.equals(chatUid))
                    return Futures.of(true); // ignore message from incorrect chat
                // anyone can remove themselves
                // an admin can remove anyone
                if (rem.memberToRemove.equals(msg.author) || getAdmins().contains(author.username)) {
                    String username = getMember(rem.memberToRemove).username;
                    members.get(rem.memberToRemove).removed = true;
                    // revoke read access to shared chat state from removee
                    return ourStore.revokeAccess(username).thenApply(b -> addToRecent(msg));
                }
                break;
            }
            case ReplyTo:
            case Delete:
                break;
        }
        return Futures.of(addToRecent(msg));
    }

    public CompletableFuture<Boolean> merge(String chatUid,
                                            Id mirrorHostId,
                                            MessageStore mirrorStore,
                                            MessageStore ourStore,
                                            ContentAddressedStorage ipfs,
                                            Function<Chat, CompletableFuture<Boolean>> committer,
                                            Function<FileRef, CompletableFuture<Boolean>> mediaCopier) {
        Member host = getMember(mirrorHostId);
        return mirrorStore.getMessagesFrom(host.messagesMergedUpto)
                .thenCompose(newMessages -> Futures.reduceAll(newMessages, true,
                        (b, msg) -> mergeMessage(chatUid, msg, host, ourStore, ipfs, committer, mediaCopier), (a, b) -> a && b));
    }

    private CompletableFuture<Boolean> mergeMessage(String chatUid,
                                                    SignedMessage signed,
                                                    Member host,
                                                    MessageStore ourStore,
                                                    ContentAddressedStorage ipfs,
                                                    Function<Chat, CompletableFuture<Boolean>> committer,
                                                    Function<FileRef, CompletableFuture<Boolean>> mediaCopier) {
        Member author = members.get(signed.msg.author);
        MessageEnvelope msg = signed.msg;
        if (! msg.timestamp.isBeforeOrEqual(current)) {
            // check signature
            return (author.chatIdentity.isPresent() ?
                    author.chatIdentity.get().getOwner(ipfs) :
                    Futures.of(author.identity))
                    .thenCompose(ipfs::getSigningKey)
                    .thenCompose(signerOpt -> {
                        if (signerOpt.isEmpty())
                            throw new IllegalStateException("Couldn't retrieve public signing key!");
                        signerOpt.get().unsignMessage(ArrayOps.concat(signed.signature, signed.msg.serialize()));
                        return apply(msg, chatUid, mediaCopier, ourStore, ipfs);
                    }).thenCompose(b -> ourStore.addMessage(host().messagesMergedUpto, signed)
                            .thenCompose(x -> {
                                current = current.merge(msg.timestamp);
                                host.messagesMergedUpto++;
                                host().messagesMergedUpto++;
                                return committer.apply(this);
                            }))
                    .exceptionally(t -> {
                        t.printStackTrace();
                        return true;
                    });
        }
        host.messagesMergedUpto++;
        return committer.apply(this);
    }

    public CompletableFuture<Boolean> join(Member host,
                                           OwnerProof chatId,
                                           PublicSigningKey chatIdPublic,
                                           SigningPrivateKeyAndPublicHash identity,
                                           MessageStore ourStore,
                                           Function<Chat, CompletableFuture<Boolean>> committer,
                                           Hasher hasher) {
        Join joinMsg = new Join(host.username, host.identity, chatId, chatIdPublic);
        return addMessage(joinMsg, identity, ourStore, hasher)
                .thenApply(x -> {
                    this.host().chatIdentity = Optional.of(chatId);
                    members.put(this.host, this.host());
                    return true;
                }).thenCompose(x -> committer.apply(this));
    }

    public Chat copy(Member host) {
        if (! members.containsKey(host.id))
            throw new IllegalStateException("Only an invited member can mirror a conversation!");
        Map<Id, Member> clonedMembers = members.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().copy()));
        clonedMembers.put(host.id, host.copy());
        return new Chat(host.id, current, clonedMembers, new HashMap<>(groupState), new ArrayList<>(recentMessages));
    }

    public CompletableFuture<Member> inviteMember(String username,
                                                  PublicKeyHash identity,
                                                  SigningPrivateKeyAndPublicHash ourChatIdentity,
                                                  MessageStore ourStore,
                                                  Function<Chat, CompletableFuture<Boolean>> committer,
                                                  Hasher hasher) {
        Id newMember = host.fork(host().membersInvited);
        Member member = new Member(username, newMember, identity, host().messagesMergedUpto, 0);
        host().membersInvited++;
        members.put(newMember, member);
        current = current.withMember(newMember);
        Invite invite = new Invite(username, identity);
        return addMessage(invite, ourChatIdentity, ourStore, hasher)
                .thenCompose(x -> committer.apply(this))
                .thenApply(x -> member);
    }

    public CompletableFuture<List<Member>> inviteMembers(List<String> usernames,
                                                         List<PublicKeyHash> identities,
                                                         SigningPrivateKeyAndPublicHash ourChatIdentity,
                                                         MessageStore ourStore,
                                                         Function<Chat, CompletableFuture<Boolean>> committer,
                                                         Hasher hasher) {
        List<Member> newMembers = new ArrayList<>();
        List<Invite> invites = new ArrayList<>();
        for (int i=0; i < usernames.size(); i++) {
            String username = usernames.get(i);
            PublicKeyHash identity = identities.get(i);
            Id newMember = host.fork(host().membersInvited);
            Member member = new Member(username, newMember, identity, host().messagesMergedUpto, 0);
            newMembers.add(member);
            host().membersInvited++;
            members.put(newMember, member);
            current = current.withMember(newMember);
            Invite invite = new Invite(username, identity);
            invites.add(invite);
        }
        return Futures.reduceAll(invites, true,
                (b, m) -> addMessage(m, ourChatIdentity, ourStore, hasher)
                        .thenApply(x -> true),
                (a, b) -> b)
                .thenCompose(x -> committer.apply(this))
                .thenApply(x -> newMembers);
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("v", new CborObject.CborLong(0));
        result.put("h", host);
        result.put("c", current);
        result.put("m", new CborObject.CborList(members));
        result.put("g", CborObject.CborMap.build(groupState.entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
        return CborObject.CborMap.build(result);
    }

    public static Chat fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor: " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        long version = m.getLong("v");
        Id host = m.get("h", Id::fromCbor);
        TreeClock current = m.get("c", TreeClock::fromCbor);
        Map<Id, Member> members = m.getListMap("m", Id::fromCbor, Member::fromCbor);
        Map<String, GroupProperty> group = m.getMap("g", CborObject.CborString::getString, GroupProperty::fromCbor);
        List<MessageEnvelope> recent = m.getList("r", MessageEnvelope::fromCbor);
        return new Chat(host, current, members, group, recent);
    }

    public static Chat createNew(String username, PublicKeyHash identity) {
        Id creator = Id.creator();
        Member us = new Member(username, creator, identity, 0, 0);
        HashMap<Id, Member> members = new HashMap<>();
        members.put(creator, us);
        TreeClock zero = TreeClock.init(Arrays.asList(us.id));
        HashMap<String, GroupProperty> groupState = new HashMap<>();
        return new Chat(creator, zero, members, groupState, new ArrayList<>());
    }

    public static List<Chat> createNew(List<String> usernames, List<PublicKeyHash> identities) {
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
                .map(id -> new Chat(id, genesis, members.entrySet().stream()
                        .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().copy())), new HashMap<>(), new ArrayList<>()))
                .collect(Collectors.toList());
    }
}