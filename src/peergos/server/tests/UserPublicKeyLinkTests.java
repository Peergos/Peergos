package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.crypto.asymmetric.curve25519.*;
import peergos.server.crypto.random.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.UserPublicKeyLink;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.time.LocalDate;
import java.util.*;


public class UserPublicKeyLinkTests {
    private final ContentAddressedStorage ipfs;

    {
        ipfs = new FileContentAddressedStorage(PathUtil.get("blockstore"),
                    JdbcTransactionStore.build(Main.buildEphemeralSqlite(), new SqliteCommands()), (a, b, c, d) -> Futures.of(true), Main.initCrypto().hasher);
    }

    private final List<Multihash> id;

    public UserPublicKeyLinkTests() throws Exception {
        id = Arrays.asList(ipfs.id().join());
    }

    @BeforeClass
    public static void init() {
        PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, new Ed25519Java());
    }

    private PublicKeyHash putPublicSigningKey(SigningKeyPair user) throws Exception {
        PublicKeyHash owner = ContentAddressedStorage.hashKey(user.publicSigningKey);
        return ipfs.putSigningKey(
                user.secretSigningKey.signMessage(user.publicSigningKey.serialize()).join(),
                owner,
                user.publicSigningKey, ipfs.startTransaction(owner).join()).get();
    }

    @Test
    public void createInitial() throws Exception {
        SigningKeyPair user = SigningKeyPair.random(new SafeRandomJava(), new Ed25519Java());
        UserPublicKeyLink.Claim node = UserPublicKeyLink.Claim.build("someuser", user.secretSigningKey, LocalDate.now().plusYears(2), id).join();

        PublicKeyHash owner = putPublicSigningKey(user);
        UserPublicKeyLink upl = new UserPublicKeyLink(owner, node);
        testSerialization(upl);
    }

    public void testSerialization(UserPublicKeyLink link) {
        byte[] serialized1 = link.serialize();
        UserPublicKeyLink upl2 = UserPublicKeyLink.fromCbor(CborObject.fromByteArray(serialized1));
        byte[] serialized2 = upl2.serialize();
        if (!Arrays.equals(serialized1, serialized2))
            throw new IllegalStateException("toByteArray not inverse of fromByteArray!");
    }

    @Test
    public void createChain() throws Exception {
        SigningKeyPair oldUser = SigningKeyPair.random(new SafeRandomJava(), new Ed25519Java());
        SigningKeyPair newUser = SigningKeyPair.random(new SafeRandomJava(), new Ed25519Java());
        PublicKeyHash oldHash = putPublicSigningKey(oldUser);
        PublicKeyHash newHash = putPublicSigningKey(newUser);

        SigningPrivateKeyAndPublicHash oldSigner = new SigningPrivateKeyAndPublicHash(oldHash, oldUser.secretSigningKey);
        SigningPrivateKeyAndPublicHash newSigner = new SigningPrivateKeyAndPublicHash(newHash, newUser.secretSigningKey);

        List<UserPublicKeyLink> links = UserPublicKeyLink.createChain(oldSigner, newSigner, "someuser", LocalDate.now().plusYears(2), id).join();
        links.forEach(link -> testSerialization(link));
    }

    @Test
    public void repeatedPassword() throws Exception {
        SigningKeyPair oldUser = SigningKeyPair.random(new SafeRandomJava(), new Ed25519Java());
        SigningKeyPair newUser = SigningKeyPair.random(new SafeRandomJava(), new Ed25519Java());
        PublicKeyHash oldHash = putPublicSigningKey(oldUser);
        PublicKeyHash newHash = putPublicSigningKey(newUser);

        SigningPrivateKeyAndPublicHash oldSigner = new SigningPrivateKeyAndPublicHash(oldHash, oldUser.secretSigningKey);
        SigningPrivateKeyAndPublicHash newSigner = new SigningPrivateKeyAndPublicHash(newHash, newUser.secretSigningKey);

        String username = "someuser";
        LocalDate expiry = LocalDate.now().plusYears(2);
        List<UserPublicKeyLink> initial = UserPublicKeyLink.createInitial(oldSigner, username, expiry, id).join();
        List<UserPublicKeyLink> newPassword = UserPublicKeyLink.createChain(oldSigner, newSigner, username, expiry, id).join();
        List<UserPublicKeyLink> changed = UserPublicKeyLink.merge(initial, newPassword, ipfs).join();
        List<UserPublicKeyLink> backToOldPassword = UserPublicKeyLink.createChain(newSigner, oldSigner, username, expiry, id).join();
        List<UserPublicKeyLink> finalChain = Arrays.asList(changed.get(0), backToOldPassword.get(0), backToOldPassword.get(1));
        try {
            UserPublicKeyLink.merge(changed, finalChain, ipfs).join();
        } catch (Exception e) {
            return;
        }
        throw new IllegalStateException("Should have failed!");
    }
}
