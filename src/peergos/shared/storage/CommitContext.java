package peergos.shared.storage;

import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;

import java.util.*;

/** What applying a {@link BulkCommit} needs that the commit itself doesn't carry.
 *
 *  The commit is the wire format; this is what the sender knows about it. The keys are needed to sign
 *  each block for a server that predates the bulk call, and to sign a block list when a commit has to
 *  be split. The new writers are the ones such a server won't accept blocks for until their parent's
 *  pointer update has registered them. The roots are what the signed pointer updates point at, which
 *  only the sender can see without unsigning them.
 */
public class CommitContext {

    public final Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> signers;
    public final Set<PublicKeyHash> newWriters;
    public final Map<PublicKeyHash, MaybeMultihash> roots;
    private final Map<PublicKeyHash, Optional<Long>> sequences;

    /** The sequence number the writer's pointer is heading for, which a block list signature is bound to. */
    public Optional<Long> nextSequence(PublicKeyHash writer) {
        return Optional.ofNullable(sequences.get(writer)).orElse(Optional.empty());
    }

    public CommitContext(Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> signers,
                         Set<PublicKeyHash> newWriters,
                         Map<PublicKeyHash, MaybeMultihash> roots,
                         Map<PublicKeyHash, Optional<Long>> sequences) {
        this.signers = signers;
        this.newWriters = newWriters;
        this.roots = roots;
        this.sequences = sequences;
    }
}
