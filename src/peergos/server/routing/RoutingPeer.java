package peergos.server.routing;

import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;

import java.util.*;
import java.util.concurrent.*;

/** Another full node, as this one calls it. The transport authenticates the caller, so a node always knows which
 *  server an announcement or an offer came from.
 */
public interface RoutingPeer {

    Multihash id();

    /** Subjects whose state changed at the caller, with their new state hashes. */
    CompletableFuture<Boolean> announce(List<JdbcRoutingStore.State> changed);

    /** Every record held for a subject, proven or not: pulling by key needs them, passing on does not. */
    CompletableFuture<List<SignedRecord>> records(Subject s);

    CompletableFuture<List<Reconciliation.Summary>> summaries(int kind, List<Reconciliation.Range> ranges);

    CompletableFuture<List<JdbcRoutingStore.State>> states(int kind, Reconciliation.Range range, int limit);

    /** Records handed in by a light server, or the caller's own, charged to the caller's budget. */
    CompletableFuture<Outcome> offer(Subject s, List<SignedRecord> records);
}
