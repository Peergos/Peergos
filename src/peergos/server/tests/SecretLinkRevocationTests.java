package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;

import static peergos.server.tests.PeergosNetworkUtils.*;

/** Revoking a secret link rotates the keys of what it held, so a capability taken from it beforehand stops working */
public class SecretLinkRevocationTests {

    private static Args args = UserTests.useMemoryDbs(UserTests.buildArgs())
            .with("enable-gc", "false");
    private static UserService service;
    private static final Crypto crypto = Main.initCrypto();
    private final Random random = new Random();
    private final NetworkAccess network;

    public SecretLinkRevocationTests() {
        this.network = NetworkAccess.buildBuffered(new CachingStorage(service.storage, 1_000, 50 * 1024),
                service.bats, service.coreNode, service.account, service.mutable, 0, service.social,
                service.controller, service.usage, service.serverMessages, crypto.hasher,
                Arrays.asList("peergos"), false);
    }

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
    }

    @Test
    public void removingAnItemRevokesIt() throws Exception {
        String username = generateUsername(random);
        UserContext context = ensureSignedUp(username, "test", network, crypto);
        context.getUserRoot().join().mkdir("keep", context.network, false, context.mirrorBatId(), crypto).join();
        context.getUserRoot().join().mkdir("drop", context.network, false, context.mirrorBatId(), crypto).join();

        LinkProperties link = context.createSecretLink(
                Arrays.asList(username + "/keep", username + "/drop"), Collections.emptyList(),
                Optional.empty(), Optional.empty(), "", false).join();
        String linkString = link.toLinkString(context.signer.publicKeyHash);

        UserContext early = UserContext.fromSecretLinkV2(linkString, () -> Futures.of(""), network.clear(), crypto).join();
        AbsoluteCapability captured = early.getByPath(username + "/drop").join().get().getPointer().capability;

        LinkProperties updated = context.setSecretLinkMembers(Collections.singletonList(username + "/keep"), Collections.emptyList(), link).join();

        UserContext later = UserContext.fromSecretLinkV2(linkString, () -> Futures.of(""), network.clear(), crypto).join();
        Assert.assertTrue(later.getByPath(username + "/keep").join().isPresent());
        Assert.assertTrue(later.getByPath(username + "/drop").join().isEmpty());
        Assert.assertTrue("a capability captured beforehand is revoked", network.clear().getFile(captured, username).join().isEmpty());
        Assert.assertTrue(context.getByPath(username + "/drop").join().isPresent());

        // the returned props are current, so the link can be edited again
        context.setSecretLinkMembers(Arrays.asList(username + "/keep", username + "/drop"), Collections.emptyList(), updated).join();
    }

    @Test
    public void revokingAWritableLinkRotatesItsKeys() throws Exception {
        String username = generateUsername(random);
        UserContext context = ensureSignedUp(username, "test", network, crypto);
        context.getUserRoot().join().mkdir("linked", context.network, false, context.mirrorBatId(), crypto).join();
        String path = username + "/linked";
        LinkProperties link = context.createSecretLink(path, true, Optional.empty(), Optional.empty(), "", false).join();
        String linkString = link.toLinkString(context.signer.publicKeyHash);

        UserContext guest = UserContext.fromSecretLinkV2(linkString, () -> Futures.of(""), network.clear(), crypto).join();
        FileWrapper dir = guest.getByPath(path).join().get();
        Assert.assertTrue(dir.isWritable());
        AbsoluteCapability captured = dir.getPointer().capability;
        Assert.assertTrue(uploadTo(guest, path, "before.txt"));

        context.deleteSecretLink(link.label, PathUtil.get(path), true).join();

        try {
            UserContext.fromSecretLinkV2(linkString, () -> Futures.of(""), network.clear(), crypto).join();
            Assert.fail("a deleted link still opens");
        } catch (Exception expected) {}
        Assert.assertTrue("the captured capability is revoked", network.clear().getFile(captured, username).join().isEmpty());
        Assert.assertFalse("the open session can't write any more", uploadTo(guest, path, "after.txt"));

        FileWrapper owned = context.getByPath(path).join().get();
        Assert.assertTrue(owned.getChild("before.txt", crypto.hasher, context.network).join().isPresent());
        Assert.assertTrue(uploadTo(context, path, "owner.txt"));
    }

    @Test
    public void revokingAReadOnlyLinkRotatesItsKeys() throws Exception {
        String username = generateUsername(random);
        UserContext context = ensureSignedUp(username, "test", network, crypto);
        context.getUserRoot().join().mkdir("linked", context.network, false, context.mirrorBatId(), crypto).join();
        String path = username + "/linked";
        context.getByPath(path).join().get().mkdir("published", context.network, false, context.mirrorBatId(), crypto).join();
        context.makePublic(context.getByPath(path + "/published").join().get()).join();

        LinkProperties revoked = context.createSecretLink(path, false, Optional.empty(), Optional.empty(), "", false).join();
        LinkProperties kept = context.createSecretLink(path, false, Optional.empty(), Optional.empty(), "", false).join();
        UserContext guest = UserContext.fromSecretLinkV2(revoked.toLinkString(context.signer.publicKeyHash),
                () -> Futures.of(""), network.clear(), crypto).join();
        AbsoluteCapability captured = guest.getByPath(path).join().get().getPointer().capability;

        context.deleteSecretLink(revoked.label, PathUtil.get(path), false).join();

        Assert.assertTrue("the captured capability is revoked", network.clear().getFile(captured, username).join().isEmpty());
        UserContext other = UserContext.fromSecretLinkV2(kept.toLinkString(context.signer.publicKeyHash),
                () -> Futures.of(""), network.clear(), crypto).join();
        Assert.assertTrue("another link to the same path is sent again", other.getByPath(path).join().isPresent());
        Assert.assertTrue("a published path below is published again",
                UserContext.getPublicFile(PathUtil.get(path + "/published"), network.clear(), crypto).join().isPresent());
    }

    @Test
    public void aPublicPathIsNotRotatedForAReadOnlyLink() throws Exception {
        String username = generateUsername(random);
        UserContext context = ensureSignedUp(username, "test", network, crypto);
        context.getUserRoot().join().mkdir("public", context.network, false, context.mirrorBatId(), crypto).join();
        String path = username + "/public";
        context.makePublic(context.getByPath(path).join().get()).join();
        AbsoluteCapability before = context.getByPath(path).join().get().getPointer().capability;

        LinkProperties link = context.createSecretLink(path, false, Optional.empty(), Optional.empty(), "", false).join();
        context.deleteSecretLink(link.label, PathUtil.get(path), false).join();

        Assert.assertEquals(before, context.getByPath(path).join().get().getPointer().capability);
        Assert.assertTrue(UserContext.getPublicFile(PathUtil.get(path), network.clear(), crypto).join().isPresent());
    }

    @Test
    public void changingAPasswordOrExpiringRotatesTheLinksKeys() throws Exception {
        String username = generateUsername(random);
        UserContext context = ensureSignedUp(username, "test", network, crypto);
        context.getUserRoot().join().mkdir("pw", context.network, false, context.mirrorBatId(), crypto).join();
        context.getUserRoot().join().mkdir("exp", context.network, false, context.mirrorBatId(), crypto).join();
        String pwPath = username + "/pw", expPath = username + "/exp";

        LinkProperties pw = context.createSecretLink(pwPath, false, Optional.empty(), Optional.empty(), "old", false).join();
        String pwLink = pw.toLinkString(context.signer.publicKeyHash);
        AbsoluteCapability pwCap = UserContext.fromSecretLinkV2(pwLink, () -> Futures.of("old"), network.clear(), crypto).join()
                .getByPath(pwPath).join().get().getPointer().capability;
        LinkProperties changed = context.setSecretLinkMembers(Collections.singletonList(pwPath), Collections.emptyList(),
                pw.with("new", "", Optional.empty(), false)).join();
        Assert.assertTrue(network.clear().getFile(pwCap, username).join().isEmpty());
        Assert.assertTrue(UserContext.fromSecretLinkV2(pwLink, () -> Futures.of("new"), network.clear(), crypto).join()
                .getByPath(pwPath).join().isPresent());

        // a second change after the rotation still has a current champ hash to replace
        LinkProperties again = context.setSecretLinkMembers(Collections.singletonList(pwPath), Collections.emptyList(),
                changed.with("newer", "", Optional.empty(), false)).join();
        Assert.assertTrue(UserContext.fromSecretLinkV2(pwLink, () -> Futures.of("newer"), network.clear(), crypto).join()
                .getByPath(pwPath).join().isPresent());

        LinkProperties exp = context.createSecretLink(expPath, false, Optional.of(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(2)),
                Optional.empty(), "", false).join();
        AbsoluteCapability expCap = UserContext.fromSecretLinkV2(exp.toLinkString(context.signer.publicKeyHash),
                        () -> Futures.of(""), network.clear(), crypto).join()
                .getByPath(expPath).join().get().getPointer().capability;
        Thread.sleep(3_000);
        Assert.assertEquals(1, (int) context.deleteExpiredSecretLinks().join());
        Assert.assertTrue(network.clear().getFile(expCap, username).join().isEmpty());
        Assert.assertTrue("only the expired link is deleted", context.getAllSecretLinks().join().stream()
                .anyMatch(l -> l.props.label == again.label));
    }

    @Test
    public void unsharingAPathWithSeveralLinksCompletes() throws Exception {
        String username = generateUsername(random);
        UserContext context = ensureSignedUp(username, "test", network, crypto);
        context.getUserRoot().join().mkdir("dir", context.network, false, context.mirrorBatId(), crypto).join();
        String dir = username + "/dir";
        context.getByPath(dir).join().get().mkdir("a", context.network, false, context.mirrorBatId(), crypto).join();
        context.getByPath(dir).join().get().mkdir("b", context.network, false, context.mirrorBatId(), crypto).join();
        LinkProperties both = context.createSecretLink(Arrays.asList(dir + "/a", dir + "/b"), Collections.emptyList(),
                Optional.empty(), Optional.empty(), "", false).join();
        LinkProperties single = context.createSecretLink(dir + "/a", false, Optional.empty(), Optional.empty(), "", false).join();

        context.unShareReadAccessWith(PathUtil.get(dir), Collections.emptySet()).join();

        UserContext fromBoth = UserContext.fromSecretLinkV2(both.toLinkString(context.signer.publicKeyHash),
                () -> Futures.of(""), network.clear(), crypto).join();
        Assert.assertTrue(fromBoth.getByPath(dir + "/a").join().isPresent());
        Assert.assertTrue(fromBoth.getByPath(dir + "/b").join().isPresent());
        Assert.assertTrue(UserContext.fromSecretLinkV2(single.toLinkString(context.signer.publicKeyHash),
                () -> Futures.of(""), network.clear(), crypto).join().getByPath(dir + "/a").join().isPresent());
    }

    private static boolean uploadTo(UserContext holder, String path, String filename) {
        try {
            Optional<FileWrapper> dir = holder.getByPath(path).join();
            if (dir.isEmpty() || ! dir.get().isWritable())
                return false;
            byte[] data = "data".getBytes();
            dir.get().uploadOrReplaceFile(filename, AsyncReader.build(data), data.length, holder.network, crypto, () -> false, l -> {}).join();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
