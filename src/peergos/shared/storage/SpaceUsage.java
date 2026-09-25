package peergos.shared.storage;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;

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

    /**
     * @param signedTime the current time signed by the owner's identity key
     * @return every capped writing space of the owner
     */
    CompletableFuture<List<WriterSpaceInfo>> getWriterQuotas(PublicKeyHash owner, byte[] signedTime);

    /**
     * @param signedTime the current time signed by the writer, or by the owner's identity key
     */
    CompletableFuture<WriterSpaceInfo> getWriterSpace(PublicKeyHash owner, PublicKeyHash writer, byte[] signedTime);
}
