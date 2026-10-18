package peergos.server.routing;

import peergos.server.storage.admin.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.routing.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** The routing log for the users hosted here: an inception is admitted only from the current identity key of a user
 *  with quota on this server, since the server owes proof of work for each one.
 */
public class HostedIdentityRouting implements IdentityRouting {
    private final CoreNode core;
    private final QuotaAdmin quotas;
    private volatile Optional<ServerRouting> routing = Optional.empty();

    public HostedIdentityRouting(CoreNode core, QuotaAdmin quotas) {
        this.core = core;
        this.quotas = quotas;
    }

    /** The routing log starts after the api, so until then it reports itself not running. */
    public void start(ServerRouting routing) {
        this.routing = Optional.of(routing);
    }

    @Override
    public CompletableFuture<Status> status(PublicKeyHash identity) {
        if (routing.isEmpty())
            return Futures.of(Status.NOT_RUNNING);
        ServerRouting r = routing.get();
        boolean committed = r.replica.log(Subject.identity(identity)).inception().isPresent();
        return Futures.of(new Status(true, committed, Optional.of(r.peerId)));
    }

    @Override
    public CompletableFuture<Boolean> commit(IdentityInception inception) {
        if (routing.isEmpty())
            return Futures.errored(new IllegalStateException("The routing log isn't running on this server"));
        PublicKeyHash identity = inception.identityHash();
        String username = core.getUsername(identity).join();
        Optional<PublicKeyHash> current = core.getPublicKeyHash(username).join();
        if (! current.equals(Optional.of(identity)))
            return Futures.errored(new IllegalStateException("Not the current identity key of " + username));
        if (quotas.getQuota(username) <= 0)
            return Futures.errored(new IllegalStateException(username + " is not hosted on this server"));
        Optional<IdentityInception> held = routing.get().replica.identity(Subject.identity(identity))
                .flatMap(IdentityLog::inception)
                .map(v -> v.record);
        if (held.isPresent())
            return held.get().equals(inception) ?
                    Futures.of(true) :
                    Futures.errored(new IllegalStateException("This identity has already committed to a different recovery key"));
        Outcome res = routing.get().admissions.submit(inception);
        if (res != Outcome.Added && res != Outcome.Duplicate)
            return Futures.errored(new IllegalStateException("The inception was not taken: " + res));
        return Futures.of(true);
    }
}
