package peergos.shared.routing;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** A client's view of the routing log through its home server: committing its identity to a next key derived from
 *  a recovery key, keeping recovery copies of its login data there, and recovering with that key alone.
 */
public interface IdentityRouting {
    long RECOVERY_REQUEST_MILLIS = 5 * 60_000;

    CompletableFuture<Status> status(PublicKeyHash identity);

    /** The inception, with the recovery keypair wrapped by the recovery key and a first recovery copy. */
    CompletableFuture<Boolean> commit(IdentityInception inception, RecoveryCopy.WrappedKey wrapped, RecoveryCopy copy);

    /** @param signedCopy a RecoveryCopy signed by the identity's current key */
    CompletableFuture<Boolean> addRecoveryCopy(PublicKeyHash identity, byte[] signedCopy);

    /** @param signedRequest a {@link RecoveryRequest} signed by the committed next key, which only the recovery key
     *                      derives */
    CompletableFuture<RecoveryData> recoveryData(PublicKeyHash identity, PublicSigningKey next, byte[] signedRequest);

    /** Rotates to the committed key, which the rotation is signed by, removes every second factor, and stores the
     *  wrapped key and a recovery copy for the next recovery key.
     */
    CompletableFuture<Boolean> recover(PublicKeyHash identity, IdentityRotation rotation, RecoveryCopy.WrappedKey wrapped, RecoveryCopy copy);

    /** Names this server as the identity's provider, after the user migrates here. */
    CompletableFuture<Boolean> move(PublicKeyHash identity, IdentityMove move);

    /** The identity's records, for a client to find its current key itself. */
    CompletableFuture<List<SignedRecord>> records(PublicKeyHash identity);

    IdentityRouting DISABLED = new IdentityRouting() {
        private <T> CompletableFuture<T> notRunning() {
            return Futures.errored(new IllegalStateException("The routing log isn't running"));
        }

        @Override
        public CompletableFuture<Status> status(PublicKeyHash identity) {
            return Futures.of(Status.NOT_RUNNING);
        }

        @Override
        public CompletableFuture<Boolean> commit(IdentityInception inception, RecoveryCopy.WrappedKey wrapped, RecoveryCopy copy) {
            return notRunning();
        }

        @Override
        public CompletableFuture<Boolean> addRecoveryCopy(PublicKeyHash identity, byte[] signedCopy) {
            return notRunning();
        }

        @Override
        public CompletableFuture<RecoveryData> recoveryData(PublicKeyHash identity, PublicSigningKey next, byte[] signedRequest) {
            return notRunning();
        }

        @Override
        public CompletableFuture<Boolean> recover(PublicKeyHash identity, IdentityRotation rotation, RecoveryCopy.WrappedKey wrapped, RecoveryCopy copy) {
            return notRunning();
        }

        @Override
        public CompletableFuture<Boolean> move(PublicKeyHash identity, IdentityMove move) {
            return notRunning();
        }

        @Override
        public CompletableFuture<List<SignedRecord>> records(PublicKeyHash identity) {
            return Futures.of(Collections.emptyList());
        }
    };

    class Status implements Cborable {
        public static final Status NOT_RUNNING = new Status(false, false, false, Optional.empty(), Optional.empty());

        public final boolean running, committed;
        /** Whether the log names this server as the identity's provider. */
        public final boolean listed;
        /** The peerid that admits identities here, which an inception names as its first provider. */
        public final Optional<Multihash> server;
        /** What recovery copies are encrypted to, for the identity's current commitment. */
        public final Optional<PublicBoxingKey> recoveryKey;

        public Status(boolean running, boolean committed, boolean listed, Optional<Multihash> server, Optional<PublicBoxingKey> recoveryKey) {
            this.running = running;
            this.committed = committed;
            this.listed = listed;
            this.server = server;
            this.recoveryKey = recoveryKey;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> m = new TreeMap<>();
            m.put("r", new CborObject.CborBoolean(running));
            m.put("c", new CborObject.CborBoolean(committed));
            m.put("l", new CborObject.CborBoolean(listed));
            server.ifPresent(s -> m.put("s", new CborObject.CborByteArray(s.toBytes())));
            recoveryKey.ifPresent(k -> m.put("k", k));
            return CborObject.CborMap.build(m);
        }

        public static Status fromCbor(Cborable cbor) {
            CborObject.CborMap m = RoutingCbor.map(cbor, "IdentityRouting.Status");
            return new Status(m.getBoolean("r"), m.getBoolean("c"), m.getBoolean("l"),
                    m.getOptional("s", c -> Multihash.decode(((CborObject.CborByteArray) c).value)),
                    m.getOptional("k", PublicBoxingKey::fromCbor));
        }
    }

    /** What the next key signs to fetch the recovery data, bound to the identity and the time against replay. */
    class RecoveryRequest implements Cborable {
        public final PublicKeyHash identity;
        public final long time;

        public RecoveryRequest(PublicKeyHash identity, long time) {
            this.identity = identity;
            this.time = time;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> m = new TreeMap<>();
            m.put("i", identity);
            m.put("t", new CborObject.CborLong(time));
            return CborObject.CborMap.build(m);
        }

        public static RecoveryRequest fromCbor(Cborable cbor) {
            CborObject.CborMap m = RoutingCbor.map(cbor, "RecoveryRequest");
            return new RecoveryRequest(m.get("i", PublicKeyHash::fromCbor), m.getLong("t"));
        }
    }

    class RecoveryData implements Cborable {
        public final RecoveryCopy.WrappedKey wrapped;
        /** Newest first. */
        public final List<RecoveryCopy> copies;

        public RecoveryData(RecoveryCopy.WrappedKey wrapped, List<RecoveryCopy> copies) {
            this.wrapped = wrapped;
            this.copies = copies;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> m = new TreeMap<>();
            m.put("w", wrapped);
            m.put("c", new CborObject.CborList(copies));
            return CborObject.CborMap.build(m);
        }

        public static RecoveryData fromCbor(Cborable cbor) {
            CborObject.CborMap m = RoutingCbor.map(cbor, "RecoveryData");
            return new RecoveryData(RecoveryCopy.WrappedKey.fromCbor(m.get("w")), m.getList("c", RecoveryCopy::fromCbor));
        }
    }

    static CborObject.CborMap recovery(SignedRecord record, RecoveryCopy.WrappedKey wrapped, RecoveryCopy copy) {
        SortedMap<String, Cborable> m = new TreeMap<>();
        m.put("r", record);
        m.put("w", wrapped);
        m.put("c", copy);
        return CborObject.CborMap.build(m);
    }

    class HTTP implements IdentityRouting {
        private final HttpPoster poster;

        public HTTP(HttpPoster poster) {
            this.poster = poster;
        }

        private static boolean bool(byte[] res) {
            return ((CborObject.CborBoolean) CborObject.fromByteArray(res)).value;
        }

        @Override
        public CompletableFuture<Status> status(PublicKeyHash identity) {
            return poster.get(Constants.IDENTITY_ROUTING_URL + "status?identity=" + identity)
                    .thenApply(res -> Status.fromCbor(CborObject.fromByteArray(res)));
        }

        @Override
        public CompletableFuture<Boolean> commit(IdentityInception inception, RecoveryCopy.WrappedKey wrapped, RecoveryCopy copy) {
            return poster.post(Constants.IDENTITY_ROUTING_URL + "commit", recovery(inception, wrapped, copy).serialize(), false)
                    .thenApply(HTTP::bool);
        }

        @Override
        public CompletableFuture<Boolean> addRecoveryCopy(PublicKeyHash identity, byte[] signedCopy) {
            return poster.post(Constants.IDENTITY_ROUTING_URL + "copy?identity=" + identity, signedCopy, false)
                    .thenApply(HTTP::bool);
        }

        @Override
        public CompletableFuture<RecoveryData> recoveryData(PublicKeyHash identity, PublicSigningKey next, byte[] signedRequest) {
            SortedMap<String, Cborable> m = new TreeMap<>();
            m.put("k", next);
            m.put("s", new CborObject.CborByteArray(signedRequest));
            return poster.post(Constants.IDENTITY_ROUTING_URL + "recovery-data?identity=" + identity,
                            CborObject.CborMap.build(m).serialize(), false)
                    .thenApply(res -> RecoveryData.fromCbor(CborObject.fromByteArray(res)));
        }

        @Override
        public CompletableFuture<Boolean> recover(PublicKeyHash identity, IdentityRotation rotation, RecoveryCopy.WrappedKey wrapped, RecoveryCopy copy) {
            return poster.post(Constants.IDENTITY_ROUTING_URL + "recover?identity=" + identity,
                            recovery(rotation, wrapped, copy).serialize(), false)
                    .thenApply(HTTP::bool);
        }

        @Override
        public CompletableFuture<Boolean> move(PublicKeyHash identity, IdentityMove move) {
            return poster.post(Constants.IDENTITY_ROUTING_URL + "move?identity=" + identity, move.serialize(), false)
                    .thenApply(HTTP::bool);
        }

        @Override
        public CompletableFuture<List<SignedRecord>> records(PublicKeyHash identity) {
            return poster.get(Constants.IDENTITY_ROUTING_URL + "records?identity=" + identity)
                    .thenApply(res -> ((CborObject.CborList) CborObject.fromByteArray(res)).value.stream()
                            .map(RecordType::untag)
                            .collect(Collectors.toList()));
        }
    }
}
