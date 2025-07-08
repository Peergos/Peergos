package peergos.server.apps.email;

import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.email.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.*;

public class EmailBridgeClient {

    private final String clientUsername;
    private final NetworkAccess network;
    private final Crypto crypto;
    private final UserContext clientWritableContext;
    private final PublicBoxingKey encryptionTarget;
    private static final Path emailDataDir = PathUtil.get(".apps", "email", "data");

    public EmailBridgeClient(String clientUsername, UserContext clientWritableContext, PublicBoxingKey encryptionTarget) {
        this.clientUsername = clientUsername;
        this.clientWritableContext = clientWritableContext;
        this.network = clientWritableContext.network;
        this.crypto = clientWritableContext.crypto;
        this.encryptionTarget = encryptionTarget;
    }

    private FileWrapper pendingFolder() {
        String pendingPath = clientWritableContext.getEntryPath().join();
        return clientWritableContext.getByPath(pendingPath).join().get();
    }

    public List<String> listOutbox() {
        return pendingFolder().getChild("outbox", crypto.hasher, network).join().get()
                .getChildren(crypto.hasher, network).join()
                .stream()
                .filter(f -> ! f.isDirectory())
                .map(FileWrapper::getName)
                .collect(Collectors.toList());
    }

    public Pair<FileWrapper, EmailMessage> getPendingEmail(String filename) {
        FileWrapper outboxFolder = pendingFolder().getChild("outbox", crypto.hasher, network).join().get();
        FileWrapper emailFile = outboxFolder.getChild(filename, crypto.hasher, network).join().get();
        EmailMessage email = Serialize.parse(emailFile, EmailMessage::fromCbor, network, crypto).join();
        return new Pair<>(emailFile, email);
    }

    public byte[] getOutgoingAttachment(String filename) {
        FileWrapper outboxFolder = pendingFolder().getChild("outbox", crypto.hasher, network).join().get();
        FileWrapper attachmentsFolder = outboxFolder.getChild("attachments", crypto.hasher, network).join().get();
        FileWrapper file = attachmentsFolder.getChild(filename, crypto.hasher, network).join().get();
        return Serialize.readFully(file, crypto, network).join();
    }

    public void encryptAndMoveEmailToSent(FileWrapper file, EmailMessage emailMessage, Map<String, byte[]> attachmentsMap) {
        Path pendingPath = App.getDataDir("email", clientUsername).resolve(PathUtil.get("default", "pending"));
        Path outboxPath = pendingPath.resolve("outbox");

        FileWrapper outbox = pendingFolder().getChild("outbox", crypto.hasher, network).join().get();
        FileWrapper sent = pendingFolder().getChild("sent", crypto.hasher, network).join().get();
        byte[] rawCipherText = encryptEmail(emailMessage).join().serialize();
        sent.uploadFileSection(file.getName(), AsyncReader.build(rawCipherText), false, 0, rawCipherText.length, Optional.empty(),
                true, network, crypto, () -> false, x -> {}).join();

        // TODO do this inside the update above and make atomic
        FileWrapper original = file.getUpdated(network).join();
        original.remove(outbox, outboxPath.resolve(file.getName()), clientWritableContext).join();
        //move attachments
        List<Attachment> allAttachments = new ArrayList(emailMessage.attachments);
        if (emailMessage.forwardingToEmail.isPresent()) {
            allAttachments.addAll(emailMessage.forwardingToEmail.get().attachments);
        }
        FileWrapper sentAttachments = sent.getUpdated(network).join().getChild("attachments", crypto.hasher, network).join().get();
        for(Attachment attachment : allAttachments) {
            byte[] bytes = attachmentsMap.get(attachment.uuid);
            if (bytes != null) {
                FileWrapper outboxAttachmentDir = outbox.getChild("attachments", crypto.hasher, network).join().get();
                FileWrapper attachmentFile = outboxAttachmentDir.getChild(attachment.uuid, crypto.hasher, network).join().get();
                Path attachmentFilePath = pendingPath.resolve(PathUtil.get("outbox", "attachments", attachment.uuid));
                byte[] rawAttachmentCipherText = encryptAttachment(bytes).join().serialize();
                sentAttachments.uploadFileSection(attachment.uuid, AsyncReader.build(rawAttachmentCipherText),
                        false, 0, rawAttachmentCipherText.length, Optional.empty(),
                        true, network, crypto, () -> false, x -> {}).join();
                attachmentFile.remove(outboxAttachmentDir, attachmentFilePath, clientWritableContext).join();
            }
        }
    }

    public Attachment uploadAttachment(String filename, int size, String type, byte[] data) {
        int dotIndex = filename.lastIndexOf('.');
        String fileExtension = dotIndex > -1 && dotIndex <= filename.length() -1
                ?  filename.substring(dotIndex + 1) : "";
        byte[] rawCipherText = encryptAttachment(data).join().serialize();
        AsyncReader.ArrayBacked reader = new AsyncReader.ArrayBacked(rawCipherText);
        String uuid = uploadAttachment(reader, fileExtension, rawCipherText.length).join();
        return new Attachment(filename, size, type, uuid);
    }

    private CompletableFuture<String> uploadAttachment(AsyncReader reader, String fileExtension,
                                                       int length) {
        String uuid = UUID.randomUUID().toString() + "." + fileExtension;

        FileWrapper inbox = pendingFolder().getChild("inbox", crypto.hasher, network).join().get();
        FileWrapper baseDir = inbox.getChild("attachments", crypto.hasher, network).join().get();
        return baseDir.uploadAndReturnFile(uuid, reader, length, false, () -> false, l -> {},
                        baseDir.mirrorBatId(), network, crypto)
                        .thenApply(hash -> uuid);
    }

    public void addToInbox(EmailMessage m) {
        FileWrapper inbox = pendingFolder().getChild("inbox", crypto.hasher, network).join().get();
        network.synchronizer.applyComplexUpdate(inbox.owner(), inbox.signingPair(), (s, c) -> {
            byte[] rawCipherText = encryptEmail(m).join().serialize();
            return inbox.getUpdated(s, network).join()
                    .uploadFileSection(s, c, m.id + ".cbor", AsyncReader.build(rawCipherText), false, 0,
                            rawCipherText.length, Optional.empty(), false, true, true,
                            network, crypto, () -> false, x -> {}, crypto.random.randomBytes(32),
                            Optional.empty(), Optional.of(Bat.random(crypto.random)), inbox.mirrorBatId());
        }).join();
    }

    private CompletableFuture<SourcedAsymmetricCipherText> encryptEmail(EmailMessage m) {
        BoxingKeyPair tmp = BoxingKeyPair.randomCurve25519(crypto.random, crypto.boxer);
        return SourcedAsymmetricCipherText.build(tmp, encryptionTarget, m);
    }

    private CompletableFuture<SourcedAsymmetricCipherText> encryptAttachment(byte[] fileData) {
        BoxingKeyPair tmp = BoxingKeyPair.randomCurve25519(crypto.random, crypto.boxer);
        return SourcedAsymmetricCipherText.build(tmp, encryptionTarget, new CborObject.CborByteArray(fileData));
    }

    private static PublicBoxingKey getEncryptionTarget(NetworkAccess network, Crypto crypto, FileWrapper pendingDirectory) {
        FileWrapper keyFile = pendingDirectory.getChild("encryption.publickey.cbor", crypto.hasher, network).join().get();
        return Serialize.parse(keyFile, PublicBoxingKey::fromCbor, network, crypto).join();
    }

    public static EmailBridgeClient build(SecretLink emailConfigFileLink, NetworkAccess network, Crypto crypto, String clientUsername, String clientEmailAddress) {

        UserContext writableContext = UserContext.fromSecretLinkV2(emailConfigFileLink.toLink(), () -> Futures.of(""),
                network, crypto).join();
        String pendingPath = writableContext.getEntryPath().join();
        Optional<FileWrapper> emailFile = writableContext.getByPath(pendingPath + "/" + "email.json").join();
        if (emailFile.isEmpty()) {
            String contents = "{ \"email\": \"" + clientEmailAddress + "\"}";
            byte[] data = contents.getBytes();
            FileWrapper pendingDirectory = writableContext.getByPath(pendingPath).join().get();
            pendingDirectory.uploadOrReplaceFile("email.json", new AsyncReader.ArrayBacked(data), data.length, network,
                    crypto, () -> false, l -> {}).join();
        }
        return new EmailBridgeClient(clientUsername, writableContext, getEncryptionTarget(network, crypto, writableContext.getByPath(pendingPath).join().get()));
    }
}
