package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;

import static peergos.server.tests.PeergosNetworkUtils.ensureSignedUp;
import static peergos.server.tests.PeergosNetworkUtils.generateUsername;

public class OrphanedWriterTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Args args = UserTests.buildArgs().with("useIPFS", "false");
    private static UserService service;
    private static RequestCountingStorage counter;
    private static NetworkAccess network;
    private static final Random random = new Random(28);

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
        counter = new RequestCountingStorage(service.storage);
        network = NetworkAccess.buildBuffered(counter, service.bats, service.coreNode, service.account,
                service.mutable, 0, service.social, service.controller, service.usage, service.serverMessages,
                crypto.hasher, Arrays.asList("peergos"), false);
    }

    @Test
    public void nothingToHeal() {
        UserContext context = ensureSignedUp(generateUsername(random), "password", network, crypto);
        mkdir(context, PathUtil.get(context.username), "a");
        Assert.assertEquals(0, (int) context.removeOrphanedWriters().join());
    }

    @Test
    public void healOrphanOfIdentity() {
        UserContext context = ensureSignedUp(generateUsername(random), "password", network, crypto);
        PublicKeyHash orphan = createOrphan(context, context.signer);
        Assert.assertTrue(isOwnedBy(context, context.signer.publicKeyHash, orphan));

        Assert.assertEquals(1, (int) context.removeOrphanedWriters().join());
        Assert.assertFalse(isOwnedBy(context, context.signer.publicKeyHash, orphan));
    }

    @Test
    public void healOrphanInNestedWritingSpace() {
        UserContext context = ensureSignedUp(generateUsername(random), "password", network, crypto);
        String username = context.username;
        Path a = PathUtil.get(username, "a");
        Path b = a.resolve("b");
        Path c = b.resolve("c");
        mkdir(context, PathUtil.get(username), "a");
        mkdir(context, a, "b");
        mkdir(context, b, "c");
        // each of these puts the dir in its own writing space, nested inside its parent's
        context.shareWriteAccessWith(a, new HashSet<>()).join();
        context.shareWriteAccessWith(b, new HashSet<>()).join();
        context.shareWriteAccessWith(c, new HashSet<>()).join();
        // a file with its own writing space, whose link node claims to be a file
        uploadFile(context, b, "file.txt");
        context.shareWriteAccessWith(b.resolve("file.txt"), new HashSet<>()).join();

        SigningPrivateKeyAndPublicHash deepParent = context.getByPath(c).join().get().signingPair();
        SigningPrivateKeyAndPublicHash shallowParent = context.getByPath(a).join().get().signingPair();
        Assert.assertNotEquals(deepParent.publicKeyHash, shallowParent.publicKeyHash);
        PublicKeyHash deepOrphan = createOrphan(context, deepParent);
        PublicKeyHash shallowOrphan = createOrphan(context, shallowParent);

        Assert.assertEquals(2, (int) context.removeOrphanedWriters().join());
        Assert.assertFalse(isOwnedBy(context, deepParent.publicKeyHash, deepOrphan));
        Assert.assertFalse(isOwnedBy(context, shallowParent.publicKeyHash, shallowOrphan));

        // the drive is still intact
        Assert.assertTrue(context.getByPath(c).join().isPresent());
        Assert.assertTrue(context.getByPath(b.resolve("file.txt")).join().isPresent());
    }

    @Test
    public void healOrphanUnderAFileWritingSpace() {
        UserContext context = ensureSignedUp(generateUsername(random), "password", network, crypto);
        String username = context.username;
        Path dir = PathUtil.get(username, "a");
        mkdir(context, PathUtil.get(username), "a");
        uploadFile(context, dir, "file.txt");
        Path file = dir.resolve("file.txt");
        // a writable link puts the file in its own writing space, behind a link node that claims to be a file,
        // so only the shared with cache can find its signing key
        context.createSecretLink("/" + file, true, Optional.empty(), Optional.<Integer>empty(), "", false).join();

        SigningPrivateKeyAndPublicHash fileSigner = context.getByPath(file).join().get().signingPair();
        Assert.assertNotEquals(fileSigner.publicKeyHash, context.getUserRoot().join().writer());
        PublicKeyHash orphan = createOrphan(context, fileSigner);

        Assert.assertEquals(1, (int) context.removeOrphanedWriters().join());
        Assert.assertFalse(isOwnedBy(context, fileSigner.publicKeyHash, orphan));
    }

    @Test
    public void prunesUnrelatedWritingSpaces() {
        // an unrelated writing space, deeper than the one we need, must not be walked
        int withoutSiblings = healCost(0);
        int withSiblings = healCost(25);
        Assert.assertTrue("Walked into unrelated writing spaces: " + withoutSiblings + " -> " + withSiblings,
                withSiblings - withoutSiblings < 10);
    }

    private static int healCost(int unrelatedDirs) {
        UserContext context = ensureSignedUp(generateUsername(random), "password", network, crypto);
        String username = context.username;
        Path a = PathUtil.get(username, "a");
        Path b = a.resolve("b");
        Path c = b.resolve("c");
        mkdir(context, PathUtil.get(username), "a");
        mkdir(context, a, "b");
        mkdir(context, b, "c");
        context.shareWriteAccessWith(a, new HashSet<>()).join();
        context.shareWriteAccessWith(b, new HashSet<>()).join();
        context.shareWriteAccessWith(c, new HashSet<>()).join();

        Path unrelated = PathUtil.get(username, "unrelated");
        mkdir(context, PathUtil.get(username), "unrelated");
        context.shareWriteAccessWith(unrelated, new HashSet<>()).join();
        for (int i = 0; i < unrelatedDirs; i++)
            mkdir(context, unrelated, "dir-" + i);

        PublicKeyHash orphan = createOrphan(context, context.getByPath(c).join().get().signingPair());

        counter.reset();
        Assert.assertEquals(1, (int) context.removeOrphanedWriters().join());
        return counter.requestTotal();
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

    private static boolean isOwnedBy(UserContext context, PublicKeyHash parent, PublicKeyHash child) {
        return UserContext.getWriterData(context.network, context.signer.publicKeyHash, parent).join()
                .props.get()
                .directOwnedKeys(context.signer.publicKeyHash, context.network.dhtClient, crypto.hasher).join()
                .contains(child);
    }

    /** Authorise a new writer, but never commit anything to it, which is what an interrupted authorisation leaves.
     */
    private static PublicKeyHash createOrphan(UserContext context, SigningPrivateKeyAndPublicHash parent) {
        PublicKeyHash owner = context.signer.publicKeyHash;
        NetworkAccess network = context.network;
        SigningKeyPair pair = SigningKeyPair.random(crypto.random, crypto.signer);
        List<PublicKeyHash> result = new ArrayList<>();
        network.synchronizer.applyComplexUpdate(owner, parent, (v, c) -> parent.secret
                .signMessage(pair.publicSigningKey.serialize())
                .thenCompose(signature -> IpfsTransaction.call(owner, tid -> network.dhtClient
                        .putSigningKey(signature, owner, parent.publicKeyHash, pair.publicSigningKey, tid)
                        .thenCompose(hash -> {
                            result.add(hash);
                            SigningPrivateKeyAndPublicHash orphan =
                                    new SigningPrivateKeyAndPublicHash(hash, pair.secretSigningKey);
                            CommittedWriterData cwd = v.get(parent);
                            return OwnerProof.build(orphan, parent.publicKeyHash)
                                    .thenCompose(proof -> cwd.props.get().addOwnedKeyAndCommit(owner, parent, proof,
                                            cwd.hash, cwd.sequence, network, c, tid));
                        }), network.dhtClient))).join();
        PublicKeyHash orphan = result.get(0);
        Assert.assertFalse(network.mutable.getPointerTarget(owner, orphan, network.dhtClient).join().updated.isPresent());
        return orphan;
    }
}
