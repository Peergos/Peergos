package peergos.shared.email;

import jsinterop.annotations.*;
import peergos.client.PathUtils;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.user.*;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 *  All email data is stored under $BASE = /$username/.apps/email/data/$ACCOUNT
 *  $ACCOUNT = 'default' until multiple email addresses are supported
 *  Emails are stored in #BASE/{inbox, sent, $custom...}
 *  Attachments are stored in $BASE/attachments
 *  The email bridge has write access to $BASE/pending
 *  Attachments are encrypted in $BASE/pending/inbox/attachments and $BASE/pending/sent/attachments
 *  Attachments are non encrypted in $BASE/pending/outbox/attachments
 *
 *  INCOMING:
 *  The bridge encrypts incoming emails and writes to $BASE/pending/inbox,
 *  putting attachments in $BASE/pending/inbox/attachments
 *  At this point a breach of the bridge server can't read anything.
 *  The client then decrypts and moves emails and attachments to $BASE/inbox and $BASE/attachments
 *
 *  OUTGOING:
 *  The client puts unencrypted emails and attachments in $BASE/pending/outbox and $BASE/pending/outbox/attachments
 *  The bridge sends each email from outbox and writes the encrypted result in $BASE/pending/sent and $BASE/pending/sent/attachments
 *  The client decrypts and moves emails and attachments from $BASE/pending/sent to $BASE/sent and $BASE/attachments
 *
 *  Attachments filenames are uuids. Email file names are uuids.
 *  The public key for the bridge to encrypt things to is at $BASE/pending/encryption.publickey.cbor,
 *  the full key pair is in $BASE/encryption.keypair.cbor
 *
 *  When emails/attachments are encrypted they will be in the format of SourcedAsymmetricCipherText
 *  This means we can only decrypt attachments that we can fit into RAM, but that should be fine as entire email (including all attachments) is limited
 *  to ~25 MiB anyway.
 */
public class EmailClient {
    private static final String ENCRYPTION_KEYPAIR_PATH = "encryption.keypair.cbor";
    private static final String PUBLIC_KEY_FILENAME = "encryption.publickey.cbor";
    private static final String CLIENT_EMAIL_FILENAME = "email.json"; //Email bridge will write client's email address to this file

    private final Crypto crypto;
    private final BoxingKeyPair encryptionKeys;
    private final App emailApp;

    public EmailClient(App emailApp, Crypto crypto, BoxingKeyPair encryptionKeys) {
        this.emailApp = emailApp;
        this.crypto = crypto;
        this.encryptionKeys = encryptionKeys;
    }

    private EmailMessage decryptEmail(SourcedAsymmetricCipherText cipherText) {
        return cipherText.decrypt(encryptionKeys.secretBoxingKey, EmailMessage::fromCbor);
    }

    private byte[] decryptAttachment(SourcedAsymmetricCipherText cipherText) {
        return cipherText.decrypt(encryptionKeys.secretBoxingKey, c -> ((CborObject.CborByteArray)c).value);
    }

    private CompletableFuture<Boolean> uploadForwardedAttachments(EmailMessage data) {
        CompletableFuture<Boolean> future = peergos.shared.util.Futures.incomplete();
        if (data.forwardingToEmail.isEmpty()) {
            future.complete(true);
        } else {
            this.reduceMovingForwardedAttachments(data.forwardingToEmail.get().attachments, 0, future);
        }
        return future;
    }
    private CompletableFuture<Boolean> reduceMovingForwardedAttachments(List<Attachment>attachments, int index, CompletableFuture<Boolean> future) {
        if (index >= attachments.size()) {
            future.complete(true);
            return future;
        } else {
            Attachment attachment = attachments.get(index);
            String srcDirStr = "default/attachments/" + attachment.uuid;
            Path srcFilePath = PathUtils.directoryToPath(srcDirStr.split("/"));
            return emailApp.readInternal(srcFilePath, null).thenCompose(bytes -> {
                    String destDirStr = "default/pending/outbox/attachments/" + attachment.uuid;
                    Path destFilePath = PathUtils.directoryToPath(destDirStr.split("/"));
                    return emailApp.writeInternal(destFilePath, bytes, null).thenCompose(res ->
                        reduceMovingForwardedAttachments(attachments, index +1, future)
                    );
            });
        }
    }

    @JsMethod
    public CompletableFuture<String> uploadAttachment(byte[] attachment) {
        String uuid = UUID.randomUUID().toString();
        Path outboundAttachment = PathUtil.get("default", "pending", "outbox", "attachments", uuid);
        return emailApp.writeInternal(outboundAttachment, attachment, null)
                .thenApply(x -> uuid);
    }

    @JsMethod
    public CompletableFuture<Boolean> send(EmailMessage msg) {
        return uploadForwardedAttachments(msg).thenCompose(res ->
            saveEmail("pending/outbox", msg, msg.id)
        );
    }

    @JsMethod
    public CompletableFuture<List<EmailMessage>> getNewIncoming() {
        Path inbox = PathUtil.get("default", "pending", "inbox");
        return listFiles(inbox);
    }

    @JsMethod
    public CompletableFuture<List<EmailMessage>> getNewSent() {
        Path inbox = PathUtil.get("default", "pending", "sent");
        return listFiles(inbox);
    }

    @JsMethod
    public CompletableFuture<byte[]> getAttachment(String uid) {
        Path attachment = PathUtil.get("default", "attachments", uid);
        return emailApp.readInternal(attachment, null);
    }

    public CompletableFuture<List<EmailMessage>> listFiles(Path internalPath) {
        List<EmailMessage> res = new ArrayList<>();
        return emailApp.dirInternal(internalPath, null)
                .thenApply(filenames -> filenames.stream().filter(n -> n.endsWith(".cbor")).collect(Collectors.toList()))
                .thenCompose(filenames -> Futures.reduceAll(filenames, true,
                        (r, n) -> emailApp.readInternal(internalPath.resolve(n), null)
                                .thenApply(bytes -> SourcedAsymmetricCipherText.fromCbor(CborObject.fromByteArray(bytes)))
                                .thenApply(this::decryptEmail)
                                .thenApply(m -> res.add(m)),
                        (a, b) -> b))
                .thenApply(x -> res);
    }

    @JsMethod
    public CompletableFuture<Boolean> moveToPrivateSent(EmailMessage emailMessage) {
        CompletableFuture<Boolean> future = Futures.incomplete();
        return reduceMovingAttachmentsToFolder(emailMessage.attachments, "sent", 0, future).thenCompose( res ->
            moveToPrivateDir("default", emailMessage, PathUtil.get("default", "pending", "sent").resolve(emailMessage.id + ".cbor"))
        );
    }

    @JsMethod
    public CompletableFuture<Boolean> moveToPrivateInbox(EmailMessage emailMessage) {
        CompletableFuture<Boolean> future = Futures.incomplete();
        return reduceMovingAttachmentsToFolder(emailMessage.attachments, "inbox",0, future).thenCompose( res ->
             moveToPrivateDir("default", emailMessage, PathUtil.get("default", "pending", "inbox").resolve(emailMessage.id + ".cbor"))
        );
    }

    private CompletableFuture<Boolean> reduceMovingAttachmentsToFolder(List<Attachment> attachments,
                                                                       String folder,
                                                                       int index,
                                                                       CompletableFuture<Boolean> future) {
        if (index >= attachments.size()) {
            future.complete(true);
            return future;
        } else {
            Attachment attachment = attachments.get(index);
            Path srcFilePath = PathUtil.get("default", "pending", folder, "attachments", attachment.uuid);
            Path destFilePath = PathUtil.get("default", "attachments", attachment.uuid);

            return Futures.asyncExceptionally(() -> emailApp.readInternal(srcFilePath, null).thenCompose(bytes -> {
                        SourcedAsymmetricCipherText cipherText = SourcedAsymmetricCipherText.fromCbor(CborObject.fromByteArray(bytes));
                        return emailApp.writeInternal(destFilePath, decryptAttachment(cipherText), null).thenCompose(res ->
                                emailApp.deleteInternal(srcFilePath, null).thenCompose(bool ->
                                        reduceMovingAttachmentsToFolder(attachments, folder, index + 1, future)
                                )
                        );
                    }),
                    // If the read failed because it has already been copied, we have nothing to do
                    t -> emailApp.readInternal(destFilePath, null).thenApply(x -> true));
        }
    }

    public CompletableFuture<Boolean> moveToPrivateDir(String account, EmailMessage m, Path original) {
        Path dirAndFile = original.subpath(original.getNameCount() - 2, original.getNameCount());
        Path dest = PathUtil.get(account).resolve(dirAndFile);
        // TODO make this move atomic
        return emailApp.writeInternal(dest, m.serialize(), null)
                .thenCompose(b -> emailApp.deleteInternal(original, null));
    }

    private CompletableFuture<Boolean> saveEmail(String folder, EmailMessage email, String id) {
        Path filePath = PathUtil.get("default", folder, id + ".cbor");
        return emailApp.writeInternal(filePath, email.serialize(), null);
    }

    /** Setup all the necessary directories, generate key pair, and store public key separately for bridge to read
     *  N.B. The pending directory still needs to be shared with the email user after initialization.
     *
     * @param crypto
     * @param emailApp
     * @return
     */
    public static CompletableFuture<EmailClient> initialise(Crypto crypto, App emailApp) {
        List<String> dirs = Arrays.asList("inbox","sent","pending", "attachments",
                "pending/inbox", "pending/outbox", "pending/sent",
                "pending/inbox/attachments", "pending/outbox/attachments", "pending/sent/attachments");
        String account = "default";
        return Futures.reduceAll(dirs, true,
                (b, d) -> emailApp.createDirectoryInternal(PathUtil.get(account, d), null),
                (a, b) -> a && b).thenCompose(x -> {
            BoxingKeyPair encryptionKeys = BoxingKeyPair.random(crypto.random, crypto.boxer);
            return emailApp.writeInternal(PathUtil.get(account, ENCRYPTION_KEYPAIR_PATH), encryptionKeys.serialize(), null)
                    .thenCompose(b -> emailApp.writeInternal(PathUtil.get(account, "pending", PUBLIC_KEY_FILENAME),
                            encryptionKeys.publicBoxingKey.serialize(), null))
                    .thenApply(b -> new EmailClient(emailApp, crypto, encryptionKeys));
        });
    }

    @JsMethod
    public CompletableFuture<Optional<String>> getEmailAddress() {
        Path relativeEmailPath = PathUtil.get("default", "pending", CLIENT_EMAIL_FILENAME);
        return emailApp.readInternal(relativeEmailPath, null).thenApply(data -> {
            Map<String, String> props = (Map<String, String>) JSONParser.parse(new String(data));
            String email = props.get("email");
            return Optional.of(email);
        }).exceptionally(t -> Optional.empty());
    }

    @JsMethod
    public static CompletableFuture<EmailClient> load(App emailApp, Crypto crypto) {
        String account = "default";
        return emailApp.dirInternal(PathUtil.get(account), null)
                .thenCompose(children -> {
                    if (children.contains(ENCRYPTION_KEYPAIR_PATH)) {
                        return emailApp.readInternal(PathUtil.get(account, ENCRYPTION_KEYPAIR_PATH), null)
                                .thenApply(bytes -> BoxingKeyPair.fromCbor(CborObject.fromByteArray(bytes)))
                                .thenApply(keys -> new EmailClient(emailApp, crypto, keys));
                    }

                    return initialise(crypto, emailApp);
                });
    }

    @JsMethod
    public CompletableFuture<Snapshot> connectToBridge(UserContext context, String bridgeUsername) {
        Path pendingDir = App.getDataDir("email", context.username)
                .resolve(PathUtil.get("default", "pending"));
        return context.sendInitialFollowRequest(bridgeUsername)
                .thenCompose(x -> context.shareWriteAccessWith(pendingDir, Collections.singleton(bridgeUsername)));
    }
}
