package peergos.server.tests;

import org.eclipse.jetty.server.Server;
import org.junit.*;
import peergos.server.*;
import peergos.server.tests.util.TestPorts;
import peergos.server.util.Args;
import peergos.server.webdav.WebdavMount;
import peergos.server.webdav.WebdavServer;
import peergos.shared.*;
import peergos.shared.user.*;

import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;

public class WebdavTests {

    private static NetworkAccess network;
    private static Crypto crypto;
    private static Args args;
    private static Random random = new Random(666);

    @BeforeClass
    public static void init() throws Exception {
        crypto = Main.initCrypto();
        args = UserTests.buildArgs().with("useIPFS", "false");
        Main.PKI_INIT.main(args);
        network = Builder.buildLocalJavaNetworkAccess(args.getInt("port")).get();
    }

    @AfterClass
    public static void cleanup() {
        Path peergosDir = args.fromPeergosDir("", "");
        UserTests.deleteFiles(peergosDir.toFile());
    }

    private String generateUsername() {
        return "webdav-test" + Math.abs(random.nextInt() % 1_000_000);
    }

    @Test
    public void webdavUploadDownloadAndListing() throws Exception {
        String username = generateUsername();
        String password = "testpassword";
        PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        int webdavPort = TestPorts.getPort();
        String webdavUser = "webdavtestuser";
        String webdavPass = "webdavtestpass";
        String peergosUrl = "http://localhost:" + args.getInt("port");

        Server webdavServer = WebdavServer.startNonBlocking(webdavPort, webdavUser, webdavPass,
                username, password, peergosUrl, "basic");
        try {
            String auth = "Basic " + Base64.getEncoder().encodeToString((webdavUser + ":" + webdavPass).getBytes());
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://localhost:" + webdavPort;

            HttpResponse<String> propfind = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/" + username + "/"))
                            .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
                            .header("Authorization", auth)
                            .header("Depth", "1")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            Assert.assertEquals("PROPFIND root should return 207 Multi-Status", 207, propfind.statusCode());

            byte[] fileContent = "Hello Peergos WebDAV!".getBytes();
            String filePath = "/" + username + "/test-webdav.txt";
            HttpResponse<String> put = client.send(
                    HttpRequest.newBuilder(URI.create(base + filePath))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(fileContent))
                            .header("Authorization", auth)
                            .header("Content-Type", "text/plain")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            Assert.assertTrue("PUT should return 201 or 204: " + put.statusCode(), put.statusCode() == 201 || put.statusCode() == 204);

            HttpResponse<byte[]> get = client.send(
                    HttpRequest.newBuilder(URI.create(base + filePath))
                            .GET()
                            .header("Authorization", auth)
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            Assert.assertEquals("GET should return 200", 200, get.statusCode());
            Assert.assertArrayEquals("Downloaded content must match uploaded content", fileContent, get.body());

            HttpResponse<String> propfind2 = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/" + username + "/"))
                            .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
                            .header("Authorization", auth)
                            .header("Depth", "1")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            Assert.assertEquals(207, propfind2.statusCode());
            Assert.assertTrue("Directory listing should contain uploaded filename",
                    propfind2.body().contains("test-webdav.txt"));

            String dirPath = "/" + username + "/test-subdir";
            HttpResponse<String> mkcol = client.send(
                    HttpRequest.newBuilder(URI.create(base + dirPath))
                            .method("MKCOL", HttpRequest.BodyPublishers.noBody())
                            .header("Authorization", auth)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            Assert.assertEquals("MKCOL should return 201: " + mkcol.statusCode(), 201, mkcol.statusCode());

            HttpResponse<String> propfind3 = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/" + username + "/"))
                            .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
                            .header("Authorization", auth)
                            .header("Depth", "1")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            Assert.assertEquals(207, propfind3.statusCode());
            Assert.assertTrue("Directory listing should contain new subdirectory",
                    propfind3.body().contains("test-subdir"));
        } finally {
            webdavServer.stop();
        }
    }

    @Test
    public void webdavMountReadWrite() throws Exception {
        String os = System.getProperty("os.name").toLowerCase();
        if (!os.contains("linux") && !os.contains("windows"))
            return;

        String username = generateUsername();
        String password = "testpassword";
        PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        int webdavPort = TestPorts.getPort();
        String webdavUser = "webdavmountuser";
        String webdavPass = "webdavmountpass";
        String peergosUrl = "http://localhost:" + args.getInt("port");

        Server webdavServer = WebdavServer.startNonBlocking(webdavPort, webdavUser, webdavPass,
                username, password, peergosUrl, "basic");
        try (WebdavMount mount = WebdavMount.mount(webdavPort, webdavUser, webdavPass)) {
            Path home = Path.of(mount.getMountPoint()).resolve(username);

            byte[] content = "Hello from mounted WebDAV!".getBytes();
            Path file = home.resolve("mount-test.txt");
            Files.write(file, content);
            long deadline = System.currentTimeMillis() + 10_000;
            while (!file.toFile().exists() && System.currentTimeMillis() < deadline)
                Thread.sleep(200);
            byte[] read = Files.readAllBytes(file);
            Assert.assertArrayEquals("File content must round-trip through mount", content, read);

            Path subdir = home.resolve("mount-subdir");
            Files.createDirectory(subdir);
            Assert.assertTrue("Subdirectory should exist after mkdir", subdir.toFile().isDirectory());

            Path subfile = subdir.resolve("nested.txt");
            byte[] subContent = "Nested file content".getBytes();
            Files.write(subfile, subContent);
            long deadline2 = System.currentTimeMillis() + 10_000;
            while (!subfile.toFile().exists() && System.currentTimeMillis() < deadline2)
                Thread.sleep(200);
            Assert.assertArrayEquals("Nested file content must round-trip", subContent, Files.readAllBytes(subfile));
        } finally {
            webdavServer.stop();
        }
    }
}
