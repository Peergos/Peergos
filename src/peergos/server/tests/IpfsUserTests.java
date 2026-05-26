package peergos.server.tests;

import org.eclipse.jetty.server.Server;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.tests.util.TestPorts;
import peergos.server.util.*;
import peergos.server.webdav.WebdavMount;
import peergos.server.webdav.WebdavServer;
import peergos.shared.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;

@RunWith(Parameterized.class)
public class IpfsUserTests extends UserTests {

    private static Args args = buildArgs()
            .with("useIPFS", "true")
            .with("async-bootstrap", "true")
            .with("enable-gc", "true")
//            .with("gc.period.millis", "10000")
            .with("collect-metrics", "true")
            .with("metrics.address", "localhost")
            .removeArg(IpfsWrapper.IPFS_BOOTSTRAP_NODES); // no bootstrapping

    public IpfsUserTests(NetworkAccess network, UserService service) {
        super(network, service);
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() throws Exception {
        UserService service = Main.PKI_INIT.main(args).localApi;
        return Arrays.asList(new Object[][] {
                {
                        Builder.buildJavaNetworkAccess(new URL("http://localhost:" + args.getInt("port")), false, Optional.empty(), Optional.empty()).join(),
                        service
                }
        });
    }

    @AfterClass
    public static void cleanup() {
        try {Thread.sleep(2000);}catch (InterruptedException e) {}
        Path peergosDir = args.fromPeergosDir("", "");
        System.out.println("Deleting " + peergosDir);
        deleteFiles(peergosDir.toFile());
    }

    @Override
    public Args getArgs() {
        return args;
    }

    public long getBlockstoreSize() {
        Path ipfsDir = args.fromPeergosDir("", ".ipfs").resolve("blocks");
        try {
            return Files.walk(ipfsDir)
                    .filter(p -> p.toFile().isFile() && p.getFileName().toString().endsWith(".data"))
                    .mapToLong(p -> p.toFile().length())
                    .sum();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    @Test
    public void gcReclaimsSpace() {
        String username = generateUsername();
        String password = "password";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        // need to clear transactions otherwise blocks won't be GC'd for a day
        TransactionStore transactionStore = Builder.buildTransactionStore(args, Builder.getDBConnector(args.with("transactions-sql-file", "transactions.sql"),
                "transactions-sql-file"));
        transactionStore.clearOldTransactions(context.signer.publicKeyHash, System.currentTimeMillis());
        gc();
        long sizeBefore = getBlockstoreSize();
        long usageBefore = context.getSpaceUsage(false).join();
        int filesize = 10 * 1024 * 1024;
        String filename = "file.bin";
        context.getUserRoot().join().uploadOrReplaceFile(filename, AsyncReader.build(new byte[filesize]),
                filesize, network, crypto, () -> false, x -> {}).join();
        long sizeWithFile = getBlockstoreSize();
        Assert.assertTrue(sizeWithFile > sizeBefore + filesize);
        Threads.sleep(2_000); // Allow time for server to update usage
        long usageWithFile = context.getSpaceUsage(false).join();
        Assert.assertTrue(usageWithFile > usageBefore + filesize);

        Path filePath = PathUtil.get(username, filename);
        context.getByPath(filePath).join().get()
                .remove(context.getUserRoot().join(), filePath, context).join();
        transactionStore.clearOldTransactions(context.signer.publicKeyHash, System.currentTimeMillis());
        List<Cid> open = transactionStore.getOpenTransactionBlocks(context.signer.publicKeyHash);
        Assert.assertTrue(open.isEmpty());
        long usageAfter = context.getSpaceUsage(false).join();
        while (usageAfter > usageBefore) { // Allow time for server to process delete event
            Threads.sleep(1_000);
            usageAfter = context.getSpaceUsage(false).join();
        }

        Assert.assertTrue(usageAfter == usageBefore);
        gc();
        long sizeAfterDelete = getBlockstoreSize();
        long diff = sizeAfterDelete - sizeBefore;
        Assert.assertTrue(diff < 20*1024); // Why not equal?
    }

    @Test
    public void webdavUploadDownloadAndListing() throws Exception {
        String username = generateUsername();
        String password = "testpassword";
        PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        int webdavPort = TestPorts.getPort();
        String webdavUser = "webdavtestuser";
        String webdavPass = "webdavtestpass";
        String peergosUrl = "http://localhost:" + getArgs().getInt("port");

        Server webdavServer = WebdavServer.startNonBlocking(webdavPort, webdavUser, webdavPass,
                username, password, peergosUrl, "basic");
        try {
            String auth = "Basic " + Base64.getEncoder().encodeToString((webdavUser + ":" + webdavPass).getBytes());
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://localhost:" + webdavPort;

            // Directory listing via PROPFIND on root
            HttpResponse<String> propfind = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/" + username + "/"))
                            .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
                            .header("Authorization", auth)
                            .header("Depth", "1")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            Assert.assertEquals("PROPFIND root should return 207 Multi-Status", 207, propfind.statusCode());

            // Upload a file via PUT
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

            // Download the file via GET and verify content
            HttpResponse<byte[]> get = client.send(
                    HttpRequest.newBuilder(URI.create(base + filePath))
                            .GET()
                            .header("Authorization", auth)
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            Assert.assertEquals("GET should return 200", 200, get.statusCode());
            Assert.assertArrayEquals("Downloaded content must match uploaded content", fileContent, get.body());

            // Directory listing should now include the uploaded file
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

            // Create a subdirectory via MKCOL
            String dirPath = "/" + username + "/test-subdir";
            HttpResponse<String> mkcol = client.send(
                    HttpRequest.newBuilder(URI.create(base + dirPath))
                            .method("MKCOL", HttpRequest.BodyPublishers.noBody())
                            .header("Authorization", auth)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            Assert.assertEquals("MKCOL should return 201: " + mkcol.statusCode(), 201, mkcol.statusCode());

            // PROPFIND on root should now list the new subdirectory
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
        String peergosUrl = "http://localhost:" + getArgs().getInt("port");

        Server webdavServer = WebdavServer.startNonBlocking(webdavPort, webdavUser, webdavPass,
                username, password, peergosUrl, "basic");
        try (WebdavMount mount = WebdavMount.mount(webdavPort, webdavUser, webdavPass)) {
            Path home = Path.of(mount.getMountPoint()).resolve(username);

            // Write a file via Files API
            byte[] content = "Hello from mounted WebDAV!".getBytes();
            Path file = home.resolve("mount-test.txt");
            Files.write(file, content);

            // Read it back and verify
            byte[] read = Files.readAllBytes(file);
            Assert.assertArrayEquals("File content must round-trip through mount", content, read);

            // Create a subdirectory
            Path subdir = home.resolve("mount-subdir");
            Files.createDirectory(subdir);
            Assert.assertTrue("Subdirectory should exist after mkdir", subdir.toFile().isDirectory());

            // Write a file inside the subdirectory
            Path subfile = subdir.resolve("nested.txt");
            byte[] subContent = "Nested file content".getBytes();
            Files.write(subfile, subContent);
            Assert.assertArrayEquals("Nested file content must round-trip", subContent, Files.readAllBytes(subfile));
        } finally {
            webdavServer.stop();
        }
    }
}
