package peergos.shared.storage;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;

import java.util.*;
import java.util.concurrent.*;

/** Applies a whole logical write - blocks and pointer updates - for a single owner.
 */
public interface BulkCommitter {

    /** Apply the commit. Either everything is applied, or nothing that a reader can reach is.
     *
     * @param legacy what an implementation that falls back to the pre-bulk endpoints needs
     * @return the hashes of the blocks written, in the order they appear in the commit
     */
    CompletableFuture<List<Cid>> commit(PublicKeyHash owner, BulkCommit commit, LegacyCommitInfo legacy);
}
