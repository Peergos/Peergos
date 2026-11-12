package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;

import static peergos.server.tests.PeergosNetworkUtils.*;

/** Writers with composite Ed25519 + ML-DSA keys, which are stored as blocks and referred to by hash. */
public class HybridWriterTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Args args = UserTests.buildArgs().with("useIPFS", "false");
    private static UserService service;
    private static NetworkAccess network;
    private static final Random random = new Random(7);

    @BeforeClass
    public static void init() {
        SigningKeyPair.HYBRID_WRITERS = true;
        service = Main.PKI_INIT.main(args).localApi;
        network = NetworkAccess.buildBuffered(service.storage, service.bats, service.coreNode, service.account,
                service.mutable, 0, service.social, service.controller, service.usage, service.serverMessages,
                crypto.hasher, Arrays.asList("peergos"), false);
    }

    @AfterClass
    public static void reset() {
        SigningKeyPair.HYBRID_WRITERS = false;
    }

    private static void assertHybrid(PublicKeyHash writer, UserContext context) {
        Assert.assertFalse("Writer key is inlined", writer.isIdentity());
        PublicSigningKey key = context.network.dhtClient.getSigningKey(context.signer.publicKeyHash, writer).join().get();
        Assert.assertEquals(PublicSigningKey.Type.HybridEd25519MLDSA, key.type());
    }

    private static byte[] read(UserContext context, Path path) {
        FileWrapper file = context.getByPath(path).join().get();
        AsyncReader in = file.getInputStream(context.network, crypto, x -> {}).join();
        return Serialize.readFully(in, file.getFileProperties().size).join();
    }

    @Test
    public void signUpWriteShareAndRevoke() {
        String password = generatePassword();
        UserContext owner = ensureSignedUp(generateUsername(random), password, network.clear(), crypto);
        UserContext sharee = ensureSignedUp(generateUsername(random), generatePassword(), network.clear(), crypto);
        FileWrapper root = owner.getUserRoot().join();
        assertHybrid(root.writer(), owner);

        byte[] small = new byte[1000];
        random.nextBytes(small);
        byte[] big = new byte[6 * 1024 * 1024];
        random.nextBytes(big);
        root.uploadOrReplaceFile("small.bin", AsyncReader.build(small), small.length, owner.network, crypto, () -> false, x -> {}).join();
        owner.getUserRoot().join()
                .uploadOrReplaceFile("big.bin", AsyncReader.build(big), big.length, owner.network, crypto, () -> false, x -> {}).join();

        friendBetweenGroups(Arrays.asList(owner), Arrays.asList(sharee));
        String folderName = "team";
        Path folder = PathUtil.get(owner.username, folderName);
        owner.getUserRoot().join().mkdir(folderName, owner.network, false, owner.mirrorBatId(), crypto).join();
        owner.shareWriteAccessWith(folder, Collections.singleton(sharee.username)).join();
        FileWrapper sharedDir = owner.getByPath(folder).join().get();
        Assert.assertNotEquals(root.writer(), sharedDir.writer());
        assertHybrid(sharedDir.writer(), owner);

        byte[] fromSharee = new byte[3000];
        random.nextBytes(fromSharee);
        sharee.getByPath(folder).join().get()
                .uploadOrReplaceFile("from-sharee.bin", AsyncReader.build(fromSharee), fromSharee.length,
                        sharee.network, crypto, () -> false, x -> {}).join();

        owner.unShareWriteAccess(folder, sharee.username).join();
        FileWrapper rotated = owner.getByPath(folder).join().get();
        Assert.assertNotEquals(sharedDir.writer(), rotated.writer());
        assertHybrid(rotated.writer(), owner);

        // the key blocks are reachable through the owner proofs, so gc keeps them
        service.gc.collect(x -> Futures.of(true));
        for (PublicKeyHash writer : List.of(root.writer(), rotated.writer()))
            Assert.assertTrue("Key block collected for " + writer,
                    service.storage.get(owner.signer.publicKeyHash, writer.target, Optional.empty()).join().isPresent());

        // a fresh login can verify every writer's pointers and read everything back
        UserContext again = PeergosNetworkUtils.ensureSignedUp(owner.username, password, network.clear(), crypto);
        Assert.assertArrayEquals(small, read(again, PathUtil.get(owner.username, "small.bin")));
        Assert.assertArrayEquals(big, read(again, PathUtil.get(owner.username, "big.bin")));
        Assert.assertArrayEquals(fromSharee, read(again, folder.resolve("from-sharee.bin")));
    }
}
