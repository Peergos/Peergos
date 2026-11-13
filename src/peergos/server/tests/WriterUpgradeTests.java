package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.mldsa.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.*;
import peergos.server.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.net.*;
import java.nio.file.*;
import java.util.*;

import static peergos.server.tests.PeergosNetworkUtils.*;

/** Existing Ed25519 writers upgrading in place to composite keys on their owner's next write */
public class WriterUpgradeTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Args args = UserTests.buildArgs().with("useIPFS", "false");
    private static UserService service;
    private static NetworkAccess network;
    private static final Random random = new Random(11);

    private static boolean hybridBefore;

    @BeforeClass
    public static void init() {
        hybridBefore = SigningKeyPair.HYBRID_WRITERS;
        service = Main.PKI_INIT.main(args).localApi;
        network = NetworkAccess.buildBuffered(service.storage, service.bats, service.coreNode, service.account,
                service.mutable, 0, service.social, service.controller, service.usage, service.serverMessages,
                crypto.hasher, Arrays.asList("peergos"), false);
    }

    @After
    public void reset() {
        SigningKeyPair.HYBRID_WRITERS = hybridBefore;
    }

    private static byte[] read(UserContext context, Path path) {
        FileWrapper file = context.getByPath(path).join().get();
        AsyncReader in = file.getInputStream(context.network, crypto, x -> {}).join();
        return Serialize.readFully(in, file.getFileProperties().size).join();
    }

    private static byte[] upload(UserContext context, Path dir, String name, int size) {
        byte[] data = new byte[size];
        random.nextBytes(data);
        context.getByPath(dir).join().get()
                .uploadOrReplaceFile(name, AsyncReader.build(data), data.length, context.network, crypto, () -> false, x -> {}).join();
        return data;
    }

    private static Optional<HybridEd25519MLDSAPublicKey> upgradedKey(PublicKeyHash owner, PublicKeyHash writer) {
        byte[] pointer = service.mutable.getPointer(owner, writer).join().get();
        if (! PointerKeys.isCompositeSigned(pointer))
            return Optional.empty();
        PublicSigningKey key = PointerKeys.pointerKey(owner, writer, pointer, service.storage).join();
        PointerUpdate target = PointerKeys.decode(key, pointer).join();
        WriterData wd = WriterData.getWriterData(owner, (Cid) target.updated.get(), target.sequence, service.storage).join().props.get();
        Assert.assertEquals(ContentAddressedStorage.hashKey(key), wd.controller);
        return Optional.of((HybridEd25519MLDSAPublicKey) key);
    }

    private static WriterData writerData(PublicKeyHash owner, PublicKeyHash writer) {
        PointerUpdate target = service.mutable.getPointerTarget(owner, writer, service.storage).join();
        return WriterData.getWriterData(owner, (Cid) target.updated.get(), target.sequence, service.storage).join().props.get();
    }

    private static Set<PublicKeyHash> ownedBy(PublicKeyHash owner, PublicKeyHash parent) {
        return DeletableContentAddressedStorage.getDirectOwnedKeys(owner, parent, service.mutable,
                (h, s) -> ContentAddressedStorage.getWriterData(owner, h, s, service.storage), service.storage, crypto.hasher).join();
    }

    /** A CRQC can forge an Ed25519 owner proof, so only the parent an upgraded writer names can claim it */
    private static Set<PublicKeyHash> claimedVia(PublicKeyHash owner, PublicKeyHash claimant, SigningPrivateKeyAndPublicHash child) {
        WriterData root = new WriterData(claimant, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Map.of("child", OwnerProof.build(child, claimant).join()), Optional.empty(), Optional.empty(), Optional.empty());
        Cid rootHash = crypto.hasher.hash(root.serialize(), false).join();
        return DeletableContentAddressedStorage.getDirectOwnedKeys(owner, claimant, MaybeMultihash.of(rootHash),
                (h, seq) -> Futures.of(new CommittedWriterData(MaybeMultihash.of(h), root, seq)),
                w -> service.mutable.getPointer(owner, w), service.storage, crypto.hasher).join();
    }

    @Test
    public void upgradedWriterNamesItsParent() {
        SigningKeyPair.HYBRID_WRITERS = false;
        String password = generatePassword();
        UserContext before = ensureSignedUp(generateUsername(random), password, network.clear(), crypto);
        PublicKeyHash owner = before.signer.publicKeyHash;
        Path home = PathUtil.get(before.username);
        Path a = home.resolve("a");
        Path b = a.resolve("b");
        before.getUserRoot().join().mkdir("a", before.network, false, before.mirrorBatId(), crypto).join();
        before.getByPath(a).join().get().mkdir("b", before.network, false, before.mirrorBatId(), crypto).join();
        before.shareWriteAccessWith(b, Collections.emptySet()).join();
        PublicKeyHash root = before.getUserRoot().join().writer();
        PublicKeyHash bWriter = before.getByPath(b).join().get().writer();
        Assert.assertNotEquals(root, bWriter);
        SigningPrivateKeyAndPublicHash notUpgraded = before.getByPath(b).join().get().signingPair();
        Assert.assertTrue("a valid Ed25519 proof counts before the upgrade",
                claimedVia(owner, owner, notUpgraded.blockSigner()).contains(bWriter));

        SigningKeyPair.HYBRID_WRITERS = true;
        UserContext context = ensureSignedUp(before.username, password, network.clear(), crypto);
        upload(context, home, "r.bin", 100);
        byte[] inB = upload(context, b, "b.bin", 100);
        Assert.assertTrue(upgradedKey(owner, root).isPresent());
        Assert.assertTrue(upgradedKey(owner, bWriter).isPresent());
        Assert.assertEquals(Optional.of(owner), writerData(owner, root).parent);
        Assert.assertEquals(Optional.of(root), writerData(owner, bWriter).parent);
        Assert.assertTrue(ownedBy(owner, owner).contains(root));
        Assert.assertTrue(ownedBy(owner, root).contains(bWriter));

        // once upgraded, the same kind of proof under any other writer is ignored
        SigningPrivateKeyAndPublicHash bSigner = context.getByPath(b).join().get().signingPair();
        Assert.assertFalse(claimedVia(owner, owner, bSigner.blockSigner()).contains(bWriter));

        // granting write access to a moves b's writing space under a's new writer, and b names it
        context.shareWriteAccessWith(a, Collections.emptySet()).join();
        PublicKeyHash aWriter = context.getByPath(a).join().get().writer();
        Assert.assertNotEquals(root, aWriter);
        Assert.assertEquals(bWriter, context.getByPath(b).join().get().writer());
        Assert.assertEquals(Optional.of(aWriter), writerData(owner, bWriter).parent);
        Assert.assertTrue(ownedBy(owner, aWriter).contains(bWriter));
        Assert.assertFalse(ownedBy(owner, root).contains(bWriter));

        byte[] after = upload(context, b, "after.bin", 100);
        UserContext again = ensureSignedUp(before.username, password, network.clear(), crypto);
        Assert.assertArrayEquals(inB, read(again, b.resolve("b.bin")));
        Assert.assertArrayEquals(after, read(again, b.resolve("after.bin")));
    }

    @Test
    public void ownerUpgradesOnWrite() {
        ownerUpgradesOnWrite(network);
    }

    /** Over http, so the server checks the signatures on every block write, not just on pointers */
    @Test
    public void ownerUpgradesOnWriteOverHttp() throws Exception {
        NetworkAccess http = Builder.buildJavaNetworkAccess(new URI("http://localhost:" + args.getArg("port")).toURL(),
                false, Optional.empty(), Optional.empty()).join();
        ownerUpgradesOnWrite(http);
    }

    private void ownerUpgradesOnWrite(NetworkAccess network) {
        SigningKeyPair.HYBRID_WRITERS = false;
        String password = generatePassword();
        UserContext before = ensureSignedUp(generateUsername(random), password, network.clear(), crypto);
        UserContext sharee = ensureSignedUp(generateUsername(random), generatePassword(), network.clear(), crypto);
        PublicKeyHash owner = before.signer.publicKeyHash;
        Path home = PathUtil.get(before.username);
        byte[] old = upload(before, home, "old.bin", 2000);
        friendBetweenGroups(Arrays.asList(before), Arrays.asList(sharee));
        Path team = home.resolve("team");
        before.getUserRoot().join().mkdir("team", before.network, false, before.mirrorBatId(), crypto).join();
        before.shareWriteAccessWith(team, Collections.singleton(sharee.username)).join();
        PublicKeyHash root = before.getUserRoot().join().writer();
        PublicKeyHash teamWriter = before.getByPath(team).join().get().writer();
        Assert.assertTrue(root.isIdentity());
        Assert.assertTrue(upgradedKey(owner, root).isEmpty());
        MaybeMultihash beforeUpgrade = service.mutable.getPointerTarget(owner, root, service.storage).join().updated;

        SigningKeyPair.HYBRID_WRITERS = true;
        // someone else writing doesn't upgrade the owner's writer
        byte[] fromSharee = upload(sharee, team, "from-sharee.bin", 3000);
        Assert.assertTrue(upgradedKey(owner, teamWriter).isEmpty());

        UserContext context = ensureSignedUp(before.username, password, network.clear(), crypto);
        byte[] first = upload(context, home, "first.bin", 5000);
        HybridEd25519MLDSAPublicKey key = upgradedKey(owner, root).get();
        Assert.assertEquals(PublicSigningKey.fromCbor(CborObject.fromByteArray(root.getHash())), key.ed25519);

        byte[] second = upload(context, home, "second.bin", 6 * 1024 * 1024);
        Assert.assertEquals(key, upgradedKey(owner, root).get());

        byte[] inTeam = upload(context, team, "owner.bin", 1000);
        Assert.assertTrue(upgradedKey(owner, teamWriter).isPresent());
        // the sharee derives the same composite key, so can carry on writing
        byte[] fromShareeAfter = upload(sharee, team, "from-sharee-after.bin", 3000);

        // an Ed25519 signed pointer can't undo the upgrade
        SigningPrivateKeyAndPublicHash signer = context.getUserRoot().join().signingPair();
        Assert.assertTrue(signer.isUpgradable());
        PointerUpdate current = service.mutable.getPointerTarget(owner, root, service.storage).join();
        PointerUpdate rollback = new PointerUpdate(current.updated, beforeUpgrade, PointerUpdate.increment(current.sequence));
        byte[] edSigned = signer.blockSigner().secret.signMessage(rollback.serialize(), SigningDomain.POINTER).join();
        try {
            service.mutable.setPointer(owner, root, edSigned).join();
            Assert.fail("Ed25519 signed pointer accepted after upgrade");
        } catch (Exception expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("must stay signed by its composite key"));
        }

        // a fresh login verifies the upgraded pointers and reads everything back
        UserContext again = ensureSignedUp(before.username, password, network.clear(), crypto);
        Assert.assertArrayEquals(old, read(again, home.resolve("old.bin")));
        Assert.assertArrayEquals(first, read(again, home.resolve("first.bin")));
        Assert.assertArrayEquals(second, read(again, home.resolve("second.bin")));
        Assert.assertArrayEquals(inTeam, read(again, team.resolve("owner.bin")));
        Assert.assertArrayEquals(fromSharee, read(again, team.resolve("from-sharee.bin")));
        Assert.assertArrayEquals(fromShareeAfter, read(again, team.resolve("from-sharee-after.bin")));

        // the key block is linked from the WriterData, so gc keeps it
        service.gc.collect(x -> Futures.of(true));
        Assert.assertTrue(service.storage.get(owner, ContentAddressedStorage.hashKey(key).target, Optional.empty()).join().isPresent());
        Assert.assertArrayEquals(first, read(ensureSignedUp(before.username, password, network.clear(), crypto), home.resolve("first.bin")));
    }
}
