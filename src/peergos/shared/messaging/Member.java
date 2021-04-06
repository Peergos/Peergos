package peergos.shared.messaging;

public class Member {
    public final String username;
    public final Id id;
    public long messagesMergedUpto;
    public int membersInvited;

    public Member(String username, Id id, long messagesMergedUpto, int membersInvited) {
        this.username = username;
        this.id = id;
        this.messagesMergedUpto = messagesMergedUpto;
        this.membersInvited = membersInvited;
    }

    public Member copy() {
        return new Member(username, id, messagesMergedUpto, membersInvited);
    }
}
