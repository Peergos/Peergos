package peergos.server.tests.simulation;

import org.junit.*;
import peergos.server.routing.*;
import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;

import java.util.*;
import java.util.stream.*;

import static peergos.server.tests.simulation.RoutingSimulation.*;
import static peergos.server.tests.simulation.RoutingSimulationTests.*;

/** Attacks by full nodes on the routing log: withholding, censoring and lying, laundering floods, splitting the
 *  network with conflicting records, starving new servers, and going offline. Run like {@link RoutingSimulationTests}.
 */
public class RoutingAttackSimulationTests {

    @Rule
    public org.junit.rules.TestName name = new org.junit.rules.TestName();

    @Before
    public void only() {
        Optional<String> only = Optional.ofNullable(System.getenv("SIM_ONLY"));
        Assume.assumeTrue(only.isEmpty() || only.get().equals(name.getMethodName()));
    }

    /** Full nodes one per /16, synced while all honest, after which the last {@code hostile} turn to this behaviour. */
    private static List<SimNode> fleet(Net net, int honest, int hostile, Behaviour b) {
        List<SimNode> all = honest(net, honest + hostile, 0);
        settle(net, all);
        for (SimNode n : all.subList(honest, all.size()))
            n.behaviour = b;
        net.choosePeers();
        return all;
    }

    private static Set<Multihash> ids(Collection<SimNode> nodes) {
        return nodes.stream().map(n -> n.id).collect(Collectors.toSet());
    }

    /** Of the peers the honest nodes drew, the share that are hostile. */
    private static double drawnShare(List<SimNode> good, List<SimNode> bad) {
        Set<Multihash> hostile = ids(bad);
        long all = 0, hostileDrawn = 0;
        for (SimNode n : good)
            for (peergos.server.routing.RoutingPeer p : n.peers.drawn()) {
                all++;
                if (hostile.contains(p.id().bareMultihash()))
                    hostileDrawn++;
            }
        return all == 0 ? 0 : hostileDrawn / (double) all;
    }

    private static long suspected(List<SimNode> by, List<SimNode> of) {
        Set<Multihash> targets = ids(of);
        return by.stream().mapToLong(n -> n.routing.suspects().stream().filter(targets::contains).count()).sum();
    }

    private static long counter(SimNode n, Subject s) {
        return n.replica.identity(s).flatMap(l -> l.records.update.map(u -> u.record.counter())).orElse(0L);
    }

    private static String pct(double d) {
        return String.format("%.0f%%", 100 * d);
    }

    @Test
    public void halfTheFullNodesWithholdingSlowsNothingAndTheyAreDropped() {
        Net net = new Net(11, 10_000);
        List<SimNode> all = fleet(net, 16, 16, Behaviour.WITHHOLD);
        net.dropAnnouncements = true;
        List<SimNode> good = all.subList(0, 16), bad = all.subList(16, 32);
        double before = drawnShare(good, bad);

        Map<Subject, Integer> born = new HashMap<>();
        Map<Subject, SigningKeyPair> owners = new HashMap<>();
        Map<Subject, Integer> moved = new HashMap<>();
        List<Integer> delays = new ArrayList<>(), moveDelays = new ArrayList<>();
        int rounds = 30;
        for (int round = 1; round <= rounds; round++) {
            for (int i = 0; i < 4; i++) {
                SigningKeyPair owner = key();
                Subject u = good.get(net.random.nextInt(good.size())).admit(owner);
                born.put(u, round);
                owners.put(u, owner);
            }
            if (round > 6 && round <= 24) {
                List<Subject> old = born.entrySet().stream().filter(e -> e.getValue() <= 3 && ! moved.containsKey(e.getKey()))
                        .map(Map.Entry::getKey).sorted(Comparator.comparing(Subject::toString)).collect(Collectors.toList());
                if (! old.isEmpty()) {
                    Subject u = old.get(0);
                    SimNode home = good.stream().filter(n -> n.replica.identity(u).isPresent() && n.replica.identity(u).get().providers().contains(n.id)).findFirst().get();
                    home.replica.add(u, IdentityMove.build(owners.get(u), List.of(home.id), 1).join());
                    moved.put(u, round);
                }
            }
            net.step(10 * MINUTE);
            if (round % 3 == 0)
                net.choosePeers();
            for (Iterator<Map.Entry<Subject, Integer>> it = born.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Subject, Integer> e = it.next();
                if (e.getValue() > 0 && good.stream().allMatch(n -> n.holds(e.getKey()))) {
                    delays.add(round - e.getValue() + 1);
                    e.setValue(-e.getValue());
                }
            }
            for (Map.Entry<Subject, Integer> e : moved.entrySet())
                if (e.getValue() > 0 && good.stream().allMatch(n -> counter(n, e.getKey()) == 1)) {
                    moveDelays.add(round - e.getValue() + 1);
                    e.setValue(-e.getValue());
                }
        }
        long unspread = born.values().stream().filter(v -> v > 0).count();
        double after = drawnShare(good, bad);
        long caught = suspected(good, bad), wrong = suspected(good, good);
        log("Half the full nodes withholding, announcements lost: " + delays.size() + " new users reached every honest node in "
                + delays.stream().mapToInt(i -> i).max().orElse(-1) + " rounds at most (" + unspread + " not yet), "
                + moveDelays.size() + " moves in " + moveDelays.stream().mapToInt(i -> i).max().orElse(-1) + " at most");
        log("Withholders were " + pct(before) + " of the peers honest nodes drew, and " + pct(after) + " after "
                + rounds + " rounds; honest nodes suspect " + caught + " withholders between them (of "
                + good.size() * bad.size() + " pairs), and " + wrong + " honest nodes");
        Assert.assertEquals("no honest node is taken for a withholder", 0, wrong);
        Assert.assertTrue("every user spread within three rounds", delays.stream().allMatch(d -> d <= 3));
        Assert.assertTrue("every move spread within three rounds", moveDelays.stream().allMatch(d -> d <= 3));
        Assert.assertTrue("withholders are dropped", after < before / 2);
    }

    @Test
    public void halfTheFullNodesHidingOneUsersMoveCantHideIt() {
        for (Behaviour b : List.of(Behaviour.CENSOR, Behaviour.LIAR)) {
            Net net = new Net(12, 10_000);
            List<SimNode> all = fleet(net, 12, 12, b);
            List<SimNode> good = all.subList(0, 12), bad = all.subList(12, 24);
            SigningKeyPair owner = key();
            SimNode home = good.get(1);
            Subject target = home.admit(owner);
            net.step(10 * MINUTE);
            net.step(10 * MINUTE);
            Assert.assertTrue(all.stream().allMatch(n -> n.holds(target)));
            for (SimNode n : bad)
                n.freeze(target);
            net.dropAnnouncements = true;
            long before = all.stream().mapToLong(n -> n.recordRequests.get()).sum();
            home.replica.add(target, IdentityMove.build(owner, List.of(good.get(2).id), 1).join());
            int rounds = -1;
            for (int round = 1; round <= 12 && rounds < 0; round++) {
                net.step(10 * MINUTE);
                if (good.stream().allMatch(n -> counter(n, target) == 1))
                    rounds = round;
            }
            for (int round = 0; round < 3; round++)
                net.step(10 * MINUTE);
            long fetches = all.stream().mapToLong(n -> n.recordRequests.get()).sum() - before;
            log(b + ": with half the full nodes hiding a user's move, it reached every honest node in " + rounds
                    + " rounds by reconciliation alone; " + suspected(good, bad) + " of " + good.size() * bad.size()
                    + " honest-hostile pairs suspected, " + suspected(good, good) + " honest; " + fetches + " fetches in "
                    + (rounds + 3) + " rounds");
            Assert.assertTrue(rounds > 0 && rounds <= 3);
            Assert.assertEquals(0, suspected(good, good));
        }
    }

    @Test
    public void aMajorityOfSyncPeersCanLaunderAFloodAsCatchUp() {
        int perDay = 50;
        Net net = new Net(13, perDay);
        List<SimNode> all = fleet(net, 12, 12, Behaviour.WITHHOLD);
        List<SimNode> good = all.subList(0, 12), bad = all.subList(12, 24);
        SimNode admitting = bad.get(0);
        List<Subject> flood = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            Subject s = admitting.admit(key());
            flood.add(s);
            for (SimNode n : bad)
                if (n != admitting)
                    n.replica.add(s, admitting.replica.identity(s).get().records.inception.get().record);
        }
        Map<Integer, String> at = new TreeMap<>();
        for (int hour = 1; hour <= 24; hour++) {
            net.step(HOUR);
            if (hour == 1 || hour == 24) {
                LongSummaryStatistics st = good.stream().mapToLong(n -> held(n, flood)).summaryStatistics();
                at.put(hour, st.getMin() + " to " + st.getMax());
            }
        }
        long laundered = good.stream().filter(n -> held(n, flood) > 2 * perDay).count();
        log("Half the full nodes, each in its own /16, holding a flood of 1000 from one admitting server at " + perDay
                + " a day: honest nodes held " + at + " after those hours; " + laundered + " of " + good.size()
                + " took more than the bucket's rate allows, as catch-up");
        Assert.assertEquals("catch-up needs a configured peer as well as a majority", 0, laundered);
    }

    @Test
    public void anIdentitySigningTwoInceptionsSettlesEverywhere() {
        Net net = new Net(14, 10_000);
        List<SimNode> good = honest(net, 10, 0);
        settle(net, good);
        SigningKeyPair id = key();
        IdentityInception[] incs = new IdentityInception[2];
        for (int i = 0; i < 2; i++) {
            SimNode server = good.get(1 + i);
            incs[i] = IdentityInception.build(id, List.of(server.id), key().publicSigningKey, crypto.hasher).join()
                    .withAdmission(Admission.build(id.publicSigningKey, GOOD_PROOF, server.key.secretSigningKey).join());
        }
        Subject s = Subject.identity(incs[0].identityHash());
        good.get(1).replica.add(s, incs[0]);
        good.get(2).replica.add(s, incs[1]);
        for (int round = 0; round < 6; round++)
            net.step(10 * MINUTE);
        long before = good.stream().mapToLong(n -> n.recordRequests.get()).sum();
        for (int round = 0; round < 6; round++)
            net.step(10 * MINUTE);
        long perRound = (good.stream().mapToLong(n -> n.recordRequests.get()).sum() - before) / 6;
        Set<String> states = good.stream().map(n -> peergos.shared.util.ArrayOps.bytesToHex(n.replica.stateHash(s))).collect(Collectors.toSet());
        Set<String> providers = good.stream().map(n -> n.replica.identity(s).get().providers().toString()).collect(Collectors.toSet());
        long warned = good.stream().filter(n -> n.replica.identity(s).get().hasDuplicityEvidence()).count();
        log("An identity key signing two inceptions given to two nodes at once: after 12 rounds " + states.size()
                + " distinct states and " + providers.size() + " distinct providers among " + good.size() + " nodes, "
                + warned + " showing the duplicity, " + perRound + " fetches a round in the network for it after settling");
        Assert.assertEquals(1, states.size());
        Assert.assertEquals(good.size(), warned);
        Assert.assertEquals(0, perRound);
    }

    @Test
    public void floodsOfRelayedServersDelayAnHonestRelayedServer() {
        int perDay = 50;
        Net net = new Net(15, perDay);
        List<SimNode> good = honest(net, 8, 0);
        SimNode attacker = net.node(Optional.of(900), ServerAddresses.Mode.Archival, Behaviour.WITHHOLD);
        known(attacker, good);
        settle(net, good);
        for (int i = 0; i < 1000; i++) {
            SigningKeyPair k = key();
            ServerInception si = ServerInception.build(k, key().publicSigningKey, crypto.hasher).join().withProof(GOOD_PROOF);
            attacker.replica.add(Subject.server(si.peerId().bareMultihash()), si);
        }
        for (SimNode n : good)
            n.routing.addSyncPeer(new SimPeer(n.id, attacker.id, net));
        net.step(10 * MINUTE);
        SimNode relayed = net.node(Optional.empty(), ServerAddresses.Mode.Archival, Behaviour.HONEST).configure(good.get(0));
        Subject user = relayed.admit(key());
        SimNode contact = good.get(5);
        boolean usable = contact.routing.pull(new SimPeer(contact.id, relayed.id, net), user).isPresent();
        int hours = -1;
        for (int hour = 1; hour <= 72 && hours < 0; hour++) {
            net.step(HOUR);
            if (good.stream().allMatch(n -> n.holds(relayed.subject) && n.holds(user)))
                hours = hour;
        }
        long sybils = good.stream().mapToLong(n -> n.replica.store().states(Subject.SERVER, new byte[0], 10_000).size()).max().getAsLong() - good.size() - 2;
        log("1000 new relayed servers from one node, then an honest relayed server: it and its user reached every node in "
                + hours + " hours (-1: not in 72); up to " + sybils + " sybils in a node; the user "
                + (usable ? "was" : "was not") + " usable at once by a node it contacted");
        Assert.assertTrue("a contact's records are used at once, whatever is queued", usable);
        Assert.assertTrue("the shared bucket's rate, not the flood's size", hours > 0 && hours <= 6);
    }

    /** Minutes until a new relayed server and its user reach every honest node, behind a thousand new servers
     *  listing the same relay, which vouches only for the honest one, if at all.
     */
    private static int relayedBehindAFlood(boolean vouched, long[] sybilsHeld, boolean[] placed) {
        Net net = new Net(18, 50);
        List<SimNode> good = honest(net, 8, 0);
        SimNode relay = good.get(1);
        relay.publishAddresses(List.of(), true);
        settle(net, good);
        SimNode attacker = net.node(Optional.of(900), ServerAddresses.Mode.Archival, Behaviour.WITHHOLD);
        known(attacker, good);
        List<Subject> sybils = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            SigningKeyPair k = key();
            ServerInception si = ServerInception.build(k, key().publicSigningKey, crypto.hasher).join().withProof(GOOD_PROOF);
            Subject sybil = Subject.server(si.peerId().bareMultihash());
            attacker.replica.add(sybil, si);
            attacker.replica.add(sybil, ServerAddresses.build(k, k.publicSigningKey, Optional.empty(), List.of(),
                    List.of(relay.id), ServerAddresses.Mode.Archival, false, 1).join());
            sybils.add(sybil);
        }
        for (SimNode n : good)
            n.routing.addSyncPeer(new SimPeer(n.id, attacker.id, net));
        net.step(10 * MINUTE);
        SimNode relayed = net.node(Optional.empty(), ServerAddresses.Mode.Archival, Behaviour.HONEST)
                .configure(good.get(0))
                .relayedBy(relay, vouched);
        Subject user = relayed.admit(key());
        net.tick(MINUTE);
        int minutes = good.stream().allMatch(n -> n.holds(relayed.subject) && n.holds(user)) ? 1 : -1;
        for (int step = 1; step <= 36 && minutes < 0; step++) {
            net.step(10 * MINUTE);
            if (good.stream().allMatch(n -> n.holds(relayed.subject) && n.holds(user)))
                minutes = 1 + step * 10;
        }
        sybilsHeld[0] = good.stream().mapToLong(n -> held(n, sybils)).max().getAsLong();
        SimNode far = good.get(5);
        placed[0] = far.buckets.bucket(relayed.id).equals(far.buckets.bucket(relay.id))
                && ! far.buckets.bucket(relay.id).equals(AddressBucket.UNVERIFIED);
        placed[1] = sybils.stream().allMatch(sy -> far.buckets.bucket(sy.id).equals(AddressBucket.UNVERIFIED));
        return minutes;
    }

    @Test
    public void aRelayVouchingForItsServerPassesItAheadOfAFloodListingTheSameRelay() {
        long[] sybilsVouched = new long[1], sybilsNot = new long[1];
        boolean[] placedVouched = new boolean[2], placedNot = new boolean[2];
        int vouched = relayedBehindAFlood(true, sybilsVouched, placedVouched);
        int not = relayedBehindAFlood(false, sybilsNot, placedNot);
        log("1000 new servers listing an honest relay, then an honest server behind it: with the relay vouching for it, it"
                + " and its user reached every node in " + vouched + " minutes, in the relay's bucket: " + placedVouched[0]
                + "; without, " + not + " minutes (-1: not in 6 hours); sybils all in the shared bucket: " + placedVouched[1]
                + ", up to " + sybilsVouched[0] + " held by a node");
        Assert.assertTrue("placed in the relay's bucket", placedVouched[0]);
        Assert.assertTrue("listing a relay that doesn't vouch for them gets them nothing", placedVouched[1] && placedNot[1]);
        Assert.assertFalse(placedNot[0]);
        Assert.assertTrue("a vouched server isn't held up by the shared bucket", vouched > 0 && vouched <= 11);
        Assert.assertTrue(not < 0 || not > vouched);
    }

    @Test
    public void halfTheFullNodesOfflineSlowsNothing() {
        Net net = new Net(16, 10_000);
        List<SimNode> all = fleet(net, 16, 16, Behaviour.OFFLINE);
        net.dropAnnouncements = true;
        List<SimNode> good = all.subList(0, 16), bad = all.subList(16, 32);
        double before = drawnShare(good, bad);
        List<Subject> users = new ArrayList<>();
        int worst = 0;
        for (int round = 1; round <= 12; round++) {
            List<Subject> fresh = new ArrayList<>();
            for (int i = 0; i < 4; i++)
                fresh.add(good.get(net.random.nextInt(good.size())).admit(key()));
            users.addAll(fresh);
            int r = converge(net, good, fresh, 6);
            net.choosePeers();
            worst = Math.max(worst, r < 0 ? 99 : r);
        }
        log("Half the full nodes offline, announcements lost: new users reached every honest node within " + worst
                + " rounds; offline nodes were " + pct(before) + " of drawn peers, then " + pct(drawnShare(good, bad))
                + "; " + good.stream().filter(n -> n.routing.isSynced()).count() + " of " + good.size() + " synced");
        Assert.assertTrue(worst <= 3);
        Assert.assertTrue("peers that stop answering are dropped", drawnShare(good, bad) < before / 2);
    }

    @Test
    public void aPartitionHealsAndConflictingMovesSettleTheSameEverywhere() {
        Net net = new Net(17, 10_000);
        List<SimNode> all = honest(net, 16, 0);
        settle(net, all);
        SigningKeyPair owner = key();
        Subject both = all.get(3).admit(owner);
        net.step(10 * MINUTE);
        Assert.assertTrue(all.stream().allMatch(n -> n.holds(both)));
        for (int i = 0; i < all.size(); i++)
            net.side.put(all.get(i).id, i < 8 ? 0 : 1);
        List<Subject> users = new ArrayList<>();
        all.get(3).replica.add(both, IdentityMove.build(owner, List.of(all.get(3).id), 1).join());
        all.get(12).replica.add(both, IdentityMove.build(owner, List.of(all.get(12).id), 1).join());
        for (int round = 0; round < 36; round++) {
            users.add(all.get(net.random.nextInt(8)).admit(key()));
            users.add(all.get(8 + net.random.nextInt(8)).admit(key()));
            net.step(10 * MINUTE);
            if (round % 3 == 0)
                net.choosePeers();
        }
        long split = all.stream().filter(n -> held(n, users) < users.size()).count();
        net.side.clear();
        int rounds = converge(net, all, users, 30);
        Set<String> states = all.stream().map(n -> peergos.shared.util.ArrayOps.bytesToHex(n.replica.stateHash(both))).collect(Collectors.toSet());
        for (int round = 0; round < 6; round++)
            net.step(10 * MINUTE);
        long wronged = suspected(all, all);
        log("Split in two for 6 hours (" + split + " of " + all.size() + " nodes missing the other side's " + users.size() / 2
                + " users), then healed: converged in " + rounds + " rounds; a move signed differently on each side settled to "
                + states.size() + " state everywhere; honest nodes suspected after the heal: " + wronged);
        Assert.assertTrue(rounds > 0 && rounds <= 6);
        Assert.assertEquals(1, states.size());
        Assert.assertEquals(0, wronged);
    }
}
