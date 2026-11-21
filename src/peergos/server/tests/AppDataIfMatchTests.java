package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;

import static peergos.server.tests.PeergosNetworkUtils.ensureSignedUp;
import static peergos.server.tests.PeergosNetworkUtils.generateUsername;

/** An app's data write with If-Match: written only from the version the app read. */
public class AppDataIfMatchTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Args args = UserTests.buildArgs().with("useIPFS", "false");
    private static final String PASSWORD = "password";
    private static final Path FILE = PathUtil.get("vault.json");
    private static UserService service;
    private static NetworkAccess network;
    private static final Random random = new Random(1789);

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

    private static Path full(UserContext context) {
        return App.getDataDir("passwords", context.username).resolve(FILE);
    }

    /** The ETag a read gives for the file as this context sees it, or null when it has no tree hash. */
    private static String tag(UserContext context) {
        Optional<HashBranch> hash = context.getByPath(full(context)).join().get().getFileProperties().treeHash;
        return hash.map(h -> "\"" + h + "\"").orElse(null);
    }

    private static String read(UserContext context) {
        return new String(App.init(context, "passwords").join().readInternal(FILE, null).join());
    }

    private static byte[] text(String s) {
        return s.getBytes();
    }

    @Test
    public void writesFromTheVersionRead() {
        UserContext context = newUser();
        App app = App.init(context, "passwords").join();
        app.writeInternal(FILE, text("first"), null).join();
        String first = tag(context);
        Assert.assertNotNull("a written file has an ETag", first);

        Assert.assertTrue(app.writeInternalIfMatch(FILE, text("second"), first, null).join());
        Assert.assertEquals("second", read(context));
        String second = tag(context);
        Assert.assertNotNull(second);
        Assert.assertNotEquals("a write gives a new ETag", first, second);
    }

    @Test
    public void refusesAStaleTag() {
        UserContext context = newUser();
        App app = App.init(context, "passwords").join();
        app.writeInternal(FILE, text("first"), null).join();
        String first = tag(context);
        Assert.assertTrue(app.writeInternalIfMatch(FILE, text("second"), first, null).join());

        Assert.assertFalse(app.writeInternalIfMatch(FILE, text("third"), first, null).join());
        Assert.assertEquals("second", read(context));
    }

    @Test
    public void shorterAndLongerContentsChangeTheTag() {
        UserContext context = newUser();
        App app = App.init(context, "passwords").join();
        app.writeInternal(FILE, text("a much longer first version of the file"), null).join();
        String longer = tag(context);
        Assert.assertTrue(app.writeInternalIfMatch(FILE, text("short"), longer, null).join());
        String shorter = tag(context);
        Assert.assertNotNull("a shortened file still has an ETag", shorter);
        Assert.assertNotEquals(longer, shorter);
        Assert.assertEquals("short", read(context));
        Assert.assertFalse("the longer version's tag no longer matches",
                app.writeInternalIfMatch(FILE, text("again"), longer, null).join());
    }

    @Test
    public void filesOfSeveralChunks() {
        UserContext context = newUser();
        App app = App.init(context, "passwords").join();
        byte[] big = new byte[6 * 1024 * 1024];
        random.nextBytes(big);
        app.writeInternal(FILE, big, null).join();
        String first = tag(context);
        Assert.assertNotNull(first);
        byte[] bigger = new byte[7 * 1024 * 1024];
        random.nextBytes(bigger);
        Assert.assertTrue(app.writeInternalIfMatch(FILE, bigger, first, null).join());
        String second = tag(context);
        Assert.assertNotNull(second);
        Assert.assertNotEquals(first, second);
        Assert.assertArrayEquals(bigger, app.readInternal(FILE, null).join());
        Assert.assertFalse(app.writeInternalIfMatch(FILE, big, first, null).join());
    }

    @Test
    public void anyVersionAndMissingFiles() {
        UserContext context = newUser();
        App app = App.init(context, "passwords").join();
        Assert.assertFalse("* needs the file to be there", app.writeInternalIfMatch(FILE, text("x"), "*", null).join());
        Assert.assertFalse(app.writeInternalIfMatch(FILE, text("x"), "\"abc\"", null).join());
        app.writeInternal(FILE, text("first"), null).join();
        Assert.assertTrue(app.writeInternalIfMatch(FILE, text("second"), "*", null).join());
        Assert.assertEquals("second", read(context));
    }

    @Test
    public void tagLists() {
        UserContext context = newUser();
        App app = App.init(context, "passwords").join();
        app.writeInternal(FILE, text("first"), null).join();
        String first = tag(context);
        Assert.assertFalse("a weak tag never matches",
                app.writeInternalIfMatch(FILE, text("x"), "W/" + first, null).join());
        Assert.assertTrue(app.writeInternalIfMatch(FILE, text("second"), "\"other\", " + first, null).join());
    }

    @Test
    public void anotherDevicesWriteMeanwhile() {
        UserContext a = newUser();
        App onA = App.init(a, "passwords").join();
        onA.writeInternal(FILE, text("first"), null).join();
        UserContext b = signInAgain(a);
        App onB = App.init(b, "passwords").join();
        String read = tag(a);
        Assert.assertEquals(read, tag(b));

        // the other device writes after this one read
        onB.writeInternal(FILE, text("from b"), null).join();
        Assert.assertFalse(onA.writeInternalIfMatch(FILE, text("from a"), read, null).join());
        Assert.assertEquals("from b", read(signInAgain(a)));
    }

    @Test
    public void twoDevicesFromTheSameVersion() {
        UserContext a = newUser();
        App onA = App.init(a, "passwords").join();
        onA.writeInternal(FILE, text("first"), null).join();
        UserContext b = signInAgain(a);
        App onB = App.init(b, "passwords").join();
        String read = tag(a);

        Assert.assertTrue(onB.writeInternalIfMatch(FILE, text("from b"), read, null).join());
        Assert.assertFalse("only one of two writes from the same version lands",
                onA.writeInternalIfMatch(FILE, text("from a"), read, null).join());
        Assert.assertEquals("from b", read(signInAgain(a)));
    }
}
