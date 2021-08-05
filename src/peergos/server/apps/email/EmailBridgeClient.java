package peergos.server.apps.email;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.email.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class EmailBridgeClient {

    private final String clientUsername;
    private final UserContext context;
    private final PublicBoxingKey encryptionTarget;

    public EmailBridgeClient(String clientUsername, UserContext context, PublicBoxingKey encryptionTarget) {
        this.clientUsername = clientUsername;
        this.context = context;
        this.encryptionTarget = encryptionTarget;
    }

    private Path pendingPath() {
        return App.getDataDir("email", clientUsername).resolve(Paths.get("default", "pending"));
    }

    public List<String> listOutbox() {
        return context.getChildren(pendingPath().resolve("outbox").toString()).join()
                .stream()
                .filter(f -> ! f.isDirectory())
                .map(FileWrapper::getName)
                .collect(Collectors.toList());
    }

    public Pair<FileWrapper, EmailMessage> getPendingEmail(String filename) {
        FileWrapper emailFile = context.getByPath(pendingPath().resolve("outbox").resolve(filename)).join().get();
        EmailMessage email = Serialize.parse(emailFile, EmailMessage::fromCbor, context.network, context.crypto).join();
        return new Pair<>(emailFile, email);
    }

    public void encryptAndMoveEmailToSent(FileWrapper file, EmailMessage m) {
        Path outboxPath = pendingPath().resolve("outbox");
        Path sentPath = pendingPath().resolve("sent");
        context.network.synchronizer.applyComplexUpdate(file.owner(), file.signingPair(), (s, c) -> {

            FileWrapper sent = context.getByPath(sentPath.toString(), s).join().get();

            byte[] rawCipherText = encryptEmail(m).serialize();
            return sent.uploadFileSection(s, c, file.getName(), AsyncReader.build(rawCipherText), false, 0,
                    rawCipherText.length, Optional.empty(), true, true,
                    context.network, context.crypto, x -> {}, context.crypto.random.randomBytes(32));
        }).join();
        // TODO do this inside the update above and make atomic
        FileWrapper original = file.getUpdated(context.network).join();
        FileWrapper outbox = context.getByPath(outboxPath).join().get();
        original.remove(original, outboxPath.resolve(file.getName()), context).join();
    }

    public void addToInbox(EmailMessage m) {
        Path inboxPath = pendingPath().resolve("inbox");
        FileWrapper inbox = context.getByPath(inboxPath).join().get();
        context.network.synchronizer.applyComplexUpdate(inbox.owner(), inbox.signingPair(), (s, c) -> {
            byte[] rawCipherText = encryptEmail(m).serialize();
            return inbox.getUpdated(s, context.network).join()
                    .uploadFileSection(s, c, m.id + ".cbor", AsyncReader.build(rawCipherText), false, 0,
                            rawCipherText.length, Optional.empty(), true, true,
                            context.network, context.crypto, x -> {}, context.crypto.random.randomBytes(32));
        }).join();
    }

    private SourcedAsymmetricCipherText encryptEmail(EmailMessage m) {
        BoxingKeyPair tmp = BoxingKeyPair.random(context.crypto.random, context.crypto.boxer);
        return SourcedAsymmetricCipherText.build(tmp, encryptionTarget, m);
    }

    private SourcedAsymmetricCipherText encryptAttachment(byte[] fileData) {
        BoxingKeyPair tmp = BoxingKeyPair.random(context.crypto.random, context.crypto.boxer);
        return SourcedAsymmetricCipherText.build(tmp, encryptionTarget, new CborObject.CborByteArray(fileData));
    }

    private static PublicBoxingKey getEncryptionTarget(UserContext context, String clientUsername) {
        Path base = App.getDataDir("email", clientUsername);
        FileWrapper keyFile = context.getByPath(base.resolve(Paths.get("default", "pending", "encryption.publickey.cbor"))).join().get();
        return Serialize.parse(keyFile, PublicBoxingKey::fromCbor, context.network, context.crypto).join();
    }

    public static EmailBridgeClient build(UserContext context, String clientUsername) {
        return new EmailBridgeClient(clientUsername, context, getEncryptionTarget(context, clientUsername));
    }
}
