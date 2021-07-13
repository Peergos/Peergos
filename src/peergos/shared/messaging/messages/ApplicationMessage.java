package peergos.shared.messaging.messages;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;
import peergos.shared.display.*;

import java.util.*;
import java.util.stream.Collectors;

@JsType
public class ApplicationMessage implements Message {
    public final List<? extends Content> body;

    public ApplicationMessage(List<? extends Content> body) {
        this.body = body;
    }

    @Override
    public String toString() {
        return body.stream().map(Object::toString).collect(Collectors.joining());
    }

    @Override
    public Type type() {
        return Type.Application;
    }

    @Override
    public CborObject.CborMap toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("c", new CborObject.CborLong(type().value));
        state.put("b", new CborObject.CborList(body));
        return CborObject.CborMap.build(state);
    }

    public static ApplicationMessage fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;

        List<Content> body = m.getList("b", Content::fromCbor);
        return new ApplicationMessage(body);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApplicationMessage that = (ApplicationMessage) o;
        return Objects.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(body);
    }

    public static ApplicationMessage text(String text) {
        return new ApplicationMessage(Collections.singletonList(new Text(text)));
    }

    public static ApplicationMessage attachment(String text, List<FileRef> attachments) {
        List<Reference> attachmentList = attachments.stream().map(a -> new Reference(a)).collect(Collectors.toList());
        List<Content> body = new ArrayList<>();
        body.add(new Text(text));
        body.addAll(attachmentList);
        return new ApplicationMessage(body);
    }
}
