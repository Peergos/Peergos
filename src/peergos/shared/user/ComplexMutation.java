package peergos.shared.user;

import java.util.concurrent.*;

public interface ComplexMutation {

    CompletableFuture<CommittedWriterData> apply(CommittedWriterData input, WriteSynchronizer.Committer committer);
}
