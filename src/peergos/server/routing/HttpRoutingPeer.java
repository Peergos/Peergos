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
    /** A peer can send anything, so no more than a full page of records or states is read. */
    public static final int MAX_RESPONSE = 16 * 1024 * 1024;

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
        return p2p.postBounded(prefix + method, CborObject.CborMap.build(args).serialize(), TIMEOUT_MILLIS, MAX_RESPONSE)
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
    public CompletableFuture<Map<Subject, List<SignedRecord>>> recordsOf(List<Subject> subjects) {
        return call("records-of", Map.of("s", new CborObject.CborList(subjects)))
                .thenApply(HttpRoutingPeer::recordsBySubject);
    }

    static CborObject.CborList recordsBySubject(Map<Subject, List<SignedRecord>> records) {
        List<Cborable> res = new ArrayList<>();
        for (Map.Entry<Subject, List<SignedRecord>> e : records.entrySet()) {
            Map<String, Cborable> m = new TreeMap<>();
            m.put("s", e.getKey());
            m.put("r", new CborObject.CborList(e.getValue().stream().map(RecordType::tag).collect(Collectors.toList())));
            res.add(CborObject.CborMap.build(m));
        }
        return new CborObject.CborList(res);
    }

    static Map<Subject, List<SignedRecord>> recordsBySubject(Cborable cbor) {
        Map<Subject, List<SignedRecord>> res = new LinkedHashMap<>();
        for (Cborable c : ((CborObject.CborList) cbor).value) {
            CborObject.CborMap m = (CborObject.CborMap) c;
            res.put(Subject.fromCbor(m.get("s")), m.getList("r", RecordType::untag));
        }
        return res;
    }

    @Override
    public CompletableFuture<Page> page(int kind, Reconciliation.Cursor after, int limit) {
        return call("page", Map.of("k", new CborObject.CborLong(kind), "a", after, "l", new CborObject.CborLong(limit)))
                .thenApply(HttpRoutingPeer::page);
    }

    static CborObject.CborMap page(Page page) {
        Map<String, Cborable> m = new TreeMap<>();
        m.put("r", recordsBySubject(page.records));
        page.next.ifPresent(n -> m.put("n", n));
        return CborObject.CborMap.build(m);
    }

    static Page page(Cborable cbor) {
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new Page(recordsBySubject(m.get("r")), m.getOptional("n", Reconciliation.Cursor::fromCbor));
    }

    @Override
    public CompletableFuture<List<Reconciliation.Summary>> summaries(int kind, List<Reconciliation.Range> ranges) {
        return call("summaries", Map.of("k", new CborObject.CborLong(kind), "r", new CborObject.CborList(ranges)))
                .thenApply(res -> ((CborObject.CborList) res).value.stream()
                        .map(Reconciliation.Summary::fromCbor)
                        .collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<List<Map<Integer, Reconciliation.Summary>>> children(int kind, List<Reconciliation.Range> parents) {
        return call("children", Map.of("k", new CborObject.CborLong(kind), "r", new CborObject.CborList(parents)))
                .thenApply(res -> ((CborObject.CborList) res).value.stream()
                        .map(Reconciliation::children)
                        .collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<List<JdbcRoutingStore.State>> states(int kind, Reconciliation.Range range, Reconciliation.Cursor after, int limit) {
        return call("states", Map.of("k", new CborObject.CborLong(kind), "r", range, "a", after, "l", new CborObject.CborLong(limit)))
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

    @Override
    public CompletableFuture<Boolean> vouches(Multihash server) {
        return call("vouches", Map.of("s", Subject.server(server.bareMultihash())))
                .thenApply(res -> ((CborObject.CborBoolean) res).value);
    }
}
