package peergos.server.storage;

import peergos.shared.io.ipfs.Cid;

/** A block this node does not have, as distinct from a block it could not read.
 *
 *  The two need telling apart during garbage collection. A block that is absent cannot be walked, but
 *  that is a state a user can create for free by publishing a pointer to something they never uploaded,
 *  so it must never be able to stop their collection. A block that is present but unreadable - a 5xx
 *  that outlived its retries, a truncated read - means reachability is simply unknown, and deleting on
 *  the strength of it would drop live data.
 */
public class BlockAbsentException extends IllegalStateException {

    public BlockAbsentException(Cid block) {
        super("Block not present locally: " + block);
    }
}
