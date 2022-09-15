package peergos.shared.user;

import peergos.shared.crypto.hash.*;

import java.util.function.*;

public interface CommitterBuilder {

    Committer buildCommitter(Committer c, PublicKeyHash owner, Supplier<Boolean> commitWatcher);
}
