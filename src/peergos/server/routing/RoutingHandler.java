package peergos.server.routing;

import com.sun.net.httpserver.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

/** The routing log's API for other servers. It is mounted only on the p2p api, where the Source header is the
 *  remote peer the transport authenticated, so announcements and offers are charged to the right server.
 *
 *  Each call is limited per caller, as a range scan or a signature check is far cheaper to ask for than to answer.
 *  A key costs nothing to make, so a caller that isn't a server in the log with its proof of work done shares one
 *  pool with every other such caller: a new server still computing its proof syncs from it, slowly.
 */
public class RoutingHandler implements HttpHandler {
    private static final Logger LOG = Logging.LOG();
    public static final int MAX_BODY = 1024 * 1024;
    public static final int MAX_ITEMS = 1024;
    public static final RateLimits.Rate SCANS = new RateLimits.Rate(200, 3_000), RECORDS = new RateLimits.Rate(10_000, 10),
            OFFERS = new RateLimits.Rate(1_000, 100);
    public static final RateLimits.Rate UNPROVEN_SCANS = new RateLimits.Rate(100, 1_000),
            UNPROVEN_RECORDS = new RateLimits.Rate(1_000, 50), UNPROVEN_OFFERS = new RateLimits.Rate(100, 1_000);
    private static final Multihash POOL = new Multihash(Multihash.Type.id, new byte[0]);

    private final RoutingNode node;
    private final Predicate<Multihash> vouches;
    private final RateLimits.TokenBuckets<Multihash> scans, records, offers, unprovenScans, unprovenRecords, unprovenOffers;

    public RoutingHandler(RoutingNode node) {
        this(node, server -> false);
    }

    /** @param vouches whether this server, as a relay, vouches for a server it relays for */
    public RoutingHandler(RoutingNode node, Predicate<Multihash> vouches) {
        this(node, vouches, System::currentTimeMillis);
    }

    public RoutingHandler(RoutingNode node, Predicate<Multihash> vouches, LongSupplier clock) {
        this.node = node;
        this.vouches = vouches;
        scans = new RateLimits.TokenBuckets<>(SCANS, clock);
        records = new RateLimits.TokenBuckets<>(RECORDS, clock);
        offers = new RateLimits.TokenBuckets<>(OFFERS, clock);
        unprovenScans = new RateLimits.TokenBuckets<>(UNPROVEN_SCANS, clock);
        unprovenRecords = new RateLimits.TokenBuckets<>(UNPROVEN_RECORDS, clock);
        unprovenOffers = new RateLimits.TokenBuckets<>(UNPROVEN_OFFERS, clock);
    }

    /** Whether the caller may make this call now. */
    public boolean allow(String method, Multihash caller) {
        RoutingReplica replica = node.replica();
        boolean proven = replica.serverForPeer(caller).filter(log -> replica.isProven(log.subject(), false)).isPresent();
        Multihash key = proven ? caller : POOL;
        switch (method) {
            case "summaries":
            case "states":
                return (proven ? scans : unprovenScans).tryTake(key);
            case "records":
                return (proven ? records : unprovenRecords).tryTake(key);
            default:
                return (proven ? offers : unprovenOffers).tryTake(key);
        }
    }

    @Override
    public void handle(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/"))
                path = path.substring(1);
            String method = path.substring(Constants.ROUTING_URL.length());
            Optional<Multihash> caller = Optional.ofNullable(exchange.getRequestHeaders().getFirst("Source"))
                    .map(Cid::decodePeerId)
                    .map(Multihash::bareMultihash);
            if (caller.isEmpty()) {
                HttpUtil.replyErrorWithCode(exchange, 403, "Routing is only served to authenticated peers");
                return;
            }
            if (! allow(method, caller.get())) {
                HttpUtil.replyErrorWithCode(exchange, 429, "Too many routing requests");
                return;
            }
            Cborable body = CborObject.fromByteArray(Serialize.readFully(exchange.getRequestBody(), MAX_BODY));
            Cborable res = handle(method, caller.get(), body);
            byte[] raw = res.serialize();
            exchange.sendResponseHeaders(200, raw.length);
            exchange.getResponseBody().write(raw);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Routing request failed: " + e.getMessage(), e);
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
        }
    }

    private Cborable handle(String method, Multihash caller, Cborable body) {
        CborObject.CborMap m = (CborObject.CborMap) body;
        switch (method) {
            case "announce": {
                List<JdbcRoutingStore.State> changed = m.getList("c", Reconciliation::state);
                node.announced(caller, changed.stream().limit(MAX_ITEMS).collect(Collectors.toList()));
                return new CborObject.CborBoolean(true);
            }
            case "records":
                return new CborObject.CborList(node.records(Subject.fromCbor(m.get("s"))).stream()
                        .map(RecordType::tag)
                        .collect(Collectors.toList()));
            case "summaries": {
                List<Reconciliation.Range> ranges = m.getList("r", Reconciliation.Range::fromCbor);
                if (ranges.size() > RoutingNode.BATCH)
                    throw new IllegalStateException("Too many ranges");
                return new CborObject.CborList(node.summaries((int) m.getLong("k"), ranges));
            }
            case "states":
                return new CborObject.CborList(node.states((int) m.getLong("k"), Reconciliation.Range.fromCbor(m.get("r")),
                        (int) m.getLong("l")).stream()
                        .map(Reconciliation::state)
                        .collect(Collectors.toList()));
            case "offer": {
                List<SignedRecord> records = m.getList("r", RecordType::untag);
                if (records.size() > MAX_ITEMS)
                    throw new IllegalStateException("Too many records");
                return new CborObject.CborString(node.offered(caller, Subject.fromCbor(m.get("s")), records).name());
            }
            case "vouches":
                return new CborObject.CborBoolean(vouches.test(Subject.fromCbor(m.get("s")).id));
            default:
                throw new IllegalStateException("Unknown routing method " + method);
        }
    }
}
