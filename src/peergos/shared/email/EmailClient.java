package peergos.shared.email;

import peergos.client.PathUtils;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.user.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

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

    private final UserContext context;
    private final BoxingKeyPair encryptionKeys;
    private final App emailApp;

    public EmailClient(App emailApp, UserContext context, BoxingKeyPair encryptionKeys) {
        this.emailApp = emailApp;
        this.context = context;
        this.encryptionKeys = encryptionKeys;
    }

    private Path getBase() {
        return getBase(context.username);
    }

    public static Path getBase(String username) {
        return Paths.get(username, ".apps", "email", "data");
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
        return saveEmail("pending/outbox", msg.toBytes(), msg.id);
    }

    public CompletableFuture<List<EmailMessage>> getIncoming(String folder) {
        throw new IllegalStateException("Unimplemented");
    }

    private CompletableFuture<List<EmailMessage>> processPending() {
        throw new IllegalStateException("Unimplemented");
    }

    public static EmailClient initialise(UserContext context, App emailApp) {
        BoxingKeyPair encryptionKeys = BoxingKeyPair.random(context.crypto.random, context.crypto.boxer);
        return new EmailClient(emailApp, context, encryptionKeys);
    }
    public static EmailClient load(UserContext context) {
        App emailApp = App.init(context, "email").join();
        boolean isInit = isInitialised(emailApp).join();
        if (isInit) {
            return initialise(context, emailApp);
        } else {
            throw new IllegalStateException("Please use UI to setup email");
        }
    }
    private static CompletableFuture<Boolean> isInitialised(App email) {
        Path filePath = Paths.get("default", "App.config");
        return email.readInternal(filePath, null).thenApply(data -> {
            Map<String, String> props = (Map<String, String>) JSONParser.parse(new String(data));
            return props.containsKey("emailBridgeUser") && props.containsKey("sharedPendingDirectory");
        }).exceptionally(throwable -> {
            return false;
        });
    }
    private CompletableFuture<Boolean> saveEmail(String folder, byte[] bytes, String id) {
        String fullFolderPath = "default/" + folder + "/" + id + ".cbor";
        String[] folderDirs = fullFolderPath.split("/");
        Path filePath = peergos.client.PathUtils.directoryToPath(folderDirs);
        return emailApp.writeInternal(filePath, bytes, context.username);
    }
}
