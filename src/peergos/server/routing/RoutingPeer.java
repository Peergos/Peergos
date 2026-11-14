package peergos.server.routing;

import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.util.*;

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

    /** The records of several subjects in one call. One the peer can't serve is left out. */
    default CompletableFuture<Map<Subject, List<SignedRecord>>> recordsOf(List<Subject> subjects) {
        Map<Subject, List<SignedRecord>> res = new HashMap<>();
        Throwable failure = null;
        for (Subject s : subjects) {
            try {
                res.put(s, records(s).join());
            } catch (Throwable t) {
                failure = t;
            }
        }
        return res.isEmpty() && failure != null ? Futures.errored(failure) : Futures.of(res);
    }

    /** A stretch of the peer's log in order, with records: what a node starting from little reads end to end. */
    final class Page {
        public final Map<Subject, List<SignedRecord>> records;
        /** Where the next page starts, or empty at the end. It may be past subjects the peer couldn't serve. */
        public final Optional<Reconciliation.Cursor> next;
        /** Subjects passed over because the peer holds them without their signatures. */
        public final int skipped;

        public Page(Map<Subject, List<SignedRecord>> records, Optional<Reconciliation.Cursor> next, int skipped) {
            this.records = records;
            this.next = next;
            this.skipped = skipped;
        }
    }

    default CompletableFuture<Page> page(int kind, Reconciliation.Cursor after, int limit) {
        return states(kind, Reconciliation.Range.ALL, after, limit)
                .thenCompose(states -> recordsOf(states.stream().map(st -> st.subject).collect(java.util.stream.Collectors.toList()))
                        .thenApply(records -> new Page(records, states.size() < limit ?
                                Optional.empty() :
                                Optional.of(Reconciliation.Cursor.after(states.get(states.size() - 1).subject)),
                                states.size() - records.size())));
    }

    CompletableFuture<List<Reconciliation.Summary>> summaries(int kind, List<Reconciliation.Range> ranges);

    /** For each range, the summaries of its children that hold anything, by the last byte of their prefix. */
    CompletableFuture<List<Map<Integer, Reconciliation.Summary>>> children(int kind, List<Reconciliation.Range> parents);

    /** States in a range after a place in it, in order. */
    CompletableFuture<List<JdbcRoutingStore.State>> states(int kind, Reconciliation.Range range, Reconciliation.Cursor after, int limit);

    /** The whole of each range in turn, stopping before the first whose states would pass the limit: the answer
     *  holds a list for each range before that one.
     */
    default CompletableFuture<List<List<JdbcRoutingStore.State>>> statesIn(int kind, List<Reconciliation.Range> ranges, int limit) {
        List<List<JdbcRoutingStore.State>> res = new ArrayList<>();
        int left = limit;
        try {
            for (Reconciliation.Range range : ranges) {
                int max = Math.min(left + 1, RoutingNode.PAGE);
                List<JdbcRoutingStore.State> all = states(kind, range, Reconciliation.Cursor.at(range.from()), max).join();
                if (all.size() > left || all.size() >= max)
                    break;
                res.add(all);
                left -= all.size();
            }
        } catch (Throwable t) {
            return Futures.errored(t);
        }
        return Futures.of(res);
    }

    /** Records handed in by a light server, or the caller's own, charged to the caller's budget. */
    CompletableFuture<Outcome> offer(Subject s, List<SignedRecord> records);

    /** Asked of a relay a server lists first: whether that server is one it relays for and stands behind, so that
     *  the server may be put in the relay's address bucket (remove-pki §4.5).
     */
    default CompletableFuture<Boolean> vouches(Multihash server) {
        return Futures.of(false);
    }
}
