package peergos.shared.messaging;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.messaging.messages.Message;

import java.util.*;

@JsType
public class SendMessageRequest implements Cborable {

    public final Optional<MessageEnvelope> messageToReplyTo;
    public final Message message;
    public SendMessageRequest(Message message, Optional<MessageEnvelope> messageToReplyTo) {
        this.message = message;
        this.messageToReplyTo = messageToReplyTo;
    }
    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("m", message);
        if (messageToReplyTo.isPresent()) {
            result.put("r", messageToReplyTo.get());
        }
        return CborObject.CborMap.build(result);
    }

    public static SendMessageRequest fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor: " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        Message message = m.get("m", peergos.shared.messaging.messages.Message::fromCbor);
        Optional<MessageEnvelope> messageToReplyTo = m.containsKey("r")
                ? Optional.of(m.get("r", peergos.shared.messaging.MessageEnvelope::fromCbor))
                : Optional.empty();
        return new SendMessageRequest(message, messageToReplyTo);
    }
}
