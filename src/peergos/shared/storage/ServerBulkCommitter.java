package peergos.shared.storage;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** Sends a whole logical write to the server in a single bulk/commit call.
 *
 *  A server that predates the endpoint answers 404, so the first commit to each owner is also the
 *  feature detection: after one of those we use the pre-bulk endpoints for that owner for the rest
 *  of the session. Only an unambiguous "no such call" falls back - anything else could mean the
 *  commit was applied and we must not send it twice.
 */
public class ServerBulkCommitter implements BulkCommitter {

    private final ContentAddressedStorage target;
    private final BulkCommitter fallback;
    private final Set<PublicKeyHash> unsupported = new HashSet<>();

    public ServerBulkCommitter(ContentAddressedStorage target, BulkCommitter fallback) {
        this.target = target;
        this.fallback = fallback;
    }

    private synchronized boolean isSupported(PublicKeyHash owner) {
        return ! unsupported.contains(owner);
    }

    private synchronized void markUnsupported(PublicKeyHash owner) {
        unsupported.add(owner);
    }

    @Override
    public CompletableFuture<List<Cid>> commit(PublicKeyHash owner,
                                               BulkCommit commit,
                                               Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> signers) {
        // A buffer can be bigger than a single request may be; splitting it is only safe once both ends
        // support the block list signature, so until then an oversized commit takes the old path.
        boolean tooBig = commit.inlineSize() > ContentAddressedStorage.MAX_BULK_COMMIT_SIZE - 64 * 1024;
        if (tooBig || ! isSupported(owner))
            return fallback.commit(owner, commit, signers);
        return Futures.asyncExceptionally(() -> target.bulkCommit(owner, commit),
                t -> {
                    if (! isUnsupported(t))
                        return Futures.errored(t);
                    markUnsupported(owner);
                    return fallback.commit(owner, commit, signers);
                });
    }

    private static boolean isUnsupported(Throwable t) {
        String msg = Exceptions.getRootCause(t).getMessage();
        if (msg == null)
            return false;
        return msg.contains("Status code: 404")
                || msg.contains("Unimplemented call!")
                || msg.contains("Cannot bulk commit");
    }
}
