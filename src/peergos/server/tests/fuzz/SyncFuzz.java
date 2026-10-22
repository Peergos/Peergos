package peergos.server.tests.fuzz;

import org.junit.*;
import peergos.server.*;
import peergos.server.sync.*;
import peergos.server.tests.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;

/** Random edits on both sides of a sync pair, with syncs in between, some of them interrupted, checked against what
 *  the edits imply. Edits on the Peergos side go through the same calls the web UI makes, not through sync's own.
 *
 *  Outside tests/ proper so that CI doesn't run it. Run with
 *    FUZZ_SEED=123 FUZZ_STEPS=100 ant execute.test -Dtest.source.absolute=$(realpath src/peergos/server/tests/fuzz/SyncFuzz.java)
 *
 *  Other knobs:
 *    FUZZ_REMOTE=local      sync against a second local folder rather than Peergos, which is much faster, for the
 *                           sync logic alone
 *    FUZZ_LARGE=1           sometimes write files of more than one 5 MiB chunk
 *    FUZZ_NATURAL_MTIMES=1  leave local modification times to the clock, so several edits of a file within a second
 *                           can share one; otherwise each edit moves it on, as a person editing would
 *    FUZZ_VERBOSE=1         print every edit and the sync log as they happen
 *
 *  After every sync that runs to completion:
 *    - both sides hold the same tree, files and folders alike
 *    - no content written since the last check is gone, unless an edit deleted or overwrote it
 *    - each path holds what a three way merge of the last synced tree and the two sides says it should, wherever
 *      that is unambiguous: a path changed on only one side takes that side's version
 *    - often, a further sync finds nothing to do
 *  Deletes are synced in both directions. Contents carry a unique header, so they can be followed through moves.
 */
public class SyncFuzz {
    private static final Args args = UserTests.useMemoryDbs(UserTests.buildArgs()).with("enable-gc", "false");
    private static final Crypto crypto = Main.initCrypto();
    // a small pool so that names collide, across files and folders too
    private static final List<String> NAMES = List.of("a", "b", "files", "main", "doc.pdf", "x y.txt", "é.txt");
    private static final String DIR = "<dir>";
    private static final String EMPTY = "<empty>";
    private static final String HEADER = "sync-fuzz-content-";
    private static UserService service;

    private final Random rnd;
    private final long seed;
    private final int steps;
    private final boolean peergosRemote = ! "local".equals(System.getenv("FUZZ_REMOTE"));
    private final boolean large = System.getenv("FUZZ_LARGE") != null;
    private final boolean naturalModtimes = System.getenv("FUZZ_NATURAL_MTIMES") != null;
    private final boolean verbose = System.getenv("FUZZ_VERBOSE") != null;
    private final List<String> log = new ArrayList<>();
    private List<String> syncLog = new ArrayList<>();
    private final Map<String, Integer> outcomes = new TreeMap<>();
    private int contentCounter = 0;
    private long clock = System.currentTimeMillis() / 1000 * 1000 - 30L * 24 * 3600 * 1000;

    public SyncFuzz() {
        String seedEnv = System.getenv("FUZZ_SEED");
        String stepsEnv = System.getenv("FUZZ_STEPS");
        this.seed = seedEnv != null ? Long.parseLong(seedEnv) : new Random().nextLong();
        this.steps = stepsEnv != null ? Integer.parseInt(stepsEnv) : 60;
        this.rnd = new Random(seed);
    }

    @BeforeClass
    public static void init() {
        if (! "local".equals(System.getenv("FUZZ_REMOTE")))
            service = Main.PKI_INIT.main(args).localApi;
    }

    private static NetworkAccess network() {
        return NetworkAccess.buildBuffered(new CachingStorage(service.storage, 1_000, 50 * 1024),
                service.bats, service.coreNode, service.account, service.mutable, 0, service.social,
                service.controller, service.usage, service.serverMessages, crypto.hasher, Arrays.asList("peergos"), false);
    }

    /** One side of the pair, as a person would edit it. Paths are relative and '/' separated. */
    private interface Side {
        String name();

        /** path to content id, or DIR */
        TreeMap<String, String> tree();

        void write(String path, byte[] data);

        void delete(String path);

        void move(String from, String to);

        void mkdirs(String path);
    }

    private class LocalSide implements Side {
        private final String name;
        final Path base;

        LocalSide(String name, Path base) {
            this.name = name;
            this.base = base;
        }

        public String name() {
            return name;
        }

        private Path resolve(String path) {
            return path.isEmpty() ? base : base.resolve(Paths.get("", path.split("/")));
        }

        public TreeMap<String, String> tree() {
            TreeMap<String, String> res = new TreeMap<>();
            try (Stream<Path> all = Files.walk(base)) {
                for (Path p : all.filter(p -> ! p.equals(base)).collect(Collectors.toList())) {
                    String rel = StreamSupport.stream(base.relativize(p).spliterator(), false)
                            .map(Path::toString)
                            .collect(Collectors.joining("/"));
                    res.put(rel, Files.isDirectory(p) ? DIR : contentId(Files.readAllBytes(p)));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return res;
        }

        public void write(String path, byte[] data) {
            try {
                Path p = resolve(path);
                Files.createDirectories(p.getParent());
                Files.write(p, data);
                if (! naturalModtimes) {
                    clock += 2_000;
                    Files.setLastModifiedTime(p, FileTime.fromMillis(clock));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public void delete(String path) {
            try (Stream<Path> all = Files.walk(resolve(path))) {
                for (Path p : all.sorted(Comparator.reverseOrder()).collect(Collectors.toList()))
                    Files.delete(p);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public void move(String from, String to) {
            try {
                Files.createDirectories(resolve(to).getParent());
                Files.move(resolve(from), resolve(to));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public void mkdirs(String path) {
            try {
                Files.createDirectories(resolve(path));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private class PeergosSide implements Side {
        final UserContext context;
        final Path root;

        PeergosSide(UserContext context, Path root) {
            this.context = context;
            this.root = root;
        }

        public String name() {
            return "peergos";
        }

        private Path resolve(String path) {
            Path res = root;
            for (String name : path.split("/"))
                if (! name.isEmpty())
                    res = res.resolve(name);
            return res;
        }

        private FileWrapper get(String path) {
            return context.getByPath(resolve(path)).join()
                    .orElseThrow(() -> new IllegalStateException("No " + path + " in Peergos"));
        }

        private static String parent(String path) {
            return path.contains("/") ? path.substring(0, path.lastIndexOf("/")) : "";
        }

        private static String filename(String path) {
            return path.substring(path.lastIndexOf("/") + 1);
        }

        public TreeMap<String, String> tree() {
            TreeMap<String, String> res = new TreeMap<>();
            walk(get(""), "", res);
            return res;
        }

        private void walk(FileWrapper dir, String path, TreeMap<String, String> res) {
            for (FileWrapper child : dir.getChildren(crypto.hasher, context.network).join()) {
                String childPath = path.isEmpty() ? child.getName() : path + "/" + child.getName();
                if (child.isDirectory()) {
                    res.put(childPath, DIR);
                    walk(child, childPath, res);
                } else
                    res.put(childPath, contentId(read(child)));
            }
        }

        private byte[] read(FileWrapper f) {
            int size = (int) f.getSize();
            byte[] res = new byte[size];
            AsyncReader reader = f.getInputStream(context.network, crypto, x -> {}).join();
            int done = 0;
            while (done < size) {
                int read = reader.readIntoArray(res, done, size - done).join();
                if (read <= 0)
                    throw new IllegalStateException("Short read of " + f.getName());
                done += read;
            }
            reader.close();
            return res;
        }

        public void write(String path, byte[] data) {
            mkdirs(parent(path));
            get(parent(path)).uploadOrReplaceFile(filename(path), AsyncReader.build(data), 0, data.length,
                    context.network, crypto, x -> {}).join();
        }

        public void delete(String path) {
            get(path).remove(get(parent(path)), resolve(path), context).join();
        }

        public void move(String from, String to) {
            if (parent(from).equals(parent(to))) {
                get(from).rename(filename(to), get(parent(from)), resolve(from), context).join();
                return;
            }
            mkdirs(parent(to));
            get(from).moveTo(get(parent(to)), get(parent(from)), resolve(from), filename(to), context, () -> Futures.of(true)).join();
        }

        public void mkdirs(String path) {
            String sofar = "";
            for (String name : path.split("/")) {
                if (name.isEmpty())
                    continue;
                String next = sofar.isEmpty() ? name : sofar + "/" + name;
                if (context.getByPath(resolve(next)).join().isEmpty())
                    get(sofar).mkdir(name, context.network, false, context.mirrorBatId(), crypto).join();
                sofar = next;
            }
        }
    }

    private static String contentId(byte[] data) {
        if (data.length == 0)
            return EMPTY;
        String start = new String(data, 0, Math.min(data.length, 64), StandardCharsets.ISO_8859_1);
        if (! start.startsWith(HEADER) || ! start.contains("\n"))
            return "<foreign:" + data.length + ">";
        return start.substring(HEADER.length(), start.indexOf("\n"));
    }

    private byte[] newContent(int size) {
        String id = "c" + (++contentCounter);
        byte[] header = (HEADER + id + "\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] res = new byte[Math.max(size, header.length)];
        rnd.nextBytes(res);
        System.arraycopy(header, 0, res, 0, header.length);
        return res;
    }

    private int randomSize() {
        int r = rnd.nextInt(1000);
        if (r < 600)
            return rnd.nextInt(2 * 1024);
        if (r < 900)
            return 2 * 1024 + rnd.nextInt(200 * 1024);
        if (r < 970)
            return 200 * 1024 + rnd.nextInt(800 * 1024);
        // more than 1 MiB goes through the first sync's single file uploads
        if (r < 995 || ! large)
            return 1024 * 1024 + 1 + rnd.nextInt(1024 * 1024);
        return Chunk.LEGACY_SIZE + rnd.nextInt(2 * 1024 * 1024);
    }

    private byte[] randomContent() {
        return rnd.nextInt(50) == 0 ? new byte[0] : newContent(randomSize());
    }

    private <T> T pick(List<T> from) {
        return from.get(rnd.nextInt(from.size()));
    }

    private static String join(String dir, String name) {
        return dir.isEmpty() ? name : dir + "/" + name;
    }

    private static String parentOf(String path) {
        return path.contains("/") ? path.substring(0, path.lastIndexOf("/")) : "";
    }

    private static boolean isUnder(String path, String dir) {
        return path.equals(dir) || path.startsWith(dir + "/");
    }

    private void record(String s) {
        log.add(log.size() + ": " + s);
        if (verbose)
            System.out.println("EDIT " + s);
    }

    private void count(String outcome) {
        outcomes.merge(outcome, 1, Integer::sum);
    }

    /** Apply one random edit to side, adding any content it removes to killed */
    private void edit(Side side, Set<String> killed) {
        TreeMap<String, String> tree = side.tree();
        List<String> files = tree.entrySet().stream().filter(e -> ! e.getValue().equals(DIR)).map(Map.Entry::getKey).collect(Collectors.toList());
        List<String> dirs = tree.entrySet().stream().filter(e -> e.getValue().equals(DIR)).map(Map.Entry::getKey).collect(Collectors.toList());
        List<String> dirsAndRoot = new ArrayList<>(dirs);
        dirsAndRoot.add("");
        List<String> entries = new ArrayList<>(tree.keySet());
        String s = side.name();
        int op = rnd.nextInt(100);
        if (op < 25 || entries.isEmpty()) {
            String dir = pick(dirsAndRoot);
            if (rnd.nextInt(4) == 0)
                dir = join(dir, pick(NAMES));
            String path = join(dir, pick(NAMES));
            if (blocked(tree, path))
                return;
            byte[] data = randomContent();
            record(s + ": write " + path + " " + contentId(data) + " (" + data.length + " bytes)");
            side.write(path, data);
            count("write");
        } else if (op < 40 && ! files.isEmpty()) {
            String path = pick(files);
            byte[] data = rnd.nextInt(3) == 0 && ! tree.get(path).equals(EMPTY) ?
                    newContent(sizeOf(side, path)) :
                    randomContent();
            record(s + ": overwrite " + path + " " + tree.get(path) + " -> " + contentId(data) + " (" + data.length + " bytes)");
            killed.add(tree.get(path));
            side.write(path, data);
            count("overwrite");
        } else if (op < 50 && ! files.isEmpty()) {
            String path = pick(files);
            record(s + ": delete " + path + " " + tree.get(path));
            killed.add(tree.get(path));
            side.delete(path);
            count("delete");
        } else if (op < 55 && ! dirs.isEmpty()) {
            String dir = pick(dirs);
            record(s + ": delete folder " + dir);
            tree.forEach((p, c) -> {
                if (isUnder(p, dir) && ! c.equals(DIR))
                    killed.add(c);
            });
            side.delete(dir);
            count("delete folder");
        } else if (op < 65) {
            String from = pick(entries);
            String to = join(parentOf(from), pick(NAMES));
            if (blocked(tree, to))
                return;
            record(s + ": rename " + from + " -> " + to);
            side.move(from, to);
            count("rename");
        } else if (op < 77) {
            String from = pick(entries);
            String dir = pick(dirsAndRoot);
            if (rnd.nextInt(4) == 0)
                dir = join(dir, pick(NAMES));
            String to = join(dir, rnd.nextBoolean() ? from.substring(from.lastIndexOf("/") + 1) : pick(NAMES));
            if (isUnder(to, from) || parentOf(to).equals(parentOf(from)) || blocked(tree, to))
                return;
            record(s + ": move " + from + " -> " + to);
            side.move(from, to);
            count("move");
        } else if (op < 82 && ! files.isEmpty()) {
            String from = pick(files);
            String to = join(pick(dirsAndRoot), pick(NAMES));
            if (blocked(tree, to))
                return;
            record(s + ": copy " + from + " -> " + to + " " + tree.get(from));
            side.write(to, read(side, from));
            count("copy");
        } else if (op < 87) {
            String path = join(pick(dirsAndRoot), pick(NAMES));
            if (blocked(tree, path))
                return;
            record(s + ": mkdir " + path);
            side.mkdirs(path);
            count("mkdir");
        } else if (op < 94 && ! files.isEmpty()) {
            String path = pick(files);
            record(s + ": replace file " + path + " " + tree.get(path) + " with a folder");
            killed.add(tree.get(path));
            side.delete(path);
            side.mkdirs(path);
            if (rnd.nextBoolean()) {
                byte[] data = randomContent();
                String child = join(path, pick(NAMES));
                record(s + ": write " + child + " " + contentId(data));
                side.write(child, data);
            }
            count("file to folder");
        } else if (! dirs.isEmpty()) {
            String dir = pick(dirs);
            record(s + ": replace folder " + dir + " with a file");
            tree.forEach((p, c) -> {
                if (isUnder(p, dir) && ! c.equals(DIR))
                    killed.add(c);
            });
            side.delete(dir);
            byte[] data = randomContent();
            record(s + ": write " + dir + " " + contentId(data));
            side.write(dir, data);
            count("folder to file");
        }
    }

    /** Whether path or one of its ancestors is taken, by anything, or by a file */
    private static boolean blocked(Map<String, String> tree, String path) {
        if (tree.containsKey(path))
            return true;
        for (String dir = parentOf(path); ! dir.isEmpty(); dir = parentOf(dir))
            if (tree.containsKey(dir) && ! tree.get(dir).equals(DIR))
                return true;
        return false;
    }

    private byte[] read(Side side, String path) {
        if (side instanceof LocalSide l) {
            try {
                return Files.readAllBytes(l.resolve(path));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        PeergosSide p = (PeergosSide) side;
        return p.read(p.get(path));
    }

    private int sizeOf(Side side, String path) {
        return read(side, path).length;
    }

    private interface SyncRun {
        void run(Supplier<Boolean> isCancelled) throws IOException;
    }

    private static boolean isAction(String line) {
        String l = line.toLowerCase();
        return Stream.of("upload", "copying", "moving", "delete", "mkdir", "renam", "set mod time", "conflict")
                .anyMatch(l::contains);
    }

    @Test
    public void fuzz() throws Exception {
        System.out.println("SyncFuzz seed=" + seed + " steps=" + steps + " remote=" + (peergosRemote ? "peergos" : "local"));
        Path localBase = Files.createTempDirectory("sync-fuzz-local");
        Path peergosDir = Files.createTempDirectory("sync-fuzz-state");
        LocalSide local = new LocalSide("local", localBase);
        LocalFileSystem localFs = new LocalFileSystem(localBase, crypto.hasher);
        Side remote;
        Supplier<SyncFilesystem> remoteFs;
        PublicKeyHash owner;
        NetworkAccess syncNetwork;
        if (peergosRemote) {
            UserContext context = PeergosNetworkUtils.ensureSignedUp("sf" + Math.abs(rnd.nextInt() % 1_000_000), "password", network(), crypto);
            context.getUserRoot().join().mkdir("sync", context.network, false, context.mirrorBatId(), crypto).join();
            Path root = PathUtil.get(context.username, "sync");
            LinkProperties link = context.createSecretLink(root.toString(), true, Optional.empty(), Optional.<Integer>empty(), "", false).join();
            String linkString = link.toLinkString(context.signer.publicKeyHash);
            remote = new PeergosSide(context, root);
            // the sync client has a network of its own, and builds the remote afresh for every pass
            syncNetwork = network();
            remoteFs = () -> DirectorySync.buildRemote(linkString, syncNetwork, crypto);
            owner = context.signer.publicKeyHash;
        } else {
            Path remoteBase = Files.createTempDirectory("sync-fuzz-remote");
            remote = new LocalSide("remote", remoteBase);
            LocalFileSystem fs = new LocalFileSystem(remoteBase, crypto.hasher);
            remoteFs = () -> fs;
            owner = null;
            syncNetwork = null;
        }
        AtomicReference<SyncState> state = new AtomicReference<>(new JdbcTreeState(":memory:"));
        SyncRun sync = isCancelled -> {
            syncLog = new ArrayList<>();
            List<String> lines = syncLog;
            DirectorySync.syncDir(localFs, remoteFs.get(), true, true, owner, syncNetwork, state.get(), 32, 5,
                    peergosDir, crypto, isCancelled, line -> {
                        lines.add(line);
                        if (verbose)
                            System.out.println("SYNC " + line);
                    });
        };

        // the tree both sides held after the last checked sync, if the edits since then are all we know about
        Optional<TreeMap<String, String>> base = Optional.of(new TreeMap<>());
        Set<String> present = new HashSet<>();
        Set<String> killed = new HashSet<>();
        int step = 0;
        try {
            for (step = 0; step < steps; step++) {
                int edits = 1 + rnd.nextInt(5);
                for (int i = 0; i < edits; i++)
                    edit(rnd.nextBoolean() ? local : remote, killed);
                TreeMap<String, String> localBefore = local.tree(), remoteBefore = remote.tree();
                present.addAll(localBefore.values());
                present.addAll(remoteBefore.values());

                int r = rnd.nextInt(100);
                if (r < 3) {
                    // as when someone clears the app's storage and sets the sync up again
                    record("reset sync state");
                    state.set(new JdbcTreeState(":memory:"));
                    base = Optional.empty();
                    count("reset state");
                } else if (r < 15) {
                    int limit = 1 + rnd.nextInt(300);
                    AtomicInteger checks = new AtomicInteger();
                    record("sync, cancelled after " + limit + " checks");
                    try {
                        sync.run(() -> checks.incrementAndGet() > limit);
                        count("sync not reaching its cancel");
                    } catch (Exception e) {
                        count("sync cancelled");
                    }
                    base = Optional.empty();
                    continue;
                }
                record("sync");
                sync.run(() -> false);
                count("sync");
                TreeMap<String, String> after = check(local, remote, sync, base, localBefore, remoteBefore, present, killed);
                base = Optional.of(after);
                present.clear();
                killed.clear();
            }
        } catch (Throwable t) {
            int from = Math.max(0, log.size() - 60);
            String edits = String.join("\n", log.subList(from, log.size()));
            int syncFrom = Math.max(0, syncLog.size() - 80);
            String lastSync = String.join("\n", syncLog.subList(syncFrom, syncLog.size()));
            throw new AssertionError("SyncFuzz failed at step " + step + ", seed=" + seed + "\nlast edits:\n" + edits +
                    "\nlast sync log:\n" + lastSync, t);
        }
        System.out.println("SyncFuzz seed=" + seed + " passed. Outcomes: " + outcomes);
    }

    private TreeMap<String, String> check(Side local,
                                          Side remote,
                                          SyncRun sync,
                                          Optional<TreeMap<String, String>> base,
                                          TreeMap<String, String> localBefore,
                                          TreeMap<String, String> remoteBefore,
                                          Set<String> present,
                                          Set<String> killed) throws IOException {
        TreeMap<String, String> localAfter = local.tree(), remoteAfter = remote.tree();
        if (! localAfter.equals(remoteAfter))
            throw new AssertionError("Sides differ after a sync:\n" + diff(localAfter, remoteAfter) +
                    "\nbefore the sync:\n" + diff(localBefore, remoteBefore));

        Set<String> contents = new HashSet<>(localAfter.values());
        for (String content : present) {
            if (content.equals(DIR) || content.equals(EMPTY) || killed.contains(content))
                continue;
            if (! contents.contains(content))
                throw new AssertionError("Lost content " + content + ", which was at " + where(content, localBefore) +
                        " locally and " + where(content, remoteBefore) + " remotely before the sync. Now:\n" + localAfter);
        }

        if (base.isPresent()) {
            Set<String> paths = new TreeSet<>(base.get().keySet());
            paths.addAll(localBefore.keySet());
            paths.addAll(remoteBefore.keySet());
            for (String path : paths) {
                if (typeClash(path, localBefore, remoteBefore))
                    continue;
                String was = fileAt(base.get(), path), l = fileAt(localBefore, path), r = fileAt(remoteBefore, path);
                // sync follows contents to a new path as a move, and two sides moving one file differently is a
                // conflict, so only judge paths whose contents are nowhere else
                if (Stream.of(was, l, r).filter(Objects::nonNull)
                        .anyMatch(c -> elsewhere(c, path, base.get()) || elsewhere(c, path, localBefore) || elsewhere(c, path, remoteBefore)))
                    continue;
                String expected;
                if (Objects.equals(l, r))
                    expected = l;
                else if (Objects.equals(l, was))
                    expected = r;
                else if (Objects.equals(r, was))
                    expected = l;
                else
                    continue; // changed on both sides, so a conflict, which only the content check judges
                String actual = fileAt(localAfter, path);
                if (! Objects.equals(expected, actual))
                    throw new AssertionError(path + " holds " + actual + " after the sync, but should hold " + expected +
                            " (last synced " + was + ", local " + l + ", remote " + r + ")");
            }
        }

        if (rnd.nextInt(3) == 0) {
            record("sync again, expecting nothing to do");
            sync.run(() -> false);
            List<String> actions = syncLog.stream().filter(SyncFuzz::isAction).collect(Collectors.toList());
            if (! actions.isEmpty())
                throw new AssertionError("A sync straight after a sync did:\n" + String.join("\n", actions));
            TreeMap<String, String> again = local.tree();
            if (! again.equals(localAfter) || ! remote.tree().equals(localAfter))
                throw new AssertionError("A sync straight after a sync changed the trees:\n" + diff(localAfter, again));
            count("stable");
        }
        return localAfter;
    }

    private static boolean elsewhere(String content, String path, Map<String, String> tree) {
        return tree.entrySet().stream().anyMatch(e -> e.getValue().equals(content) && ! e.getKey().equals(path));
    }

    private static String fileAt(Map<String, String> tree, String path) {
        String c = tree.get(path);
        return c == null || c.equals(DIR) ? null : c;
    }

    /** Whether the sides disagree about whether path, or one of its ancestors, is a file or a folder */
    private static boolean typeClash(String path, Map<String, String> a, Map<String, String> b) {
        for (String p = path; ! p.isEmpty(); p = parentOf(p)) {
            String x = a.get(p), y = b.get(p);
            if (x != null && y != null && x.equals(DIR) != y.equals(DIR))
                return true;
        }
        return false;
    }

    private static List<String> where(String content, Map<String, String> tree) {
        return tree.entrySet().stream().filter(e -> e.getValue().equals(content)).map(Map.Entry::getKey).collect(Collectors.toList());
    }

    private static String diff(Map<String, String> local, Map<String, String> remote) {
        Set<String> paths = new TreeSet<>(local.keySet());
        paths.addAll(remote.keySet());
        return paths.stream()
                .filter(p -> ! Objects.equals(local.get(p), remote.get(p)))
                .map(p -> "  " + p + ": local " + local.get(p) + ", remote " + remote.get(p))
                .collect(Collectors.joining("\n"));
    }
}
