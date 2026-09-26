package peergos.shared.storage;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public interface SpaceUsage extends QuotaControl {

    CompletableFuture<Long> getUsage(PublicKeyHash owner, byte[] signedTime, boolean local);

    /** Set or remove the cap on the space used by a writing space and all the writing spaces it owns.
     *
     * @param signedRequest a WriterQuotaRequest signed by the owner's identity key
     */
    CompletableFuture<Boolean> setWriterQuota(PublicKeyHash owner, byte[] signedRequest);

    default CompletableFuture<Boolean> setWriterQuota(SigningPrivateKeyAndPublicHash identity, PublicKeyHash writer, Optional<Long> bytes) {
        WriterQuotaRequest req = new WriterQuotaRequest(identity.publicKeyHash, writer, bytes, System.currentTimeMillis());
        return identity.secret.signMessage(req.serialize())
                .thenCompose(signed -> setWriterQuota(identity.publicKeyHash, signed));
    }

    static String writerQuotasPath() {
        return Constants.SPACE_USAGE_URL + "writer-quotas";
    }

    static String writerUsagePath(PublicKeyHash owner, PublicKeyHash writer) {
        return Constants.SPACE_USAGE_URL + "writer-usage/" + owner + "/" + writer;
    }

    /**
     * @param signedRequest a TimeLimitedClient.SignedRequest for writerQuotasPath() signed by the owner's identity key
     * @return every capped writing space of the owner
     */
    CompletableFuture<List<WriterUsageInfo>> getWriterQuotas(PublicKeyHash owner, byte[] signedRequest);

    default CompletableFuture<List<WriterUsageInfo>> getWriterQuotas(SigningPrivateKeyAndPublicHash identity) {
        return new TimeLimitedClient.SignedRequest(writerQuotasPath(), System.currentTimeMillis())
                .sign(identity.secret)
                .thenCompose(signed -> getWriterQuotas(identity.publicKeyHash, signed));
    }

    /**
     * @param signedRequest a TimeLimitedClient.SignedRequest for writerUsagePath(owner, writer) signed by the writer,
     *                      or by the owner's identity key
     */
    CompletableFuture<WriterUsageInfo> getWriterUsage(PublicKeyHash owner, PublicKeyHash writer, byte[] signedRequest);

    default CompletableFuture<WriterUsageInfo> getWriterUsage(PublicKeyHash owner, PublicKeyHash writer, SecretSigningKey signer) {
        return new TimeLimitedClient.SignedRequest(writerUsagePath(owner, writer), System.currentTimeMillis())
                .sign(signer)
                .thenCompose(signed -> getWriterUsage(owner, writer, signed));
    }
}
