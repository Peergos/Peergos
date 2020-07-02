package peergos.shared.user;

import jsinterop.annotations.JsType;

import java.time.LocalDateTime;
@JsType
public class Message {
    public final String Id;
    public final LocalDateTime dateTime;
    public final String contents;
    public boolean acknowledged;
    public Message(String Id, LocalDateTime dateTime, String contents, boolean acknowledged) {
        this.Id = Id;
        this.dateTime = dateTime;
        this.contents = contents;
        this.acknowledged = acknowledged;
    }
}
