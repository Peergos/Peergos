package peergos.shared.messaging;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.messaging.messages.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class Chat implements Cborable {

    public final Member host;
    public TreeClock current;
    public final Map<Id, Member> members;
    public final Map<String, GroupProperty> groupState;

    public Chat(Member host, TreeClock current, Map<Id, Member> members, Map<String, GroupProperty> groupState) {
        this.host = host;
        this.current = current;
        this.members = members;
        this.groupState = groupState;
    }

    public Member getMember(Id id) {
        return members.get(id);
    }

    public Member getMember(String username) {
        return members.values().stream().filter(m -> m.username.equals(username)).findFirst().get();
    }

    public CompletableFuture<MessageEnvelope> addApplicationMessage(ApplicationMessage body,
                                                                    SigningPrivateKeyAndPublicHash signer,
                                                                    MessageStore store) {
        return addMessage(body, signer, store);
    }

    public CompletableFuture<MessageEnvelope> addMessage(Message body,
                                                         SigningPrivateKeyAndPublicHash signer,
                                                         MessageStore store) {
        TreeClock msgTime = current.increment(host.id);
        MessageEnvelope msg = new MessageEnvelope(host.id, msgTime, body);
        current = msgTime;
        byte[] signature = signer.secret.signatureOnly(msg.serialize());
        members.get(host.id).messagesMergedUpto++;
        return store.addMessage(new SignedMessage(signature, msg)).thenApply(x -> msg);
    }

    public CompletableFuture<Boolean> merge(Id mirrorHostId,
                                            MessageStore mirrorStore,
                                            MessageStore ourStore,
                                            ContentAddressedStorage ipfs,
                                            Function<Chat, CompletableFuture<Boolean>> committer) {
        Member host = getMember(mirrorHostId);
        return mirrorStore.getMessagesFrom(host.messagesMergedUpto)
                .thenCompose(newMessages -> Futures.reduceAll(newMessages, true,
                        (b, msg) -> mergeMessage(msg, host, ourStore, ipfs, committer), (a, b) -> a && b));
    }

    private CompletableFuture<Boolean> mergeMessage(SignedMessage signed,
                                                    Member host,
                                                    MessageStore ourStore,
                                                    ContentAddressedStorage ipfs,
                                                    Function<Chat, CompletableFuture<Boolean>> committer) {
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
                            throw new IllegalStateException("Couldn't retrieve public siging key!");
                        signerOpt.get().unsignMessage(ArrayOps.concat(signed.signature, signed.msg.serialize()));

                        switch(msg.payload.type()) {
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
                                        return true;
                                    });
                                }
                                break;
                            case GroupState: {
                                SetGroupState update = (SetGroupState) msg.payload;
                                GroupProperty existing = groupState.get(update.key);
                                if (existing == null ||
                                        existing.updateTimestamp.isBeforeOrEqual(msg.timestamp) ||
                                        (existing.updateTimestamp.isConcurrentWith(msg.timestamp) &&
                                                msg.author.compareTo(existing.author) < 0)) {
                                    groupState.put(update.key, new GroupProperty(msg.author, msg.timestamp, update.value));
                                }
                                break;
                            }
                            case Application:
                                break;
                        }
                        return Futures.of(true);
                    }).thenCompose(b -> ourStore.addMessage(signed)
                            .thenCompose(x -> {
                                current = current.merge(msg.timestamp);
                                host.messagesMergedUpto++;
                                return committer.apply(this);
                            }));
        }
        host.messagesMergedUpto++;
        return committer.apply(this);
    }

    public CompletableFuture<Boolean> join(Member host,
                                           OwnerProof chatId,
                                           PublicSigningKey chatIdPublic,
                                           SigningPrivateKeyAndPublicHash identity,
                                           MessageStore ourStore,
                                           Function<Chat, CompletableFuture<Boolean>> committer) {
        Join joinMsg = new Join(host.username, host.identity, chatId, chatIdPublic);
        return addMessage(joinMsg, identity, ourStore)
                .thenApply(x -> {
                    this.host.chatIdentity = Optional.of(chatId);
                    members.put(this.host.id, this.host);
                    return true;
                }).thenCompose(x -> committer.apply(this));
    }

    public Chat copy(Member host) {
        if (! members.containsKey(host.id))
            throw new IllegalStateException("Only an invited member can mirror a conversation!");
        Map<Id, Member> clonedMembers = members.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().copy()));
        return new Chat(host.copy(), current, clonedMembers, new HashMap<>(groupState));
    }

    public CompletableFuture<Member> inviteMember(String username,
                                                  PublicKeyHash identity,
                                                  SigningPrivateKeyAndPublicHash ourChatIdentity,
                                                  MessageStore ourStore,
                                                  Function<Chat, CompletableFuture<Boolean>> committer) {
        Id newMember = host.id.fork(host.membersInvited);
        Member member = new Member(username, newMember, identity, host.messagesMergedUpto, 0);
        host.membersInvited++;
        members.put(newMember, member);
        current = current.withMember(newMember);
        Invite invite = new Invite(username, identity);
        return addMessage(invite, ourChatIdentity, ourStore)
                .thenCompose(x -> committer.apply(this))
                .thenApply(x -> member);
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
        Member host = m.get("h", Member::fromCbor);
        TreeClock current = m.get("c", TreeClock::fromCbor);
        Map<Id, Member> members = m.getListMap("m", Id::fromCbor, Member::fromCbor);
        Map<String, GroupProperty> group = m.getMap("g", CborObject.CborString::getString, GroupProperty::fromCbor);
        return new Chat(host, current, members, group);
    }

    public static Chat createNew(String username, PublicKeyHash identity) {
        Id creator = Id.creator();
        Member us = new Member(username, creator, identity, 0, 0);
        HashMap<Id, Member> members = new HashMap<>();
        members.put(creator, us);
        TreeClock zero = TreeClock.init(Arrays.asList(us.id));
        return new Chat(us, zero, members, new HashMap<>());
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
                .map(id -> new Chat(members.get(id), genesis, members.entrySet().stream()
                        .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().copy())), new HashMap<>()))
                .collect(Collectors.toList());
    }
}