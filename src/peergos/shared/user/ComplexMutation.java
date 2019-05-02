package peergos.shared.user;

import java.util.concurrent.*;

public interface ComplexMutation {

    CompletableFuture<Snapshot> apply(Snapshot input, Committer committer);
}
