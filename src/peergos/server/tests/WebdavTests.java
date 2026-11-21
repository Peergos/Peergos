package peergos.server.tests;

import org.eclipse.jetty.server.Server;
import org.junit.*;
import org.peergos.util.Pair;
import peergos.server.*;
import peergos.server.tests.util.TestPorts;
import peergos.server.util.Args;
import peergos.server.webdav.MountConfig;
import peergos.server.webdav.WebdavFileSystem;
import peergos.server.webdav.WebdavMount;
import peergos.server.webdav.WebdavServer;
import peergos.shared.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

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

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        random.nextBytes(data);
        return data;
    }

    /** Holds its data back until released, and says when it is first asked for it */
    private static class HeldReader implements AsyncReader {
        private final AsyncReader data;
        private final CompletableFuture<Boolean> asked, release;

        HeldReader(byte[] data, CompletableFuture<Boolean> asked, CompletableFuture<Boolean> release) {
            this.data = AsyncReader.build(data);
            this.asked = asked;
            this.release = release;
        }

        @Override
        public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
            asked.complete(true);
            return release.thenCompose(x -> data.readIntoArray(res, offset, length));
        }

        @Override
        public CompletableFuture<AsyncReader> seek(long offset) {
            return data.seek(offset).thenApply(x -> this);
        }

        @Override
        public CompletableFuture<AsyncReader> reset() {
            return data.reset().thenApply(x -> this);
        }

        @Override
        public void close() {
            data.close();
        }
    }

    /** A mount sends range writes in parallel, and each waits for the one before. One that looked the file up before
     *  an earlier write grew it must write into the file as it is now, not cut it back to the size it saw. */
    @Test
    public void aRangeWriteQueuedBehindAnAppendKeepsTheAppend() throws Exception {
        String username = generateUsername();
        String password = "testpassword";
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        int chunk = FileProperties.chunkSizeForNewFiles();
        byte[] original = randomBytes(2 * chunk + chunk / 2);
        user.getUserRoot().join().uploadOrReplaceFile("a", AsyncReader.build(original), original.length,
                user.network, crypto, () -> false, x -> {}).join();
        WebdavFileSystem fs = new WebdavFileSystem(username, password, "http://localhost:" + args.getInt("port"),
                MountConfig.disabled());
        String uri = "/" + username + "/a";
        ExecutorService requests = Executors.newFixedThreadPool(2);
        try {
            byte[] tail = randomBytes(1000);
            CompletableFuture<Boolean> appending = new CompletableFuture<>();
            CompletableFuture<Boolean> release = new CompletableFuture<>();
            CompletableFuture<Long> append = CompletableFuture.supplyAsync(() -> fs.setResourceContent(null, uri,
                    new Pair<>(new HeldReader(tail, appending, release), (long) tail.length),
                    original.length, original.length + tail.length, null, null), requests);
            appending.get(60, TimeUnit.SECONDS);
            // past the end the file had when this looked it up, but short of the end the append leaves
            byte[] section = randomBytes(510);
            CompletableFuture<Long> write = CompletableFuture.supplyAsync(() -> fs.setResourceContent(null, uri,
                    new Pair<>(AsyncReader.build(section), (long) section.length),
                    original.length - 10, original.length + 500, null, null), requests);
            // time for it to look the file up, which doesn't wait for the append, and queue behind it
            Thread.sleep(3_000);
            release.complete(true);
            append.get(60, TimeUnit.SECONDS);
            write.get(60, TimeUnit.SECONDS);

            byte[] expected = ArrayOps.concat(original, tail);
            System.arraycopy(section, 0, expected, original.length - 10, section.length);
            UserContext fresh = PeergosNetworkUtils.ensureSignedUp(username, password, network.clear(), crypto);
            FileWrapper file = fresh.getByPath(uri).join().get();
            Assert.assertArrayEquals(expected, Serialize.readFully(file.getInputStream(fresh.network, crypto, x -> {}).join(),
                    file.getSize()).join());
            Optional<HashBranch> stored = file.getFileProperties().treeHash;
            HashBranch exact = HashTree.build(AsyncReader.build(expected), 0, expected.length, chunk, crypto.hasher).join().branch(0);
            Assert.assertTrue("stored " + stored, stored.isEmpty() || stored.get().equals(exact));
        } finally {
            requests.shutdownNow();
        }
    }
}
