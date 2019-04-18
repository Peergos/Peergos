package peergos.shared.user;

import java.util.concurrent.*;

public interface ComplexMutation {

    CompletableFuture<MutableVersion> apply(MutableVersion input, WriteSynchronizer.Committer committer);
}
