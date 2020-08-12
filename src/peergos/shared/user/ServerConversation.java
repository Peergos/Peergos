package peergos.shared.user;

import jsinterop.annotations.*;

import java.util.*;

public class ServerConversation implements Comparable<ServerConversation> {

    public final long latestEpochMillis;
    @JsProperty
    public final List<ServerMessage> messages;
    public final boolean isDisplayable;

    public ServerConversation(List<ServerMessage> messages) {
        this.messages = messages;
        long latest = 0;
        for (ServerMessage message : messages) {
            if (message.sentEpochMillis > latest)
                latest = message.sentEpochMillis;
        }
        ServerMessage lastMessage = messages.get(messages.size() - 1);
        this.isDisplayable = !lastMessage.isDismissed && lastMessage.type != ServerMessage.Type.FromUser;
        this.latestEpochMillis = latest;
    }

    public ServerMessage lastMessage() {
        return messages.get(messages.size() - 1);
    }

    @Override
    public int compareTo(ServerConversation other) {
        return Long.compare(latestEpochMillis, other.latestEpochMillis);
    }

    public static List<ServerConversation> combine(List<ServerMessage> all) {
        Map<Long, List<ServerMessage>> lookup = new HashMap<>();
        List<ServerConversation> res = new ArrayList<>();
        for (ServerMessage msg : all) {
            List<ServerMessage> conv;
            if (msg.replyToId.isPresent()) {
                lookup.putIfAbsent(msg.replyToId.get(), new ArrayList<>());
                conv = lookup.get(msg.replyToId.get());
                lookup.put(msg.id, conv);
            } else {
                conv = lookup.computeIfAbsent(msg.id, x -> new ArrayList<>());
            }
            conv.add(msg);
        }
        new HashSet<>(lookup.values()).forEach(c -> res.add(new ServerConversation(c)));
        Collections.sort(res);
        return res;
    }
}
