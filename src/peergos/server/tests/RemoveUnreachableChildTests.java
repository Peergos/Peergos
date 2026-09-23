package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;

import static peergos.server.tests.PeergosNetworkUtils.ensureSignedUp;
import static peergos.server.tests.PeergosNetworkUtils.generateUsername;

/** The repair for a drive that already has a writing space with no pointer, which no amount of
 *  fixing the code that emptied it can undo. The state is built here the way it arises: the
 *  pointer is set to empty while the parent still links to a capability naming that writer.
 */
public class RemoveUnreachableChildTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Args args = UserTests.buildArgs().with("useIPFS", "false");
    private static UserService service;
    private static NetworkAccess network;
    private static final Random random = new Random(91);

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
        network = NetworkAccess.buildBuffered(service.storage, service.bats, service.coreNode, service.account,
                service.mutable, 0, service.social, service.controller, service.usage, service.serverMessages,
                crypto.hasher, Arrays.asList("peergos"), false);
    }

    @Test
    public void detachesAChildWhoseWritingSpaceHasNoPointer() {
        UserContext context = ensureSignedUp(generateUsername(random), "password", network, crypto);
        String username = context.username;
        Path a = PathUtil.get(username, "a");
        Path b = a.resolve("b");

        mkdir(context, PathUtil.get(username), "a");
        mkdir(context, a, "b");
        mkdir(context, a, "sibling");
        context.shareWriteAccessWith(b, new HashSet<>()).join();
        uploadFile(context, b, "file.txt");

        PublicKeyHash dead = context.getByPath(b).join().get().writer();
        emptyPointer(context, context.getByPath(b).join().get().signingPair());

        // the parent is now unusable: listing it resolves the dead writer
        Assert.assertTrue(listingFails(context, a));

        Assert.assertTrue(RemoveUnreachableChild.removeUnreachableChild(context, a, "b", false));

        // the parent opens again, keeps its other children, and has dropped the dead owned key
        Assert.assertFalse(listingFails(context, a));
        Assert.assertTrue(context.getByPath(a.resolve("sibling")).join().isPresent());
        Assert.assertTrue(context.getByPath(b).join().isEmpty());
        Assert.assertFalse(isOwnedBy(context, context.getByPath(a).join().get().writer(), dead));

        // and the drive stays usable afterwards
        mkdir(context, a, "b");
        Assert.assertTrue(context.getByPath(b).join().isPresent());
    }

    @Test
    public void refusesToDetachAHealthyChild() {
        UserContext context = ensureSignedUp(generateUsername(random), "password", network, crypto);
        String username = context.username;
        Path a = PathUtil.get(username, "a");
        Path b = a.resolve("b");

        mkdir(context, PathUtil.get(username), "a");
        mkdir(context, a, "b");
        context.shareWriteAccessWith(b, new HashSet<>()).join();

        try {
            RemoveUnreachableChild.removeUnreachableChild(context, a, "b", false);
            Assert.fail("removed a child that was perfectly reachable");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("still has a pointer"));
        }
        Assert.assertTrue(context.getByPath(b).join().isPresent());
    }

    @Test
    public void dryRunChangesNothing() {
        UserContext context = ensureSignedUp(generateUsername(random), "password", network, crypto);
        String username = context.username;
        Path a = PathUtil.get(username, "a");
        Path b = a.resolve("b");

        mkdir(context, PathUtil.get(username), "a");
        mkdir(context, a, "b");
        context.shareWriteAccessWith(b, new HashSet<>()).join();
        emptyPointer(context, context.getByPath(b).join().get().signingPair());

        Assert.assertTrue(RemoveUnreachableChild.removeUnreachableChild(context, a, "b", true));
        Assert.assertTrue("a dry run repaired the drive", listingFails(context, a));
    }

    /** Set a writing space's pointer to empty, which is what an interrupted revocation leaves. */
    private static void emptyPointer(UserContext context, SigningPrivateKeyAndPublicHash signer) {
        PublicKeyHash owner = context.signer.publicKeyHash;
        NetworkAccess network = context.network;
        PointerUpdate current = network.mutable
                .getPointerTarget(owner, signer.publicKeyHash, network.dhtClient).join();
        PointerUpdate cas = new PointerUpdate(current.updated, MaybeMultihash.empty(),
                PointerUpdate.increment(current.sequence));
        byte[] signed = signer.secret.signMessage(cas.serialize()).join();
        Assert.assertTrue(network.mutable.setPointer(owner, signer.publicKeyHash, signed).join());
        network.synchronizer.clear();
    }

    private static boolean listingFails(UserContext context, Path dir) {
        try {
            context.getByPath(dir).join().get().getChildren(crypto.hasher, context.network).join();
            return false;
        } catch (Exception e) {
            return peergos.shared.util.Exceptions.getRootCause(e).getMessage().contains("not present in snapshot");
        }
    }

    private static boolean isOwnedBy(UserContext context, PublicKeyHash parent, PublicKeyHash child) {
        return UserContext.getWriterData(context.network, context.signer.publicKeyHash, parent).join()
                .props.get()
                .directOwnedKeys(context.signer.publicKeyHash, context.network.dhtClient, crypto.hasher).join()
                .contains(child);
    }

    private static void mkdir(UserContext context, Path parent, String name) {
        FileWrapper dir = context.getByPath(parent).join().get();
        dir.mkdir(name, context.network, false, dir.mirrorBatId(), crypto).join();
    }

    private static void uploadFile(UserContext context, Path parent, String name) {
        byte[] data = "Some text".getBytes();
        context.getByPath(parent).join().get()
                .uploadOrReplaceFile(name, AsyncReader.build(data), data.length, context.network, crypto,
                        () -> false, x -> {}).join();
    }
}
