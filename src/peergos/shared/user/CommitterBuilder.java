package peergos.shared.user;

import peergos.shared.crypto.hash.*;

import java.util.*;
import java.util.function.*;

public interface CommitterBuilder {

    /**
     * @param updateWriters the writers the update has written to so far. A flush partway through the update commits
     *                      all of them together, as a new writer is only accepted with the parent update that owns it.
     */
    Committer buildCommitter(Committer c, PublicKeyHash owner, Supplier<Set<PublicKeyHash>> updateWriters, Supplier<Boolean> commitWatcher);
}
