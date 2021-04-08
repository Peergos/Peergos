package peergos.shared.messaging;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.stream.*;

public class Chat {

    public final Member us;
    public TreeClock current;
    public final Map<Id, Member> members;
    public final List<SignedMessage> messages;

    public Chat(Member us, TreeClock current, Map<Id, Member> members, List<SignedMessage> messages) {
        this.us = us;
        this.current = current;
        this.members = members;
        this.messages = messages;
    }

    public Collection<Member> getMembers() {
        return members.values();
    }

    public Member getMember(Id id) {
        return members.get(id);
    }

    public List<SignedMessage> getMessagesFrom(long index) {
        return messages.subList((int) index, messages.size());
    }

    public Message addMessage(byte[] body, SigningPrivateKeyAndPublicHash signer) {
        TreeClock msgTime = current.increment(us.id);
        Message msg = new Message(us.id, msgTime, body);
        current = msgTime;
        byte[] signature = signer.secret.signatureOnly(msg.serialize());
        messages.add(new SignedMessage(signature, msg));
        return msg;
    }

    public void merge(Chat mirror, ContentAddressedStorage ipfs) {
        Member host = getMember(mirror.us.id);
        List<SignedMessage> newMessages = mirror.getMessagesFrom(host.messagesMergedUpto);

        for (SignedMessage msg : newMessages) {
            mergeMessage(msg, host, ipfs);
        }
    }

    private void mergeMessage(SignedMessage signed, Member host, ContentAddressedStorage ipfs) {
        Member author = members.get(signed.msg.author);
        Message msg = signed.msg;
        if (! msg.timestamp.isBeforeOrEqual(current)) {
            // check signature
            PublicSigningKey signer = ipfs.getSigningKey(author.chatIdentity.map(p -> p.getOwner(ipfs).join()).orElse(author.identity)).join().get();
            signer.unsignMessage(ArrayOps.concat(signed.signature, signed.msg.serialize()));

            Set<Id> newMembers = current.newMembersFrom(msg.timestamp);
            for (Id newMember : newMembers) {
                long indexIntoParent = getMember(newMember.parent()).messagesMergedUpto;
                Message.Invite invite = Message.Invite.fromCbor(CborObject.fromByteArray(msg.payload));
                String username = invite.username;
                PublicKeyHash identity = invite.identity;
                members.put(newMember, new Member(username, newMember, identity, indexIntoParent, 0));
            }
            if (author.chatIdentity.isEmpty()) {
                // This is a Join message from a new member
                Message.Join join = Message.Join.fromCbor(CborObject.fromByteArray(msg.payload));
                OwnerProof chatIdentity = join.chatIdentity;
                if (! chatIdentity.ownedKey.equals(author.identity))
                    throw new IllegalStateException("Identity keys don't match!");
                // verify signature
                PublicKeyHash chatId = chatIdentity.getOwner(ipfs).join();
                members.put(author.id, author.withChatId(chatIdentity));
            }
            messages.add(signed);
            current = current.merge(msg.timestamp);
        }
        host.messagesMergedUpto++;
    }

    public void join(Member host, OwnerProof chatId, SigningPrivateKeyAndPublicHash identity) {
        Message.Join joinMsg = new Message.Join(host.username, host.identity, chatId);
        addMessage(joinMsg.serialize(), identity);
        us.chatIdentity = Optional.of(chatId);
        members.put(us.id, us);
    }

    public Chat copy(Member host) {
        if (! members.containsKey(host.id))
            throw new IllegalStateException("Only an invited member can mirror a conversation!");
        Map<Id, Member> clonedMembers = members.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().copy()));
        return new Chat(host.copy(), current, clonedMembers, new ArrayList<>(messages));
    }

    public Member inviteMember(String username, PublicKeyHash identity, SigningPrivateKeyAndPublicHash ourChatIdentity) {
        Id newMember = us.id.fork(us.membersInvited);
        Member member = new Member(username, newMember, identity, us.messagesMergedUpto, 0);
//        us.membersInvited++;
        members.put(newMember, member);
        current = current.withMember(newMember);
        Message.Invite invite = new Message.Invite(username, identity);
        addMessage(invite.serialize(), ourChatIdentity);
        return member;
    }

    public static Chat createNew(String username, PublicKeyHash identity) {
        Id creator = Id.creator();
        Member us = new Member(username, creator, identity, 0, 0);
        HashMap<Id, Member> members = new HashMap<>();
        members.put(creator, us);
        TreeClock zero = TreeClock.init(Arrays.asList(us.id));
        return new Chat(us, zero, members, new ArrayList<>());
    }

    public static Chat createNew(List<String> usernames, List<PublicKeyHash> identities) {
        HashMap<Id, Member> members = new HashMap<>();
        List<Id> initialMembers = new ArrayList<>();

        for (int i=0; i < usernames.size(); i++) {
            Id id = new Id(i);
            initialMembers.add(id);
            Member member = new Member(usernames.get(i), id, identities.get(i), 0, 0);
            members.put(id, member);
        }
        TreeClock genesis = TreeClock.init(initialMembers);
        return new Chat(members.get(initialMembers.get(0)), genesis, members, new ArrayList<>());
    }
}