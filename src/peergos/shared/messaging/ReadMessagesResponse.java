package peergos.shared.messaging;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@JsType
public class ReadMessagesResponse implements Cborable {

    public final String chatId;
    public final boolean result;
    public final int startIndex;

    public final Map<String, String> authorMap;
    public final Map<String, String> attachmentMap;
    public final List<MessagePair> messagePairs;

    public ReadMessagesResponse(boolean result, String chatId, int startIndex,
                                Map<String, String> authorMap,
                                Map<String, String> attachmentMap,
                                List<MessagePair> messagePairs) {
        this.chatId = chatId;
        this.result = result;
        this.startIndex = startIndex;
        this.authorMap = authorMap;
        this.attachmentMap = attachmentMap;
        this.messagePairs = messagePairs;
    }
    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("c", new CborObject.CborString(chatId));
        result.put("r", new CborObject.CborBoolean(this.result));
        result.put("s", new CborObject.CborLong(startIndex));

        Map<String, Cborable> transformedAuthorMap = authorMap.entrySet()
                .stream()
                .collect(Collectors.toMap(
                        e -> e.getKey(),
                        e -> new CborObject.CborString(e.getValue()),
                        (a, b) -> a, TreeMap::new));
        result.put("a", CborObject.CborMap.build(transformedAuthorMap));

        Map<String, Cborable> transformedAttachmentMap = attachmentMap.entrySet()
                .stream()
                .collect(Collectors.toMap(
                        e -> e.getKey(),
                        e -> new CborObject.CborString(e.getValue()),
                        (a, b) -> a, TreeMap::new));
        result.put("t", CborObject.CborMap.build(transformedAttachmentMap));
        result.put("p", new CborObject.CborList(messagePairs));
        return CborObject.CborMap.build(result);
    }

    public static ReadMessagesResponse fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor: " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        String chatId = m.getString("c");
        Boolean result = m.getBoolean("r");
        Long startIndex = m.getLong("s");

        List<MessagePair> messagePairs = cbor instanceof CborObject.CborMap ?
                ((CborObject.CborMap) cbor).getList("p", MessagePair::fromCbor) :
                Collections.emptyList();

        Function<Cborable, String> fromString = e -> ((CborObject.CborString) e).value;
        Map<String, String> authorMap = ((CborObject.CborMap)m.get("a")).toMap(fromString, fromString);
        Map<String, String> attachmentMap = ((CborObject.CborMap)m.get("t")).toMap(fromString, fromString);
        return new ReadMessagesResponse(result, chatId, startIndex.intValue(), authorMap, attachmentMap, messagePairs);
    }
}
