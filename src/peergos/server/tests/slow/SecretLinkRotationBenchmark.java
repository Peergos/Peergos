package peergos.server.tests.slow;

import com.sun.net.httpserver.*;
import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.tests.*;
import peergos.server.tests.util.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

/** Time to revoke a secret link to a large subtree, through a proxy that adds a round trip latency to every request.
 *  -Dfiles=10000 -Dfolders=10 -Dlatency=100
 */
public class SecretLinkRotationBenchmark {
    private static final int FILES = Integer.getInteger("files", 10_000);
    private static final int FOLDERS = Integer.getInteger("folders", 10);
    private static final int LATENCY = Integer.getInteger("latency", 100);
    /** Like the web ui's block cache, which keeps every block read or written */
    private static final boolean BLOCK_CACHE = Boolean.getBoolean("blockCache");

    private static final Crypto crypto = Main.initCrypto();
    private static final Random random = new Random(666);
    private static final Args args = UserTests.buildArgs().with("useIPFS", "false");
    private static int proxyPort;
    private static final AtomicLong requests = new AtomicLong();
    private static final Map<String, AtomicLong> byEndpoint = new ConcurrentHashMap<>();

    @BeforeClass
    public static void init() throws Exception {
        Main.PKI_INIT.main(args);
        proxyPort = TestPorts.getPort();
        startDelayingProxy(proxyPort, args.getInt("port"), LATENCY);
    }

    private static NetworkAccess withBlockCache(NetworkAccess net) {
        if (! BLOCK_CACHE)
            return net;
        return net.withStorage(s -> new UnauthedCachingStorage(s, new RamBlockCache(1024 * 1024, 200_000), crypto.hasher));
    }

    private static NetworkAccess network(int port) throws Exception {
        return Builder.buildJavaNetworkAccess(new URL("http://localhost:" + port), false, Optional.empty(), Optional.empty()).get();
    }

    @Test
    public void revokeLinksToALargeSubtree() throws Exception {
        String username = "bench" + Math.abs(random.nextInt() % 10000);
        String password = "test01";
        NetworkAccess direct = network(args.getInt("port"));
        UserContext owner = PeergosNetworkUtils.ensureSignedUp(username, password, direct, crypto);
        String dirName = "tree";
        Path dir = PathUtil.get(username, dirName);

        long uploadStart = System.currentTimeMillis();
        byte[] data = new byte[1024];
        random.nextBytes(data);
        int perFolder = FILES / FOLDERS;
        List<FileWrapper.FolderUploadProperties> folders = IntStream.range(0, FOLDERS)
                .mapToObj(f -> new FileWrapper.FolderUploadProperties(Arrays.asList(dirName, "folder" + f),
                        IntStream.range(0, perFolder)
                                .mapToObj(i -> new FileWrapper.FileUploadProperties("file" + i, () -> AsyncReader.build(data), 0,
                                        data.length, Optional.empty(), Optional.empty(), false, false, x -> {}))
                                .collect(Collectors.toList())))
                .collect(Collectors.toList());
        FileWrapper root = owner.getUserRoot().join();
        root.uploadSubtree(folders.stream(), root.mirrorBatId(), owner.network, crypto, owner.getTransactionService(),
                f -> Futures.of(false), f -> Futures.of(true), () -> true).join();
        System.err.printf("UPLOAD(%d files in %d folders) took %d s, no latency%n", FILES, FOLDERS,
                (System.currentTimeMillis() - uploadStart) / 1000);

        LinkProperties writable = owner.createSecretLink(dir.toString(), true, Optional.empty(), Optional.empty(), "", false).join();
        time("WRITABLE", username, password, ctx -> ctx.deleteSecretLink(writable.label, dir, true).join());

        // the proxied session rotated keys, so the original one is stale
        owner = UserContext.signIn(username, password, UserTests::noMfa, network(args.getInt("port")), crypto).join();
        LinkProperties readOnly = owner.createSecretLink(dir.toString(), false, Optional.empty(), Optional.empty(), "", false).join();
        time("READ_ONLY", username, password, ctx -> ctx.deleteSecretLink(readOnly.label, dir, false).join());

        UserContext check = UserContext.signIn(username, password, UserTests::noMfa, network(args.getInt("port")), crypto).join();
        Assert.assertEquals(FOLDERS, check.getByPath(dir).join().get().getChildren(crypto.hasher, check.network).join().size());
    }

    private void time(String name, String username, String password, java.util.function.Consumer<UserContext> revoke) throws Exception {
        // a fresh login through the proxy, so nothing below the link is cached
        UserContext ctx = UserContext.signIn(username, password, UserTests::noMfa,
                withBlockCache(network(proxyPort)), crypto).join();
        long before = requests.get();
        byEndpoint.clear();
        long start = System.currentTimeMillis();
        revoke.accept(ctx);
        long duration = System.currentTimeMillis() - start;
        System.err.printf("REVOKE %s link (%d files, %d folders, %d ms latency%s): %d ms, %d requests%n",
                name, FILES, FOLDERS, LATENCY, BLOCK_CACHE ? ", block cache" : "", duration, requests.get() - before);
        byEndpoint.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .forEach(e -> System.err.println("    " + e.getValue().get() + " " + e.getKey()));
    }

    private static void startDelayingProxy(int port, int targetPort, int latencyMillis) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 1000);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            try {
                requests.incrementAndGet();
                String path = ex.getRequestURI().getPath();
                byEndpoint.computeIfAbsent(path, k -> new AtomicLong()).incrementAndGet();
                byte[] body = ex.getRequestBody().readAllBytes();
                Thread.sleep(latencyMillis / 2);
                HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + targetPort + ex.getRequestURI()).openConnection();
                conn.setRequestMethod(ex.getRequestMethod());
                for (Map.Entry<String, List<String>> h : ex.getRequestHeaders().entrySet()) {
                    String k = h.getKey();
                    if (k.equalsIgnoreCase("Host") || k.equalsIgnoreCase("Content-Length") || k.equalsIgnoreCase("Connection"))
                        continue;
                    for (String v : h.getValue())
                        conn.addRequestProperty(k, v);
                }
                if (body.length > 0) {
                    conn.setDoOutput(true);
                    conn.getOutputStream().write(body);
                }
                int code = conn.getResponseCode();
                InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                byte[] resp = in == null ? new byte[0] : in.readAllBytes();
                Thread.sleep(latencyMillis / 2);
                for (Map.Entry<String, List<String>> h : conn.getHeaderFields().entrySet()) {
                    String k = h.getKey();
                    if (k == null || k.equalsIgnoreCase("Content-Length") || k.equalsIgnoreCase("Transfer-Encoding") || k.equalsIgnoreCase("Connection"))
                        continue;
                    ex.getResponseHeaders().put(k, h.getValue());
                }
                ex.sendResponseHeaders(code, resp.length == 0 ? -1 : resp.length);
                if (resp.length > 0)
                    ex.getResponseBody().write(resp);
            } catch (Exception e) {
                e.printStackTrace();
                ex.sendResponseHeaders(502, -1);
            } finally {
                ex.close();
            }
        });
        server.start();
    }
}
