package peergos.server.tests.simulation;

import org.junit.*;
import peergos.server.routing.*;
import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;

import java.util.*;
import java.util.stream.*;

import static peergos.server.tests.simulation.RoutingSimulation.*;

/** Floods, partitions and reachability across many routing nodes. Not part of the regular suite: run it with
 *  ant execute.test -Dtest.source.absolute=$(realpath src/peergos/server/tests/simulation/RoutingSimulationTests.java)
 *  with SIM_NODES=N in the environment to scale the convergence run, and SIM_ONLY=method to run one scenario.
 */
public class RoutingSimulationTests {
    private static final int NODES = Integer.parseInt(Optional.ofNullable(System.getenv("SIM_NODES")).orElse("60"));

    @Rule
    public org.junit.rules.TestName name = new org.junit.rules.TestName();

    @Before
    public void only() {
        Optional<String> only = Optional.ofNullable(System.getenv("SIM_ONLY"));
        Assume.assumeTrue(only.isEmpty() || only.get().equals(name.getMethodName()));
    }

    private static void log(String s) {
        System.out.println("[sim] " + s);
    }

    /** Honest full nodes, three to a /16, configured with the first three as seeds, each hosting some users. */
    private static List<SimNode> network(Net net, int nodes, int usersEach, List<Subject> identities) {
        List<SimNode> res = new ArrayList<>();
        for (int i = 0; i < nodes; i++)
            res.add(net.node(Optional.of(i / 3), i % 2 == 0 ? ServerAddresses.Mode.Archival : ServerAddresses.Mode.Compact,
                    Behaviour.HONEST));
        SimNode[] seeds = res.subList(0, 3).toArray(new SimNode[0]);
        for (SimNode n : res) {
            n.configure(Arrays.stream(seeds).filter(s -> s != n).toArray(SimNode[]::new));
            for (int u = 0; u < usersEach; u++)
                identities.add(n.admit(key()));
        }
        return res;
    }

    private static int converge(Net net, Collection<SimNode> nodes, Collection<Subject> subjects, int maxRounds) {
        for (int round = 1; round <= maxRounds; round++) {
            net.step(10 * MINUTE);
            if (round % 3 == 0)
                net.choosePeers();
            if (nodes.stream().allMatch(n -> subjects.stream().allMatch(n::holds)))
                return round;
        }
        return -1;
    }

    private static List<Subject> servers(Collection<SimNode> nodes) {
        return nodes.stream().map(n -> n.subject).collect(Collectors.toList());
    }

    @Test
    public void everyIdentityReachesEveryNodeAndNewcomersCatchUp() {
        long start = System.currentTimeMillis();
        Net net = new Net(1, 10_000);
        List<Subject> identities = new ArrayList<>();
        List<SimNode> nodes = network(net, NODES, 3, identities);
        List<Subject> all = new ArrayList<>(identities);
        all.addAll(servers(nodes));

        int rounds = converge(net, nodes, all, 30);
        long served = nodes.stream().mapToLong(n -> n.recordRequests.get()).sum();
        log(NODES + " nodes, " + all.size() + " subjects: converged in " + rounds + " rounds of 10 minutes, "
                + String.format("%.2f", served / (double) (all.size() * (NODES - 1))) + " fetches per subject per node, "
                + (System.currentTimeMillis() - start) + " ms");
        Assert.assertTrue("converged", rounds > 0);
        net.choosePeers();
        net.step(10 * MINUTE);
        Assert.assertTrue("every node is synced", nodes.stream().allMatch(n -> n.routing.isSynced()));
        Assert.assertTrue("every node draws peers in several buckets",
                nodes.stream().allMatch(n -> n.routing.syncPeers().size() >= 4));

        SimNode newcomer = net.node(Optional.of(10_000), ServerAddresses.Mode.Compact, Behaviour.HONEST).configure(nodes.get(5));
        Subject newcomerUser = newcomer.admit(key());
        all.add(newcomer.subject);
        all.add(newcomerUser);
        List<SimNode> withNewcomer = new ArrayList<>(nodes);
        withNewcomer.add(newcomer);
        int joined = converge(net, withNewcomer, all, 30);
        log("A newcomer with one non-seed peer, and its new user, reached by and reaching every node in " + joined + " rounds");
        Assert.assertTrue(joined > 0);
        net.step(10 * MINUTE);
        Assert.assertTrue(newcomer.routing.isSynced());
    }

    /** Honest full nodes, one per /16, all configured with the first, with peers drawn and their records spread. */
    private static List<SimNode> honest(Net net, int count, int firstBucket) {
        List<SimNode> res = new ArrayList<>();
        for (int i = 0; i < count; i++)
            res.add(net.node(Optional.of(firstBucket + i), ServerAddresses.Mode.Archival, Behaviour.HONEST));
        for (SimNode n : res)
            if (n != res.get(0))
                n.configure(res.get(0));
        res.get(0).configure(res.get(1));
        return res;
    }

    private static void settle(Net net, List<SimNode> nodes) {
        Assert.assertTrue(converge(net, nodes, servers(nodes), 20) > 0);
        net.choosePeers();
        net.step(10 * MINUTE);
    }

    private static long held(SimNode n, Collection<Subject> subjects) {
        return subjects.stream().filter(n::holds).count();
    }

    @Test
    public void anInceptionFloodFromOneRangeOnlyDelaysTheAttackersOwnIdentities() {
        int perDay = 50;
        Net net = new Net(2, perDay);
        List<SimNode> nodes = honest(net, 12, 0);
        List<SimNode> attackers = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            attackers.add(net.node(Optional.of(900), ServerAddresses.Mode.Archival, Behaviour.HONEST).configure(nodes.get(0)));
        SimNode neighbour = net.node(Optional.of(900), ServerAddresses.Mode.Archival, Behaviour.HONEST).configure(nodes.get(0));
        List<SimNode> everyone = new ArrayList<>(nodes);
        everyone.addAll(attackers);
        everyone.add(neighbour);
        settle(net, everyone);

        List<Subject> flood = new ArrayList<>(), honestUsers = new ArrayList<>(), neighbours = new ArrayList<>();
        for (SimNode a : attackers)
            for (int i = 0; i < 250; i++)
                flood.add(a.admit(key()));
        for (int i = 0; i < 5; i++) {
            honestUsers.add(nodes.get(5).admit(key()));
            neighbours.add(neighbour.admit(key()));
        }
        List<SimNode> victims = nodes.subList(1, nodes.size()).stream().filter(n -> n != nodes.get(5)).collect(Collectors.toList());
        net.work.drain();
        int honestMinutes = reachedAll(victims, honestUsers) ? 0 : -1, neighbourMinutes = reachedAll(victims, neighbours) ? 0 : -1;
        for (int minute = 1; minute <= 9 && (honestMinutes < 0 || neighbourMinutes < 0); minute++) {
            net.tick(MINUTE);
            if (honestMinutes < 0 && victims.stream().allMatch(v -> held(v, honestUsers) == honestUsers.size()))
                honestMinutes = minute;
            if (neighbourMinutes < 0 && victims.stream().allMatch(v -> held(v, neighbours) == neighbours.size()))
                neighbourMinutes = minute;
        }
        log("Before any reconciliation round: honest users reached every node in " + honestMinutes
                + " minutes (0 being by announcements alone), the neighbour's in " + neighbourMinutes
                + "; the flood reached at most " + victims.stream().mapToLong(v -> held(v, flood)).max().getAsLong());
        Assert.assertEquals("honest signups spread by announcement, without waiting for a tick or a round", 0, honestMinutes);
        int honestHours = -1, neighbourHours = -1;
        for (int hour = 1; hour <= 48; hour++) {
            net.step(HOUR);
            if (honestHours < 0 && victims.stream().allMatch(v -> held(v, honestUsers) == honestUsers.size()))
                honestHours = hour;
            if (neighbourHours < 0 && victims.stream().allMatch(v -> held(v, neighbours) == neighbours.size()))
                neighbourHours = hour;
            if (hour == 24 || hour == 48) {
                long most = victims.stream().mapToLong(v -> held(v, flood)).max().getAsLong();
                long least = victims.stream().mapToLong(v -> held(v, flood)).min().getAsLong();
                log("Flood of " + flood.size() + " from one /16 at " + perDay + " a day per bucket: after " + hour
                        + " hours each node holds " + least + " to " + most);
                Assert.assertTrue("the flood propagates at its bucket's rate", most <= perDay * (1 + hour / 24) + perDay);
            }
        }
        log("Honest users elsewhere reached every node in " + honestHours + " hours; an honest server's users in the "
                + "attacker's /16 in " + neighbourHours);
        Assert.assertEquals(1, honestHours);
        Assert.assertTrue("round-robin by admitting server gives the neighbour turns", neighbourHours > 0 && neighbourHours <= 6);
    }

    @Test
    public void serversBehindRelaysShareOneBucket() {
        int perDay = 50;
        Net net = new Net(3, perDay);
        List<SimNode> nodes = honest(net, 8, 0);
        List<SimNode> relayed = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            relayed.add(net.node(Optional.empty(), ServerAddresses.Mode.Archival, Behaviour.HONEST).configure(nodes.get(0)));
        SimNode honestRelayed = net.node(Optional.empty(), ServerAddresses.Mode.Archival, Behaviour.HONEST).configure(nodes.get(0));
        List<SimNode> everyone = new ArrayList<>(nodes);
        everyone.addAll(relayed);
        everyone.add(honestRelayed);
        settle(net, everyone);

        List<Subject> flood = new ArrayList<>(), users = new ArrayList<>();
        for (SimNode a : relayed)
            for (int i = 0; i < 250; i++)
                flood.add(a.admit(key()));
        for (int i = 0; i < 5; i++)
            users.add(honestRelayed.admit(key()));
        net.work.drain();
        int minutes = reachedAll(nodes.subList(1, nodes.size()), users) ? 0 : -1;
        for (int minute = 1; minute <= 9 && minutes < 0; minute++) {
            net.tick(MINUTE);
            if (nodes.subList(1, nodes.size()).stream().allMatch(v -> held(v, users) == users.size()))
                minutes = minute;
        }
        log("Before any reconciliation round, the relayed server's users reached every node in " + minutes
                + " minutes (0 being by announcements alone)");
        int hours = -1;
        for (int hour = 1; hour <= 24 && hours < 0; hour++) {
            net.step(HOUR);
            if (nodes.subList(1, nodes.size()).stream().allMatch(v -> held(v, users) == users.size()))
                hours = hour;
        }
        long most = nodes.stream().mapToLong(v -> held(v, flood)).max().getAsLong();
        log("Relayed servers: a flood of " + flood.size() + " shares the unverified bucket with an honest relayed server, "
                + "whose users reached every node in " + hours + " hours; the flood reached at most " + most + " per node");
        Assert.assertTrue("round-robin by admitting server still gives it turns", hours > 0);
    }

    private static boolean reachedAll(List<SimNode> nodes, List<Subject> users) {
        return nodes.stream().allMatch(v -> held(v, users) == users.size());
    }

    /** A server's records known to these nodes, as if it had registered long ago. */
    private static void known(SimNode server, Collection<SimNode> to) {
        for (SimNode n : to)
            for (peergos.shared.routing.SignedRecord r : server.routing.records(server.subject))
                n.replica.add(server.subject, r);
    }

    @Test
    public void sybilsInAFewRangesCantEclipseANodeWithOneHonestSeed() {
        Net net = new Net(4, 10_000);
        List<SimNode> nodes = honest(net, 20, 0);
        List<Subject> users = new ArrayList<>();
        List<SimNode> sybils = new ArrayList<>();
        for (int i = 0; i < 40; i++)
            sybils.add(net.node(Optional.of(500 + i % 2), ServerAddresses.Mode.Archival, Behaviour.WITHHOLD));
        for (SimNode s : sybils)
            known(s, nodes);
        settle(net, nodes);
        for (SimNode n : nodes)
            for (int i = 0; i < 2; i++)
                users.add(n.admit(key()));
        net.step(10 * MINUTE);

        SimNode victim = net.node(Optional.of(700), ServerAddresses.Mode.Archival, Behaviour.HONEST).configure(sybils.get(0), nodes.get(3));
        SimNode alone = net.node(Optional.of(701), ServerAddresses.Mode.Archival, Behaviour.HONEST).configure(sybils.get(0));
        int rounds = converge(net, List.of(victim), users, 20);
        net.choosePeers();
        long drawnSybils = victim.peers.drawn().stream().filter(p -> sybils.stream().anyMatch(s -> s.id.equals(p.id()))).count();
        log("With a hostile and an honest seed, and 40 sybils in 2 /16s against 20 honest nodes, a newcomer held every "
                + "user after " + rounds + " rounds, " + drawnSybils + " of its " + victim.peers.drawn().size() + " drawn peers sybils");
        Assert.assertTrue(rounds > 0);
        Assert.assertTrue(victim.peers.drawn().size() >= 4);
        Assert.assertTrue("sybils get at most one slot per range", drawnSybils <= 2);
        log("With only the hostile seed, a newcomer holds " + held(alone, users) + " of " + users.size()
                + " users: the seed list is the one first-contact weakness (remove-pki §4.3.1)");
    }

    @Test
    public void aLightServerFindsWhatItsFullNodeWithholdsBySampling() {
        Net net = new Net(5, 10_000);
        List<SimNode> nodes = honest(net, 8, 0);
        SimNode hostile = net.node(Optional.of(600), ServerAddresses.Mode.Archival, Behaviour.WITHHOLD);
        known(hostile, nodes);
        settle(net, nodes);
        List<Subject> users = new ArrayList<>();
        for (SimNode n : nodes)
            users.add(n.admit(key()));
        net.step(10 * MINUTE);

        SimNode light = net.node(Optional.empty(), ServerAddresses.Mode.Light, Behaviour.HONEST).configure(hostile, nodes.get(2));
        SimNode trusting = net.node(Optional.empty(), ServerAddresses.Mode.Light, Behaviour.HONEST).configure(hostile);
        long found = users.stream().filter(u -> light.light.identity((peergos.shared.crypto.hash.PublicKeyHash) u.id).isPresent()).count();
        long foundTrusting = users.stream().filter(u -> trusting.light.identity((peergos.shared.crypto.hash.PublicKeyHash) u.id).isPresent()).count();
        log("A light server whose first full node withholds found " + found + " of " + users.size()
                + " users by sampling; one configured with that node alone found " + foundTrusting);
        Assert.assertEquals(users.size(), found);

        long before = nodes.stream().mapToLong(n -> n.recordRequests.get()).sum();
        for (int i = 0; i < 1_000; i++) {
            byte[] h = new byte[32];
            net.random.nextBytes(h);
            light.light.identity(new peergos.shared.crypto.hash.PublicKeyHash(new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, h)));
        }
        long asked = nodes.stream().mapToLong(n -> n.recordRequests.get()).sum() - before;
        log("1000 lookups of unknown identities on a light server asked full nodes " + asked + " times");
        Assert.assertTrue(asked <= (LightLookup.PULLS.burst + 1) * (1 + LightLookup.SAMPLE));
    }

    @Test
    public void anUpdateFloodIsLimitedAndOnlyTheNewestPassedOn() {
        Net net = new Net(6, 10_000);
        List<SimNode> nodes = honest(net, 6, 0);
        settle(net, nodes);
        SigningKeyPair owner = key();
        SimNode home = nodes.get(1);
        Subject s = home.admit(owner);
        net.step(10 * MINUTE);
        for (int i = 1; i <= 30; i++) {
            home.replica.add(s, IdentityMove.build(owner, List.of(home.id), i).join());
            net.step(10 * MINUTE);
        }
        List<Long> seen = nodes.stream().filter(n -> n != home)
                .map(n -> n.replica.identity(s).get().records.update.map(u -> u.record.counter).orElse(0L))
                .collect(Collectors.toList());
        int held = nodes.stream().mapToInt(n -> n.routing.heldUpdates()).sum();
        log("30 moves in 5 hours: other nodes applied up to moves " + seen + ", holding " + held + " newest for later");
        Assert.assertTrue("a burst of three, then held", seen.stream().allMatch(c -> c < 30));
        net.step(31 * DAY);
        net.step(10 * MINUTE);
        Assert.assertTrue("the newest arrives when a token is due", nodes.stream()
                .allMatch(n -> n.replica.identity(s).get().records.update.get().record.counter == 30));
    }

    @Test
    public void floodsOfAnnouncementsJunkAndUnprovenRecords() {
        Net net = new Net(7, 10_000);
        List<SimNode> nodes = honest(net, 4, 0);
        settle(net, nodes);
        SimNode victim = nodes.get(1), home = nodes.get(2);
        SigningKeyPair owner = key();
        Subject s = home.admit(owner);
        home.replica.add(s, IdentityMove.build(owner, List.of(home.id), 5).join());
        net.step(10 * MINUTE);
        Multihash attacker = ServerKeys.peerId(key().publicSigningKey), spammer = ServerKeys.peerId(key().publicSigningKey);

        long t = System.currentTimeMillis();
        for (int call = 0; call < 200; call++) {
            List<JdbcRoutingStore.State> junk = new ArrayList<>();
            for (int i = 0; i < RoutingHandler.MAX_ITEMS; i++) {
                byte[] h = new byte[32];
                net.random.nextBytes(h);
                junk.add(new JdbcRoutingStore.State(Subject.identity(new peergos.shared.crypto.hash.PublicKeyHash(
                        new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, h))), new byte[32]));
            }
            victim.routing.announced(attacker, junk);
        }
        int queued = net.work.size();
        net.work.drain();
        log("200 announcements of 1024 unknown states from one caller queued " + queued + " fetches, "
                + (System.currentTimeMillis() - t) + " ms");
        Assert.assertTrue(queued <= RoutingNode.MAX_FETCHING_PER_PEER);

        List<SignedRecord> forged = new ArrayList<>();
        IdentityMove real = IdentityMove.build(owner, List.of(attacker), 6).join();
        for (int i = 0; i < RoutingHandler.MAX_ITEMS; i++) {
            byte[] sig = real.signature.clone();
            sig[i % sig.length] ^= (byte) (1 + i / sig.length);
            forged.add(RecordType.withSignature(new IdentityMove(owner.publicSigningKey, List.of(attacker), 6 + i, real.signature), sig));
        }
        t = System.currentTimeMillis();
        Outcome o = victim.routing.offered(attacker, s, forged);
        long forgedMillis = System.currentTimeMillis() - t;
        log("One offer of " + forged.size() + " moves with forged signatures: " + o + ", " + forgedMillis
                + " ms of signature checks for the victim");
        Assert.assertEquals(5, victim.replica.identity(s).get().records.update.get().record.counter);
        Assert.assertTrue("an offer stops at its first forgery", forgedMillis < 500);
        t = System.currentTimeMillis();
        for (int i = 0; i < RoutingNode.REJECTIONS * 2; i++)
            victim.routing.offered(attacker, s, forged);
        log(RoutingNode.REJECTIONS * 2 + " more such offers took " + (System.currentTimeMillis() - t)
                + " ms: after " + RoutingNode.REJECTIONS + " forgeries a sender is ignored until its budget refills");
        Assert.assertEquals(Outcome.Rejected, victim.routing.offered(attacker, s, List.of(IdentityMove.build(owner, List.of(home.id), 7).join())));

        Outcome stale = victim.routing.offered(attacker, s, List.of(IdentityMove.build(owner, List.of(attacker), 1).join()));
        Assert.assertNotEquals("an old move doesn't roll the state back", Outcome.Added, stale);
        Assert.assertEquals(5, victim.replica.identity(s).get().records.update.get().record.counter);

        List<IdentityInception> spam = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            SigningKeyPair id = key();
            spam.add(IdentityInception.build(id, List.of(home.id), key().publicSigningKey, crypto.hasher).join()
                    .withAdmission(Admission.build(id.publicSigningKey, new byte[]{9}, home.key.secretSigningKey).join()));
        }
        t = System.currentTimeMillis();
        for (IdentityInception inc : spam)
            victim.routing.offered(spammer, Subject.identity(inc.identityHash()), List.of(inc));
        net.step(10 * MINUTE);
        long spread = nodes.stream().filter(n -> n != victim).mapToLong(n -> n.replica.store().count(Subject.IDENTITY)).max().getAsLong();
        log("2000 inceptions without proof of work, offered to one node: " + victim.replica.store().count(Subject.IDENTITY)
                + " identities stored there, at most " + spread + " at any other, " + (System.currentTimeMillis() - t) + " ms");
        Assert.assertEquals(1, victim.replica.store().count(Subject.IDENTITY));

        List<IdentityInception> unknownAdmitters = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            SigningKeyPair id = key(), madeUp = key();
            unknownAdmitters.add(IdentityInception.build(id, List.of(ServerKeys.peerId(madeUp.publicSigningKey)), key().publicSigningKey, crypto.hasher).join()
                    .withAdmission(Admission.build(id.publicSigningKey, new byte[]{9}, madeUp.secretSigningKey).join()));
        }
        Multihash third = ServerKeys.peerId(key().publicSigningKey);
        t = System.currentTimeMillis();
        for (IdentityInception inc : unknownAdmitters)
            victim.routing.offered(third, Subject.identity(inc.identityHash()), List.of(inc));
        log("500 inceptions without proof of work naming made-up admitting servers: " + victim.routing.waitingInceptions()
                + " waiting, " + (System.currentTimeMillis() - t) + " ms");
        Assert.assertEquals(0, victim.routing.waitingInceptions());
    }

    @Test
    public void aCompactNodeFetchesFromTheProviderOnEveryRequest() {
        Net net = new Net(8, 10_000);
        SimNode provider = net.node(Optional.of(1), ServerAddresses.Mode.Archival, Behaviour.HONEST);
        SimNode compact = net.node(Optional.of(2), ServerAddresses.Mode.Compact, Behaviour.HONEST);
        provider.configure(compact);
        compact.configure(provider);
        Subject user = provider.admit(key());
        settle(net, List.of(provider, compact));
        net.step(10 * MINUTE);
        Assert.assertTrue(compact.holds(user));
        long before = provider.recordRequests.get();
        for (int i = 0; i < 50; i++)
            compact.routing.records(user);
        log("50 requests to a compact node for one stripped identity made " + (provider.recordRequests.get() - before)
                + " requests to its provider");
        Assert.assertEquals(1, provider.recordRequests.get() - before);
    }

    @Test
    public void callsAreLimitedPerProvenServerAndFreshKeysShareAPool() {
        Net net = new Net(9, 10_000);
        List<SimNode> nodes = honest(net, 3, 0);
        settle(net, nodes);
        long[] now = {0};
        RoutingHandler handler = new RoutingHandler(nodes.get(0).routing, () -> now[0]);
        int proven = 0;
        while (handler.allow("summaries", nodes.get(1).id))
            proven++;
        int fresh = 0;
        while (handler.allow("summaries", ServerKeys.peerId(key().publicSigningKey)))
            fresh++;
        log("A proven server may make " + proven + " range scans at once; fresh keys between them " + fresh);
        Assert.assertEquals(RoutingHandler.SCANS.burst, proven);
        Assert.assertEquals(RoutingHandler.UNPROVEN_SCANS.burst, fresh);
        Assert.assertTrue("another proven server has its own", handler.allow("summaries", nodes.get(2).id));
        now[0] += RoutingHandler.SCANS.refillMillis;
        Assert.assertTrue(handler.allow("summaries", nodes.get(1).id));
    }

    @Test
    public void aSyncPeerThatAnswersAnythingCantStallOrFloodReconciliation() {
        Net net = new Net(10, 10_000);
        List<SimNode> nodes = honest(net, 3, 0);
        settle(net, nodes);
        SimNode victim = nodes.get(1);
        RoutingPeer looping = new SimPeer(victim.id, nodes.get(2).id, net) {
            @Override
            public java.util.concurrent.CompletableFuture<List<Reconciliation.Summary>> summaries(int kind, List<Reconciliation.Range> ranges) {
                return peergos.shared.util.Futures.of(ranges.stream().map(r -> new Reconciliation.Summary(1, new byte[32])).collect(Collectors.toList()));
            }

            @Override
            public java.util.concurrent.CompletableFuture<List<JdbcRoutingStore.State>> states(int kind, Reconciliation.Range range, int limit) {
                List<JdbcRoutingStore.State> same = new ArrayList<>();
                for (int i = 0; i < RoutingNode.PAGE; i++)
                    same.add(new JdbcRoutingStore.State(nodes.get(0).subject, new byte[32]));
                return peergos.shared.util.Futures.of(same);
            }
        };
        long t = System.currentTimeMillis();
        try {
            victim.routing.differences(looping, Subject.SERVER);
            Assert.fail("A page that doesn't move forward is refused");
        } catch (IllegalStateException expected) {}
        RoutingPeer flooding = new SimPeer(victim.id, nodes.get(2).id, net) {
            @Override
            public java.util.concurrent.CompletableFuture<List<Reconciliation.Summary>> summaries(int kind, List<Reconciliation.Range> ranges) {
                return peergos.shared.util.Futures.of(ranges.stream().map(r -> new Reconciliation.Summary(1, new byte[32])).collect(Collectors.toList()));
            }

            @Override
            public java.util.concurrent.CompletableFuture<List<JdbcRoutingStore.State>> states(int kind, Reconciliation.Range range, int limit) {
                List<JdbcRoutingStore.State> fake = new ArrayList<>();
                byte[] after = range.after.length == 0 ? nodes.get(0).id.toBytes() : range.after.clone();
                if (range.after.length == 0)
                    Arrays.fill(after, 6, after.length, (byte) 0);
                for (int i = 0; i < limit; i++) {
                    for (int j = after.length - 1; j >= 0 && ++after[j] == 0; j--) {}
                    if (range.upTo.map(u -> peergos.shared.util.ArrayOps.compare(after, u) > 0).orElse(false))
                        break;
                    fake.add(new JdbcRoutingStore.State(Subject.server(peergos.shared.io.ipfs.Multihash.decode(after.clone())), new byte[32]));
                }
                return peergos.shared.util.Futures.of(fake);
            }
        };
        List<JdbcRoutingStore.State> d = victim.routing.differences(flooding, Subject.SERVER);
        log("A peer looping on one page was refused, and one inventing states had " + d.size()
                + " taken in a round, " + (System.currentTimeMillis() - t) + " ms");
        Assert.assertTrue(d.size() <= RoutingNode.MAX_DIFFERENCES + RoutingNode.PAGE);
    }
}
