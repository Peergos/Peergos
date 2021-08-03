package peergos.shared.email;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.concurrent.*;

public class EmailBridgeClient {

    private final String clientUsername;
    private final UserContext context;
    private final PublicBoxingKey encryptionTarget;

    public EmailBridgeClient(String clientUsername, UserContext context, PublicBoxingKey encryptionTarget) {
        this.clientUsername = clientUsername;
        this.context = context;
        this.encryptionTarget = encryptionTarget;
    }

    private SourcedAsymmetricCipherText encryptEmail(EmailMessage m) {
        BoxingKeyPair tmp = BoxingKeyPair.random(context.crypto.random, context.crypto.boxer);
        return SourcedAsymmetricCipherText.build(tmp, encryptionTarget, m);
    }

    private SourcedAsymmetricCipherText encryptAttachment(byte[] fileData) {
        BoxingKeyPair tmp = BoxingKeyPair.random(context.crypto.random, context.crypto.boxer);
        return SourcedAsymmetricCipherText.build(tmp, encryptionTarget, new CborObject.CborByteArray(fileData));
    }

    private static CompletableFuture<PublicBoxingKey> getEncryptionTarget(UserContext context, String clientUsername) {
        Path base = EmailClient.getBase(clientUsername);
        return context.getByPath(base.resolve(Paths.get("pending", "encryption.publickey.cbor")))
                .thenCompose(kopt -> Serialize.parse(kopt.get(), PublicBoxingKey::fromCbor, context.network, context.crypto));
    }

    public static CompletableFuture<EmailBridgeClient> build(UserContext context, String clientUsername) {
        return getEncryptionTarget(context, clientUsername)
                .thenApply(pub -> new EmailBridgeClient(clientUsername, context, pub));
    }
}
