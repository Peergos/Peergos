package peergos.server.routing;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;

import java.util.*;
import java.util.function.*;

/** Where every reader of the routing log gets an identity's or a server's log: the replica itself on a full node,
 *  and on a light one the replica as a cache in front of full nodes (remove-pki §4.7).
 */
public interface RoutingLookup {

    Optional<IdentityLog> identity(PublicKeyHash identity);

    Optional<ServerLog> server(Multihash peerId);

    RoutingReplica replica();

    /** Whether "absent" means not found, rather than not arrived yet. A light server's answers come from full
     *  nodes, sampled when one says not found, so it always is.
     */
    default boolean isSynced() {
        return true;
    }

    static RoutingLookup full(RoutingReplica replica) {
        return full(replica, () -> true);
    }

    static RoutingLookup full(RoutingReplica replica, BooleanSupplier synced) {
        return new RoutingLookup() {
            @Override
            public boolean isSynced() {
                return synced.getAsBoolean();
            }

            @Override
            public Optional<IdentityLog> identity(PublicKeyHash identity) {
                return replica.identity(Subject.identity(identity));
            }

            @Override
            public Optional<ServerLog> server(Multihash peerId) {
                return replica.server(Subject.server(peerId.bareMultihash()));
            }

            @Override
            public RoutingReplica replica() {
                return replica;
            }
        };
    }
}
