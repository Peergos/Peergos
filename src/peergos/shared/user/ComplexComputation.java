package peergos.shared.user;

import peergos.shared.util.*;

import java.util.concurrent.*;

public interface ComplexComputation<V> {

    CompletableFuture<Pair<Snapshot, V>> apply(Snapshot input, Committer committer);
}
