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
    private static final Path emailDataDir = Paths.get(".apps", "email", "data");

    private final Crypto crypto;
    private final BoxingKeyPair encryptionKeys;
    private final App emailApp;
    private final UserContext context;

    public EmailClient(App emailApp, Crypto crypto, BoxingKeyPair encryptionKeys, UserContext context) {
        this.emailApp = emailApp;
        this.crypto = crypto;
        this.encryptionKeys = encryptionKeys;
        this.context = context;
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
    public CompletableFuture<String> uploadAttachment(AsyncReader reader, String fileExtension, int length,
                                                             ProgressConsumer<Long> monitor) {
        String uuid = UUID.randomUUID().toString() + "." + fileExtension;
        Path outboundAttachmentDir = Paths.get("default", "pending", "outbox", "attachments");
        Path baseDir = Paths.get(context.username).resolve(emailDataDir).resolve(outboundAttachmentDir);
        return context.getByPath(baseDir)
                .thenCompose(dir -> dir.get().uploadAndReturnFile(uuid, reader, length, false, monitor,
                        context.network, context.crypto)
                        .thenApply(hash -> uuid));
    }

    @JsMethod
    public CompletableFuture<Optional<FileWrapper>> retrieveAttachment( String uuid) {
        Path attachmentDir = Paths.get("default", "attachments", uuid);
        Path path = Paths.get(context.username).resolve(emailDataDir).resolve(attachmentDir);
        return context.getByPath(path);
    }

    @JsMethod
    public CompletableFuture<Boolean> send(EmailMessage msg) {
        return uploadForwardedAttachments(msg).thenCompose(res ->
            saveEmail("pending/outbox", msg, msg.id)
        );
    }

    @JsMethod
    public CompletableFuture<List<EmailMessage>> getNewIncoming() {
        Path inbox = Paths.get("default", "pending", "inbox");
        return listFiles(inbox);
    }

    @JsMethod
    public CompletableFuture<List<EmailMessage>> getNewSent() {
        Path inbox = Paths.get("default", "pending", "sent");
        return listFiles(inbox);
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
    public CompletableFuture<Boolean> moveToPrivateSent(EmailMessage m) {
        return moveToPrivateDir("default", m, Paths.get("default", "pending", "sent").resolve(m.id + ".cbor"));
    }

    @JsMethod
    public CompletableFuture<Boolean> moveToPrivateInbox(EmailMessage emailMessage) {
        CompletableFuture<Boolean> future = Futures.incomplete();
        return reduceMovingAttachmentToFolder(emailMessage.attachments, 0, future).thenCompose( res ->
             moveToPrivateDir("default", emailMessage, Paths.get("default", "pending", "inbox").resolve(emailMessage.id + ".cbor"))
        );
    }

    private CompletableFuture<Boolean> reduceMovingAttachmentToFolder(List<Attachment> attachments, int index, CompletableFuture<Boolean> future) {
        if (index >= attachments.size()) {
            future.complete(true);
            return future;
        } else {
            Attachment attachment = attachments.get(index);
            String srcDirStr = "default/pending/inbox/attachments/" + attachment.uuid;
            Path srcFilePath = PathUtils.directoryToPath(srcDirStr.split("/"));
            return emailApp.readInternal(srcFilePath, null).thenCompose(bytes -> {
                SourcedAsymmetricCipherText cipherText = SourcedAsymmetricCipherText.fromCbor(CborObject.fromByteArray(bytes));
                String destDirStr = "default/attachments/" + attachment.uuid;
                Path destFilePath = PathUtils.directoryToPath(destDirStr.split("/"));
                return emailApp.writeInternal(destFilePath, decryptAttachment(cipherText), null).thenCompose(res ->
                        emailApp.deleteInternal(srcFilePath, null).thenCompose(bool ->
                                reduceMovingAttachmentToFolder(attachments, index + 1, future)
                        )
                );
            });
        }
    }

    public CompletableFuture<Boolean> moveToPrivateDir(String account, EmailMessage m, Path original) {
        Path dirAndFile = original.subpath(original.getNameCount() - 2, original.getNameCount());
        Path dest = Paths.get(account).resolve(dirAndFile);
        // TODO make this move atomic
        return emailApp.writeInternal(dest, m.serialize(), null)
                .thenCompose(b -> emailApp.deleteInternal(original, null));
    }

    private CompletableFuture<Boolean> saveEmail(String folder, EmailMessage email, String id) {
        String fullFolderPath = "default/" + folder + "/" + id + ".cbor";
        String[] folderDirs = fullFolderPath.split("/");
        Path filePath = peergos.client.PathUtils.directoryToPath(folderDirs);
        return emailApp.writeInternal(filePath, email.serialize(), null);
    }

    /** Setup all the necessary directories, generate key pair, and store public key separately for bridge to read
     *  N.B. The pending directory still needs to be shared with the email user after initialization.
     *
     * @param crypto
     * @param emailApp
     * @return
     */
    public static CompletableFuture<EmailClient> initialise(Crypto crypto, App emailApp, UserContext context) {
        List<String> dirs = Arrays.asList("inbox","sent","pending", "attachments",
                "pending/inbox", "pending/outbox", "pending/sent",
                "pending/inbox/attachments", "pending/outbox/attachments", "pending/sent/attachments");
        String account = "default";
        return Futures.reduceAll(dirs, true,
                (b, d) -> emailApp.createDirectoryInternal(Paths.get(account, d), null),
                (a, b) -> a && b).thenCompose(x -> {
            BoxingKeyPair encryptionKeys = BoxingKeyPair.random(crypto.random, crypto.boxer);
            return emailApp.writeInternal(Paths.get(account, ENCRYPTION_KEYPAIR_PATH), encryptionKeys.serialize(), null)
                    .thenCompose(b -> emailApp.writeInternal(Paths.get(account, "pending", PUBLIC_KEY_FILENAME),
                            encryptionKeys.publicBoxingKey.serialize(), null))
                    .thenApply(b -> new EmailClient(emailApp, crypto, encryptionKeys, context));
        });
    }

    @JsMethod
    public CompletableFuture<Optional<String>> getEmailAddress() {
        Path relativeEmailPath = Paths.get("default", "pending", CLIENT_EMAIL_FILENAME);
        Path emailPath = App.getDataDir("email", context.username)
                .resolve(relativeEmailPath);
        return context.getByPath(emailPath).thenCompose(emailFile -> {
            if (emailFile.isPresent()) {
                return emailApp.readInternal(relativeEmailPath, null).thenApply(data -> {
                    Map<String, String> props = (Map<String, String>) JSONParser.parse(new String(data));
                    String email = props.get("email");
                    return Optional.of(email);
                });
            } else {
                return Futures.of(Optional.empty());
            }
        });
    }
    @JsMethod
    public static CompletableFuture<EmailClient> load(App emailApp, Crypto crypto, UserContext context) {
        String account = "default";
        return emailApp.dirInternal(Paths.get(account), null)
                .thenCompose(children -> {
                    if (children.contains(ENCRYPTION_KEYPAIR_PATH)) {
                        return emailApp.readInternal(Paths.get(account, ENCRYPTION_KEYPAIR_PATH), null)
                                .thenApply(bytes -> BoxingKeyPair.fromCbor(CborObject.fromByteArray(bytes)))
                                .thenApply(keys -> new EmailClient(emailApp, crypto, keys, context));
                    }

                    return initialise(crypto, emailApp, context);
                });
    }

    @JsMethod
    public CompletableFuture<Snapshot> connectToBridge(String bridgeUsername) {
        Path pendingDir = App.getDataDir("email", context.username)
                .resolve(Paths.get("default", "pending"));
        return context.sendInitialFollowRequest(bridgeUsername)
                .thenCompose(x -> context.shareWriteAccessWith(pendingDir, Collections.singleton(bridgeUsername)));
    }
}
