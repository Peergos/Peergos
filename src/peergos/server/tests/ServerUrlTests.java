package peergos.server.tests;

import com.sun.net.httpserver.HttpServer;
import org.junit.Assert;
import org.junit.Test;
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

/** The desktop app may talk plain http to a server on this machine or the local network, but
 *  nothing further away.
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
                "http://172.16.4.2:8000"})
            Assert.assertEquals(ok, ConfigHandler.validateServerUrl(ok));

        for (String refused : new String[]{
                "http://8.8.8.8:8000",
                "http://172.32.0.1:8000",
                "http://peergos.net"}) {
            try {
                ConfigHandler.validateServerUrl(refused);
                Assert.fail("Plain http to " + refused + " should be refused");
            } catch (IllegalStateException expected) {}
        }
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
