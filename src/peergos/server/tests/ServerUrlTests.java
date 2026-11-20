package peergos.server.tests;

import com.sun.net.httpserver.HttpServer;
import org.junit.Assert;
import org.junit.Test;
import peergos.server.Main;
import peergos.server.UserService;
import peergos.server.net.ConfigHandler;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.util.Constants;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** The desktop app may talk plain http to a server on this machine, the local network or a private
 *  VPN, but nothing further away.
 */
public class ServerUrlTests {

    @Test
    public void plainHttpOnlyOnThisMachineOrTheLan() {
        for (String ok : new String[]{
                "https://peergos.net",
                "http://localhost:8000",
                "http://127.0.0.1:8000",
                "http://192.168.1.10:8000",
                "http://10.0.0.5:8000",
                "http://172.16.4.2:8000",
                // NetBird and Tailscale peers: the shared address space, and IPv6 unique local
                "http://100.64.0.10:8000",
                "http://100.127.255.254:8000",
                "http://[fd7a:115c:a1e0::1]:8000",
                "http://[0:0:0:0:0:0:0:1]:8000",
                // a VPN's own names: Tailscale MagicDNS and NetBird
                "http://machine.tailnet.ts.net:8000",
                "http://peer.netbird.cloud:8000"})
            Assert.assertEquals(ok, ConfigHandler.validateServerUrl(ok));

        for (String refused : new String[]{
                "http://8.8.8.8:8000",
                "http://172.32.0.1:8000",
                "http://100.63.255.254:8000",
                "http://100.128.0.1:8000",
                "http://[2001:db8::1]:8000",
                "http://peergos.net",
                // a name is never looked up, as it could resolve to a private address now and a public one later
                "http://nas.local:8000",
                "http://myserver:8000",
                "http://attacker-ts.net:8000",
                "http://peer.netbird.cloud.attacker.com:8000",
                // forms some parsers read differently: octal, a single number, a zone
                "http://010.0.0.1:8000",
                "http://2130706433:8000",
                "http://167772161:8000",
                "http://10.1:8000",
                "http://[fe80::1%25eth0]:8000",
                // link local is not the local network's addressing
                "http://169.254.1.1:8000"}) {
            try {
                ConfigHandler.validateServerUrl(refused);
                Assert.fail("Plain http to " + refused + " should be refused");
            } catch (IllegalStateException expected) {}
        }
    }

    /** The Android app checks the host of every request it sends, as it comes, so the rule can't lean on a url parser. */
    @Test
    public void plainHttpRuleOnRawHosts() {
        for (String ok : new String[]{"localhost", "LOCALHOST", "127.0.0.2", "[::1]", "[FD00::1]", "172.16.0.1",
                "172.31.255.255", "192.168.0.1", "100.64.0.1", "a.ts.net", "Machine.Tailnet.TS.NET", "peer-1.netbird.cloud"})
            Assert.assertTrue(ok, Main.allowsPlainHttp(ok));
        for (String refused : new String[]{null, "", "172.15.255.255", "172.32.0.1", "192.169.0.1", "100.63.255.255",
                "100.128.0.1", "[fbff::1]", "[fe00::1]", "[::]", "0.0.0.0", "[:::]", "[1::2::3]", "[fd00::1:2:3:4:5:6:7]",
                "[fd00:1:2:3:4:5:6:7:8]", "[fd00::12345]", "[::ffff:192.168.1.10]", "fd00::1", "127.1", "1.2.3.4.5",
                "ts.net", ".ts.net", "a..ts.net", "a_b.ts.net", "evil%2ecom.ts.net", "a.ts.net.", "localhost.",
                "a.".repeat(2000) + "ts.net"})
            Assert.assertFalse(String.valueOf(refused), Main.allowsPlainHttp(refused));
    }

    /** The web ui sends the url through encodeURIComponent, and must read back what was typed. */
    @Test
    public void savedServerUrlReadsBackAsTyped() throws Exception {
        Path dir = Files.createTempDirectory("server-url");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/" + Constants.CONFIG,
                new ConfigHandler(null, Optional.of(new UserService.LocalAppProperties(dir, "https://peergos.net"))));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/" + Constants.CONFIG;
            for (String url : new String[]{
                    "https://peergos.example/a+b",
                    "https://peergos.example/100%25",
                    "https://peergos.example/a&b=c"}) {
                post(base + "server-url/set?url=" + encodeURIComponent(url));
                Assert.assertEquals(url, post(base + "server-url/get").get("configured"));
            }
        } finally {
            server.stop(0);
            Files.deleteIfExists(dir.resolve("config"));
            Files.deleteIfExists(dir);
        }
    }

    private static Map<String, Object> post(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        Assert.assertEquals(url, 200, conn.getResponseCode());
        try (InputStream in = conn.getInputStream()) {
            return (Map<String, Object>) JSONParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String encodeURIComponent(String s) {
        StringBuilder res = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c < 128 && (Character.isLetterOrDigit(c) || "-_.!~*'()".indexOf(c) >= 0))
                res.append((char) c);
            else
                res.append(String.format("%%%02X", c));
        }
        return res.toString();
    }
}
