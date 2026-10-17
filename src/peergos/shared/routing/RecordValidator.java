package peergos.shared.routing;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

import java.util.concurrent.*;

/** Checks a record on its own, cheapest first: size bounds, then its signature (remove-pki §4.5). What it means
 *  against other records is {@link RecordLog}'s job, and whether its proof of work or admission is done decides
 *  only whether it is passed on, not whether it is used.
 */
public class RecordValidator {
    private final Hasher hasher;
    private final Work identityWork, serverWork;

    public RecordValidator(Hasher hasher, Work identityWork, Work serverWork) {
        this.hasher = hasher;
        this.identityWork = identityWork;
        this.serverWork = serverWork;
    }

    public <R extends SignedRecord> CompletableFuture<Verified<R>> verify(R record) {
        try {
            record.checkBounds();
        } catch (RuntimeException e) {
            return Futures.errored(e);
        }
        return record.verifySignature()
                .thenCompose(valid -> {
                    if (! valid)
                        throw new IllegalStateException("Invalid signature on " + record.getClass().getSimpleName());
                    return hasher.sha256(record.signedBytes());
                })
                .thenCompose(hash -> (record instanceof Establishment ?
                        hasher.sha256(((Establishment) record).key().serialize()) :
                        Futures.of(new byte[0]))
                        .thenApply(keyHash -> new Verified<>(record, hash, keyHash)));
    }

    /** A new inception's admission must verify against the admitting server's current key; one taken in by
     *  catch-up against any key in the server's chain (remove-pki §4.1).
     */
    public CompletableFuture<Boolean> hasValidAdmission(IdentityInception inception, PublicSigningKey admittingServerKey) {
        if (inception.admission.isEmpty())
            return Futures.of(false);
        return inception.admission.get().verify(inception.identity, admittingServerKey, identityWork);
    }

    public CompletableFuture<Boolean> hasValidProof(ServerInception inception) {
        if (inception.proof.isEmpty())
            return Futures.of(false);
        return serverWork.verify(inception.server.serialize(), inception.proof.get());
    }
}
