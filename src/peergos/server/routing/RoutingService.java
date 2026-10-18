package peergos.server.routing;

import io.libp2p.core.*;
import io.libp2p.core.crypto.*;
import org.peergos.protocol.autonat.*;
import peergos.server.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.*;
import peergos.shared.routing.*;
import peergos.shared.user.*;

import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

/** The routing log in a running server, behind -routing-log while it is built: the replica and this server's
 *  records, replication with the configured sync peers, and the proof of work it owes.
 */
public class RoutingService {
    private static final Logger LOG = Logging.LOG();

    public static final Command.Arg ARG_ROUTING_LOG = new Command.Arg("routing-log",
            "Run the routing log that replaces the PKI (in development)", false, "false");
    public static final Command.Arg ARG_ROUTING_SQL_FILE = new Command.Arg("routing-sql-file",
            "The filename for the routing log datastore", false, "routing.sql");
    public static final Command.Arg ARG_ROUTING_PEERS = new Command.Arg("routing-peers",
            "Comma separated peerids of the full nodes this server syncs the routing log with", false);
    public static final Command.Arg ARG_ROUTING_POW_THREADS = new Command.Arg("routing-pow-threads",
            "Cores used for the proof of work on identities signing up here", false);
    public static final Command.Arg ARG_ROUTING_INCEPTIONS_PER_DAY = new Command.Arg("routing-inceptions-per-day",
            "New inceptions taken per day from each address bucket", false, "" + RateLimits.DEFAULT_INCEPTIONS_PER_DAY);
    public static final Command.Arg ARG_ROUTING_LEGACY_STATE = new Command.Arg("routing-legacy-state",
            "Path to the PKI's final state, imported as legacy records", false);
    public static final Command.Arg ARG_ROUTING_LEGACY_STATE_HASH = new Command.Arg("routing-legacy-state-hash",
            "The pinned hash of the PKI's final state", false);

    public static final Command.Arg ARG_ROUTING_WORK_BITS = new Command.Arg("routing-work-bits",
            "Test networks only: one proof of work graph size for identities and servers, which every node must share", false);
    public static final Command.Arg ARG_ROUTING_EXPORT_LEGACY_STATE = new Command.Arg("routing-export-legacy-state",
            "On the pki node, write the PKI's current state to this path to be pinned, and log its hash", false);

    public static final List<Command.Arg> ARGS = List.of(ARG_ROUTING_LOG, ARG_ROUTING_SQL_FILE, ARG_ROUTING_PEERS,
            ARG_ROUTING_POW_THREADS, ARG_ROUTING_INCEPTIONS_PER_DAY, ARG_ROUTING_LEGACY_STATE, ARG_ROUTING_LEGACY_STATE_HASH,
            ARG_ROUTING_WORK_BITS, ARG_ROUTING_EXPORT_LEGACY_STATE);

    private static final long TICK_MILLIS = 60_000, SYNC_MILLIS = 10 * 60_000, ADDRESS_MILLIS = 10 * 60_000;

    public final ServerRouting server;
    public final RoutingNode node;
    private final ScheduledExecutorService timer;

    private RoutingService(ServerRouting server, RoutingNode node, ScheduledExecutorService timer) {
        this.server = server;
        this.node = node;
        this.timer = timer;
    }

    public static Optional<RoutingService> start(Args a,
                                                 Optional<PrivKey> identity,
                                                 Optional<ReachabilityManager> reachability,
                                                 ServerIdentityStore ids,
                                                 Crypto crypto,
                                                 SqlSupplier commands,
                                                 Supplier<Connection> db,
                                                 DeletableContentAddressedStorage storage,
                                                 HttpPoster p2p) {
        if (! a.getBoolean(ARG_ROUTING_LOG.name, false))
            return Optional.empty();
        Optional<PrivKey> key = identity.or(() -> a.getOptionalArg("ipfs.identity.priv-key")
                .map(k -> KeyKt.unmarshalPrivateKey(Base64.getDecoder().decode(k))));
        if (key.isEmpty()) {
            LOG.warning("The routing log needs this server's identity key");
            return Optional.empty();
        }
        PrivKey priv = key.get();
        if (! ServerSigningKeys.isEd25519(priv)) {
            LOG.warning("The routing log needs an Ed25519 server identity; rotate this server's identity first");
            return Optional.empty();
        }
        PeerId current = PeerId.fromPubKey(priv.publicKey());
        JdbcRoutingStore store = new JdbcRoutingStore(db, commands);
        int threads = a.getInt(ARG_ROUTING_POW_THREADS.name, Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        Optional<CuckooCycle> testWork = a.getOptionalArg(ARG_ROUTING_WORK_BITS.name)
                .map(bits -> new CuckooCycle(Integer.parseInt(bits), 0));
        ServerRouting server = ServerRouting.start(store, ServerSigningKeys.fromLibp2p(priv, crypto.signer),
                ServerRouting.committedNext(ids, current, crypto), crypto,
                testWork.orElse(CuckooCycle.IDENTITY), testWork.orElse(CuckooCycle.SERVER), threads,
                replica -> importLegacy(a, replica, storage, crypto), s -> {});
        List<String> announced = a.getOptionalArg("ipfs-announce-addresses")
                .map(addrs -> Arrays.asList(addrs.split(",")))
                .orElse(Collections.emptyList());
        ServerAddressPublisher addresses = new ServerAddressPublisher(server.replica, server.serverKey, announced,
                () -> reachability.map(r -> r.getConfirmedPublicAddresses().stream()
                        .map(Object::toString)
                        .collect(Collectors.toList()))
                        .orElse(Collections.emptyList()),
                ServerAddresses.Mode.Archival, System::currentTimeMillis);
        ExecutorService work = Executors.newFixedThreadPool(4, daemon("Routing replication"));
        RoutingNode node = new RoutingNode(server.replica, crypto.hasher, System::currentTimeMillis,
                a.getInt(ARG_ROUTING_INCEPTIONS_PER_DAY.name, RateLimits.DEFAULT_INCEPTIONS_PER_DAY),
                peer -> AddressBucket.UNVERIFIED, peer -> new HttpRoutingPeer(peer, p2p), work);
        for (String peer : a.getOptionalArg(ARG_ROUTING_PEERS.name).map(s -> s.split(",")).orElse(new String[0]))
            if (! peer.isBlank())
                node.addSyncPeer(new HttpRoutingPeer(Cid.decodePeerId(peer.trim()).bareMultihash(), p2p));
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(daemon("Routing timer"));
        timer.scheduleWithFixedDelay(() -> run(node::tick), TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(() -> run(node::syncRound), 0, SYNC_MILLIS, TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(() -> run(addresses::refresh), 0, ADDRESS_MILLIS, TimeUnit.MILLISECONDS);
        reachability.ifPresent(r -> r.addListener(state -> timer.execute(() -> run(addresses::refresh))));
        LOG.info("Routing log started for " + server.peerId + " with " + node.syncPeers().size() + " sync peers");
        return Optional.of(new RoutingService(server, node, timer));
    }

    private static void run(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Routing task failed: " + t.getMessage(), t);
        }
    }

    private static void importLegacy(Args a, RoutingReplica replica, DeletableContentAddressedStorage storage, Crypto crypto) {
        Optional<String> path = a.getOptionalArg(ARG_ROUTING_LEGACY_STATE.name);
        Optional<String> hash = a.getOptionalArg(ARG_ROUTING_LEGACY_STATE_HASH.name);
        if (path.isEmpty() || hash.isEmpty())
            return;
        try {
            byte[] raw = Files.readAllBytes(Paths.get(path.get()));
            Cid pinned = Cid.decode(hash.get());
            if (replica.store().getMeta(LegacyImport.META_KEY).isPresent())
                return;
            LegacyState state = LegacyState.fromCbor(peergos.shared.cbor.CborObject.fromByteArray(raw));
            PublicKeyHash pkiOwner = PublicKeyHash.fromString(a.getArg("peergos.identity.hash"));
            Multihash pki = Builder.getPkiServerId(a).bareMultihash();
            List<Multihash> sources = List.of(new Cid(1, Cid.Codec.LibP2pKey, pki.type, pki.getHash()));
            LegacyImport.run(raw, pinned, LegacyImport.chains(state, pkiOwner, sources, storage, crypto), storage, replica, crypto);
        } catch (Exception e) {
            throw new IllegalStateException("Legacy import failed, so the routing log is not started: " + e.getMessage(), e);
        }
    }

    /** Writes the PKI's state as of its current WriterData, with the rotations of every server its claims name. */
    public static void exportLegacy(Args a,
                                    PublicKeyHash pkiKey,
                                    MutablePointers pointers,
                                    DeletableContentAddressedStorage storage,
                                    Crypto crypto) throws Exception {
        Optional<String> path = a.getOptionalArg(ARG_ROUTING_EXPORT_LEGACY_STATE.name);
        if (path.isEmpty())
            return;
        PublicKeyHash pkiOwner = PublicKeyHash.fromString(a.getArg("peergos.identity.hash"));
        Cid writerData = (Cid) pointers.getPointerTarget(pkiOwner, pkiKey, storage).join().updated.get();
        LegacyState pin = new LegacyState(writerData, List.of());
        Map<String, List<UserPublicKeyLink>> chains = LegacyImport.chains(pin, pkiOwner, List.of(), storage, crypto);
        LegacyState state = LegacyExport.build(writerData, chains, server -> {
            try {
                return Optional.ofNullable(storage.getIpnsEntry(server).get(30, TimeUnit.SECONDS));
            } catch (Exception e) {
                return Optional.empty();
            }
        });
        byte[] raw = state.serialize();
        Files.write(Paths.get(path.get()), raw);
        LOG.info("Wrote the PKI's legacy state for " + chains.size() + " users and " + state.servers.size() +
                " server rotations to " + path.get() + ", hash " + LegacyImport.hash(raw, crypto));
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    public void stop() {
        timer.shutdownNow();
        server.stop();
    }
}
