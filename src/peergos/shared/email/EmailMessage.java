package peergos.shared.email;

import jsinterop.annotations.JsConstructor;
import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.user.fs.MimeTypes;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@JsType
public class EmailMessage implements Cborable {

    private static final String VERSION_1 = "1";

    public final String id;
    public final String msgId;
    public final String from;
    public final String subject;
    public final List<String> to;
    public final List<String> cc;
    public final List<String> bcc;
    public final String content;
    public final boolean unread;
    public final boolean star;
    public final LocalDateTime created;
    public final List<Attachment> attachments;
    public final String icalEvent;
    public final Optional<String> sendError;
    public final Optional<EmailMessage> replyingToEmail;
    public final Optional<EmailMessage> forwardingToEmail;

    @JsConstructor
    public EmailMessage(String id, String msgId, String from, String subject, LocalDateTime created,
                        List<String> to, List<String> cc, List<String> bcc,
                        String content, boolean unread, boolean star,
                        List<Attachment> attachments,
                        String icalEvent, Optional<EmailMessage> replyingToEmail, Optional<EmailMessage> forwardingToEmail,
                        Optional<String> sendError
    ) {
        this.id = id;
        this.msgId = msgId;
        this.from = from;
        this.subject = subject;
        this.created = created;
        this.to = to;
        this.cc = cc;
        this.bcc = bcc;
        this.content = content;
        this.unread = unread;
        this.star = star;
        this.attachments = new ArrayList<>(attachments);
        this.icalEvent = icalEvent == null ? "" : icalEvent;
        this.replyingToEmail = replyingToEmail;
        this.forwardingToEmail = forwardingToEmail;
        this.sendError = sendError;
    }
    public EmailMessage prepare(String generatedMsgId, String fromEmailAddress, LocalDateTime emailSent) {
        return new EmailMessage(id, generatedMsgId, fromEmailAddress, subject, emailSent, to, cc, bcc, content, unread, star,
                attachments, icalEvent, replyingToEmail, forwardingToEmail, sendError);
    }

    public EmailMessage withAttachments(List<Attachment> suppliedAttachments) {
        return new EmailMessage(id, msgId, from, subject, created, to, cc, bcc, content, unread, star,
                suppliedAttachments, icalEvent, replyingToEmail, forwardingToEmail, sendError);
    }

    public EmailMessage withError(String error) {
        return new EmailMessage(id, msgId, from, subject, created, to, cc, bcc, content, unread, star,
                attachments, icalEvent, replyingToEmail, forwardingToEmail, Optional.of(error));
    }

    public byte[] toBytes() {
        return this.serialize();
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("v", new CborObject.CborString(VERSION_1));
        state.put("i", new CborObject.CborString(id));
        state.put("m", new CborObject.CborString(msgId));
        state.put("f", new CborObject.CborString(from));
        state.put("h", new CborObject.CborString(subject));
        state.put("t", new CborObject.CborLong(created.toEpochSecond(ZoneOffset.UTC)));
        state.put("d", new CborObject.CborList(to.stream()
                .map(CborObject.CborString::new)
                .collect(Collectors.toList())));
        state.put("c", new CborObject.CborList(cc.stream()
                .map(CborObject.CborString::new)
                .collect(Collectors.toList())));
        state.put("b", new CborObject.CborList(bcc.stream()
                .map(CborObject.CborString::new)
                .collect(Collectors.toList())));
        state.put("z", new CborObject.CborString(content));
        state.put("u", new CborObject.CborBoolean(unread));
        state.put("s", new CborObject.CborBoolean(star));

        state.put("a", new CborObject.CborList(attachments));
        state.put("e", new CborObject.CborString(icalEvent));

        replyingToEmail.ifPresent(r -> state.put("r", replyingToEmail.get().toCbor()));
        forwardingToEmail.ifPresent(o -> state.put("o", forwardingToEmail.get().toCbor()));

        sendError.ifPresent(o -> state.put("x", new CborObject.CborString(sendError.get())));

        List<CborObject> withMimeType = new ArrayList<>();
        withMimeType.add(new CborObject.CborLong(MimeTypes.CBOR_PEERGOS_EMAIL_INT));
        withMimeType.add(CborObject.CborMap.build(state));

        return new CborObject.CborList(withMimeType);
    }

    public static EmailMessage fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor! " + cbor);
        CborObject.CborList withMimeType = (CborObject.CborList) cbor;
        long mimeType = withMimeType.getLong(0);
        if (mimeType != MimeTypes.CBOR_PEERGOS_EMAIL_INT)
            throw new IllegalStateException("Invalid mimetype for Email: " + mimeType);

        CborObject.CborMap m = withMimeType.get(1, c -> (CborObject.CborMap)c);

        String version = m.getString("v");
        if (! version.equals(VERSION_1)) {
            throw new IllegalStateException("Unsupported version:" + version);
        }
        String id = m.getString("i");
        String msgId = m.getString("m");
        String from = m.getString("f");
        String subject = m.getString("h");
        LocalDateTime created = m.get("t", c -> LocalDateTime.ofEpochSecond(((CborObject.CborLong)c).value, 0, ZoneOffset.UTC));
        List<String> to = m.getList("d", n -> ((CborObject.CborString)n).value);
        List<String> cc = m.getList("c", n -> ((CborObject.CborString)n).value);
        List<String> bcc = m.getList("b", n -> ((CborObject.CborString)n).value);
        String content = m.getString("z");
        boolean unread = m.getBoolean("u");
        boolean star = m.getBoolean("s");
        List<Attachment> attachments = m.getList("a", Attachment::fromCbor);
        String icalEvent = m.getString("e");

        Optional<EmailMessage> replyingToEmail = Optional.ofNullable(m.get("r"))
                .map(c -> EmailMessage.fromCbor(c));
        Optional<EmailMessage> forwardingToEmail = Optional.ofNullable(m.get("o"))
                .map(c -> EmailMessage.fromCbor(c));

        Optional<String> sendError = Optional.ofNullable(m.get("x"))
                .map(c -> m.getString("x"));

        return new EmailMessage(id, msgId, from, subject, created, to, cc, bcc, content, unread, star, attachments, icalEvent,
                replyingToEmail, forwardingToEmail, sendError);
    }
}
