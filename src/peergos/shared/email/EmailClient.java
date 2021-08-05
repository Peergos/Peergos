package peergos.shared.email;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 *  All email data is stored under $BASE = /$username/.apps/email/data
 *  Emails are stored in #BASE/{inbox, sent, $custom...}
 *  Attachments are stored in #BASE/attachments
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

    public CompletableFuture<Boolean> send(EmailMessage msg, List<PendingAttachment> attachments) {
        if (msg.attachments.size() > 0 || attachments.size() > 0) {
            throw new IllegalStateException("Unimplemented");
        }
        return saveEmail("pending/outbox", msg, msg.id);
    }

    @JsMethod
    public CompletableFuture<List<EmailMessage>> getIncoming() {
        Path inbox = Paths.get("default", "pending", "inbox");
        List<EmailMessage> res = new ArrayList<>();
        return emailApp.dirInternal(inbox, null)
                .thenApply(filenames -> filenames.stream().filter(n -> n.endsWith(".cbor")).collect(Collectors.toList()))
                .thenCompose(filenames -> Futures.reduceAll(filenames, true,
                        (r, n) -> emailApp.readInternal(inbox.resolve(n), null)
                                .thenApply(bytes -> SourcedAsymmetricCipherText.fromCbor(CborObject.fromByteArray(bytes)))
                                .thenApply(this::decryptEmail)
                                .thenApply(m -> res.add(m)),
                        (a, b) -> b))
                .thenApply(x -> res);
    }

    private CompletableFuture<List<EmailMessage>> processPending() {
        throw new IllegalStateException("Unimplemented");
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
    public static CompletableFuture<EmailClient> initialise(Crypto crypto, App emailApp) {
        List<String> dirs = Arrays.asList("inbox","sent","pending", "attachments",
                "pending/inbox", "pending/outbox", "pending/sent",
                "pending/inbox/attachments", "pending/outbox/attachments", "pending/sent/attachments");
        String account = "default";
        return Futures.reduceAll(dirs, true,
                (b, d) -> emailApp.createDirectoryInternal(Paths.get(account + "/" + d), null),
                (a, b) -> a && b).thenCompose(x -> {
            BoxingKeyPair encryptionKeys = BoxingKeyPair.random(crypto.random, crypto.boxer);
            return emailApp.writeInternal(Paths.get(account, ENCRYPTION_KEYPAIR_PATH), encryptionKeys.serialize(), null)
                    .thenCompose(b -> emailApp.writeInternal(Paths.get(account, "pending", PUBLIC_KEY_FILENAME),
                            encryptionKeys.publicBoxingKey.serialize(), null))
                    .thenApply(b -> new EmailClient(emailApp, crypto, encryptionKeys));
        });
    }

    @JsMethod
    public static CompletableFuture<EmailClient> load(App emailApp, Crypto crypto) {
        return emailApp.dirInternal(Paths.get(""), null)
                .thenCompose(children -> {
                    if (children.contains(ENCRYPTION_KEYPAIR_PATH)) {
                        return emailApp.readInternal(Paths.get(ENCRYPTION_KEYPAIR_PATH), null)
                                .thenApply(bytes -> BoxingKeyPair.fromCbor(CborObject.fromByteArray(bytes)))
                                .thenApply(keys -> new EmailClient(emailApp, crypto, keys));
                    }

                    return initialise(crypto, emailApp);
                });
    }

    @JsMethod
    public static CompletableFuture<Snapshot> connectToBridge(String bridgeUsername, UserContext context) {
        Path pendingDir = App.getDataDir("email", context.username)
                .resolve(Paths.get("default", "pending"));
        return context.sendInitialFollowRequest(bridgeUsername)
                .thenCompose(x -> context.shareWriteAccessWith(pendingDir, Collections.singleton(bridgeUsername)));
    }
}
