package peergos.server.routing;

import io.libp2p.core.*;
import io.libp2p.core.crypto.*;
import org.peergos.protocol.ipns.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.resolution.*;
import peergos.shared.routing.*;
import peergos.shared.storage.IpnsEntry;
import peergos.shared.util.*;

import java.nio.charset.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;

/** This server's part in the routing log: its replica, its own records, and the proof of work it owes. */
public class ServerRouting {
    private static final Logger LOG = Logging.LOG();
    /** What every next identity from server-identity gen-next was before 9be919c5b: its secret is public. */
    private static final String BROKEN_GEN_NEXT = "12D3KooWFeNXWhKkHecwJZGZEanQbniMs57XhJMvMhbXZkN4hPmF";

    public final RoutingReplica replica;
    public final AdmissionWorker admissions;
    public final Multihash peerId;
    public final SigningKeyPair serverKey;

    private ServerRouting(RoutingReplica replica, AdmissionWorker admissions, Multihash peerId, SigningKeyPair serverKey) {
        this.replica = replica;
        this.admissions = admissions;
        this.peerId = peerId;
        this.serverKey = serverKey;
    }

    public static ServerRouting start(JdbcRoutingStore store,
                                      SigningKeyPair serverKey,
                                      Optional<PublicSigningKey> committedNext,
                                      Crypto crypto,
                                      CuckooCycle identityWork,
                                      CuckooCycle serverWork,
                                      int threads,
                                      Consumer<Subject> onProven) {
        RoutingReplica replica = new RoutingReplica(store, new RecordValidator(crypto.hasher, identityWork, serverWork), identityWork, serverWork);
        Multihash peerId = ServerKeys.peerId(serverKey.publicSigningKey);
        AdmissionWorker admissions = new AdmissionWorker(replica, peerId, serverKey, identityWork, serverWork, threads, onProven);
        ensureInception(replica, serverKey, committedNext, crypto);
        admissions.start();
        return new ServerRouting(replica, admissions, peerId, serverKey);
    }

    /** Writes this server's inception if it has none, committing to the next key the operator generated with
     *  gen-next, or failing that to a fresh one kept in the store until the operator moves it off the server.
     *  The proof of work is owed from then on.
     */
    static void ensureInception(RoutingReplica replica, SigningKeyPair serverKey, Optional<PublicSigningKey> committedNext, Crypto crypto) {
        Multihash peerId = ServerKeys.peerId(serverKey.publicSigningKey);
        Subject self = Subject.server(peerId);
        if (replica.server(self).isPresent())
            return;
        JdbcRoutingStore store = replica.store();
        PublicSigningKey next = committedNext.orElseGet(() -> {
            byte[] seed = store.nextKey(peerId).orElseGet(() -> {
                byte[] fresh = ServerSigningKeys.seed(SigningKeyPair.random(crypto.random, crypto.signer));
                store.setNextKey(peerId, fresh);
                return fresh;
            });
            LOG.warning("This server's next routing key is stored on the server. Run server-identity gen-next " +
                    "before the routing log is enabled to keep it off the server.");
            return ServerSigningKeys.fromSeed(seed, crypto.signer).publicSigningKey;
        });
        ServerInception inception = ServerInception.build(serverKey, next, crypto.hasher).join();
        Outcome res = replica.add(self, inception);
        if (res != Outcome.Added)
            throw new IllegalStateException("Couldn't add our own server inception: " + res);
        store.addPending(self);
    }

    /** The next identity the operator generated with gen-next, if any, from the current identity's record. */
    public static Optional<PublicSigningKey> committedNext(ServerIdentityStore ids, PeerId current, Crypto crypto) {
        Optional<IpnsMapping> mapping = IPNS.parseAndValidateIpnsEntry(
                ArrayOps.concat("/ipns/".getBytes(StandardCharsets.UTF_8), current.getBytes()),
                ids.getRecord(current));
        if (mapping.isEmpty())
            return Optional.empty();
        ResolutionRecord res = new IpnsEntry(mapping.get().getSignature(), mapping.get().getData()).getValue();
        if (res.moved || res.host.isEmpty())
            return Optional.empty();
        if (res.host.get().toBase58().equals(BROKEN_GEN_NEXT)) {
            LOG.warning("This server's next identity from gen-next is publicly known; it is not used for the routing log.");
            return Optional.empty();
        }
        return ServerSigningKeys.fromPeerId(res.host.get(), crypto.signer);
    }

    public static SigningKeyPair serverKey(PrivKey priv, Crypto crypto) {
        return ServerSigningKeys.fromLibp2p(priv, crypto.signer);
    }

    public void stop() {
        admissions.stop();
    }
}
