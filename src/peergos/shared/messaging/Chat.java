package peergos.shared.messaging;

import java.util.*;
import java.util.stream.*;

public class Chat {

    public final Member us;
    public TreeClock current;
    public final Map<Id, Member> members;
    public final List<Message> messages;

    public Chat(Member us, TreeClock current, Map<Id, Member> members, List<Message> messages) {
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

    public List<Message> getMessagesFrom(long index) {
        return messages.subList((int) index, messages.size());
    }

    public Message addMessage(byte[] body) {
        TreeClock msgTime = current.increment(us.id);
        Message msg = new Message(msgTime, body);
        current = msgTime;
        messages.add(msg);
        return msg;
    }

    public void merge(Chat mirror) {
        Member host = getMember(mirror.us.id);
        List<Message> newMessages = mirror.getMessagesFrom(host.messagesMergedUpto);

        for (Message msg : newMessages) {
            mergeMessage(msg, host);
        }
    }

    private void mergeMessage(Message msg, Member host) {
        if (! msg.timestamp.isBeforeOrEqual(current)) {
            messages.add(msg);
            Set<Id> newMembers = msg.timestamp.newMembersFrom(current);
            for (Id newMember : newMembers) {
                long indexIntoParent = getMember(newMember.parent()).messagesMergedUpto;
                members.put(newMember, new Member(new String(msg.payload), newMember, indexIntoParent, 0));
            }
            current = current.merge(msg.timestamp);
        }
        host.messagesMergedUpto++;
    }

    public Chat createCopy(Member host) {
        if (! members.containsKey(host.id))
            throw new IllegalStateException("Only an invited member can mirror a conversation!");
        Map<Id, Member> clonedMembers = members.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().copy()));
        return new Chat(host, current, clonedMembers, new ArrayList<>(messages));
    }

    public Member inviteMember(String username) {
        Id newMember = us.id.fork(us.membersInvited);
        Member member = new Member(username, newMember, us.messagesMergedUpto, 0);
        members.put(newMember, member);
        current = current.withMember(newMember).increment(us.id);
        messages.add(new Message(current, username.getBytes()));
        return member;
    }

    public static Chat createNew(String username) {
        Id creator = Id.creator();
        Member us = new Member(username, creator, 0, 0);
        HashMap<Id, Member> members = new HashMap<>();
        members.put(creator, us);
        TreeClock zero = TreeClock.init(Arrays.asList(us.id));
        return new Chat(us, zero, members, new ArrayList<>());
    }

    public static Chat createNew(List<String> usernames) {
        HashMap<Id, Member> members = new HashMap<>();
        List<Id> initialMembers = new ArrayList<>();

        for (int i=0; i < usernames.size(); i++) {
            Id id = new Id(i);
            initialMembers.add(id);
            Member member = new Member(usernames.get(i), id, 0, 0);
            members.put(id, member);
        }
        TreeClock genesis = TreeClock.init(initialMembers);
        return new Chat(members.get(initialMembers.get(0)), genesis, members, new ArrayList<>());
    }
}