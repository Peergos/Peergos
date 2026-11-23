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
    /** A scan reads a bounded number of rows whatever the log's size, so a peer may make enough to find everything
     *  that changed between rounds.
     */
    public static final RateLimits.Rate SCANS = new RateLimits.Rate(2_000, 100), RECORDS = new RateLimits.Rate(10_000, 10),
            OFFERS = new RateLimits.Rate(1_000, 100);
    /** Pages of the log with records, a thousand subjects each, for a node starting from little. */
    public static final RateLimits.Rate PAGES = new RateLimits.Rate(600, 100), UNPROVEN_PAGES = new RateLimits.Rate(60, 1_000);
    public static final RateLimits.Rate UNPROVEN_SCANS = new RateLimits.Rate(100, 1_000),
            UNPROVEN_RECORDS = new RateLimits.Rate(1_000, 50), UNPROVEN_OFFERS = new RateLimits.Rate(100, 1_000);
    /** A server introducing itself, on its own allowance: its peer id is free, but its proof of work isn't. */
    public static final RateLimits.Rate INTRODUCTIONS = new RateLimits.Rate(4, 15 * 60_000);
    public static final int MAX_INTRODUCTION = 64 * 1024, MAX_INTRODUCED = 16;
    private static final Set<String> METHODS = Set.of("announce", "records", "records-of", "page", "summaries",
            "children", "states", "states-in", "offer", "vouches", "introduce");
    private static final Multihash POOL = new Multihash(Multihash.Type.id, new byte[0]);

    private final RoutingNode node;
    private final Predicate<Multihash> vouches;
    private final RateLimits.TokenBuckets<Multihash> scans, records, offers, unprovenScans, unprovenRecords, unprovenOffers,
            pages, unprovenPages, introductions;
    /** Each unproven peer id's share of what all of them share, so one can't take it all. */
    private final RateLimits.TokenBuckets<Multihash> scansShare, recordsShare, offersShare, pagesShare;

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
        pages = new RateLimits.TokenBuckets<>(PAGES, clock);
        unprovenPages = new RateLimits.TokenBuckets<>(UNPROVEN_PAGES, clock);
        introductions = new RateLimits.TokenBuckets<>(INTRODUCTIONS, clock);
        scansShare = new RateLimits.TokenBuckets<>(share(UNPROVEN_SCANS), clock);
        recordsShare = new RateLimits.TokenBuckets<>(share(UNPROVEN_RECORDS), clock);
        offersShare = new RateLimits.TokenBuckets<>(share(UNPROVEN_OFFERS), clock);
        pagesShare = new RateLimits.TokenBuckets<>(share(UNPROVEN_PAGES), clock);
    }

    private static RateLimits.Rate share(RateLimits.Rate pool) {
        return new RateLimits.Rate(Math.max(1, pool.burst / RoutingNode.UNPROVEN_SHARE), pool.refillMillis * RoutingNode.UNPROVEN_SHARE);
    }

    /** From the caller's share first, so an id past its share leaves the pool alone; a share is kept only for an id
     *  the pool let in, so ids that never got in cost no state.
     */
    private static boolean unproven(RateLimits.TokenBuckets<Multihash> pool, RateLimits.TokenBuckets<Multihash> share,
                                    Multihash caller, long cost) {
        if (share.tokens(caller) < cost)
            return false;
        if (! pool.tryTake(POOL, cost))
            return false;
        share.tryTake(caller, cost);
        return true;
    }

    /** Whether the caller may make this call now. */
    public boolean allow(String method, Multihash caller) {
        return allow(method, caller, 1);
    }

    /** @param cost what the call asks for: the subjects whose records it wants, or one */
    public boolean allow(String method, Multihash caller, int cost) {
        if (method.equals("introduce"))
            return introductions.tryTake(caller);
        boolean proven = node.isProvenServer(caller);
        switch (method) {
            case "summaries":
            case "children":
            case "states":
                return proven ? scans.tryTake(caller) : unproven(unprovenScans, scansShare, caller, 1);
            case "states-in": {
                int scanCost = RoutingNode.LISTED / RoutingNode.PAGE;
                return proven ? scans.tryTake(caller, scanCost) : unproven(unprovenScans, scansShare, caller, scanCost);
            }
            case "page":
                return proven ? pages.tryTake(caller) : unproven(unprovenPages, pagesShare, caller, 1);
            case "records":
            case "records-of":
                return proven ? records.tryTake(caller, cost) : unproven(unprovenRecords, recordsShare, caller, cost);
            default:
                return proven ? offers.tryTake(caller) : unproven(unprovenOffers, offersShare, caller, 1);
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
            if (! METHODS.contains(method))
                throw new IllegalStateException("Unknown routing method " + method);
            // charged before the body is read or decoded, so neither is free to a caller over its limit
            if (! allow(method, caller.get(), 1)) {
                HttpUtil.replyErrorWithCode(exchange, 429, "Too many routing requests");
                return;
            }
            Cborable body = CborObject.fromByteArray(Serialize.readFully(exchange.getRequestBody(),
                    method.equals("introduce") ? MAX_INTRODUCTION : MAX_BODY));
            int cost = method.equals("records-of") ? ((CborObject.CborMap) body).getList("s", Subject::fromCbor).size() : 1;
            if (cost > RoutingNode.FETCH_BATCH)
                throw new IllegalStateException("Too many subjects");
            if (cost > 1 && ! allow(method, caller.get(), cost - 1)) {
                HttpUtil.replyErrorWithCode(exchange, 429, "Too many routing requests");
                return;
            }
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
                return new CborObject.CborBoolean(node.announced(caller, changed.stream().limit(MAX_ITEMS).collect(Collectors.toList())));
            }
            case "records":
                return new CborObject.CborList(node.records(Subject.fromCbor(m.get("s"))).stream()
                        .map(RecordType::tag)
                        .collect(Collectors.toList()));
            case "records-of":
                return HttpRoutingPeer.recordsBySubject(node.recordsOf(m.getList("s", Subject::fromCbor)));
            case "page":
                return HttpRoutingPeer.page(node.page((int) m.getLong("k"), Reconciliation.Cursor.fromCbor(m.get("a")), (int) m.getLong("l")));
            case "summaries": {
                List<Reconciliation.Range> ranges = m.getList("r", Reconciliation.Range::fromCbor);
                if (ranges.size() > RoutingNode.BATCH)
                    throw new IllegalStateException("Too many ranges");
                return new CborObject.CborList(node.summaries((int) m.getLong("k"), ranges));
            }
            case "children": {
                List<Reconciliation.Range> parents = m.getList("r", Reconciliation.Range::fromCbor);
                if (parents.size() > RoutingNode.BATCH)
                    throw new IllegalStateException("Too many ranges");
                return new CborObject.CborList(node.children((int) m.getLong("k"), parents).stream()
                        .map(Reconciliation::children)
                        .collect(Collectors.toList()));
            }
            case "states":
                return new CborObject.CborList(node.states((int) m.getLong("k"), Reconciliation.Range.fromCbor(m.get("r")),
                        Reconciliation.Cursor.fromCbor(m.get("a")), (int) m.getLong("l")).stream()
                        .map(Reconciliation::state)
                        .collect(Collectors.toList()));
            case "states-in": {
                List<Reconciliation.Range> ranges = m.getList("r", Reconciliation.Range::fromCbor);
                if (ranges.size() > RoutingNode.LIST_RANGES)
                    throw new IllegalStateException("Too many ranges");
                return new CborObject.CborList(node.statesIn((int) m.getLong("k"), ranges, (int) m.getLong("l")).stream()
                        .map(range -> new CborObject.CborList(range.stream().map(Reconciliation::state).collect(Collectors.toList())))
                        .collect(Collectors.toList()));
            }
            case "offer": {
                List<SignedRecord> records = m.getList("r", RecordType::untag);
                if (records.size() > MAX_ITEMS)
                    throw new IllegalStateException("Too many records");
                return new CborObject.CborString(node.offered(caller, Subject.fromCbor(m.get("s")), records).name());
            }
            case "vouches":
                return new CborObject.CborBoolean(vouches.test(Subject.fromCbor(m.get("s")).id));
            case "introduce": {
                List<SignedRecord> own = m.getList("r", RecordType::untag);
                if (own.size() > MAX_INTRODUCED)
                    throw new IllegalStateException("Too many records");
                return new CborObject.CborString(node.introduced(caller, own).name());
            }
            default:
                throw new IllegalStateException("Unknown routing method " + method);
        }
    }
}
