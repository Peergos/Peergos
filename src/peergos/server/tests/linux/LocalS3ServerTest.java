package peergos.server.tests.linux;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.tests.util.*;
import peergos.server.util.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.bases.Charsets;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public class LocalS3ServerTest {
    private static final Hasher hasher = JavaCrypto.init().hasher;
    private static final String BUCKET = "testbucket";
    private static final String ACCESS_KEY = "testaccesskey";
    private static final String SECRET_KEY = "testsecretkey";
    private static final int PORT = TestPorts.getPort();

    private LocalS3Server server;
    private S3Config config;
    private String host;

    @Before
    public void start() throws Exception {
        Path dir = Files.createTempDirectory("local-s3-test");
        server = new LocalS3Server(dir, BUCKET, ACCESS_KEY, SECRET_KEY, PORT);
        server.start();
        config = LocalS3Server.getConfig(BUCKET, ACCESS_KEY, SECRET_KEY, PORT);
        host = config.getHost(); // "testbucket.localhost:PORT"
    }

    @After
    public void stop() {
        server.stop();
    }

    @Test
    public void putAndGet() throws Exception {
        String key = BUCKET + "/blocks/hello";
        byte[] data = "hello world".getBytes();
        String sha = ArrayOps.bytesToHex(Hash.sha256(data));

        PresignedUrl put = S3Request.preSignPut(key, data.length, sha, Optional.empty(), false,
                S3AdminRequests.asAwsDate(ZonedDateTime.now()), host,
                new HashMap<>(), config.region, config.accessKey, config.secretKey, false, hasher).join();
        HttpUtil.putWithVersion(put, data);

        PresignedUrl get = S3Request.preSignGet(key, Optional.of(600), Optional.empty(),
                S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, config.region,
                Optional.empty(), config.accessKey, config.secretKey, false, hasher).join();
        byte[] result = HttpUtil.get(get);
        Assert.assertArrayEquals(data, result);
    }

    @Test
    public void head() throws Exception {
        String key = BUCKET + "/blocks/headtest";
        byte[] data = "headdata".getBytes();
        String sha = ArrayOps.bytesToHex(Hash.sha256(data));

        PresignedUrl put = S3Request.preSignPut(key, data.length, sha, Optional.empty(), false,
                S3AdminRequests.asAwsDate(ZonedDateTime.now()), host,
                new HashMap<>(), config.region, config.accessKey, config.secretKey, false, hasher).join();
        HttpUtil.putWithVersion(put, data);

        PresignedUrl head = S3Request.preSignHead(key, Optional.of(600),
                S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, config.region,
                Optional.empty(), config.accessKey, config.secretKey, false, hasher).join();
        Map<String, List<String>> headers = HttpUtil.head(head);
        String contentLength = headers.entrySet().stream()
                .filter(e -> "content-length".equalsIgnoreCase(e.getKey()))
                .map(e -> e.getValue().get(0))
                .findFirst().orElse(null);
        Assert.assertEquals(String.valueOf(data.length), contentLength);
    }

    @Test
    public void listVersions() throws Exception {
        String prefix = BUCKET + "/blocks/alice/";
        String key1 = prefix + "AAA";
        String key2 = prefix + "BBB";
        byte[] data = "block".getBytes();
        String sha = ArrayOps.bytesToHex(Hash.sha256(data));

        for (String key : List.of(key1, key2)) {
            PresignedUrl put = S3Request.preSignPut(key, data.length, sha, Optional.empty(), false,
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), host,
                    new HashMap<>(), config.region, config.accessKey, config.secretKey, false, hasher).join();
            HttpUtil.putWithVersion(put, data);
        }

        S3AdminRequests.ListObjectVersionsReply reply = S3AdminRequests.listObjectVersions(
                prefix, 1000, Optional.empty(), Optional.empty(),
                ZonedDateTime.now(), host, config.region, Optional.empty(),
                config.accessKey, config.secretKey,
                url -> { try { return HttpUtil.get(url); } catch (java.io.IOException e) { throw new RuntimeException(e); } },
                S3AdminRequests.builder::get, false, hasher);

        Assert.assertEquals(2, reply.versions.size());
        Assert.assertTrue(reply.versions.stream().anyMatch(v -> v.key.equals(key1)));
        Assert.assertTrue(reply.versions.stream().anyMatch(v -> v.key.equals(key2)));
    }

    /** A client cancelled mid-upload closes the connection with only part of the body
     *  sent. Storing what arrived would leave a block whose content no longer matches
     *  the content-addressed key it lives under, which only shows up much later as a
     *  corrupt or missing block, so pin down that a truncated PUT is rejected and
     *  persists nothing. */
    @Test
    public void truncatedPutIsRejected() throws Exception {
        String key = BUCKET + "/blocks/truncated";
        byte[] data = new byte[8192];
        new Random(42).nextBytes(data);
        String sha = ArrayOps.bytesToHex(Hash.sha256(data));

        PresignedUrl put = S3Request.preSignPut(key, data.length, sha, Optional.empty(), false,
                S3AdminRequests.asAwsDate(ZonedDateTime.now()), host,
                new HashMap<>(), config.region, config.accessKey, config.secretKey, false, hasher).join();

        int status = putHalfTheBody(put, data);
        Assert.assertEquals("truncated upload rejected", 400, status);

        // and nothing was persisted under the key
        PresignedUrl get = S3Request.preSignGet(key, Optional.of(600), Optional.empty(),
                S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, config.region,
                Optional.empty(), config.accessKey, config.secretKey, false, hasher).join();
        try {
            byte[] stored = HttpUtil.get(get);
            Assert.fail("Expected 404, got " + stored.length + " bytes");
        } catch (java.io.IOException e) {
            // expected
        }
    }

    /** Sends a PUT declaring the full Content-Length but writing only half the body,
     *  then closes, as an aborted upload does. Returns the response status code. */
    private static int putHalfTheBody(PresignedUrl target, byte[] data) throws Exception {
        URI uri = new URI(target.base);
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        try (Socket socket = new Socket(uri.getHost(), port)) {
            socket.setSoTimeout(10_000);
            StringBuilder req = new StringBuilder();
            req.append("PUT ").append(uri.getRawPath());
            if (uri.getRawQuery() != null)
                req.append('?').append(uri.getRawQuery());
            req.append(" HTTP/1.1\r\n");
            boolean hasHost = target.fields.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("host"));
            if (! hasHost)
                req.append("Host: ").append(uri.getHost()).append(':').append(port).append("\r\n");
            for (Map.Entry<String, String> e : target.fields.entrySet())
                req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            req.append("Content-Length: ").append(data.length).append("\r\n");
            req.append("Connection: close\r\n\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(Charsets.UTF_8));
            out.write(data, 0, data.length / 2); // only half of what we promised
            out.flush();
            socket.shutdownOutput();

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), Charsets.UTF_8));
            String statusLine = in.readLine();
            if (statusLine == null)
                return -1;
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    @Test
    public void delete() throws Exception {
        String key = BUCKET + "/blocks/todelete";
        byte[] data = "bye".getBytes();
        String sha = ArrayOps.bytesToHex(Hash.sha256(data));

        PresignedUrl put = S3Request.preSignPut(key, data.length, sha, Optional.empty(), false,
                S3AdminRequests.asAwsDate(ZonedDateTime.now()), host,
                new HashMap<>(), config.region, config.accessKey, config.secretKey, false, hasher).join();
        HttpUtil.putWithVersion(put, data);

        PresignedUrl del = S3AdminRequests.preSignDelete(key, Optional.empty(),
                S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, config.region,
                Optional.empty(), config.accessKey, config.secretKey, false, hasher).join();
        HttpUtil.delete(del);

        PresignedUrl get = S3Request.preSignGet(key, Optional.of(600), Optional.empty(),
                S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, config.region,
                Optional.empty(), config.accessKey, config.secretKey, false, hasher).join();
        try {
            HttpUtil.get(get);
            Assert.fail("Expected 404");
        } catch (java.io.IOException e) {
            // expected
        }
    }
}
