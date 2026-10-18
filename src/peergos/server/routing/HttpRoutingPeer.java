package peergos.server.routing;

import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** Another server's routing API, over libp2p HTTP through the local proxy. */
public class HttpRoutingPeer implements RoutingPeer {
    private static final int TIMEOUT_MILLIS = 30_000;

    private final Multihash target;
    private final HttpPoster p2p;
    private final String prefix;

    public HttpRoutingPeer(Multihash target, HttpPoster p2p) {
        this.target = target.bareMultihash();
        this.p2p = p2p;
        this.prefix = "/p2p/" + new Cid(1, Cid.Codec.LibP2pKey, this.target.type, this.target.getHash()) + "/http/" + Constants.ROUTING_URL;
    }

    @Override
    public Multihash id() {
        return target;
    }

    private CompletableFuture<CborObject> call(String method, Map<String, Cborable> args) {
        return p2p.postUnzip(prefix + method, CborObject.CborMap.build(args).serialize(), TIMEOUT_MILLIS)
                .thenApply(CborObject::fromByteArray);
    }

    @Override
    public CompletableFuture<Boolean> announce(List<JdbcRoutingStore.State> changed) {
        return call("announce", Map.of("c", new CborObject.CborList(changed.stream()
                .map(Reconciliation::state)
                .collect(Collectors.toList()))))
                .thenApply(res -> ((CborObject.CborBoolean) res).value);
    }

    @Override
    public CompletableFuture<List<SignedRecord>> records(Subject s) {
        return call("records", Map.of("s", s))
                .thenApply(res -> ((CborObject.CborList) res).value.stream()
                        .map(RecordType::untag)
                        .collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<List<Reconciliation.Summary>> summaries(int kind, List<Reconciliation.Range> ranges) {
        return call("summaries", Map.of("k", new CborObject.CborLong(kind), "r", new CborObject.CborList(ranges)))
                .thenApply(res -> ((CborObject.CborList) res).value.stream()
                        .map(Reconciliation.Summary::fromCbor)
                        .collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<List<JdbcRoutingStore.State>> states(int kind, Reconciliation.Range range, int limit) {
        return call("states", Map.of("k", new CborObject.CborLong(kind), "r", range, "l", new CborObject.CborLong(limit)))
                .thenApply(res -> ((CborObject.CborList) res).value.stream()
                        .map(Reconciliation::state)
                        .collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<Outcome> offer(Subject s, List<SignedRecord> records) {
        return call("offer", Map.of("s", s, "r", new CborObject.CborList(records.stream()
                .map(RecordType::tag)
                .collect(Collectors.toList()))))
                .thenApply(res -> Outcome.valueOf(((CborObject.CborString) res).value));
    }
}
