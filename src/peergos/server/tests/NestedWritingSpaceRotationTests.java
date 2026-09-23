package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static peergos.server.tests.PeergosNetworkUtils.ensureSignedUp;
import static peergos.server.tests.PeergosNetworkUtils.generateUsername;

/** Revoking access rotates a subtree onto fresh keys and then deletes the old copy.
 *
 *  A writing space nested inside that subtree is the awkward case. Revocation rotates the symmetric
 *  keys but not the signers - CryptreeNode.generateNewChildCap only mints a new signing key when
 *  rotateSigner is set, and unsharing read access passes false - so the nested space keeps the
 *  writer it already had and the rotated tree still points into it. Deleting the old subtree must
 *  therefore delete the old chunks without touching that writer's pointer, because the new tree is
 *  now the thing depending on it.
 */
public class NestedWritingSpaceRotationTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Args args = UserTests.buildArgs().with("useIPFS", "false");
    private static UserService service;
    private static NetworkAccess network;
    private static final Random random = new Random(37);

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
        network = NetworkAccess.buildBuffered(service.storage, service.bats, service.coreNode, service.account,
                service.mutable, 0, service.social, service.controller, service.usage, service.serverMessages,
                crypto.hasher, Arrays.asList("peergos"), false);
    }

    /** Unshare read access on a dir with a nested writing space below it. The nested space keeps its
     *  signing key across the rotation, so its pointer has to survive the delete of the old subtree.
     */
    @Test
    public void revokingReadAccessKeepsANestedWritingSpaceAlive() {
        UserContext context = ensureSignedUp(generateUsername(random), "password", network, crypto);
        String username = context.username;
        Path a = PathUtil.get(username, "a");
        Path b = a.resolve("b");
        Path file = b.resolve("file.txt");

        mkdir(context, PathUtil.get(username), "a");
        mkdir(context, a, "b");
        // puts b in its own writing space, nested inside a's
        context.shareWriteAccessWith(b, new HashSet<>()).join();
        byte[] data = "Some text".getBytes();
        uploadFile(context, b, "file.txt", data);

        PublicKeyHash owner = context.signer.publicKeyHash;
        PublicKeyHash nested = context.getByPath(b).join().get().writer();
        Assert.assertNotEquals("b should have its own writing space", owner, nested);
        Assert.assertTrue("b's pointer should exist before the rotation", pointerIsSet(context, nested));

        Optional<Throwable> failure = revokeReadAccess(context, a);

        Assert.assertTrue("deleting the old subtree emptied the nested writing space's pointer",
                pointerIsSet(context, nested));
        Assert.assertFalse("revoking read access did not complete: " + describe(failure), failure.isPresent());

        // the rotation does not mint a new signer for a nested space, so b is still the same writer
        PublicKeyHash afterRotation = context.getByPath(b).join().get().writer();
        Assert.assertEquals("a nested writing space keeps its signing key across a rotation", nested, afterRotation);

        // and nothing below it was lost
        Assert.assertTrue(context.getByPath(b).join().isPresent());
        Assert.assertArrayEquals(data, read(context, file, data.length));
    }

    /** The same shape one level deeper, to pin that it is the nesting and not the immediate child
     *  that matters: a's subtree is rotated, and the space hanging off b must survive it.
     */
    @Test
    public void revokingReadAccessKeepsADeeplyNestedWritingSpaceAlive() {
        UserContext context = ensureSignedUp(generateUsername(random), "password", network, crypto);
        String username = context.username;
        Path a = PathUtil.get(username, "a");
        Path b = a.resolve("b");
        Path c = b.resolve("c");
        Path file = c.resolve("file.txt");

        mkdir(context, PathUtil.get(username), "a");
        mkdir(context, a, "b");
        mkdir(context, b, "c");
        context.shareWriteAccessWith(c, new HashSet<>()).join();
        byte[] data = "Some text".getBytes();
        uploadFile(context, c, "file.txt", data);

        PublicKeyHash nested = context.getByPath(c).join().get().writer();
        Assert.assertTrue(pointerIsSet(context, nested));

        Optional<Throwable> failure = revokeReadAccess(context, a);

        Assert.assertTrue("deleting the old subtree emptied the nested writing space's pointer",
                pointerIsSet(context, nested));
        Assert.assertFalse("revoking read access did not complete: " + describe(failure), failure.isPresent());
        Assert.assertTrue(context.getByPath(c).join().isPresent());
        Assert.assertArrayEquals(data, read(context, file, data.length));
    }

    /** The control: the same revocation over a subtree that holds no nested writing space. This is
     *  the case that works, so a failure here means the harness is wrong rather than the rotation.
     */
    @Test
    public void revokingReadAccessOverASingleWritingSpaceIsFine() {
        UserContext context = ensureSignedUp(generateUsername(random), "password", network, crypto);
        String username = context.username;
        Path a = PathUtil.get(username, "a");
        Path b = a.resolve("b");
        Path file = b.resolve("file.txt");

        mkdir(context, PathUtil.get(username), "a");
        mkdir(context, a, "b");
        byte[] data = "Some text".getBytes();
        uploadFile(context, b, "file.txt", data);

        Optional<Throwable> failure = revokeReadAccess(context, a);

        Assert.assertFalse("revoking read access did not complete: " + describe(failure), failure.isPresent());
        Assert.assertTrue(context.getByPath(b).join().isPresent());
        Assert.assertArrayEquals(data, read(context, file, data.length));
    }

    /** Revoking is what is under test, so a failure of it has to be reported alongside the state it
     *  left rather than thrown from the middle of the test: the pointer says what actually broke.
     */
    private static Optional<Throwable> revokeReadAccess(UserContext context, Path path) {
        try {
            context.unShareReadAccessWith(path, Collections.emptySet()).join();
            return Optional.empty();
        } catch (CompletionException e) {
            return Optional.of(e.getCause() != null ? e.getCause() : e);
        }
    }

    private static String describe(Optional<Throwable> failure) {
        return failure.map(t -> t.getClass().getSimpleName() + ": " + t.getMessage()).orElse("");
    }

    /** A writing space is dead when its pointer has no target, which is what commitDeletion leaves
     *  and what turns every later read of it into "writer not present in snapshot!".
     */
    private static boolean pointerIsSet(UserContext context, PublicKeyHash writer) {
        return context.network.mutable
                .getPointerTarget(context.signer.publicKeyHash, writer, context.network.dhtClient)
                .join().updated.isPresent();
    }

    private static void mkdir(UserContext context, Path parent, String name) {
        FileWrapper dir = context.getByPath(parent).join().get();
        dir.mkdir(name, context.network, false, dir.mirrorBatId(), crypto).join();
    }

    private static void uploadFile(UserContext context, Path parent, String name, byte[] data) {
        context.getByPath(parent).join().get()
                .uploadOrReplaceFile(name, AsyncReader.build(data), data.length, context.network, crypto,
                        () -> false, x -> {}).join();
    }

    private static byte[] read(UserContext context, Path file, int length) {
        return Serialize.readFully(context.getByPath(file).join().get()
                .getInputStream(context.network, context.crypto, x -> {}).join(), length).join();
    }
}
