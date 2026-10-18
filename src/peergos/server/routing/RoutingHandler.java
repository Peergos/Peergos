package peergos.server.routing;

import com.sun.net.httpserver.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.logging.*;
import java.util.stream.*;

/** The routing log's API for other servers. It is mounted only on the p2p api, where the Source header is the
 *  remote peer the transport authenticated, so announcements and offers are charged to the right server.
 */
public class RoutingHandler implements HttpHandler {
    private static final Logger LOG = Logging.LOG();
    public static final int MAX_BODY = 1024 * 1024;
    public static final int MAX_ITEMS = 1024;

    private final RoutingNode node;

    public RoutingHandler(RoutingNode node) {
        this.node = node;
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
            default:
                throw new IllegalStateException("Unknown routing method " + method);
        }
    }
}
