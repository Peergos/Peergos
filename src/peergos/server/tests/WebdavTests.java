package peergos.server.tests;

import org.eclipse.jetty.server.Server;
import org.junit.*;
import peergos.server.*;
import peergos.server.tests.util.TestPorts;
import peergos.server.util.Args;
import peergos.server.webdav.MountConfig;
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
                username, password, peergosUrl, "basic", MountConfig.disabled());
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
            // Each entry must carry its own content type; without it clients sniff the type by
            // reading the head of every file in the directory.
            Assert.assertTrue("Listing should report the file's content type: " + propfind2.body(),
                    propfind2.body().contains("text/plain"));

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

            // Rename: MOVE the file to a new name
            String renamedPath = "/" + username + "/test-webdav-renamed.txt";
            HttpResponse<String> move = client.send(
                    HttpRequest.newBuilder(URI.create(base + filePath))
                            .method("MOVE", HttpRequest.BodyPublishers.noBody())
                            .header("Authorization", auth)
                            .header("Destination", base + renamedPath)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            Assert.assertTrue("MOVE should return 201 or 204: " + move.statusCode(),
                    move.statusCode() == 201 || move.statusCode() == 204);

            HttpResponse<byte[]> getRenamedFile = client.send(
                    HttpRequest.newBuilder(URI.create(base + renamedPath))
                            .GET()
                            .header("Authorization", auth)
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            Assert.assertEquals("GET renamed file should return 200", 200, getRenamedFile.statusCode());
            Assert.assertArrayEquals("Renamed file content should match original", fileContent, getRenamedFile.body());

            // Modify: PUT new content to the renamed file
            byte[] modifiedContent = "Updated WebDAV content!".getBytes();
            HttpResponse<String> putModified = client.send(
                    HttpRequest.newBuilder(URI.create(base + renamedPath))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(modifiedContent))
                            .header("Authorization", auth)
                            .header("Content-Type", "text/plain")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            Assert.assertTrue("Modify PUT should return 201 or 204: " + putModified.statusCode(),
                    putModified.statusCode() == 201 || putModified.statusCode() == 204);

            HttpResponse<byte[]> getModified = client.send(
                    HttpRequest.newBuilder(URI.create(base + renamedPath))
                            .GET()
                            .header("Authorization", auth)
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            Assert.assertEquals("GET modified file should return 200", 200, getModified.statusCode());
            Assert.assertArrayEquals("Modified content should match", modifiedContent, getModified.body());
        } finally {
            webdavServer.stop();
        }
    }

    @Test
    public void webdavMountReadWrite() throws Exception {
        String os = System.getProperty("os.name").toLowerCase();
        if (!os.contains("linux"))
            return;

        String username = generateUsername();
        String password = "testpassword";
        PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        int webdavPort = TestPorts.getPort();
        String webdavUser = "webdavmountuser";
        String webdavPass = "webdavmountpass";
        String peergosUrl = "http://localhost:" + args.getInt("port");

        Server webdavServer = WebdavServer.startNonBlocking(webdavPort, webdavUser, webdavPass,
                username, password, peergosUrl, "basic", MountConfig.disabled());
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

            // Rename: move the top-level file to a new name
            Path renamed = home.resolve("mount-test-renamed.txt");
            Files.move(file, renamed);
            long deadline3 = System.currentTimeMillis() + 10_000;
            while (!renamed.toFile().exists() && System.currentTimeMillis() < deadline3)
                Thread.sleep(200);
            Assert.assertTrue("Renamed file should exist", renamed.toFile().exists());
            Assert.assertFalse("Original file should not exist after rename", file.toFile().exists());
            Assert.assertArrayEquals("Renamed file content should match original", content, Files.readAllBytes(renamed));

            // Modify: overwrite the renamed file with new content
            byte[] modifiedContent = "Modified mount content!".getBytes();
            Files.write(renamed, modifiedContent);
            long deadline4 = System.currentTimeMillis() + 10_000;
            while (Files.readAllBytes(renamed).length != modifiedContent.length && System.currentTimeMillis() < deadline4)
                Thread.sleep(200);
            Assert.assertArrayEquals("Modified content must round-trip through mount", modifiedContent, Files.readAllBytes(renamed));
        } finally {
            webdavServer.stop();
        }
    }
}
