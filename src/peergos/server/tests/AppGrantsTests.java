package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.user.*;
import peergos.shared.user.app.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static peergos.server.tests.PeergosNetworkUtils.ensureSignedUp;
import static peergos.server.tests.PeergosNetworkUtils.generateUsername;

public class AppGrantsTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Args args = UserTests.buildArgs().with("useIPFS", "false");
    private static final String PASSWORD = "password";
    private static UserService service;
    private static NetworkAccess network;
    private static final Random random = new Random(53);

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
        network = NetworkAccess.buildBuffered(service.storage, service.bats, service.coreNode, service.account,
                service.mutable, 0, service.social, service.controller, service.usage, service.serverMessages,
                crypto.hasher, Arrays.asList("peergos"), false);
    }

    private static UserContext newUser() {
        return ensureSignedUp(generateUsername(random), PASSWORD, network, crypto);
    }

    private static UserContext signInAgain(UserContext context) {
        return UserContext.signIn(context.username, PASSWORD, UserTests::noMfa, network, crypto).join();
    }

    private static FileWrapper mkdirs(UserContext context, String... names) {
        return context.getUserRoot().join()
                .getOrMkdirs(PathUtil.get(String.join("/", names)), network, false, context.mirrorBatId(), crypto).join();
    }

    private static FileWrapper get(UserContext context, Path path) {
        return context.getByPath(path).join().get();
    }

    private static byte[] read(UserContext context, Path path) {
        FileWrapper f = get(context, path);
        long len = f.getSize();
        return Serialize.readFully(f.getInputStream(network, crypto, len, l -> {}).join(), len).join();
    }

    private static Throwable failure(Supplier<CompletableFuture<?>> op) {
        try {
            op.get().join();
        } catch (CompletionException e) {
            return e.getCause() != null ? e.getCause() : e;
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("Expected failure");
    }

    @Test
    public void cborRoundTrip() {
        UserContext context = newUser();
        FileWrapper dir = mkdirs(context, "docs");
        FolderGrant grant = new FolderGrant("bid", new CapabilityWithPath("/" + context.username + "/docs",
                dir.writableFilePointer()), true, 12345L);
        AppGrants grants = AppGrants.empty().add(grant);
        AppGrants parsed = AppGrants.fromCbor(CborObject.fromByteArray(grants.serialize()));
        Assert.assertEquals(grants, parsed);
        Assert.assertTrue(parsed.grants.get(0).target.cap.isWritable());
    }

    @Test
    public void persistentWriteGrantSurvivesNewSession() {
        UserContext context = newUser();
        String username = context.username;
        FileWrapper notes = mkdirs(context, "Documents", "notes");
        App app = App.init(context, "notesapp").join();
        GrantInfo info = app.addGrant(notes, "/" + username + "/Documents/notes", true, true).join();
        Assert.assertTrue(info.write && info.persist && ! info.stale);

        UserContext again = signInAgain(context);
        App fresh = App.init(again, "notesapp").join();
        List<GrantInfo> grants = fresh.listGrants().join();
        Assert.assertEquals(1, grants.size());
        Assert.assertEquals(info.grantId, grants.get(0).grantId);
        Assert.assertEquals("/" + username + "/Documents/notes", grants.get(0).path);

        byte[] data = "todo".getBytes();
        Assert.assertTrue(fresh.writeGranted(info.grantId, "sub/todo.md", data).join());
        Assert.assertArrayEquals(data, read(again, PathUtil.get(username, "Documents", "notes", "sub", "todo.md")));
        Assert.assertArrayEquals(data, fresh.readGranted(info.grantId, "sub/todo.md").join());
        Assert.assertEquals(Collections.singletonList("sub"), fresh.dirGranted(info.grantId, "").join());
        Assert.assertEquals(1, (int) fresh.existsGranted(info.grantId, "sub").join());
        Assert.assertEquals(0, (int) fresh.existsGranted(info.grantId, "sub/todo.md").join());
        Assert.assertEquals(-1, (int) fresh.existsGranted(info.grantId, "missing").join());

        byte[] more = " more".getBytes();
        Assert.assertTrue(fresh.appendGranted(info.grantId, "sub/todo.md", more).join());
        Assert.assertArrayEquals("todo more".getBytes(), fresh.readGranted(info.grantId, "sub/todo.md").join());

        Assert.assertTrue(fresh.mkdirGranted(info.grantId, "a/b").join());
        Assert.assertEquals(1, (int) fresh.existsGranted(info.grantId, "a/b").join());

        Assert.assertTrue(fresh.deleteGranted(info.grantId, "sub/todo.md").join());
        Assert.assertEquals(-1, (int) fresh.existsGranted(info.grantId, "sub/todo.md").join());
    }

    @Test
    public void sessionGrantIsNotPersisted() {
        UserContext context = newUser();
        FileWrapper photos = mkdirs(context, "Photos");
        App app = App.init(context, "album").join();
        GrantInfo info = app.addGrant(photos, "/" + context.username + "/Photos", false, false).join();
        Assert.assertFalse(info.persist);
        Assert.assertEquals(1, app.listGrants().join().size());

        App fresh = App.init(signInAgain(context), "album").join();
        Assert.assertEquals(0, fresh.listGrants().join().size());
    }

    @Test
    public void grantingTheSameFolderAgainKeepsItsId() {
        UserContext context = newUser();
        FileWrapper photos = mkdirs(context, "Photos");
        App app = App.init(context, "album").join();
        GrantInfo first = app.addGrant(photos, "/" + context.username + "/Photos", false, true).join();
        GrantInfo second = app.addGrant(get(context, PathUtil.get(context.username, "Photos")),
                "/" + context.username + "/Photos", true, true).join();
        Assert.assertEquals(first.grantId, second.grantId);
        List<GrantInfo> grants = app.listGrants().join();
        Assert.assertEquals(1, grants.size());
        Assert.assertTrue(grants.get(0).write);
    }

    @Test
    public void largeFileReadThroughGrant() {
        UserContext context = newUser();
        String username = context.username;
        FileWrapper music = mkdirs(context, "Music");
        byte[] data = new byte[6 * 1024 * 1024 + 17];
        random.nextBytes(data);
        music.uploadOrReplaceFile("song.mp3", AsyncReader.build(data), data.length, network, crypto, () -> false, x -> {}).join();
        App app = App.init(context, "player").join();
        GrantInfo info = app.addGrant(get(context, PathUtil.get(username, "Music")), "/" + username + "/Music", false, true).join();

        FileWrapper song = app.getGranted(info.grantId, "song.mp3").join().get();
        Assert.assertEquals(data.length, song.getSize());
        long offset = 5 * 1024 * 1024 + 3;
        AsyncReader reader = song.getInputStream(network, crypto, song.getSize(), l -> {}).join().seek(offset).join();
        byte[] tail = new byte[(int) (data.length - offset)];
        reader.readIntoArray(tail, 0, tail.length).join();
        Assert.assertArrayEquals(Arrays.copyOfRange(data, (int) offset, data.length), tail);
        Assert.assertArrayEquals(data, app.readGranted(info.grantId, "song.mp3").join());
    }

    @Test
    public void grantSurvivesRenameAndMove() {
        UserContext context = newUser();
        String username = context.username;
        mkdirs(context, "Documents", "notes");
        mkdirs(context, "Archive");
        App app = App.init(context, "notesapp").join();
        GrantInfo info = app.addGrant(get(context, PathUtil.get(username, "Documents", "notes")),
                "/" + username + "/Documents/notes", true, true).join();
        app.writeGranted(info.grantId, "a.md", "a".getBytes()).join();

        FileWrapper documents = get(context, PathUtil.get(username, "Documents"));
        get(context, PathUtil.get(username, "Documents", "notes"))
                .rename("journal", documents, PathUtil.get(username, "Documents", "notes"), context).join();
        Assert.assertArrayEquals("a".getBytes(), app.readGranted(info.grantId, "a.md").join());
        Assert.assertEquals("/" + username + "/Documents/journal", app.listGrants().join().get(0).path);

        FileWrapper journal = get(context, PathUtil.get(username, "Documents", "journal"));
        Assert.assertTrue(journal.moveTo(get(context, PathUtil.get(username, "Archive")), get(context, PathUtil.get(username, "Documents")),
                PathUtil.get(username, "Documents", "journal"), context, () -> Futures.of(true)).join());
        Assert.assertTrue(app.writeGranted(info.grantId, "b.md", "b".getBytes()).join());
        Assert.assertArrayEquals("b".getBytes(), read(context, PathUtil.get(username, "Archive", "journal", "b.md")));
        Assert.assertEquals("/" + username + "/Archive/journal", app.listGrants().join().get(0).path);

        UserContext again = signInAgain(context);
        Assert.assertEquals("/" + username + "/Archive/journal", App.init(again, "notesapp").join().listGrants().join().get(0).path);
    }

    @Test
    public void grantGoesStaleAfterUnshareAndCanBeRebound() {
        UserContext context = newUser();
        String username = context.username;
        mkdirs(context, "Documents", "notes");
        App app = App.init(context, "notesapp").join();
        GrantInfo info = app.addGrant(get(context, PathUtil.get(username, "Documents", "notes")),
                "/" + username + "/Documents/notes", true, true).join();
        app.writeGranted(info.grantId, "a.md", "a".getBytes()).join();

        context.unShareReadAccessWith(PathUtil.get(username, "Documents", "notes"), Collections.emptySet()).join();
        assertStaleThenRebind(context, app, info, PathUtil.get(username, "Documents", "notes"));

        context.unShareReadAccessWith(PathUtil.get(username, "Documents"), Collections.emptySet()).join();
        assertStaleThenRebind(context, app, info, PathUtil.get(username, "Documents", "notes"));
    }

    private static void assertStaleThenRebind(UserContext context, App app, GrantInfo info, Path folder) {
        Assert.assertTrue(app.getGranted(info.grantId, "a.md").join().isEmpty());
        Assert.assertTrue(app.isGrantStale(info.grantId));
        Assert.assertTrue(app.listGrants().join().get(0).stale);
        Assert.assertNotNull(failure(() -> app.writeGranted(info.grantId, "b.md", "b".getBytes())));

        Assert.assertTrue(app.rebindGrant(info.grantId, get(context, folder), "/" + folder).join());
        Assert.assertFalse(app.isGrantStale(info.grantId));
        Assert.assertArrayEquals("a".getBytes(), app.readGranted(info.grantId, "a.md").join());
        Assert.assertTrue(app.writeGranted(info.grantId, "b.md", "b".getBytes()).join());
        List<GrantInfo> grants = App.init(signInAgain(context), "notesapp").join().listGrants().join();
        Assert.assertEquals(info.grantId, grants.get(0).grantId);
        Assert.assertFalse(grants.get(0).stale);
    }

    @Test
    public void writesInsideNestedWritingSpaces() {
        UserContext context = newUser();
        String username = context.username;
        mkdirs(context, "shared", "inner");
        // gives "shared" its own writing space, reached from home through a link node
        context.shareWriteAccessWith(PathUtil.get(username, "shared"), new HashSet<>()).join();

        App app = App.init(context, "notesapp").join();
        GrantInfo outer = app.addGrant(context.getUserRoot().join().getOrMkdirs(PathUtil.get("top"), network, false, context.mirrorBatId(), crypto).join(),
                "/" + username + "/top", true, true).join();
        Assert.assertTrue(app.writeGranted(outer.grantId, "x.md", "x".getBytes()).join());

        GrantInfo inner = app.addGrant(get(context, PathUtil.get(username, "shared", "inner")),
                "/" + username + "/shared/inner", true, true).join();
        Assert.assertTrue(app.writeGranted(inner.grantId, "y.md", "y".getBytes()).join());
        Assert.assertArrayEquals("y".getBytes(), read(context, PathUtil.get(username, "shared", "inner", "y.md")));

        mkdirs(context, "top", "sub");
        context.shareWriteAccessWith(PathUtil.get(username, "top", "sub"), new HashSet<>()).join();
        Assert.assertTrue(app.writeGranted(outer.grantId, "sub/z.md", "z".getBytes()).join());
        Assert.assertArrayEquals("z".getBytes(), app.readGranted(outer.grantId, "sub/z.md").join());
    }

    @Test
    public void rejections() {
        UserContext context = newUser();
        String username = context.username;
        mkdirs(context, "Photos", "2026");
        mkdirs(context, "Photos", ".hidden");
        App app = App.init(context, "album").join();
        GrantInfo ro = app.addGrant(get(context, PathUtil.get(username, "Photos")), "/" + username + "/Photos", false, true).join();

        Assert.assertNotNull(failure(() -> app.getGranted("bnotagrant", "")));
        Assert.assertNotNull(failure(() -> app.getGranted(ro.grantId, "../x")));
        Assert.assertNotNull(failure(() -> app.getGranted(ro.grantId, "2026/../../x")));
        Assert.assertNotNull(failure(() -> app.getGranted(ro.grantId, ".hidden")));
        Assert.assertNotNull(failure(() -> app.getGranted(ro.grantId, "/" + username)));
        Assert.assertNotNull(failure(() -> app.writeGranted(ro.grantId, "a.md", new byte[1])));
        Assert.assertNotNull(failure(() -> app.mkdirGranted(ro.grantId, "new")));
        Assert.assertNotNull(failure(() -> app.deleteGranted(ro.grantId, "2026")));
        Assert.assertNotNull(failure(() -> app.appendGranted(ro.grantId, "a.md", new byte[1])));
        Assert.assertEquals(-1, (int) app.existsGranted(ro.grantId, "new").join());

        Assert.assertNotNull(failure(() -> app.readInternal(PathUtil.get("../" + App.GRANTS_FILENAME), null)));
        Assert.assertNotNull(failure(() -> app.writeInternal(PathUtil.get("../" + App.GRANTS_FILENAME), new byte[1], null)));
        Assert.assertNotNull(failure(() -> app.deleteInternal(PathUtil.get("../" + App.GRANTS_FILENAME), null)));
        Assert.assertEquals(1, app.listGrants().join().size());

        Assert.assertNotNull(failure(() -> app.addGrant(context.getUserRoot().join(), "/" + username, true, true)));
        Assert.assertNotNull(failure(() -> app.addGrant(get(context, PathUtil.get(username, "Photos", ".hidden")),
                "/" + username + "/Photos/.hidden", true, true)));
    }

    @Test
    public void revokedGrantStopsWorking() {
        UserContext context = newUser();
        String username = context.username;
        mkdirs(context, "Documents");
        App app = App.init(context, "notesapp").join();
        GrantInfo info = app.addGrant(get(context, PathUtil.get(username, "Documents")), "/" + username + "/Documents", true, true).join();
        Assert.assertTrue(app.writeGranted(info.grantId, "a.md", "a".getBytes()).join());
        Assert.assertTrue(app.revokeGrant(info.grantId).join());
        Assert.assertNotNull(failure(() -> app.writeGranted(info.grantId, "b.md", "b".getBytes())));
        Assert.assertEquals(0, app.listGrants().join().size());
        Assert.assertEquals(0, App.init(signInAgain(context), "notesapp").join().listGrants().join().size());
    }
}
