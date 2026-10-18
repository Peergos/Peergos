package peergos.server.tests;

import com.sun.net.httpserver.*;
import io.libp2p.core.*;
import io.libp2p.core.crypto.*;
import io.libp2p.crypto.keys.*;
import org.junit.*;
import peergos.server.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.tests.util.*;
import peergos.server.util.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.MultiAddress;
import peergos.shared.io.ipfs.Multihash;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;

/** A server that rotated its identity, as the pki node does away from RSA, still answers p2p http under the
 *  identity it rotated away from when run with ipfs-previous-identity-swarm-port.
 */
public class PreviousIdentityTests {

    @Test
    public void rotatedServerAnswersUnderBothIdentities() throws Exception {
        PrivKey rsaPrivate = RsaKt.generateRsaKeyPair(2048).getFirst();
        PeerId rsaId = PeerId.fromPubKey(rsaPrivate.publicKey());
        PrivKey nextPrivate = Ed25519Kt.generateEd25519KeyPair().getFirst();
        PeerId nextId = PeerId.fromPubKey(nextPrivate.publicKey());
        int previousPort = TestPorts.getTcpAndUdpPort();

        Args rotatedArgs = UserTests.buildArgs()
                .with("useIPFS", "true")
                .with("async-bootstrap", "true")
                .with("ipfs.identity.priv-key", Base64.getEncoder().encodeToString(nextPrivate.bytes()))
                .with("ipfs.identity.peerid", nextId.toBase58())
                .with(Main.ARG_PREVIOUS_IDENTITY_SWARM_PORT.name, Integer.toString(previousPort))
                .with("serverids-file", "serverids.sql");
        // the server identity db as ServerIdentity rotate leaves it
        JdbcServerIdentityStore ids = JdbcServerIdentityStore.build(Builder.getDBConnector(rotatedArgs, "serverids-file"),
                new SqliteCommands(), Main.initCrypto());
        ids.addIdentity(rsaId, ServerIdentity.generateSignedIpnsRecord(rsaPrivate, Optional.empty(), false, 1));
        ids.setPrivateKey(rsaPrivate);
        ids.setRecord(rsaId, ServerIdentity.generateSignedIpnsRecord(rsaPrivate, Optional.of(Multihash.decode(nextId.getBytes())), true, 2));
        ids.addIdentity(nextId, ServerIdentity.generateSignedIpnsRecord(nextPrivate, Optional.empty(), false, 1));
        ids.setPrivateKey(nextPrivate);

        HttpServer backend = backend(new MultiAddress(rotatedArgs.getArg("proxy-target")).getTCPPort());
        IpfsWrapper rotated = Main.IPFS.main(rotatedArgs);
        IpfsWrapper dialler = null;
        try {
            Multihash rsa = Multihash.decode(rsaId.getBytes());
            Multihash next = Multihash.decode(nextId.getBytes());
            Args diallerArgs = UserTests.buildArgs()
                    .with("useIPFS", "true")
                    .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES,
                            Main.getLocalBootstrapAddress(previousPort, rsa) + "," +
                            Main.getLocalBootstrapAddress(rotatedArgs.getInt("ipfs-swarm-port"), next));
            dialler = Main.IPFS.main(diallerArgs);
            int gatewayPort = new MultiAddress(diallerArgs.getArg("ipfs-gateway-address")).getTCPPort();

            Assert.assertEquals("ok", p2pPost(gatewayPort, rsa));
            Assert.assertEquals("ok", p2pPost(gatewayPort, new Cid(1, Cid.Codec.LibP2pKey, next.type, next.getHash())));
        } finally {
            if (dialler != null)
                dialler.stop();
            rotated.stop();
            backend.stop(0);
        }
    }

    private static HttpServer backend(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 10);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        return server;
    }

    /** First contact can take a few attempts while the dialler connects to its bootstrap peers. */
    private static String p2pPost(int gatewayPort, Multihash target) throws Exception {
        Exception last = null;
        for (int i = 0; i < 10; i++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + gatewayPort + "/p2p/" + target + "/http/test").openConnection();
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(30_000);
                // nabu's gateway only accepts POST
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(0);
                conn.getOutputStream().close();
                if (conn.getResponseCode() == 200)
                    return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                last = new IllegalStateException("status " + conn.getResponseCode());
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(1_000);
        }
        throw last;
    }
}
