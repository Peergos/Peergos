package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

public class LocalS3ServerTest {
    private static final Hasher hasher = JavaCrypto.init().hasher;
    private static final String BUCKET = "testbucket";
    private static final String ACCESS_KEY = "testaccesskey";
    private static final String SECRET_KEY = "testsecretkey";
    private static final int PORT = 19878;

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
