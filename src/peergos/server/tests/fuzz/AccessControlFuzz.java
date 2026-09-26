package peergos.server.tests.fuzz;

import org.junit.*;
import peergos.server.*;
import peergos.server.tests.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** Random operations by an owner, friends with no, read or write access, a stranger, and sessions kept from before a
 *  revocation, with writes failing at random. Checked against a model for access violations and data loss.
 *
 *  Outside tests/ proper so that CI doesn't run it. Run with
 *    FUZZ_SEED=123 FUZZ_STEPS=300 ant execute.test -Dtest.source.absolute=$(realpath src/peergos/server/tests/fuzz/AccessControlFuzz.java)
 *  FUZZ_FAULT_RATE sets the chance that a write call fails (default 0.1).
 *
 *  Invariants:
 *   - an actor without read access can't read or list, and without write access can't create, change or delete
 *   - a session kept from before a revocation can't read anything written after it, or write at all
 *   - an actor with access can do what it's allowed, when no fault was injected
 *   - after any operation, including one that failed part way, every file the owner has is there with exactly the
 *     contents it should have; the file being changed has either its old contents or its new ones
 */
public class AccessControlFuzz {
    private static final int KiB = 1024;
    private static final int MAX_FOLDERS = 10;
    private static final int MAX_DEPTH = 3;
    private static final long TIMEOUT_SECONDS = 120;

    private static final Args args = UserTests.useMemoryDbs(UserTests.buildArgs()).with("enable-gc", "false");
    private static UserService service;
    private static final Crypto crypto = Main.initCrypto();

    enum Level { NONE, READ, WRITE }

    private static class Folder {
        final Path path;
        final Map<String, Level> grants = new HashMap<>();

        Folder(Path path) {
            this.path = path;
        }
    }

    /** A session and handles kept from just before the actor lost access, as an attacker would keep them */
    private static class Stale {
        final String username;
        final Level lostLevel;
        final UserContext context;
        final Path folder;
        final FileWrapper folderHandle;
        final Set<String> namesAtRevocation;
        final Map<Path, FileWrapper> fileHandles;
        final Map<Path, byte[]> contentsAtRevocation;

        Stale(String username, Level lostLevel, UserContext context, Path folder, FileWrapper folderHandle,
              Set<String> namesAtRevocation, Map<Path, FileWrapper> fileHandles, Map<Path, byte[]> contentsAtRevocation) {
            this.username = username;
            this.lostLevel = lostLevel;
            this.context = context;
            this.folder = folder;
            this.folderHandle = folderHandle;
            this.namesAtRevocation = namesAtRevocation;
            this.fileHandles = fileHandles;
            this.contentsAtRevocation = contentsAtRevocation;
        }
    }

    /** Fails write calls at random, either before they reach the server or after it has applied them */
    private static class Faults {
        final Random rnd;
        final double rate;
        volatile boolean enabled = false;
        int injected = 0;

        Faults(Random rnd, double rate) {
            this.rnd = rnd;
            this.rate = rate;
        }

        synchronized <T> CompletableFuture<T> apply(java.util.function.Supplier<CompletableFuture<T>> call) {
            if (! enabled)
                return call.get();
            double r = rnd.nextDouble();
            if (r < rate / 2) {
                injected++;
                return Futures.errored(new RuntimeException("Injected fault before write"));
            }
            if (r < rate) {
                injected++;
                return call.get().thenCompose(x -> Futures.errored(new RuntimeException("Injected fault after write")));
            }
            return call.get();
        }
    }

    private final Random rnd;
    private final long seed;
    private final int steps;
    private final Faults faults;
    private final List<String> log = new ArrayList<>();
    private final List<Folder> folders = new ArrayList<>();
    private final Map<Path, byte[]> files = new HashMap<>();
    private final Map<String, UserContext> sessions = new LinkedHashMap<>();
    private final List<String> friends = new ArrayList<>();
    private final List<Stale> stale = new ArrayList<>();
    private final Map<String, Integer> outcomes = new TreeMap<>();
    private String owner, stranger;
    private int counter = 0;

    public AccessControlFuzz() {
        String seedEnv = System.getenv("FUZZ_SEED");
        String stepsEnv = System.getenv("FUZZ_STEPS");
        String rateEnv = System.getenv("FUZZ_FAULT_RATE");
        this.seed = seedEnv != null ? Long.parseLong(seedEnv) : new Random().nextLong();
        this.steps = stepsEnv != null ? Integer.parseInt(stepsEnv) : 200;
        this.rnd = new Random(seed);
        this.faults = new Faults(new Random(seed * 31 + 7), rateEnv != null ? Double.parseDouble(rateEnv) : 0.1);
    }

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
    }

    private NetworkAccess cleanNetwork() {
        return NetworkAccess.buildBuffered(new CachingStorage(service.storage, 1_000, 50 * 1024),
                service.bats, service.coreNode, service.account, service.mutable, 0, service.social,
                service.controller, service.usage, service.serverMessages, crypto.hasher, Arrays.asList("peergos"), false);
    }

    private NetworkAccess faultyNetwork() {
        ContentAddressedStorage storage = new DelegatingStorage(service.storage) {
            @Override
            public ContentAddressedStorage directToOrigin() {
                return this;
            }
            @Override
            public CompletableFuture<List<Cid>> put(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signedHashes, List<byte[]> blocks, TransactionId tid) {
                return faults.apply(() -> super.put(owner, writer, signedHashes, blocks, tid));
            }
            @Override
            public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks, TransactionId tid, ProgressConsumer<Long> progress) {
                return faults.apply(() -> super.putRaw(owner, writer, signatures, blocks, tid, progress));
            }
            @Override
            public CompletableFuture<List<Cid>> putBatch(PublicKeyHash owner, SigningPrivateKeyAndPublicHash signer, List<byte[]> blocks, TransactionId tid, Hasher hasher) {
                return faults.apply(() -> super.putBatch(owner, signer, blocks, tid, hasher));
            }
            @Override
            public CompletableFuture<List<Cid>> putRawBatch(PublicKeyHash owner, SigningPrivateKeyAndPublicHash signer, List<byte[]> blocks, TransactionId tid, ProgressConsumer<Long> progress, Hasher hasher) {
                return faults.apply(() -> super.putRawBatch(owner, signer, blocks, tid, progress, hasher));
            }
            @Override
            public CompletableFuture<List<Cid>> putBatch(PublicKeyHash owner, PublicKeyHash writer, BlockWriteBatch batch, boolean isRaw, TransactionId tid) {
                return faults.apply(() -> super.putBatch(owner, writer, batch, isRaw, tid));
            }
            @Override
            public CompletableFuture<List<Cid>> bulkCommit(PublicKeyHash owner, BulkCommit commit) {
                return faults.apply(() -> super.bulkCommit(owner, commit));
            }
        };
        MutablePointers mutable = new MutablePointers() {
            @Override
            public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] signed) {
                return faults.apply(() -> service.mutable.setPointer(owner, writer, signed));
            }
            @Override
            public CompletableFuture<Boolean> setPointers(PublicKeyHash owner, List<SignedPointerUpdate> updates) {
                return faults.apply(() -> service.mutable.setPointers(owner, updates));
            }
            @Override
            public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
                return service.mutable.getPointer(owner, writer);
            }
            @Override
            public MutablePointers clearCache() {
                return this;
            }
        };
        return NetworkAccess.buildBuffered(storage, service.bats, service.coreNode, service.account, mutable, 0,
                service.social, service.controller, service.usage, service.serverMessages, crypto.hasher,
                Arrays.asList("peergos"), false);
    }

    private UserContext signIn(String username, boolean faulty) {
        return PeergosNetworkUtils.ensureSignedUp(username, "password", faulty ? faultyNetwork() : cleanNetwork(), crypto);
    }

    private void freshSession(String username) {
        sessions.put(username, signIn(username, true));
    }

    private UserContext auditor() {
        return signIn(owner, false);
    }

    private static <T> T await(CompletableFuture<T> f) {
        try {
            return f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("Operation hung for " + TIMEOUT_SECONDS + "s", e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new CompletionException(e.getCause());
        }
    }

    @Test
    public void fuzz() {
        System.out.println("AccessControlFuzz seed=" + seed + " steps=" + steps + " faultRate=" + faults.rate);
        String suffix = "" + Math.abs(rnd.nextInt() % 1_000_000);
        owner = "ao" + suffix;
        sessions.put(owner, signIn(owner, true));
        List<UserContext> friendContexts = new ArrayList<>();
        for (String name : List.of("aa", "ab", "ac")) {
            String username = name + suffix;
            friends.add(username);
            UserContext ctx = signIn(username, true);
            sessions.put(username, ctx);
            friendContexts.add(ctx);
        }
        stranger = "as" + suffix;
        sessions.put(stranger, signIn(stranger, true));
        PeergosNetworkUtils.friendBetweenGroups(List.of(sessions.get(owner)), friendContexts);
        UserContext o = sessions.get(owner);
        await(o.getUserRoot().join().mkdir("acl", o.network, false, o.mirrorBatId(), crypto));
        folders.add(new Folder(PathUtil.get(owner, "acl")));

        try {
            for (int step = 0; step < steps; step++) {
                int op = rnd.nextInt(100);
                if (op < 5) ownerMkdir();
                else if (op < 14) ownerUpload();
                else if (op < 20) ownerOverwrite();
                else if (op < 24) ownerDelete();
                else if (op < 27) ownerRename();
                else if (op < 34) share(Level.READ);
                else if (op < 39) share(Level.WRITE);
                else if (op < 43) revoke(Level.READ);
                else if (op < 47) revoke(Level.WRITE);
                else if (op < 60) actorRead();
                else if (op < 65) actorList();
                else if (op < 73) actorUpload();
                else if (op < 79) actorOverwrite();
                else if (op < 83) actorDelete();
                else if (op < 86) actorRename();
                else if (op < 96) staleAttack();
                else audit();
            }
            audit();
        } catch (Throwable t) {
            throw new AssertionError("AccessControlFuzz failed, seed=" + seed + "\nops:\n"
                    + String.join("\n", log), t);
        }
        System.out.println("AccessControlFuzz seed=" + seed + " passed. Faults injected: " + faults.injected
                + ". Outcomes: " + outcomes);
    }

    private void record(String s) {
        log.add(log.size() + ": " + s);
    }

    private void count(String outcome) {
        outcomes.merge(outcome, 1, Integer::sum);
    }

    private <T> T pick(List<T> from) {
        return from.get(rnd.nextInt(from.size()));
    }

    private boolean isUnder(Path p, Path ancestor) {
        return p.startsWith(ancestor) && ! p.equals(ancestor);
    }

    private Folder folderAt(Path p) {
        return folders.stream().filter(f -> f.path.equals(p)).findFirst().orElse(null);
    }

    /** The access a user has to a folder, through a grant on it or on a folder above it */
    private Level level(String username, Path folder) {
        if (username.equals(owner))
            return Level.WRITE;
        Level best = Level.NONE;
        for (Folder f : folders) {
            if (! (f.path.equals(folder) || isUnder(folder, f.path)))
                continue;
            Level l = f.grants.getOrDefault(username, Level.NONE);
            if (l.ordinal() > best.ordinal())
                best = l;
        }
        return best;
    }

    private byte[] randomContents() {
        int kind = rnd.nextInt(10);
        int size = kind == 0 ? 0 : kind < 8 ? rnd.nextInt(8 * KiB) : rnd.nextInt(64 * KiB);
        byte[] data = new byte[size];
        rnd.nextBytes(data);
        return data;
    }

    private String nonOwner() {
        List<String> actors = new ArrayList<>(friends);
        actors.add(stranger);
        return pick(actors);
    }

    private Throwable lastFindError;

    private Optional<FileWrapper> find(UserContext ctx, Path p) {
        lastFindError = null;
        try {
            return await(ctx.getByPath(p));
        } catch (Exception e) {
            lastFindError = e;
            return Optional.empty();
        }
    }

    private static byte[] read(UserContext ctx, FileWrapper file) {
        long size = file.getFileProperties().size;
        return await(Serialize.readFully(await(file.getInputStream(ctx.network, crypto, x -> {})), size));
    }

    /** Runs a write with faults on. Returns true if it reported success. */
    private boolean attempt(String username, Runnable write) {
        faults.enabled = true;
        int before = faults.injected;
        try {
            write.run();
            return true;
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            if (faults.injected == before)
                throw new CompletionException("Failed with no fault injected", e);
            // a session that saw a failure may hold state it didn't commit; start again as a user would
            freshSession(username);
            return false;
        } finally {
            faults.enabled = false;
        }
    }

    /** Runs a write that the actor isn't allowed to do. It must fail and change nothing. */
    private void forbidden(String what, Runnable write) {
        try {
            write.run();
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            count("forbidden/refused");
            return;
        }
        throw new AssertionError("ACCESS VIOLATION: " + what + " succeeded");
    }

    // ---- owner operations ----

    private void ownerMkdir() {
        List<Folder> parents = folders.stream().filter(f -> f.path.getNameCount() - 2 < MAX_DEPTH).collect(Collectors.toList());
        if (folders.size() >= MAX_FOLDERS || parents.isEmpty())
            return;
        Folder parent = pick(parents);
        String name = "d" + counter++;
        Path path = parent.path.resolve(name);
        record("owner mkdir " + path);
        UserContext o = sessions.get(owner);
        boolean ok = attempt(owner, () -> await(find(o, parent.path).get().mkdir(name, o.network, false, o.mirrorBatId(), crypto)));
        boolean exists = find(auditor(), path).isPresent();
        if (ok && ! exists)
            throw new AssertionError("DATA LOSS: mkdir reported success but " + path + " is missing");
        if (exists)
            folders.add(new Folder(path));
        if (! ok)
            audit();
    }

    private void upload(String username, Path folder, String name, byte[] data) {
        UserContext ctx = sessions.get(username);
        FileWrapper dir = find(ctx, folder).orElseThrow(() -> new IllegalStateException("Can't see " + folder));
        await(dir.uploadOrReplaceFile(name, AsyncReader.build(data), data.length, ctx.network, crypto, () -> false, x -> {}));
    }

    /** Settle the model after a change that may or may not have happened, and fail on anything else */
    private void resolveWrite(Path file, Optional<byte[]> before, Optional<byte[]> after, boolean reportedOk) {
        UserContext a = auditor();
        Optional<byte[]> now = find(a, file).map(f -> read(a, f));
        boolean isBefore = same(now, before), isAfter = same(now, after);
        if (reportedOk && ! isAfter)
            throw new AssertionError("DATA LOSS: " + file + " reported written but holds " + describe(now));
        if (! isBefore && ! isAfter)
            throw new AssertionError("DATA LOSS: " + file + " holds " + describe(now) + ", neither the old "
                    + describe(before) + " nor the new " + describe(after) + torn(now, before, after));
        if (now.isPresent())
            files.put(file, now.get());
        else
            files.remove(file);
    }

    /** Recognise the new contents written over the start of the old ones, with the old tail left in place */
    private static String torn(Optional<byte[]> now, Optional<byte[]> before, Optional<byte[]> after) {
        if (now.isEmpty() || before.isEmpty() || after.isEmpty())
            return "";
        byte[] n = now.get(), o = before.get(), w = after.get();
        if (n.length != o.length || w.length > n.length)
            return "";
        boolean newHead = Arrays.equals(Arrays.copyOfRange(n, 0, w.length), w);
        boolean oldTail = Arrays.equals(Arrays.copyOfRange(n, w.length, n.length), Arrays.copyOfRange(o, w.length, o.length));
        return newHead && oldTail ? ". It is torn: the new " + w.length + " bytes followed by the old file's last "
                + (o.length - w.length) + " bytes" : "";
    }

    private static boolean same(Optional<byte[]> a, Optional<byte[]> b) {
        return a.isPresent() == b.isPresent() && (a.isEmpty() || Arrays.equals(a.get(), b.get()));
    }

    private static String describe(Optional<byte[]> contents) {
        return contents.map(c -> c.length + " bytes (" + Arrays.hashCode(c) + ")").orElse("nothing");
    }

    private void ownerUpload() {
        Folder f = pick(folders);
        Path file = f.path.resolve("f" + counter++);
        byte[] data = randomContents();
        record("owner upload " + data.length + " to " + file);
        boolean ok = attempt(owner, () -> upload(owner, f.path, file.getFileName().toString(), data));
        resolveWrite(file, Optional.empty(), Optional.of(data), ok);
        if (! ok)
            audit();
    }

    private void ownerOverwrite() {
        if (files.isEmpty())
            return;
        Path file = pick(new ArrayList<>(files.keySet()));
        byte[] old = files.get(file), data = randomContents();
        record("owner overwrite " + file + " with " + data.length);
        boolean ok = attempt(owner, () -> upload(owner, file.getParent(), file.getFileName().toString(), data));
        resolveWrite(file, Optional.of(old), Optional.of(data), ok);
        if (! ok)
            audit();
    }

    private void delete(String username, Path file) {
        UserContext ctx = sessions.get(username);
        FileWrapper parent = find(ctx, file.getParent()).orElseThrow(() -> new IllegalStateException("Can't see " + file.getParent()));
        FileWrapper target = find(ctx, file).orElseThrow(() -> new IllegalStateException("Can't see " + file));
        await(target.remove(parent, file, ctx));
    }

    private void ownerDelete() {
        if (files.isEmpty())
            return;
        Path file = pick(new ArrayList<>(files.keySet()));
        record("owner delete " + file);
        byte[] old = files.get(file);
        boolean ok = attempt(owner, () -> delete(owner, file));
        resolveWrite(file, Optional.of(old), Optional.empty(), ok);
        if (! ok)
            audit();
    }

    private void rename(String username, Path file, String newName) {
        UserContext ctx = sessions.get(username);
        FileWrapper parent = find(ctx, file.getParent()).orElseThrow(() -> new IllegalStateException("Can't see " + file.getParent()));
        FileWrapper target = find(ctx, file).orElseThrow(() -> new IllegalStateException("Can't see " + file));
        await(target.rename(newName, parent, file, ctx));
    }

    private void resolveRename(Path from, Path to, byte[] contents, boolean reportedOk) {
        UserContext a = auditor();
        Optional<byte[]> atFrom = find(a, from).map(f -> read(a, f));
        Optional<byte[]> atTo = find(a, to).map(f -> read(a, f));
        boolean moved = atFrom.isEmpty() && same(atTo, Optional.of(contents));
        boolean stayed = atTo.isEmpty() && same(atFrom, Optional.of(contents));
        if (reportedOk && ! moved)
            throw new AssertionError("DATA LOSS: rename " + from + " -> " + to + " reported done but from holds "
                    + describe(atFrom) + " and to holds " + describe(atTo));
        if (! moved && ! stayed)
            throw new AssertionError("DATA LOSS: rename " + from + " -> " + to + " left " + describe(atFrom)
                    + " and " + describe(atTo));
        if (moved) {
            files.remove(from);
            files.put(to, contents);
        }
    }

    private void ownerRename() {
        if (files.isEmpty())
            return;
        Path file = pick(new ArrayList<>(files.keySet()));
        Path to = file.getParent().resolve("r" + counter++);
        record("owner rename " + file + " -> " + to);
        byte[] contents = files.get(file);
        boolean ok = attempt(owner, () -> rename(owner, file, to.getFileName().toString()));
        resolveRename(file, to, contents, ok);
        if (! ok)
            audit();
    }

    private void share(Level level) {
        Folder f = pick(folders);
        String friend = pick(friends);
        if (f.grants.getOrDefault(friend, Level.NONE) != Level.NONE)
            return;
        record("owner share " + level + " " + f.path + " with " + friend);
        UserContext o = sessions.get(owner);
        Runnable doShare = () -> await(level == Level.WRITE ?
                sessions.get(owner).shareWriteAccessWith(f.path, Set.of(friend)) :
                sessions.get(owner).shareReadAccessWith(f.path, Set.of(friend)));
        if (! attempt(owner, doShare)) {
            count("share/retried");
            retry(doShare);
        }
        f.grants.put(friend, level);
        // a share into a folder that just became its own writing space rewrites links, which older sessions miss
        freshSession(friend);
        audit();
    }

    /** A share or revocation that failed part way must succeed when tried again, as a user would */
    private void retry(Runnable op) {
        for (int i = 0; i < 3; i++) {
            try {
                op.run();
                return;
            } catch (AssertionError e) {
                throw e;
            } catch (Exception e) {
                record("  retry failed: " + peergos.shared.util.Exceptions.getRootCause(e).getMessage());
                freshSession(owner);
            }
        }
        throw new AssertionError("A share or revocation that failed part way still fails without faults");
    }

    private void revoke(Level level) {
        List<Pair<Folder, String>> grants = new ArrayList<>();
        for (Folder f : folders)
            for (Map.Entry<String, Level> e : f.grants.entrySet())
                if (e.getValue() == level)
                    grants.add(new Pair<>(f, e.getKey()));
        if (grants.isEmpty())
            return;
        Pair<Folder, String> g = pick(grants);
        Folder f = g.left;
        String friend = g.right;
        record("owner revoke " + level + " " + f.path + " from " + friend);

        // keep what the actor holds now, as an attacker would
        UserContext before = sessions.get(friend);
        Level levelBefore = level(friend, f.path);
        Optional<FileWrapper> folderHandle = find(before, f.path);
        Map<Path, FileWrapper> handles = new HashMap<>();
        Map<Path, byte[]> contents = new HashMap<>();
        for (Map.Entry<Path, byte[]> e : files.entrySet()) {
            if (! isUnder(e.getKey(), f.path))
                continue;
            find(before, e.getKey()).ifPresent(h -> {
                handles.put(e.getKey(), h);
                contents.put(e.getKey(), e.getValue());
            });
        }
        Set<String> names = folderHandle.map(h -> childNames(before, h)).orElse(Set.of());

        Runnable doRevoke = () -> await(level == Level.WRITE ?
                sessions.get(owner).unShareWriteAccessWith(f.path, Set.of(friend)) :
                sessions.get(owner).unShareReadAccessWith(f.path, Set.of(friend)));
        if (! attempt(owner, doRevoke)) {
            count("revoke/retried");
            if (stillShared(f.path, friend, level))
                retry(doRevoke);
        }
        if (stillShared(f.path, friend, level))
            throw new AssertionError("Revoking " + level + " from " + friend + " on " + f.path + " left it shared");
        f.grants.remove(friend);

        Level levelAfter = level(friend, f.path);
        // only a revocation that actually took access away leaves handles that must stop working
        if (folderHandle.isPresent() && levelAfter.ordinal() < levelBefore.ordinal())
            stale.add(new Stale(friend, levelBefore, before, f.path, folderHandle.get(), names, handles, contents));
        for (String u : friends)
            freshSession(u);
        audit();
    }

    private boolean stillShared(Path path, String friend, Level level) {
        FileSharedWithState state = await(auditor().sharedWith(path));
        Set<String> holders = level == Level.WRITE ? state.writeAccess : state.readAccess;
        return holders.contains(friend);
    }

    private Set<String> childNames(UserContext ctx, FileWrapper dir) {
        return await(dir.getChildren(crypto.hasher, ctx.network)).stream()
                .map(FileWrapper::getName)
                .collect(Collectors.toSet());
    }

    // ---- other actors ----

    private void actorRead() {
        if (files.isEmpty())
            return;
        String actor = nonOwner();
        Path file = pick(new ArrayList<>(files.keySet()));
        Level l = level(actor, file.getParent());
        record(actor + " read " + file + " [" + l + "]");
        UserContext ctx = sessions.get(actor);
        Optional<FileWrapper> found = find(ctx, file);
        if (l == Level.NONE) {
            if (found.isPresent()) {
                byte[] got;
                try {
                    got = read(ctx, found.get());
                } catch (Exception e) {
                    count("read/refused");
                    return;
                }
                throw new AssertionError("ACCESS VIOLATION: " + actor + " with no access read " + got.length + " bytes of " + file);
            }
            count("read/refused");
            return;
        }
        if (found.isEmpty()) {
            boolean secondTry = find(ctx, file).isPresent();
            throw new AssertionError(actor + " with " + l + " access can't see " + file + " (a second direct try "
                    + (secondTry ? "finds it" : "doesn't") + "); " + reach(ctx, file), lastFindError);
        }
        byte[] got = read(ctx, found.get());
        if (! Arrays.equals(got, files.get(file)))
            throw new AssertionError(actor + " read the wrong contents of " + file + ": " + describe(Optional.of(got))
                    + " instead of " + describe(Optional.of(files.get(file))));
        count("read/allowed");
    }

    /** How far down a path a session can get, and what it sees at the last step */
    private String reach(UserContext ctx, Path p) {
        for (int i = 1; i <= p.getNameCount(); i++) {
            Path prefix = p.subpath(0, i);
            Optional<FileWrapper> at = find(ctx, prefix);
            if (at.isEmpty()) {
                Path parent = prefix.getParent();
                String seen = parent == null ? "" : find(ctx, parent)
                        .map(d -> " which holds " + childNames(ctx, d)).orElse("");
                return "it can't see " + prefix + seen;
            }
        }
        return "every step resolves";
    }

    private Set<String> expectedChildren(Path folder) {
        Set<String> names = new HashSet<>();
        for (Path p : files.keySet())
            if (p.getParent().equals(folder))
                names.add(p.getFileName().toString());
        for (Folder f : folders)
            if (f.path.getParent().equals(folder))
                names.add(f.path.getFileName().toString());
        return names;
    }

    private void actorList() {
        String actor = nonOwner();
        Folder f = pick(folders);
        Level l = level(actor, f.path);
        record(actor + " list " + f.path + " [" + l + "]");
        UserContext ctx = sessions.get(actor);
        Optional<FileWrapper> found = find(ctx, f.path);
        if (l == Level.NONE) {
            if (found.isEmpty()) {
                count("list/refused");
                return;
            }
            // a sharee is shown the folders leading down to what's shared with them, and nothing else
            Set<String> names = childNames(ctx, found.get());
            Set<String> leading = leadingTo(actor, f.path);
            if (! leading.containsAll(names))
                throw new AssertionError("ACCESS VIOLATION: " + actor + " with no access to " + f.path + " sees "
                        + names + " there, but only " + leading + " lead to anything shared with them");
            count("list/path-to-share");
            return;
        }
        if (found.isEmpty())
            throw new AssertionError(actor + " with " + l + " access can't see " + f.path + "; " + reach(ctx, f.path));
        Set<String> names = childNames(ctx, found.get());
        if (! names.equals(expectedChildren(f.path)))
            throw new AssertionError(actor + " lists " + f.path + " as " + names + ", expected " + expectedChildren(f.path));
        count("list/allowed");
    }

    /** The children of a folder that lead to a folder the user has access to */
    private Set<String> leadingTo(String username, Path folder) {
        Set<String> res = new HashSet<>();
        for (Folder f : folders) {
            if (! isUnder(f.path, folder) || level(username, f.path) == Level.NONE)
                continue;
            res.add(f.path.getName(folder.getNameCount()).toString());
        }
        return res;
    }

    private void actorUpload() {
        String actor = nonOwner();
        Folder f = pick(folders);
        Level l = level(actor, f.path);
        Path file = f.path.resolve("u" + counter++);
        byte[] data = randomContents();
        record(actor + " upload " + data.length + " to " + file + " [" + l + "]");
        if (l != Level.WRITE) {
            forbidden(actor + " with " + l + " access uploading " + file, () -> upload(actor, f.path, file.getFileName().toString(), data));
            if (find(auditor(), file).isPresent())
                throw new AssertionError("ACCESS VIOLATION: " + file + " exists after a refused upload by " + actor);
            return;
        }
        boolean ok = attempt(actor, () -> upload(actor, f.path, file.getFileName().toString(), data));
        resolveWrite(file, Optional.empty(), Optional.of(data), ok);
        count(ok ? "upload/allowed" : "upload/faulted");
        if (! ok)
            audit();
    }

    private void actorOverwrite() {
        if (files.isEmpty())
            return;
        String actor = nonOwner();
        Path file = pick(new ArrayList<>(files.keySet()));
        Level l = level(actor, file.getParent());
        byte[] old = files.get(file), data = randomContents();
        record(actor + " overwrite " + file + " with " + data.length + " [" + l + "]");
        if (l != Level.WRITE) {
            forbidden(actor + " with " + l + " access overwriting " + file, () -> upload(actor, file.getParent(), file.getFileName().toString(), data));
            resolveWrite(file, Optional.of(old), Optional.of(old), false);
            return;
        }
        boolean ok = attempt(actor, () -> upload(actor, file.getParent(), file.getFileName().toString(), data));
        resolveWrite(file, Optional.of(old), Optional.of(data), ok);
        count(ok ? "overwrite/allowed" : "overwrite/faulted");
        if (! ok)
            audit();
    }

    private void actorDelete() {
        if (files.isEmpty())
            return;
        String actor = nonOwner();
        Path file = pick(new ArrayList<>(files.keySet()));
        Level l = level(actor, file.getParent());
        byte[] old = files.get(file);
        record(actor + " delete " + file + " [" + l + "]");
        if (l != Level.WRITE) {
            forbidden(actor + " with " + l + " access deleting " + file, () -> delete(actor, file));
            resolveWrite(file, Optional.of(old), Optional.of(old), false);
            return;
        }
        boolean ok = attempt(actor, () -> delete(actor, file));
        resolveWrite(file, Optional.of(old), Optional.empty(), ok);
        count(ok ? "delete/allowed" : "delete/faulted");
        if (! ok)
            audit();
    }

    private void actorRename() {
        if (files.isEmpty())
            return;
        String actor = nonOwner();
        Path file = pick(new ArrayList<>(files.keySet()));
        Level l = level(actor, file.getParent());
        Path to = file.getParent().resolve("r" + counter++);
        byte[] contents = files.get(file);
        record(actor + " rename " + file + " -> " + to + " [" + l + "]");
        if (l != Level.WRITE) {
            forbidden(actor + " with " + l + " access renaming " + file, () -> rename(actor, file, to.getFileName().toString()));
            resolveRename(file, to, contents, false);
            return;
        }
        boolean ok = attempt(actor, () -> rename(actor, file, to.getFileName().toString()));
        resolveRename(file, to, contents, ok);
        count(ok ? "rename/allowed" : "rename/faulted");
        if (! ok)
            audit();
    }

    // ---- sessions kept from before a revocation ----

    private void staleAttack() {
        if (stale.isEmpty())
            return;
        Stale s = pick(stale);
        Level now = level(s.username, s.folder);
        int kind = rnd.nextInt(3);
        if (kind == 0 && ! s.fileHandles.isEmpty()) {
            Path file = pick(new ArrayList<>(s.fileHandles.keySet()));
            record("stale " + s.username + " (lost " + s.lostLevel + ", now " + now + ") read " + file);
            byte[] got;
            try {
                got = read(s.context, s.fileHandles.get(file));
            } catch (Exception e) {
                count("stale-read/refused");
                return;
            }
            byte[] then = s.contentsAtRevocation.get(file);
            if (! Arrays.equals(got, then) && now == Level.NONE)
                throw new AssertionError("ACCESS VIOLATION: stale session of " + s.username + " read " + file
                        + " as " + describe(Optional.of(got)) + ", which it never had access to");
            count("stale-read/old-contents");
        } else if (kind == 1) {
            record("stale " + s.username + " (lost " + s.lostLevel + ", now " + now + ") list " + s.folder);
            Set<String> names;
            try {
                names = childNames(s.context, s.folderHandle);
            } catch (Exception e) {
                count("stale-list/refused");
                return;
            }
            Set<String> learned = new HashSet<>(names);
            learned.removeAll(s.namesAtRevocation);
            if (! learned.isEmpty() && now == Level.NONE)
                throw new AssertionError("ACCESS VIOLATION: stale session of " + s.username + " sees " + learned
                        + " added to " + s.folder + " after its access was revoked");
            count("stale-list/nothing-new");
        } else {
            if (s.lostLevel != Level.WRITE || now == Level.WRITE)
                return;
            String name = "stale" + counter++;
            Path file = s.folder.resolve(name);
            byte[] data = randomContents();
            record("stale " + s.username + " (lost WRITE, now " + now + ") upload to " + file);
            try {
                await(s.folderHandle.uploadOrReplaceFile(name, AsyncReader.build(data), data.length,
                        s.context.network, crypto, () -> false, x -> {}));
            } catch (Exception e) {
                count("stale-write/refused");
                if (find(auditor(), file).isPresent())
                    throw new AssertionError("ACCESS VIOLATION: a refused stale write still created " + file);
                return;
            }
            if (find(auditor(), file).isPresent())
                throw new AssertionError("ACCESS VIOLATION: stale session of " + s.username + " wrote " + file
                        + " after its write access was revoked");
            throw new AssertionError("ACCESS VIOLATION: the server accepted a write from " + s.username
                    + "'s revoked writing key, into space the owner can no longer reach");
        }
    }

    // ---- audit ----

    /** Everything the owner should have is there, with exactly the contents it should have, and nothing else */
    private void audit() {
        record("audit");
        UserContext a = auditor();
        for (Map.Entry<Path, byte[]> e : files.entrySet()) {
            Optional<FileWrapper> found = find(a, e.getKey());
            if (found.isEmpty())
                throw new AssertionError("DATA LOSS: " + e.getKey() + " is missing");
            byte[] got = read(a, found.get());
            if (! Arrays.equals(got, e.getValue()))
                throw new AssertionError("DATA LOSS: " + e.getKey() + " holds " + describe(Optional.of(got))
                        + " instead of " + describe(Optional.of(e.getValue())));
        }
        for (Folder f : folders) {
            Optional<FileWrapper> found = find(a, f.path);
            if (found.isEmpty())
                throw new AssertionError("DATA LOSS: folder " + f.path + " is missing");
            Set<String> names = childNames(a, found.get());
            if (! names.equals(expectedChildren(f.path)))
                throw new AssertionError(f.path + " holds " + names + ", expected " + expectedChildren(f.path));
        }
        count("audit/ok");
    }
}
