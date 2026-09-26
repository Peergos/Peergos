package peergos.server.tests.fuzz;

import org.junit.*;
import peergos.server.*;
import peergos.server.tests.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Random operations on capped write shares, checked against a model of what is stored where.
 *
 *  Outside tests/ proper so that CI doesn't run it. Run with
 *    FUZZ_SEED=123 FUZZ_STEPS=300 ant execute.test -Dtest.source.absolute=$(realpath src/peergos/server/tests/fuzz/WriterQuotaFuzz.java)
 *
 *  The model only knows the bytes of file contents, not the metadata stored beside them, so an upload is only judged
 *  where the answer is clear: one that would take a cap over its limit even counting contents alone must be rejected,
 *  and one that fits comfortably once metadata is allowed for must be accepted.
 */
public class WriterQuotaFuzz {
    private static final int KiB = 1024;
    private static final long TOLERANCE = 1024 * KiB; // SpaceCheckingKeyFilter.USAGE_TOLERANCE
    private static final long FILE_OVERHEAD = 16 * KiB;
    private static final long FOLDER_OVERHEAD = 8 * KiB;
    private static final long BASE_OVERHEAD = 64 * KiB;
    private static final int MAX_FOLDERS = 14;
    private static final int MAX_DEPTH = 4;

    private static final Args args = UserTests.useMemoryDbs(UserTests.buildArgs()).with("enable-gc", "false");
    private static UserService service;
    private static final Crypto crypto = Main.initCrypto();

    private static class Folder {
        final Path path;
        final Set<String> sharedWith = new HashSet<>();
        boolean isWritingSpace = false;
        Optional<Long> cap = Optional.empty();

        Folder(Path path) {
            this.path = path;
        }
    }

    private final Random rnd;
    private final long seed;
    private final int steps;
    private final List<String> log = new ArrayList<>();
    private final List<Folder> folders = new ArrayList<>();
    private final Map<Path, Long> files = new HashMap<>();
    private final Map<String, UserContext> users = new LinkedHashMap<>();
    private UserContext owner;
    private int fileCounter = 0;
    private final Map<String, Integer> outcomes = new TreeMap<>();
    private final Set<String> everRevoked = new HashSet<>();

    public WriterQuotaFuzz() {
        String seedEnv = System.getenv("FUZZ_SEED");
        String stepsEnv = System.getenv("FUZZ_STEPS");
        this.seed = seedEnv != null ? Long.parseLong(seedEnv) : new Random().nextLong();
        this.steps = stepsEnv != null ? Integer.parseInt(stepsEnv) : 200;
        this.rnd = new Random(seed);
    }

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
    }

    private NetworkAccess network() {
        return NetworkAccess.buildBuffered(new CachingStorage(service.storage, 1_000, 50 * 1024),
                service.bats, service.coreNode, service.account, service.mutable, 0, service.social,
                service.controller, service.usage, service.serverMessages, crypto.hasher, Arrays.asList("peergos"), false);
    }

    private UserContext signUp(String prefix) {
        String username = prefix + Math.abs(rnd.nextInt() % 1_000_000);
        return PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
    }

    @Test
    public void fuzz() {
        System.out.println("WriterQuotaFuzz seed=" + seed + " steps=" + steps);
        owner = signUp("fo");
        users.put(owner.username, owner);
        List<UserContext> sharees = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UserContext sharee = signUp("fs");
            sharees.add(sharee);
            users.put(sharee.username, sharee);
        }
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), sharees);
        owner.getUserRoot().join().mkdir("fz", owner.network, false, owner.mirrorBatId(), crypto).join();
        folders.add(new Folder(PathUtil.get(owner.username, "fz")));

        try {
            for (int step = 0; step < steps; step++) {
                int op = rnd.nextInt(100);
                if (op < 8) mkdir();
                else if (op < 18) share();
                else if (op < 22) unshare();
                else if (op < 36) setCap();
                else if (op < 82) upload();
                else if (op < 93) delete();
                else checkReported();
                if (System.getenv("FUZZ_CHECK_TOTAL") != null)
                    checkTotalUsage();
            }
            settle();
            checkReported();
            checkCapsPreserved();
        } catch (Throwable t) {
            int from = Math.max(0, log.size() - 40);
            String recent = String.join("\n", log.subList(from, log.size()));
            throw new AssertionError("WriterQuotaFuzz failed, seed=" + seed + "\nlast ops:\n" + recent, t);
        }
        System.out.println("WriterQuotaFuzz seed=" + seed + " passed. Upload outcomes: " + outcomes);
    }

    private void record(String s) {
        log.add(log.size() + ": " + s);
    }

    private static void settle() {
        Threads.sleep(2_000);
    }

    private <T> T pick(List<T> from) {
        return from.get(rnd.nextInt(from.size()));
    }

    private int depth(Folder f) {
        return f.path.getNameCount() - 1;
    }

    private boolean isUnder(Path p, Folder f) {
        return p.startsWith(f.path) && ! p.equals(f.path);
    }

    private boolean canWrite(String username, Folder f) {
        if (username.equals(owner.username))
            return true;
        return folders.stream().anyMatch(a -> (a == f || isUnder(f.path, a)) && a.sharedWith.contains(username));
    }

    /** The capped folders whose limit applies to a write into this one */
    private List<Folder> capsOver(Folder f) {
        return folders.stream()
                .filter(a -> a.cap.isPresent() && (a == f || isUnder(f.path, a)))
                .collect(Collectors.toList());
    }

    private long contentsUnder(Folder cap) {
        return files.entrySet().stream()
                .filter(e -> isUnder(e.getKey(), cap))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    /** A generous bound on what the server can have counted for this subtree */
    private long upperBoundUnder(Folder cap) {
        long contents = contentsUnder(cap);
        long fileCount = files.keySet().stream().filter(p -> isUnder(p, cap)).count();
        long folderCount = folders.stream().filter(g -> g == cap || isUnder(g.path, cap)).count();
        return contents + contents / 10 + fileCount * FILE_OVERHEAD + folderCount * FOLDER_OVERHEAD + BASE_OVERHEAD;
    }

    private Folder folderOf(Path file) {
        return folders.stream().filter(f -> f.path.equals(file.getParent())).findFirst().get();
    }

    private void mkdir() {
        List<Folder> parents = folders.stream().filter(f -> depth(f) < MAX_DEPTH).collect(Collectors.toList());
        if (folders.size() >= MAX_FOLDERS || parents.isEmpty())
            return;
        Folder parent = pick(parents);
        String name = "d" + folders.size();
        // a folder's metadata counts towards the caps over it, like a file's contents do
        List<Folder> caps = capsOver(parent);
        boolean mustAccept = caps.stream().allMatch(c -> upperBoundUnder(c) + 2 * FOLDER_OVERHEAD <= c.cap.get());
        record("mkdir " + parent.path.resolve(name) + (mustAccept ? " [must accept]" : " [either]"));
        try {
            owner.getByPath(parent.path).join().get().mkdir(name, owner.network, false, owner.mirrorBatId(), crypto).join();
        } catch (Exception e) {
            if (mustAccept || ! isQuotaRejection(e))
                throw new AssertionError("mkdir failed under every cap", e);
            outcomes.merge("mkdir/rejected", 1, Integer::sum);
            return;
        }
        folders.add(new Folder(parent.path.resolve(name)));
    }

    private void share() {
        Folder f = pick(folders);
        List<String> sharees = users.keySet().stream()
                .filter(u -> ! u.equals(owner.username) && ! f.sharedWith.contains(u))
                .collect(Collectors.toList());
        if (sharees.isEmpty())
            return;
        String sharee = pick(sharees);
        record("share " + f.path + " with " + sharee);
        owner.shareWriteAccessWith(f.path, Set.of(sharee)).join();
        f.sharedWith.add(sharee);
        f.isWritingSpace = true;
        if (everRevoked.contains(sharee))
            freshSession(sharee);
    }

    private void unshare() {
        List<Folder> shared = folders.stream().filter(f -> ! f.sharedWith.isEmpty()).collect(Collectors.toList());
        if (shared.isEmpty())
            return;
        Folder f = pick(shared);
        // revoking rewrites the subtree under new keys before deleting the old copy, which needs room under every cap.
        // Without it the revocation is refused, which is a known problem rather than something to find again here.
        long rewrite = upperBoundUnder(f);
        if (! capsOver(f).stream().allMatch(c -> upperBoundUnder(c) + rewrite <= c.cap.get())) {
            outcomes.merge("unshare/skipped-no-room", 1, Integer::sum);
            return;
        }
        String sharee = pick(new ArrayList<>(f.sharedWith));
        record("unshare " + f.path + " from " + sharee);
        owner.unShareWriteAccessWith(f.path, Set.of(sharee)).join();
        f.sharedWith.remove(sharee);
        everRevoked.add(sharee);
        // a session opened before the keys were rotated doesn't follow them, which is not what's being tested here
        for (String u : new ArrayList<>(users.keySet()))
            if (! u.equals(owner.username))
                freshSession(u);
        checkCapsPreserved();
    }

    private void freshSession(String username) {
        users.put(username, PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto));
    }

    private void setCap() {
        List<Folder> spaces = folders.stream().filter(f -> f.isWritingSpace).collect(Collectors.toList());
        if (spaces.isEmpty())
            return;
        Folder f = pick(spaces);
        Optional<Long> cap;
        int kind = rnd.nextInt(10);
        if (kind < 2)
            cap = Optional.empty();
        else if (kind < 4)
            cap = Optional.of(Math.max(1, contentsUnder(f) / 2)); // below what's already there
        else
            cap = Optional.of((long) (100 + rnd.nextInt(3000)) * KiB);
        record("cap " + f.path + " = " + cap);
        owner.setWriteShareQuota(f.path, cap).join();
        f.cap = cap;
    }

    private int uploadSize() {
        int kind = rnd.nextInt(10);
        if (kind < 5)
            return 1 + rnd.nextInt(32 * KiB);
        if (kind < 9)
            return 1 + rnd.nextInt(256 * KiB);
        return 1 + rnd.nextInt(1024 * KiB);
    }

    private void upload() {
        Folder f = pick(folders);
        List<String> writers = users.keySet().stream().filter(u -> canWrite(u, f)).collect(Collectors.toList());
        String username = pick(writers);
        UserContext user = users.get(username);
        int size = uploadSize();
        String name = "f" + fileCounter++;

        List<Folder> caps = capsOver(f);
        boolean mustReject = caps.stream().anyMatch(c -> contentsUnder(c) + size > c.cap.get() + TOLERANCE);
        boolean mustAccept = caps.stream().allMatch(c -> upperBoundUnder(c) + size <= c.cap.get());
        record("upload " + size + " to " + f.path.resolve(name) + " by " + username
                + (mustReject ? " [must reject]" : mustAccept ? " [must accept]" : " [either]")
                + caps.stream().map(c -> " " + c.path + ":" + contentsUnder(c) + "/" + c.cap.get()).collect(Collectors.joining()));

        byte[] data = new byte[size];
        rnd.nextBytes(data);
        boolean accepted;
        try {
            user.getByPath(f.path).join().get()
                    .uploadOrReplaceFile(name, AsyncReader.build(data), size, user.network, crypto, () -> false, x -> {})
                    .join();
            accepted = true;
        } catch (Exception e) {
            if (! isQuotaRejection(e))
                throw new AssertionError("Upload failed for a reason other than quota", e);
            accepted = false;
        }
        String outcome = (mustReject ? "must-reject" : mustAccept ? "must-accept" : "either") + (accepted ? "/accepted" : "/rejected");
        outcomes.merge(outcome, 1, Integer::sum);
        if (accepted && mustReject)
            throw new AssertionError("Upload of " + size + " was accepted over a cap: " + describe(caps));
        if (! accepted && mustAccept)
            throw new AssertionError("Upload of " + size + " was rejected under every cap: " + describe(caps));
        if (accepted)
            files.put(f.path.resolve(name), (long) size);
    }

    private static boolean isQuotaRejection(Exception e) {
        String message = peergos.shared.util.Exceptions.getRootCause(e).getMessage();
        return message != null && message.contains("Storage quota reached");
    }

    private String describe(List<Folder> caps) {
        return caps.stream()
                .map(c -> c.path + " contents=" + contentsUnder(c) + " cap=" + c.cap.get()
                        + " serverUsed=" + owner.getWriteShareQuota(c.path).join().used
                        + " writer=" + owner.getByPath(c.path).join().get().writer())
                .collect(Collectors.joining(", "))
                + "\nowner total usage=" + owner.getSpaceUsage(false).join()
                + " all file contents=" + files.values().stream().mapToLong(x -> x).sum()
                + "\nserver caps: " + owner.getWriteShareQuotas().join().stream()
                .map(i -> i.writer + " quota=" + i.quota + " used=" + i.used)
                .collect(Collectors.joining(", "));
    }

    private void delete() {
        if (files.isEmpty())
            return;
        Path file = pick(new ArrayList<>(files.keySet()));
        Folder f = folderOf(file);
        List<String> writers = users.keySet().stream().filter(u -> canWrite(u, f)).collect(Collectors.toList());
        String username = pick(writers);
        UserContext user = users.get(username);
        record("delete " + file + " by " + username);
        FileWrapper parent = user.getByPath(f.path).join().get();
        user.getByPath(file).join().get().remove(parent, file, user).join();
        files.remove(file);
        // usage drops once the server has processed the new root, and until then a delete frees nothing
        settle();
    }

    /** Stored bytes can't be less than the file contents stored */
    private void checkTotalUsage() {
        settle();
        long total = owner.getSpaceUsage(false).join();
        long contents = files.values().stream().mapToLong(x -> x).sum();
        if (total < contents)
            throw new AssertionError("Owner's usage " + total + " is less than the " + contents + " bytes of file contents");
    }

    private void checkCapsPreserved() {
        for (Folder f : folders) {
            if (! f.isWritingSpace)
                continue;
            Optional<Long> reported = owner.getWriteShareQuota(f.path).join().quota;
            if (! reported.equals(f.cap))
                throw new AssertionError("Cap on " + f.path + " is " + reported + ", expected " + f.cap);
        }
    }

    private void checkReported() {
        record("check reported");
        settle();
        long capped = folders.stream().filter(f -> f.cap.isPresent()).count();
        int listed = owner.getWriteShareQuotas().join().size();
        if (listed != capped)
            throw new AssertionError("Owner lists " + listed + " caps, expected " + capped);
        checkCapsPreserved();

        for (Folder f : folders) {
            List<String> sharees = users.keySet().stream()
                    .filter(u -> ! u.equals(owner.username) && canWrite(u, f))
                    .collect(Collectors.toList());
            if (sharees.isEmpty())
                continue;
            UserContext sharee = users.get(pick(sharees));
            WriterUsageInfo info = sharee.getWriteUsageInfo(sharee.getByPath(f.path).join().get()).join();
            List<Folder> caps = capsOver(f).stream()
                    .filter(c -> c.isWritingSpace)
                    .collect(Collectors.toList());
            // a folder that isn't its own writing space answers for the space it's in, which may be uncapped
            if (! f.isWritingSpace)
                continue;
            if (caps.isEmpty() != info.available.isEmpty())
                throw new AssertionError("Space left in " + f.path + " is " + info.available + " but caps are " + describe(caps));
            if (info.available.isPresent()) {
                long available = info.available.get();
                long mostPossible = caps.stream().mapToLong(c -> Math.max(0, c.cap.get() - contentsUnder(c))).min().getAsLong();
                if (available < 0 || available > mostPossible)
                    throw new AssertionError("Space left in " + f.path + " is " + available + ", at most " + mostPossible
                            + " is possible: " + describe(caps));
            }
        }
    }
}
