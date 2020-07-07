package peergos.shared.user;

import jsinterop.annotations.*;

import java.util.*;

public class ServerConversation implements Comparable<ServerConversation> {

    public final long latestEpochMillis;
    @JsProperty
    public final List<ServerMessage> messages;

    public ServerConversation(List<ServerMessage> messages) {
        this.messages = messages;
        long latest = 0;
        for (ServerMessage message : messages) {
            if (message.sentEpochMillis > latest)
                latest = message.sentEpochMillis;
        }
        this.latestEpochMillis = latest;
    }

    @Override
    public int compareTo(ServerConversation other) {
        return Long.compare(latestEpochMillis, other.latestEpochMillis);
    }
}
