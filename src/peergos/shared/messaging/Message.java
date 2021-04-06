package peergos.shared.messaging;

import java.util.*;

public class Message {
    public final TreeClock timestamp;
    public final byte[] payload;

    public Message(TreeClock timestamp, byte[] payload) {
        this.timestamp = timestamp;
        this.payload = payload;
    }

    @Override
    public String toString() {
        return timestamp + " - " + new String(payload);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Message message = (Message) o;
        return Objects.equals(timestamp, message.timestamp) && Arrays.equals(payload, message.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(timestamp);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }
}
