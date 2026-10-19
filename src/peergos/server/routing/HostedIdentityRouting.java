package peergos.server.routing;

import peergos.server.login.*;
import peergos.server.storage.admin.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.routing.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** The routing log for the users hosted here, and the recovery data that lets one of them take their account back
 *  with their recovery key alone. An inception is admitted only from the current identity key of a user with quota
 *  here, since the server owes proof of work for each one.
 */
public class HostedIdentityRouting implements IdentityRouting {
    public static final int MAX_COPIES_PER_DAY = 20, MAX_COPIES = 1_000;

    private final CoreNode core;
    private final QuotaAdmin quotas;
    private final JdbcAccount account;
    private volatile Optional<ServerRouting> routing = Optional.empty();

    public HostedIdentityRouting(CoreNode core, QuotaAdmin quotas, JdbcAccount account) {
        this.core = core;
        this.quotas = quotas;
        this.account = account;
    }

    /** The routing log starts after the api, so until then it reports itself not running. */
    public void start(ServerRouting routing) {
        this.routing = Optional.of(routing);
    }

    /** The current key of each identity whose log this server holds, for every signature it checks. */
    public static IdentityKeys keys(RoutingReplica replica) {
        return identity -> Futures.of(replica.identity(Subject.identity(identity))
                .flatMap(log -> IdentityKeys.rotated(identity, log)));
    }

    private ServerRouting running() {
        return routing.orElseThrow(() -> new IllegalStateException("The routing log isn't running on this server"));
    }

    private String hostedUser(PublicKeyHash identity) {
        String username = core.getUsername(identity).join();
        if (quotas.getQuota(username) <= 0)
            throw new IllegalStateException(username + " is not hosted on this server");
        return username;
    }

    private Optional<IdentityLog> log(PublicKeyHash identity) {
        return running().replica.identity(Subject.identity(identity));
    }

    private static byte[] commitment(IdentityLog log) {
        return log.records.chain.currentEstablishment()
                .orElseThrow(() -> new IllegalStateException("No commitment to a next key"))
                .next();
    }

    private PublicSigningKey currentKey(PublicKeyHash identity) {
        IdentityLog log = log(identity).orElseThrow(() -> new IllegalStateException("No recovery key is committed"));
        return IdentityKeys.rotated(identity, log)
                .orElseGet(() -> log.currentKey().orElseThrow(() -> new IllegalStateException("No current key")));
    }

    @Override
    public CompletableFuture<Status> status(PublicKeyHash identity) {
        if (routing.isEmpty())
            return Futures.of(Status.NOT_RUNNING);
        ServerRouting r = routing.get();
        Optional<IdentityLog> log = r.replica.identity(Subject.identity(identity));
        boolean committed = log.flatMap(IdentityLog::inception).isPresent();
        Optional<PublicBoxingKey> recoveryKey = committed ?
                r.replica.store().getRecoveryKey(identity.serialize(), commitment(log.get()))
                        .map(b -> RecoveryCopy.WrappedKey.fromCbor(CborObject.fromByteArray(b)).publicKey) :
                Optional.empty();
        return Futures.of(new Status(true, committed, Optional.of(r.peerId), recoveryKey));
    }

    /** The wrapped key and copy are stored only with a new inception, so replaying a public one stores nothing. */
    @Override
    public CompletableFuture<Boolean> commit(IdentityInception inception, RecoveryCopy.WrappedKey wrapped, RecoveryCopy copy) {
        try {
            ServerRouting r = running();
            PublicKeyHash identity = inception.identityHash();
            String username = hostedUser(identity);
            if (! core.getPublicKeyHash(username).join().equals(Optional.of(identity)))
                throw new IllegalStateException("Not the current identity key of " + username);
            Optional<IdentityInception> held = log(identity)
                    .flatMap(IdentityLog::inception)
                    .map(v -> v.record);
            if (held.isPresent()) {
                if (held.get().equals(inception))
                    return Futures.of(true);
                throw new IllegalStateException("This identity has already committed to a different recovery key");
            }
            JdbcRoutingStore store = r.replica.store();
            store.putRecoveryKey(identity.serialize(), inception.next, wrapped.serialize());
            store.addRecoveryCopy(identity.serialize(), System.currentTimeMillis(), copy.serialize());
            Outcome res = r.admissions.submit(inception);
            if (res != Outcome.Added && res != Outcome.Duplicate)
                throw new IllegalStateException("The inception was not taken: " + res);
            return Futures.of(true);
        } catch (Exception e) {
            return Futures.errored(e);
        }
    }

    @Override
    public CompletableFuture<Boolean> addRecoveryCopy(PublicKeyHash identity, byte[] signedCopy) {
        try {
            JdbcRoutingStore store = running().replica.store();
            hostedUser(identity);
            byte[] raw = currentKey(identity).unsignMessage(signedCopy).join();
            RecoveryCopy copy = RecoveryCopy.fromCbor(CborObject.fromByteArray(raw));
            long now = System.currentTimeMillis();
            byte[] id = identity.serialize();
            if (store.countRecoveryCopies(id, now - 24 * 3600_000L) >= MAX_COPIES_PER_DAY
                    || store.countRecoveryCopies(id, 0) >= MAX_COPIES)
                throw new IllegalStateException("Too many recovery copies");
            store.addRecoveryCopy(id, now, copy.serialize());
            return Futures.of(true);
        } catch (Exception e) {
            return Futures.errored(e);
        }
    }

    @Override
    public CompletableFuture<RecoveryData> recoveryData(PublicKeyHash identity, PublicSigningKey next, byte[] signedRequest) {
        try {
            ServerRouting r = running();
            IdentityLog log = log(identity).orElseThrow(() -> new IllegalStateException("No recovery key is committed"));
            byte[] commitment = commitment(log);
            if (! Arrays.equals(Hash.sha256(next.serialize()), commitment))
                throw new IllegalStateException("Not the committed next key");
            RecoveryRequest req = RecoveryRequest.fromCbor(CborObject.fromByteArray(next.unsignMessage(signedRequest).join()));
            if (! req.identity.equals(identity) || Math.abs(System.currentTimeMillis() - req.time) > RECOVERY_REQUEST_MILLIS)
                throw new IllegalStateException("Stale recovery request");
            byte[] id = identity.serialize();
            RecoveryCopy.WrappedKey wrapped = r.replica.store().getRecoveryKey(id, commitment)
                    .map(b -> RecoveryCopy.WrappedKey.fromCbor(CborObject.fromByteArray(b)))
                    .orElseThrow(() -> new IllegalStateException("No recovery data is held here for this key"));
            List<RecoveryCopy> copies = new ArrayList<>();
            for (byte[] raw : r.replica.store().getRecoveryCopies(id))
                copies.add(RecoveryCopy.fromCbor(CborObject.fromByteArray(raw)));
            return Futures.of(new RecoveryData(wrapped, copies));
        } catch (Exception e) {
            return Futures.errored(e);
        }
    }

    /** Only a rotation new to the log has effect: one already in it is public, and replaying it must not remove the
     *  second factors the user set up since.
     */
    @Override
    public CompletableFuture<Boolean> recover(PublicKeyHash identity, IdentityRotation rotation, RecoveryCopy.WrappedKey wrapped, RecoveryCopy copy) {
        try {
            ServerRouting r = running();
            String username = hostedUser(identity);
            Subject s = Subject.identity(identity);
            IdentityLog log = log(identity).orElseThrow(() -> new IllegalStateException("No recovery key is committed"));
            if (log.held().stream().anyMatch(v -> v.record.equals(rotation)))
                return Futures.of(true);
            IdentityLog after = (IdentityLog) r.replica.preview(s, List.of(rotation));
            if (! after.currentKey().equals(Optional.of(rotation.to)) || after.isFrozen())
                throw new IllegalStateException("Not a rotation to the committed key");
            account.removeAllMfa(username);
            JdbcRoutingStore store = r.replica.store();
            store.putRecoveryKey(identity.serialize(), rotation.next, wrapped.serialize());
            store.addRecoveryCopy(identity.serialize(), System.currentTimeMillis(), copy.serialize());
            Outcome res = r.replica.add(s, rotation);
            if (res != Outcome.Added)
                throw new IllegalStateException("The rotation was not taken: " + res);
            return Futures.of(true);
        } catch (Exception e) {
            return Futures.errored(e);
        }
    }

    @Override
    public CompletableFuture<Boolean> move(PublicKeyHash identity, IdentityMove move) {
        try {
            ServerRouting r = running();
            if (! move.providers.equals(List.of(r.peerId)))
                throw new IllegalStateException("A move added here must name only this server");
            hostedUser(identity);
            Outcome res = r.replica.add(Subject.identity(identity), move);
            if (res != Outcome.Added && res != Outcome.Duplicate)
                throw new IllegalStateException("The move was not taken: " + res);
            return Futures.of(true);
        } catch (Exception e) {
            return Futures.errored(e);
        }
    }

    @Override
    public CompletableFuture<List<SignedRecord>> records(PublicKeyHash identity) {
        if (routing.isEmpty())
            return Futures.of(Collections.emptyList());
        List<SignedRecord> res = new ArrayList<>();
        routing.get().replica.log(Subject.identity(identity)).held().forEach(v -> res.add(v.record));
        return Futures.of(res);
    }
}
