package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.sql.*;
import java.util.*;

public class WriterDataTests {

    @Test
    public void tolerateLoopsInOwnedKeys() {
        Crypto crypto = Main.initCrypto();
        Hasher hasher = crypto.hasher;
        DeletableContentAddressedStorage dht = new RAMStorage(hasher);
        MutablePointers mutable = UserRepository.build(dht, new JdbcIpnsAndSocial(Main.buildEphemeralSqlite(), new SqliteCommands()));

        SigningKeyPair pairA = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash pubA = ContentAddressedStorage.hashKey(pairA.publicSigningKey);
        TransactionId test = dht.startTransaction(pubA).join();
        SigningPrivateKeyAndPublicHash signerA = new SigningPrivateKeyAndPublicHash(pubA, pairA.secretSigningKey);

        SigningKeyPair pairB = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash pubB = ContentAddressedStorage.hashKey(pairB.publicSigningKey);
        SigningPrivateKeyAndPublicHash signerB = new SigningPrivateKeyAndPublicHash(pubB, pairB.secretSigningKey);

        WriterData wdA = IpfsTransaction.call(pubA, tid -> WriterData.createEmpty(pubA, signerA, dht, hasher, tid), dht).join();
        WriterData wdB = IpfsTransaction.call(pubB, tid -> WriterData.createEmpty(pubB, signerB, dht, hasher, tid), dht).join();

        WriterData wdA2 = wdA.addOwnedKey(pubA, signerA, OwnerProof.build(signerB, pubA), dht, hasher).join();
        wdA2.commit(pubA, signerA, MaybeMultihash.empty(), Optional.empty(), mutable, dht, hasher, test).join();
        CommittedWriterData bCurrentCwd = wdB.commit(pubB, signerB, MaybeMultihash.empty(), Optional.empty(), mutable, dht, hasher, test).join().get(pubB);
        MaybeMultihash bCurrent = bCurrentCwd.hash;

        CommittedWriterData.Retriever retriever = (h, s) -> DeletableContentAddressedStorage.getWriterData(Collections.emptyList(), h, s, false, dht);
        Set<PublicKeyHash> ownedByA1 = DeletableContentAddressedStorage.getOwnedKeysRecursive(pubA, pubA, mutable, retriever, dht, hasher).join();
        Set<PublicKeyHash> ownedByB1 = DeletableContentAddressedStorage.getOwnedKeysRecursive(pubB, pubB, mutable, retriever, dht, hasher).join();

        Assert.assertTrue(ownedByA1.size() == 2);
        Assert.assertTrue(ownedByB1.size() == 1);

        WriterData wdB2 = wdB.addOwnedKey(pubB, signerB, OwnerProof.build(signerA, pubB), dht, hasher).join();
        wdB2.commit(pubB, signerB, bCurrent, bCurrentCwd.sequence, mutable, dht, hasher, test).join();

        Set<PublicKeyHash> ownedByA2 = DeletableContentAddressedStorage.getOwnedKeysRecursive(pubA, pubA, mutable, retriever, dht, hasher).join();
        Set<PublicKeyHash> ownedByB2 = DeletableContentAddressedStorage.getOwnedKeysRecursive(pubB, pubB, mutable, retriever, dht, hasher).join();

        Assert.assertTrue(ownedByA2.size() == 2);
        Assert.assertTrue(ownedByB2.size() == 2);
    }
}
