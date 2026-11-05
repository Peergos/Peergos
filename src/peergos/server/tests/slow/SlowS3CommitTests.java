package peergos.server.tests.slow;

import org.junit.*;
import peergos.server.*;
import peergos.server.crypto.hash.*;
import peergos.server.storage.*;
import peergos.server.tests.*;
import peergos.server.tests.util.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.login.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Users making large changes at once on a server whose S3 answers every read slowly, as S3 can (a second or two).
 *  The server keeps no local block cache, so its reads reach S3 as they do when the cache is cold: with one, what is
 *  written here is read back from the cache and S3 is hardly read. Prints commit and operation times, the S3 reads,
 *  and how long the usage queue takes to catch up: run it on two builds to compare them.
 *  -Dslows3.users (default 6), -Dslows3.latency in ms (default 1000) */
public class SlowS3CommitTests {
    private static final int USERS = Integer.getInteger("slows3.users", 6);
    private static final long LATENCY = Long.getLong("slows3.latency", 1000);
    private static final int SMALL_FILES = 20, SHARED_DIRS = 2, FILES_PER_SHARED_DIR = 3;
    private static final int BIG_FILE = 12 * 1024 * 1024;

    private static final Crypto crypto = Main.initCrypto();
    private static final String S3_BUCKET = "local-s3";
    private static final String S3_ACCESS_KEY = "test";
    private static final String S3_SECRET_KEY = "testdslocal";
    private static LocalS3Server localS3;
    private static int s3Port, peergosPort;
    private static Args pkiArgs = UserTests.buildArgs()
            .with("useIPFS", "true")
            .with("async-bootstrap", "true")
            .removeArg(IpfsWrapper.IPFS_BOOTSTRAP_NODES);
    private static final List<Args> argsToCleanUp = new ArrayList<>();
    private static final List<ServerProcesses> services = new ArrayList<>();

    private static Args withS3(Args in) {
        S3Config cfg = LocalS3Server.getConfig(S3_BUCKET, S3_ACCESS_KEY, S3_SECRET_KEY, s3Port);
        return in.with("s3.bucket", cfg.bucket)
                .with("s3.region", cfg.region)
                .with("s3.region.endpoint", cfg.regionEndpoint)
                .with("direct-s3-writes", "true")
                .with("authed-s3-reads", "true")
                .with("s3.accessKey", cfg.accessKey)
                .with("s3.secretKey", cfg.secretKey)
                .with("allow-external-login", "true");
    }

    /** A pki node, an ipfs node and a Peergos node on S3 with no ipfs of its own, as S3UserTests starts them */
    @BeforeClass
    public static void init() throws Exception {
        s3Port = TestPorts.getPort();
        localS3 = new LocalS3Server(Files.createTempDirectory("peergos-slow-s3"), S3_BUCKET, S3_ACCESS_KEY, S3_SECRET_KEY, s3Port);
        localS3.start();

        ServerProcesses pki = Main.PKI_INIT.main(pkiArgs);
        PublicKeyHash peergosId = pki.localApi.coreNode.getPublicKeyHash("peergos").join().get();
        pkiArgs = pkiArgs.setArg("peergos.identity.hash", peergosId.toString());
        services.add(pki);
        NetworkAccess toPki = Builder.buildNonCachingJavaNetworkAccess(new URL("http://localhost:" + pkiArgs.getInt("port")),
                false, 1_000, Optional.empty(), Optional.empty(), Optional.empty()).get();
        Cid pkiNodeId = toPki.dhtClient.id().get();
        UserContext peergos = UserContext.signIn("peergos", "testpassword", m -> Futures.errored(new IllegalStateException("No MFA")),
                toPki, crypto).join();
        BatWithId mirrorBat = peergos.getMirrorBat().join().get();

        int ipfsApiPort = TestPorts.getPort();
        int ipfsGatewayPort = TestPorts.getPort();
        int proxyTargetPort = TestPorts.getPort();
        int allowPort = TestPorts.getPort();
        Args ipfsArgs = withS3(UserTests.buildArgs())
                .with("useIPFS", "true")
                .with("async-bootstrap", "true")
                .with("ipfs-gateway-address", "/ip4/127.0.0.1/tcp/" + ipfsGatewayPort)
                .with("allow-target", "/ip4/127.0.0.1/tcp/" + allowPort)
                .with("ipfs-swarm-port", "" + TestPorts.getTcpAndUdpPort())
                .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, "" + Main.getLocalBootstrapAddress(pkiArgs.getInt("ipfs-swarm-port"), pkiNodeId))
                .with("proxy-target", Main.getLocalMultiAddress(proxyTargetPort).toString())
                .with("ipfs-api-address", Main.getLocalMultiAddress(ipfsApiPort).toString());
        Main.IPFS.main(ipfsArgs);
        argsToCleanUp.add(ipfsArgs);

        peergosPort = TestPorts.getPort();
        Cid ourId = new ContentAddressedStorage.HTTP(new JavaPoster(new URL("http://localhost:" + ipfsApiPort), false), false, crypto.hasher).id().get();
        Args peergosArgs = ipfsArgs
                .with("port", "" + peergosPort)
                .with("useIPFS", "false")
                .with("enable-gc", "false")
                .with("mirror.username", "peergos")
                .with("mirror.bat", mirrorBat.encode())
                .with("ipfs-api-address", "/ip4/127.0.0.1/tcp/" + ipfsApiPort)
                .with("ipfs-gateway-address", "/ip4/127.0.0.1/tcp/" + ipfsGatewayPort)
                .with("allow-target", "/ip4/127.0.0.1/tcp/" + allowPort)
                .with("proxy-target", Main.getLocalMultiAddress(proxyTargetPort).toString())
                .with("ipfs.id", ourId.toString())
                .with("pki-node-id", pkiNodeId.toString())
                .with("peergos.identity.hash", peergosId.toString());
        services.add(Main.PEERGOS.main(peergosArgs));
        argsToCleanUp.add(peergosArgs);
        withoutBlockCache(services.get(1).localApi);
    }

    /** Swaps the local cbor cache of each S3 block store the server reaches for one that holds nothing */
    private static void withoutBlockCache(Object server) throws Exception {
        BlockCache none = new BlockCache() {
            public CompletableFuture<Boolean> put(Cid hash, byte[] data) {
                return Futures.of(true);
            }

            public CompletableFuture<Optional<byte[]>> get(Cid hash) {
                return Futures.of(hash.isIdentity() ? Optional.of(hash.getHash()) : Optional.empty());
            }

            public boolean hasBlock(Cid hash) {
                return false;
            }

            public CompletableFuture<Boolean> clear() {
                return Futures.of(true);
            }

            public long getMaxSize() {
                return 0;
            }

            public void setMaxSize(long maxSizeBytes) {}
        };
        java.lang.reflect.Field cache = S3BlockStorage.class.getDeclaredField("cborCache");
        cache.setAccessible(true);
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        Deque<Object> toVisit = new ArrayDeque<>(List.of(server));
        int swapped = 0;
        while (! toVisit.isEmpty()) {
            Object o = toVisit.poll();
            if (seen.put(o, true) != null)
                continue;
            if (o instanceof S3BlockStorage) {
                cache.set(o, none);
                swapped++;
            }
            for (Class<?> c = o.getClass(); c != null && c.getName().startsWith("peergos."); c = c.getSuperclass())
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive())
                        continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v != null && v.getClass().getName().startsWith("peergos."))
                            toVisit.add(v);
                    } catch (RuntimeException | IllegalAccessException inaccessible) {}
                }
        }
        if (swapped == 0)
            throw new IllegalStateException("No S3 block store found in the server");
    }

    @AfterClass
    public static void cleanup() {
        if (localS3 != null)
            localS3.stop();
        argsToCleanUp.add(pkiArgs);
        for (Args toClean : argsToCleanUp)
            UserTests.deleteFiles(toClean.fromPeergosDir("", "").toFile());
    }

    /** Times every commit sent to the server, which is where a slow S3 read on the request would show */
    private static class TimedStorage extends DelegatingStorage {
        private final ContentAddressedStorage target;
        final List<Long> commitMillis = Collections.synchronizedList(new ArrayList<>());

        TimedStorage(ContentAddressedStorage target) {
            super(target);
            this.target = target;
        }

        @Override
        public ContentAddressedStorage directToOrigin() {
            return this;
        }

        @Override
        public CompletableFuture<List<Cid>> bulkCommit(PublicKeyHash owner, BulkCommit commit) {
            long start = System.nanoTime();
            return target.bulkCommit(owner, commit)
                    .whenComplete((r, e) -> commitMillis.add((System.nanoTime() - start) / 1_000_000));
        }
    }

    private record Run(UserContext user, TimedStorage storage, Map<String, Long> opMillis) {}

    private static Run signUp(int i) throws Exception {
        JavaPoster poster = new JavaPoster(new URL("http://localhost:" + peergosPort), false);
        ScryptJava hasher = new ScryptJava();
        TimedStorage timed = new TimedStorage(NetworkAccess.buildLocalDht(poster, true, hasher));
        NetworkAccess network = NetworkAccess.buildViaPeergosInstance(poster, poster, timed, 7_000, hasher, false,
                DeviceTokenStore.inMemory()).join();
        String username = "slow" + i + "x" + Math.abs(new Random().nextInt() % 100_000);
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, "password", network, crypto);
        return new Run(user, timed, Collections.synchronizedMap(new LinkedHashMap<>()));
    }

    private static void timed(Run run, String op, Runnable task) {
        long start = System.nanoTime();
        task.run();
        run.opMillis.put(op, (System.nanoTime() - start) / 1_000_000);
    }

    private static void upload(UserContext user, Path dir, String name, int size) {
        byte[] data = new byte[size];
        new Random().nextBytes(data);
        user.getByPath(dir).join().get()
                .uploadOrReplaceFile(name, AsyncReader.build(data), data.length, user.network, crypto, () -> false, x -> {})
                .join();
    }

    /** Many small files, a big file, and new writers with files in them */
    private static void largeChanges(Run run) {
        UserContext user = run.user;
        Path home = PathUtil.get(user.username);
        timed(run, "mkdir", () -> user.getUserRoot().join().mkdir("big", user.network, false, user.mirrorBatId(), crypto).join());
        Path big = home.resolve("big");
        for (int i = 0; i < SMALL_FILES; i++) {
            int n = i;
            timed(run, "small" + n, () -> upload(user, big, "f" + n, 16 * 1024));
        }
        timed(run, "bigfile", () -> upload(user, big, "large", BIG_FILE));
        for (int d = 0; d < SHARED_DIRS; d++) {
            String name = "shared" + d;
            timed(run, "newwriter" + d, () -> {
                user.getUserRoot().join().mkdir(name, user.network, false, user.mirrorBatId(), crypto).join();
                user.shareWriteAccessWith(home.resolve(name), Collections.emptySet()).join();
            });
            for (int f = 0; f < FILES_PER_SHARED_DIR; f++) {
                int n = f;
                timed(run, name + "/f" + n, () -> upload(user, home.resolve(name), "f" + n, 64 * 1024));
            }
        }
    }

    private static String percentiles(List<Long> millis) {
        List<Long> sorted = new ArrayList<>(millis);
        Collections.sort(sorted);
        if (sorted.isEmpty())
            return "n=0";
        return "n=" + sorted.size() + " p50=" + sorted.get(sorted.size() / 2) + " p90=" + sorted.get(sorted.size() * 9 / 10)
                + " max=" + sorted.get(sorted.size() - 1) + " ms";
    }

    /** Pointer updates waiting for the server's usage queue */
    private static int queued() {
        try {
            Object usage = services.get(1).localApi.usage;
            java.lang.reflect.Field local = ProxyingSpaceUsage.class.getDeclaredField("local");
            local.setAccessible(true);
            Object filter = local.get(usage);
            java.lang.reflect.Field queue = filter.getClass().getDeclaredField("mutableQueue");
            queue.setAccessible(true);
            return ((Collection<?>) queue.get(filter)).size();
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't read the server's usage queue", e);
        }
    }

    @Test
    public void largeChangesFromManyUsersAtOnce() throws Exception {
        List<Run> runs = new ArrayList<>();
        for (int i = 0; i < USERS; i++)
            runs.add(signUp(i));
        for (Run run : runs)
            run.storage.commitMillis.clear();

        localS3.setReadLatency(LATENCY);
        long readsBefore = localS3.reads();
        long start = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(USERS);
        List<Future<?>> done = new ArrayList<>();
        for (Run run : runs)
            done.add(pool.submit(() -> largeChanges(run)));
        for (Future<?> f : done)
            f.get(2, TimeUnit.HOURS);
        long wall = (System.nanoTime() - start) / 1_000_000;
        long reads = localS3.reads() - readsBefore;
        long drainStart = System.nanoTime();
        while (queued() > 0 && System.nanoTime() - drainStart < 30 * 60_000_000_000L)
            Thread.sleep(100);
        long drain = (System.nanoTime() - drainStart) / 1_000_000;
        pool.shutdown();
        localS3.setReadLatency(0);

        List<Long> commits = new ArrayList<>(), ops = new ArrayList<>(), newWriters = new ArrayList<>(), bigFiles = new ArrayList<>();
        for (Run run : runs) {
            commits.addAll(run.storage.commitMillis);
            ops.addAll(run.opMillis.values());
            run.opMillis.forEach((op, ms) -> {
                if (op.startsWith("newwriter"))
                    newWriters.add(ms);
                if (op.equals("bigfile"))
                    bigFiles.add(ms);
            });
            System.out.println("SLOW_S3 user " + run.user.username + " commits " + percentiles(run.storage.commitMillis)
                    + " ops " + percentiles(new ArrayList<>(run.opMillis.values())));
        }
        System.out.println("SLOW_S3 users=" + USERS + " latency=" + LATENCY + "ms wall=" + wall + "ms s3reads=" + reads
                + " queueDrainAfter=" + drain + "ms");
        System.out.println("SLOW_S3 commits " + percentiles(commits));
        System.out.println("SLOW_S3 ops " + percentiles(ops));
        System.out.println("SLOW_S3 newwriter ops " + percentiles(newWriters));
        System.out.println("SLOW_S3 bigfile ops " + percentiles(bigFiles));
        Assert.assertFalse("no commits were timed", commits.isEmpty());
    }

}
