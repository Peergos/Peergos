package peergos.server.tests;

import com.sun.net.httpserver.*;
import org.junit.*;
import peergos.server.*;
import peergos.server.routing.*;
import peergos.server.sql.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Two nodes over HTTP, through a stand-in for nabu's proxy that sets the Source header to the caller. */
public class HttpRoutingTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final byte[] GOOD_PROOF = {1, 2, 3};
    private static final Work TEST_WORK = (subject, proof) -> CompletableFuture.completedFuture(Arrays.equals(proof, GOOD_PROOF));
    private static final ExecutorService work = Executors.newFixedThreadPool(4);
    private static final Map<Multihash, Integer> ports = new ConcurrentHashMap<>();

    private static SigningKeyPair key() {
        return SigningKeyPair.random(crypto.random, crypto.signer);
    }

    /** Routes /p2p/<target>/http/<path> to the target's port, as the caller. */
    private static class Proxy implements HttpPoster {
        final Multihash caller;

        Proxy(Multihash caller) {
            this.caller = caller;
        }

        @Override
        public CompletableFuture<byte[]> postUnzip(String url, byte[] payload, int timeoutMillis) {
            String[] parts = url.split("/", 5);
            Multihash target = Cid.decodePeerId(parts[2]).bareMultihash();
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + ports.get(target) + "/" + parts[4]).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Source", caller.toString());
                conn.getOutputStream().write(payload);
                if (conn.getResponseCode() != 200)
                    throw new IOException("HTTP " + conn.getResponseCode());
                return CompletableFuture.completedFuture(Serialize.readFully(conn.getInputStream(), 10 * 1024 * 1024));
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        public CompletableFuture<byte[]> post(String url, byte[] payload, boolean unzip, int timeoutMillis) {
            return postUnzip(url, payload, timeoutMillis);
        }

        public CompletableFuture<byte[]> postMultipart(String url, List<byte[]> files, int timeoutMillis) {
            throw new IllegalStateException();
        }

        public CompletableFuture<byte[]> put(String url, byte[] payload, Map<String, String> headers) {
            throw new IllegalStateException();
        }

        public CompletableFuture<byte[]> get(String url, Map<String, String> headers) {
            throw new IllegalStateException();
        }
    }

    private static class Node {
        final Multihash id;
        final Proxy proxy;
        final RoutingReplica replica;
        final RoutingNode routing;
        final HttpServer server;
        final Set<Multihash> vouchFor = ConcurrentHashMap.newKeySet();

        Node() throws IOException {
            this(ServerKeys.peerId(key().publicSigningKey));
        }

        Node(Multihash id) throws IOException {
            this.id = id.bareMultihash();
            this.proxy = new Proxy(this.id);
            JdbcRoutingStore store = new JdbcRoutingStore(Builder.buildEphemeralSqlite(), new SqliteCommands());
            replica = new RoutingReplica(store, new RecordValidator(crypto.hasher, TEST_WORK, TEST_WORK), TEST_WORK, TEST_WORK);
            routing = new RoutingNode(replica, crypto.hasher, System::currentTimeMillis, 100,
                    s -> AddressBucket.UNVERIFIED, peer -> new HttpRoutingPeer(peer, proxy), work);
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 10);
            server.createContext("/" + Constants.ROUTING_URL, new RoutingHandler(routing, vouchFor::contains));
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            ports.put(this.id, server.getAddress().getPort());
        }

        HttpRoutingPeer peer(Node target) {
            return new HttpRoutingPeer(target.id, proxy);
        }
    }

    @Test
    public void syncAndAnnounceOverHttp() throws Exception {
        Node a = new Node(), b = new Node(), c = new Node();
        SigningKeyPair serverKey = key();
        ServerInception x = ServerInception.build(serverKey, key().publicSigningKey, crypto.hasher).join().withProof(GOOD_PROOF);
        Subject xs = Subject.server(x.peerId());
        a.replica.add(xs, x);
        c.replica.add(xs, x);
        List<Subject> identities = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            SigningKeyPair id = key();
            IdentityInception inc = IdentityInception.build(id, List.of(x.peerId()), key().publicSigningKey, crypto.hasher).join()
                    .withAdmission(Admission.build(id.publicSigningKey, GOOD_PROOF, serverKey.secretSigningKey).join());
            Subject s = Subject.identity(inc.identityHash());
            a.replica.add(s, inc);
            c.replica.add(s, inc);
            identities.add(s);
        }
        b.routing.addSyncPeer(b.peer(a));
        b.routing.addSyncPeer(b.peer(c));
        Assert.assertEquals(41, b.routing.syncRound());
        for (Subject s : identities)
            Assert.assertArrayEquals(a.replica.stateHash(s), b.replica.stateHash(s));

        a.routing.addSyncPeer(a.peer(b));
        SigningKeyPair id = key();
        IdentityInception late = IdentityInception.build(id, List.of(x.peerId()), key().publicSigningKey, crypto.hasher).join()
                .withAdmission(Admission.build(id.publicSigningKey, GOOD_PROOF, serverKey.secretSigningKey).join());
        a.replica.add(Subject.identity(late.identityHash()), late);
        long end = System.currentTimeMillis() + 10_000;
        while (b.routing.waitingInceptions() == 0 && System.currentTimeMillis() < end)
            Thread.sleep(20);
        b.routing.tick();
        Assert.assertTrue(b.replica.log(Subject.identity(late.identityHash())).inception().isPresent());

        Assert.assertEquals(Outcome.Duplicate, a.peer(b).offer(xs, List.of(x)).join());
        Assert.assertEquals(1, a.peer(b).records(Subject.identity(late.identityHash())).join().size());
    }

    @Test
    public void aCallerOverItsLimitIsRefusedBeforeItsBodyIsRead() throws Exception {
        Node a = new Node();
        Multihash stranger = ServerKeys.peerId(key().publicSigningKey);
        byte[] notCbor = new byte[64 * 1024];
        Arrays.fill(notCbor, (byte) 0x9f);
        int refused = 0;
        for (int i = 0; i < RoutingHandler.UNPROVEN_OFFERS.burst + 5; i++) {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + ports.get(a.id) + "/" + Constants.ROUTING_URL + "announce").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Source", stranger.toString());
            conn.getOutputStream().write(notCbor);
            if (conn.getResponseCode() == 429)
                refused++;
            conn.disconnect();
        }
        Assert.assertTrue("requests past the limit are refused, though their bodies don't decode", refused >= 5);
    }

    @Test
    public void unknownCallersShareOnePoolOneIdCantEmptyAndANewServerCanAlwaysIntroduceItself() throws Exception {
        Node a = new Node();
        RoutingHandler handler = new RoutingHandler(a.routing);
        Multihash flooder = ServerKeys.peerId(key().publicSigningKey);
        int took = 0;
        while (handler.allow("summaries", flooder))
            took++;
        Assert.assertEquals(RoutingHandler.UNPROVEN_SCANS.burst / RoutingNode.UNPROVEN_SHARE, took);
        Assert.assertTrue("another id still gets in", handler.allow("summaries", ServerKeys.peerId(key().publicSigningKey)));

        // many free ids do empty it
        while (handler.allow("summaries", ServerKeys.peerId(key().publicSigningKey)))
            ;
        // but a new server's own records come in on its own allowance, over HTTP, proof of work checked
        SigningKeyPair serverKey = key();
        ServerInception x = ServerInception.build(serverKey, key().publicSigningKey, crypto.hasher).join().withProof(GOOD_PROOF);
        Node n = new Node(x.peerId());
        n.replica.add(Subject.server(x.peerId()), x);
        Proxy newServer = n.proxy;
        Outcome first = new HttpRoutingPeer(a.id, newServer).introduce(List.of(x)).get(10, TimeUnit.SECONDS);
        Assert.assertTrue("taken, or waiting its bucket's turn: " + first, first == Outcome.Added || first == Outcome.Limited);
        long end = System.currentTimeMillis() + 10_000;
        while (! a.replica.log(Subject.server(x.peerId())).exists() && System.currentTimeMillis() < end) {
            a.routing.tick();
            Thread.sleep(20);
        }
        Assert.assertTrue(a.replica.log(Subject.server(x.peerId())).exists());
        Assert.assertEquals(Outcome.Duplicate, new HttpRoutingPeer(a.id, newServer).introduce(List.of(x)).get(10, TimeUnit.SECONDS));

        ServerInception unproven = ServerInception.build(key(), key().publicSigningKey, crypto.hasher).join();
        Assert.assertNotEquals("its proof of work is still checked", Outcome.Added,
                new HttpRoutingPeer(a.id, new Proxy(unproven.peerId())).introduce(List.of(unproven))
                        .handle((o, t) -> t == null ? o : Outcome.Rejected).get(10, TimeUnit.SECONDS));
        Assert.assertFalse(a.replica.log(Subject.server(unproven.peerId())).exists());
    }

    @Test
    public void aServerThatNeverAnswersHoldsOnlyAFewCallsAtOnce() throws Exception {
        Node a = new Node(), b = new Node();
        Multihash silent = ServerKeys.peerId(key().publicSigningKey);
        List<CompletableFuture<byte[]>> hanging = new ArrayList<>();
        Proxy proxy = new Proxy(a.id) {
            @Override
            public CompletableFuture<byte[]> postUnzip(String url, byte[] payload, int timeoutMillis) {
                if (! url.contains(new Cid(1, Cid.Codec.LibP2pKey, silent.type, silent.getHash()).toString()))
                    return super.postUnzip(url, payload, timeoutMillis);
                CompletableFuture<byte[]> never = new CompletableFuture<>();
                hanging.add(never);
                return never;
            }
        };
        HttpRoutingPeer toSilent = new HttpRoutingPeer(silent, proxy), toB = new HttpRoutingPeer(b.id, proxy);
        List<CompletableFuture<List<SignedRecord>>> calls = new ArrayList<>();
        for (int i = 0; i < HttpRoutingPeer.MAX_IN_FLIGHT + 5; i++)
            calls.add(toSilent.records(Subject.server(silent)));
        Assert.assertEquals(HttpRoutingPeer.MAX_IN_FLIGHT, hanging.size());
        Assert.assertTrue("the rest fail at once", calls.get(calls.size() - 1).isCompletedExceptionally());
        String toOther = toB.records(Subject.server(silent)).handle((r, t) -> t == null ? "answered" : t.getMessage()).get(5, TimeUnit.SECONDS);
        Assert.assertFalse("another server is still called: " + toOther, toOther.contains("in flight"));

        hanging.get(0).completeExceptionally(new SocketTimeoutException());
        Assert.assertEquals(HttpRoutingPeer.MAX_IN_FLIGHT - 1, HttpRoutingPeer.inFlight(silent));
        toSilent.records(Subject.server(silent));
        Assert.assertEquals(HttpRoutingPeer.MAX_IN_FLIGHT + 1, hanging.size());
        hanging.forEach(f -> f.completeExceptionally(new SocketTimeoutException()));
        Assert.assertEquals(0, HttpRoutingPeer.inFlight(silent));
    }

    @Test
    public void aRelayAnswersWhetherItVouchesForAServer() throws Exception {
        Node relay = new Node(), asker = new Node();
        Multihash own = ServerKeys.peerId(key().publicSigningKey), stranger = ServerKeys.peerId(key().publicSigningKey);
        relay.vouchFor.add(own);
        Assert.assertTrue(asker.peer(relay).vouches(own).join());
        Assert.assertFalse(asker.peer(relay).vouches(stranger).join());
    }

    @Test
    public void unauthenticatedCallsAreRefused() throws Exception {
        Node a = new Node();
        HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + ports.get(a.id) + "/" + Constants.ROUTING_URL + "records").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.getOutputStream().write(Subject.server(a.id).serialize());
        Assert.assertEquals(403, conn.getResponseCode());
    }
}
