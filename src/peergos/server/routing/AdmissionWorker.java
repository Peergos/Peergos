package peergos.server.routing;

import peergos.server.util.*;
import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.*;

/** Computes the proof of work this server owes: an admission for each identity it let in, and the proof on its
 *  own inception (remove-pki §4.5). Signup does not wait: the inception is in the replica, and so usable here, as
 *  soon as it is submitted; only passing it on waits for the proof. What is owed is kept in the store, so a
 *  restart resumes it. Identities go first, on several cores; the server's own hour of work runs on one
 *  low-priority thread beside them.
 */
public class AdmissionWorker {
    private static final Logger LOG = Logging.LOG();

    private final RoutingReplica replica;
    private final Multihash peerId;
    private final SigningKeyPair serverKey;
    private final CuckooCycle identityWork, serverWork;
    private final int threads;
    private final Consumer<Subject> onProven;
    private final ExecutorService identities, servers;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public AdmissionWorker(RoutingReplica replica,
                           Multihash peerId,
                           SigningKeyPair serverKey,
                           CuckooCycle identityWork,
                           CuckooCycle serverWork,
                           int threads,
                           Consumer<Subject> onProven) {
        this.replica = replica;
        this.peerId = peerId.bareMultihash();
        this.serverKey = serverKey;
        this.identityWork = identityWork;
        this.serverWork = serverWork;
        this.threads = threads;
        this.onProven = onProven;
        this.identities = Executors.newSingleThreadExecutor(daemon("Admission worker", Thread.NORM_PRIORITY));
        this.servers = Executors.newSingleThreadExecutor(daemon("Server proof of work", Thread.MIN_PRIORITY));
    }

    private static ThreadFactory daemon(String name, int priority) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setPriority(priority);
            return t;
        };
    }

    public void start() {
        for (Subject s : replica.store().pending())
            schedule(s);
    }

    public void stop() {
        stopped.set(true);
        identities.shutdownNow();
        servers.shutdownNow();
    }

    /** Takes in an inception signed by a client signing up here, and schedules its admission. */
    public Outcome submit(IdentityInception inception) {
        if (! inception.admittingServer().bareMultihash().equals(peerId))
            return Outcome.Rejected;
        Subject s = replica.canonical(Subject.identity(inception.identityHash()));
        Outcome res = replica.add(s, inception);
        if (res == Outcome.Added || res == Outcome.Duplicate)
            owe(s);
        return res;
    }

    /** Schedules the proof of work on an inception already in the replica. */
    public void owe(Subject s) {
        replica.store().addPending(s);
        schedule(s);
    }

    private void schedule(Subject s) {
        (s.isIdentity() ? identities : servers).submit(() -> prove(s));
    }

    private void prove(Subject s) {
        try {
            if (! replica.isProven(s, false)) {
                SignedRecord inception = replica.log(s).inception()
                        .orElseThrow(() -> new IllegalStateException("No inception for " + s)).record;
                Optional<SignedRecord> proven = inception instanceof IdentityInception ?
                        admit(s, (IdentityInception) inception) :
                        prove((ServerInception) inception);
                if (proven.isEmpty())
                    return;
                Outcome res = replica.add(s, proven.get());
                if (res != Outcome.Added)
                    LOG.warning("Proof of work for " + s + " was not taken: " + res);
            }
            replica.store().removePending(s);
            onProven.accept(s);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Proof of work for " + s + " failed: " + t.getMessage(), t);
        }
    }

    /** A legacy identity is already in the pinned state, so its admission carries no proof of work. */
    private Optional<SignedRecord> admit(Subject s, IdentityInception inception) {
        if (replica.identity(s).map(IdentityLog::isLegacy).orElse(false))
            return Optional.of(inception.withAdmission(Admission.build(inception.identity, new byte[0], serverKey.secretSigningKey).join()));
        byte[] subject = inception.identity.serialize();
        Optional<CuckooCycle.Proof> proof = new CuckooSolver(identityWork).solveParallel(subject, threads, Long.MAX_VALUE / threads);
        if (proof.isEmpty())
            return Optional.empty();
        Admission admission = Admission.build(inception.identity, proof.get().serialize(), serverKey.secretSigningKey).join();
        return Optional.of(inception.withAdmission(admission));
    }

    private Optional<SignedRecord> prove(ServerInception inception) {
        byte[] subject = inception.server.serialize();
        CuckooSolver solver = new CuckooSolver(serverWork);
        for (long nonce = 0; ! stopped.get(); nonce++) {
            Optional<CuckooCycle.Proof> proof = solver.solveNonce(subject, nonce);
            if (proof.isPresent())
                return Optional.of(inception.withProof(proof.get().serialize()));
        }
        return Optional.empty();
    }
}
