package peergos.shared.routing;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** A client's view of the routing log through its home server: whether its identity has committed to a next key,
 *  and submitting the inception that does, which the server admits.
 */
public interface IdentityRouting {

    CompletableFuture<Status> status(PublicKeyHash identity);

    CompletableFuture<Boolean> commit(IdentityInception inception);

    IdentityRouting DISABLED = new IdentityRouting() {
        @Override
        public CompletableFuture<Status> status(PublicKeyHash identity) {
            return Futures.of(Status.NOT_RUNNING);
        }

        @Override
        public CompletableFuture<Boolean> commit(IdentityInception inception) {
            return Futures.errored(new IllegalStateException("The routing log isn't running"));
        }
    };

    class Status implements Cborable {
        public static final Status NOT_RUNNING = new Status(false, false, Optional.empty());

        public final boolean running, committed;
        /** The peerid that admits identities here, which an inception names as its first provider. */
        public final Optional<Multihash> server;

        public Status(boolean running, boolean committed, Optional<Multihash> server) {
            this.running = running;
            this.committed = committed;
            this.server = server;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> m = new TreeMap<>();
            m.put("r", new CborObject.CborBoolean(running));
            m.put("c", new CborObject.CborBoolean(committed));
            server.ifPresent(s -> m.put("s", new CborObject.CborByteArray(s.toBytes())));
            return CborObject.CborMap.build(m);
        }

        public static Status fromCbor(Cborable cbor) {
            CborObject.CborMap m = RoutingCbor.map(cbor, "IdentityRouting.Status");
            return new Status(m.getBoolean("r"), m.getBoolean("c"),
                    m.getOptional("s", c -> Multihash.decode(((CborObject.CborByteArray) c).value)));
        }
    }

    class HTTP implements IdentityRouting {
        private final HttpPoster poster;

        public HTTP(HttpPoster poster) {
            this.poster = poster;
        }

        @Override
        public CompletableFuture<Status> status(PublicKeyHash identity) {
            return poster.get(Constants.IDENTITY_ROUTING_URL + "status?identity=" + identity)
                    .thenApply(res -> Status.fromCbor(CborObject.fromByteArray(res)));
        }

        @Override
        public CompletableFuture<Boolean> commit(IdentityInception inception) {
            return poster.post(Constants.IDENTITY_ROUTING_URL + "commit", inception.serialize(), false)
                    .thenApply(res -> ((CborObject.CborBoolean) CborObject.fromByteArray(res)).value);
        }
    }
}
