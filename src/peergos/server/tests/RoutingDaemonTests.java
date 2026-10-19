package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.routing.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.tests.util.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static peergos.server.tests.UserTests.deleteFiles;

/** A pki node and a server, both running the routing log, over nabu's libp2p http. */
public class RoutingDaemonTests {
    private static final Crypto crypto = Main.initCrypto();

    private static JdbcRoutingStore store(Args a) {
        Args withDefault = a.setIfAbsent(RoutingService.ARG_ROUTING_SQL_FILE.name, RoutingService.ARG_ROUTING_SQL_FILE.defaultValue.get());
        return new JdbcRoutingStore(Builder.getDBConnector(withDefault, RoutingService.ARG_ROUTING_SQL_FILE.name), new SqliteCommands());
    }

    private static void waitFor(String what, Callable<Boolean> condition, long millis) throws Exception {
        long end = System.currentTimeMillis() + millis;
        while (! condition.call()) {
            if (System.currentTimeMillis() > end)
                throw new TimeoutException(what);
            Thread.sleep(500);
        }
    }

    /** The second server has no routing peers, so it learns of an identity on the first only from a hint; then
     *  that identity logs in through the second server by naming the first.
     */
    @Test
    public void aHintPullsAnIdentityAndANamedServerLogsItIn() throws Exception {
        Args pkiArgs = UserTests.buildArgs()
                .with("useIPFS", "true")
                .with("async-bootstrap", "true")
                .with(RoutingService.ARG_ROUTING_LOG.name, "true")
                .with(RoutingService.ARG_ROUTING_WORK_BITS.name, "16")
                .removeArg(IpfsWrapper.IPFS_BOOTSTRAP_NODES);
        ServerProcesses pki = Main.PKI_INIT.main(pkiArgs);
        List<ServerProcesses> services = new ArrayList<>(List.of(pki));
        List<Args> dirs = new ArrayList<>(List.of(pkiArgs));
        try {
            PublicKeyHash peergosId = pki.localApi.coreNode.getPublicKeyHash("peergos").join().get();
            Multihash pkiNodeId = pki.localApi.storage.id().get().bareMultihash();
            int proxyTargetPort = TestPorts.getPort(), ipfsApiPort = TestPorts.getPort(), swarmPort = TestPorts.getTcpAndUdpPort();
            Args nodeArgs = UserTests.buildArgs()
                    .with(Main.PEERGOS_PATH, Files.createTempDirectory("peergos-routing-" + System.nanoTime()).toString())
                    .with("useIPFS", "true")
                    .with("async-bootstrap", "false")
                    .with("port", "" + TestPorts.getPort())
                    .with("pki-node-id", pkiNodeId.toString())
                    .with("peergos.identity.hash", peergosId.toString())
                    .with("ipfs-api-address", Main.getLocalMultiAddress(ipfsApiPort).toString())
                    .with("ipfs-gateway-address", "/ip4/127.0.0.1/tcp/" + TestPorts.getPort())
                    .with("ipfs-swarm-port", "" + swarmPort)
                    .with("proxy-target", Main.getLocalMultiAddress(proxyTargetPort).toString())
                    .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, Main.getLocalBootstrapAddress(pkiArgs.getInt("ipfs-swarm-port"), pkiNodeId).toString())
                    .with(RoutingService.ARG_ROUTING_LOG.name, "true")
                    .with(RoutingService.ARG_ROUTING_WORK_BITS.name, "16");
            dirs.add(nodeArgs);
            ServerProcesses node = Main.PEERGOS.main(nodeArgs);
            services.add(node);

            String suffix = "" + (System.currentTimeMillis() % 100_000);
            UserContext alice = UserContext.signUp("alice" + suffix, "password", "",
                    Builder.buildLocalJavaNetworkAccess(pkiArgs.getInt("port")).join(), crypto).join();
            Assert.assertTrue(alice.commitRecoveryKey("abandon-ability-able-about-above-absent-absorb").join());
            PublicKeyHash aliceId = alice.signer.publicKeyHash;
            JdbcRoutingStore pkiStore = store(pkiArgs), nodeStore = store(nodeArgs);
            waitFor("the inception is admitted", () -> pkiStore.get(Subject.identity(aliceId)).stream()
                    .anyMatch(r -> r instanceof IdentityInception && ((IdentityInception) r).admission.isPresent()), 120_000);

            ServerHint hint = alice.serverHint().join();
            Assert.assertEquals(pkiNodeId, hint.peerId);
            UserContext bob = UserContext.signUp("bob" + suffix, "password", "",
                    Builder.buildLocalJavaNetworkAccess(nodeArgs.getInt("port")).join(), crypto).join();
            Assert.assertTrue("not synced: only the hint brings alice", nodeStore.get(Subject.identity(aliceId)).isEmpty());
            bob.addHint(hint, Optional.of(aliceId)).join();
            waitFor("the hinted identity is pulled", () -> ! nodeStore.get(Subject.identity(aliceId)).isEmpty(), 60_000);

            UserContext aliceViaNode = UserContext.signIn("alice" + suffix + "@" + pkiNodeId, "password", UserTests::noMfa,
                    Builder.buildLocalJavaNetworkAccess(nodeArgs.getInt("port")).join(), crypto).join();
            Assert.assertEquals("alice" + suffix, aliceViaNode.username);
            Assert.assertEquals(aliceId, aliceViaNode.signer.publicKeyHash);

            try {
                new ServerHint(pkiNodeId, List.of("/dns4/peergos.net/tcp/4001"));
                Assert.fail("a hint with a DNS name");
            } catch (IllegalStateException expected) {}
        } finally {
            for (ServerProcesses s : services) {
                try { s.localApi.stop(); } catch (Exception e) {}
                try { s.p2pApi.stop(); } catch (Exception e) {}
                try { if (s.ipfs != null) s.ipfs.stop(); } catch (Exception e) {}
            }
            for (Args a : dirs)
                deleteFiles(a.fromPeergosDir("", "").toFile());
        }
    }

    @Test
    public void serversExchangeTheirRecordsAndImportThePkisState() throws Exception {
        Path legacy = Files.createTempFile("legacy-state", ".cbor");
        Args pkiArgs = UserTests.buildArgs()
                .with("useIPFS", "true")
                .with("async-bootstrap", "true")
                .with(RoutingService.ARG_ROUTING_LOG.name, "true")
                .with(RoutingService.ARG_ROUTING_WORK_BITS.name, "16")
                .removeArg(IpfsWrapper.IPFS_BOOTSTRAP_NODES);
        ServerProcesses pki = Main.PKI_INIT.main(pkiArgs);
        List<ServerProcesses> services = new ArrayList<>(List.of(pki));
        List<Args> dirs = new ArrayList<>(List.of(pkiArgs));
        try {
            PublicKeyHash peergosId = pki.localApi.coreNode.getPublicKeyHash("peergos").join().get();
            Multihash pkiNodeId = pki.localApi.storage.id().get().bareMultihash();
            PublicKeyHash pkiKey = ContentAddressedStorage.hashKey(PublicSigningKey.fromByteArray(
                    Files.readAllBytes(pkiArgs.fromPeergosDir("pki.public.key.path", "test.pki.public.key"))));
            Cid writerData = (Cid) pki.localApi.mutable.getPointerTarget(peergosId, pkiKey, pki.localApi.storage).join().updated.get();
            byte[] state = new LegacyState(writerData, List.of()).serialize();
            Files.write(legacy, state);
            Cid pinned = LegacyImport.hash(state, crypto);

            int proxyTargetPort = TestPorts.getPort(), ipfsApiPort = TestPorts.getPort(), swarmPort = TestPorts.getTcpAndUdpPort();
            Args nodeArgs = UserTests.buildArgs()
                    .with(Main.PEERGOS_PATH, Files.createTempDirectory("peergos-routing-" + System.nanoTime()).toString())
                    .with("useIPFS", "true")
                    .with("async-bootstrap", "false")
                    .with("port", "" + TestPorts.getPort())
                    .with("pki-node-id", pkiNodeId.toString())
                    .with("peergos.identity.hash", peergosId.toString())
                    .with("ipfs-api-address", Main.getLocalMultiAddress(ipfsApiPort).toString())
                    .with("ipfs-gateway-address", "/ip4/127.0.0.1/tcp/" + TestPorts.getPort())
                    .with("ipfs-swarm-port", "" + swarmPort)
                    .with("proxy-target", Main.getLocalMultiAddress(proxyTargetPort).toString())
                    .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, Main.getLocalBootstrapAddress(pkiArgs.getInt("ipfs-swarm-port"), pkiNodeId).toString())
                    .with(RoutingService.ARG_ROUTING_LOG.name, "true")
                    .with(RoutingService.ARG_ROUTING_WORK_BITS.name, "16")
                    .with(RoutingService.ARG_ROUTING_PEERS.name, pkiNodeId.toString())
                    .with(RoutingService.ARG_ROUTING_LEGACY_STATE.name, legacy.toString())
                    .with(RoutingService.ARG_ROUTING_LEGACY_STATE_HASH.name, pinned.toString());
            dirs.add(nodeArgs);
            ServerProcesses node = Main.PEERGOS.main(nodeArgs);
            services.add(node);
            Multihash nodeId = node.localApi.storage.id().get().bareMultihash();

            JdbcRoutingStore nodeStore = store(nodeArgs), pkiStore = store(pkiArgs);
            Assert.assertTrue("the peergos user is imported as legacy", nodeStore.getLegacy(Subject.identity(peergosId)).isPresent());
            waitFor("the server learns the pki's records", () -> ! nodeStore.get(Subject.server(pkiNodeId)).isEmpty(), 60_000);
            waitFor("the pki learns the server's records", () -> ! pkiStore.get(Subject.server(nodeId)).isEmpty(), 180_000);
        } finally {
            for (ServerProcesses s : services) {
                try { s.localApi.stop(); } catch (Exception e) {}
                try { s.p2pApi.stop(); } catch (Exception e) {}
                try { if (s.ipfs != null) s.ipfs.stop(); } catch (Exception e) {}
            }
            for (Args a : dirs)
                deleteFiles(a.fromPeergosDir("", "").toFile());
            Files.deleteIfExists(legacy);
        }
    }
}
